//! Removes writes of `gl_PointSize`.
//!
//! WebGPU rasterizes every point as one pixel and WGSL has no point size
//! output. `debug_point.vsh` (the debug renderer's point pipelines) is the only
//! vanilla shader writing it; dropping the stores lets naga cull the member of
//! `gl_PerVertex` as it does for the members glslang declares but never
//! writes. Points then render at 1 px instead of `LineWidth` pixels.

use crate::spirv::{op, storage, Inst, Module};
use crate::Error;
use std::collections::HashSet;

const BUILT_IN: u32 = 11;
const POINT_SIZE: u32 = 1;

pub fn run(module: &mut Module) -> Result<(), Error> {
    if module
        .insts
        .iter()
        .any(|i| i.op == op::DECORATE && i.ops[1] == BUILT_IN && i.ops.get(2) == Some(&POINT_SIZE))
    {
        return Err(Error::spirv("gl_PointSize outside an interface block"));
    }
    let members: Vec<(u32, u32)> = module
        .insts
        .iter()
        .filter(|i| i.op == op::MEMBER_DECORATE && i.ops[2] == BUILT_IN && i.ops.get(3) == Some(&POINT_SIZE))
        .map(|i| (i.ops[0], i.ops[1]))
        .collect();
    for (block, member) in members {
        let pointers: HashSet<u32> = module
            .insts
            .iter()
            .filter(|i| i.op == op::TYPE_POINTER && i.ops[1] == storage::OUTPUT && i.ops[2] == block)
            .map(|i| i.ops[0])
            .collect();
        let variables: HashSet<u32> = module
            .insts
            .iter()
            .filter(|i| i.op == op::VARIABLE && pointers.contains(&i.ops[0]))
            .map(|i| i.ops[1])
            .collect();
        let chains: Vec<u32> = module
            .insts
            .iter()
            .filter(|i| {
                matches!(i.op, op::ACCESS_CHAIN | op::IN_BOUNDS_ACCESS_CHAIN)
                    && variables.contains(&i.ops[2])
                    && i.ops.len() == 4
                    && module.constant_value(i.ops[3]) == Some(member)
            })
            .map(|i| i.ops[1])
            .collect();
        for chain in chains {
            let only_stores = module
                .mentions(chain, |_, i| {
                    (i.op == op::STORE && i.ops[0] == chain)
                        || (matches!(i.op, op::ACCESS_CHAIN | op::IN_BOUNDS_ACCESS_CHAIN) && i.ops[1] == chain)
                })
                .is_none();
            if !only_stores {
                return Err(Error::spirv("gl_PointSize is read back"));
            }
            for inst in module.insts.iter_mut() {
                let store = inst.op == op::STORE && inst.ops[0] == chain;
                let def = matches!(inst.op, op::ACCESS_CHAIN | op::IN_BOUNDS_ACCESS_CHAIN) && inst.ops[1] == chain;
                if store || def {
                    *inst = Inst::new(op::NOP, Vec::new());
                }
            }
        }
    }
    Ok(())
}
