//! Splits combined image samplers (`sampler2D`, `samplerCube`, ...) into an
//! image and a sampler, the only form WGSL (and naga's SPIR-V reader) accepts.
//!
//! Every `UniformConstant` variable of sampled-image type keeps its id, name
//! and decorations but becomes the image; a new sampler variable gets the same
//! descriptor set and binding (the binding pass in `lib.rs` moves it to its
//! own slot). Function parameters of sampled-image pointer type are split the
//! same way, function types and calls follow, and each load of a split
//! variable or parameter becomes two loads joined by `OpSampledImage` with the
//! original result id, so the sampling instructions stay untouched. This is
//! what SPIRV-Tools' `--split-combined-image-sampler` does, restricted to the
//! shapes glslang emits for GLSL without sampler arrays.

use crate::spirv::{decoration, op, storage, Inst, Module};
use crate::Error;
use std::collections::HashMap;

pub fn run(module: &mut Module) -> Result<(), Error> {
    // Sampled-image type -> image type.
    let sampled_types: HashMap<u32, u32> = module
        .insts
        .iter()
        .filter(|i| i.op == op::TYPE_SAMPLED_IMAGE)
        .map(|i| (i.ops[0], i.ops[1]))
        .collect();
    if sampled_types.is_empty() {
        return Ok(());
    }
    if module
        .insts
        .iter()
        .any(|i| matches!(i.op, op::TYPE_ARRAY | op::TYPE_RUNTIME_ARRAY) && sampled_types.contains_key(&i.ops[1]))
    {
        return Err(Error::spirv("arrays of combined image samplers are not supported"));
    }
    // UniformConstant pointer to a sampled image -> image type, in declaration order so the
    // new declarations (and the WGSL) do not depend on hash order.
    let pointer_list: Vec<(u32, u32)> = module
        .insts
        .iter()
        .filter(|i| i.op == op::TYPE_POINTER && i.ops[1] == storage::UNIFORM_CONSTANT)
        .filter_map(|i| sampled_types.get(&i.ops[2]).map(|&image| (i.ops[0], image)))
        .collect();
    if pointer_list.is_empty() {
        return Ok(());
    }
    let pointer_types: HashMap<u32, u32> = pointer_list.iter().copied().collect();

    let sampler_ty = module.type_sampler();
    let sampler_ptr = module.type_pointer(storage::UNIFORM_CONSTANT, sampler_ty);
    let mut image_ptrs: HashMap<u32, u32> = HashMap::new();
    for &(pointer, image) in &pointer_list {
        let image_ptr = module.type_pointer(storage::UNIFORM_CONSTANT, image);
        image_ptrs.insert(pointer, image_ptr);
    }

    // Function types: (ptr to sampled image) -> (ptr to image, ptr to sampler).
    let function_types: Vec<u32> = module
        .insts
        .iter()
        .filter(|i| i.op == op::TYPE_FUNCTION && i.ops[2..].iter().any(|p| pointer_types.contains_key(p)))
        .map(|i| i.ops[0])
        .collect();
    for id in function_types {
        let index = module
            .find(op::TYPE_FUNCTION, |i| i.ops[0] == id)
            .expect("listed above");
        let old = module.insts[index].ops.clone();
        let mut ops = vec![old[0], old[1]];
        for &param in &old[2..] {
            match image_ptrs.get(&param) {
                Some(&image_ptr) => ops.extend([image_ptr, sampler_ptr]),
                None => ops.push(param),
            }
        }
        module.insts[index].ops = ops;
        module.move_global_to_end(index);
    }

    // Variables: retype to the image, add the sampler.
    let variables: Vec<(u32, u32)> = module
        .insts
        .iter()
        .filter(|i| i.op == op::VARIABLE && pointer_types.contains_key(&i.ops[0]))
        .map(|i| (i.ops[1], i.ops[0]))
        .collect();
    let mut samplers: HashMap<u32, u32> = HashMap::new();
    for (var, pointer) in variables {
        let index = module.find(op::VARIABLE, |i| i.ops[1] == var).expect("listed above");
        if module.insts[index].ops[2] != storage::UNIFORM_CONSTANT {
            return Err(Error::spirv(format!(
                "combined image sampler %{var} is not UniformConstant"
            )));
        }
        module.insts[index].ops[0] = image_ptrs[&pointer];
        module.move_global_to_end(index);
        let sampler = module.fresh_id();
        module.append_globals([Inst::new(
            op::VARIABLE,
            vec![sampler_ptr, sampler, storage::UNIFORM_CONSTANT],
        )]);
        let copied: Vec<Inst> = module
            .decorations(var)
            .filter(|d| matches!(d.ops[1], decoration::DESCRIPTOR_SET | decoration::BINDING))
            .map(|d| {
                let mut ops = d.ops.clone();
                ops[0] = sampler;
                Inst::new(op::DECORATE, ops)
            })
            .collect();
        module.add_annotations(copied);
        let name = module.name(var).unwrap_or_else(|| format!("sampler_{var}"));
        module.add_name(sampler, &format!("{name}_sampler"));
        module.extend_interface(var, &[sampler]);
        samplers.insert(var, sampler);
    }

    // Parameters: split in place.
    let mut split_params = Vec::new();
    let mut index = 0;
    while index < module.insts.len() {
        let inst = &module.insts[index];
        if inst.op == op::FUNCTION_PARAMETER {
            if let Some(&image_ptr) = image_ptrs.get(&inst.ops[0]) {
                let param = inst.ops[1];
                module.insts[index].ops[0] = image_ptr;
                let sampler = module.fresh_id();
                module
                    .insts
                    .insert(index + 1, Inst::new(op::FUNCTION_PARAMETER, vec![sampler_ptr, sampler]));
                samplers.insert(param, sampler);
                split_params.push((param, sampler));
                index += 1;
            }
        }
        index += 1;
    }
    for (param, sampler) in split_params {
        if let Some(name) = module.name(param) {
            module.add_name(sampler, &format!("{name}_sampler"));
        }
    }

    // Calls pass both halves; loads become image + sampler + OpSampledImage.
    let mut index = 0;
    while index < module.insts.len() {
        let inst = &module.insts[index];
        match inst.op {
            op::FUNCTION_CALL if inst.ops[3..].iter().any(|a| samplers.contains_key(a)) => {
                let old = inst.ops.clone();
                let mut ops = old[..3].to_vec();
                for &arg in &old[3..] {
                    ops.push(arg);
                    if let Some(&sampler) = samplers.get(&arg) {
                        ops.push(sampler);
                    }
                }
                module.insts[index].ops = ops;
            }
            op::LOAD if samplers.contains_key(&inst.ops[2]) => {
                let (sampled_ty, result, pointer) = (inst.ops[0], inst.ops[1], inst.ops[2]);
                if inst.ops.len() > 3 {
                    return Err(Error::spirv("memory operands on a combined image sampler load"));
                }
                let image_ty = *sampled_types
                    .get(&sampled_ty)
                    .ok_or_else(|| Error::spirv("load of a combined image sampler with another type"))?;
                let image = module.fresh_id();
                let sampler = module.fresh_id();
                let code = [
                    Inst::new(op::LOAD, vec![image_ty, image, pointer]),
                    Inst::new(op::LOAD, vec![sampler_ty, sampler, samplers[&pointer]]),
                    Inst::new(op::SAMPLED_IMAGE, vec![sampled_ty, result, image, sampler]),
                ];
                module.insts.splice(index..index + 1, code);
                index += 2;
            }
            _ => {}
        }
        index += 1;
    }

    // Anything else touching a split variable or parameter is a shape this pass does not know.
    let split: Vec<u32> = samplers.keys().copied().collect();
    for id in split {
        let unsupported = module.mentions(id, |_, i| match i.op {
            op::NAME | op::DECORATE | op::ENTRY_POINT | op::FUNCTION_CALL => true,
            op::VARIABLE | op::FUNCTION_PARAMETER => i.ops[1] == id,
            op::LOAD => i.ops[2] == id,
            _ => false,
        });
        if let Some(at) = unsupported {
            return Err(Error::spirv(format!(
                "unsupported use of combined image sampler %{id} by opcode {}",
                module.insts[at].op
            )));
        }
    }
    Ok(())
}
