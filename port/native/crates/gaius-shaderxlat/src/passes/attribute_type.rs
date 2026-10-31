//! Reads a vertex input with the base type its attribute delivers ([`crate::frontend`], lenient
//! mode).
//!
//! 1.21.11 feeds integer UVs (`SHORT` overlay and lightmap coordinates) through
//! `glVertexAttribIFormat`, and some of its shaders still declare them as float vectors
//! (`rendertype_entity_alpha.vsh`: `in vec2 UV1;`): undefined in GL, a pipeline validation error
//! in WebGPU (the attribute's base type must match the shader's). The input is redeclared with
//! the attribute's integer type and every load converts back to the declared type
//! (`OpConvertSToF`/`OpConvertUToF`, or `OpBitcast` between signed and unsigned), so the shader
//! sees the integer values as floats, which is what its arithmetic expects.

use crate::frontend::BaseType;
use crate::spirv::{op, storage, Inst, Module};
use crate::Error;
use std::collections::HashSet;

/// Scalar base type and component count of a numeric scalar or vector type.
fn numeric(module: &Module, ty: u32) -> Option<(BaseType, u32, u32)> {
    let def = module.def(ty)?;
    let (component, count) = match def.op {
        op::TYPE_VECTOR => (def.ops[1], def.ops[2]),
        op::TYPE_FLOAT | op::TYPE_INT => (ty, 1),
        _ => return None,
    };
    let scalar = module.def(component)?;
    let base = match (scalar.op, scalar.ops.get(1), scalar.ops.get(2)) {
        (op::TYPE_FLOAT, Some(32), _) => BaseType::Float,
        (op::TYPE_INT, Some(32), Some(1)) => BaseType::Sint,
        (op::TYPE_INT, Some(32), Some(0)) => BaseType::Uint,
        _ => return None,
    };
    Some((base, component, count))
}

/// Redeclares input `var` with base type `base` when the shader declares a float input for an
/// integer attribute, or the other signedness of an integer one. Returns whether it changed;
/// a float attribute read as an integer input is left alone (no GL rule defines it).
pub fn run(module: &mut Module, var: u32, base: BaseType) -> Result<bool, Error> {
    let index = module
        .find(op::VARIABLE, |i| i.ops[1] == var)
        .ok_or_else(|| Error::Link(format!("no variable %{var}")))?;
    let pointer = module.insts[index].ops[0];
    let pointee = module
        .def(pointer)
        .filter(|d| d.op == op::TYPE_POINTER && d.ops[1] == storage::INPUT)
        .map(|d| d.ops[2])
        .ok_or_else(|| Error::Link(format!("%{var} is not a stage input")))?;
    let name = module.name(var).unwrap_or_else(|| format!("%{var}"));
    let Some((declared, component, count)) = numeric(module, pointee) else {
        return Err(Error::Link(format!(
            "vertex input {name} is not a 32-bit scalar or vector"
        )));
    };
    let convert = match (declared, base) {
        (a, b) if a == b => return Ok(false),
        (BaseType::Float, BaseType::Sint) => op::CONVERT_S_TO_F,
        (BaseType::Float, BaseType::Uint) => op::CONVERT_U_TO_F,
        (BaseType::Sint, BaseType::Uint) | (BaseType::Uint, BaseType::Sint) => op::BITCAST,
        (_, BaseType::Float) => return Ok(false),
        _ => unreachable!("all base type pairs are covered"),
    };

    let new_component = module.type_int(32, base == BaseType::Sint);
    let new_type = if count == 1 {
        new_component
    } else {
        module.type_vector(new_component, count)
    };
    let new_pointer = module.type_pointer(storage::INPUT, new_type);
    let component_pointer = module.type_pointer(storage::INPUT, new_component);

    // The variable now points at the integer type (moved after the types it needs).
    let index = module.find(op::VARIABLE, |i| i.ops[1] == var).expect("found above");
    module.insts[index].ops[0] = new_pointer;
    module.move_global_to_end(index);

    // Component access chains (`UV1.x`) point at the integer component.
    let mut pointers: HashSet<u32> = HashSet::from([var]);
    for inst in module.insts.iter_mut() {
        if matches!(inst.op, op::ACCESS_CHAIN | op::IN_BOUNDS_ACCESS_CHAIN) && inst.ops[2] == var {
            if inst.ops.len() != 4 {
                return Err(Error::Link(format!(
                    "unsupported access chain into vertex input {name}"
                )));
            }
            inst.ops[0] = component_pointer;
            pointers.insert(inst.ops[1]);
        }
    }
    for &id in &pointers {
        let other = module.mentions(id, |_, i| match i.op {
            op::NAME | op::DECORATE | op::ENTRY_POINT => true,
            op::VARIABLE => i.ops[1] == var,
            op::LOAD => i.ops[2] == id,
            op::ACCESS_CHAIN | op::IN_BOUNDS_ACCESS_CHAIN => i.ops[1] == id || i.ops[2] == var,
            _ => false,
        });
        if let Some(at) = other {
            return Err(Error::Link(format!(
                "unsupported use of vertex input {name} by opcode {}",
                module.insts[at].op
            )));
        }
    }

    // Every load reads the integer value and converts it to the declared type.
    let mut at = 0;
    while at < module.insts.len() {
        let inst = &module.insts[at];
        if inst.op == op::LOAD && pointers.contains(&inst.ops[2]) {
            let (loaded_type, result) = (inst.ops[0], inst.ops[1]);
            let integer_type = if loaded_type == pointee {
                new_type
            } else if loaded_type == component {
                new_component
            } else {
                return Err(Error::Link(format!("unexpected load type of vertex input {name}")));
            };
            let raw = module.fresh_id();
            let load = &mut module.insts[at];
            load.ops[0] = integer_type;
            load.ops[1] = raw;
            module
                .insts
                .insert(at + 1, Inst::new(convert, vec![loaded_type, result, raw]));
            at += 1;
        }
        at += 1;
    }
    Ok(true)
}
