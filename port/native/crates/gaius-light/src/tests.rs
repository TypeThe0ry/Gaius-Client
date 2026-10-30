//! Quick sanity checks: hand-computed scenes, and the bucket increase order
//! against vanilla's first-in-first-out order on random columns.

use crate::column::{
    flags, ColumnSpec, Layer, LightColumn, RING_CELLS, RING_LAYER_BYTES, SIDE_EAST, SIDE_NORTH, SIDE_SOUTH, SIDE_WEST,
};
use crate::section::SectionStates;
use crate::table::{make_props, LightTable};
use alloc::collections::BTreeMap;
use alloc::vec;
use alloc::vec::Vec;

const AIR: u16 = 0;
const STONE: u16 = 1;
const GLASS: u16 = 2;
const TORCH: u16 = 3;
const WATER: u16 = 4;
const BOTTOM_SLAB: u16 = 5;
const TOP_SLAB: u16 = 6;
const GLOWSTONE: u16 = 7;
const LEAVES: u16 = 8;

fn table() -> LightTable {
    let props = vec![
        make_props(0, 0, 0),
        make_props(15, 0, 0),
        make_props(0, 0, 0),
        make_props(0, 14, 0),
        make_props(1, 0, 0),
        make_props(0, 0, 1),
        make_props(0, 0, 2),
        make_props(15, 15, 0),
        make_props(1, 0, 0),
    ];
    // Faces: 0 empty, 1 full, 2 lower half, 3 upper half. Directions are
    // DOWN, UP, NORTH, SOUTH, WEST, EAST.
    let faces = [[0; 6], [1, 0, 2, 2, 2, 2], [0, 1, 3, 3, 3, 3]];
    LightTable::from_parts(props, &faces, 4, |a, b| {
        a == 1 || b == 1 || (a == 2 && b == 3) || (a == 3 && b == 2)
    })
    .unwrap()
}

struct Scene {
    sections: usize,
    neighbours: u8,
    emit_outgoing: bool,
    /// Slot-major flat ids, (y << 8 | z << 4 | x) per section.
    states: Vec<Vec<Vec<u16>>>,
    flags: Vec<Vec<u8>>,
    ring_layers: Vec<Vec<(Vec<u8>, Vec<u8>)>>,
}

impl Scene {
    fn new(sections: usize, neighbours: u8) -> Scene {
        let light = sections + 2;
        Scene {
            sections,
            neighbours,
            emit_outgoing: true,
            states: vec![vec![vec![AIR; 4096]; sections]; 5],
            flags: vec![vec![flags::SKY_STORING | flags::BLOCK_STORING; light]; 5],
            ring_layers: vec![vec![(vec![0; 2048], vec![0; 2048]); light]; 5],
        }
    }

    fn set(&mut self, x: usize, y: usize, z: usize, id: u16) {
        self.states[0][y >> 4][((y & 15) << 8) | (z << 4) | x] = id;
    }

    fn spec(&self) -> ColumnSpec {
        ColumnSpec {
            min_section: 0,
            section_count: self.sections,
            sky_bottom_section: -1,
            sky_light_on: true,
            block_light_on: true,
            emit_outgoing: self.emit_outgoing,
            neighbours: self.neighbours,
        }
    }

    fn load(&self, column: &mut LightColumn, table: &LightTable) {
        column.begin(self.spec()).unwrap();
        for slot in 0..5 {
            if slot > 0 && self.neighbours & (1 << (slot - 1)) == 0 {
                continue;
            }
            for (section, ids) in self.states[slot].iter().enumerate() {
                let bytes: Vec<u8> = ids.iter().flat_map(|id| id.to_le_bytes()).collect();
                column
                    .load_section(slot, section, SectionStates::FlatU16(&bytes), table)
                    .unwrap();
            }
            for (light_section, &value) in self.flags[slot].iter().enumerate() {
                column.set_section_flags(slot, light_section, value).unwrap();
                if slot > 0 {
                    let (sky, block) = &self.ring_layers[slot][light_section];
                    column.load_layer(slot, light_section, Layer::Sky, sky).unwrap();
                    column.load_layer(slot, light_section, Layer::Block, block).unwrap();
                }
            }
        }
        column.compute_sources(table);
    }

