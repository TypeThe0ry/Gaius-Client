use std::collections::BTreeMap;
use std::env;
use std::fs;
use std::path::PathBuf;

fn main() {
    let manifest = PathBuf::from(env::var_os("CARGO_MANIFEST_DIR").unwrap());
    let versions = manifest.join("../../../versions");
    println!("cargo:rerun-if-changed={}", versions.display());
    let mut layouts = BTreeMap::new();
    for entry in fs::read_dir(versions).unwrap() {
        let path = entry.unwrap().path();
        if path.extension().is_none_or(|extension| extension != "json") {
            continue;
        }
        println!("cargo:rerun-if-changed={}", path.display());
        let profile: serde_json::Value = serde_json::from_slice(&fs::read(&path).unwrap()).unwrap();
        let semantics = &profile["kernelSemantics"];
        let code = u16::try_from(semantics["code"].as_u64().unwrap()).unwrap();
        let stride = u16::try_from(semantics["meshVertexStride"].as_u64().unwrap()).unwrap();
        assert!(
            matches!(stride, 28 | 32),
            "unsupported meshVertexStride in {}",
            path.display()
        );
        if let Some(previous) = layouts.insert(code, stride) {
            assert_eq!(previous, stride, "conflicting vertex layouts for semantics {code}");
        }
    }
    assert!(!layouts.is_empty());
    let source = format!(
        "pub const VERTEX_LAYOUTS: &[(u16, u16)] = &{:?};\n",
        layouts.into_iter().collect::<Vec<_>>()
    );
    fs::write(
        PathBuf::from(env::var_os("OUT_DIR").unwrap()).join("mesh_layouts.rs"),
        source,
    )
    .unwrap();
}
