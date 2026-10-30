//! The GLSL front-end (`frontend::link`) and the GL compatibility passes on the shaders of
//! `tests/frontend` (written for these tests and committed with their shaderc output, see its
//! README). The corpus test checks the strict link against every vanilla 26.2 pipeline
//! (`port/native/golden/shaders/run-corpus.sh --mc 26.2`); these tests pin the rules one by one.

use gaius_shaderxlat::frontend::{self, Attribute, BaseType, Entry, EntryKind, Fix, Layout, Mode, Uniform};
use gaius_shaderxlat::spirv::{decoration, op, words_from_bytes, Module};
use gaius_shaderxlat::{translate, BindingKind, Error, Options, Stage};

fn fixture(name: &str) -> Vec<u8> {
    let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("tests/frontend")
        .join(name);
    std::fs::read(&path).unwrap_or_else(|e| panic!("{}: {e}", path.display()))
}

fn module(bytes: &[u8]) -> Module {
    Module::parse(&words_from_bytes(bytes).unwrap()).unwrap()
}

/// The `OpVariable` named `name`.
fn var(module: &Module, name: &str) -> u32 {
    module
        .insts
        .iter()
        .filter(|i| i.op == op::VARIABLE)
        .map(|i| i.ops[1])
        .find(|&id| module.name(id).as_deref() == Some(name))
        .unwrap_or_else(|| panic!("no variable {name}"))
}

fn location(module: &Module, name: &str) -> u32 {
    module.decoration(var(module, name), decoration::LOCATION).unwrap()
}

fn binding(module: &Module, name: &str) -> u32 {
    module.decoration(var(module, name), decoration::BINDING).unwrap()
}

/// The binding of the uniform block whose type is named `block`.
fn block_binding(module: &Module, block: &str) -> u32 {
    let reflection = frontend::reflect(module).unwrap();
    let ubo = reflection.uniform_buffers.iter().find(|r| r.name == block).unwrap();
    module.decoration(ubo.var, decoration::BINDING).unwrap()
}

fn uniform(name: &str) -> Uniform {
    Uniform {
        name: name.into(),
        texel_format: None,
    }
}

fn attribute(name: &str, format: &str) -> Attribute {
    Attribute {
        name: name.into(),
        base: BaseType::of_gpu_format(format),
    }
}

fn link_layout() -> Layout {
    Layout {
        uniforms: vec![
            uniform("Transform"),
            uniform("Globals"),
            uniform("Fog"),
            Uniform {
                name: "Offsets".into(),
                texel_format: Some("R8_SINT".into()),
            },
        ],
        samplers: vec!["Sampler0".into(), "Sky".into()],
        attributes: vec![
            attribute("Position", "RGB32_FLOAT"),
            attribute("Color", "RGBA8_UNORM"),
            attribute("UV0", "RG32_FLOAT"),
        ],
    }
}

fn legacy_layout() -> Layout {
    Layout {
        uniforms: vec![uniform("Projection")],
        samplers: Vec::new(),
        attributes: vec![attribute("Position", "RGB32_FLOAT"), attribute("UV1", "RG16_SINT")],
    }
}

fn entry(name: &str, kind: EntryKind, declared: bool) -> Entry {
    Entry {
        name: name.into(),
        kind,
        declared,
    }
}

/// Every word of `a` and `b` outside `OpDecorate ... Binding|Location <literal>` is the same.
fn same_but_bindings_and_locations(a: &[u8], b: &[u8]) {
    let (a, b) = (module(a), module(b));
    assert_eq!(a.header, b.header);
    assert_eq!(a.insts.len(), b.insts.len());
    for (x, y) in a.insts.iter().zip(&b.insts) {
        let literal = x.op == op::DECORATE && matches!(x.ops[1], decoration::BINDING | decoration::LOCATION);
        if literal {
            assert_eq!(x.ops[..2], y.ops[..2]);
        } else {
            assert_eq!(x, y);
        }
    }
}

#[test]
fn reflection_follows_spirv_cross() {
    let vs = module(&fixture("link.vert.spv"));
    let r = frontend::reflect(&vs).unwrap();
    let names = |list: &[frontend::Resource]| list.iter().map(|r| r.name.clone()).collect::<Vec<_>>();
    // Blocks by their type name, built-ins (gl_PerVertex, gl_VertexIndex) left out.
    let mut ubos = names(&r.uniform_buffers);
    ubos.sort();
    assert_eq!(ubos, ["Globals", "Transform"]);
    assert_eq!(names(&r.samplers), ["Offsets"]);
    assert_eq!(r.samplers[0].dim, 5, "texel buffers are SpvDimBuffer");
    let mut inputs = names(&r.inputs);
    inputs.sort();
    assert_eq!(inputs, ["Color", "Position", "UV0"]);
    assert_eq!(r.outputs.len(), 3);
    let fs = module(&fixture("link.frag.spv"));
    let r = frontend::reflect(&fs).unwrap();
    let dims: Vec<(String, u32)> = r.samplers.iter().map(|s| (s.name.clone(), s.dim)).collect();
    assert!(
        dims.contains(&("Sampler0".into(), 1)) && dims.contains(&("Sky".into(), 3)),
        "{dims:?}"
    );
}

