//! Translates a shader corpus dumped by
//! `port/native/golden/shaders/dump-corpus.sh` and reports per-module results
//! and timings.
//!
//!   shaderxlat-corpus CORPUS_DIR OUT_DIR [--iterations N] [--layout split|flat]
//!                     [--flip-y | --no-flip-y] [--gl-depth-remap]
//!
//! The defaults are the v0.4.0 convention (split binding layout, flipped y);
//! `--layout flat --no-flip-y` reproduces the first prototype's output (flat layout, no y flip), and
//! `--gl-depth-remap` is the 1.21.11 depth rule. Writes
//! OUT_DIR/wgsl/<spirv sha256>.wgsl and OUT_DIR/results.json (whose
//! summary.options the wasm and Chrome checks reuse). Exit status 0 when
//! every pipeline translated, 1 otherwise.

use gaius_shaderxlat::{
    assign_bindings, parse, patch_spirv, reflect, validate, write_wgsl, BindingKind, BindingLayout, Error, Options,
    SampleKind, Stage, TexelFormat, TextureDim,
};
use serde_json::{json, Value};
use std::collections::BTreeMap;
use std::path::Path;
use std::time::Instant;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 3 {
        eprintln!(
            "usage: shaderxlat-corpus CORPUS_DIR OUT_DIR [--iterations N] [--layout split|flat] [--flip-y | --no-flip-y] [--gl-depth-remap]"
        );
        std::process::exit(2);
    }
    let corpus_dir = Path::new(&args[1]);
    let out_dir = Path::new(&args[2]);
    let iterations: usize = args
        .iter()
        .position(|a| a == "--iterations")
        .and_then(|i| args.get(i + 1))
        .map(|n| n.parse().expect("--iterations N"))
        .unwrap_or(20);
    let binding_layout = match args.iter().position(|a| a == "--layout").and_then(|i| args.get(i + 1)) {
        None => BindingLayout::Split,
        Some(name) if name == "split" => BindingLayout::Split,
        Some(name) if name == "flat" => BindingLayout::Flat,
        Some(name) => panic!("--layout split|flat, not {name}"),
    };
    let flip_vertex_y = !args.iter().any(|a| a == "--no-flip-y");
    let gl_depth_remap = args.iter().any(|a| a == "--gl-depth-remap");
    let corpus: Value =
        serde_json::from_str(&std::fs::read_to_string(corpus_dir.join("corpus.json")).expect("corpus.json"))
            .expect("corpus.json parses");
    std::fs::create_dir_all(out_dir.join("wgsl")).expect("out dir");

    // Unique (module, texel buffer formats) jobs.
    let mut jobs: BTreeMap<String, (String, Vec<(u32, TexelFormat)>)> = BTreeMap::new();
    let pipelines = corpus["pipelines"].as_array().expect("pipelines");
    for pipeline in pipelines {
        let mut texel = Vec::new();
        for (index, uniform) in pipeline["uniforms"].as_array().into_iter().flatten().enumerate() {
            if uniform["type"] == "TEXEL_BUFFER" {
                let name = uniform["gpuFormat"].as_str().unwrap_or("");
                let format = TexelFormat::from_gpu_format(name)
                    .unwrap_or_else(|| panic!("unsupported texel buffer format {name}"));
                texel.push((index as u32, format));
            }
        }
        for shader in pipeline["shaders"].as_array().into_iter().flatten() {
            let hash = shader["spirv"].as_str().expect("spirv hash").to_string();
            let key = format!("{hash}{}", texel_key(&texel));
            jobs.entry(key).or_insert((hash, texel.clone()));
        }
    }

    let mut modules = serde_json::Map::new();
    let mut failures = 0;
    let mut totals = Vec::new();
    let mut firsts = Vec::new();
    for (key, (hash, texel)) in &jobs {
        let spirv = std::fs::read(corpus_dir.join("spirv").join(format!("{hash}.spv"))).expect("spirv blob");
        let options = Options {
            texel_buffers: texel.clone(),
            flip_vertex_y,
            binding_layout,
            gl_depth_remap,
        };
        let first = Instant::now();
        let result = run(&spirv, &options);
        let first_us = first.elapsed().as_secs_f64() * 1e6;
        let entry = match result {
            Ok((stage, wgsl, bindings, _)) => {
                // Warm timings per phase: median over `iterations` runs.
                let mut phases: Vec<[f64; 5]> = (0..iterations)
                    .map(|_| run(&spirv, &options).expect("deterministic").3)
                    .collect();
                let median = |i: usize, phases: &mut Vec<[f64; 5]>| {
                    phases.sort_by(|a, b| a[i].partial_cmp(&b[i]).unwrap());
                    phases[phases.len() / 2][i]
                };
                let timing: Vec<f64> = (0..5).map(|i| median(i, &mut phases)).collect();
                totals.push(timing[4]);
                firsts.push(first_us);
                std::fs::write(out_dir.join("wgsl").join(format!("{key}.wgsl")), &wgsl).expect("write wgsl");
                json!({
                    "spirv": hash,
                    "texelBuffers": texel.iter().map(|(b, f)| json!([b, format!("{f:?}")])).collect::<Vec<_>>(),
                    "ok": true,
                    "stage": format!("{stage:?}"),
                    "spirvBytes": spirv.len(),
                    "wgslBytes": wgsl.len(),
                    "wgsl": format!("{key}.wgsl"),
                    "bindings": bindings,
                    "firstUs": round(first_us),
                    "medianUs": {"patch": round(timing[0]), "parse": round(timing[1]), "validate": round(timing[2]),
                        "write": round(timing[3]), "total": round(timing[4])},
                })
            }
            Err(error) => {
                failures += 1;
                println!("FAIL {key}: {error}");
                json!({"spirv": hash, "ok": false, "phase": error.phase(), "error": error.to_string(),
                    "spirvBytes": spirv.len()})
            }
        };
        modules.insert(key.clone(), entry);
    }

    let mut pipeline_ok = 0;
    let mut pipeline_results = Vec::new();
    for pipeline in pipelines {
        let mut texel = Vec::new();
        for (index, uniform) in pipeline["uniforms"].as_array().into_iter().flatten().enumerate() {
            if uniform["type"] == "TEXEL_BUFFER" {
                texel.push((
                    index as u32,
                    TexelFormat::from_gpu_format(uniform["gpuFormat"].as_str().unwrap()).unwrap(),
                ));
            }
        }
        let keys: Vec<String> = pipeline["shaders"]
            .as_array()
            .into_iter()
            .flatten()
            .map(|s| format!("{}{}", s["spirv"].as_str().unwrap(), texel_key(&texel)))
            .collect();
        let ok = !keys.is_empty() && keys.iter().all(|k| modules[k]["ok"] == true);
        pipeline_ok += ok as usize;
        pipeline_results.push(json!({"location": pipeline["location"], "ok": ok, "modules": keys}));
    }

    totals.sort_by(|a, b| a.partial_cmp(b).unwrap());
    firsts.sort_by(|a, b| a.partial_cmp(b).unwrap());
    let summary = json!({
        "pipelines": pipelines.len(),
        "pipelinesOk": pipeline_ok,
        "modules": jobs.len(),
        "modulesOk": jobs.len() - failures,
        "iterations": iterations,
        "options": {"layout": layout_name(binding_layout), "flipVertexY": flip_vertex_y, "glDepthRemap": gl_depth_remap},
        "warmTotalUs": stats(&totals),
        "firstTotalUs": stats(&firsts),
    });
    let report = json!({"summary": summary, "modules": modules, "pipelines": pipeline_results});
    std::fs::write(
        out_dir.join("results.json"),
        serde_json::to_string_pretty(&report).unwrap(),
    )
    .expect("results");
    println!("{}", serde_json::to_string(&summary).unwrap());
    std::process::exit(if failures == 0 && pipeline_ok == pipelines.len() {
        0
    } else {
        1
    });
}

