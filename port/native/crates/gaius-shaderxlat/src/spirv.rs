//! A minimal SPIR-V module editor: enough structure for the WebGPU patches.
//!
//! The module is kept as a flat instruction list in logical layout order. The
//! passes never rename ids: they retype, insert and delete instructions, and
//! append new global declarations at the end of the global section (just
//! before the first `OpFunction`), where every type, constant and variable of
//! the module is already declared. A rewritten global whose operands change
//! (a variable or a function type that must refer to a new type) is moved to
//! that end as well; nothing in the global section refers to variables or
//! function types, so moving them keeps the module in declaration order.

use crate::Error;

pub const MAGIC: u32 = 0x0723_0203;

/// Opcodes used by the passes.
pub mod op {
    pub const NOP: u16 = 0;
    pub const SOURCE_CONTINUED: u16 = 2;
    pub const SOURCE: u16 = 3;
    pub const SOURCE_EXTENSION: u16 = 4;
    pub const NAME: u16 = 5;
    pub const MEMBER_NAME: u16 = 6;
    pub const STRING: u16 = 7;
    pub const LINE: u16 = 8;
    pub const EXT_INST_IMPORT: u16 = 11;
    pub const EXT_INST: u16 = 12;
    pub const ENTRY_POINT: u16 = 15;
    pub const TYPE_VOID: u16 = 19;
    pub const TYPE_BOOL: u16 = 20;
    pub const TYPE_INT: u16 = 21;
    pub const TYPE_FLOAT: u16 = 22;
    pub const TYPE_VECTOR: u16 = 23;
    pub const TYPE_IMAGE: u16 = 25;
    pub const TYPE_SAMPLER: u16 = 26;
    pub const TYPE_SAMPLED_IMAGE: u16 = 27;
    pub const TYPE_ARRAY: u16 = 28;
    pub const TYPE_RUNTIME_ARRAY: u16 = 29;
    pub const TYPE_STRUCT: u16 = 30;
    pub const TYPE_POINTER: u16 = 32;
    pub const TYPE_FUNCTION: u16 = 33;
    pub const CONSTANT: u16 = 43;
    pub const FUNCTION: u16 = 54;
    pub const FUNCTION_PARAMETER: u16 = 55;
    pub const FUNCTION_END: u16 = 56;
    pub const FUNCTION_CALL: u16 = 57;
    pub const VARIABLE: u16 = 59;
    pub const LOAD: u16 = 61;
    pub const STORE: u16 = 62;
    pub const ACCESS_CHAIN: u16 = 65;
    pub const IN_BOUNDS_ACCESS_CHAIN: u16 = 66;
    pub const DECORATE: u16 = 71;
    pub const MEMBER_DECORATE: u16 = 72;
    pub const COMPOSITE_CONSTRUCT: u16 = 80;
    pub const COMPOSITE_EXTRACT: u16 = 81;
    pub const COMPOSITE_INSERT: u16 = 82;
    pub const SAMPLED_IMAGE: u16 = 86;
    pub const IMAGE_FETCH: u16 = 95;
    pub const IMAGE: u16 = 100;
    pub const IMAGE_QUERY_SIZE: u16 = 104;
    pub const CONVERT_S_TO_F: u16 = 111;
    pub const CONVERT_U_TO_F: u16 = 112;
    pub const BITCAST: u16 = 124;
    pub const I_ADD: u16 = 128;
    pub const F_ADD: u16 = 129;
    pub const I_MUL: u16 = 132;
    pub const F_MUL: u16 = 133;
    pub const SHIFT_RIGHT_LOGICAL: u16 = 194;
    pub const SHIFT_RIGHT_ARITHMETIC: u16 = 195;
    pub const SHIFT_LEFT_LOGICAL: u16 = 196;
    pub const BITWISE_AND: u16 = 199;
    pub const LABEL: u16 = 248;
    pub const RETURN: u16 = 253;
    pub const RETURN_VALUE: u16 = 254;
    pub const NO_LINE: u16 = 317;
    pub const MODULE_PROCESSED: u16 = 330;
}

