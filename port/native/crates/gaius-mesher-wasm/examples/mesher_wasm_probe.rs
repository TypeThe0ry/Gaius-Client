//! Writes framed `mesh_section` jobs (each carrying the synthetic model table
//! inline) and the result bytes the native build produces for them, so
//! `wasm-check.mjs` can prove the simd128 and the baseline modules mesh byte
//! for byte like the native build.
//!
//! Usage: `cargo run -p gaius-mesher-wasm --example mesher_wasm_probe -- <out-dir>`

use gaius_kernel_abi::{release_descriptor, run_kernel, JobHeader, RunDescriptor};
use gaius_mesher::job::flags;
use gaius_mesher::synthetic;
use gaius_mesher::table::TableWriter;
use gaius_mesher::{kinds, Kernel};
use std::cell::RefCell;
use std::path::PathBuf;

const EPOCH: u32 = 11;

fn jobs() -> Vec<Vec<u8>> {
    let table = TableWriter {
        table: synthetic::table(EPOCH),
    }
    .encode();
    let variants = [
        flags::AMBIENT_OCCLUSION | flags::EMIT_VANILLA | flags::SORT_TRANSLUCENT | flags::EMIT_CENTROIDS,
        flags::EMIT_VANILLA | flags::EMIT_COMPACT | flags::SORT_TRANSLUCENT | flags::CUTOUT_LEAVES,
        flags::AMBIENT_OCCLUSION | flags::EMIT_COMPACT,
    ];
    let mut out = Vec::new();
    for seed in 1..=6u32 {
        let mut job = synthetic::scene(EPOCH, seed);
        job.flags = variants[seed as usize % variants.len()];
        job.camera = [seed as f32 * 1.75 - 3.0, 9.5, 20.0 - seed as f32 * 2.5];
        job.request_seq = seed;
        job.inline_table = table.clone();
        out.push(job.encode());
    }
    out
}

fn main() {
    let out = PathBuf::from(std::env::args().nth(1).expect("usage: wasm_probe <out-dir>"));
    std::fs::create_dir_all(&out).expect("create out dir");
    let kernel = RefCell::new(Kernel::new());
    let jobs = jobs();
    for (index, payload) in jobs.iter().enumerate() {
        let framed = JobHeader::frame(kinds::MESH_SECTION, index as u32, payload);
        let desc = run_kernel(&framed, kinds::MESH_SECTION, |p| kernel.borrow_mut().mesh_section(p));
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
        assert_eq!(
            status,
            0,
            "probe job {index} failed natively: {}",
            String::from_utf8_lossy(&data)
        );
        std::fs::write(out.join(format!("job-{index}.bin")), framed).expect("write job");
        std::fs::write(out.join(format!("job-{index}.expected")), data).expect("write expected");
    }
    println!("{} probe jobs in {}", jobs.len(), out.display());
}
