//! Quick end-to-end sanity checks of the generator on the example IR for both
//! density systems. They do not prove vanilla parity (that needs fixtures dumped
//! from the client jars); they catch broken plumbing.

use gaius_noise::Semantics;
use gaius_worldgen::chunk::HeightmapKind;
use gaius_worldgen::ir::example::{overworld, state};
use gaius_worldgen::ir::Ir;
use gaius_worldgen::{Generator, TerrainRequest};

#[test]
fn ir_round_trips() {
    for &semantics in Semantics::all() {
        let ir = overworld(semantics, 7);
        let bytes = ir.encode();
        assert_eq!(Ir::decode(&bytes).expect("decodes"), ir, "{semantics:?}");
        let mut bad = bytes.clone();
        bad.truncate(bytes.len() - 3);
        assert!(Ir::decode(&bad).is_err());
    }
}

#[test]
fn terrain_has_ground_air_and_sea() {
    for &semantics in Semantics::all() {
        let mut generator = Generator::load(&overworld(semantics, 1234).encode()).expect("loads");
        for (cx, cz) in [(0, 0), (-3, 5), (40, -17)] {
            let request = TerrainRequest {
                chunk_x: cx,
                chunk_z: cz,
                surface: true,
                biomes: true,
                ..TerrainRequest::default()
            };
            let result = generator.run_terrain(&request);
            let chunk = &result.chunk;
            let bottom = chunk.get(cx * 16, -60, cz * 16);
            assert_ne!(
                bottom,
                state::AIR as u16,
                "{semantics:?} {cx} {cz}: the deep layers are solid"
            );
            assert_eq!(
                chunk.get(cx * 16, 318, cz * 16),
                state::AIR as u16,
                "{semantics:?}: the sky is air"
            );
            for x in 0..16 {
                for z in 0..16 {
                    let top = chunk.height_at(HeightmapKind::WorldSurface, x, z);
                    assert!((-64..320).contains(&top), "{semantics:?}: height {top}");
                    let floor = chunk.height_at(HeightmapKind::OceanFloor, x, z);
                    assert!(floor <= top);
                    let at_top = chunk.get(cx * 16 + x, top, cz * 16 + z);
                    assert_ne!(
                        at_top,
                        state::AIR as u16,
                        "{semantics:?}: the heightmap points at a block"
                    );
                }
            }
            let biomes = result.biomes.expect("biomes requested");
            assert_eq!(biomes.len(), generator.section_count() * 64);
            assert!(biomes.iter().all(|b| (40..=43).contains(b)));
            // The same request again is bit identical (no state leaks between jobs).
            let again = generator.run_terrain(&request);
            assert_eq!(again.chunk.blocks, result.chunk.blocks, "{semantics:?}: deterministic");
            assert_eq!(again.chunk.world_surface, result.chunk.world_surface);
            assert_eq!(generator.run_biomes(cx, cz), biomes, "{semantics:?}: biome job matches");
        }
    }
}

#[test]
fn surface_job_matches_inline_surface() {
    for &semantics in Semantics::all() {
        let mut generator = Generator::load(&overworld(semantics, 99).encode()).expect("loads");
        let mut request = TerrainRequest {
            chunk_x: 2,
            chunk_z: -1,
            ..TerrainRequest::default()
        };
        let mut filled = generator.run_terrain(&request).chunk;
        request.surface = true;
        let inline = generator.run_terrain(&request).chunk;
        generator.run_surface(&mut filled, Default::default());
        let differing = filled.blocks.iter().zip(&inline.blocks).filter(|(a, b)| a != b).count();
        assert_eq!(differing, 0, "{semantics:?}: {differing} blocks differ");
        let surfaced = inline
            .blocks
            .iter()
            .filter(|&&b| b == state::GRASS as u16 || b == state::DIRT as u16)
            .count();
        assert!(surfaced > 0, "{semantics:?}: the surface rules placed blocks");
    }
}

#[test]
fn extreme_chunk_coordinates_remain_finite() {
    // 30,000,000 blocks is close to the supported world border. Exercise both
    // signs and the build-height limits for every profile semantics.
    for &semantics in Semantics::all() {
        let mut generator = Generator::load(&overworld(semantics, 0x5eed).encode()).expect("loads");
        for &(cx, cz) in &[(1_875_000, -1_875_000), (-1_875_000, 1_875_000)] {
            let biomes = generator.run_biomes(cx, cz);
            assert_eq!(biomes.len(), generator.section_count() * 64, "{semantics:?}");
            assert!(
                biomes.iter().all(|&id| (40..=43).contains(&id)),
                "{semantics:?} {cx} {cz}"
            );
            if let Some(density) = generator.debug_final_density(cx, cz) {
                assert!(density.iter().all(|v| v.is_finite()), "{semantics:?} {cx} {cz}");
            }
        }
    }
}