#[test]
fn strict_link_numbers_bindings_and_locations() {
    let (vs_in, fs_in) = (fixture("link.vert.spv"), fixture("link.frag.spv"));
    let linked = frontend::link(&vs_in, &fs_in, &link_layout(), Mode::Strict).unwrap();
    assert!(linked.fixes.is_empty());
    // addToBindGroup: the vertex stage's blocks, then its samplers, then what the fragment stage
    // adds, each name once (Globals is shared).
    let vertex_ubos: Vec<&str> = linked.entries[..2].iter().map(|e| e.name.as_str()).collect();
    assert!(
        vertex_ubos.contains(&"Globals") && vertex_ubos.contains(&"Transform"),
        "{vertex_ubos:?}"
    );
    assert_eq!(
        linked.entries[2..],
        [
            entry(
                "Offsets",
                EntryKind::TexelBuffer {
                    format: Some("R8_SINT".into())
                },
                true
            ),
            entry("Fog", EntryKind::UniformBuffer, true),
            entry("Sampler0", EntryKind::SampledImage, true),
            entry("Sky", EntryKind::SampledImage, true),
        ]
    );
    assert!(linked.entries.iter().all(|e| e.declared));
    let index = |name: &str| linked.entries.iter().position(|e| e.name == name).unwrap() as u32;

    let vs = module(&linked.vertex);
    let fs = module(&linked.fragment);
    // Binding = entry index, in both stages.
    for (module, blocks) in [(&vs, &["Globals", "Transform"][..]), (&fs, &["Globals", "Fog"][..])] {
        for block in blocks {
            assert_eq!(block_binding(module, block), index(block), "{block}");
        }
    }
    assert_eq!(binding(&vs, "Offsets"), index("Offsets"));
    assert_eq!(binding(&fs, "Sampler0"), index("Sampler0"));
    assert_eq!(binding(&fs, "Sky"), index("Sky"));
    // Vertex inputs follow the vertex format, whatever the declaration order.
    assert_eq!(
        [location(&vs, "Position"), location(&vs, "Color"), location(&vs, "UV0")],
        [0, 1, 2]
    );
    // Outputs are numbered in reflection order; a fragment input takes its index among the vertex
    // outputs the fragment stage declares (vertexColor, texCoord; fogDistance is not read).
    let outputs: Vec<String> = frontend::reflect(&vs)
        .unwrap()
        .outputs
        .into_iter()
        .map(|r| r.name)
        .collect();
    for (i, name) in outputs.iter().enumerate() {
        assert_eq!(location(&vs, name), i as u32, "{name}");
    }
    let declared: Vec<&String> = outputs.iter().filter(|n| *n != "fogDistance").collect();
    for (i, name) in declared.iter().enumerate() {
        assert_eq!(location(&fs, name), i as u32, "{name}");
    }
    let fragment_outputs: Vec<String> = frontend::reflect(&fs)
        .unwrap()
        .outputs
        .into_iter()
        .map(|r| r.name)
        .collect();
    for (i, name) in fragment_outputs.iter().enumerate() {
        assert_eq!(location(&fs, name), i as u32, "{name}");
    }
    // Nothing else changes: the vanilla rebind writes those literals only.
    same_but_bindings_and_locations(&vs_in, &linked.vertex);
    same_but_bindings_and_locations(&fs_in, &linked.fragment);
}

