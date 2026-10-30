//! Times the generator jobs on an IR file (for example the vanilla overworld written by
//! `port/native/golden/worldgen/run-worldgen-reference.sh`).
//!
//!   cargo run --release -p gaius-worldgen --example bench_ir -- <ir-file> [chunks]

use gaius_worldgen::{Generator, TerrainRequest};
use std::collections::HashMap;
use std::time::Instant;

fn main() {
    let mut args = std::env::args().skip(1);
    let path = args.next().expect("usage: bench_ir <ir-file> [chunks]");
    let count: i32 = args.next().map_or(16, |n| n.parse().expect("chunk count"));
    let ir = std::fs::read(&path).expect("IR file");
    let started = Instant::now();
    let mut generator = Generator::load(&ir).expect("IR loads");
    println!(
        "load: {:.1} ms ({} IR bytes)",
        started.elapsed().as_secs_f64() * 1e3,
        ir.len()
    );
    let side = (count as f64).sqrt().ceil() as i32;
    let mut states: HashMap<u16, usize> = HashMap::new();
    for (label, surface) in [("fill", false), ("fill+surface", true)] {
        let started = Instant::now();
        let mut done = 0;
        'outer: for cx in 0..side {
            for cz in 0..side {
                if done == count {
                    break 'outer;
                }
                let result = generator.run_terrain(&TerrainRequest {
                    chunk_x: cx,
                    chunk_z: cz,
                    surface,
                    ..TerrainRequest::default()
                });
                if surface {
                    for &b in &result.chunk.blocks {
                        *states.entry(b).or_default() += 1;
                    }
                }
                done += 1;
            }
        }
        let ms = started.elapsed().as_secs_f64() * 1e3;
        println!(
            "{label}: {count} chunks in {ms:.1} ms ({:.2} ms/chunk)",
            ms / count as f64
        );
    }
    let started = Instant::now();
    for i in 0..count {
        generator.run_biomes(i % side, i / side);
    }
    let ms = started.elapsed().as_secs_f64() * 1e3;
    println!(
        "biomes: {count} chunks in {ms:.1} ms ({:.2} ms/chunk)",
        ms / count as f64
    );
    let mut top: Vec<_> = states.into_iter().collect();
    top.sort_by_key(|&(_, n)| std::cmp::Reverse(n));
    let names: Vec<String> = top
        .iter()
        .take(12)
        .map(|&(s, n)| format!("{}x{}", generator.states.global_ids[s as usize], n))
        .collect();
    println!("most common global states after surface: {}", names.join(" "));
    println!("footprint: {:?}", generator.footprint());
}