fn texel_key(texel: &[(u32, TexelFormat)]) -> String {
    texel.iter().map(|(b, f)| format!("-t{b}{f:?}")).collect()
}

type Run = (Stage, String, Vec<Value>, [f64; 5]);

fn run(spirv: &[u8], options: &Options) -> Result<Run, Error> {
    let t0 = Instant::now();
    let words = patch_spirv(spirv, options)?;
    let t1 = Instant::now();
    let mut module = parse(&words, options)?;
    assign_bindings(&mut module, options.binding_layout)?;
    let t2 = Instant::now();
    let info = validate(&module)?;
    let t3 = Instant::now();
    let wgsl = write_wgsl(&module, &info)?;
    let t4 = Instant::now();
    let (stage, bindings) = reflect(&module, &info, options.binding_layout)?;
    let us = |a: Instant, b: Instant| (b - a).as_secs_f64() * 1e6;
    let bindings = bindings
        .iter()
        .map(|b| {
            let kind = match b.kind {
                BindingKind::UniformBuffer { size } => json!({"type": "uniform", "size": size}),
                BindingKind::StorageBuffer => json!({"type": "storage"}),
                BindingKind::Texture {
                    dim,
                    kind,
                    multisampled,
                    sampled,
                } => json!({
                    "type": "texture", "dim": dim_name(dim), "kind": kind_name(kind),
                    "multisampled": multisampled, "sampled": sampled}),
                BindingKind::Sampler { comparison } => json!({"type": "sampler", "comparison": comparison}),
            };
            json!({"group": b.group, "binding": b.binding, "uniform": b.uniform, "used": b.used, "kind": kind})
        })
        .collect();
    Ok((
        stage,
        wgsl,
        bindings,
        [us(t0, t1), us(t1, t2), us(t2, t3), us(t3, t4), us(t0, t4)],
    ))
}

fn layout_name(layout: BindingLayout) -> &'static str {
    match layout {
        BindingLayout::Split => "split",
        BindingLayout::Flat => "flat",
    }
}

fn dim_name(dim: TextureDim) -> &'static str {
    match dim {
        TextureDim::D1 => "1d",
        TextureDim::D2 => "2d",
        TextureDim::D2Array => "2d-array",
        TextureDim::D3 => "3d",
        TextureDim::Cube => "cube",
        TextureDim::CubeArray => "cube-array",
    }
}

fn kind_name(kind: SampleKind) -> &'static str {
    match kind {
        SampleKind::Float => "float",
        SampleKind::Sint => "sint",
        SampleKind::Uint => "uint",
        SampleKind::Depth => "depth",
    }
}

fn round(us: f64) -> f64 {
    (us * 10.0).round() / 10.0
}

fn stats(sorted: &[f64]) -> Value {
    if sorted.is_empty() {
        return Value::Null;
    }
    let pick = |q: f64| sorted[((sorted.len() - 1) as f64 * q).round() as usize];
    json!({"count": sorted.len(), "sum": round(sorted.iter().sum()), "mean": round(sorted.iter().sum::<f64>() / sorted.len() as f64),
        "p50": round(pick(0.5)), "p95": round(pick(0.95)), "max": round(pick(1.0))})
}
