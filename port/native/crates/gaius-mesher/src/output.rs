//! Vertex sinks and the MESH RESULT encoding.
//!
//! # Vanilla BLOCK vertices (28 bytes, `DefaultVertexFormat.BLOCK`)
//!
//! ```text
//! @0  f32 x, y, z     section-relative position
//! @12 u8  r, g, b, a  BufferBuilder.putRgba(color) (ARGB.toABGR, little-endian)
//! @16 f32 u, v        atlas UV0
//! @24 u16 block, sky  UV2 light coords (packed int, little-endian)
//! ```
//! Four vertices per quad, quads in vanilla emission order, so a layer's bytes
//! equal what `SectionCompiler.compile` leaves in that layer's `BufferBuilder`.
//!
//! # Compact vertices (12 bytes) plus one u32 per quad
//!
//! ```text
//! @0  u16 x, y, z     round((p + 8) * 2048), p section-relative (covers -8..24 blocks, 1/2048 steps)
//! @6  u16 u, v        round(uv * 65535) (atlas unorm)
//! @10 u16 light       bits 0-4 block (smooth block light 0..240 >> 3), bits 5-9 sky (same),
//!                     bits 10-15 shade (vertex gray from AO and CardinalLighting, 0..255 >> 2)
//! quad u32            tint ARGB of the quad (0xFFFFFFFF untinted); the shader multiplies
//!                     tint * shade / 63 (fetch it with gl_VertexID / 4)
//! ```
//!
//! # Result payload (after the ABI result header), little-endian
//!
//! ```text
//! @0  u32 status          0 meshed, 1 table missing (epoch not resident: resend with the table),
//!                         2 needs vanilla (a state the table marks unsupported is in the section)
//! @4  u32 request_seq     @8 u32 section_version   (echoed)
//! @12 i32 section_x, y, z
//! @24 u32 table_epoch     the epoch resident in the kernel
//! @28 u32 visibility_lo   @32 u32 visibility_hi   VisibilitySet bits a + 6 * b
//! @36 u32 flags           bit 0 vanilla vertices, 1 compact vertices, 2 translucent sorted,
//!                         3 centroids, 4 no geometry
//! @40 f32 camera x, y, z  sort origin used
//! @52 u32 detail          status 2: the first unsupported state id; otherwise non-air blocks
//! @56 u32 reserved[2]
//! @64 layer[3]            SOLID, CUTOUT, TRANSLUCENT, 32 bytes each:
//!                         u32 quad_count, u32 vanilla_offset, u32 compact_offset,
//!                         u32 tint_offset, u32 order_offset, u32 centroid_offset, u32 0, u32 0
//!                         offsets are from the payload start, 8-aligned, 0 when absent;
//!                         vanilla: quad_count * 4 * 28 bytes; compact: quad_count * 4 * 12;
//!                         tint: quad_count u32; order (translucent): quad_count u32 quad indices,
//!                         farthest first (MeshData.SortState indices before the 6-per-quad
//!                         expansion); centroids (translucent): quad_count * 3 f32
//! ```

use crate::mth::{smooth_block, smooth_sky, to_abgr};

pub const RESULT_HEADER_LEN: usize = 160;
pub const STATUS_MESHED: u32 = 0;
pub const STATUS_TABLE_MISSING: u32 = 1;
pub const STATUS_NEEDS_VANILLA: u32 = 2;

pub mod result_flags {
    pub const VANILLA: u32 = 1 << 0;
    pub const COMPACT: u32 = 1 << 1;
    pub const SORTED: u32 = 1 << 2;
    pub const CENTROIDS: u32 = 1 << 3;
    pub const EMPTY: u32 = 1 << 4;
}

pub const VANILLA_VERTEX: usize = 28;
pub const COMPACT_VERTEX: usize = 12;

/// One output vertex before encoding.
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct Vertex {
    pub x: f32,
    pub y: f32,
    pub z: f32,
    pub color: i32,
    pub u: f32,
    pub v: f32,
    pub light: i32,
    /// Gray level (0..255) before the tint, for the compact format.
    pub shade: u8,
}

