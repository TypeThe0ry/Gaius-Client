//! Block states of one 16x16x16 section, as the Java side ships them.
//!
//! Encodings:
//!
//! - [`SectionStates::Single`]: every cell holds one state id (an empty
//!   section is `Single(air)`);
//! - [`SectionStates::Network`]: the bytes of `PalettedContainer.write` for the
//!   block-state container, i.e. what the chunk packet carries: `u8 bits`,
//!   then the palette (`bits == 0`: one VarInt; `1..=8`: VarInt count and
//!   VarInt ids; otherwise none, the values are global ids), then the
//!   `SimpleBitStorage` longs, big-endian, values never spanning two longs.
//!   The Java side gets it from `section.getStates().write(buf)` without
//!   touching a single cell;
//! - [`SectionStates::FlatU16`]: 4096 little-endian `u16` ids in
//!   `(y << 8) | (z << 4) | x` order (tests and simple producers).
//!
//! Decoding resolves ids through the [`LightTable`] once per palette entry and
//! writes [`Props`] straight into the caller's cells.

use crate::table::{LightTable, Props};
use alloc::vec::Vec;

pub const SECTION_CELLS: usize = 4096;

#[derive(Clone, Copy, Debug)]
pub enum SectionStates<'a> {
    Single(u32),
    Network(&'a [u8]),
    FlatU16(&'a [u8]),
}

/// A section decoded down to light props, borrowing scratch storage.
pub struct DecodedSection<'a> {
    kind: Kind<'a>,
}

enum Kind<'a> {
    Uniform(Props),
    /// `bits`, per-palette-entry props (or none for global ids), longs.
    Packed {
        bits: u32,
        palette: Option<&'a [Props]>,
        words: &'a [u8],
        table: &'a LightTable,
    },
    Flat(&'a [u8], &'a LightTable),
}

fn read_var_int(bytes: &[u8], at: &mut usize) -> Result<u32, &'static str> {
    let mut value = 0u32;
    for shift in (0..35).step_by(7) {
        let byte = *bytes.get(*at).ok_or("section palette is truncated")?;
        *at += 1;
        value |= ((byte & 0x7f) as u32) << shift;
        if byte & 0x80 == 0 {
            return Ok(value);
        }
    }
    Err("section palette VarInt is too long")
}

/// Props with zero dampening and an empty shape.
#[inline(always)]
pub(crate) const fn transparent_props(props: Props) -> bool {
    props & 0xff0f == 0
}

/// Props with a non-zero light emission.
#[inline(always)]
pub(crate) const fn emitting_props(props: Props) -> bool {
    props & 0x00f0 != 0
}

#[inline(always)]
pub(crate) fn lookup(table: &LightTable, id: u32) -> Result<Props, &'static str> {
    table
        .props(id)
        .ok_or("section names a block state outside the light table")
}

impl<'a> DecodedSection<'a> {
    /// Parses `states`; `palette_scratch` keeps the palette props alive.
    pub fn decode(
        states: SectionStates<'a>,
        table: &'a LightTable,
        palette_scratch: &'a mut Vec<Props>,
    ) -> Result<DecodedSection<'a>, &'static str> {
        let kind = match states {
            SectionStates::Single(id) => Kind::Uniform(lookup(table, id)?),
            SectionStates::FlatU16(bytes) => {
                if bytes.len() != SECTION_CELLS * 2 {
                    return Err("flat section must hold 4096 u16 ids");
                }
                Kind::Flat(bytes, table)
            }
            SectionStates::Network(bytes) => {
                let bits = *bytes.first().ok_or("network section is empty")? as u32;
                let mut at = 1usize;
                if bits == 0 {
                    let id = read_var_int(bytes, &mut at)?;
                    Kind::Uniform(lookup(table, id)?)
                } else if bits > 32 {
                    return Err("network section has an invalid bit count");
                } else {
                    palette_scratch.clear();
                    let palette = if bits <= 8 {
                        let count = read_var_int(bytes, &mut at)? as usize;
                        if count == 0 || count > 1 << bits {
                            return Err("network section palette size is invalid");
                        }
                        for _ in 0..count {
                            let id = read_var_int(bytes, &mut at)?;
                            palette_scratch.push(lookup(table, id)?);
                        }
                        true
                    } else {
                        false
                    };
                    let per_long = (64 / bits) as usize;
                    let words_len = SECTION_CELLS.div_ceil(per_long) * 8;
                    let words = bytes
                        .get(at..at + words_len)
                        .ok_or("network section storage is truncated")?;
                    if palette && palette_scratch.len() == 1 {
                        Kind::Uniform(palette_scratch[0])
                    } else {
                        Kind::Packed {
                            bits,
                            palette: if palette { Some(&palette_scratch[..]) } else { None },
                            words,
                            table,
                        }
                    }
                }
            }
        };
        Ok(DecodedSection { kind })
    }

    /// The single props value of a uniform section.
    pub fn uniform(&self) -> Option<Props> {
        match self.kind {
            Kind::Uniform(props) => Some(props),
            _ => None,
        }
    }

    /// True when no cell can block or shape light: every state has zero
    /// dampening and an empty shape (air, most plants, glass is not one).
    pub fn is_transparent(&self) -> bool {
        match self.kind {
            Kind::Uniform(props) => transparent_props(props),
            Kind::Packed {
                palette: Some(palette), ..
            } => palette.iter().all(|&p| transparent_props(p)),
            _ => false,
        }
    }

