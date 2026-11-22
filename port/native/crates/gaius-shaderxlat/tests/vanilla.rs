//! Vanilla GLSL generations must retain their pre-gl_BaseVertex WGSL byte for byte.
//! Input SPIR-V is intentionally external, as in fixtures.rs. Dump both generations with
//! dump-corpus.sh --mc PROFILE; point GAIUS_SHADERXLAT_VANILLA_ROOT at the parent containing
//! 26.2-frontend/ and 1.21.11-frontend/. GAIUS_SHADERXLAT_REQUIRE_FIXTURES=1 makes
//! missing inputs fatal. Golden fingerprints were generated from before_base_vertex,
//! after checking that only the base-vertex pass changed in this pipeline.
use gaius_shaderxlat::{
    frontend::{self, Attribute, BaseType, Layout, Mode, Uniform},
    passes,
    spirv::{words_from_bytes, Module},
    translate, Options,
};
use std::path::PathBuf;

// FNV-1a pins every byte without adding a hashing dependency to the translator.
fn fingerprint(text: &str) -> u64 {
    text.bytes().fold(0xcbf29ce484222325, |hash, byte| {
        (hash ^ u64::from(byte)).wrapping_mul(0x100000001b3)
    })
}

// Reference translation pipeline before the base-vertex pass.
fn before_base_vertex(bytes: &[u8], options: &Options) -> String {
    let mut module = Module::parse(&words_from_bytes(bytes).unwrap()).unwrap();
    passes::strip::run(&mut module);
    passes::point_size::run(&mut module).unwrap();
    passes::texel_buffer::run(&mut module, &options.texel_buffers).unwrap();
    passes::split_sampler::run(&mut module).unwrap();
    passes::output_array::run(&mut module).unwrap();
    if options.gl_depth_remap {
        passes::depth_remap::run(&mut module).unwrap();
    }
    let mut naga = gaius_shaderxlat::parse(&module.emit(), options).unwrap();
    gaius_shaderxlat::assign_bindings(&mut naga, options.binding_layout).unwrap();
    let info = gaius_shaderxlat::validate(&naga).unwrap();
    gaius_shaderxlat::write_wgsl(&naga, &info).unwrap()
}

fn check(profile: &str, name: &str, hashes: [&str; 2], layout: Layout, goldens: [u64; 2]) {
    let root = std::env::var_os("GAIUS_SHADERXLAT_VANILLA_ROOT")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../target/shader-corpus"));
    let directory = root.join(format!("{profile}-frontend/spirv"));
    let mut inputs = Vec::new();
    for hash in hashes {
        let path = directory.join(format!("{hash}.spv"));
        match std::fs::read(&path) {
            Ok(bytes) => inputs.push(bytes),
            Err(error)
                if error.kind() == std::io::ErrorKind::NotFound
                    && std::env::var("GAIUS_SHADERXLAT_REQUIRE_FIXTURES").as_deref() != Ok("1") =>
            {
                eprintln!(
                    "skipping {profile}/{name}: {} missing; dump vanilla fixtures",
                    path.display()
                );
                return;
            }
            Err(error) => panic!("{}: {error}", path.display()),
        }
    }
    let mode = if profile == "26.2" { Mode::Strict } else { Mode::Lenient };
    let linked = frontend::link(&inputs[0], &inputs[1], &layout, mode).unwrap();
    let options = Options {
        texel_buffers: frontend::texel_buffers(&linked.entries).unwrap(),
        gl_depth_remap: profile == "1.21.11",
        ..Options::default()
    };
    for (stage, (bytes, golden)) in [linked.vertex, linked.fragment].iter().zip(goldens).enumerate() {
        let baseline = before_base_vertex(bytes, &options);
        let current = translate(bytes, &options).unwrap().wgsl;
        assert_eq!(current, baseline, "{profile}/{name} stage {stage} changed WGSL");
        assert_eq!(
            fingerprint(&baseline),
            golden,
            "{profile}/{name} stage {stage} baseline drifted"
        );
    }
}

