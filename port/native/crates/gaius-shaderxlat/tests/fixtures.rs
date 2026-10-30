//! Vanilla 26.3 modules through the whole translation, one per SPIR-V patch.
//!
//! The modules are compiled from Mojang's shader sources, so the repository does not carry them:
//! `port/native/golden/shaders/dump-corpus.sh` (webgpu profile, needs `port/work/26.3`) copies
//! them into `port/native/golden/shaders/fixtures` (ignored by git) by the hashes listed in
//! `fixtures/MANIFEST`, or `GAIUS_SHADERXLAT_FIXTURES` names another directory. Without them each
//! test reports the skip and passes, unless `GAIUS_SHADERXLAT_REQUIRE_FIXTURES=1` (run-corpus.sh).

use gaius_shaderxlat::spirv::{words_from_bytes, Module};
use gaius_shaderxlat::{
    translate, BindingKind, BindingLayout, Error, Options, SampleKind, Stage, TexelFormat, TextureDim,
};
use std::path::PathBuf;

fn load_fixture(name: &str) -> Option<Vec<u8>> {
    let directory = match std::env::var_os("GAIUS_SHADERXLAT_FIXTURES") {
        Some(directory) => PathBuf::from(directory),
        None => PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../golden/shaders/fixtures"),
    };
    let path = directory.join(name);
    match std::fs::read(&path) {
        Ok(bytes) => Some(bytes),
        Err(e)
            if e.kind() == std::io::ErrorKind::NotFound
                && std::env::var_os("GAIUS_SHADERXLAT_REQUIRE_FIXTURES").is_none_or(|v| v != "1") =>
        {
            eprintln!(
                "skipping: {} is missing (run port/native/golden/shaders/dump-corpus.sh)",
                path.display()
            );
            None
        }
        Err(e) => panic!("{}: {e}", path.display()),
    }
}

/// The bytes of a fixture module; returns from the test when the fixtures are not dumped.
macro_rules! fixture {
    ($name:expr) => {
        match load_fixture($name) {
            Some(bytes) => bytes,
            None => return,
        }
    };
}

fn binding(t: &gaius_shaderxlat::Translation, group: u32, binding: u32) -> gaius_shaderxlat::Binding {
    *t.bindings
        .iter()
        .find(|b| b.group == group && b.binding == binding)
        .unwrap_or_else(|| panic!("no binding {group}:{binding}"))
}

/// The first prototype's convention: one group, no flip.
fn flat() -> Options {
    Options {
        binding_layout: BindingLayout::Flat,
        flip_vertex_y: false,
        ..Options::default()
    }
}

#[test]
fn modules_round_trip_through_the_editor() {
    for name in [
        "clouds.vert.spv",
        "debug_points.vert.spv",
        "oit_transmittance_terrain.frag.spv",
        "panorama.frag.spv",
        "solid_terrain.vert.spv",
    ] {
        let words = words_from_bytes(&fixture!(name)).unwrap();
        assert_eq!(Module::parse(&words).unwrap().emit(), words, "{name}");
    }
}

#[test]
fn texel_buffer_becomes_a_read_only_storage_buffer() {
    let options = Options {
        texel_buffers: vec![(4, TexelFormat::R8Sint)],
        ..Options::default()
    };
    let t = translate(&fixture!("clouds.vert.spv"), &options).unwrap();
    assert_eq!(t.stage, Stage::Vertex);
    // Split layout: the texel buffer of uniform 4 in group 1 at 2 * 4.
    let storage = binding(&t, 1, 8);
    assert_eq!((storage.kind, storage.uniform), (BindingKind::StorageBuffer, 4));
    assert!(
        t.wgsl
            .contains("@group(1) @binding(8) \nvar<storage> CloudFaces: GaiusTexelBuffer;"),
        "{}",
        t.wgsl
    );
    // Sign extension of byte (index & 3) of word (index >> 2).
    assert!(t.wgsl.contains("CloudFaces.data["));
    assert!(t.wgsl.contains(">> bitcast<u32>(24i)"));
    // Uniform blocks sit in group 0 at their vanilla index; nothing else does.
    let ubo = binding(&t, 0, 0);
    assert!(matches!(ubo.kind, BindingKind::UniformBuffer { size: 48 }));
    assert_eq!(ubo.uniform, 0);
    assert!(t
        .bindings
        .iter()
        .all(|b| (b.group == 0) == matches!(b.kind, BindingKind::UniformBuffer { .. })));
}

#[test]
fn texel_buffer_needs_its_format() {
    match translate(&fixture!("clouds.vert.spv"), &Options::default()) {
        Err(Error::Spirv(message)) => assert!(message.contains("texel format"), "{message}"),
        other => panic!("expected a SPIR-V patch error, got {other:?}"),
    }
}

#[test]
fn output_array_is_split_per_location() {
    let t = translate(&fixture!("oit_transmittance_terrain.frag.spv"), &Options::default()).unwrap();
    assert_eq!(t.stage, Stage::Fragment);
    assert!(
        t.wgsl.contains("var<private> coeff: array<vec4<f32>, 2>;"),
        "{}",
        t.wgsl
    );
    assert!(t.wgsl.contains("@location(0) "));
    assert!(t.wgsl.contains("@location(1) "));
    // Both textures are split: image at group 1 binding 2i, sampler at 2i + 1.
    for (texture, sampler) in [(10, 11), (12, 13)] {
        assert!(matches!(
            binding(&t, 1, texture).kind,
            BindingKind::Texture {
                dim: TextureDim::D2,
                ..
            }
        ));
        assert!(matches!(
            binding(&t, 1, sampler).kind,
            BindingKind::Sampler { comparison: false }
        ));
        assert_eq!(binding(&t, 1, sampler).uniform, texture / 2);
    }
}

