//! Compares the kernel with reference data dumped from the profile-selected vanilla classes by
//! `port/native/worldgen-perf-reference.mjs` (set `GAIUS_WORLDGEN_REFERENCE` to
//! its output directory). Without the variable the test does nothing.
#![allow(clippy::needless_range_loop)]

use gaius_worldgen::{Generator, TerrainRequest};
use std::collections::HashSet;
use std::path::Path;

const POST_MAGIC: i32 = 0x3150_5047;

fn ints(bytes: &[u8]) -> Vec<i32> {
    assert_eq!(bytes.len() % 4, 0, "truncated reference word");
    bytes
        .as_chunks::<4>()
        .0
        .iter()
        .map(|c| i32::from_le_bytes([c[0], c[1], c[2], c[3]]))
        .collect()
}

#[test]
fn kernel_matches_vanilla_reference() {
    let Ok(dir) = std::env::var("GAIUS_WORLDGEN_REFERENCE") else {
        eprintln!("GAIUS_WORLDGEN_REFERENCE is not set; skipping");
        return;
    };
    let dir = Path::new(&dir);
    let ir = std::fs::read(dir.join("overworld.gwir")).expect("overworld.gwir");
    let mut generator = Generator::load(&ir).unwrap_or_else(|e| panic!("IR does not load: {e}"));
    let mut failures = Vec::new();
    let mut references: Vec<_> = std::fs::read_dir(dir)
        .unwrap()
        .map(|entry| entry.unwrap().path())
        .filter(|path| path.extension().and_then(|extension| extension.to_str()) == Some("ref"))
        .collect();
    references.sort();
    assert!(!references.is_empty(), "no vanilla chunk references");
    if let Ok(side_text) = std::env::var("GAIUS_WORLDGEN_REFERENCE_SIDE") {
        let side: usize = side_text.parse().expect("GAIUS_WORLDGEN_REFERENCE_SIDE is an integer");
        assert!(side > 0, "GAIUS_WORLDGEN_REFERENCE_SIDE must be positive");
        assert_eq!(references.len(), side * side, "reference area is incomplete");
        let mut coordinates = HashSet::with_capacity(references.len());
        for path in &references {
            let stem = path.file_stem().and_then(|s| s.to_str()).expect("reference filename");
            let mut fields = stem
                .strip_prefix("chunk_")
                .expect("reference filename prefix")
                .split('_');
            let cx: i32 = fields
                .next()
                .expect("reference chunk x")
                .parse()
                .expect("reference chunk x");
            let cz: i32 = fields
                .next()
                .expect("reference chunk z")
                .parse()
                .expect("reference chunk z");
            assert!(
                fields.next().is_none(),
                "reference filename has extra coordinates: {stem}"
            );
            assert!(
                coordinates.insert((cx, cz)),
                "duplicate reference coordinate: {cx},{cz}"
            );
        }
        let min_x = coordinates.iter().map(|&(x, _)| x).min().expect("reference x");
        let min_z = coordinates.iter().map(|&(_, z)| z).min().expect("reference z");
        for z in min_z..min_z + side as i32 {
            for x in min_x..min_x + side as i32 {
                assert!(coordinates.contains(&(x, z)), "missing reference coordinate: {x},{z}");
            }
        }
    }
    for path in references {
        let data = ints(&std::fs::read(&path).unwrap());
        let height = generator.settings.level_height;
        let min_y = generator.settings.level_min_y;
        let volume = (256 * height) as usize;
        let biome_count = generator.section_count() * 64;
        let base_words = 2 + 3 * volume + biome_count + 512;
        assert!(data.len() >= base_words, "reference is truncated: {path:?}");
        let tail = &data[base_words..];
        let (post_ref, stored_biomes) = if tail.first().copied() == Some(POST_MAGIC) {
            assert!(tail.len() >= 2, "truncated post reference: {path:?}");
            let post_count = usize::try_from(tail[1]).expect("negative post count");
            let post_end = 2usize.checked_add(post_count).expect("post count overflow");
            assert!(
                tail.len() == post_end || tail.len() == post_end + generator.stored_biomes_len(),
                "reference post shape: {path:?}"
            );
            let posts = tail[2..post_end].iter().map(|&v| v as u32).collect::<Vec<_>>();
            let ring = (tail.len() > post_end).then(|| {
                generator
                    .ring_biomes(&tail[post_end..].iter().map(|&id| id as u32).collect::<Vec<_>>())
                    .unwrap()
            });
            (Some(posts), ring)
        } else {
            assert!(
                tail.is_empty() || tail.len() == generator.stored_biomes_len(),
                "reference shape: {path:?}"
            );
            let ring = (!tail.is_empty()).then(|| {
                generator
                    .ring_biomes(&tail.iter().map(|&id| id as u32).collect::<Vec<_>>())
                    .unwrap()
            });
            (None, ring)
        };
        let (cx, cz) = (data[0], data[1]);
        let density_ref = &data[2..2 + volume];
        let biomes_ref = &data[2 + volume..2 + volume + biome_count];
        let states_ref = &data[2 + volume + biome_count..2 + 2 * volume + biome_count];
        let surface_ref = &data[2 + 2 * volume + biome_count..2 + 3 * volume + biome_count];
        let heights_ref = &data[2 + 3 * volume + biome_count..base_words];
        // Router references carry no density block (zeros); sampler references have a volume.
        if let Some(density) = generator.debug_final_density(cx, cz) {
            assert_eq!(density.len(), density_ref.len(), "density coverage");
            let mut mismatches = 0;
            let mut worst = 0i64;
            for (i, (&d, &r)) in density.iter().zip(density_ref).enumerate() {
                let ulps = (d.to_bits() as i32 as i64 - r as i64).abs();
                if ulps != 0 {
                    if mismatches < 5 {
                        eprintln!(
                            "chunk {cx},{cz} density[{i}]: kernel {d} vanilla {}",
                            f32::from_bits(r as u32)
                        );
                    }
                    mismatches += 1;
                    worst = worst.max(ulps);
                }
            }
            eprintln!("chunk {cx},{cz}: density mismatches {mismatches} (worst {worst} ulps)");
            if mismatches > 0 {
                failures.push(format!("{cx},{cz} density"));
            }
        }

        let biomes: Vec<i32> = generator.run_biomes(cx, cz).iter().map(|&b| b as i32).collect();
        assert_eq!(biomes.len(), biomes_ref.len(), "biome coverage");
        let mut biome_mismatches = 0;
        for (i, (a, b)) in biomes.iter().zip(biomes_ref).enumerate() {
            if a != b {
                let (section, local) = (i as i32 / 64, i as i32 % 64);
                let (qy, qz, qx) = (local / 16, (local / 4) % 4, local % 4);
                let quart = [cx * 4 + qx, (min_y >> 2) + section * 4 + qy, cz * 4 + qz];
                if biome_mismatches < 8 {
                    eprintln!("chunk {cx},{cz} quart {quart:?}: kernel biome {a} vanilla {b}");
                }
                biome_mismatches += 1;
            }
        }
        eprintln!("chunk {cx},{cz}: biome mismatches {biome_mismatches}");
        if biome_mismatches > 0 {
            failures.push(format!("{cx},{cz} biomes"));
        }

        for (label, surface, reference) in [("fill", false, states_ref), ("surface", true, surface_ref)] {
            let result = generator.run_terrain(&TerrainRequest {
                chunk_x: cx,
                chunk_z: cz,
                surface,
                ring_biomes: stored_biomes.clone(),
                ..TerrainRequest::default()
            });
            let chunk = &result.chunk;
            let mut state_mismatches = 0;
            for z in 0..16 {
                for x in 0..16 {
                    for y in 0..height {
                        let local = chunk.get(cx * 16 + x, y + min_y, cz * 16 + z);
                        let global = generator.states.global_ids[local as usize] as i32;
                        let expected = reference[(y + (x + z * 16) * height) as usize];
                        if global != expected {
                            if state_mismatches < 8 {
                                eprintln!(
                                    "chunk {cx},{cz} {label} block {x} {} {z}: kernel {global} vanilla {expected}",
                                    y + min_y
                                );
                            }
                            state_mismatches += 1;
                        }
                    }
                }
            }
            eprintln!("chunk {cx},{cz}: {label} block mismatches {state_mismatches}");
            if state_mismatches > 0 {
                failures.push(format!("{cx},{cz} {label} blocks"));
            }
            if surface {
                let heights: Vec<i32> = chunk
                    .world_surface
                    .iter()
                    .chain(chunk.ocean_floor.iter())
                    .copied()
                    .collect();
                assert_eq!(heights.len(), heights_ref.len(), "heightmap coverage");
                let height_mismatches = heights.iter().zip(heights_ref).filter(|(a, b)| a != b).count();
                eprintln!("chunk {cx},{cz}: heightmap mismatches {height_mismatches}");
                if height_mismatches > 0 {
                    failures.push(format!("{cx},{cz} heightmaps"));
                }
                if let Some(expected) = &post_ref {
                    let actual: Vec<u32> = chunk
                        .post_processing
                        .iter()
                        .map(|&(section, packed)| ((section as u32) << 16) | packed as u32)
                        .collect();
                    if actual != *expected {
                        eprintln!(
                            "chunk {cx},{cz}: post-processing order mismatch (kernel {} entries, vanilla {} entries)",
                            actual.len(),
                            expected.len()
                        );
                        failures.push(format!("{cx},{cz} post-processing"));
                    }
                }
            }
        }
    }
    assert!(failures.is_empty(), "vanilla mismatches: {failures:?}");
}

#[test]
#[should_panic(expected = "truncated reference word")]
fn truncated_reference_word_is_rejected() {
    ints(&[0, 0, 0]);
}