#[test]
fn linked_modules_translate() {
    let linked = frontend::link(
        &fixture("link.vert.spv"),
        &fixture("link.frag.spv"),
        &link_layout(),
        Mode::Strict,
    )
    .unwrap();
    let options = Options {
        texel_buffers: frontend::texel_buffers(&linked.entries).unwrap(),
        ..Options::default()
    };
    let offsets = linked.entries.iter().position(|e| e.name == "Offsets").unwrap() as u32;
    assert_eq!(
        options.texel_buffers,
        [(offsets, gaius_shaderxlat::TexelFormat::R8Sint)]
    );
    let vs = translate(&linked.vertex, &options).unwrap();
    let fs = translate(&linked.fragment, &options).unwrap();
    assert_eq!((vs.stage, fs.stage), (Stage::Vertex, Stage::Fragment));
    // Split layout: uniform buffers in group 0 at their entry index, everything else in group 1.
    for b in vs.bindings.iter().chain(&fs.bindings) {
        match b.kind {
            BindingKind::UniformBuffer { .. } => assert_eq!((b.group, b.binding), (0, b.uniform)),
            BindingKind::Sampler { .. } => assert_eq!((b.group, b.binding), (1, 2 * b.uniform + 1)),
            _ => assert_eq!((b.group, b.binding), (1, 2 * b.uniform)),
        }
    }
    assert!(vs
        .bindings
        .iter()
        .any(|b| b.kind == BindingKind::StorageBuffer && b.uniform == offsets));
}

#[test]
fn strict_link_refuses_what_vanilla_refuses() {
    let (vs, fs) = (fixture("link.vert.spv"), fixture("link.frag.spv"));
    let mut layout = link_layout();
    layout.uniforms.retain(|u| u.name != "Fog");
    assert_eq!(
        frontend::link(&vs, &fs, &layout, Mode::Strict).unwrap_err(),
        Error::Link("Unable to find shader defined uniform (Fog)".into())
    );
    let mut layout = link_layout();
    layout.samplers.retain(|s| s != "Sky");
    assert_eq!(
        frontend::link(&vs, &fs, &layout, Mode::Strict).unwrap_err(),
        Error::Link("Unable to find shader defined uniform (Sky)".into())
    );
    let mut layout = link_layout();
    layout.attributes.retain(|a| a.name != "Color");
    assert_eq!(
        frontend::link(&vs, &fs, &layout, Mode::Strict).unwrap_err(),
        Error::Link("Shader expects input variables which are not being provided: [Color]".into())
    );
    // A texel buffer the pipeline declares as a sampler.
    let mut layout = link_layout();
    layout.uniforms.retain(|u| u.name != "Offsets");
    layout.samplers.push("Offsets".into());
    assert_eq!(
        frontend::link(&vs, &fs, &layout, Mode::Strict).unwrap_err(),
        Error::Link("Sampled texture (Offsets) must have type of SpvDim2D or SpvDimCube".into())
    );
    // Stages swapped.
    assert!(matches!(
        frontend::link(&fs, &vs, &link_layout(), Mode::Strict),
        Err(Error::Link(_))
    ));
}

#[test]
fn lenient_link_applies_the_gl_rules() {
    let (vs_in, fs_in) = (fixture("legacy.vert.spv"), fixture("legacy.frag.spv"));
    assert_eq!(
        frontend::link(&vs_in, &fs_in, &legacy_layout(), Mode::Strict).unwrap_err(),
        Error::Link("Unable to find shader defined uniform (Globals)".into())
    );
    let linked = frontend::link(&vs_in, &fs_in, &legacy_layout(), Mode::Lenient).unwrap();
    assert_eq!(
        linked.fixes,
        [
            Fix::DroppedFragmentInput("texCoord0".into()),
            Fix::AttributeBaseType {
                name: "UV1".into(),
                base: BaseType::Sint
            },
            Fix::UndeclaredUniformBuffer("Globals".into()),
            Fix::UndeclaredSampler("Sampler1".into()),
        ]
    );
    // Undeclared resources come after the declared ones of their stage, marked as such.
    assert_eq!(
        linked.entries,
        [
            entry("Projection", EntryKind::UniformBuffer, true),
            entry("Globals", EntryKind::UniformBuffer, false),
            entry("Sampler1", EntryKind::SampledImage, false),
        ]
    );
    let fs = module(&linked.fragment);
    assert!(frontend::reflect(&fs)
        .unwrap()
        .inputs
        .iter()
        .all(|r| r.name != "texCoord0"));
    assert!(
        fs.name(var(&module(&fs_in), "texCoord0")).is_none(),
        "the declaration is gone"
    );
    assert_eq!(binding(&fs, "Sampler1"), 2);

    // The translated stages agree with each other and validate.
    let vs = translate(&linked.vertex, &Options::default()).unwrap();
    let fs = translate(&linked.fragment, &Options::default()).unwrap();
    // UV1 arrives as an integer vector and is converted for the float arithmetic.
    assert!(vs.wgsl.contains("vec2<i32>"), "{}", vs.wgsl);
    assert!(vs.wgsl.contains("vec2<f32>("), "{}", vs.wgsl);
    assert!(!fs.wgsl.contains("texCoord0"), "{}", fs.wgsl);
}

