//! Links a blaze3d shader corpus (26.2, 1.21.11) through the GLSL front-end and writes the
//! corpus that `shaderxlat-corpus` and `xlat-check.mjs` read.
//!
//!   shaderxlat-link FRONTEND_DIR CORPUS_DIR [--mode strict|lenient] [--keep-going]
//!
//! FRONTEND_DIR is a `dump-corpus.sh --mc 26.2|1.21.11` dump: frontend.json (pipelines, declared
//! layouts, the shaderc output of each stage) and spirv/<sha256>.spv. Every pipeline goes through
//! `frontend::link` (mode from frontend.json unless `--mode`); CORPUS_DIR gets corpus.json (the
//! pipeline state plus the link's uniforms and modules, `frontend` with the fixes) and
//! spirv/<sha256>.spv of the linked modules.
//!
//! Where the dump carries the vanilla 26.2 result (26.2), the linked modules and entries must be
//! byte-identical to it, and a pipeline vanilla refuses must fail with the same message. Exit
//! status 0 when every pipeline links (or `--keep-going`) and nothing differs from vanilla.

use gaius_shaderxlat::frontend::{self, Attribute, BaseType, EntryKind, Layout, Mode, Uniform};
use serde_json::{json, Map, Value};
use std::collections::{BTreeMap, BTreeSet};
use std::path::Path;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 3 {
        eprintln!("usage: shaderxlat-link FRONTEND_DIR CORPUS_DIR [--mode strict|lenient] [--keep-going]");
        std::process::exit(2);
    }
    let input = Path::new(&args[1]);
    let output = Path::new(&args[2]);
    let keep_going = args.iter().any(|a| a == "--keep-going");
    let frontend: Value =
        serde_json::from_str(&std::fs::read_to_string(input.join("frontend.json")).expect("frontend.json"))
            .expect("frontend.json parses");
    let mode_name = args
        .iter()
        .position(|a| a == "--mode")
        .and_then(|i| args.get(i + 1))
        .map(String::as_str)
        .or(frontend["mode"].as_str())
        .unwrap_or("strict")
        .to_string();
    let mode = match mode_name.as_str() {
        "strict" => Mode::Strict,
        "lenient" => Mode::Lenient,
        other => panic!("--mode strict|lenient, not {other}"),
    };
    std::fs::create_dir_all(output.join("spirv")).expect("corpus dir");
    let read = |hash: &str| std::fs::read(input.join("spirv").join(format!("{hash}.spv"))).expect("spirv blob");

    let mut pipelines = Vec::new();
    let mut unique = BTreeSet::new();
    let (mut ok, mut failed, mut compared, mut identical, mut mismatches) = (0, 0, 0, 0, 0);
    let mut fix_counts: BTreeMap<String, usize> = BTreeMap::new();
    let mut fixed_pipelines = 0;
    let mut link_us = Vec::new();
    for source in frontend["pipelines"].as_array().expect("pipelines") {
        let location = source["location"].as_str().unwrap_or("?").to_string();
        // The dump's corpus fields pass through; the link's own fields replace the dump's.
        let mut pipeline: Map<String, Value> = source
            .as_object()
            .expect("pipeline object")
            .iter()
            .filter(|(k, _)| !matches!(k.as_str(), "layout" | "intermediate" | "vanilla" | "vanillaError"))
            .map(|(k, v)| (k.clone(), v.clone()))
            .collect();
        if !source["error"].is_null() || source["intermediate"].is_null() {
            failed += 1;
            println!("FAIL {location}: {}", source["error"]);
            pipelines.push(Value::Object(pipeline));
            continue;
        }
        let layout = layout_of(&source["layout"]);
        let vs = read(source["intermediate"]["VERTEX"].as_str().expect("vertex hash"));
        let fs = read(source["intermediate"]["FRAGMENT"].as_str().expect("fragment hash"));
        let started = std::time::Instant::now();
        let result = frontend::link(&vs, &fs, &layout, mode);
        link_us.push(started.elapsed().as_secs_f64() * 1e6);
        let vanilla = &source["vanilla"];
        let vanilla_error = source["vanillaError"].as_str();
        match result {
            Err(error) => {
                failed += 1;
                println!("FAIL {location}: {error}");
                if let Some(expected) = vanilla_error {
                    compared += 1;
                    if error.to_string() == format!("link: {expected}") {
                        identical += 1;
                    } else {
                        mismatches += 1;
                        println!("MISMATCH {location}: vanilla fails with {expected:?}");
                    }
                } else if !vanilla.is_null() {
                    compared += 1;
                    mismatches += 1;
                    println!("MISMATCH {location}: vanilla links");
                }
                pipeline.insert("error".into(), json!(error.to_string()));
            }
            Ok(linked) => {
                ok += 1;
                let entries: Vec<Value> = linked
                    .entries
                    .iter()
                    .map(|e| {
                        let (kind, format) = match &e.kind {
                            EntryKind::UniformBuffer => ("UNIFORM_BUFFER", None),
                            EntryKind::TexelBuffer { format } => ("TEXEL_BUFFER", format.clone()),
                            EntryKind::SampledImage => ("COMBINED_IMAGE_SAMPLER", None),
                        };
                        json!({"name": e.name, "type": kind, "gpuFormat": format, "declared": e.declared})
                    })
                    .collect();
                let hashes = [sha256_hex(&linked.vertex), sha256_hex(&linked.fragment)];
                for (hash, bytes) in hashes.iter().zip([&linked.vertex, &linked.fragment]) {
                    if unique.insert(hash.clone()) {
                        std::fs::write(output.join("spirv").join(format!("{hash}.spv")), bytes).expect("write spirv");
                    }
                }
                if vanilla_error.is_some() {
                    compared += 1;
                    mismatches += 1;
                    println!("MISMATCH {location}: vanilla fails with {vanilla_error:?}");
                } else if !vanilla.is_null() {
                    compared += 1;
                    let same_modules = vanilla["spirv"]["VERTEX"] == hashes[0].as_str()
                        && vanilla["spirv"]["FRAGMENT"] == hashes[1].as_str();
                    let without_declared: Vec<Value> = entries
                        .iter()
                        .map(|e| json!({"name": e["name"], "type": e["type"], "gpuFormat": e["gpuFormat"]}))
                        .collect();
                    let same_entries = vanilla["uniforms"] == Value::Array(without_declared);
                    if same_modules && same_entries {
                        identical += 1;
                    } else {
                        mismatches += 1;
                        println!(
                            "MISMATCH {location}: modules {} entries {}",
                            if same_modules { "identical" } else { "differ" },
                            if same_entries { "identical" } else { "differ" }
                        );
                    }
                }
                let fixes: Vec<String> = linked.fixes.iter().map(|f| f.describe()).collect();
                for fix in &fixes {
                    *fix_counts
                        .entry(fix.split(':').next().unwrap().to_string())
                        .or_default() += 1;
                }
                fixed_pipelines += !fixes.is_empty() as usize;
                let ids = &source["shaderIds"];
                pipeline.insert("uniforms".into(), Value::Array(entries));
                pipeline.insert(
                    "shaders".into(),
                    json!([
                        {"name": ids["VERTEX"], "entryPoint": "main", "type": "VERTEX", "spirv": hashes[0],
                            "bytes": linked.vertex.len()},
                        {"name": ids["FRAGMENT"], "entryPoint": "main", "type": "FRAGMENT", "spirv": hashes[1],
                            "bytes": linked.fragment.len()},
                    ]),
                );
                pipeline.insert("frontend".into(), json!({"mode": mode_name, "fixes": fixes}));
            }
        }
        pipelines.push(Value::Object(pipeline));
    }

    link_us.sort_by(|a, b| a.partial_cmp(b).unwrap());
    let summary = json!({
        "pipelines": pipelines.len(),
        "ok": ok,
        "failed": failed,
        "uniqueModules": unique.len(),
        "mode": mode_name,
        "vanillaCompared": compared,
        "vanillaIdentical": identical,
        "vanillaMismatches": mismatches,
        "fixedPipelines": fixed_pipelines,
        "fixes": fix_counts,
        "linkUs": {
            "sum": link_us.iter().sum::<f64>().round(),
            "p50": link_us.get(link_us.len() / 2).copied().unwrap_or(0.0).round(),
            "max": link_us.last().copied().unwrap_or(0.0).round(),
        },
    });
    let mut text = String::from("{");
    text += &format!(
        "\"profile\":{},\"frontend\":{},\"pipelines\":[\n",
        frontend["profile"],
        json!(format!(
            "{} + gaius-shaderxlat frontend::link ({mode_name})",
            frontend["frontend"].as_str().unwrap_or("")
        ))
    );
    for (i, pipeline) in pipelines.iter().enumerate() {
        text += if i == 0 { "" } else { ",\n" };
        text += &pipeline.to_string();
    }
    text += &format!("\n],\"summary\":{summary}}}\n");
    std::fs::write(output.join("corpus.json"), text).expect("write corpus.json");
    println!("{summary}");
    let bad = mismatches > 0 || (failed > 0 && !keep_going);
    std::process::exit(bad as i32);
}