#[test]
fn point_size_is_dropped() {
    let t = translate(&fixture!("debug_points.vert.spv"), &Options::default()).unwrap();
    assert!(!t.wgsl.to_lowercase().contains("point_size"), "{}", t.wgsl);
    assert!(t.wgsl.contains("@builtin(position)"));
}

#[test]
fn cube_sampler_splits() {
    let t = translate(&fixture!("panorama.frag.spv"), &Options::default()).unwrap();
    match binding(&t, 1, 4).kind {
        BindingKind::Texture {
            dim,
            kind,
            multisampled,
            sampled,
        } => {
            assert_eq!(
                (dim, kind, multisampled, sampled),
                (TextureDim::Cube, SampleKind::Float, false, true)
            );
        }
        other => panic!("binding 4 is {other:?}"),
    }
    assert!(binding(&t, 1, 5).used);
    assert!(t.wgsl.contains("texture_cube<f32>"));
}

#[test]
fn sampler_parameters_split_through_calls() {
    let t = translate(&fixture!("solid_terrain.vert.spv"), &Options::default()).unwrap();
    // sample_lightmap(sampler2D, ivec2) takes the image and the sampler.
    assert!(
        t.wgsl.contains("lightMap: texture_2d<f32>, lightMap_sampler: sampler"),
        "{}",
        t.wgsl
    );
    // Sampler2 (uniform 5) is sampled inside sample_lightmap: the reflection follows the call.
    match binding(&t, 1, 10).kind {
        BindingKind::Texture { sampled, .. } => assert!(sampled),
        other => panic!("binding 10 is {other:?}"),
    }
}

#[test]
fn translation_is_deterministic() {
    let a = translate(&fixture!("solid_terrain.vert.spv"), &Options::default()).unwrap();
    let b = translate(&fixture!("solid_terrain.vert.spv"), &Options::default()).unwrap();
    assert_eq!(a.wgsl, b.wgsl);
}

#[test]
fn flip_vertex_y_negates_the_position() {
    let unflipped = Options {
        flip_vertex_y: false,
        ..Options::default()
    };
    assert!(Options::default().flip_vertex_y, "the v0.4.0 convention flips");
    let plain = translate(&fixture!("panorama.frag.spv"), &unflipped).unwrap();
    let flipped = translate(&fixture!("panorama.frag.spv"), &Options::default()).unwrap();
    // Fragment stages do not change.
    assert_eq!(plain.wgsl, flipped.wgsl);
    let plain = translate(&fixture!("debug_points.vert.spv"), &unflipped).unwrap();
    let flipped = translate(&fixture!("debug_points.vert.spv"), &Options::default()).unwrap();
    assert!(!plain.wgsl.contains(".y = -"), "{}", plain.wgsl);
    assert!(flipped.wgsl.contains(".y = -"), "{}", flipped.wgsl);
}

#[test]
fn flat_layout_keeps_the_s4_bindings() {
    let options = Options {
        texel_buffers: vec![(4, TexelFormat::R8Sint)],
        ..flat()
    };
    let t = translate(&fixture!("clouds.vert.spv"), &options).unwrap();
    assert!(t.bindings.iter().all(|b| b.group == 0 && b.uniform == b.binding / 2));
    assert_eq!(binding(&t, 0, 8).kind, BindingKind::StorageBuffer);
    assert!(t.wgsl.contains("@group(0) @binding(8) "), "{}", t.wgsl);
    let t = translate(&fixture!("oit_transmittance_terrain.frag.spv"), &flat()).unwrap();
    assert!(matches!(binding(&t, 0, 11).kind, BindingKind::Sampler { .. }));
    assert_eq!(binding(&t, 0, 11).uniform, 5);
}

#[test]
fn split_layout_moves_only_the_group_and_binding() {
    // The same WGSL as the flat layout up to the @group/@binding attributes.
    let strip = |wgsl: &str| {
        wgsl.lines()
            .map(|l| if l.starts_with("@group(") { "@group" } else { l })
            .collect::<Vec<_>>()
            .join("\n")
    };
    for name in [
        "oit_transmittance_terrain.frag.spv",
        "panorama.frag.spv",
        "solid_terrain.vert.spv",
    ] {
        let bytes = fixture!(name);
        let split = translate(
            &bytes,
            &Options {
                flip_vertex_y: false,
                ..Options::default()
            },
        )
        .unwrap();
        let flat = translate(&bytes, &flat()).unwrap();
        assert_eq!(strip(&split.wgsl), strip(&flat.wgsl), "{name}");
        assert_ne!(split.wgsl, flat.wgsl, "{name}");
    }
}

#[test]
fn gl_depth_remap_halves_z_plus_w() {
    let remap = Options {
        gl_depth_remap: true,
        ..Options::default()
    };
    let plain = translate(&fixture!("solid_terrain.vert.spv"), &Options::default()).unwrap();
    let remapped = translate(&fixture!("solid_terrain.vert.spv"), &remap).unwrap();
    assert!(!plain.wgsl.contains("0.5f"), "{}", plain.wgsl);
    assert!(remapped.wgsl.contains("0.5f"), "{}", remapped.wgsl);
    // Fragment stages do not change.
    let plain = translate(&fixture!("panorama.frag.spv"), &Options::default()).unwrap();
    let remapped = translate(&fixture!("panorama.frag.spv"), &remap).unwrap();
    assert_eq!(plain.wgsl, remapped.wgsl);
}