#[derive(Default)]
pub struct Layer {
    pub quads: u32,
    pub vanilla: Vec<u8>,
    pub compact: Vec<u8>,
    pub tints: Vec<u32>,
    pub cx: Vec<f32>,
    pub cy: Vec<f32>,
    pub cz: Vec<f32>,
}

impl Layer {
    fn clear(&mut self) {
        self.quads = 0;
        self.vanilla.clear();
        self.compact.clear();
        self.tints.clear();
        self.cx.clear();
        self.cy.clear();
        self.cz.clear();
    }

    /// Drops capacity a rare huge section left behind (memory budget on small devices).
    fn trim(&mut self, keep: usize) {
        for v in [&mut self.vanilla, &mut self.compact] {
            if v.capacity() > keep {
                v.shrink_to(keep);
            }
        }
    }
}

pub struct Output {
    pub layers: [Layer; 3],
    pub vanilla: bool,
    pub compact: bool,
}

impl Default for Output {
    fn default() -> Self {
        Output {
            layers: Default::default(),
            vanilla: true,
            compact: false,
        }
    }
}

#[inline]
fn quantize_position(p: f32) -> u16 {
    ((p as f64 + 8.0) * 2048.0).round().clamp(0.0, 65535.0) as u16
}

#[inline]
fn quantize_uv(p: f32) -> u16 {
    (p as f64 * 65535.0).round().clamp(0.0, 65535.0) as u16
}

impl Output {
    pub fn reset(&mut self, vanilla: bool, compact: bool) {
        self.vanilla = vanilla;
        self.compact = compact;
        for l in &mut self.layers {
            l.trim(8 << 20);
            l.clear();
        }
    }

    /// Appends one quad (four vertices in order) to `layer`.
    #[inline]
    pub fn quad(&mut self, layer: u8, v: &[Vertex; 4], tint: i32) {
        let out = &mut self.layers[layer as usize];
        out.quads += 1;
        if self.vanilla {
            let mut bytes = [0u8; VANILLA_VERTEX * 4];
            for (k, vert) in v.iter().enumerate() {
                let b = &mut bytes[k * VANILLA_VERTEX..(k + 1) * VANILLA_VERTEX];
                b[0..4].copy_from_slice(&vert.x.to_le_bytes());
                b[4..8].copy_from_slice(&vert.y.to_le_bytes());
                b[8..12].copy_from_slice(&vert.z.to_le_bytes());
                b[12..16].copy_from_slice(&to_abgr(vert.color).to_le_bytes());
                b[16..20].copy_from_slice(&vert.u.to_le_bytes());
                b[20..24].copy_from_slice(&vert.v.to_le_bytes());
                b[24..28].copy_from_slice(&vert.light.to_le_bytes());
            }
            out.vanilla.extend_from_slice(&bytes);
        }
        if self.compact {
            let mut bytes = [0u8; COMPACT_VERTEX * 4];
            for (k, vert) in v.iter().enumerate() {
                let b = &mut bytes[k * COMPACT_VERTEX..(k + 1) * COMPACT_VERTEX];
                b[0..2].copy_from_slice(&quantize_position(vert.x).to_le_bytes());
                b[2..4].copy_from_slice(&quantize_position(vert.y).to_le_bytes());
                b[4..6].copy_from_slice(&quantize_position(vert.z).to_le_bytes());
                b[6..8].copy_from_slice(&quantize_uv(vert.u).to_le_bytes());
                b[8..10].copy_from_slice(&quantize_uv(vert.v).to_le_bytes());
                let block = (smooth_block(vert.light) >> 3).min(31) as u16;
                let sky = (smooth_sky(vert.light) >> 3).min(31) as u16;
                let word = block | (sky << 5) | (((vert.shade >> 2) as u16) << 10);
                b[10..12].copy_from_slice(&word.to_le_bytes());
            }
            out.compact.extend_from_slice(&bytes);
            out.tints.push(tint as u32);
        }
        if layer == crate::table::LAYER_TRANSLUCENT {
            // MeshData.decodeQuadCentroids: the midpoint of vertices 0 and 2.
            out.cx.push((v[0].x + v[2].x) / 2.0);
            out.cy.push((v[0].y + v[2].y) / 2.0);
            out.cz.push((v[0].z + v[2].z) / 2.0);
        }
    }
}