    fn light_first_time(&self, column: &mut LightColumn, table: &LightTable) {
        self.load(column, table);
        column.enqueue_block_sources();
        column.propagate(Layer::Block, table);
        column.enqueue_sky_sources();
        column.propagate(Layer::Sky, table);
    }
}

#[test]
fn torch_light_falls_off_by_one_per_block() {
    let table = table();
    let mut scene = Scene::new(1, 0);
    scene.set(8, 8, 8, TORCH);
    let mut column = LightColumn::new();
    scene.light_first_time(&mut column, &table);
    for (x, y, z, expected) in [
        (8, 8, 8, 14),
        (9, 8, 8, 13),
        (8, 12, 8, 10),
        (0, 8, 8, 6),
        (0, 0, 0, 0),
        (12, 12, 12, 2),
    ] {
        assert_eq!(
            column.level(Layer::Block, x, y, z),
            expected,
            "block light at {x} {y} {z}"
        );
    }
    // Torch light reaches the padding sections above and below the world.
    assert_eq!(column.level(Layer::Block, 8, 17, 8), 5);
    assert_eq!(column.level(Layer::Block, 8, -1, 8), 5);
}

#[test]
fn sky_light_fills_open_air_and_creeps_under_a_roof() {
    let table = table();
    let mut scene = Scene::new(2, 0);
    for x in 0..16 {
        for z in 0..16 {
            for y in 0..4 {
                scene.set(x, y, z, STONE);
            }
        }
    }
    for x in 4..9 {
        for z in 4..9 {
            scene.set(x, 10, z, STONE);
        }
    }
    let mut column = LightColumn::new();
    scene.light_first_time(&mut column, &table);
    assert_eq!(column.lowest_source(0, 0), 4);
    assert_eq!(column.lowest_source(6, 6), 11);
    assert_eq!(column.level(Layer::Sky, 0, 20, 0), 15);
    assert_eq!(column.level(Layer::Sky, 0, 4, 0), 15);
    assert_eq!(column.level(Layer::Sky, 0, 3, 0), 0);
    assert_eq!(column.level(Layer::Sky, 6, 11, 6), 15);
    assert_eq!(column.level(Layer::Sky, 6, 5, 6), 12);
    assert_eq!(column.level(Layer::Sky, 4, 9, 4), 14);
}

#[test]
fn slabs_block_light_through_their_full_faces() {
    let table = table();
    let mut scene = Scene::new(1, 0);
    // A torch with a bottom slab right above it: light cannot go up through
    // the slab's full bottom face, so the cell above the slab is lit around it.
    scene.set(8, 4, 8, TORCH);
    scene.set(8, 5, 8, BOTTOM_SLAB);
    scene.set(3, 4, 3, TORCH);
    scene.set(3, 5, 3, TOP_SLAB);
    let mut column = LightColumn::new();
    scene.light_first_time(&mut column, &table);
    assert_eq!(column.level(Layer::Block, 8, 5, 8), 11);
    assert_eq!(column.level(Layer::Block, 3, 5, 3), 13);
}

#[test]
fn removing_a_torch_darkens_the_column() {
    let table = table();
    let mut scene = Scene::new(1, 0);
    scene.set(8, 8, 8, TORCH);
    let mut column = LightColumn::new();
    scene.light_first_time(&mut column, &table);
    column.set_props(8, 8, 8, 0);
    column.compute_sources(&table);
    column.check_block(8, 8, 8);
    column.propagate(Layer::Block, &table);
    for y in -1..17 {
        for z in 0..16 {
            for x in 0..16 {
                assert_eq!(column.level(Layer::Block, x, y, z), 0);
            }
        }
    }
}

