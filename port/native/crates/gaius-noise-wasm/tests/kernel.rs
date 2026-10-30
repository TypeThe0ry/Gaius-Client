//! Drives the `noise_points` kernel through the full ABI framing natively and
//! checks it against golden fixture lines.

use gaius_kernel_abi::{
    kind, release_descriptor, run_kernel, JobHeader, ResultHeader, RunDescriptor, Status, RESULT_HEADER_LEN,
};
use gaius_noise::semantics::profiles;
use gaius_noise::synth32::{NormalNoiseParameters, Normalization};
use gaius_noise::{RandomKind, Semantics};
use gaius_noise_wasm::job::{run_noise_points, NoisePointsJob, NoiseSpec};
use serde_json::Value;
use std::path::Path;

/// Runs one framed job and returns (status, data).
fn run(job: &NoisePointsJob) -> (u32, Vec<u8>) {
    let framed = JobHeader::frame(kind::NOISE_POINTS, 77, &job.encode());
    let desc = run_kernel(&framed, kind::NOISE_POINTS, run_noise_points);
    assert!(!desc.is_null());
    // SAFETY: the descriptor and its data were just produced by run_kernel.
    let (status, data) = unsafe {
        let head = std::slice::from_raw_parts(desc, RunDescriptor::LEN);
        let status = u32::from_le_bytes(head[0..4].try_into().unwrap());
        let len = u32::from_le_bytes(head[8..12].try_into().unwrap()) as usize;
        let data = std::slice::from_raw_parts(desc.add(RunDescriptor::LEN), len).to_vec();
        release_descriptor(desc);
        (status, data)
    };
    (status, data)
}

fn run_ok(job: &NoisePointsJob) -> Vec<f64> {
    let (status, data) = run(job);
    assert_eq!(status, 0, "kernel failed: {}", String::from_utf8_lossy(&data));
    let header = ResultHeader::parse(&data).expect("result header");
    assert_eq!(header.job_id, 77);
    data[RESULT_HEADER_LEN..]
        .as_chunks::<8>()
        .0
        .iter()
        .map(|c| f64::from_le_bytes(*c))
        .collect()
}

fn hex(v: &Value) -> f64 {
    f64::from_bits(u64::from_str_radix(v.as_str().unwrap(), 16).unwrap())
}

fn hex_list(v: &Value) -> Vec<f64> {
    v.as_array().unwrap().iter().map(hex).collect()
}

fn fixture_lines(profile: &str, file: &str) -> Vec<Value> {
    let path = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../fixtures")
        .join(profile)
        .join(file);
    let text = std::fs::read_to_string(&path).unwrap_or_else(|e| panic!("{}: {e}", path.display()));
    text.lines().map(|line| serde_json::from_str(line).unwrap()).collect()
}

/// Builds the kernel job for a normal or blended noise fixture line, if the
/// line is one the kernel covers (point sampling).
fn job_for(semantics: Semantics, line: &Value) -> Option<NoisePointsJob> {
    let p = &line["params"];
    let method = p["method"].as_str().unwrap_or("value3");
    if method == "volume" || p.get("ctor").and_then(Value::as_str) == Some("legacy_nether") {
        return None;
    }
    let noise = match line["kind"].as_str().unwrap() {
        "normal_noise" if p.get("parity_first_octave").is_some() => NoiseSpec::Normal {
            first_octave: p["parity_first_octave"].as_i64().unwrap() as i32,
            amplitudes: hex_list(&p["parity_amplitudes"]),
        },
        "normal_noise" if semantics.uses_synth32() => NoiseSpec::NormalRecipe(NormalNoiseParameters {
            base_amplitude: hex(&p["base_amplitude"]),
            base_octave: p["base_octave"].as_i64().unwrap() as i32,
            octave_count: p["octave_count"].as_i64().unwrap() as i32,
            normalize: match p["normalize"].as_str().unwrap() {
                "disabled" => Normalization::Disabled,
                "legacy" => Normalization::Legacy,
                _ => Normalization::Enabled,
            },
            amplitude_modifiers: hex_list(&p["amplitude_modifiers"]),
        }),
        "normal_noise" => NoiseSpec::Normal {
            first_octave: p["first_octave"].as_i64().unwrap() as i32,
            amplitudes: hex_list(&p["amplitudes"]),
        },
        "blended_noise" => NoiseSpec::Blended(
            ["xz_scale", "y_scale", "xz_factor", "y_factor", "smear_scale_multiplier"].map(|k| hex(&p[k])),
        ),
        _ => return None,
    };
    let xyz = line["inputs"]
        .as_array()
        .unwrap()
        .iter()
        .flat_map(|row| {
            row.as_array()
                .unwrap()
                .iter()
                .map(|v| {
                    if v.is_string() {
                        hex(v)
                    } else {
                        v.as_i64().unwrap() as f64
                    }
                })
                .collect::<Vec<_>>()
        })
        .collect();
    Some(NoisePointsJob {
        semantics,
        random: if p["random"] == "legacy" {
            RandomKind::Legacy
        } else {
            RandomKind::Xoroshiro
        },
        seed: p["seed"].as_str().unwrap().parse().unwrap(),
        fork: p.get("fork").and_then(Value::as_str).map(String::from),
        noise,
        xyz,
    })
}

