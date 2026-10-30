//! Removes a stage input the shader never reads ([`crate::frontend`], lenient mode).
//!
//! GL links a fragment input that the vertex stage does not write as long as the fragment stage
//! never reads it (1.21.11 `rendertype_text_background.fsh` declares `texCoord0`); a Vulkan or
//! WebGPU interface needs every input provided. Dropping the declaration changes nothing the
//! shader computes. An input that is read stays, and the link then fails with the vanilla
//! message.

use crate::spirv::{op, storage, Inst, Module};
use crate::Error;

/// Removes input variable `var` when nothing but its name, decorations and the entry point
/// interface refer to it. Returns whether it was removed.
pub fn run(module: &mut Module, var: u32) -> Result<bool, Error> {
    let index = module
        .find(op::VARIABLE, |i| i.ops[1] == var)
        .ok_or_else(|| Error::Link(format!("no variable %{var}")))?;
    if module.insts[index].ops[2] != storage::INPUT {
        return Err(Error::Link(format!("%{var} is not a stage input")));
    }
    let used = module.mentions(var, |_, i| {
        matches!(i.op, op::NAME | op::DECORATE | op::ENTRY_POINT) || (i.op == op::VARIABLE && i.ops[1] == var)
    });
    if used.is_some() {
        return Ok(false);
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
    // Later passes look instructions up by index: drop the placeholders now.
    module.insts.retain(|i| i.op != op::NOP);
    Ok(true)
}