pub mod storage {
    pub const UNIFORM_CONSTANT: u32 = 0;
    pub const INPUT: u32 = 1;
    pub const UNIFORM: u32 = 2;
    pub const OUTPUT: u32 = 3;
    pub const PRIVATE: u32 = 6;
    pub const STORAGE_BUFFER: u32 = 12;
}

pub mod decoration {
    pub const BLOCK: u32 = 2;
    pub const ARRAY_STRIDE: u32 = 6;
    pub const BUILT_IN: u32 = 11;
    pub const NON_WRITABLE: u32 = 24;
    pub const LOCATION: u32 = 30;
    pub const BINDING: u32 = 33;
    pub const DESCRIPTOR_SET: u32 = 34;
    pub const OFFSET: u32 = 35;
}

/// Image dimensionalities (`OpTypeImage` operand 2).
pub const DIM_2D: u32 = 1;
pub const DIM_CUBE: u32 = 3;
/// Image dimensionality `Buffer` (texel buffers).
pub const DIM_BUFFER: u32 = 5;
/// `BuiltIn Position`.
pub const BUILT_IN_POSITION: u32 = 0;

/// One instruction: its opcode and every word after the first.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Inst {
    pub op: u16,
    pub ops: Vec<u32>,
}

impl Inst {
    pub fn new(op: u16, ops: Vec<u32>) -> Self {
        Inst { op, ops }
    }

    /// Result id of the instructions the passes look at (`None` for others).
    pub fn result_id(&self) -> Option<u32> {
        match self.op {
            op::EXT_INST_IMPORT
            | op::TYPE_VOID
            | op::TYPE_BOOL
            | op::TYPE_INT
            | op::TYPE_FLOAT
            | op::TYPE_VECTOR
            | op::TYPE_IMAGE
            | op::TYPE_SAMPLER
            | op::TYPE_SAMPLED_IMAGE
            | op::TYPE_ARRAY
            | op::TYPE_RUNTIME_ARRAY
            | op::TYPE_STRUCT
            | op::TYPE_POINTER
            | op::TYPE_FUNCTION
            | op::LABEL
            | op::STRING => self.ops.first().copied(),
            op::CONSTANT
            | op::FUNCTION
            | op::FUNCTION_PARAMETER
            | op::FUNCTION_CALL
            | op::VARIABLE
            | op::LOAD
            | op::ACCESS_CHAIN
            | op::IN_BOUNDS_ACCESS_CHAIN
            | op::SAMPLED_IMAGE
            | op::IMAGE_FETCH
            | op::IMAGE
            | op::IMAGE_QUERY_SIZE
            | op::COMPOSITE_EXTRACT
            | op::COMPOSITE_INSERT
            | op::CONVERT_S_TO_F
            | op::CONVERT_U_TO_F
            | op::BITCAST
            | op::F_ADD
            | op::F_MUL => self.ops.get(1).copied(),
            _ => None,
        }
    }

    fn is_type_or_global(&self) -> bool {
        matches!(self.op, 19..=39 | 41..=46 | 48..=52 | op::VARIABLE | 322 | 4456 | 5341 | 5344)
    }
}

#[derive(Clone, Debug)]
pub struct Module {
    /// Magic, version, generator, id bound, schema.
    pub header: [u32; 5],
    pub insts: Vec<Inst>,
}

pub fn words_from_bytes(bytes: &[u8]) -> Result<Vec<u32>, Error> {
    if !bytes.len().is_multiple_of(4) {
        return Err(Error::spirv("module length is not a multiple of 4"));
    }
    let mut words: Vec<u32> = bytes
        .as_chunks::<4>()
        .0
        .iter()
        .map(|c| u32::from_le_bytes(*c))
        .collect();
    if words.first() == Some(&MAGIC.swap_bytes()) {
        for word in &mut words {
            *word = word.swap_bytes();
        }
    }
    Ok(words)
}