#[test]
fn sky_reaches_the_ring_and_reports_it() {
    let table = table();
    let mut scene = Scene::new(1, 1 << SIDE_EAST);
    // A stone roof over the east neighbour's touching column keeps its sky
    // sources high, so light leaks sideways out of the column below the roof.
    for z in 0..16 {
        for y in 0..16 {
            scene.states[1 + SIDE_EAST][0][(y << 8) | (z << 4)] = if y == 12 { STONE } else { AIR };
        }
    }
    let mut column = LightColumn::new();
    scene.light_first_time(&mut column, &table);
    assert_eq!(column.ring_level(Layer::Sky, SIDE_EAST, 5, 6), 14);
    let records: Vec<_> = column
        .outgoing()
        .iter()
        .filter(|r| r.side == SIDE_EAST as u8 && r.y == 6)
        .collect();
    assert!(records
        .iter()
        .any(|r| r.level == 14 && r.kind == crate::outgoing::SKY_INCREASE));
}

struct Rng(u64);

impl Rng {
    fn next(&mut self) -> u64 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        self.0
    }

    fn below(&mut self, n: u64) -> u64 {
        self.next() % n
    }
}

fn random_scene(seed: u64) -> Scene {
    let mut rng = Rng(seed);
    let mut scene = Scene::new(3, 0b1111);
    let palette = [
        AIR,
        AIR,
        AIR,
        AIR,
        STONE,
        STONE,
        GLASS,
        TORCH,
        WATER,
        BOTTOM_SLAB,
        TOP_SLAB,
        GLOWSTONE,
        LEAVES,
    ];
    for slot in 0..5 {
        for section in 0..scene.sections {
            let density = rng.below(4);
            for cell in 0..4096 {
                let id = if rng.below(4) < density {
                    palette[rng.below(palette.len() as u64) as usize]
                } else {
                    AIR
                };
                scene.states[slot][section][cell] = id;
            }
        }
        for light_section in 0..scene.sections + 2 {
            scene.flags[slot][light_section] = match rng.below(8) {
                0 => flags::SKY_STORING,
                1 => flags::BLOCK_STORING,
                _ => flags::SKY_STORING | flags::BLOCK_STORING,
            };
            if slot > 0 {
                let (sky, block) = &mut scene.ring_layers[slot][light_section];
                for byte in sky.iter_mut().chain(block.iter_mut()) {
                    *byte = if rng.below(3) == 0 { rng.next() as u8 } else { 0 };
                }
            }
        }
    }
    scene
}

/// Sky levels, block levels (column then ring, by y) and the outgoing records.
type Snapshot = (Vec<u8>, Vec<u8>, BTreeMap<(u8, u8, u8, i32), (u8, u16)>);

fn snapshot(column: &LightColumn, sections: usize) -> Snapshot {
    let mut levels = (Vec::new(), Vec::new());
    for y in -16..(sections as i32 + 1) * 16 {
        for z in 0..16 {
            for x in 0..16 {
                levels.0.push(column.level(Layer::Sky, x, y, z));
                levels.1.push(column.level(Layer::Block, x, y, z));
            }
        }
        for side in [SIDE_NORTH, SIDE_SOUTH, SIDE_WEST, SIDE_EAST] {
            for along in 0..16 {
                levels.0.push(column.ring_level(Layer::Sky, side, along, y));
                levels.1.push(column.ring_level(Layer::Block, side, along, y));
            }
        }
    }
    // Vanilla order may raise a ring cell several times; the last (highest)
    // report is the one that sticks.
    let mut records = BTreeMap::new();
    for r in column.outgoing() {
        let slot = records.entry((r.kind, r.side, r.along, r.y)).or_insert((0u8, 0u16));
        if r.level >= slot.0 {
            *slot = (r.level, r.count);
        }
    }
    (levels.0, levels.1, records)
}