/// Some declared semantics with the float synth classes (recipes exist only there).
fn float_semantics() -> Semantics {
    *Semantics::all()
        .iter()
        .find(|s| s.uses_synth32())
        .expect("a profile with float synth classes")
}

/// Some declared semantics with the double synth classes.
fn double_semantics() -> Semantics {
    *Semantics::all()
        .iter()
        .find(|s| !s.uses_synth32())
        .expect("a profile with double synth classes")
}

#[test]
fn kernel_reproduces_golden_noise() {
    let mut checked = 0;
    for (profile, semantics) in profiles() {
        for file in ["normal_noise.jsonl", "blended_noise.jsonl"] {
            for line in fixture_lines(profile, file) {
                let Some(job) = job_for(semantics, &line) else { continue };
                let expected: Vec<u64> = line["outputs"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .map(|v| hex(v).to_bits())
                    .collect();
                let actual: Vec<u64> = run_ok(&job).iter().map(|v| v.to_bits()).collect();
                assert_eq!(actual, expected, "{} {} {}", profile, file, line["params"]);
                checked += 1;
            }
        }
    }
    assert!(checked > 100, "only {checked} fixture lines exercised the kernel");
}

#[test]
fn encode_decode_round_trip() {
    let job = NoisePointsJob {
        semantics: float_semantics(),
        random: RandomKind::Legacy,
        seed: -5,
        fork: Some("minecraft:ridge".into()),
        noise: NoiseSpec::NormalRecipe(NormalNoiseParameters {
            base_amplitude: 0.5,
            base_octave: -7,
            octave_count: 3,
            normalize: Normalization::Legacy,
            amplitude_modifiers: vec![1.0, 0.0, 2.0],
        }),
        xyz: vec![1.0, 2.0, 3.0, -4.5, 5.25, 1e7],
    };
    assert_eq!(NoisePointsJob::decode(&job.encode()).unwrap(), job);
}

#[test]
fn rejects_invalid_payloads() {
    let mut job = NoisePointsJob {
        semantics: double_semantics(),
        random: RandomKind::Xoroshiro,
        seed: 1,
        fork: None,
        noise: NoiseSpec::Normal {
            first_octave: -3,
            amplitudes: vec![],
        },
        xyz: vec![0.0; 3],
    };
    assert_eq!(run(&job).0, Status::BadPayload as u32, "empty amplitudes");

    job.noise = NoiseSpec::Blended([1.0, 1.0, 80.0, 160.0, 8.0]);
    job.xyz = vec![0.5, 1.0, 2.0];
    assert_eq!(run(&job).0, Status::BadPayload as u32, "fractional block position");

    job.xyz = vec![0.0, 64.0, 0.0];
    let mut payload = job.encode();
    payload.pop();
    let framed = JobHeader::frame(kind::NOISE_POINTS, 1, &payload);
    let desc = run_kernel(&framed, kind::NOISE_POINTS, run_noise_points);
    // SAFETY: produced by run_kernel above.
    let status = unsafe { u32::from_le_bytes(std::slice::from_raw_parts(desc, 4).try_into().unwrap()) };
    unsafe { release_descriptor(desc) };
    assert_eq!(status, Status::Truncated as u32);
}

#[test]
fn rejects_oversized_point_count_before_allocation() {
    // The count is rejected before decoding the rest of the job, so a hostile
    // header cannot force a large xyz/output allocation or a panic.
    let mut payload = vec![0u8; 12];
    payload[0] = double_semantics().code;
    payload[4..8].copy_from_slice(&((1u32 << 20) + 1).to_le_bytes());
    assert_eq!(NoisePointsJob::decode(&payload).unwrap_err().status, Status::BadPayload);
}
