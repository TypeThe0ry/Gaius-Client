//! Drives the worldgen jobs through the full kernel framing natively.

use gaius_kernel_abi::{
    release_descriptor, run_kernel, JobHeader, ResultHeader, RunDescriptor, Status, RESULT_HEADER_LEN,
};
use gaius_noise::Semantics;
use gaius_worldgen::beard::Beard;
use gaius_worldgen::ir::example::overworld;
use gaius_worldgen::Generator;
use gaius_worldgen_wasm::job::{self, encode_surface, encode_surface_with, encode_terrain, write_ref, write_ring};

fn run(
    kind: u16,
    payload: &[u8],
    handler: fn(&[u8]) -> Result<Vec<u8>, gaius_kernel_abi::KernelError>,
) -> (u32, Vec<u8>) {
    let framed = JobHeader::frame(kind, 9, payload);
    let desc = run_kernel(&framed, kind, handler);
    assert!(!desc.is_null());
    // SAFETY: the descriptor and its data were just produced by run_kernel.
    unsafe {
        let head = std::slice::from_raw_parts(desc, RunDescriptor::LEN);
        let status = u32::from_le_bytes(head[0..4].try_into().unwrap());
        let len = u32::from_le_bytes(head[8..12].try_into().unwrap()) as usize;
        let data = std::slice::from_raw_parts(desc.add(RunDescriptor::LEN), len).to_vec();
        release_descriptor(desc);
        (status, data)
    }
}

fn payload_of(data: &[u8]) -> &[u8] {
    let header = ResultHeader::parse(data).expect("result header");
    assert_eq!(header.job_id, 9);
    &data[RESULT_HEADER_LEN..]
}

fn i32_at(b: &[u8], at: usize) -> i32 {
    i32::from_le_bytes(b[at..at + 4].try_into().unwrap())
}

fn u16_at(b: &[u8], at: usize) -> usize {
    u16::from_le_bytes(b[at..at + 2].try_into().unwrap()) as usize
}

/// Reads the side-effect portion of a chunk result without depending on the browser JS decoder.
/// The returned post entries stay in their encoded order.
fn chunk_metadata(payload: &[u8]) -> (Vec<i32>, Vec<i32>, Vec<u32>) {
    let sections = i32_at(payload, 12) as usize;
    let mut p = 24usize;
    for _ in 0..sections {
        let palette = u16_at(payload, p);
        let bits = payload[p + 2];
        p += 4 + palette * 4;
        p += match bits {
            0 => 0,
            8 => 4096,
            16 => 8192,
            other => panic!("bad test result section bits {other}"),
        };
        p = (p + 3) & !3;
    }
    let world_surface = (0..256).map(|i| i32_at(payload, p + i * 4)).collect();
    p += 1024;
    let ocean_floor = (0..256).map(|i| i32_at(payload, p + i * 4)).collect();
    p += 1024;
    let count = i32_at(payload, p) as usize;
    p += 4;
    let post = (0..count).map(|i| i32_at(payload, p + i * 4) as u32).collect();
    (world_surface, ocean_floor, post)
}

#[test]
fn history_independent_biome_sources_remain_available() {
    for &semantics in Semantics::all() {
        for source in [
            gaius_worldgen::ir::BiomeSource::Fixed(0),
            gaius_worldgen::ir::BiomeSource::TheEnd {
                end: 0,
                highlands: 0,
                midlands: 0,
                islands: 0,
                barrens: 0,
            },
        ] {
            job::clear_cache();
            let mut ir = overworld(semantics, 555);
            ir.biomes.source = source;
            let bytes = ir.encode();
            let mut generator = Generator::load(&bytes).unwrap();
            assert!(!generator.biome_context_requires_vanilla());
            let expected = generator.run_biomes(3, -10);
            let mut payload = Vec::new();
            write_ref(&mut payload, 71, &bytes, true);
            payload.extend_from_slice(&3i32.to_le_bytes());
            payload.extend_from_slice(&(-10i32).to_le_bytes());
            let (status, data) = run(job::KIND_BIOMES, &payload, job::biomes);
            assert_eq!(status, 0, "{}", String::from_utf8_lossy(&data));
            let result = payload_of(&data);
            assert_eq!(result.len(), 8 + expected.len() * 4);
            for (index, biome) in expected.iter().enumerate() {
                assert_eq!(i32_at(result, 8 + index * 4) as u32, *biome);
            }
        }
    }
}

