//! GL depth range at the vertex output ([`crate::Options::gl_depth_remap`]).
//!
//! 1.21.11 builds its projection matrices for GL's clip space, z in `-w..w`; WebGPU clips and
//! maps depth from `0..w`. Before every return of a vertex entry point the position becomes
//! `z = (z + w) / 2`: the same NDC depth `(z/w + 1) / 2` GL's viewport transform produces and the
//! same clip volume. (26.2 and 26.3 ask the device: `isZZeroToOne` is true for WebGPU and their
//! matrices already target `0..w`.)

use crate::spirv::{decoration, op, storage, Inst, Module, BUILT_IN_POSITION};
use crate::Error;

pub fn run(module: &mut Module) -> Result<(), Error> {
    let (model, entry) = module.entry_point()?;
    if model != 0 {
        return Ok(());
    }
    // The position: a BuiltIn Position output, or member m of the gl_PerVertex output block.
    let mut target = None;
    for inst in module
        .insts
        .iter()
        .filter(|i| i.op == op::VARIABLE && i.ops[2] == storage::OUTPUT)
    {
        let var = inst.ops[1];
        let Some(pointee) = module
            .def(inst.ops[0])
            .filter(|d| d.op == op::TYPE_POINTER)
            .map(|d| d.ops[2])
        else {
            continue;
        };
        if module.decoration(var, decoration::BUILT_IN) == Some(BUILT_IN_POSITION) {
            target = Some((var, None, pointee));
            break;
        }
        let member = module
            .member_decorations(pointee)
            .find(|d| d.ops.get(2) == Some(&decoration::BUILT_IN) && d.ops.get(3) == Some(&BUILT_IN_POSITION))
            .map(|d| d.ops[1]);
        if let Some(member) = member {
            let ty = module
                .def(pointee)
                .filter(|d| d.op == op::TYPE_STRUCT)
                .and_then(|d| d.ops.get(1 + member as usize).copied())
                .ok_or_else(|| Error::spirv("gl_PerVertex is not a struct"))?;
            target = Some((var, Some(member), ty));
            break;
        }
    }
    let Some((var, member, vec4)) = target else {
        // A vertex stage that never writes the position has no depth to remap.
        return Ok(());
    };
    let float = module
        .def(vec4)
        .filter(|d| d.op == op::TYPE_VECTOR && d.ops[2] == 4)
        .map(|d| d.ops[1])
        .filter(|&f| module.def(f).is_some_and(|d| d.op == op::TYPE_FLOAT && d.ops[1] == 32))
        .ok_or_else(|| Error::spirv("the vertex position is not a vec4 of 32-bit floats"))?;
    let half = module.constant(float, 0.5f32.to_bits());
    let output_vec4 = module.type_pointer(storage::OUTPUT, vec4);
    let member = match member {
        Some(m) => {
            let int = module.type_int(32, true);
            Some(module.constant(int, m))
        }
        None => None,
    };

    let mut index = module
        .insts
        .iter()
        .position(|i| i.op == op::FUNCTION && i.ops[1] == entry)
        .ok_or_else(|| Error::spirv("entry point function not found"))?;
    while index < module.insts.len() && module.insts[index].op != op::FUNCTION_END {
        match module.insts[index].op {
            op::RETURN => {
                let mut code = Vec::new();
                let pointer = match member {
                    Some(constant) => {
                        let pointer = module.fresh_id();
                        code.push(Inst::new(op::ACCESS_CHAIN, vec![output_vec4, pointer, var, constant]));
                        pointer
                    }
                    None => var,
                };
                let [position, z, w, sum, depth, remapped] = std::array::from_fn(|_| module.fresh_id());
                code.push(Inst::new(op::LOAD, vec![vec4, position, pointer]));
                code.push(Inst::new(op::COMPOSITE_EXTRACT, vec![float, z, position, 2]));
                code.push(Inst::new(op::COMPOSITE_EXTRACT, vec![float, w, position, 3]));
                code.push(Inst::new(op::F_ADD, vec![float, sum, z, w]));
                code.push(Inst::new(op::F_MUL, vec![float, depth, sum, half]));
                code.push(Inst::new(
                    op::COMPOSITE_INSERT,
                    vec![vec4, remapped, depth, position, 2],
                ));
                code.push(Inst::new(op::STORE, vec![pointer, remapped]));
                let added = code.len();
                module.insts.splice(index..index, code);
                index += added;
            }
            op::RETURN_VALUE => return Err(Error::spirv("entry point returns a value")),
            _ => {}
        }
        index += 1;
    }
    Ok(())
}
