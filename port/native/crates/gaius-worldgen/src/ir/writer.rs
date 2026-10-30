//! Encoder for the generator IR; the reference for exporters and the source of
//! the IR used by tests. `Ir::decode(&ir.encode())` returns `ir`.

use super::*;

#[derive(Default)]
pub struct Writer {
    pub buf: Vec<u8>,
}

impl Writer {
    pub fn u8(&mut self, v: u8) {
        self.buf.push(v);
    }
    pub fn bool(&mut self, v: bool) {
        self.buf.push(v as u8);
    }
    pub fn u16(&mut self, v: u16) {
        self.buf.extend_from_slice(&v.to_le_bytes());
    }
    pub fn u32(&mut self, v: u32) {
        self.buf.extend_from_slice(&v.to_le_bytes());
    }
    pub fn i32(&mut self, v: i32) {
        self.buf.extend_from_slice(&v.to_le_bytes());
    }
    pub fn i64(&mut self, v: i64) {
        self.buf.extend_from_slice(&v.to_le_bytes());
    }
    pub fn f32(&mut self, v: f32) {
        self.buf.extend_from_slice(&v.to_le_bytes());
    }
    pub fn f64(&mut self, v: f64) {
        self.buf.extend_from_slice(&v.to_le_bytes());
    }
    pub fn string(&mut self, s: &str) {
        self.u16(s.len() as u16);
        self.buf.extend_from_slice(s.as_bytes());
    }
    pub fn len(&self) -> usize {
        self.buf.len()
    }
    pub fn is_empty(&self) -> bool {
        self.buf.is_empty()
    }
}

fn section(out: &mut Writer, tag: u32, body: Writer) {
    out.u32(tag);
    out.u32(body.buf.len() as u32);
    out.buf.extend_from_slice(&body.buf);
}

impl Ir {
    pub fn encode(&self) -> Vec<u8> {
        let mut sections = Writer::default();
        let mut count = 0u32;
        let mut add = |tag: u32, body: Writer| {
            section(&mut sections, tag, body);
            count += 1;
        };
        add(TAG_SETTINGS, encode_settings(&self.settings));
        add(TAG_STATES, encode_states(&self.states));
        add(TAG_NOISES, encode_noises(&self.noises));
        add(TAG_DENSITY, encode_density(&self.nodes, &self.splines));
        add(TAG_ROOTS, encode_roots(&self.roots));
        add(TAG_FLUID, encode_fluid(&self.fluid));
        if let Some(v) = &self.veins {
            add(TAG_VEINS, encode_veins(v));
        }
        if let Some(s) = &self.surface {
            add(TAG_SURFACE, encode_surface(s));
        }
        add(TAG_BIOMES, encode_biomes(&self.biomes));
        let mut out = Writer::default();
        out.u32(MAGIC);
        out.u16(VERSION);
        out.u8(self.semantics.code);
        out.u8(0);
        out.u32(count);
        out.u32((16 + sections.buf.len()) as u32);
        out.buf.extend_from_slice(&sections.buf);
        out.buf
    }
}

fn encode_settings(s: &Settings) -> Writer {
    let mut w = Writer::default();
    w.i64(s.seed);
    w.bool(s.legacy_random);
    w.bool(s.aquifers_enabled);
    w.bool(s.ore_veins_enabled);
    w.bool(s.legacy_random_source);
    w.i32(s.noise_min_y);
    w.i32(s.noise_height);
    w.i32(s.cell_width);
    w.i32(s.cell_height);
    w.i32(s.sea_level);
    w.u32(s.default_block);
    w.u32(s.default_fluid);
    w.i64(s.biome_zoom_seed);
    w.i32(s.level_min_y);
    w.i32(s.level_height);
    w
}

fn encode_states(s: &States) -> Writer {
    let mut w = Writer::default();
    w.u32(s.global_ids.len() as u32);
    for (id, flags) in s.global_ids.iter().zip(&s.flags) {
        w.u32(*id);
        w.u32(*flags);
    }
    w.u32(SPECIAL_COUNT as u32);
    for &v in &s.special {
        w.u32(v);
    }
    w
}