#[test]
fn multi_noise_declines_even_without_a_competing_biome() {
    for &semantics in Semantics::all() {
        job::clear_cache();
        let mut ir = overworld(semantics, 555);
        let gaius_worldgen::ir::BiomeSource::MultiNoise { entries, .. } = &mut ir.biomes.source else {
            panic!("multi-noise fixture");
        };
        // No climate tie is possible, but the production contract declines the entire source.
        entries.truncate(1);
        let bytes = ir.encode();
        let generator = Generator::load(&bytes).unwrap();
        assert!(generator.biome_context_requires_vanilla());
        let mut payload = Vec::new();
        write_ref(&mut payload, 81, &bytes, true);
        payload.extend_from_slice(&3i32.to_le_bytes());
        payload.extend_from_slice(&(-10i32).to_le_bytes());
        let (status, data) = run(job::KIND_BIOMES, &payload, job::biomes);
        assert_eq!(status, Status::BadPayload as u32);
        assert_eq!(
            String::from_utf8_lossy(&data),
            "worldgen-fallback:multi-noise biomes require vanilla thread-local search history"
        );
    }
}

#[test]
fn climate_tie_declines_only_the_job_without_stored_biomes() {
    for (key, &semantics) in (101u32..).zip(Semantics::all()) {
        job::clear_cache();
        let mut ir = overworld(semantics, 12345);
        let gaius_worldgen::ir::BiomeSource::MultiNoise { entries, .. } = &mut ir.biomes.source else {
            panic!("multi-noise fixture");
        };
        entries.truncate(2);
        for entry in entries {
            entry.params = [[-1_000_000, 1_000_000]; 7];
        }
        let bytes = ir.encode();
        let generator = Generator::load(&bytes).unwrap();
        let mut payload = Vec::new();
        write_ref(&mut payload, key, &bytes, true);
        payload.extend_from_slice(&0i32.to_le_bytes());
        payload.extend_from_slice(&0i32.to_le_bytes());
        let (status, data) = run(job::KIND_BIOMES, &payload, job::biomes);
        assert_eq!(status, Status::BadPayload as u32);
        assert!(String::from_utf8_lossy(&data).starts_with(job::FALLBACK_PREFIX));
        let stored = vec![ir.biomes.biomes[0].global_id; generator.stored_biomes_len()];
        let mut terrain = encode_terrain(
            key,
            &bytes,
            false,
            0,
            0,
            job::FLAG_SURFACE | job::FLAG_RING_BIOMES,
            &Beard::default(),
        );
        write_ring(&mut terrain, &stored);
        let (status, data) = run(job::KIND_TERRAIN, &terrain, job::terrain);
        assert_eq!(status, 0, "{}", String::from_utf8_lossy(&data));
        assert!(ResultHeader::parse(&data).is_some());
    }
}