#[test]
fn attribute_retype_converts_every_load() {
    let mut vs = module(&fixture("legacy.vert.spv"));
    let uv1 = var(&vs, "UV1");
    assert!(gaius_shaderxlat::passes::attribute_type::run(&mut vs, uv1, BaseType::Sint).unwrap());
    let loads = vs
        .insts
        .iter()
        .filter(|i| i.op == op::LOAD)
        .filter(|i| {
            i.ops[2] == uv1
                || vs
                    .insts
                    .iter()
                    .any(|a| a.op == op::ACCESS_CHAIN && a.ops[1] == i.ops[2] && a.ops[2] == uv1)
        })
        .count();
    let conversions = vs.insts.iter().filter(|i| i.op == op::CONVERT_S_TO_F).count();
    assert!(loads >= 2, "the fixture reads UV1 whole and by component");
    assert_eq!(loads, conversions);
    // The same attribute as float, or already integer, is left alone.
    let mut again = vs.clone();
    assert!(!gaius_shaderxlat::passes::attribute_type::run(&mut again, uv1, BaseType::Sint).unwrap());
    let mut float = module(&fixture("legacy.vert.spv"));
    let uv1 = var(&float, "UV1");
    assert!(!gaius_shaderxlat::passes::attribute_type::run(&mut float, uv1, BaseType::Float).unwrap());
}

#[test]
fn lenient_link_keeps_inputs_that_are_read() {
    let error = frontend::link(
        &fixture("legacy.vert.spv"),
        &fixture("reads_missing.frag.spv"),
        &legacy_layout(),
        Mode::Lenient,
    )
    .unwrap_err();
    assert_eq!(
        error,
        Error::Link("Shader expects input variables which are not being provided: [missing]".into())
    );
}

#[test]
fn strict_link_of_a_conforming_pipeline_matches_lenient() {
    let (vs, fs) = (fixture("link.vert.spv"), fixture("link.frag.spv"));
    let strict = frontend::link(&vs, &fs, &link_layout(), Mode::Strict).unwrap();
    let lenient = frontend::link(&vs, &fs, &link_layout(), Mode::Lenient).unwrap();
    assert_eq!(strict.vertex, lenient.vertex);
    assert_eq!(strict.fragment, lenient.fragment);
    assert_eq!(strict.entries, lenient.entries);
}

#[test]
fn gl_depth_remap_rewrites_the_vertex_position() {
    let linked = frontend::link(
        &fixture("legacy.vert.spv"),
        &fixture("legacy.frag.spv"),
        &legacy_layout(),
        Mode::Lenient,
    )
    .unwrap();
    let remap = Options {
        gl_depth_remap: true,
        ..Options::default()
    };
    let plain = translate(&linked.vertex, &Options::default()).unwrap();
    let remapped = translate(&linked.vertex, &remap).unwrap();
    assert!(!plain.wgsl.contains("0.5f"), "{}", plain.wgsl);
    assert!(remapped.wgsl.contains("0.5f"), "{}", remapped.wgsl);
    // z = (z + w) * 0.5 before the return: once per return of main.
    let words = gaius_shaderxlat::patch_spirv(&linked.vertex, &remap).unwrap();
    let patched = Module::parse(&words).unwrap();
    let returns = patched.insts.iter().filter(|i| i.op == op::RETURN).count();
    let muls = patched.insts.iter().filter(|i| i.op == op::F_MUL).count();
    let plain_words = gaius_shaderxlat::patch_spirv(&linked.vertex, &Options::default()).unwrap();
    let plain_muls = Module::parse(&plain_words)
        .unwrap()
        .insts
        .iter()
        .filter(|i| i.op == op::F_MUL)
        .count();
    assert!(returns >= 1);
    assert_eq!(muls, plain_muls + 1, "one return in main");
    // Fragment stages have no position.
    let fs_plain = translate(&linked.fragment, &Options::default()).unwrap();
    let fs_remapped = translate(&linked.fragment, &remap).unwrap();
    assert_eq!(fs_plain.wgsl, fs_remapped.wgsl);
}

#[test]
fn base_types_of_vertex_formats() {
    for (format, base) in [
        ("RG16_SINT", BaseType::Sint),
        ("RGBA8_UINT", BaseType::Uint),
        ("R32_UINT", BaseType::Uint),
        ("RGBA8_UNORM", BaseType::Float),
        ("RGBA8_SNORM", BaseType::Float),
        ("RGB32_FLOAT", BaseType::Float),
    ] {
        assert_eq!(BaseType::of_gpu_format(format), base, "{format}");
        assert_eq!(BaseType::from_code(base.code()), Some(base));
    }
}
