//! Replaces `gl_BaseVertex` with the constant 0.
//!
//! WGSL has no base-vertex built-in and naga refuses `BuiltIn BaseVertex`
//! (`Unsupported builtin value: BaseVertex`), so a core shader that reads it fails its
//! pipeline. Resource packs do: the server packs that draw custom player-display models
//! (ModelEngine style) recover the raw vertex number of an entity quad with
//! `gl_VertexIndex - gl_BaseVertex`; vanilla 26.3 shaders never read it.
//!
//! WebGPU's `vertex_index` already includes the draw's base vertex, so with the constant the
//! shader sees `base_vertex + index` where Vulkan/GL shaders see `index`. A shader that only
//! uses the difference for geometry it special-cases (the player-display parts) renders the
//! rest of the scene unchanged; those special parts may decode the wrong quad corner when
//! the draw's base vertex is not a multiple of the shader's modulus. Everything else about
//! the pipeline compiles and runs, which beats dropping the whole pack.

use crate::spirv::{decoration, op, Inst, Module};
use crate::Error;

const BASE_VERTEX: u32 = 4424;
const CAPABILITY: u16 = 17;
const CAPABILITY_DRAW_PARAMETERS: u32 = 4427;
const COPY_OBJECT: u16 = 83;

pub fn run(module: &mut Module) -> Result<(), Error> {
    let vars: Vec<u32> = module
        .insts
        .iter()
        .filter(|i| i.op == op::DECORATE && i.ops.get(1) == Some(&decoration::BUILT_IN) && i.ops.get(2) == Some(&BASE_VERTEX))
        .map(|i| i.ops[0])
        .collect();
    if vars.is_empty() {
        return Ok(());
    }
    for var in vars {
        let other = module.mentions(var, |_, i| {
            matches!(i.op, op::NAME | op::DECORATE | op::ENTRY_POINT | op::LOAD)
                || (i.op == op::VARIABLE && i.ops[1] == var)
        });
        if other.is_some() {
            return Err(Error::spirv("gl_BaseVertex used other than by a plain load"));
        }
        let mut types: Vec<u32> = module
            .insts
            .iter()
            .filter(|i| i.op == op::LOAD && i.ops[2] == var)
            .map(|i| i.ops[0])
            .collect();
        types.sort_unstable();
        types.dedup();
        // `constant` inserts into the global section, which moves every later index: declare
        // them all before rewriting by position.
        let zeros: Vec<(u32, u32)> = types.into_iter().map(|ty| (ty, module.constant(ty, 0))).collect();
        for inst in module.insts.iter_mut() {
            if inst.op == op::LOAD && inst.ops[2] == var {
                let (ty, id) = (inst.ops[0], inst.ops[1]);
                let zero = zeros.iter().find(|(t, _)| *t == ty).map(|(_, c)| *c).unwrap();
                *inst = Inst::new(COPY_OBJECT, vec![ty, id, zero]);
            }
        }
        for inst in module.insts.iter_mut() {
            let declares = match inst.op {
                op::VARIABLE => inst.ops[1] == var,
                op::NAME | op::DECORATE => inst.ops[0] == var,
                _ => false,
            };
            if declares {
                *inst = Inst::new(op::NOP, Vec::new());
            }
        }
        module.remove_from_interface(var);
    }
    for inst in module.insts.iter_mut() {
        if inst.op == CAPABILITY && inst.ops.first() == Some(&CAPABILITY_DRAW_PARAMETERS) {
            *inst = Inst::new(op::NOP, Vec::new());
        }
    }
    Ok(())
}