#[test]
fn bucket_order_matches_vanilla_order_on_random_columns() {
    let table = table();
    for seed in 1..41u64 {
        let scene = random_scene(seed.wrapping_mul(0x9e37_79b9_7f4a_7c15));
        let mut fast = LightColumn::new();
        let mut vanilla = LightColumn::new();
        vanilla.set_vanilla_increase_order(true);
        scene.light_first_time(&mut fast, &table);
        scene.light_first_time(&mut vanilla, &table);
        let (a, b) = (snapshot(&fast, scene.sections), snapshot(&vanilla, scene.sections));
        let sky_diff = a.0.iter().zip(&b.0).filter(|(x, y)| x != y).count();
        let block_diff = a.1.iter().zip(&b.1).filter(|(x, y)| x != y).count();
        let rec_diff: Vec<_> = a.2.iter().filter(|(k, v)| b.2.get(k) != Some(v)).take(5).collect();
        let rec_diff2: Vec<_> = b.2.iter().filter(|(k, v)| a.2.get(k) != Some(v)).take(5).collect();
        assert!(
            a == b,
            "first light, seed {seed}: sky {sky_diff} block {block_diff} records {rec_diff:?} / {rec_diff2:?}"
        );

        // Then a burst of block changes through checkNode.
        let mut rng = Rng(seed);
        let mut changes = Vec::new();
        for _ in 0..12 {
            let (x, y, z) = (rng.below(16) as i32, rng.below(48) as i32, rng.below(16) as i32);
            let id = [AIR, STONE, TORCH, GLOWSTONE, BOTTOM_SLAB][rng.below(5) as usize];
            changes.push((x, y, z, table.props(id as u32).unwrap()));
        }
        for column in [&mut fast, &mut vanilla] {
            for &(x, y, z, props) in &changes {
                column.set_props(x, y, z, props);
            }
            column.compute_sources(&table);
            for &(x, y, z, _) in &changes {
                column.check_block(x, y, z);
            }
            column.propagate(Layer::Block, &table);
            for &(x, y, z, _) in &changes {
                column.check_sky(x, y, z);
            }
            column.propagate(Layer::Sky, &table);
        }
        let (a, b) = (snapshot(&fast, scene.sections), snapshot(&vanilla, scene.sections));
        let sky_diff: Vec<_> =
            a.0.iter()
                .zip(&b.0)
                .enumerate()
                .filter(|(_, (x, y))| x != y)
                .take(6)
                .collect();
        let block_diff: Vec<_> =
            a.1.iter()
                .zip(&b.1)
                .enumerate()
                .filter(|(_, (x, y))| x != y)
                .take(6)
                .collect();
        assert!(
            a.0 == b.0 && a.1 == b.1,
            "after changes, seed {seed}: sky {sky_diff:?} block {block_diff:?} changes {changes:?}"
        );
    }
}

/// Index into a neighbour section of the cell that touches the column at
/// `(y, along)` on `side`.
fn facing_index(side: usize, y: usize, along: usize) -> usize {
    let (x, z) = match side {
        SIDE_NORTH => (along, 15),
        SIDE_SOUTH => (along, 0),
        SIDE_WEST => (15, along),
        _ => (0, along),
    };
    (y << 8) | (z << 4) | x
}