/// Result header fields filled in by the mesher.
#[derive(Clone, Copy, Debug, Default)]
pub struct ResultHead {
    pub status: u32,
    pub request_seq: u32,
    pub section_version: u32,
    pub section: [i32; 3],
    pub table_epoch: u32,
    pub visibility: u64,
    pub camera: [f32; 3],
    pub detail: u32,
}

fn put_u32(o: &mut [u8], at: usize, v: u32) {
    o[at..at + 4].copy_from_slice(&v.to_le_bytes());
}

fn append_aligned(o: &mut Vec<u8>, bytes: &[u8]) -> u32 {
    while !o.len().is_multiple_of(8) {
        o.push(0);
    }
    let at = o.len() as u32;
    o.extend_from_slice(bytes);
    at
}

/// Encodes a result without geometry (table missing, needs vanilla).
pub fn encode_head_only(head: &ResultHead) -> Vec<u8> {
    let mut o = vec![0u8; RESULT_HEADER_LEN];
    write_head(&mut o, head, 0);
    o
}

fn write_head(o: &mut [u8], head: &ResultHead, flags: u32) {
    put_u32(o, 0, head.status);
    put_u32(o, 4, head.request_seq);
    put_u32(o, 8, head.section_version);
    for k in 0..3 {
        put_u32(o, 12 + 4 * k, head.section[k] as u32);
    }
    put_u32(o, 24, head.table_epoch);
    put_u32(o, 28, head.visibility as u32);
    put_u32(o, 32, (head.visibility >> 32) as u32);
    put_u32(o, 36, flags);
    for k in 0..3 {
        put_u32(o, 40 + 4 * k, head.camera[k].to_bits());
    }
    put_u32(o, 52, head.detail);
}

/// Encodes a meshed result. `order` is the translucent permutation (empty when unsorted).
pub fn encode(head: &ResultHead, out: &Output, order: &[u32], centroids: bool) -> Vec<u8> {
    let mut size = RESULT_HEADER_LEN;
    for l in &out.layers {
        size += l.vanilla.len() + l.compact.len() + l.tints.len() * 4 + 64;
    }
    size += order.len() * 4 + out.layers[2].cx.len() * 12;
    let mut o = Vec::with_capacity(size);
    o.resize(RESULT_HEADER_LEN, 0);
    let mut flags = 0;
    if out.vanilla {
        flags |= result_flags::VANILLA;
    }
    if out.compact {
        flags |= result_flags::COMPACT;
    }
    if !order.is_empty() {
        flags |= result_flags::SORTED;
    }
    if centroids && out.layers[2].quads > 0 {
        flags |= result_flags::CENTROIDS;
    }
    if out.layers.iter().all(|l| l.quads == 0) {
        flags |= result_flags::EMPTY;
    }
    let mut descriptors = [[0u32; 8]; 3];
    for (k, l) in out.layers.iter().enumerate() {
        let d = &mut descriptors[k];
        d[0] = l.quads;
        if l.quads == 0 {
            continue;
        }
        if out.vanilla {
            d[1] = append_aligned(&mut o, &l.vanilla);
        }
        if out.compact {
            d[2] = append_aligned(&mut o, &l.compact);
            let tints: Vec<u8> = l.tints.iter().flat_map(|t| t.to_le_bytes()).collect();
            d[3] = append_aligned(&mut o, &tints);
        }
        if k == 2 {
            if !order.is_empty() {
                let bytes: Vec<u8> = order.iter().flat_map(|t| t.to_le_bytes()).collect();
                d[4] = append_aligned(&mut o, &bytes);
            }
            if centroids {
                let mut bytes = Vec::with_capacity(l.cx.len() * 12);
                for i in 0..l.cx.len() {
                    bytes.extend_from_slice(&l.cx[i].to_le_bytes());
                    bytes.extend_from_slice(&l.cy[i].to_le_bytes());
                    bytes.extend_from_slice(&l.cz[i].to_le_bytes());
                }
                d[5] = append_aligned(&mut o, &bytes);
            }
        }
    }
    write_head(&mut o, head, flags);
    for (k, d) in descriptors.iter().enumerate() {
        for (j, v) in d.iter().enumerate() {
            put_u32(&mut o, 64 + 32 * k + 4 * j, *v);
        }
    }
    o
}
