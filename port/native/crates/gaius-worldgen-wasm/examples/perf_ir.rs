use gaius_kernel_abi::{release_descriptor, run_kernel, JobHeader, RunDescriptor};
use gaius_worldgen::beard::Beard;
use gaius_worldgen::{Generator, TerrainRequest};
use gaius_worldgen_wasm::job;
use std::hint::black_box;
use std::path::Path;
use std::time::Instant;

type Handler = fn(&[u8]) -> Result<Vec<u8>, gaius_kernel_abi::KernelError>;
type Jobs = Vec<(&'static str, u16, Handler, Vec<Vec<u8>>)>;

fn run_frame(frame: &[u8], kind: u16, handler: Handler) -> Vec<u8> {
    let descriptor = run_kernel(frame, kind, handler);
    assert!(!descriptor.is_null());
    unsafe {
        let header = std::slice::from_raw_parts(descriptor, RunDescriptor::LEN);
        assert_eq!(u32::from_le_bytes(header[..4].try_into().unwrap()), 0);
        let length = u32::from_le_bytes(header[8..12].try_into().unwrap()) as usize;
        let result = std::slice::from_raw_parts(descriptor.add(RunDescriptor::LEN), length).to_vec();
        release_descriptor(descriptor);
        result
    }
}

fn main() {
    let arguments: Vec<_> = std::env::args().skip(1).collect();
    assert!(
        arguments.len() >= 2,
        "usage: perf_ir <reference-dir> <output-dir> [rounds] [repeats]"
    );
    let reference = Path::new(&arguments[0]);
    let output = Path::new(&arguments[1]);
    let rounds: usize = arguments.get(2).map_or(5, |value| value.parse().unwrap());
    let repeats: usize = arguments.get(3).map_or(2, |value| value.parse().unwrap());
    assert!(rounds > 0 && repeats > 0);
    std::fs::create_dir_all(output).unwrap();
    let ir = std::fs::read(reference.join("overworld.gwir")).unwrap();
    let mut references: Vec<_> = std::fs::read_dir(reference)
        .unwrap()
        .map(|entry| entry.unwrap().path())
        .filter(|path| path.extension().is_some_and(|extension| extension == "ref"))
        .collect();
    references.sort();
    let coordinates: Vec<_> = references
        .iter()
        .map(|path| {
            let bytes = std::fs::read(path).unwrap();
            (
                i32::from_le_bytes(bytes[..4].try_into().unwrap()),
                i32::from_le_bytes(bytes[4..8].try_into().unwrap()),
            )
        })
        .collect();
    assert!(!coordinates.is_empty());
    let mut generator = Generator::load(&ir).unwrap();
    let requests: Vec<_> = coordinates
        .iter()
        .map(|&(chunk_x, chunk_z)| TerrainRequest {
            chunk_x,
            chunk_z,
            ..Default::default()
        })
        .collect();
    let filled: Vec<_> = requests
        .iter()
        .map(|request| generator.run_terrain(request).chunk)
        .collect();
    let mut load = Vec::new();
    job::write_ref(&mut load, 1, &ir, true);
    let load_frame = JobHeader::frame(job::KIND_LOAD_GENERATOR, 1, &load);
    run_frame(&load_frame, job::KIND_LOAD_GENERATOR, job::load_generator);
    std::fs::write(output.join("load.job"), &load_frame).unwrap();
    let mut jobs: Jobs = Vec::new();
    for (stage, kind, handler) in [
        ("biomes", job::KIND_BIOMES, job::biomes as Handler),
        ("terrain", job::KIND_TERRAIN, job::terrain as Handler),
        ("surface", job::KIND_SURFACE, job::surface as Handler),
        ("combined", job::KIND_TERRAIN, job::terrain as Handler),
    ] {
        let mut frames = Vec::new();
        for (index, &(chunk_x, chunk_z)) in coordinates.iter().enumerate() {
            let payload = match stage {
                "biomes" => {
                    let mut bytes = Vec::new();
                    job::write_ref(&mut bytes, 1, &ir, false);
                    bytes.extend_from_slice(&chunk_x.to_le_bytes());
                    bytes.extend_from_slice(&chunk_z.to_le_bytes());
                    bytes
                }
                "surface" => {
                    let terrain = job::encode_terrain(1, &ir, false, chunk_x, chunk_z, 0, &Beard::default());
                    let chunk = job::terrain(&terrain).unwrap();
                    job::encode_surface(1, &ir, false, chunk_x, chunk_z, &Beard::default(), &chunk)
                }
                _ => job::encode_terrain(
                    1,
                    &ir,
                    false,
                    chunk_x,
                    chunk_z,
                    if stage == "combined" {
                        job::FLAG_SURFACE | job::FLAG_BIOMES
                    } else {
                        0
                    },
                    &Beard::default(),
                ),
            };
            let frame = JobHeader::frame(kind, 1, &payload);
            let result = run_frame(&frame, kind, handler);
            std::fs::write(output.join(format!("{stage}-{index}.job")), &frame).unwrap();
            std::fs::write(output.join(format!("{stage}-{index}.expected")), result).unwrap();
            frames.push(frame);
        }
        jobs.push((stage, kind, handler, frames));
    }
    println!("path\tstage\tround\tjobs\tmilliseconds\tms_per_chunk");
    for (stage, kind, handler, frames) in jobs {
        for path in ["core", "framed"] {
            for round in 0..=rounds {
                let mut elapsed = 0.0;
                for _ in 0..repeats {
                    for (index, request) in requests.iter().enumerate() {
                        let mut surface_chunk = (stage == "surface" && path == "core").then(|| filled[index].clone());
                        let started = Instant::now();
                        if path == "framed" {
                            black_box(run_frame(&frames[index], kind, handler));
                        } else {
                            match stage {
                                "biomes" => {
                                    black_box(generator.run_biomes(request.chunk_x, request.chunk_z));
                                }
                                "terrain" => {
                                    black_box(generator.run_terrain(request));
                                }
                                "surface" => generator.run_surface(surface_chunk.as_mut().unwrap(), Beard::default()),
                                "combined" => {
                                    let combined = TerrainRequest {
                                        surface: true,
                                        biomes: true,
                                        ..request.clone()
                                    };
                                    black_box(generator.run_terrain(&combined));
                                }
                                _ => unreachable!(),
                            }
                        }
                        elapsed += started.elapsed().as_secs_f64() * 1e3;
                    }
                }
                if round > 0 {
                    let count = requests.len() * repeats;
                    println!(
                        "{path}\t{stage}\t{round}\t{count}\t{elapsed:.6}\t{:.6}",
                        elapsed / count as f64
                    );
                }
            }
        }
    }
}