impl Scene {
    /// Like [`Scene::load`], but the neighbours arrive as ring slices only,
    /// the way `light_column` jobs ship them.
    fn load_ring(&self, column: &mut LightColumn, table: &LightTable) {
        column.begin(self.spec()).unwrap();
        for (section, ids) in self.states[0].iter().enumerate() {
            let bytes: Vec<u8> = ids.iter().flat_map(|id| id.to_le_bytes()).collect();
            column
                .load_section(0, section, SectionStates::FlatU16(&bytes), table)
                .unwrap();
        }
        for (light_section, &value) in self.flags[0].iter().enumerate() {
            column.set_section_flags(0, light_section, value).unwrap();
        }
        for side in 0..4 {
            if self.neighbours & (1 << side) == 0 {
                continue;
            }
            let slot = side + 1;
            for (section, ids) in self.states[slot].iter().enumerate() {
                let mut palette: Vec<u32> = Vec::new();
                let mut indices = vec![0u8; RING_CELLS];
                for y in 0..16 {
                    for along in 0..16 {
                        let id = ids[facing_index(side, y, along)] as u32;
                        let index = palette.iter().position(|&p| p == id).unwrap_or_else(|| {
                            palette.push(id);
                            palette.len() - 1
                        });
                        indices[(y << 4) | along] = index as u8;
                    }
                }
                let indices = if palette.len() == 1 { None } else { Some(&indices[..]) };
                column
                    .load_ring_states(side, section, &palette, indices, table)
                    .unwrap();
            }
            for (light_section, &value) in self.flags[slot].iter().enumerate() {
                column.set_section_flags(slot, light_section, value).unwrap();
                let (sky, block) = &self.ring_layers[slot][light_section];
                for (layer, data) in [(Layer::Sky, sky), (Layer::Block, block)] {
                    let mut ring = vec![0u8; RING_LAYER_BYTES];
                    for y in 0..16 {
                        for along in 0..16 {
                            let i = facing_index(side, y, along);
                            let level = (data[i >> 1] >> ((i & 1) << 2)) & 15;
                            let r = (y << 4) | along;
                            ring[r >> 1] |= level << ((r & 1) << 2);
                        }
                    }
                    column.load_ring_layer(side, light_section, layer, &ring).unwrap();
                }
            }
        }
        column.compute_sources(table);
    }
}

#[test]
fn ring_slices_light_like_full_neighbour_sections() {
    let table = table();
    for seed in 1..21u64 {
        let scene = random_scene(seed.wrapping_mul(0x2545_f491_4f6c_dd1d));
        let mut full = LightColumn::new();
        let mut ring = LightColumn::new();
        scene.load(&mut full, &table);
        scene.load_ring(&mut ring, &table);
        for column in [&mut full, &mut ring] {
            column.enqueue_block_sources();
            column.propagate(Layer::Block, &table);
            column.enqueue_sky_sources();
            column.propagate(Layer::Sky, &table);
        }
        assert!(
            snapshot(&full, scene.sections) == snapshot(&ring, scene.sections),
            "seed {seed}: ring slices light differently from full neighbour sections"
        );
    }
}

impl Scene {
    /// A `light_column` job with PULL_RING: INITIAL lights the column from
    /// nothing, CHECKS starts from `previous` (the column's stored layers) and
    /// runs `checkNode` at `checks`.
    fn light_settled(
        &self,
        column: &mut LightColumn,
        table: &LightTable,
        previous: Option<&Layers>,
        checks: &[(i32, i32, i32)],
    ) {
        self.load(column, table);
        if let Some(previous) = previous {
            for light_section in 0..self.sections + 2 {
                column
                    .load_layer(0, light_section, Layer::Sky, &previous.0[light_section])
                    .unwrap();
                column
                    .load_layer(0, light_section, Layer::Block, &previous.1[light_section])
                    .unwrap();
            }
        } else {
            column.enqueue_block_sources();
        }
        for &(x, y, z) in checks {
            column.check_block(x, y, z);
        }
        column.enqueue_ring_light(Layer::Block);
        column.propagate(Layer::Block, table);
        if previous.is_none() {
            column.enqueue_sky_sources();
        }
        for &(x, y, z) in checks {
            column.check_sky(x, y, z);
        }
        column.enqueue_ring_light(Layer::Sky);
        column.propagate(Layer::Sky, table);
    }
}

/// The column's stored layers per light section, sky then block.
type Layers = (Vec<Vec<u8>>, Vec<Vec<u8>>);

