//! Splits output arrays (`layout(location = 0) out vec4 coeff[N]`, the OIT
//! transmittance targets) into one output per location.
//!
//! WGSL entry point outputs cannot be arrays, and the shader indexes the array
//! with loop counters, so the array itself becomes a `Private` variable (same
//! id, every access chain retyped to `Private` pointers) and each element gets
//! its own `Output` variable at location `L + k`, written from the private
//! copy before every return of the entry point. Outputs are only observable
//! when the invocation ends, so the shader computes exactly the same values.

use crate::spirv::{decoration, op, storage, Inst, Module};
use crate::Error;
use std::collections::{HashMap, HashSet};

pub fn run(module: &mut Module) -> Result<(), Error> {
    let (_, entry) = module.entry_point()?;
    // Output pointer types to arrays: pointer -> (array type, element type, length).
    let mut arrays: HashMap<u32, (u32, u32, u32)> = HashMap::new();
    for inst in module
        .insts
        .iter()
        .filter(|i| i.op == op::TYPE_POINTER && i.ops[1] == storage::OUTPUT)
    {
        if let Some(array) = module.def(inst.ops[2]).filter(|d| d.op == op::TYPE_ARRAY) {
            let length = module
                .constant_value(array.ops[2])
                .ok_or_else(|| Error::spirv("output array with a non-constant length"))?;
            arrays.insert(inst.ops[0], (array.ops[0], array.ops[1], length));
        }
    }
    let targets: Vec<(u32, u32)> = module
        .insts
        .iter()
        .filter(|i| i.op == op::VARIABLE && i.ops[2] == storage::OUTPUT && arrays.contains_key(&i.ops[0]))
        .map(|i| (i.ops[1], i.ops[0]))
        .collect();
    for (var, pointer) in targets {
        let (array_ty, element_ty, length) = arrays[&pointer];
        let Some(location) = module.decoration(var, decoration::LOCATION) else {
            // Built-in arrays (gl_ClipDistance and friends) stay as they are.
            continue;
        };
        if module
            .decorations(var)
            .any(|d| !matches!(d.ops[1], decoration::LOCATION))
        {
            return Err(Error::spirv(format!(
                "output array %{var} has decorations besides Location"
            )));
        }

        // The array becomes private.
        let private_array_ptr = module.type_pointer(storage::PRIVATE, array_ty);
        let index = module.find(op::VARIABLE, |i| i.ops[1] == var).expect("listed above");
        module.insts[index].ops = vec![private_array_ptr, var, storage::PRIVATE];
        module.move_global_to_end(index);
        for inst in module.insts.iter_mut() {
            if inst.op == op::DECORATE && inst.ops[0] == var && inst.ops[1] == decoration::LOCATION {
                *inst = Inst::new(op::NOP, Vec::new());
            }
        }

        // Every pointer derived from it now points into Private storage.
        let mut derived: HashSet<u32> = HashSet::from([var]);
        let mut retype = Vec::new();
        for inst in module.insts.iter() {
            if matches!(inst.op, op::ACCESS_CHAIN | op::IN_BOUNDS_ACCESS_CHAIN) && derived.contains(&inst.ops[2]) {
                derived.insert(inst.ops[1]);
                retype.push((inst.ops[1], inst.ops[0]));
            }
        }
        for (result, old_ty) in retype {
            let pointee = module
                .def(old_ty)
                .filter(|d| d.op == op::TYPE_POINTER && d.ops[1] == storage::OUTPUT)
                .map(|d| d.ops[2])
                .ok_or_else(|| Error::spirv("access chain into an output array without an Output pointer type"))?;
            let private_ptr = module.type_pointer(storage::PRIVATE, pointee);
            // type_pointer may have appended a global and shifted the bodies.
            let at = module
                .insts
                .iter()
                .position(|i| matches!(i.op, op::ACCESS_CHAIN | op::IN_BOUNDS_ACCESS_CHAIN) && i.ops[1] == result)
                .expect("access chain present");
            module.insts[at].ops[0] = private_ptr;
        }
        for &id in &derived {
            let unsupported = module.mentions(id, |_, i| {
                matches!(
                    i.op,
                    op::NAME
                        | op::DECORATE
                        | op::ENTRY_POINT
                        | op::VARIABLE
                        | op::LOAD
                        | op::STORE
                        | op::ACCESS_CHAIN
                        | op::IN_BOUNDS_ACCESS_CHAIN
                )
            });
            if let Some(at) = unsupported {
                return Err(Error::spirv(format!(
                    "unsupported use of output array %{var} by opcode {}",
                    module.insts[at].op
                )));
            }
        }

        // One Output variable per element.
        let output_ptr = module.type_pointer(storage::OUTPUT, element_ty);
        let private_ptr = module.type_pointer(storage::PRIVATE, element_ty);
        let int_ty = module.type_int(32, true);
        let base_name = module.name(var).unwrap_or_else(|| format!("output_{var}"));
        let mut outputs = Vec::new();
        for k in 0..length {
            let output = module.fresh_id();
            let constant = module.constant(int_ty, k);
            module.append_globals([Inst::new(op::VARIABLE, vec![output_ptr, output, storage::OUTPUT])]);
            module.add_annotations([Inst::new(
                op::DECORATE,
                vec![output, decoration::LOCATION, location + k],
            )]);
            module.add_name(output, &format!("{base_name}_{k}"));
            outputs.push((output, constant));
        }
        let ids: Vec<u32> = outputs.iter().map(|(o, _)| *o).collect();
        module.extend_interface(var, &ids);

        // Copy out before every return of the entry point.
        let mut index = module
            .insts
            .iter()
            .position(|i| i.op == op::FUNCTION && i.ops[1] == entry)
            .ok_or_else(|| Error::spirv("entry point function not found"))?;
        while index < module.insts.len() && module.insts[index].op != op::FUNCTION_END {
            match module.insts[index].op {
                op::RETURN => {
                    let mut code = Vec::new();
                    for &(output, constant) in &outputs {
                        let element = module.fresh_id();
                        let value = module.fresh_id();
                        code.push(Inst::new(op::ACCESS_CHAIN, vec![private_ptr, element, var, constant]));
                        code.push(Inst::new(op::LOAD, vec![element_ty, value, element]));
                        code.push(Inst::new(op::STORE, vec![output, value]));
                    }
                    let added = code.len();
                    module.insts.splice(index..index, code);
                    index += added;
                }
                op::RETURN_VALUE => return Err(Error::spirv("entry point returns a value")),
                _ => {}
            }
            index += 1;
        }
    }
    Ok(())
}