impl Module {
    pub fn parse(words: &[u32]) -> Result<Module, Error> {
        if words.len() < 5 || words[0] != MAGIC {
            return Err(Error::spirv("not a SPIR-V module"));
        }
        let mut header = [0u32; 5];
        header.copy_from_slice(&words[..5]);
        let mut insts = Vec::with_capacity(words.len() / 4);
        let mut at = 5;
        while at < words.len() {
            let count = (words[at] >> 16) as usize;
            if count == 0 || at + count > words.len() {
                return Err(Error::spirv(format!("malformed instruction at word {at}")));
            }
            insts.push(Inst::new(
                (words[at] & 0xffff) as u16,
                words[at + 1..at + count].to_vec(),
            ));
            at += count;
        }
        Ok(Module { header, insts })
    }

    pub fn emit(&self) -> Vec<u32> {
        let mut out = Vec::with_capacity(5 + self.insts.iter().map(|i| i.ops.len() + 1).sum::<usize>());
        out.extend_from_slice(&self.header);
        for inst in &self.insts {
            if inst.op == op::NOP {
                continue;
            }
            out.push(((inst.ops.len() as u32 + 1) << 16) | inst.op as u32);
            out.extend_from_slice(&inst.ops);
        }
        out
    }

    pub fn fresh_id(&mut self) -> u32 {
        let id = self.header[3];
        self.header[3] += 1;
        id
    }

    /// Index of the first `OpFunction`: the end of the global section.
    pub fn globals_end(&self) -> usize {
        self.insts
            .iter()
            .position(|i| i.op == op::FUNCTION)
            .unwrap_or(self.insts.len())
    }

    pub fn find(&self, opcode: u16, pred: impl Fn(&Inst) -> bool) -> Option<usize> {
        self.insts.iter().position(|i| i.op == opcode && pred(i))
    }

    pub fn def(&self, id: u32) -> Option<&Inst> {
        self.insts.iter().find(|i| i.result_id() == Some(id))
    }

    /// Inserts global declarations at the end of the global section.
    pub fn append_globals(&mut self, insts: impl IntoIterator<Item = Inst>) {
        let end = self.globals_end();
        self.insts.splice(end..end, insts);
    }

    /// Moves the global instruction at `index` to the end of the global section.
    pub fn move_global_to_end(&mut self, index: usize) {
        debug_assert!(self.insts[index].is_type_or_global());
        let inst = std::mem::replace(&mut self.insts[index], Inst::new(op::NOP, Vec::new()));
        self.append_globals([inst]);
    }

    /// `OpTypeInt width signedness`, declared if missing.
    pub fn type_int(&mut self, width: u32, signed: bool) -> u32 {
        let s = signed as u32;
        if let Some(i) = self.find(op::TYPE_INT, |i| i.ops[1] == width && i.ops[2] == s) {
            return self.insts[i].ops[0];
        }
        let id = self.fresh_id();
        self.append_globals([Inst::new(op::TYPE_INT, vec![id, width, s])]);
        id
    }

    /// `OpTypeVector component count`, declared if missing.
    pub fn type_vector(&mut self, component: u32, count: u32) -> u32 {
        if let Some(i) = self.find(op::TYPE_VECTOR, |i| i.ops[1] == component && i.ops[2] == count) {
            return self.insts[i].ops[0];
        }
        let id = self.fresh_id();
        self.append_globals([Inst::new(op::TYPE_VECTOR, vec![id, component, count])]);
        id
    }

    /// `OpTypeFloat width`, declared if missing.
    pub fn type_float(&mut self, width: u32) -> u32 {
        if let Some(i) = self.find(op::TYPE_FLOAT, |i| i.ops[1] == width) {
            return self.insts[i].ops[0];
        }
        let id = self.fresh_id();
        self.append_globals([Inst::new(op::TYPE_FLOAT, vec![id, width])]);
        id
    }

    pub fn type_sampler(&mut self) -> u32 {
        if let Some(i) = self.find(op::TYPE_SAMPLER, |_| true) {
            return self.insts[i].ops[0];
        }
        let id = self.fresh_id();
        self.append_globals([Inst::new(op::TYPE_SAMPLER, vec![id])]);
        id
    }

    /// `OpTypePointer storage pointee`, declared if missing.
    pub fn type_pointer(&mut self, storage: u32, pointee: u32) -> u32 {
        if let Some(i) = self.find(op::TYPE_POINTER, |i| i.ops[1] == storage && i.ops[2] == pointee) {
            return self.insts[i].ops[0];
        }
        let id = self.fresh_id();
        self.append_globals([Inst::new(op::TYPE_POINTER, vec![id, storage, pointee])]);
        id
    }

