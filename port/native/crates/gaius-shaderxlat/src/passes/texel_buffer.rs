//! Texel buffers (`samplerBuffer`, `isamplerBuffer`, `usamplerBuffer`) become
//! read-only storage buffers of 32-bit words.
//!
//! WGSL has no texel buffer type. Each buffer variable is retyped to
//! `struct { data: array<u32> }` in the `StorageBuffer` class at the same
//! descriptor set and binding, and every `texelFetch` (`OpImageFetch` on a
//! load of the variable) becomes a word load plus the decode of the buffer's
//! texel format, which the backend knows from the pipeline's uniform
//! description: `texelFetch(buf, i)` returns `(texel, 0, 0, 1)` like a
//! one-channel texel buffer does. The backend binds the buffer's bytes
//! unchanged (padded to a multiple of 4 bytes).

use crate::spirv::{decoration, op, storage, Inst, Module, DIM_BUFFER};
use crate::{Error, TexelFormat};
use std::collections::HashMap;

pub fn run(module: &mut Module, formats: &[(u32, TexelFormat)]) -> Result<(), Error> {
    let image_types: HashMap<u32, u32> = module
        .insts
        .iter()
        .filter(|i| i.op == op::TYPE_IMAGE && i.ops[2] == DIM_BUFFER)
        .map(|i| (i.ops[0], i.ops[1]))
        .collect();
    if image_types.is_empty() {
        return Ok(());
    }
    // Sampled-image types over a buffer image map to the image's sampled type too.
    let mut handle_types = image_types.clone();
    for inst in module.insts.iter().filter(|i| i.op == op::TYPE_SAMPLED_IMAGE) {
        if let Some(&sampled) = image_types.get(&inst.ops[1]) {
            handle_types.insert(inst.ops[0], sampled);
        }
    }
    let pointer_types: HashMap<u32, u32> = module
        .insts
        .iter()
        .filter(|i| i.op == op::TYPE_POINTER && i.ops[1] == storage::UNIFORM_CONSTANT)
        .filter_map(|i| handle_types.get(&i.ops[2]).map(|&sampled| (i.ops[0], sampled)))
        .collect();
    // Buffer variable -> (sampled scalar type, texel format).
    let mut order = Vec::new();
    let mut variables: HashMap<u32, (u32, TexelFormat)> = HashMap::new();
    for inst in module.insts.iter().filter(|i| i.op == op::VARIABLE) {
        if let Some(&sampled) = pointer_types.get(&inst.ops[0]) {
            let var = inst.ops[1];
            let binding = module
                .decoration(var, decoration::BINDING)
                .ok_or_else(|| Error::spirv(format!("texel buffer %{var} has no binding")))?;
            let format = formats
                .iter()
                .find(|(b, _)| *b == binding)
                .map(|(_, f)| *f)
                .ok_or_else(|| Error::spirv(format!("texel buffer at binding {binding} has no texel format")))?;
            variables.insert(var, (sampled, format));
            order.push(var);
        }
    }
    if variables.is_empty() {
        return Ok(());
    }

    // Shared declarations.
    let u32_ty = module.type_int(32, false);
    let i32_ty = module.type_int(32, true);
    let array = module.fresh_id();
    let block = module.fresh_id();
    let block_ptr = module.fresh_id();
    module.append_globals([
        Inst::new(op::TYPE_RUNTIME_ARRAY, vec![array, u32_ty]),
        Inst::new(op::TYPE_STRUCT, vec![block, array]),
        Inst::new(op::TYPE_POINTER, vec![block_ptr, storage::STORAGE_BUFFER, block]),
    ]);
    let word_ptr = module.type_pointer(storage::STORAGE_BUFFER, u32_ty);
    module.add_annotations([
        Inst::new(op::DECORATE, vec![array, decoration::ARRAY_STRIDE, 4]),
        Inst::new(op::DECORATE, vec![block, decoration::BLOCK]),
        Inst::new(op::MEMBER_DECORATE, vec![block, 0, decoration::OFFSET, 0]),
        Inst::new(op::MEMBER_DECORATE, vec![block, 0, decoration::NON_WRITABLE]),
    ]);
    module.add_name(block, "GaiusTexelBuffer");
    let mut member_name = vec![block, 0];
    member_name.extend(crate::spirv::string_words("data"));
    let name_at = module
        .insts
        .iter()
        .rposition(|i| i.op == op::NAME)
        .map(|i| i + 1)
        .unwrap_or(0);
    module.insts.insert(name_at, Inst::new(op::MEMBER_NAME, member_name));

    let mut constants = Constants::default();
    for &var in &order {
        let index = module
            .insts
            .iter()
            .position(|i| i.op == op::VARIABLE && i.ops[1] == var)
            .expect("variable found above");
        module.insts[index].ops[0] = block_ptr;
        module.insts[index].ops[2] = storage::STORAGE_BUFFER;
        module.insts[index].ops.truncate(3);
        module.move_global_to_end(index);
        // naga takes the access mode of a Block in StorageBuffer from the variable.
        module.add_annotations([Inst::new(op::DECORATE, vec![var, decoration::NON_WRITABLE])]);
    }

    // Loads of a buffer variable and OpImage of such loads are handles.
    let mut handles: HashMap<u32, u32> = HashMap::new();
    let mut fetches = Vec::new();
    for inst in module.insts.iter_mut() {
        match inst.op {
            op::LOAD if variables.contains_key(&inst.ops[2]) => {
                handles.insert(inst.ops[1], inst.ops[2]);
                *inst = Inst::new(op::NOP, Vec::new());
            }
            op::IMAGE if handles.contains_key(&inst.ops[2]) => {
                let var = handles[&inst.ops[2]];
                handles.insert(inst.ops[1], var);
                *inst = Inst::new(op::NOP, Vec::new());
            }
            op::IMAGE_FETCH if handles.contains_key(&inst.ops[2]) => {
                // SignExtend / ZeroExtend (SPIR-V 1.4, emitted by glslang for integer
                // images) carry no operands and describe what the decode does anyway.
                if inst.ops.len() > 5 || inst.ops.get(4).is_some_and(|mask| mask & !0x3000 != 0) {
                    return Err(Error::spirv("texelFetch on a texel buffer with image operands"));
                }
                fetches.push(inst.clone());
            }
            _ => {}
        }
    }

    for fetch in fetches {
        let (result_ty, result, handle, coord) = (fetch.ops[0], fetch.ops[1], fetch.ops[2], fetch.ops[3]);
        let var = handles[&handle];
        let (scalar, format) = variables[&var];
        let mut code = Vec::new();
        let texel = decode(
            module,
            &mut constants,
            &mut code,
            var,
            coord,
            format,
            scalar,
            word_ptr,
            u32_ty,
            i32_ty,
        )?;
        let zero = constants.get(module, scalar, 0);
        let one_bits = if module.def(scalar).map(|d| d.op) == Some(op::TYPE_INT) {
            1
        } else {
            1.0f32.to_bits()
        };
        let one = constants.get(module, scalar, one_bits);
        code.push(Inst::new(
            op::COMPOSITE_CONSTRUCT,
            vec![result_ty, result, texel, zero, zero, one],
        ));
        // Constants were appended to the globals, which shifted the function body.
        let index = module
            .insts
            .iter()
            .position(|i| i == &fetch)
            .expect("fetch still present");
        module.insts.splice(index..index + 1, code);
    }

    let generated: Vec<u32> = handles.keys().copied().collect();
    for id in generated {
        if let Some(at) = module.mentions(id, |_, i| matches!(i.op, op::NAME | op::DECORATE)) {
            return Err(Error::spirv(format!(
                "unsupported use of a texel buffer handle by opcode {}",
                module.insts[at].op
            )));
        }
    }
    for &var in variables.keys() {
        let unsupported = module.mentions(var, |_, i| match i.op {
            op::NAME | op::DECORATE | op::ENTRY_POINT => true,
            op::VARIABLE => i.ops[1] == var,
            op::ACCESS_CHAIN => i.ops[2] == var && i.ops[0] == word_ptr,
            _ => false,
        });
        if let Some(at) = unsupported {
            return Err(Error::spirv(format!(
                "unsupported use of texel buffer %{var} by opcode {}",
                module.insts[at].op
            )));
        }
    }
    // naga rejects buffer images even when unused: drop the now dead types.
    // Pointers first, then sampled images, then images: each only refers to the next.
    let mut dead: Vec<u32> = Vec::new();
    for group in [
        pointer_types.keys().copied().collect::<Vec<_>>(),
        handle_types
            .keys()
            .filter(|id| !image_types.contains_key(id))
            .copied()
            .collect(),
        image_types.keys().copied().collect(),
    ] {
        let mut group = group;
        group.sort_unstable();
        dead.extend(group);
    }
    for id in dead {
        let declaration = |i: &Inst| {
            i.result_id() == Some(id) && matches!(i.op, op::TYPE_POINTER | op::TYPE_SAMPLED_IMAGE | op::TYPE_IMAGE)
        };
        let used = module.mentions(id, |_, i| declaration(i) || matches!(i.op, op::NAME | op::DECORATE));
        if used.is_none() {
            for inst in module
                .insts
                .iter_mut()
                .filter(|i| declaration(i) || (i.op == op::NAME && i.ops[0] == id))
            {
                *inst = Inst::new(op::NOP, Vec::new());
            }
        }
    }
    Ok(())
}