#[test]
fn jobs_round_trip_through_the_framing() {
    for (key, &semantics) in (1u32..).zip(Semantics::all()) {
        job::clear_cache();
        let ir = overworld(semantics, 555).encode();
        let mut generator = Generator::load(&ir).expect("example generator loads");

        // A job without the IR fails until the generator is loaded.
        let lazy = encode_terrain(key, &ir, false, 0, 0, job::FLAG_SURFACE, &Beard::default());
        let (status, data) = run(job::KIND_TERRAIN, &lazy, job::terrain);
        assert_eq!(status, Status::BadPayload as u32);
        assert!(String::from_utf8_lossy(&data).starts_with("generator-missing:"));

        let mut load = Vec::new();
        write_ref(&mut load, key, &ir, true);
        load.extend_from_slice(&0u32.to_le_bytes());
        let (status, data) = run(job::KIND_LOAD_GENERATOR, &load, job::load_generator);
        assert_eq!(status, 0, "{}", String::from_utf8_lossy(&data));
        let info = payload_of(&data);
        assert_eq!(i32_at(info, 20), 24, "section count");

        let (status, data) = run(job::KIND_TERRAIN, &lazy, job::terrain);
        assert_eq!(status, 0, "{}", String::from_utf8_lossy(&data));
        let chunk = payload_of(&data).to_vec();
        assert_eq!(i32_at(&chunk, 8), -64);
        assert_eq!(i32_at(&chunk, 12), 24);

        // The surface job over a plain fill gives the same chunk as the inline surface.
        let plain = encode_terrain(key, &ir, false, 0, 0, 0, &Beard::default());
        let (status, data) = run(job::KIND_TERRAIN, &plain, job::terrain);
        assert_eq!(status, 0);
        let filled = payload_of(&data).to_vec();
        let fill_meta = chunk_metadata(&filled);
        let expected_fill = generator.run_terrain(&gaius_worldgen::TerrainRequest {
            chunk_x: 0,
            chunk_z: 0,
            ..gaius_worldgen::TerrainRequest::default()
        });
        assert_eq!(
            fill_meta.0,
            expected_fill.chunk.world_surface.to_vec(),
            "{semantics:?}: fill world surface"
        );
        assert_eq!(
            fill_meta.1,
            expected_fill.chunk.ocean_floor.to_vec(),
            "{semantics:?}: fill ocean floor"
        );
        let expected_fill_post: Vec<u32> = expected_fill
            .chunk
            .post_processing
            .iter()
            .map(|&(section, packed)| ((section as u32) << 16) | packed as u32)
            .collect();
        assert_eq!(fill_meta.2, expected_fill_post, "{semantics:?}: fill post order");
        let surface = encode_surface(key, &ir, false, 0, 0, &Beard::default(), &filled);
        let (status, data) = run(job::KIND_SURFACE, &surface, job::surface);
        assert_eq!(status, 0, "{}", String::from_utf8_lossy(&data));
        let surfaced = payload_of(&data);
        let surface_meta = chunk_metadata(surfaced);
        let expected_surface = generator.run_terrain(&gaius_worldgen::TerrainRequest {
            chunk_x: 0,
            chunk_z: 0,
            surface: true,
            ..gaius_worldgen::TerrainRequest::default()
        });
        assert_eq!(
            surface_meta.0,
            expected_surface.chunk.world_surface.to_vec(),
            "{semantics:?}: surface world surface"
        );
        assert_eq!(
            surface_meta.1,
            expected_surface.chunk.ocean_floor.to_vec(),
            "{semantics:?}: surface ocean floor"
        );
        let expected_surface_post: Vec<u32> = expected_surface
            .chunk
            .post_processing
            .iter()
            .map(|&(section, packed)| ((section as u32) << 16) | packed as u32)
            .collect();
        assert_eq!(
            surface_meta.2, expected_surface_post,
            "{semantics:?}: surface post order"
        );
        assert_eq!(
            &surfaced[24..],
            &chunk[24..],
            "{semantics:?}: surface job matches the inline surface"
        );

        let mut biomes = Vec::new();
        write_ref(&mut biomes, key, &ir, false);
        biomes.extend_from_slice(&0i32.to_le_bytes());
        biomes.extend_from_slice(&0i32.to_le_bytes());
        let (status, data) = run(job::KIND_BIOMES, &biomes, job::biomes);
        assert_eq!(status, Status::BadPayload as u32);
        assert!(String::from_utf8_lossy(&data).starts_with(job::FALLBACK_PREFIX));
    }
}

/// The ring a server would send for chunk (cx, cz) when every neighbour stores the biomes this
/// generator computes for it.
fn computed_ring(generator: &mut Generator, cx: i32, cz: i32) -> Vec<u32> {
    let size_y = generator.section_count() as i32 * 4;
    let mut neighbours = std::collections::HashMap::new();
    let mut ring = Vec::new();
    for gx in 0..6 {
        for gz in 0..6 {
            if (1..=4).contains(&gx) && (1..=4).contains(&gz) {
                continue;
            }
            let (qx, qz) = (cx * 4 - 1 + gx, cz * 4 - 1 + gz);
            let ids = neighbours
                .entry((qx >> 2, qz >> 2))
                .or_insert_with(|| generator.run_biomes(qx >> 2, qz >> 2));
            for y in 0..size_y {
                let at = (y >> 2) * 64 + ((y & 3) * 4 + (qz & 3)) * 4 + (qx & 3);
                ring.push(ids[at as usize]);
            }
        }
    }
    ring
}

