//! Writes framed `noise_points` jobs and the result bytes the native build
//! produces for them, so `wasm-check.mjs` can prove the wasm build answers
//! bit for bit the same.
//!
//! Usage: `cargo run -p gaius-noise-wasm --example wasm_probe -- <out-dir>`

use gaius_kernel_abi::{kind, release_descriptor, run_kernel, JobHeader, RunDescriptor};
use gaius_noise::synth32::{NormalNoiseParameters, Normalization};
use gaius_noise::{RandomKind, Semantics};
use gaius_noise_wasm::job::{run_noise_points, NoisePointsJob, NoiseSpec};
use std::path::PathBuf;

fn positions(count: usize, integral: bool) -> Vec<f64> {
    // A fixed spread of coordinates: negatives, fractions, far lands.
    let mut state = 0x2545F4914F6CDD1Du64;
    (0..count * 3)
        .map(|i| {
            state ^= state << 13;
            state ^= state >> 7;
            state ^= state << 17;
            let unit = (state >> 11) as f64 / (1u64 << 53) as f64 - 0.5;
            let scale = [1.0e3, 4.0e4, 3.0e7][i % 3];
            if integral {
                (unit * if i % 3 == 1 { 640.0 } else { scale }).floor()
            } else {
                unit * scale
            }
        })
        .collect()
}

fn jobs() -> Vec<NoisePointsJob> {
    let mut jobs = Vec::new();
    for &semantics in Semantics::all() {
        for (random, fork) in [
            (RandomKind::Xoroshiro, Some("minecraft:continentalness")),
            (RandomKind::Legacy, None),
        ] {
            jobs.push(NoisePointsJob {
                semantics,
                random,
                seed: 7331008642,
                fork: fork.map(String::from),
                noise: NoiseSpec::Normal {
                    first_octave: -9,
                    amplitudes: vec![1.0, 1.0, 2.0, 2.0, 2.0, 1.0, 1.0, 1.0, 1.0],
                },
                xyz: positions(64, false),
            });
        }
        jobs.push(NoisePointsJob {
            semantics,
            random: RandomKind::Xoroshiro,
            seed: -42,
            fork: Some("minecraft:terrain".into()),
            noise: NoiseSpec::Blended([0.25, 0.125, 80.0, 160.0, 8.0]),
            xyz: positions(32, true),
        });
    }
    // NormalNoise recipes exist only with the float synth classes.
    let float = Semantics::all().iter().copied().find(|s| s.uses_synth32());
    jobs.extend(float.map(|semantics| NoisePointsJob {
        semantics,
        random: RandomKind::Xoroshiro,
        seed: 12345,
        fork: Some("minecraft:ridge".into()),
        noise: NoiseSpec::NormalRecipe(NormalNoiseParameters {
            base_amplitude: 0.9147152149950137,
            base_octave: -7,
            octave_count: 6,
            normalize: Normalization::Enabled,
            amplitude_modifiers: vec![1.0, 2.0, 1.0, 0.0, 0.0, 0.0],
        }),
        xyz: positions(64, false),
    }));
    jobs
}

fn main() {
    let out = PathBuf::from(std::env::args().nth(1).expect("usage: wasm_probe <out-dir>"));
    std::fs::create_dir_all(&out).expect("create out dir");
    for (index, job) in jobs().iter().enumerate() {
        let framed = JobHeader::frame(kind::NOISE_POINTS, index as u32, &job.encode());
        let desc = run_kernel(&framed, kind::NOISE_POINTS, run_noise_points);
        // SAFETY: the descriptor and its data were just produced by run_kernel.
        let (status, data) = unsafe {
            let head = std::slice::from_raw_parts(desc, RunDescriptor::LEN);
            let status = u32::from_le_bytes(head[0..4].try_into().unwrap());
            let len = u32::from_le_bytes(head[8..12].try_into().unwrap()) as usize;
            let data = std::slice::from_raw_parts(desc.add(RunDescriptor::LEN), len).to_vec();
            release_descriptor(desc);
            (status, data)
        };
        assert_eq!(
            status,
            0,
            "probe job {index} failed natively: {}",
            String::from_utf8_lossy(&data)
        );
        std::fs::write(out.join(format!("job-{index}.bin")), framed).expect("write job");
        std::fs::write(out.join(format!("job-{index}.expected")), data).expect("write expected");
    }
    println!("{} probe jobs in {}", jobs().len(), out.display());
}