/// Cache of `OpConstant`s created by the pass, keyed by (type, bits).
#[derive(Default)]
struct Constants {
    ids: HashMap<(u32, u32), u32>,
}

impl Constants {
    fn get(&mut self, module: &mut Module, ty: u32, bits: u32) -> u32 {
        *self.ids.entry((ty, bits)).or_insert_with(|| module.constant(ty, bits))
    }
}

/// Emits the word load and format decode of texel `coord` of buffer `var`;
/// returns the id of the decoded scalar of type `scalar`.
#[allow(clippy::too_many_arguments)]
fn decode(
    module: &mut Module,
    constants: &mut Constants,
    code: &mut Vec<Inst>,
    var: u32,
    coord: u32,
    format: TexelFormat,
    scalar: u32,
    word_ptr: u32,
    u32_ty: u32,
    i32_ty: u32,
) -> Result<u32, Error> {
    let (bits, signed): (u32, bool) = match format {
        TexelFormat::R8Sint => (8, true),
        TexelFormat::R8Uint => (8, false),
        TexelFormat::R16Sint => (16, true),
        TexelFormat::R16Uint => (16, false),
        TexelFormat::R32Sint | TexelFormat::R32Uint | TexelFormat::R32Float => (32, false),
    };
    let per_word = 32 / bits;
    let zero_i = constants.get(module, i32_ty, 0);
    let mut emit = |module: &mut Module, opcode: u16, ty: u32, operands: &[u32]| {
        let id = module.fresh_id();
        let mut ops = vec![ty, id];
        ops.extend_from_slice(operands);
        code.push(Inst::new(opcode, ops));
        id
    };
    let word_index = if per_word == 1 {
        coord
    } else {
        let shift = constants.get(module, i32_ty, per_word.trailing_zeros());
        emit(module, op::SHIFT_RIGHT_ARITHMETIC, i32_ty, &[coord, shift])
    };
    let pointer = emit(module, op::ACCESS_CHAIN, word_ptr, &[var, zero_i, word_index]);
    let word = emit(module, op::LOAD, u32_ty, &[pointer]);
    let value = if per_word == 1 {
        word
    } else {
        // Little-endian texel position inside the word, in bits.
        let lane_mask = constants.get(module, i32_ty, per_word - 1);
        let lane = emit(module, op::BITWISE_AND, i32_ty, &[coord, lane_mask]);
        let width = constants.get(module, i32_ty, bits);
        let offset = emit(module, op::I_MUL, i32_ty, &[lane, width]);
        if signed {
            // (int(word) << (32 - bits - offset)) >> (32 - bits), arithmetic.
            let top = constants.get(module, i32_ty, 32 - bits);
            let neg = constants.get(module, i32_ty, (-(1i32)) as u32);
            let minus = emit(module, op::I_MUL, i32_ty, &[offset, neg]);
            let left = emit(module, op::I_ADD, i32_ty, &[minus, top]);
            let as_int = emit(module, op::BITCAST, i32_ty, &[word]);
            let shifted = emit(module, op::SHIFT_LEFT_LOGICAL, i32_ty, &[as_int, left]);
            emit(module, op::SHIFT_RIGHT_ARITHMETIC, i32_ty, &[shifted, top])
        } else {
            let mask = constants.get(module, u32_ty, (1u32 << bits) - 1);
            let shifted = emit(module, op::SHIFT_RIGHT_LOGICAL, u32_ty, &[word, offset]);
            emit(module, op::BITWISE_AND, u32_ty, &[shifted, mask])
        }
    };
    let value_ty = if per_word != 1 && signed { i32_ty } else { u32_ty };
    if value_ty == scalar {
        return Ok(value);
    }
    match module.def(scalar).map(|d| (d.op, d.ops.clone())) {
        Some((op::TYPE_INT, _)) | Some((22, _)) => Ok(emit(module, op::BITCAST, scalar, &[value])),
        _ => Err(Error::spirv("texel buffer sampled type is not a 32-bit scalar")),
    }
}
