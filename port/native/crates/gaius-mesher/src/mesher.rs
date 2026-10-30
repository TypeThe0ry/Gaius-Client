//! `SectionCompiler.compile` over a [`SectionJob`]: the block loop, model
//! part selection, face culling, flat and smooth (AO) lighting, tint and the
//! per-layer output. Fluids live in `fluid.rs`.
//!
//! Every block of the section is visited in vanilla order (x fastest, then y,
//! then z, `BlockPos.betweenClosed`), fluid first and model second, and every
//! quad goes to its layer in the order `ModelBlockRenderer` emits it, so the
//! vanilla vertex bytes of each layer match the Java compiler's.

use crate::job::{flags as job_flags, region_index, SectionJob, REGION, VOLUME};
use crate::mth::{
    block_seed, clamp_d, gray, light_pack, light_with_block, light_with_emission, multiply, red, scale_rgb,
    smooth_blend, FULL_BRIGHT,
};
use crate::output::{self, Output, ResultHead, Vertex};
use crate::rng::Rng;
use crate::simd;
use crate::table::{flags, skip, Group, ModelTable, Quad, StateInfo, FACE_EMPTY, FACE_FULL, LAYER_SOLID};
use crate::tint::{BiomeCache, Tinter};
use crate::visgraph::VisGraph;
use gaius_kernel_abi::KernelError;

pub const DOWN: usize = 0;
pub const UP: usize = 1;
pub const NORTH: usize = 2;
pub const SOUTH: usize = 3;
pub const WEST: usize = 4;
pub const EAST: usize = 5;

/// Index delta of one step along each Direction in the 20^3 region.
pub const DELTA: [isize; 6] = [
    -((REGION * REGION) as isize),
    (REGION * REGION) as isize,
    -(REGION as isize),
    REGION as isize,
    -1,
    1,
];

#[inline]
pub fn step(i: usize, dir: usize) -> usize {
    (i as isize + DELTA[dir]) as usize
}

/// `BlockModelLighter.AdjacencyInfo.corners`.
const CORNERS: [[usize; 4]; 6] = [
    [WEST, EAST, NORTH, SOUTH],
    [EAST, WEST, NORTH, SOUTH],
    [UP, DOWN, EAST, WEST],
    [WEST, EAST, DOWN, UP],
    [UP, DOWN, NORTH, SOUTH],
    [DOWN, UP, NORTH, SOUTH],
];

/// `BlockModelLighter.AmbientVertexRemap` (vert0..vert3).
const REMAP: [[usize; 4]; 6] = [
    [0, 1, 2, 3],
    [2, 3, 0, 1],
    [3, 0, 1, 2],
    [0, 1, 2, 3],
    [3, 0, 1, 2],
    [1, 2, 3, 0],
];

// SizeInfo indices into the face shape: DOWN 0, UP 1, NORTH 2, SOUTH 3, WEST 4, EAST 5,
// FLIP_DOWN 6, FLIP_UP 7, FLIP_NORTH 8, FLIP_SOUTH 9, FLIP_WEST 10, FLIP_EAST 11.
/// `AdjacencyInfo.vert0Weights..vert3Weights` per face.
const WEIGHTS: [[[usize; 8]; 4]; 6] = [
    [
        [10, 3, 10, 9, 4, 9, 4, 3],
        [10, 2, 10, 8, 4, 8, 4, 2],
        [11, 2, 11, 8, 5, 8, 5, 2],
        [11, 3, 11, 9, 5, 9, 5, 3],
    ],
    [
        [5, 3, 5, 9, 11, 9, 11, 3],
        [5, 2, 5, 8, 11, 8, 11, 2],
        [4, 2, 4, 8, 10, 8, 10, 2],
        [4, 3, 4, 9, 10, 9, 10, 3],
    ],
    [
        [1, 10, 1, 4, 7, 4, 7, 10],
        [1, 11, 1, 5, 7, 5, 7, 11],
        [0, 11, 0, 5, 6, 5, 6, 11],
        [0, 10, 0, 4, 6, 4, 6, 10],
    ],
    [
        [1, 10, 7, 10, 7, 4, 1, 4],
        [0, 10, 6, 10, 6, 4, 0, 4],
        [0, 11, 6, 11, 6, 5, 0, 5],
        [1, 11, 7, 11, 7, 5, 1, 5],
    ],
    [
        [1, 3, 1, 9, 7, 9, 7, 3],
        [1, 2, 1, 8, 7, 8, 7, 2],
        [0, 2, 0, 8, 6, 8, 6, 2],
        [0, 3, 0, 9, 6, 9, 6, 3],
    ],
    [
        [6, 3, 6, 9, 0, 9, 0, 3],
        [6, 2, 6, 8, 0, 8, 0, 2],
        [7, 2, 7, 8, 1, 8, 1, 2],
        [7, 3, 7, 9, 1, 9, 1, 3],
    ],
];