#[test]
fn vanilla_26_2_mojang_logo() {
    check(
        "26.2",
        "mojang_logo",
        [
            "c5b7564b2bd5c9c9e5c82e03a7b546503164edd1789be1b7adf1e3bc8ed0eb12",
            "c86c65eefbf4de2aa812bc0b064d515ac604209b5599873e9f7d46a09d82b95b",
        ],
        Layout {
            uniforms: vec![
                Uniform {
                    name: "Globals".into(),
                    texel_format: None,
                },
                Uniform {
                    name: "DynamicTransforms".into(),
                    texel_format: None,
                },
                Uniform {
                    name: "Projection".into(),
                    texel_format: None,
                },
            ],
            samplers: vec!["Sampler0".into()],
            attributes: vec![
                Attribute {
                    name: "Position".into(),
                    base: BaseType::of_gpu_format("RGB32_FLOAT"),
                },
                Attribute {
                    name: "UV0".into(),
                    base: BaseType::of_gpu_format("RG32_FLOAT"),
                },
                Attribute {
                    name: "Color".into(),
                    base: BaseType::of_gpu_format("RGBA8_UNORM"),
                },
            ],
        },
        [0x8398068a7318b952, 0xa4fb9ae2a6d9ea0c],
    );
}

#[test]
fn vanilla_26_2_gui() {
    check(
        "26.2",
        "gui",
        [
            "9d8faa604b030c1bcfca9d54a924774971a4c1485e5bda091dcb20b9cce84b0b",
            "5f961cb42afc6a8ba52dcce3f8596793298bd5bf4a4cd9173eeabb5064df3085",
        ],
        Layout {
            uniforms: vec![
                Uniform {
                    name: "Globals".into(),
                    texel_format: None,
                },
                Uniform {
                    name: "DynamicTransforms".into(),
                    texel_format: None,
                },
                Uniform {
                    name: "Projection".into(),
                    texel_format: None,
                },
            ],
            samplers: vec![],
            attributes: vec![
                Attribute {
                    name: "Position".into(),
                    base: BaseType::of_gpu_format("RGB32_FLOAT"),
                },
                Attribute {
                    name: "Color".into(),
                    base: BaseType::of_gpu_format("RGBA8_UNORM"),
                },
            ],
        },
        [0x5b2dac4db5b1a2f3, 0xa5dce91a90888209],
    );
}

#[test]
fn vanilla_26_2_solid_terrain() {
    check(
        "26.2",
        "solid_terrain",
        [
            "5c1b9c96ead386f2b141204a3403e09efaca1f2d8688ccac1dada6e9283c79e1",
            "4a87112e56352c4ae7ad5193cbd81cdd0b352a4344a6f3665f12b219c6695ff8",
        ],
        Layout {
            uniforms: vec![
                Uniform {
                    name: "Globals".into(),
                    texel_format: None,
                },
                Uniform {
                    name: "Fog".into(),
                    texel_format: None,
                },
                Uniform {
                    name: "Projection".into(),
                    texel_format: None,
                },
                Uniform {
                    name: "ChunkSection".into(),
                    texel_format: None,
                },
            ],
            samplers: vec!["Sampler0".into(), "Sampler2".into()],
            attributes: vec![
                Attribute {
                    name: "Position".into(),
                    base: BaseType::of_gpu_format("RGB32_FLOAT"),
                },
                Attribute {
                    name: "Color".into(),
                    base: BaseType::of_gpu_format("RGBA8_UNORM"),
                },
                Attribute {
                    name: "UV0".into(),
                    base: BaseType::of_gpu_format("RG32_FLOAT"),
                },
                Attribute {
                    name: "UV2".into(),
                    base: BaseType::of_gpu_format("RG16_SINT"),
                },
            ],
        },
        [0x7f3f56355239b9b6, 0x89a83a99e17ecfb1],
    );
}

