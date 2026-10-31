//! A small synthetic model table and deterministic scenes over it, for the
//! unit tests and for the wasm probe (`gaius-mesher-wasm/examples/wasm_probe.rs`)
//! that proves the simd128 and baseline modules answer byte for byte like the
//! native build. Not a vanilla table: just enough states to reach every path
//! (culling, AO with and without partial faces, tint, weighted variants,
//! random offsets, fluids, translucency).

use crate::job::{SectionJobData, GRASS_MODIFIER_SWAMP, MARGIN, REGION};
use crate::table::*;

pub const AIR: u32 = 0;
pub const STONE: u32 = 1;
pub const WATER: u32 = 2;
pub const ODD: u32 = 3;
pub const TUFT: u32 = 4;
pub const GLASS: u32 = 5;
pub const LANTERN: u32 = 6;
pub const FLOWING_WATER: u32 = 7;

/// Full-cube face quads in FaceBakery vertex order, one per Direction.
pub fn cube_faces() -> Vec<[f32; 12]> {
    vec![
        [0., 0., 1., 0., 0., 0., 1., 0., 0., 1., 0., 1.], // DOWN
        [0., 1., 0., 0., 1., 1., 1., 1., 1., 1., 1., 0.], // UP
        [1., 1., 0., 1., 0., 0., 0., 0., 0., 0., 1., 0.], // NORTH
        [0., 1., 1., 0., 0., 1., 1., 0., 1., 1., 1., 1.], // SOUTH
        [0., 1., 0., 0., 0., 0., 0., 0., 1., 0., 1., 1.], // WEST
        [1., 1., 1., 1., 0., 1., 1., 0., 0., 1., 1., 0.], // EAST
    ]
}

/// Adds six cube quads in `layer` and a part holding them; returns the part id.
fn cube_part(t: &mut ModelTable, layer: u8, emission: u8) -> u32 {
    let first_ref = t.quad_refs.len() as u32;
    for d in 0..6u8 {
        t.quad_refs.push(t.quads.len() as u32);
        t.quads.push(Quad {
            geometry: d as u32,
            uv: 0,
            tint_index: -1,
            direction: d,
            shade_face: d,
            layer,
            emission,
        });
    }
    t.parts.push(Part {
        first_ref,
        end: [1, 2, 3, 4, 5, 6, 6],
        use_ao: true,
    });
    t.parts.len() as u32 - 1
}

fn single_group(t: &mut ModelTable, part: u32) -> u32 {
    t.entries.push(Entry { weight: 1, part });
    t.groups.push(Group {
        first_entry: t.entries.len() as u32 - 1,
        entry_count: 1,
        weighted: false,
        total_weight: 1,
    });
    t.groups.len() as u32 - 1
}