/// Reusable per-worker buffers; nothing in the block loop allocates once these have grown.
pub struct Scratch {
    pub ids: Vec<u32>,
    pub rows: [u16; 256],
    pub biome: BiomeCache,
    pub vis: VisGraph,
    pub out: Output,
    pub parts: Vec<u32>,
    pub dist: Vec<f32>,
    pub order: Vec<u32>,
}

impl Default for Scratch {
    fn default() -> Self {
        Scratch {
            ids: Vec::with_capacity(VOLUME),
            rows: [0; 256],
            biome: BiomeCache::default(),
            vis: VisGraph::default(),
            out: Output::default(),
            parts: Vec::with_capacity(16),
            dist: Vec::new(),
            order: Vec::new(),
        }
    }
}

/// `prepareQuadShape`: the quad's bounds as SizeInfo values plus facePartial and faceCubic.
pub(crate) struct QuadShape {
    pub size: [f32; 12],
    pub partial: bool,
    pub cubic: bool,
}

pub(crate) fn quad_shape(g: &[f32; 12], dir: usize, collision_full: bool) -> QuadShape {
    let (mut min_x, mut min_y, mut min_z) = (32.0f32, 32.0f32, 32.0f32);
    let (mut max_x, mut max_y, mut max_z) = (-32.0f32, -32.0f32, -32.0f32);
    for v in 0..4 {
        let (x, y, z) = (g[3 * v], g[3 * v + 1], g[3 * v + 2]);
        min_x = min_x.min(x);
        min_y = min_y.min(y);
        min_z = min_z.min(z);
        max_x = max_x.max(x);
        max_y = max_y.max(y);
        max_z = max_z.max(z);
    }
    let size = [
        min_y,
        max_y,
        min_z,
        max_z,
        min_x,
        max_x,
        1.0 - min_y,
        1.0 - max_y,
        1.0 - min_z,
        1.0 - max_z,
        1.0 - min_x,
        1.0 - max_x,
    ];
    const LOW: f32 = 1.0E-4;
    const HIGH: f32 = 0.9999;
    let partial = match dir {
        DOWN | UP => min_x >= LOW || min_z >= LOW || max_x <= HIGH || max_z <= HIGH,
        NORTH | SOUTH => min_x >= LOW || min_y >= LOW || max_x <= HIGH || max_y <= HIGH,
        _ => min_y >= LOW || min_z >= LOW || max_y <= HIGH || max_z <= HIGH,
    };
    let cubic = match dir {
        DOWN => min_y == max_y && (min_y < LOW || collision_full),
        UP => min_y == max_y && (max_y > HIGH || collision_full),
        NORTH => min_z == max_z && (min_z < LOW || collision_full),
        SOUTH => min_z == max_z && (max_z > HIGH || collision_full),
        WEST => min_x == max_x && (min_x < LOW || collision_full),
        _ => min_x == max_x && (max_x > HIGH || collision_full),
    };
    QuadShape { size, partial, cubic }
}

pub(crate) struct Mesher<'s, 'j, 'a> {
    pub t: &'s ModelTable,
    pub ids: &'s [u32],
    pub light: &'a [u8],
    pub tinter: Tinter<'j, 'a>,
    pub cardinal: [f32; 6],
    pub ao: bool,
    pub cutout_leaves: bool,
    pub origin: [i32; 3],
    pub out: &'s mut Output,
    pub biome: &'s mut BiomeCache,
    pub parts: &'s mut Vec<u32>,
    pub rng: Rng,
    pub tint_vals: [i32; 64],
    pub tint_set: u64,
}