    /// A new `OpConstant` (duplicates of scalar constants are valid SPIR-V).
    pub fn constant(&mut self, ty: u32, value: u32) -> u32 {
        let id = self.fresh_id();
        self.append_globals([Inst::new(op::CONSTANT, vec![ty, id, value])]);
        id
    }

    /// Value of a 32-bit integer `OpConstant`.
    pub fn constant_value(&self, id: u32) -> Option<u32> {
        self.insts
            .iter()
            .find(|i| i.op == op::CONSTANT && i.ops.get(1) == Some(&id))
            .and_then(|i| i.ops.get(2).copied())
    }

    /// The `OpMemberDecorate` instructions on struct `target`.
    pub fn member_decorations(&self, target: u32) -> impl Iterator<Item = &Inst> {
        self.insts
            .iter()
            .filter(move |i| i.op == op::MEMBER_DECORATE && i.ops[0] == target)
    }

    pub fn decorations(&self, target: u32) -> impl Iterator<Item = &Inst> {
        self.insts
            .iter()
            .filter(move |i| i.op == op::DECORATE && i.ops[0] == target)
    }

    pub fn decoration(&self, target: u32, decoration: u32) -> Option<u32> {
        self.decorations(target)
            .find(|i| i.ops.get(1) == Some(&decoration))
            .and_then(|i| i.ops.get(2).copied())
    }

    /// Adds `OpDecorate` (or `OpMemberDecorate`) instructions after the last existing one.
    pub fn add_annotations(&mut self, insts: impl IntoIterator<Item = Inst>) {
        let at = self
            .insts
            .iter()
            .rposition(|i| matches!(i.op, op::DECORATE | op::MEMBER_DECORATE | 332 | 5632 | 5633))
            .map(|i| i + 1)
            .unwrap_or_else(|| {
                self.insts
                    .iter()
                    .position(|i| i.is_type_or_global())
                    .unwrap_or(self.insts.len())
            });
        self.insts.splice(at..at, insts);
    }

    pub fn add_name(&mut self, target: u32, name: &str) {
        let mut ops = vec![target];
        ops.extend(string_words(name));
        let at = self
            .insts
            .iter()
            .rposition(|i| matches!(i.op, op::NAME | op::MEMBER_NAME))
            .map(|i| i + 1)
            .unwrap_or_else(|| self.insts.iter().position(|i| i.op == op::DECORATE).unwrap_or(0));
        self.insts.insert(at, Inst::new(op::NAME, ops));
    }

    pub fn name(&self, target: u32) -> Option<String> {
        self.insts
            .iter()
            .find(|i| i.op == op::NAME && i.ops[0] == target)
            .map(|i| decode_string(&i.ops[1..]))
    }

    /// Removes `id` from the interface of every entry point.
    pub fn remove_from_interface(&mut self, id: u32) {
        for inst in self.insts.iter_mut().filter(|i| i.op == op::ENTRY_POINT) {
            let start = interface_start(&inst.ops);
            let mut at = start;
            while at < inst.ops.len() {
                if inst.ops[at] == id {
                    inst.ops.remove(at);
                } else {
                    at += 1;
                }
            }
        }
    }

    /// The interface ids of the single entry point (empty without one).
    pub fn interface(&self) -> Vec<u32> {
        self.insts
            .iter()
            .find(|i| i.op == op::ENTRY_POINT)
            .map(|i| i.ops[interface_start(&i.ops)..].to_vec())
            .unwrap_or_default()
    }

    /// Appends `ids` to the interface of every entry point that lists `like`.
    pub fn extend_interface(&mut self, like: u32, ids: &[u32]) {
        for inst in self.insts.iter_mut().filter(|i| i.op == op::ENTRY_POINT) {
            let start = interface_start(&inst.ops);
            if inst.ops[start..].contains(&like) {
                inst.ops.extend_from_slice(ids);
            }
        }
    }