fn encode_noises(noises: &[NoiseDef]) -> Writer {
    let mut w = Writer::default();
    w.u32(noises.len() as u32);
    for n in noises {
        w.u8(match n.params {
            NoiseParams::Parity { .. } => 0,
            NoiseParams::Recipe { .. } => 1,
        });
        w.u8(n.legacy_nether_offset.is_some() as u8);
        w.u16(0);
        w.i64(n.legacy_nether_offset.unwrap_or(0));
        w.string(&n.name);
        match &n.params {
            NoiseParams::Parity {
                first_octave,
                amplitudes,
            } => {
                w.i32(*first_octave);
                w.u32(amplitudes.len() as u32);
                amplitudes.iter().for_each(|&a| w.f64(a));
            }
            NoiseParams::Recipe {
                base_amplitude,
                base_octave,
                octave_count,
                normalize,
                amplitude_modifiers,
            } => {
                w.f64(*base_amplitude);
                w.i32(*base_octave);
                w.i32(*octave_count);
                w.u8(*normalize);
                w.u32(amplitude_modifiers.len() as u32);
                amplitude_modifiers.iter().for_each(|&a| w.f64(a));
            }
        }
    }
    w
}

fn encode_node(w: &mut Writer, node: &Node) {
    let (op, arg): (u8, u8) = match &node.op {
        Op::Const(_) => (0, 0),
        Op::Noise { shift, .. } => (1, shift.is_some() as u8),
        Op::ShiftA(_) => (2, 0),
        Op::ShiftB(_) => (3, 0),
        Op::Shift(_) => (4, 0),
        Op::OldBlendedNoise(_) => (5, 0),
        Op::EndIslands => (6, 0),
        Op::Gradient { .. } => (7, 0),
        Op::YClampedGradient { .. } => (8, 0),
        Op::DistanceToPoint { .. } => (9, 0),
        Op::BlendAlpha => (10, 0),
        Op::BlendOffset => (11, 0),
        Op::Beardifier => (12, 0),
        Op::Unary(t, _) => (13, *t as u8),
        Op::Binary(t, _, _) => (14, *t as u8),
        Op::MulOrAdd { add, .. } => (15, *add as u8),
        Op::Pow(..) => (16, 0),
        Op::Clamp(..) => (17, 0),
        Op::Lerp(..) => (18, 0),
        Op::RangeChoice { .. } => (19, 0),
        Op::IntervalSelect { .. } => (20, 0),
        Op::Round(t, _, _) => (21, *t as u8),
        Op::Slice(axis, _, _) => (22, axis.code()),
        Op::FindTopSurface { .. } => (23, 0),
        Op::Spline(_) => (24, 0),
        Op::Interpolated { .. } => (25, 0),
        Op::Cache(_) => (26, 0),
        Op::Marker(Marker::FlatCache, _) => (27, 0),
        Op::Marker(Marker::Cache2D, _) => (28, 0),
        Op::Marker(Marker::CacheOnce, _) => (29, 0),
        Op::Marker(Marker::CacheAllInCell, _) => (30, 0),
        Op::BlendDensity(_) => (31, 0),
        Op::ScaledNoise { .. } => (32, 0),
    };
    w.u8(op);
    w.u8(arg);
    w.u8(node.axes);
    w.u8(0);
    w.f64(node.min);
    w.f64(node.max);
    match &node.op {
        Op::Const(v) => w.f64(*v),
        Op::Noise {
            noise,
            xz_scale,
            y_scale,
            shift,
        } => {
            w.u32(*noise);
            w.f64(*xz_scale);
            w.f64(*y_scale);
            for c in shift.unwrap_or([NONE; 3]) {
                w.u32(c);
            }
        }
        Op::ShiftA(n) | Op::ShiftB(n) | Op::Shift(n) => w.u32(*n),
        Op::OldBlendedNoise(f) => f.iter().for_each(|&v| w.f64(v)),
        Op::EndIslands | Op::BlendAlpha | Op::BlendOffset | Op::Beardifier => {}
        Op::Gradient {
            axis,
            tiling,
            from,
            to,
            from_value,
            to_value,
        } => {
            w.u8(axis.code());
            w.u8(*tiling as u8);
            w.i32(*from);
            w.i32(*to);
            w.f64(*from_value);
            w.f64(*to_value);
        }
        Op::YClampedGradient {
            from_y,
            to_y,
            from_value,
            to_value,
        } => {
            w.i32(*from_y);
            w.i32(*to_y);
            w.f64(*from_value);
            w.f64(*to_value);
        }
        Op::DistanceToPoint { point, metric } => {
            point.iter().for_each(|&c| w.i32(c));
            w.u8(*metric as u8);
        }
        Op::Unary(_, a) | Op::Cache(a) | Op::Marker(_, a) | Op::BlendDensity(a) => w.u32(*a),
        Op::ScaledNoise { noise, scale } => {
            w.u32(*noise);
            w.u32(*scale);
        }
        Op::Binary(_, a, b) | Op::Pow(a, b) | Op::Round(_, a, b) => {
            w.u32(*a);
            w.u32(*b);
        }
        Op::MulOrAdd { input, argument, .. } => {
            w.u32(*input);
            w.f64(*argument);
        }
        Op::Clamp(a, min, max) => {
            w.u32(*a);
            w.f64(*min);
            w.f64(*max);
        }
        Op::Lerp(a, b, c) => {
            w.u32(*a);
            w.u32(*b);
            w.u32(*c);
        }
        Op::RangeChoice {
            input,
            min_inclusive,
            max_exclusive,
            in_range,
            out_of_range,
        } => {
            w.u32(*input);
            w.f64(*min_inclusive);
            w.f64(*max_exclusive);
            w.u32(*in_range);
            w.u32(*out_of_range);
        }
        Op::IntervalSelect {
            input,
            thresholds,
            functions,
        } => {
            w.u32(*input);
            w.u32(thresholds.len() as u32);
            thresholds.iter().for_each(|&t| w.f64(t));
            functions.iter().for_each(|&f| w.u32(f));
        }
        Op::Slice(_, coordinate, input) => {
            w.i32(*coordinate);
            w.u32(*input);
        }
        Op::FindTopSurface {
            density,
            upper_bound,
            lower_bound,
            cell_height,
        } => {
            w.u32(*density);
            w.u32(*upper_bound);
            w.i32(*lower_bound);
            w.i32(*cell_height);
        }
        Op::Spline(s) => w.u32(*s),
        Op::Interpolated { input, cell_xz, cell_y } => {
            w.u32(*input);
            w.i32(*cell_xz);
            w.i32(*cell_y);
        }
    }
}