impl<'s> Mesher<'s, '_, '_> {
    #[inline]
    pub fn st(&self, i: usize) -> &'s StateInfo {
        &self.t.states[self.ids[i] as usize]
    }

    /// `LightCoordsUtil.getLightCoords(level, state, pos)` with the region's light at `i`.
    #[inline]
    pub fn light_coords(&self, st: &StateInfo, i: usize) -> i32 {
        if st.has(flags::EMISSIVE) {
            return FULL_BRIGHT;
        }
        let l = self.light[i] as i32;
        let block = l & 15;
        let packed = light_pack(block, l >> 4);
        let emission = st.emission as i32;
        if block < emission {
            light_with_block(packed, emission)
        } else {
            packed
        }
    }

    #[inline]
    pub fn world(&self, x: i32, y: i32, z: i32) -> (i32, i32, i32) {
        (self.origin[0] + x, self.origin[1] + y, self.origin[2] + z)
    }

    /// `Block.shouldRenderFace(state, neighbor, dir)`, `neighbor` at `i + dir`.
    fn should_render_face(&self, st: &StateInfo, i: usize, dir: usize) -> bool {
        let nb = self.st(step(i, dir));
        let nface = nb.faces[dir ^ 1];
        if nface == FACE_FULL {
            return false;
        }
        if self.skip_rendering(st, nb, dir) {
            return false;
        }
        if nface == FACE_EMPTY {
            return true;
        }
        let sface = st.faces[dir];
        if sface == FACE_EMPTY {
            return true;
        }
        let s = &self.t.masks[sface as usize];
        let n = &self.t.masks[nface as usize];
        (s[0] & !n[0]) | (s[1] & !n[1]) | (s[2] & !n[2]) | (s[3] & !n[3]) != 0
    }

    /// `Block.skipRendering(state, neighbor, dir)` and its overrides.
    fn skip_rendering(&self, st: &StateInfo, nb: &StateInfo, dir: usize) -> bool {
        match st.skip {
            skip::SAME_BLOCK => nb.block_id == st.block_id,
            skip::SAME_FLUID => nb.fluid_group != 0 && nb.fluid_group == st.fluid_group,
            skip::BARS => {
                let opposite = dir ^ 1;
                let related = nb.block_id == st.block_id
                    || (st.has(flags::BARS_TAG) && nb.has(flags::BARS_TAG) && nb.conn_present & (1 << opposite) != 0);
                related && (dir <= UP || (st.conn_true & (1 << dir) != 0 && nb.conn_true & (1 << opposite) != 0))
            }
            skip::SAME_BLOCK_VERTICAL => nb.block_id == st.block_id && dir <= UP,
            skip::LEAVES => !self.cutout_leaves && nb.has(flags::LEAVES),
            _ => false,
        }
    }

    /// `WeightedList.getRandomOrThrow` / `SingleVariant`.
    fn pick(&mut self, g: &Group) -> u32 {
        let entries = &self.t.entries[g.first_entry as usize..][..g.entry_count as usize];
        if !g.weighted {
            return entries[0].part;
        }
        let mut i = self.rng.next_int(g.total_weight as i32);
        for e in entries {
            i -= e.weight as i32;
            if i < 0 {
                return e.part;
            }
        }
        entries[entries.len() - 1].part
    }

    /// `BlockState.getOffset(pos)`.
    fn offset(st: &StateInfo, wx: i32, wz: i32) -> (f64, f64, f64) {
        if st.offset_type == 0 {
            return (0.0, 0.0, 0.0);
        }
        let seed = block_seed(wx, 0, wz);
        let h = st.max_h;
        let horizontal = |shift: u32| {
            clamp_d(
                ((((seed >> shift) & 15) as f32 / 15.0) as f64 - 0.5) * 0.5,
                -h as f64,
                h as f64,
            )
        };
        let y = if st.offset_type == 2 {
            ((((seed >> 4) & 15) as f32 / 15.0) as f64 - 1.0) * st.max_v as f64
        } else {
            0.0
        };
        (horizontal(0), y, horizontal(8))
    }

    #[inline]
    fn tint_color(&mut self, st: &StateInfo, index: i16, wx: i32, wy: i32, wz: i32) -> i32 {
        if index < 0 || index as usize >= st.tint_count as usize {
            return -1;
        }
        let k = index as usize;
        if self.tint_set & (1 << k) != 0 {
            return self.tint_vals[k];
        }
        let source = self.t.tints[st.first_tint as usize + k];
        let c = self.tinter.color(self.biome, source.kind, source.argb, wx, wy, wz);
        self.tint_vals[k] = c;
        self.tint_set |= 1 << k;
        c
    }

    /// `ModelBlockRenderer.tesselateBlock` for the block at region index `i`.
    pub fn model(&mut self, i: usize, x: i32, y: i32, z: i32, st: &'s StateInfo) {
        let t = self.t;
        if st.group_count == 0 {
            return;
        }
        let (wx, wy, wz) = self.world(x, y, z);
        let so = st.seed_offset;
        self.rng
            .set_seed(block_seed(wx + so[0] as i32, wy + so[1] as i32, wz + so[2] as i32));
        self.parts.clear();
        let groups = &t.groups[st.first_group as usize..][..st.group_count as usize];
        if st.multipart {
            let seed = self.rng.next_long();
            for g in groups {
                self.rng.set_seed(seed);
                let p = self.pick(g);
                self.parts.push(p);
            }
        } else {
            for g in groups {
                let p = self.pick(g);
                self.parts.push(p);
            }
        }
        if self.parts.is_empty() {
            return;
        }
        let (ox, oy, oz) = Self::offset(st, wx, wz);
        let fx = x as f32 + ox as f32;
        let fy = y as f32 + oy as f32;
        let fz = z as f32 + oz as f32;
        let ao = self.ao && st.emission == 0 && t.parts[self.parts[0] as usize].use_ao;
        let force_solid = !self.cutout_leaves && st.has(flags::LEAVES);
        self.tint_set = 0;
        let place = Place {
            world: (wx, wy, wz),
            offset: (fx, fy, fz),
            force_solid,
        };
        let mut checked = 0u8;
        let mut visible = 0u8;
        for p in 0..self.parts.len() {
            let part = &t.parts[self.parts[p] as usize];
            for dir in 0..6 {
                let bit = 1u8 << dir;
                if checked & bit != 0 && visible & bit == 0 {
                    continue;
                }
                let range = part.range(dir);
                if range.is_empty() {
                    continue;
                }
                if checked & bit == 0 {
                    checked |= bit;
                    if self.should_render_face(st, i, dir) {
                        visible |= bit;
                    }
                }
                if visible & bit == 0 {
                    continue;
                }
                if ao {
                    for r in range {
                        let q = &t.quads[t.quad_refs[r] as usize];
                        let (colors, lights) = self.prepare_ao(st, i, q);
                        self.put(st, &place, q, colors, lights);
                    }
                } else {
                    let light = self.light_coords(st, step(i, dir));
                    for r in range {
                        let q = &t.quads[t.quad_refs[r] as usize];
                        let (colors, lights) = self.prepare_flat(st, i, q, light);
                        self.put(st, &place, q, colors, lights);
                    }
                }
            }
            for r in part.range(6) {
                let q = &t.quads[t.quad_refs[r] as usize];
                let (colors, lights) = if ao {
                    self.prepare_ao(st, i, q)
                } else {
                    self.prepare_flat(st, i, q, -1)
                };
                self.put(st, &place, q, colors, lights);
            }
        }
    }

    /// `BlockModelLighter.prepareQuadFlat`; `light == -1` is CHECK_LIGHT.
    fn prepare_flat(&self, st: &StateInfo, i: usize, q: &Quad, light: i32) -> ([i32; 4], [i32; 4]) {
        let light = if light == -1 {
            let g = &self.t.geometry[q.geometry as usize];
            let shape = quad_shape(g, q.direction as usize, st.has(flags::COLLISION_FULL));
            let at = if shape.cubic { step(i, q.direction as usize) } else { i };
            self.light_coords(st, at)
        } else {
            light
        };
        let color = gray(self.cardinal[q.shade_face as usize]);
        ([color; 4], [light; 4])
    }

    /// `BlockModelLighter.prepareQuadAmbientOcclusion`.
    fn prepare_ao(&self, st: &StateInfo, i: usize, q: &Quad) -> ([i32; 4], [i32; 4]) {
        let dir = q.direction as usize;
        let g = &self.t.geometry[q.geometry as usize];
        let shape = quad_shape(g, dir, st.has(flags::COLLISION_FULL));
        let p2 = if shape.cubic { step(i, dir) } else { i };
        let c = CORNERS[dir];
        let n = [step(p2, c[0]), step(p2, c[1]), step(p2, c[2]), step(p2, c[3])];
        let mut light = [0i32; 4];
        let mut shade = [0f32; 4];
        let mut permeable = [false; 4];
        for k in 0..4 {
            let s = self.st(n[k]);
            light[k] = self.light_coords(s, n[k]);
            shade[k] = s.shade;
        }
        for k in 0..4 {
            permeable[k] = self.st(step(n[k], dir)).has(flags::LIGHT_PERMEABLE);
        }
        let diagonal = |a: usize, b: usize| {
            let p = step(n[a], c[b]);
            let s = self.st(p);
            (s.shade, self.light_coords(s, p))
        };
        // Vanilla falls back to corner 0 for all four diagonals.
        let fallback = (shade[0], light[0]);
        let (shade4, light4) = if permeable[2] || permeable[0] {
            diagonal(0, 2)
        } else {
            fallback
        };
        let (shade5, light5) = if permeable[3] || permeable[0] {
            diagonal(0, 3)
        } else {
            fallback
        };
        let (shade6, light6) = if permeable[2] || permeable[1] {
            diagonal(1, 2)
        } else {
            fallback
        };
        let (shade7, light7) = if permeable[3] || permeable[1] {
            diagonal(1, 3)
        } else {
            fallback
        };
        let mut light_c = self.light_coords(st, i);
        let nb_i = step(i, dir);
        let nb = self.st(nb_i);
        if shape.cubic || !nb.has(flags::SOLID_RENDER) {
            light_c = self.light_coords(nb, nb_i);
        }
        let shade_c = if shape.cubic { self.st(p2).shade } else { st.shade };
        let ao = [
            (shade[3] + shade[0] + shade5 + shade_c) * 0.25,
            (shade[2] + shade[0] + shade4 + shade_c) * 0.25,
            (shade[2] + shade[1] + shade6 + shade_c) * 0.25,
            (shade[3] + shade[1] + shade7 + shade_c) * 0.25,
        ];
        let blended = [
            smooth_blend(light[3], light[0], light5, light_c),
            smooth_blend(light[2], light[0], light4, light_c),
            smooth_blend(light[2], light[1], light6, light_c),
            smooth_blend(light[3], light[1], light7, light_c),
        ];
        let remap = REMAP[dir];
        let mut colors = [0i32; 4];
        let mut lights = [0i32; 4];
        // AdjacencyInfo.doNonCubicWeight is true for every face, so facePartial decides.
        if shape.partial {
            let table = &WEIGHTS[dir];
            let mut w = [[0f32; 4]; 4];
            for k in 0..4 {
                for j in 0..4 {
                    w[k][j] = shape.size[table[k][2 * j]] * shape.size[table[k][2 * j + 1]];
                }
            }
            let a = simd::weighted_ao(ao, &w);
            let l = simd::weighted_light(blended, &w);
            for k in 0..4 {
                colors[remap[k]] = gray(a[k]);
                lights[remap[k]] = l[k];
            }
        } else {
            for k in 0..4 {
                colors[remap[k]] = gray(ao[k]);
                lights[remap[k]] = blended[k];
            }
        }
        let brightness = self.cardinal[q.shade_face as usize];
        for c in &mut colors {
            *c = scale_rgb(*c, brightness);
        }
        (colors, lights)
    }

    /// `putQuadWithTint` + `BufferBuilder.putBlockBakedQuad`.
    fn put(&mut self, st: &StateInfo, place: &Place, q: &Quad, mut colors: [i32; 4], lights: [i32; 4]) {
        let shades = [
            red(colors[0]) as u8,
            red(colors[1]) as u8,
            red(colors[2]) as u8,
            red(colors[3]) as u8,
        ];
        let mut tint = -1;
        if q.tint_index != -1 {
            let (wx, wy, wz) = place.world;
            tint = self.tint_color(st, q.tint_index, wx, wy, wz);
            for c in &mut colors {
                *c = multiply(*c, tint);
            }
        }
        let g = &self.t.geometry[q.geometry as usize];
        let uv = &self.t.uvs[q.uv as usize];
        let (fx, fy, fz) = place.offset;
        let emission = q.emission as i32;
        let mut v = [Vertex::default(); 4];
        for k in 0..4 {
            v[k] = Vertex {
                x: g[3 * k] + fx,
                y: g[3 * k + 1] + fy,
                z: g[3 * k + 2] + fz,
                color: colors[k],
                u: uv[2 * k],
                v: uv[2 * k + 1],
                light: light_with_emission(lights[k], emission),
                shade: shades[k],
            };
        }
        let layer = if place.force_solid { LAYER_SOLID } else { q.layer };
        self.out.quad(layer, &v, tint);
    }
}

