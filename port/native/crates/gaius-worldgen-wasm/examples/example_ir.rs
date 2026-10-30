//! Writes the example generator IRs (`<profile>.gwir` for every version profile of
//! `port/versions/*.json`, built with its kernel semantics) into a directory, for exercising
//! the wasm kernel and `port/web/kernels/worldgen-job.js` from node.
//!
//!   cargo run -p gaius-worldgen-wasm --example example_ir -- <out-dir>

use gaius_noise::semantics::profiles;
use gaius_worldgen::ir::example::overworld;

fn main() {
    let dir = std::env::args().nth(1).expect("usage: example_ir <out-dir>");
    std::fs::create_dir_all(&dir).expect("output directory");
    for (profile, semantics) in profiles() {
        let path = std::path::Path::new(&dir).join(format!("{profile}.gwir"));
        std::fs::write(&path, overworld(semantics, 1234).encode()).expect("write IR");
        println!("{}", path.display());
    }
}
