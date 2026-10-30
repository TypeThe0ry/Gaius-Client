//! The static per-block-state light table.
//!
//! The light engine only asks four things of a `BlockState`:
//! `getLightDampening()`, `getLightEmission()`, `LightEngine.isEmptyShape`
//! (`!canOcclude() || !useShapeForLightOcclusion()`) and, for the others,
//! `getFaceOcclusionShape(dir)` compared with `Shapes.faceShapeOccludes`.
//! The Java exporter (`BrowserLightStateTable`) folds them into one `u16` per
//! state id ([`Props`]) plus two small side tables:
//!
//! - a *shape class* is the six face shape ids of a state that is not an empty
//!   shape; class 0 is reserved for every empty-shape state;
//! - the *face matrix* holds `Shapes.faceShapeOccludes(a, b)` for every pair of
//!   distinct face shapes, computed by vanilla itself, so no geometry is
//!   reimplemented here. Face id 0 is `Shapes.empty()`.
//!
//! Wire format (little-endian):
//!
//! ```text
//! u32 magic "GLTB", u32 version (1), u32 state_count, u32 run_count,
//! u32 class_count (1..=256), u32 face_count (1..=4096)
//! run_count x (u32 length, u32 props)      run-length coded props by state id, or, when
//!                                          run_count is 0, state_count x u16 props, pad to 4
//! class_count x u8[6]                      face ids per Direction ordinal
//! ceil(face_count^2 / 8) bytes             bit (a * face_count + b), LSB first
//! ```

use alloc::vec;
use alloc::vec::Vec;

pub const TABLE_MAGIC: u32 = u32::from_le_bytes(*b"GLTB");
pub const TABLE_VERSION: u32 = 1;
pub const MAX_CLASSES: usize = 256;
pub const MAX_FACES: usize = 4096;
/// Generous bound on the block state registry (26.3 has about 30k states).
pub const MAX_STATES: usize = 1 << 20;

/// Packed light properties of one block state: dampening in bits 0-3,
/// emission in bits 4-7, shape class in bits 8-15. `0` is plain air.
pub type Props = u16;

#[inline(always)]
pub const fn dampening(props: Props) -> u32 {
    (props & 0xf) as u32
}

/// `LightEngine.getOpacity`: `max(1, getLightDampening())`.
#[inline(always)]
pub const fn opacity(props: Props) -> u32 {
    let d = dampening(props);
    if d == 0 {
        1
    } else {
        d
    }
}

#[inline(always)]
pub const fn emission(props: Props) -> u32 {
    ((props >> 4) & 0xf) as u32
}

#[inline(always)]
pub const fn shape_class(props: Props) -> usize {
    (props >> 8) as usize
}

/// `LightEngine.isEmptyShape(state)`.
#[inline(always)]
pub const fn is_empty_shape(props: Props) -> bool {
    props >> 8 == 0
}

#[inline(always)]
pub const fn make_props(dampening: u32, emission: u32, class: usize) -> Props {
    ((dampening & 0xf) | ((emission & 0xf) << 4) | ((class as u32 & 0xff) << 8)) as Props
}

#[derive(Clone, Debug)]
pub struct LightTable {
    props: Vec<Props>,
    /// `class * 6 + dir` -> face id.
    faces: Vec<u16>,
    face_count: usize,
    matrix: Vec<u8>,
}