fn encode_density(nodes: &[Node], splines: &[SplineDef]) -> Writer {
    let mut w = Writer::default();
    w.u32(nodes.len() as u32);
    for node in nodes {
        encode_node(&mut w, node);
    }
    w.u32(splines.len() as u32);
    for s in splines {
        match s {
            SplineDef::Constant(v) => {
                w.u8(0);
                w.f32(*v);
            }
            SplineDef::Multipoint {
                coordinate,
                locations,
                derivatives,
                values,
            } => {
                w.u8(1);
                w.u32(*coordinate);
                w.u32(locations.len() as u32);
                locations.iter().for_each(|&v| w.f32(v));
                derivatives.iter().for_each(|&v| w.f32(v));
                values.iter().for_each(|&v| w.u32(v));
            }
        }
    }
    w
}

fn encode_roots(roots: &Roots) -> Writer {
    let mut w = Writer::default();
    let present: Vec<(usize, u32)> = roots
        .nodes
        .iter()
        .enumerate()
        .filter_map(|(role, node)| node.map(|n| (role, n)))
        .collect();
    w.u32(present.len() as u32);
    for (role, node) in present {
        w.u16(role as u16);
        w.u16(0);
        w.u32(node);
    }
    w
}

fn encode_fluid(f: &FluidPicker) -> Writer {
    let mut w = Writer::default();
    w.i32(f.lava_below);
    w.i32(f.lava_level);
    w.u32(f.lava_state);
    w.i32(f.fluid_level);
    w.u32(f.fluid_state);
    w
}

fn encode_veins(v: &Veins) -> Writer {
    let mut w = Writer::default();
    for s in [v.copper_ore, v.raw_copper, v.granite, v.iron_ore, v.raw_iron, v.tuff] {
        w.u32(s);
    }
    for y in v.copper_y.iter().chain(v.iron_y.iter()) {
        w.i32(*y);
    }
    w
}

