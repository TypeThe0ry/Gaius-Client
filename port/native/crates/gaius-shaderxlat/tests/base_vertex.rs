//! `gl_BaseVertex` (resource-pack core shaders that read it, e.g. the player-display shader of
//! ModelEngine-style packs) translates instead of failing the pipeline.

use gaius_shaderxlat::spirv::{decoration, op, words_from_bytes, Module};
use gaius_shaderxlat::{patch_spirv, translate, Options};

fn fixture() -> Vec<u8> {
    let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/frontend/base_vertex.vert.spv");
    std::fs::read(&path).unwrap_or_else(|e| panic!("{}: {e}", path.display()))
}

#[test]
fn fixture_reads_base_vertex() {
    let module = Module::parse(&words_from_bytes(&fixture()).unwrap()).unwrap();
    assert!(module
        .insts
        .iter()
        .any(|i| i.op == op::DECORATE && i.ops[1] == decoration::BUILT_IN && i.ops[2] == 4424));
}

#[test]
fn base_vertex_is_replaced_by_zero() {
    let patched = patch_spirv(&fixture(), &Options::default()).unwrap();
    let module = Module::parse(&patched).unwrap();
    assert!(!module
        .insts
        .iter()
        .any(|i| i.op == op::DECORATE && i.ops[1] == decoration::BUILT_IN && i.ops[2] == 4424));
    assert!(!module.insts.iter().any(|i| i.op == 17 && i.ops[0] == 4427), "DrawParameters capability stays");
}

#[test]
fn shader_reading_base_vertex_translates() {
    let t = translate(&fixture(), &Options::default()).expect("the pipeline must not fail");
    assert!(t.wgsl.contains("vertex_index"), "{}", t.wgsl);
    assert!(!t.wgsl.to_lowercase().contains("base_vertex"), "{}", t.wgsl);
}