/// Where the block being tesselated sits.
struct Place {
    world: (i32, i32, i32),
    offset: (f32, f32, f32),
    force_solid: bool,
}

/// Runs one decoded job against a resident table.
pub fn mesh_section(table: &ModelTable, job: &SectionJob<'_>, scratch: &mut Scratch) -> Result<Vec<u8>, KernelError> {
    let Scratch {
        ids,
        rows,
        biome,
        vis,
        out,
        parts,
        dist,
        order,
    } = scratch;
    job.read_ids(table, ids)?;
    let origin = [
        job.section[0].wrapping_mul(16),
        job.section[1].wrapping_mul(16),
        job.section[2].wrapping_mul(16),
    ];
    let mut head = ResultHead {
        status: output::STATUS_MESHED,
        request_seq: job.request_seq,
        section_version: job.section_version,
        section: job.section,
        table_epoch: table.epoch,
        camera: job.camera,
        ..ResultHead::default()
    };

    // Non-air bits per (y, z) row: SIMD compares against the air ids, flags otherwise.
    if !table.air_ids.is_empty() && table.air_ids.len() <= 4 {
        simd::non_air_rows(ids, &table.air_ids, rows);
    } else {
        for y in 0..16 {
            for z in 0..16 {
                let start = region_index(0, y, z);
                let mut mask = 0u16;
                for x in 0..16 {
                    if !table.states[ids[start + x] as usize].has(flags::AIR) {
                        mask |= 1 << x;
                    }
                }
                rows[(y * 16 + z) as usize] = mask;
            }
        }
    }

    // A state the exporter could not express sends the whole section back to vanilla.
    for y in 0..16 {
        for z in 0..16 {
            let mut mask = rows[(y * 16 + z) as usize];
            let start = region_index(0, y, z);
            while mask != 0 {
                let x = mask.trailing_zeros() as usize;
                mask &= mask - 1;
                let id = ids[start + x];
                if table.states[id as usize].has(flags::UNSUPPORTED)
                    || !crate::VERTEX_LAYOUTS.contains(&(table.semantics, 28))
                {
                    head.status = output::STATUS_NEEDS_VANILLA;
                    head.detail = id;
                    return Ok(output::encode_head_only(&head));
                }
            }
        }
    }

    out.reset(job.has(job_flags::EMIT_VANILLA), job.has(job_flags::EMIT_COMPACT));
    vis.reset();
    biome.reset();
    let mut non_air = 0u32;
    {
        let mut m = Mesher {
            t: table,
            ids,
            light: job.light,
            tinter: Tinter { job, origin },
            cardinal: job.cardinal,
            ao: job.has(job_flags::AMBIENT_OCCLUSION),
            cutout_leaves: job.has(job_flags::CUTOUT_LEAVES),
            origin,
            out,
            biome,
            parts,
            rng: Rng::default(),
            tint_vals: [0; 64],
            tint_set: 0,
        };
        // BlockPos.betweenClosed(origin, origin + 15): x fastest, then y, then z.
        for z in 0..16i32 {
            for y in 0..16i32 {
                let mut mask = rows[(y * 16 + z) as usize];
                let start = region_index(0, y, z);
                while mask != 0 {
                    let x = mask.trailing_zeros() as i32;
                    mask &= mask - 1;
                    let i = start + x as usize;
                    let st = m.st(i);
                    if st.has(flags::AIR) {
                        continue;
                    }
                    non_air += 1;
                    if st.has(flags::SOLID_RENDER) {
                        vis.set_opaque(x, y, z);
                    }
                    if st.fluid_group != 0 {
                        m.fluid(i, x, y, z, st);
                    }
                    if st.has(flags::RENDER_MODEL) {
                        m.model(i, x, y, z, st);
                    }
                }
            }
        }
    }
    head.visibility = vis.resolve();
    head.detail = non_air;

    // MeshData.sortQuads with VertexSorting.byDistance(camera): stable, farthest first.
    order.clear();
    let translucent = &out.layers[2];
    if job.has(job_flags::SORT_TRANSLUCENT) && translucent.quads > 0 {
        simd::distances(job.camera, &translucent.cx, &translucent.cy, &translucent.cz, dist);
        order.extend(0..translucent.quads);
        let d = &*dist;
        order.sort_by(|&a, &b| d[b as usize].total_cmp(&d[a as usize]));
    }
    Ok(output::encode(&head, out, order, job.has(job_flags::EMIT_CENTROIDS)))
}