fn encode_surface(s: &SurfaceDef) -> Writer {
    let mut w = Writer::default();
    s.noises.iter().for_each(|&n| w.u32(n));
    w.u32(s.conditions.len() as u32);
    for c in &s.conditions {
        match c {
            Condition::Biome(list) => {
                w.u8(0);
                w.u32(list.len() as u32);
                list.iter().for_each(|&b| w.u32(b));
            }
            Condition::NoiseThreshold { noise, min, max, is_3d } => {
                w.u8(1);
                w.u32(*noise);
                w.f64(*min);
                w.f64(*max);
                w.bool(*is_3d);
            }
            Condition::VerticalGradient {
                random_name,
                true_at_and_below,
                false_at_and_above,
            } => {
                w.u8(2);
                w.string(random_name);
                w.i32(*true_at_and_below);
                w.i32(*false_at_and_above);
            }
            Condition::YAbove {
                anchor_y,
                surface_depth_multiplier,
                add_stone_depth,
            } => {
                w.u8(3);
                w.i32(*anchor_y);
                w.i32(*surface_depth_multiplier);
                w.bool(*add_stone_depth);
            }
            Condition::Water {
                offset,
                surface_depth_multiplier,
                add_stone_depth,
            } => {
                w.u8(4);
                w.i32(*offset);
                w.i32(*surface_depth_multiplier);
                w.bool(*add_stone_depth);
            }
            Condition::Temperature => w.u8(5),
            Condition::Steep => w.u8(6),
            Condition::Not(c) => {
                w.u8(7);
                w.u32(*c);
            }
            Condition::Hole => w.u8(8),
            Condition::AbovePreliminarySurface => w.u8(9),
            Condition::StoneDepth {
                offset,
                add_surface_depth,
                secondary_depth_range,
                ceiling,
            } => {
                w.u8(10);
                w.i32(*offset);
                w.bool(*add_surface_depth);
                w.i32(*secondary_depth_range);
                w.bool(*ceiling);
            }
        }
    }
    w.u32(s.rules.len() as u32);
    for r in &s.rules {
        match r {
            Rule::Block(state) => {
                w.u8(0);
                w.u32(*state);
            }
            Rule::Sequence(list) => {
                w.u8(1);
                w.u32(list.len() as u32);
                list.iter().for_each(|&r| w.u32(r));
            }
            Rule::Condition(c, r) => {
                w.u8(2);
                w.u32(*c);
                w.u32(*r);
            }
            Rule::Bandlands => w.u8(3),
            Rule::OreVein {
                ore,
                raw_ore,
                filler,
                raw_ore_chance,
                density,
                richness,
                filler_gap,
            } => {
                w.u8(4);
                w.u32(*ore);
                w.u32(*raw_ore);
                w.u32(*filler);
                w.f32(*raw_ore_chance);
                w.u32(*density);
                w.u32(*richness);
                w.u32(*filler_gap);
            }
        }
    }
    w.u32(s.root);
    w
}

fn encode_biomes(b: &Biomes) -> Writer {
    let mut w = Writer::default();
    w.u8(match b.source {
        BiomeSource::MultiNoise { .. } => 0,
        BiomeSource::Fixed(_) => 1,
        BiomeSource::TheEnd { .. } => 2,
    });
    w.u8(0);
    w.u8(0);
    w.u8(0);
    w.u32(b.biomes.len() as u32);
    for info in &b.biomes {
        w.u32(info.global_id);
        w.f32(info.base_temperature);
        w.u8(info.frozen_modifier as u8);
        w.u8(info.flags);
        w.u16(0);
    }
    match &b.source {
        BiomeSource::MultiNoise {
            children_per_node,
            entries,
        } => {
            w.u32(*children_per_node);
            w.u32(entries.len() as u32);
            for e in entries {
                for p in &e.params {
                    w.i64(p[0]);
                    w.i64(p[1]);
                }
                w.u32(e.biome);
            }
        }
        BiomeSource::Fixed(biome) => w.u32(*biome),
        BiomeSource::TheEnd {
            end,
            highlands,
            midlands,
            islands,
            barrens,
        } => {
            for v in [end, highlands, midlands, islands, barrens] {
                w.u32(*v);
            }
        }
    }
    w
}