fn layout_of(value: &Value) -> Layout {
    let strings = |v: &Value| v.as_str().map(str::to_string);
    Layout {
        uniforms: value["uniforms"]
            .as_array()
            .into_iter()
            .flatten()
            .map(|u| Uniform {
                name: strings(&u["name"]).expect("uniform name"),
                texel_format: strings(&u["gpuFormat"]),
            })
            .collect(),
        samplers: value["samplers"]
            .as_array()
            .into_iter()
            .flatten()
            .map(|s| strings(s).expect("sampler name"))
            .collect(),
        attributes: value["attributes"]
            .as_array()
            .into_iter()
            .flatten()
            .map(|a| Attribute {
                name: strings(&a["name"]).expect("attribute name"),
                base: BaseType::of_gpu_format(a["format"].as_str().unwrap_or("")),
            })
            .collect(),
    }
}

/// SHA-256 (FIPS 180-4) of `data` as lowercase hex: the corpus names modules by it.
fn sha256_hex(data: &[u8]) -> String {
    const K: [u32; 64] = [
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5, 0xd807aa98,
        0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786,
        0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8,
        0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
        0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819,
        0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a,
        0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7,
        0xc67178f2,
    ];
    let mut h: [u32; 8] = [
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
    ];
    let mut message = data.to_vec();
    message.push(0x80);
    while message.len() % 64 != 56 {
        message.push(0);
    }
    message.extend_from_slice(&((data.len() as u64) * 8).to_be_bytes());
    for block in message.as_chunks::<64>().0 {
        let mut w = [0u32; 64];
        for (i, word) in block.as_chunks::<4>().0.iter().enumerate() {
            w[i] = u32::from_be_bytes(*word);
        }
        for i in 16..64 {
            let s0 = w[i - 15].rotate_right(7) ^ w[i - 15].rotate_right(18) ^ (w[i - 15] >> 3);
            let s1 = w[i - 2].rotate_right(17) ^ w[i - 2].rotate_right(19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16].wrapping_add(s0).wrapping_add(w[i - 7]).wrapping_add(s1);
        }
        let [mut a, mut b, mut c, mut d, mut e, mut f, mut g, mut hh] = h;
        for i in 0..64 {
            let s1 = e.rotate_right(6) ^ e.rotate_right(11) ^ e.rotate_right(25);
            let ch = (e & f) ^ (!e & g);
            let t1 = hh
                .wrapping_add(s1)
                .wrapping_add(ch)
                .wrapping_add(K[i])
                .wrapping_add(w[i]);
            let s0 = a.rotate_right(2) ^ a.rotate_right(13) ^ a.rotate_right(22);
            let maj = (a & b) ^ (a & c) ^ (b & c);
            let t2 = s0.wrapping_add(maj);
            hh = g;
            g = f;
            f = e;
            e = d.wrapping_add(t1);
            d = c;
            c = b;
            b = a;
            a = t1.wrapping_add(t2);
        }
        for (state, value) in h.iter_mut().zip([a, b, c, d, e, f, g, hh]) {
            *state = state.wrapping_add(value);
        }
    }
    h.iter().map(|v| format!("{v:08x}")).collect()
}

#[cfg(test)]
mod tests {
    #[test]
    fn sha256_known_answers() {
        assert_eq!(
            super::sha256_hex(b""),
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        );
        assert_eq!(
            super::sha256_hex(b"abc"),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
    }
}