/// The synthetic table at `epoch`.
pub fn table(epoch: u32) -> ModelTable {
    let sprite = Sprite {
        u0: 0.25,
        u1: 0.3125,
        v0: 0.5,
        v1: 0.5625,
    };
    let mut t = ModelTable {
        semantics: 2,
        epoch,
        masks: vec![MASK_NONE, MASK_ALL],
        geometry: cube_faces(),
        uvs: vec![
            [0.0, 0.0, 0.0, 0.0625, 0.0625, 0.0625, 0.0625, 0.0],
            [0.5, 0.5, 0.5, 0.53125, 0.53125, 0.53125, 0.53125, 0.5],
        ],
        air_ids: vec![AIR],
        tints: vec![Tint {
            kind: tint::GRASS,
            argb: 0,
        }],
        ..ModelTable::default()
    };
    let stone_part = cube_part(&mut t, LAYER_SOLID, 0);
    let stone_group = single_group(&mut t, stone_part);
    let glass_part = cube_part(&mut t, LAYER_TRANSLUCENT, 0);
    let glass_group = single_group(&mut t, glass_part);
    let lantern_part = cube_part(&mut t, LAYER_CUTOUT, 0);
    let lantern_group = single_group(&mut t, lantern_part);

    // A tinted tuft: one small UP quad (a partial face) with two weighted variants.
    t.geometry
        .push([0.25, 0.5, 0.25, 0.25, 0.5, 0.75, 0.75, 0.5, 0.75, 0.75, 0.5, 0.25]);
    let tuft_geometry = t.geometry.len() as u32 - 1;
    let tuft_entry = t.entries.len() as u32;
    for uv in 0..2u32 {
        t.quad_refs.push(t.quads.len() as u32);
        t.quads.push(Quad {
            geometry: tuft_geometry,
            uv,
            tint_index: 0,
            direction: 1,
            shade_face: 1,
            layer: LAYER_CUTOUT,
            emission: 0,
        });
        t.parts.push(Part {
            first_ref: t.quad_refs.len() as u32 - 1,
            end: [0, 0, 0, 0, 0, 0, 1],
            use_ao: true,
        });
        t.entries.push(Entry {
            weight: 3 - 2 * uv,
            part: t.parts.len() as u32 - 1,
        });
    }
    t.groups.push(Group {
        first_entry: tuft_entry,
        entry_count: 2,
        weighted: true,
        total_weight: 4,
    });
    let tuft_group = t.groups.len() as u32 - 1;

    t.fluids.push(FluidModel {
        layer: LAYER_TRANSLUCENT,
        has_overlay: true,
        tint_kind: tint::WATER,
        tint_argb: 0,
        still: sprite,
        flowing: sprite,
        overlay: sprite,
    });
    let air = StateInfo {
        flags: flags::AIR | flags::LIGHT_PERMEABLE,
        fluid_model: NO_FLUID_MODEL,
        shade: 1.0,
        ..StateInfo::default()
    };
    let stone = StateInfo {
        flags: flags::SOLID_RENDER | flags::RENDER_MODEL | flags::COLLISION_FULL | flags::SOLID,
        block_id: 1,
        fluid_model: NO_FLUID_MODEL,
        faces: [FACE_FULL; 6],
        shade: 0.2,
        first_group: stone_group,
        group_count: 1,
        sturdy: 0x3F,
        ..StateInfo::default()
    };
    let water = StateInfo {
        flags: flags::LIGHT_PERMEABLE,
        block_id: 2,
        fluid_model: 0,
        shade: 1.0,
        fluid_height: 0.8888889,
        skip: skip::SAME_FLUID,
        fluid_group: 1,
        ..StateInfo::default()
    };
    let odd = StateInfo {
        flags: flags::UNSUPPORTED | flags::RENDER_MODEL,
        block_id: 3,
        fluid_model: NO_FLUID_MODEL,
        shade: 1.0,
        ..StateInfo::default()
    };
    let tuft = StateInfo {
        flags: flags::RENDER_MODEL | flags::LIGHT_PERMEABLE,
        block_id: 4,
        fluid_model: NO_FLUID_MODEL,
        shade: 1.0,
        max_h: 0.25,
        first_group: tuft_group,
        group_count: 1,
        tint_count: 1,
        offset_type: 1,
        ..StateInfo::default()
    };
    let glass = StateInfo {
        flags: flags::RENDER_MODEL | flags::LIGHT_PERMEABLE | flags::COLLISION_FULL | flags::HALF_TRANSPARENT,
        block_id: 5,
        fluid_model: NO_FLUID_MODEL,
        shade: 1.0,
        first_group: glass_group,
        group_count: 1,
        skip: skip::SAME_BLOCK,
        ..StateInfo::default()
    };
    let lantern = StateInfo {
        flags: flags::RENDER_MODEL | flags::LIGHT_PERMEABLE | flags::COLLISION_FULL,
        block_id: 6,
        fluid_model: NO_FLUID_MODEL,
        shade: 1.0,
        first_group: lantern_group,
        group_count: 1,
        emission: 15,
        ..StateInfo::default()
    };
    let flowing = StateInfo {
        fluid_height: 0.5,
        ..water
    };
    t.states = vec![air, stone, water, odd, tuft, glass, lantern, flowing];
    t
}

/// xorshift32, for repeatable scenes.
fn next(state: &mut u32) -> u32 {
    *state ^= *state << 13;
    *state ^= *state >> 17;
    *state ^= *state << 5;
    *state
}

/// A deterministic scene: uneven stone ground, a water pool, tufts, glass and lanterns, with
/// a light gradient and a swamp-tinted biome next to a plain one.
pub fn scene(epoch: u32, seed: u32) -> SectionJobData {
    let mut job = SectionJobData::empty(epoch);
    let mut rng = seed.max(1);
    job.section = [seed as i32 % 7 - 3, 4, -(seed as i32 % 5)];
    job.biome_zoom_seed = 0x1234_5678_9ABC_DEF0u64 as i64 ^ seed as i64;
    job.palette.push(crate::job::BiomeColors {
        grass: -9_801_671,
        grass_modifier: GRASS_MODIFIER_SWAMP,
        foliage: -9_801_671,
        dry_foliage: -10_732_494,
        water: -10_195_342,
    });
    for (k, q) in job.biome_quarts.iter_mut().enumerate() {
        *q = ((k / 7) % 2) as u8;
    }
    for (k, b) in job.swamp_mask.iter_mut().enumerate() {
        *b = (k as u8).wrapping_mul(37) ^ seed as u8;
    }
    let lo = -MARGIN;
    let hi = REGION as i32 - MARGIN;
    for z in lo..hi {
        for x in lo..hi {
            let ground = 3 + (next(&mut rng) % 4) as i32;
            for y in lo..hi {
                let id = if y < ground {
                    STONE
                } else if y == ground && (4..10).contains(&x) && (4..10).contains(&z) {
                    WATER
                } else if y == ground && x == 10 && (4..10).contains(&z) {
                    FLOWING_WATER
                } else if y == ground && next(&mut rng).is_multiple_of(5) {
                    TUFT
                } else if y > ground && y < ground + 4 && x == 12 && z.rem_euclid(3) == 0 {
                    GLASS
                } else if y == ground + 1 && x == 2 && z == 13 {
                    LANTERN
                } else {
                    AIR
                };
                job.set(x, y, z, id);
                let i = crate::job::region_index(x, y, z);
                let sky = if y > ground { 15 } else { 15 - (ground - y).min(15) } as u8;
                let block = ((x + z + y).rem_euclid(9)) as u8;
                job.light[i] = (sky << 4) | block;
            }
        }
    }
    job
}