#[test]
fn ring_biomes_reach_terrain_and_surface_jobs() {
    for (key, &semantics) in (11u32..).zip(Semantics::all()) {
        job::clear_cache();
        let ir = overworld(semantics, 777).encode();
        let mut generator = Generator::load(&ir).expect("loads");
        let (cx, cz) = (-2, 3);
        let ring = computed_ring(&mut generator, cx, cz);
        assert_eq!(ring.len(), generator.ring_len());

        let plain = encode_terrain(key, &ir, true, cx, cz, job::FLAG_SURFACE, &Beard::default());
        let (status, data) = run(job::KIND_TERRAIN, &plain, job::terrain);
        assert_eq!(status, 0, "{}", String::from_utf8_lossy(&data));
        let expected = payload_of(&data).to_vec();

        // Neighbours storing what the kernel computes change nothing.
        let mut seeded = encode_terrain(
            key,
            &ir,
            false,
            cx,
            cz,
            job::FLAG_SURFACE | job::FLAG_RING_BIOMES,
            &Beard::default(),
        );
        write_ring(&mut seeded, &ring);
        let (status, data) = run(job::KIND_TERRAIN, &seeded, job::terrain);
        assert_eq!(status, 0, "{}", String::from_utf8_lossy(&data));
        assert_eq!(
            payload_of(&data),
            &expected[..],
            "{semantics:?}: ring of computed biomes"
        );

        let fill = encode_terrain(key, &ir, false, cx, cz, 0, &Beard::default());
        let (status, data) = run(job::KIND_TERRAIN, &fill, job::terrain);
        assert_eq!(status, 0);
        let filled = payload_of(&data).to_vec();
        let surface = encode_surface_with(key, &ir, false, (cx, cz), &Beard::default(), Some(&ring), &filled);
        let (status, data) = run(job::KIND_SURFACE, &surface, job::surface);
        assert_eq!(status, 0, "{}", String::from_utf8_lossy(&data));
        assert_eq!(
            &payload_of(&data)[24..],
            &expected[24..],
            "{semantics:?}: surface job with a ring"
        );

        // The surface rules read the ring: neighbours that all store the example's badlands
        // (global id 42, clay bands) change the border columns.
        assert!(
            ring.iter().any(|&id| id != 42),
            "{semantics:?}: pick a chunk outside the badlands"
        );
        let badlands = vec![42u32; ring.len()];
        let surface = encode_surface_with(key, &ir, false, (cx, cz), &Beard::default(), Some(&badlands), &filled);
        let (status, data) = run(job::KIND_SURFACE, &surface, job::surface);
        assert_eq!(status, 0, "{}", String::from_utf8_lossy(&data));
        assert!(
            payload_of(&data)[24..] != expected[24..],
            "{semantics:?}: the surface rules ignored the ring"
        );

        let stored = vec![42u32; generator.stored_biomes_len()];
        let mut combined = encode_terrain(
            key,
            &ir,
            false,
            cx,
            cz,
            job::FLAG_SURFACE | job::FLAG_BIOMES | job::FLAG_RING_BIOMES,
            &Beard::default(),
        );
        write_ring(&mut combined, &stored);
        let (status, data) = run(job::KIND_TERRAIN, &combined, job::terrain);
        assert_eq!(status, 0, "{}", String::from_utf8_lossy(&data));
        let returned = payload_of(&data);
        let biome_start = returned.len() - generator.section_count() * 64 * 4;
        assert!(returned[biome_start..]
            .as_chunks::<4>()
            .0
            .iter()
            .all(|bytes| i32_at(bytes, 0) == 42));
        let surface = encode_surface_with(key, &ir, false, (cx, cz), &Beard::default(), Some(&stored), &filled);
        let (status, data) = run(job::KIND_SURFACE, &surface, job::surface);
        assert_eq!(status, 0, "{}", String::from_utf8_lossy(&data));
        assert_eq!(&payload_of(&data)[24..], &returned[24..biome_start]);

        // A neighbour biome outside the generator's table only refuses this chunk.
        let mut foreign = ring.clone();
        foreign[7] = 0x7fff_0000;
        let surface = encode_surface_with(key, &ir, false, (cx, cz), &Beard::default(), Some(&foreign), &filled);
        let (status, data) = run(job::KIND_SURFACE, &surface, job::surface);
        assert_eq!(status, Status::BadPayload as u32);
        assert!(String::from_utf8_lossy(&data).starts_with(job::FALLBACK_PREFIX));
        let mut unknown = encode_terrain(
            key,
            &ir,
            false,
            cx,
            cz,
            job::FLAG_SURFACE | job::FLAG_RING_BIOMES,
            &Beard::default(),
        );
        write_ring(&mut unknown, &foreign);
        let (status, data) = run(job::KIND_TERRAIN, &unknown, job::terrain);
        assert_eq!(status, Status::BadPayload as u32);
        assert!(String::from_utf8_lossy(&data).starts_with(job::FALLBACK_PREFIX));

        // A ring of the wrong size is a broken job, not a fallback.
        let mut short = encode_terrain(
            key,
            &ir,
            false,
            cx,
            cz,
            job::FLAG_SURFACE | job::FLAG_RING_BIOMES,
            &Beard::default(),
        );
        write_ring(&mut short, &ring[1..]);
        let (status, data) = run(job::KIND_TERRAIN, &short, job::terrain);
        assert_eq!(status, Status::BadPayload as u32);
        assert!(!String::from_utf8_lossy(&data).starts_with(job::FALLBACK_PREFIX));
    }
}