fn layers(column: &LightColumn, sections: usize) -> Layers {
    let mut out: Layers = (Vec::new(), Vec::new());
    for light_section in 0..sections + 2 {
        for (layer, list) in [(Layer::Sky, &mut out.0), (Layer::Block, &mut out.1)] {
            let mut nibbles = vec![0u8; 2048];
            column.write_layer(light_section, layer, &mut nibbles);
            list.push(nibbles);
        }
    }
    out
}

fn set_nibble(data: &mut [u8], index: usize, level: u8) {
    let shift = (index & 1) << 2;
    data[index >> 1] = (data[index >> 1] & !(15 << shift)) | (level << shift);
}

/// Regression for the 1.21.11 emitter-remove goldens: a neighbour emitter that
/// touched the column is gone and the settled neighbour relit that ring cell
/// to a lower level. A settled ring is a boundary condition: checking the
/// changed border cell without EMIT_OUTGOING ends exactly where a fresh light
/// with the same ring does. With EMIT_OUTGOING the ring is live storage: the
/// decrease zeroes the kernel's copy of the ring cell and reports it, and the
/// column only gets the light back when the Java side replays that record.
#[test]
fn settled_ring_checks_end_where_a_fresh_light_does() {
    let table = table();
    let face = facing_index(SIDE_WEST, 8, 8);
    let mut before = Scene::new(1, 1 << SIDE_WEST);
    before.states[1 + SIDE_WEST][0][face] = GLOWSTONE;
    set_nibble(&mut before.ring_layers[1 + SIDE_WEST][1].1, face, 15);
    let mut column = LightColumn::new();
    before.light_settled(&mut column, &table, None, &[]);
    assert_eq!(column.level(Layer::Block, 0, 8, 8), 14);
    let previous = layers(&column, 1);

    for emit_outgoing in [false, true] {
        let mut after = Scene::new(1, 1 << SIDE_WEST);
        after.emit_outgoing = emit_outgoing;
        set_nibble(&mut after.ring_layers[1 + SIDE_WEST][1].1, face, 10);
        let mut fresh = LightColumn::new();
        after.light_settled(&mut fresh, &table, None, &[]);
        assert_eq!(fresh.level(Layer::Block, 0, 8, 8), 9);
        let mut checked = LightColumn::new();
        after.light_settled(&mut checked, &table, Some(&previous), &[(0, 8, 8)]);
        if !emit_outgoing {
            assert!(
                layers(&checked, 1) == layers(&fresh, 1),
                "settled checks differ from a fresh light"
            );
            continue;
        }
        assert_eq!(checked.level(Layer::Block, 0, 8, 8), 0);
        assert!(checked
            .outgoing()
            .iter()
            .any(|r| r.kind == crate::outgoing::BLOCK_DECREASE
                && r.side == SIDE_WEST as u8
                && r.along == 8
                && r.y == 8
                && r.level == 14));
    }
}

