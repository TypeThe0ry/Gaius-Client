//! A small but complete generator IR (a simplified overworld) used by the
//! sanity tests of this crate and of the wasm kernel. It exercises every
//! section: noises, an interpolated final density, aquifers, ore veins, a
//! spline, surface rules and a multi-noise biome list.

use super::*;
use crate::climate::quantize;

/// State table of [`overworld`].
pub mod state {
    pub const AIR: u32 = 0;
    pub const STONE: u32 = 1;
    pub const WATER: u32 = 2;
    pub const LAVA: u32 = 3;
    pub const GRASS: u32 = 4;
    pub const DIRT: u32 = 5;
    pub const SAND: u32 = 6;
    pub const TERRACOTTA: u32 = 7;
    pub const SNOW_BLOCK: u32 = 14;
    pub const PACKED_ICE: u32 = 15;
    pub const COPPER_ORE: u32 = 16;
    pub const COUNT: u32 = 22;
}

fn node(op: Op, min: f64, max: f64) -> Node {
    Node { op, axes: 7, min, max }
}

struct Builder {
    nodes: Vec<Node>,
}

impl Builder {
    fn add(&mut self, op: Op, min: f64, max: f64) -> u32 {
        self.nodes.push(node(op, min, max));
        (self.nodes.len() - 1) as u32
    }
}

fn parity(name: &str, first_octave: i32, amplitudes: &[f64]) -> NoiseDef {
    NoiseDef {
        name: name.to_string(),
        legacy_nether_offset: None,
        params: NoiseParams::Parity {
            first_octave,
            amplitudes: amplitudes.to_vec(),
        },
    }
}