impl LightTable {
    /// A table built in memory (tests, tools): `faces[class]` are the face ids
    /// of each class and `occludes(a, b)` fills the face matrix.
    pub fn from_parts(
        props: Vec<Props>,
        faces: &[[u16; 6]],
        face_count: usize,
        occludes: impl Fn(usize, usize) -> bool,
    ) -> Result<LightTable, &'static str> {
        if faces.is_empty() || faces.len() > MAX_CLASSES {
            return Err("light table needs 1..=256 shape classes");
        }
        if face_count == 0 || face_count > MAX_FACES {
            return Err("light table needs 1..=4096 face shapes");
        }
        if faces[0] != [0; 6] {
            return Err("shape class 0 must be the empty shape");
        }
        let mut flat = Vec::with_capacity(faces.len() * 6);
        for class in faces {
            for &face in class {
                if face as usize >= face_count {
                    return Err("face id out of range");
                }
                flat.push(face);
            }
        }
        for &p in &props {
            if shape_class(p) >= faces.len() {
                return Err("state names an unknown shape class");
            }
        }
        let mut matrix = vec![0u8; (face_count * face_count).div_ceil(8)];
        for a in 0..face_count {
            for b in 0..face_count {
                if occludes(a, b) {
                    let bit = a * face_count + b;
                    matrix[bit >> 3] |= 1 << (bit & 7);
                }
            }
        }
        Ok(LightTable {
            props,
            faces: flat,
            face_count,
            matrix,
        })
    }

    /// Decodes the wire format described in the module docs.
    pub fn decode(bytes: &[u8]) -> Result<LightTable, &'static str> {
        fn u32_at(bytes: &[u8], at: &mut usize, what: &'static str) -> Result<u32, &'static str> {
            let raw = bytes.get(*at..*at + 4).ok_or(what)?;
            *at += 4;
            Ok(u32::from_le_bytes([raw[0], raw[1], raw[2], raw[3]]))
        }
        const SHORT: &str = "light table is truncated";
        let mut at = 0usize;
        if u32_at(bytes, &mut at, SHORT)? != TABLE_MAGIC {
            return Err("light table has a bad magic");
        }
        if u32_at(bytes, &mut at, SHORT)? != TABLE_VERSION {
            return Err("unsupported light table version");
        }
        let state_count = u32_at(bytes, &mut at, SHORT)? as usize;
        let run_count = u32_at(bytes, &mut at, SHORT)? as usize;
        let class_count = u32_at(bytes, &mut at, SHORT)? as usize;
        let face_count = u32_at(bytes, &mut at, SHORT)? as usize;
        if state_count > MAX_STATES || run_count > state_count {
            return Err("light table state count is out of range");
        }
        if class_count == 0 || class_count > MAX_CLASSES || face_count == 0 || face_count > MAX_FACES {
            return Err("light table class or face count is out of range");
        }
        let mut props = Vec::with_capacity(state_count);
        if run_count == 0 {
            // Raw form: one u16 per state, padded to 4 bytes.
            let raw = bytes
                .get(at..at + state_count * 2)
                .ok_or("light table props are truncated")?;
            props.extend(raw.as_chunks::<2>().0.iter().map(|pair| u16::from_le_bytes(*pair)));
            at += (state_count * 2).next_multiple_of(4);
        } else {
            for _ in 0..run_count {
                let length = u32_at(bytes, &mut at, "light table runs are truncated")? as usize;
                let value = u32_at(bytes, &mut at, "light table runs are truncated")?;
                if props.len() + length > state_count || value > 0xffff {
                    return Err("light table runs are invalid");
                }
                props.resize(props.len() + length, value as Props);
            }
        }
        if props.len() != state_count {
            return Err("light table runs do not cover every state");
        }
        let faces_bytes = bytes
            .get(at..at + class_count * 6)
            .ok_or("light table classes are truncated")?;
        at += class_count * 6;
        let mut faces = [[0u16; 6]; MAX_CLASSES];
        for (class, chunk) in faces_bytes.as_chunks::<6>().0.iter().enumerate() {
            for (dir, &face) in chunk.iter().enumerate() {
                faces[class][dir] = face as u16;
            }
        }
        let matrix_len = (face_count * face_count).div_ceil(8);
        let matrix = bytes
            .get(at..at + matrix_len)
            .ok_or("light table face matrix is truncated")?;
        LightTable::from_parts(props, &faces[..class_count], face_count, |a, b| {
            let bit = a * face_count + b;
            matrix[bit >> 3] & (1 << (bit & 7)) != 0
        })
    }

    /// Encodes the table in the wire format (tests and tools): run-length coded, or raw when
    /// that is smaller.
    pub fn encode(&self) -> Vec<u8> {
        let mut runs: Vec<(u32, u32)> = Vec::new();
        for &p in &self.props {
            match runs.last_mut() {
                Some((length, value)) if *value == p as u32 => *length += 1,
                _ => runs.push((1, p as u32)),
            }
        }
        let raw = runs.len() * 8 > (self.props.len() * 2).next_multiple_of(4);
        let class_count = self.faces.len() / 6;
        let mut out = Vec::new();
        for value in [
            TABLE_MAGIC,
            TABLE_VERSION,
            self.props.len() as u32,
            if raw { 0 } else { runs.len() as u32 },
            class_count as u32,
            self.face_count as u32,
        ] {
            out.extend_from_slice(&value.to_le_bytes());
        }
        if raw {
            for &p in &self.props {
                out.extend_from_slice(&p.to_le_bytes());
            }
            while out.len() % 4 != 0 {
                out.push(0);
            }
        } else {
            for (length, value) in runs {
                out.extend_from_slice(&length.to_le_bytes());
                out.extend_from_slice(&value.to_le_bytes());
            }
        }
        out.extend(self.faces.iter().map(|&f| f as u8));
        out.extend_from_slice(&self.matrix);
        out
    }

    pub fn state_count(&self) -> usize {
        self.props.len()
    }

    /// Props of a state id, or `None` for an id outside the registry.
    #[inline(always)]
    pub fn props(&self, state: u32) -> Option<Props> {
        self.props.get(state as usize).copied()
    }

    /// `Shapes.faceShapeOccludes(getOcclusionShape(from, dir),
    /// getOcclusionShape(to, dir.getOpposite()))`, i.e. `shapeOccludes`.
    #[inline(always)]
    pub fn occludes(&self, from_class: usize, to_class: usize, dir: usize) -> bool {
        if from_class | to_class == 0 {
            return false;
        }
        let a = self.faces[from_class * 6 + dir] as usize;
        let b = self.faces[to_class * 6 + (dir ^ 1)] as usize;
        let bit = a * self.face_count + b;
        self.matrix[bit >> 3] & (1 << (bit & 7)) != 0
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trips_through_the_wire_format() {
        // Face 0 empty, 1 full square, 2 half square; class 1 = bottom slab.
        let props = vec![0, 0, make_props(15, 0, 0), make_props(0, 14, 0), make_props(0, 0, 1)];
        let faces = [[0; 6], [1, 0, 2, 2, 2, 2]];
        let table = LightTable::from_parts(props, &faces, 3, |a, b| a == 1 || b == 1).unwrap();
        let decoded = LightTable::decode(&table.encode()).unwrap();
        assert_eq!(decoded.state_count(), 5);
        assert_eq!(decoded.props(3), Some(make_props(0, 14, 0)));
        // Light going down out of a bottom slab hits its full bottom face.
        assert!(decoded.occludes(1, 0, crate::dir::DOWN));
        assert!(!decoded.occludes(1, 0, crate::dir::UP));
        assert!(!decoded.occludes(0, 0, crate::dir::UP));
        assert_eq!(decoded.props(9), None);

        // Alternating props are smaller raw than run-length coded.
        let props = (0..64).map(|i| make_props(i % 2, 0, 0)).collect();
        let table = LightTable::from_parts(props, &[[0; 6]], 1, |_, _| false).unwrap();
        let bytes = table.encode();
        assert_eq!(u32::from_le_bytes(bytes[12..16].try_into().unwrap()), 0);
        assert_eq!(LightTable::decode(&bytes).unwrap().props(63), Some(make_props(1, 0, 0)));
    }
}