/// Incremental jobs against a settled ring (block changes in the column and in
/// the ring, changed ring levels, checks at the changed cells and at the border
/// cells facing changed ring cells) end exactly where a fresh light ends.
#[test]
fn settled_ring_checks_match_a_fresh_light_on_random_columns() {
    let table = table();
    let ids = [AIR, STONE, TORCH, GLOWSTONE, BOTTOM_SLAB, TOP_SLAB, GLASS, LEAVES];
    for seed in 1..41u64 {
        let mut scene = random_scene(seed.wrapping_mul(0x6c8e_9cf5_7093_2bd5));
        scene.emit_outgoing = false;
        let mut column = LightColumn::new();
        scene.light_settled(&mut column, &table, None, &[]);
        let previous = layers(&column, scene.sections);

        let mut rng = Rng(seed ^ 0x5851_f42d_4c95_7f2d);
        let mut checks = Vec::new();
        for _ in 0..16 {
            let (x, y, z) = (rng.below(16) as usize, rng.below(48) as usize, rng.below(16) as usize);
            scene.set(x, y, z, ids[rng.below(ids.len() as u64) as usize]);
            checks.push((x as i32, y as i32, z as i32));
        }
        for _ in 0..24 {
            let side = rng.below(4) as usize;
            let (y, along) = (rng.below(48) as usize, rng.below(16) as usize);
            let index = facing_index(side, y & 15, along);
            if rng.below(2) == 0 {
                scene.states[1 + side][y >> 4][index] = ids[rng.below(ids.len() as u64) as usize];
            }
            let (sky, block) = &mut scene.ring_layers[1 + side][(y >> 4) + 1];
            for data in [sky, block] {
                if rng.below(2) == 0 {
                    set_nibble(data, index, rng.below(16) as u8);
                }
            }
            let (x, z) = match side {
                SIDE_NORTH => (along as i32, 0),
                SIDE_SOUTH => (along as i32, 15),
                SIDE_WEST => (0, along as i32),
                _ => (15, along as i32),
            };
            checks.push((x, y as i32, z));
        }

        let mut fresh = LightColumn::new();
        scene.light_settled(&mut fresh, &table, None, &[]);
        let mut checked = LightColumn::new();
        scene.light_settled(&mut checked, &table, Some(&previous), &checks);
        let (a, b) = (layers(&checked, scene.sections), layers(&fresh, scene.sections));
        let sky = a.0.iter().zip(&b.0).filter(|(x, y)| x != y).count();
        let block = a.1.iter().zip(&b.1).filter(|(x, y)| x != y).count();
        assert!(
            a == b,
            "seed {seed}: settled checks differ from a fresh light (sky sections {sky}, block sections {block})"
        );
    }
}

/// Skylight source changes at a section boundary must update both the section
/// containing the changed block and the section immediately above it.  This is
/// the path used when a chunk sends a block update at y=16 (the first cell of
/// the second section): the incremental check must settle to the same layers as
/// a fresh light build, then do so again when the roof is removed.
#[test]
fn skylight_roof_changes_at_section_border_match_fresh_light() {
    let table = table();
    let mut scene = Scene::new(2, 0);

    // Initial open column. Keep the settled layers as the host would between
    // light jobs.
    let mut initial = LightColumn::new();
    scene.light_settled(&mut initial, &table, None, &[]);
    let previous = layers(&initial, scene.sections);

    // Add a roof exactly on the section boundary (y = 16), then process only
    // that changed position incrementally.
    scene.set(8, 16, 8, STONE);
    let mut incremental = LightColumn::new();
    scene.light_settled(&mut incremental, &table, Some(&previous), &[(8, 16, 8)]);
    let mut fresh = LightColumn::new();
    scene.light_settled(&mut fresh, &table, None, &[]);
    assert_eq!(
        layers(&incremental, scene.sections),
        layers(&fresh, scene.sections),
        "adding a roof at the section border leaves stale skylight"
    );
    assert_eq!(incremental.level(Layer::Sky, 8, 15, 8), 14);
    assert_eq!(incremental.level(Layer::Sky, 8, 16, 8), 0);

    // Remove the same roof and verify that skylight is restored across the
    // boundary using the now-settled incremental layers as the previous job.
    let previous = layers(&incremental, scene.sections);
    scene.set(8, 16, 8, AIR);
    let mut incremental = LightColumn::new();
    scene.light_settled(&mut incremental, &table, Some(&previous), &[(8, 16, 8)]);
    let mut fresh = LightColumn::new();
    scene.light_settled(&mut fresh, &table, None, &[]);
    assert_eq!(
        layers(&incremental, scene.sections),
        layers(&fresh, scene.sections),
        "removing a roof at the section border leaves stale skylight"
    );
    assert_eq!(incremental.level(Layer::Sky, 8, 16, 8), 15);
}