    /// Execution model and function id of the single entry point.
    pub fn entry_point(&self) -> Result<(u32, u32), Error> {
        let mut entries = self.insts.iter().filter(|i| i.op == op::ENTRY_POINT);
        let entry = entries.next().ok_or_else(|| Error::spirv("no entry point"))?;
        if entries.next().is_some() {
            return Err(Error::spirv("more than one entry point"));
        }
        Ok((entry.ops[0], entry.ops[1]))
    }

    /// Conservative use check: true when any instruction other than the ones
    /// `skip` accepts has a word equal to `id` (literals may give false hits,
    /// which only make a pass refuse a module, never miscompile it).
    pub fn mentions(&self, id: u32, skip: impl Fn(usize, &Inst) -> bool) -> Option<usize> {
        self.insts
            .iter()
            .enumerate()
            .find(|(index, inst)| inst.op != op::NOP && !skip(*index, inst) && id_operands(inst).any(|w| w == id))
            .map(|(index, _)| index)
    }
}

/// The operand words of `inst` that may be ids. Literal operands of the
/// instructions the passes meet often (annotations, types, constants, memory
/// access) are left out; everything else is reported whole.
pub fn id_operands(inst: &Inst) -> impl Iterator<Item = u32> + '_ {
    let ops = &inst.ops;
    let (head, tail): (&[u32], &[u32]) = match inst.op {
        op::NAME | op::MEMBER_NAME | op::DECORATE | op::MEMBER_DECORATE | 16 => (&ops[..1], &[]),
        op::ENTRY_POINT => (&ops[1..2], &ops[interface_start(ops)..]),
        op::CONSTANT | 50 => (&ops[..1], &[]),
        10
        | 11
        | 14
        | 17
        | op::SOURCE
        | op::SOURCE_CONTINUED
        | op::SOURCE_EXTENSION
        | op::STRING
        | 19
        | 20
        | op::TYPE_INT
        | 22
        | op::TYPE_SAMPLER => (&[], &[]),
        op::TYPE_VECTOR | 24 | op::TYPE_IMAGE => (&ops[1..2], &[]),
        op::TYPE_POINTER => (&ops[2..3], &[]),
        op::VARIABLE => (&ops[..2], ops.get(3..).unwrap_or(&[])),
        op::LOAD => (&ops[..3], &[]),
        op::STORE => (&ops[..2], &[]),
        op::EXT_INST => (&ops[..3], ops.get(4..).unwrap_or(&[])),
        _ => (&ops[..], &[]),
    };
    head.iter().chain(tail.iter()).copied()
}

/// Start of the interface ids in `OpEntryPoint` operands (after the literal name).
pub fn interface_start(ops: &[u32]) -> usize {
    let mut at = 2;
    while at < ops.len() {
        let word = ops[at];
        at += 1;
        if word >> 24 == 0 {
            break;
        }
    }
    at
}

pub fn string_words(text: &str) -> Vec<u32> {
    let mut bytes = text.as_bytes().to_vec();
    bytes.push(0);
    while !bytes.len().is_multiple_of(4) {
        bytes.push(0);
    }
    bytes
        .as_chunks::<4>()
        .0
        .iter()
        .map(|c| u32::from_le_bytes(*c))
        .collect()
}

pub fn decode_string(words: &[u32]) -> String {
    let mut bytes = Vec::new();
    'outer: for word in words {
        for byte in word.to_le_bytes() {
            if byte == 0 {
                break 'outer;
            }
            bytes.push(byte);
        }
    }
    String::from_utf8_lossy(&bytes).into_owned()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn strings_round_trip() {
        for text in ["", "a", "abc", "abcd", "main", "CloudFaces_sampler"] {
            assert_eq!(decode_string(&string_words(text)), text);
            assert_eq!(string_words(text).len(), text.len() / 4 + 1);
        }
    }

    #[test]
    fn interface_start_skips_the_name() {
        // OpEntryPoint Fragment %4 "main" %9 %11
        let mut ops = vec![4, 4];
        ops.extend(string_words("main"));
        ops.extend([9, 11]);
        assert_eq!(&ops[interface_start(&ops)..], &[9, 11]);
    }
}
