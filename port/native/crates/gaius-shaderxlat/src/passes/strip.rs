//! Drops source-level debug information.
//!
//! The vanilla `GlslCompiler` always asks shaderc for debug info, so the
//! modules carry `OpSource` with the full GLSL text, `OpString` file names and
//! an `OpLine` before nearly every instruction, including inside the type
//! section, where naga's SPIR-V reader rejects them. `OpName` and
//! `OpMemberName` stay: they name the WGSL declarations.

use crate::spirv::{op, Module};

pub fn run(module: &mut Module) {
    module.insts.retain(|inst| {
        !matches!(
            inst.op,
            op::SOURCE_CONTINUED
                | op::SOURCE
                | op::SOURCE_EXTENSION
                | op::STRING
                | op::LINE
                | op::NO_LINE
                | op::MODULE_PROCESSED
        )
    });
}