    /// True when some cell may emit light.
    pub fn may_emit(&self) -> bool {
        match self.kind {
            Kind::Uniform(props) => emitting_props(props),
            Kind::Packed {
                palette: Some(palette), ..
            } => palette.iter().any(|&p| emitting_props(p)),
            _ => true,
        }
    }

    #[inline(always)]
    fn packed_value(words: &[u8], bits: u32, index: usize) -> u32 {
        let per_long = (64 / bits) as usize;
        let word = index / per_long;
        let shift = (index - word * per_long) as u32 * bits;
        let raw = &words[word * 8..word * 8 + 8];
        let value = u64::from_be_bytes([raw[0], raw[1], raw[2], raw[3], raw[4], raw[5], raw[6], raw[7]]);
        ((value >> shift) & ((1u64 << bits) - 1)) as u32
    }

    /// Props of the cell at section index `(y << 8) | (z << 4) | x`.
    pub fn get(&self, index: usize) -> Result<Props, &'static str> {
        match self.kind {
            Kind::Uniform(props) => Ok(props),
            Kind::Flat(bytes, table) => lookup(
                table,
                u16::from_le_bytes([bytes[index * 2], bytes[index * 2 + 1]]) as u32,
            ),
            Kind::Packed {
                bits,
                palette,
                words,
                table,
            } => {
                let value = Self::packed_value(words, bits, index);
                match palette {
                    Some(palette) => palette
                        .get(value as usize)
                        .copied()
                        .ok_or("section index outside its palette"),
                    None => lookup(table, value),
                }
            }
        }
    }

    /// Writes all 4096 cells: `row(y, z)` gives the start of the 16-cell x row.
    pub fn write_all(&self, cells: &mut [Props], row: impl Fn(usize, usize) -> usize) -> Result<(), &'static str> {
        match self.kind {
            Kind::Uniform(props) => {
                for y in 0..16 {
                    for z in 0..16 {
                        let start = row(y, z);
                        cells[start..start + 16].fill(props);
                    }
                }
            }
            Kind::Flat(bytes, table) => {
                for (i, pair) in bytes.as_chunks::<2>().0.iter().enumerate() {
                    let start = row(i >> 8, (i >> 4) & 15);
                    cells[start + (i & 15)] = lookup(table, u16::from_le_bytes([pair[0], pair[1]]) as u32)?;
                }
            }
            Kind::Packed {
                bits,
                palette,
                words,
                table,
            } => {
                let per_long = (64 / bits) as usize;
                let mask = (1u64 << bits) - 1;
                let mut index = 0usize;
                for raw in words.as_chunks::<8>().0 {
                    let mut value =
                        u64::from_be_bytes([raw[0], raw[1], raw[2], raw[3], raw[4], raw[5], raw[6], raw[7]]);
                    let take = per_long.min(SECTION_CELLS - index);
                    for _ in 0..take {
                        let id = (value & mask) as u32;
                        value >>= bits;
                        let props = match palette {
                            Some(palette) => *palette.get(id as usize).ok_or("section index outside its palette")?,
                            None => lookup(table, id)?,
                        };
                        let start = row(index >> 8, (index >> 4) & 15);
                        cells[start + (index & 15)] = props;
                        index += 1;
                    }
                    if index == SECTION_CELLS {
                        break;
                    }
                }
            }
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::table::make_props;
    use alloc::vec;

    fn table() -> LightTable {
        let props = (0..40).map(|i| make_props(i % 16, 0, 0)).collect();
        LightTable::from_parts(props, &[[0; 6]], 1, |_, _| false).unwrap()
    }

    #[test]
    fn decodes_network_palette_and_direct_storage() {
        let table = table();
        // 4-bit linear palette [0, 7, 33]; cell i holds palette index i % 3.
        let mut bytes = vec![4u8, 3, 0, 7, 33];
        for word in 0..256 {
            let mut value = 0u64;
            for k in 0..16 {
                value |= (((word * 16 + k) % 3) as u64) << (4 * k);
            }
            bytes.extend_from_slice(&value.to_be_bytes());
        }
        let mut scratch = Vec::new();
        let section = DecodedSection::decode(SectionStates::Network(&bytes), &table, &mut scratch).unwrap();
        assert_eq!(section.get(4).unwrap(), make_props(7, 0, 0));
        assert_eq!(section.get(5).unwrap(), make_props(1, 0, 0));
        let mut cells = vec![0u16; 4096];
        section.write_all(&mut cells, |y, z| (y * 16 + z) * 16).unwrap();
        assert_eq!(cells[4095], section.get(4095).unwrap());

        // 15-bit direct storage: 4 values per long, so 1024 longs.
        let mut direct = vec![15u8];
        for word in 0..1024u64 {
            let value = (0..4u64).fold(0u64, |acc, k| acc | (((word * 4 + k) % 40) << (15 * k)));
            direct.extend_from_slice(&value.to_be_bytes());
        }
        let mut scratch = Vec::new();
        let section = DecodedSection::decode(SectionStates::Network(&direct), &table, &mut scratch).unwrap();
        assert_eq!(section.get(41).unwrap(), make_props(1, 0, 0));

        let single = [0u8, 33];
        let mut scratch = Vec::new();
        let section = DecodedSection::decode(SectionStates::Network(&single), &table, &mut scratch).unwrap();
        assert_eq!(section.uniform(), Some(make_props(1, 0, 0)));
    }
}