/// A simplified overworld for `semantics` (router or compiled samplers) and `seed`.
pub fn overworld(semantics: gaius_noise::Semantics, seed: i64) -> Ir {
    let synth32 = semantics.uses_synth32();
    let noises = vec![
        parity("minecraft:temperature", -10, &[1.5, 0.0, 1.0, 0.0, 0.0, 0.0]),
        parity("minecraft:vegetation", -8, &[1.0, 1.0, 0.0, 0.0, 0.0, 0.0]),
        parity(
            "minecraft:continentalness",
            -9,
            &[1.0, 1.0, 2.0, 2.0, 2.0, 1.0, 1.0, 1.0, 1.0],
        ),
        parity("minecraft:erosion", -9, &[1.0, 1.0, 0.0, 1.0, 1.0]),
        parity("minecraft:ridge", -7, &[1.0, 2.0, 1.0, 0.0, 0.0, 0.0]),
        parity("minecraft:offset", -3, &[1.0, 1.0, 1.0, 0.0]),
        parity("minecraft:cave_layer", -8, &[1.0]),
        parity("minecraft:aquifer_barrier", -3, &[1.0]),
        parity("minecraft:aquifer_fluid_level_floodedness", -7, &[1.0]),
        parity("minecraft:aquifer_fluid_level_spread", -5, &[1.0]),
        parity("minecraft:aquifer_lava", -1, &[1.0]),
        parity("minecraft:surface", -6, &[1.0, 1.0, 1.0]),
        parity("minecraft:surface_secondary", -6, &[1.0, 1.0, 0.0, 1.0]),
        parity("minecraft:clay_bands_offset", -8, &[1.0]),
        parity("minecraft:ore_veininess", -8, &[1.0]),
        parity("minecraft:ore_vein_a", -7, &[1.0]),
        parity("minecraft:ore_gap", -5, &[1.0]),
    ];
    let mut b = Builder { nodes: Vec::new() };
    let noise = |b: &mut Builder, n: u32, xz: f64, y: f64| {
        b.add(
            Op::Noise {
                noise: n,
                xz_scale: xz,
                y_scale: y,
                shift: None,
            },
            -1.5,
            1.5,
        )
    };
    let temperature = noise(&mut b, 0, 0.25, 0.0);
    let vegetation = noise(&mut b, 1, 0.25, 0.0);
    let continents = noise(&mut b, 2, 0.25, 0.0);
    let erosion = noise(&mut b, 3, 0.25, 0.0);
    let ridges = noise(&mut b, 4, 0.25, 0.0);
    let gradient = b.add(
        Op::YClampedGradient {
            from_y: -64,
            to_y: 320,
            from_value: 1.5,
            to_value: -1.5,
        },
        -1.5,
        1.5,
    );
    let depth = b.add(Op::Binary(Binary::Add, gradient, continents), -3.0, 3.0);
    // offset spline over continentalness: -0.2 at the ocean, 0.3 inland
    let splines = vec![
        SplineDef::Constant(-0.2),
        SplineDef::Constant(0.05),
        SplineDef::Constant(0.3),
        SplineDef::Multipoint {
            coordinate: continents,
            locations: vec![-0.5, 0.0, 0.5],
            derivatives: vec![0.0, 0.5, 0.0],
            values: vec![0, 1, 2],
        },
    ];
    let offset = b.add(Op::Spline(3), -0.5, 0.5);
    let offset = b.add(
        Op::Marker(if synth32 { Marker::CacheOnce } else { Marker::FlatCache }, offset),
        -0.5,
        0.5,
    );
    let shaped = b.add(Op::Binary(Binary::Add, gradient, offset), -2.0, 2.0);
    let cave = noise(&mut b, 6, 1.0, 8.0);
    let cave_scaled = b.add(
        Op::MulOrAdd {
            add: false,
            input: cave,
            argument: 0.3,
        },
        -0.45,
        0.45,
    );
    let cave_scaled = if synth32 {
        let k = b.add(Op::Const(0.3), 0.3, 0.3);
        b.add(Op::Binary(Binary::Mul, cave, k), -0.45, 0.45)
    } else {
        cave_scaled
    };
    let raw = b.add(Op::Binary(Binary::Add, shaped, cave_scaled), -2.5, 2.5);
    let clamped = b.add(Op::Clamp(raw, -1.0, 1.0), -1.0, 1.0);
    let final_density = b.add(
        Op::Interpolated {
            input: clamped,
            cell_xz: 4,
            cell_y: 8,
        },
        -1.0,
        1.0,
    );
    let final_density = if synth32 {
        let beard = b.add(Op::Beardifier, f64::NEG_INFINITY, f64::INFINITY);
        b.add(
            Op::Binary(Binary::Add, final_density, beard),
            f64::NEG_INFINITY,
            f64::INFINITY,
        )
    } else {
        final_density
    };
    let barrier = noise(&mut b, 7, 1.0, 0.5);
    let floodedness = noise(&mut b, 8, 1.0, 0.67);
    let spread = noise(&mut b, 9, 1.0, 0.7142857142857143);
    let lava = noise(&mut b, 10, 1.0, 1.0);
    let exclusion = b.add(Op::Const(-1.0), -1.0, 1.0);
    let surface_level = b.add(Op::Const(60.0), 60.0, 60.0);
    let upper = b.add(Op::Const(320.0), 320.0, 320.0);
    let preliminary = b.add(
        Op::FindTopSurface {
            density: shaped,
            upper_bound: upper,
            lower_bound: -64,
            cell_height: 8,
        },
        -64.0,
        320.0,
    );
    let vein_toggle = noise(&mut b, 14, 1.5, 1.5);
    let vein_toggle = b.add(
        Op::Interpolated {
            input: vein_toggle,
            cell_xz: 4,
            cell_y: 8,
        },
        -1.5,
        1.5,
    );
    let vein_ridged = noise(&mut b, 15, 4.0, 4.0);
    let vein_gap = noise(&mut b, 16, 1.0, 1.0);

    let mut roots = Roots::default();
    let mut set = |role: Role, n: u32| roots.nodes[role as usize] = Some(n);
    set(Role::Temperature, temperature);
    set(Role::Vegetation, vegetation);
    set(Role::Continents, continents);
    set(Role::Erosion, erosion);
    set(Role::Depth, depth);
    set(Role::Ridges, ridges);
    set(Role::FinalDensity, final_density);
    set(Role::PreliminarySurface, preliminary);
    set(Role::AquiferBarrier, barrier);
    set(Role::AquiferFloodedness, floodedness);
    set(Role::AquiferSpread, spread);
    set(Role::AquiferLava, lava);
    set(Role::AquiferExclusion, exclusion);
    set(Role::AquiferSurfaceLevel, surface_level);
    set(Role::VeinToggle, vein_toggle);
    set(Role::VeinRidged, vein_ridged);
    set(Role::VeinGap, vein_gap);

    let mut flags = vec![0u32; state::COUNT as usize];
    flags[state::AIR as usize] = state_flags::AIR;
    flags[state::STONE as usize] = state_flags::OCEAN_FLOOR_OPAQUE | state_flags::DEFAULT_BLOCK;
    flags[state::WATER as usize] = state_flags::FLUID | state_flags::WATER;
    flags[state::LAVA as usize] = state_flags::FLUID | state_flags::LAVA;
    for s in 4..state::COUNT as usize {
        flags[s] = state_flags::OCEAN_FLOOR_OPAQUE;
    }
    let states = States {
        global_ids: (0..state::COUNT).map(|i| if i == 0 { 0 } else { 1000 + i }).collect(),
        flags,
        special: [
            state::AIR,
            state::WATER,
            state::LAVA,
            state::TERRACOTTA,
            8,
            9,
            10,
            11,
            12,
            13,
            state::PACKED_ICE,
            state::SNOW_BLOCK,
        ],
    };

    let conditions = vec![
        Condition::StoneDepth {
            offset: 0,
            add_surface_depth: false,
            secondary_depth_range: 0,
            ceiling: false,
        },
        Condition::Water {
            offset: -1,
            surface_depth_multiplier: 0,
            add_stone_depth: false,
        },
        Condition::YAbove {
            anchor_y: 60,
            surface_depth_multiplier: 0,
            add_stone_depth: false,
        },
        Condition::Biome(vec![2]),
        Condition::StoneDepth {
            offset: 0,
            add_surface_depth: true,
            secondary_depth_range: 0,
            ceiling: false,
        },
        Condition::VerticalGradient {
            random_name: "minecraft:deepslate".to_string(),
            true_at_and_below: 0,
            false_at_and_above: 8,
        },
        Condition::AbovePreliminarySurface,
    ];
    let mut rules = vec![
        Rule::Block(state::GRASS),          // 0
        Rule::Block(state::DIRT),           // 1
        Rule::Block(state::SAND),           // 2
        Rule::Bandlands,                    // 3
        Rule::Condition(1, 0),              // 4: grass where no water is above
        Rule::Sequence(vec![4, 1]),         // 5: grass, else dirt
        Rule::Condition(0, 5),              // 6: on the floor
        Rule::Condition(3, 3),              // 7: badlands biome -> clay bands
        Rule::Condition(4, 1),              // 8: just below the floor -> dirt
        Rule::Block(state::COPPER_ORE + 5), // 9: deepslate stand-in
        Rule::Condition(5, 9),              // 10: vertical gradient
        Rule::Condition(2, 2),              // 11: sand above y 60 when nothing else applied
    ];
    let mut surface_rules = vec![7u32, 6, 8, 10, 11];
    if synth32 {
        rules.push(Rule::OreVein {
            ore: state::COPPER_ORE,
            raw_ore: state::COPPER_ORE + 1,
            filler: state::COPPER_ORE + 2,
            raw_ore_chance: 0.02,
            density: vein_ridged,
            richness: vein_toggle,
            filler_gap: vein_gap,
        });
        surface_rules.push((rules.len() - 1) as u32);
    }
    rules.push(Rule::Sequence(surface_rules));
    let above = (rules.len() - 1) as u32;
    rules.push(Rule::Condition(6, above));
    let root = (rules.len() - 1) as u32;

    let biome = |id: u32, temperature: f32, flags: u8| BiomeInfo {
        global_id: id,
        base_temperature: temperature,
        frozen_modifier: false,
        flags,
    };
    let span = |a: f32, b: f32| [quantize(a), quantize(b)];
    let entry = |t: [i64; 2], c: [i64; 2], biome: u32| ClimateEntry {
        params: [t, span(-1.0, 1.0), c, span(-1.0, 1.0), [0, 0], span(-1.0, 1.0), [0, 0]],
        biome,
    };
    let biomes = Biomes {
        biomes: vec![
            biome(40, 0.5, 0),
            biome(41, 0.8, 0),
            biome(42, 2.0, biome_flags::ERODED_BADLANDS),
            biome(43, 0.0, biome_flags::FROZEN_OCEAN),
        ],
        source: BiomeSource::MultiNoise {
            children_per_node: if synth32 { 19 } else { 6 },
            entries: vec![
                entry(span(-1.0, -0.3), span(-1.0, -0.2), 3),
                entry(span(-0.3, 0.3), span(-0.2, 1.0), 0),
                entry(span(0.3, 0.6), span(-0.2, 1.0), 1),
                entry(span(0.6, 1.0), span(-0.2, 1.0), 2),
                entry(span(-0.3, 1.0), span(-1.0, -0.2), 1),
            ],
        },
    };

    Ir {
        semantics,
        settings: Settings {
            seed,
            legacy_random: false,
            aquifers_enabled: true,
            ore_veins_enabled: !synth32,
            legacy_random_source: false,
            noise_min_y: -64,
            noise_height: 384,
            cell_width: 4,
            cell_height: 8,
            sea_level: 63,
            default_block: state::STONE,
            default_fluid: state::WATER,
            biome_zoom_seed: seed.wrapping_mul(0x5DEECE66D),
            level_min_y: -64,
            level_height: 384,
        },
        states,
        noises,
        nodes: b.nodes,
        splines,
        roots,
        fluid: FluidPicker {
            lava_below: -54,
            lava_level: -54,
            lava_state: state::LAVA,
            fluid_level: 63,
            fluid_state: state::WATER,
        },
        veins: (!synth32).then_some(Veins {
            copper_ore: state::COPPER_ORE,
            raw_copper: state::COPPER_ORE + 1,
            granite: state::COPPER_ORE + 2,
            iron_ore: state::COPPER_ORE + 3,
            raw_iron: state::COPPER_ORE + 4,
            tuff: state::COPPER_ORE + 5,
            copper_y: [0, 50],
            iron_y: [-60, -8],
        }),
        surface: Some(SurfaceDef {
            noises: [11, 12, 13, NONE, NONE, NONE, NONE, NONE, NONE],
            conditions,
            rules,
            root,
        }),
        biomes,
    }
}