#[test]
fn vanilla_1_21_11_mojang_logo() {
    check(
        "1.21.11",
        "mojang_logo",
        [
            "c5b7564b2bd5c9c9e5c82e03a7b546503164edd1789be1b7adf1e3bc8ed0eb12",
            "c86c65eefbf4de2aa812bc0b064d515ac604209b5599873e9f7d46a09d82b95b",
        ],
        Layout {
            uniforms: vec![
                Uniform {
                    name: "DynamicTransforms".into(),
                    texel_format: None,
                },
                Uniform {
                    name: "Projection".into(),
                    texel_format: None,
                },
            ],
            samplers: vec!["Sampler0".into()],
            attributes: vec![
                Attribute {
                    name: "Position".into(),
                    base: BaseType::of_gpu_format("RGB32_FLOAT"),
                },
                Attribute {
                    name: "UV0".into(),
                    base: BaseType::of_gpu_format("RG32_FLOAT"),
                },
                Attribute {
                    name: "Color".into(),
                    base: BaseType::of_gpu_format("RGBA8_UNORM"),
                },
            ],
        },
        [0x70212fbf72513762, 0xa4fb9ae2a6d9ea0c],
    );
}

#[test]
fn vanilla_1_21_11_gui() {
    check(
        "1.21.11",
        "gui",
        [
            "9d8faa604b030c1bcfca9d54a924774971a4c1485e5bda091dcb20b9cce84b0b",
            "5f961cb42afc6a8ba52dcce3f8596793298bd5bf4a4cd9173eeabb5064df3085",
        ],
        Layout {
            uniforms: vec![
                Uniform {
                    name: "DynamicTransforms".into(),
                    texel_format: None,
                },
                Uniform {
                    name: "Projection".into(),
                    texel_format: None,
                },
            ],
            samplers: vec![],
            attributes: vec![
                Attribute {
                    name: "Position".into(),
                    base: BaseType::of_gpu_format("RGB32_FLOAT"),
                },
                Attribute {
                    name: "Color".into(),
                    base: BaseType::of_gpu_format("RGBA8_UNORM"),
                },
            ],
        },
        [0xac39a1da3c66c29f, 0xa5dce91a90888209],
    );
}

#[test]
fn vanilla_1_21_11_solid_terrain() {
    check(
        "1.21.11",
        "solid_terrain",
        [
            "84b08156d22b97529d1120d63f9230de57c2bff8b3dee03082ff47a1b255c13d",
            "d8e9b30f424dd96d85e7402d98a4ed9ce66d3ef6746a729449595600e25b33b9",
        ],
        Layout {
            uniforms: vec![
                Uniform {
                    name: "Fog".into(),
                    texel_format: None,
                },
                Uniform {
                    name: "Projection".into(),
                    texel_format: None,
                },
                Uniform {
                    name: "ChunkSection".into(),
                    texel_format: None,
                },
            ],
            samplers: vec!["Sampler0".into(), "Sampler2".into()],
            attributes: vec![
                Attribute {
                    name: "Position".into(),
                    base: BaseType::of_gpu_format("RGB32_FLOAT"),
                },
                Attribute {
                    name: "Color".into(),
                    base: BaseType::of_gpu_format("RGBA8_UNORM"),
                },
                Attribute {
                    name: "UV0".into(),
                    base: BaseType::of_gpu_format("RG32_FLOAT"),
                },
                Attribute {
                    name: "UV2".into(),
                    base: BaseType::of_gpu_format("RG16_SINT"),
                },
                Attribute {
                    name: "Normal".into(),
                    base: BaseType::of_gpu_format("RGBA8_SNORM"),
                },
            ],
        },
        [0x5eb164eea3060107, 0x21bf6f0e1aa29b9f],
    );
}
