//! The blaze3d GLSL front-end of the 26.2 and 1.21.11 WebGPU backends.
//!
//! renderpearl (26.3) hands its backend bound SPIR-V. The blaze3d backends of 26.2
//! (`GpuDeviceBackend.precompilePipeline`) and 1.21.11 (`GpuDevice.precompilePipeline`) receive
//! GLSL, so the browser runs the vanilla 26.2 Vulkan front half itself. The Java side does what
//! comes before shaderc (`ShaderSource`, `GlslPreprocessor.injectDefines` with the pipeline
//! defines, then with `gl_VertexID -> gl_VertexIndex` and `gl_InstanceID -> gl_InstanceIndex`)
//! and compiles each stage with shaderc exactly as `GlslCompiler` configures it (Vulkan 1.2, auto
//! bind uniforms, auto map locations, debug info, `-O0`). [`link`] then does the rest of
//! `GlslCompiler.compile` on the two modules:
//!
//! 1. the reflection of `IntermediaryShaderModule.createFromSpirv`, with SPIRV-Cross's rules
//!    ([`reflect`]): uniform buffers, combined image samplers, stage outputs and stage inputs,
//!    each in declaration order; every stage output is renumbered to its index in that list;
//! 2. `GlslCompiler.addToBindGroup`, vertex stage then fragment stage: the bind group entries
//!    (uniform buffers, texel buffers and sampled images, each name once);
//! 3. `IntermediaryShaderModule.rebind`: every resource's binding becomes its entry index, a
//!    vertex input's location its index among the pipeline's vertex attributes that the shader
//!    declares, a fragment input's location its index among the vertex outputs that the fragment
//!    stage declares.
//!
//! Only literal words of `OpDecorate Binding` and `OpDecorate Location` change, so
//! [`Mode::Strict`] output is byte for byte the SPIR-V the vanilla 26.2 Vulkan backend passes to
//! `vkCreateShaderModule`; the corpus test (`port/native/golden/shaders/run-corpus.sh --mc 26.2`)
//! compares every 26.2 pipeline. The entries are the pipeline's uniform list: entry `i` is
//! vanilla uniform `i` for [`crate::translate`] and for the WebGPU bind group layout.
//!
//! [`Mode::Lenient`] adds the GL behaviour 1.21.11 shaders rely on (a GL driver links them, the
//! vanilla 26.2 rules refuse 27 of its 113 pipelines):
//! - a uniform block or sampler the pipeline does not declare still gets an entry, after the
//!   declared ones of its stage ([`Entry::declared`] is false: GL binds the built-in blocks
//!   `Projection`, `Lighting`, `Fog`, `Globals` by name and an undeclared sampler reads unit 0);
//! - a fragment input that no vertex output provides is removed when the shader never reads it
//!   ([`passes::dead_input`](crate::passes::dead_input));
//! - a vertex input declared as a float vector for an integer attribute (GL's
//!   `glVertexAttribIFormat` UVs) is read as the integer type and converted
//!   ([`passes::attribute_type`](crate::passes::attribute_type)).
//!
//! GLSL that shaderc cannot compile at all needs its fix before shaderc, on the Java side: 1.21.11
//! uses `sampler` (a type name in `GL_KHR_vulkan_glsl`) as an identifier, which the front-end
//! renames to `gaius_sampler`.

use crate::passes;
use crate::spirv::{self, decoration, op, storage, Module};
use crate::Error;
use std::collections::{BTreeSet, HashMap, HashSet};

/// Which rules [`link`] applies.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Mode {
    /// The vanilla 26.2 Vulkan front-end (26.2 pipelines).
    Strict,
    /// The vanilla rules plus the GL behaviour of 1.21.11 shaders (see the module docs).
    Lenient,
}

/// Base type of a vertex attribute as the vertex fetch delivers it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum BaseType {
    Float,
    Sint,
    Uint,
}

impl BaseType {
    /// Base type of a blaze3d `GpuFormat` name used as a vertex format: `*_SINT`, `*_UINT`,
    /// everything else (`*_FLOAT`, `*_UNORM`, `*_SNORM`) reaches the shader as float.
    pub fn of_gpu_format(name: &str) -> BaseType {
        if name.ends_with("_SINT") {
            BaseType::Sint
        } else if name.ends_with("_UINT") {
            BaseType::Uint
        } else {
            BaseType::Float
        }
    }

    pub fn code(self) -> u32 {
        self as u32
    }

    pub fn from_code(code: u32) -> Option<BaseType> {
        [BaseType::Float, BaseType::Sint, BaseType::Uint]
            .get(code as usize)
            .copied()
    }
}

/// One entry of `BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts())`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Uniform {
    pub name: String,
    /// `UniformDescription.gpuFormat()` (texel buffers; `None` for uniform buffers).
    pub texel_format: Option<String>,
}

/// One element of the pipeline's vertex formats (`getVertexFormatBindings()`, every element of
/// every binding in slot order).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Attribute {
    pub name: String,
    pub base: BaseType,
}

/// What the pipeline declares.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Layout {
    pub uniforms: Vec<Uniform>,
    /// `BindGroupLayout.flattenSamplers(pipeline.getBindGroupLayouts())`.
    pub samplers: Vec<String>,
    pub attributes: Vec<Attribute>,
}

/// `VulkanBindGroupLayout.VulkanBindGroupEntryType` plus the texel format.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum EntryKind {
    UniformBuffer,
    TexelBuffer { format: Option<String> },
    SampledImage,
}

/// One bind group entry; its index in [`Linked::entries`] is its binding.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Entry {
    pub name: String,
    pub kind: EntryKind,
    /// False for a resource the pipeline does not declare ([`Mode::Lenient`] only).
    pub declared: bool,
}

/// A GL-compatibility correction [`Mode::Lenient`] applied.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Fix {
    UndeclaredUniformBuffer(String),
    UndeclaredSampler(String),
    DroppedFragmentInput(String),
    /// The vertex input now reads `base` and converts to the declared type.
    AttributeBaseType {
        name: String,
        base: BaseType,
    },
}

impl Fix {
    /// Short form for logs and the corpus (`kind:name`).
    pub fn describe(&self) -> String {
        match self {
            Fix::UndeclaredUniformBuffer(name) => format!("undeclared-uniform-buffer:{name}"),
            Fix::UndeclaredSampler(name) => format!("undeclared-sampler:{name}"),
            Fix::DroppedFragmentInput(name) => format!("drop-unused-fragment-input:{name}"),
            Fix::AttributeBaseType { name, base } => format!("attribute-base-type:{name}:{base:?}"),
        }
    }
}

#[derive(Clone, Debug)]
pub struct Linked {
    pub vertex: Vec<u8>,
    pub fragment: Vec<u8>,
    pub entries: Vec<Entry>,
    pub fixes: Vec<Fix>,
}

/// One reflected resource or interface variable.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Resource {
    pub name: String,
    /// The `OpVariable` result id.
    pub var: u32,
    /// Instruction index of the `OpDecorate` (Binding or Location) whose literal `rebind` writes.
    decoration: usize,
    /// `OpTypeImage` dimensionality of a combined image sampler (0 otherwise).
    pub dim: u32,
}

/// What `IntermediaryShaderModule.createFromSpirv` reflects (SPIRV-Cross
/// `get_shader_resources`): each list in declaration order of the global variables.
#[derive(Clone, Debug, Default)]
pub struct Reflection {
    pub uniform_buffers: Vec<Resource>,
    pub samplers: Vec<Resource>,
    pub outputs: Vec<Resource>,
    pub inputs: Vec<Resource>,
}

/// Reflects one module with SPIRV-Cross's rules: only variables of the entry point's interface
/// (SPIR-V 1.4+; before, the rule applies to inputs and outputs only), built-in variables and
/// blocks left out; a uniform block is named by its block type, everything else by its variable.
pub fn reflect(module: &Module) -> Result<Reflection, Error> {
    let mut names: HashMap<u32, String> = HashMap::new();
    // Last OpDecorate per (target, decoration), as SPIRV-Cross records the word offset.
    let mut decorations: HashMap<(u32, u32), usize> = HashMap::new();
    let mut builtin_members: HashSet<u32> = HashSet::new();
    let mut defs: HashMap<u32, usize> = HashMap::new();
    for (index, inst) in module.insts.iter().enumerate() {
        match inst.op {
            op::NAME => {
                names.insert(inst.ops[0], spirv::decode_string(&inst.ops[1..]));
            }
            op::DECORATE if inst.ops.len() >= 2 => {
                decorations.insert((inst.ops[0], inst.ops[1]), index);
            }
            op::MEMBER_DECORATE if inst.ops.get(2) == Some(&decoration::BUILT_IN) => {
                builtin_members.insert(inst.ops[0]);
            }
            _ => {}
        }
        if let Some(id) = inst.result_id() {
            defs.entry(id).or_insert(index);
        }
    }
    let def = |id: u32| defs.get(&id).map(|&i| &module.insts[i]);
    // SPIRV-Cross copies an array's element type (and its `self`) into the array type.
    let unwrap_arrays = |mut id: u32| {
        while let Some(inst) = def(id).filter(|d| matches!(d.op, op::TYPE_ARRAY | op::TYPE_RUNTIME_ARRAY)) {
            id = inst.ops[1];
        }
        id
    };
    let interface: HashSet<u32> = module.interface().into_iter().collect();
    let all_in_interface = module.header[1] >= 0x0001_0400;
    let mut out = Reflection::default();
    for inst in module.insts[..module.globals_end()]
        .iter()
        .filter(|i| i.op == op::VARIABLE)
    {
        let (ty, var, class) = (inst.ops[0], inst.ops[1], inst.ops[2]);
        let Some(pointer) = def(ty).filter(|d| d.op == op::TYPE_POINTER) else {
            continue;
        };
        let pointee = unwrap_arrays(pointer.ops[2]);
        let io = matches!(class, storage::INPUT | storage::OUTPUT);
        if (all_in_interface || io) && !interface.contains(&var) {
            continue;
        }
        let builtin = decorations.contains_key(&(var, decoration::BUILT_IN)) || builtin_members.contains(&pointee);
        let block = decorations.contains_key(&(pointee, decoration::BLOCK));
        let var_name = names.get(&var).cloned().unwrap_or_default();
        let located = |what: u32, name: String| -> Result<Resource, Error> {
            let decoration = *decorations.get(&(var, what)).ok_or_else(|| {
                Error::Link(format!(
                    "{name} has no {} decoration",
                    if what == decoration::BINDING {
                        "Binding"
                    } else {
                        "Location"
                    }
                ))
            })?;
            Ok(Resource {
                name,
                var,
                decoration,
                dim: 0,
            })
        };
        match class {
            storage::INPUT | storage::OUTPUT if !builtin => {
                let name = if block {
                    names.get(&pointee).cloned().unwrap_or_default()
                } else {
                    var_name
                };
                let resource = located(decoration::LOCATION, name)?;
                if class == storage::INPUT {
                    out.inputs.push(resource);
                } else {
                    out.outputs.push(resource);
                }
            }
            storage::UNIFORM if block => {
                // get_remapped_declared_block_name: the block type's name, else the fallback.
                let name = match names.get(&pointee).filter(|n| !n.is_empty()) {
                    Some(name) => name.clone(),
                    None if !var_name.is_empty() => var_name,
                    None => format!("_{pointee}_{var}"),
                };
                out.uniform_buffers.push(located(decoration::BINDING, name)?);
            }
            storage::UNIFORM_CONSTANT => {
                if let Some(sampled) = def(pointee).filter(|d| d.op == op::TYPE_SAMPLED_IMAGE) {
                    let dim = def(sampled.ops[1])
                        .filter(|d| d.op == op::TYPE_IMAGE)
                        .map(|d| d.ops[2])
                        .ok_or_else(|| Error::Link(format!("sampler {var_name} without an image type")))?;
                    let mut resource = located(decoration::BINDING, var_name)?;
                    resource.dim = dim;
                    out.samplers.push(resource);
                }
            }
            _ => {}
        }
    }
    Ok(out)
}

fn set_literal(module: &mut Module, decoration: usize, value: u32) {
    module.insts[decoration].ops[2] = value;
}

/// `SpvcUtil.imageDimensionToString`.
fn dim_name(dim: u32) -> &'static str {
    match dim {
        0 => "SpvDim1D",
        1 => "SpvDim2D",
        2 => "SpvDim3D",
        3 => "SpvDimCube",
        4 => "SpvDimRect",
        5 => "SpvDimBuffer",
        6 => "SpvDimSubpassData",
        _ => "unknown",
    }
}

fn find_entry(entries: &[Entry], name: &str, kind: fn(&EntryKind) -> bool) -> bool {
    entries.iter().any(|e| kind(&e.kind) && e.name == name)
}

/// `GlslCompiler.addToBindGroup` for one stage ([`Mode::Lenient`]: undeclared resources are
/// appended instead of refused).
fn add_to_bind_group(
    entries: &mut Vec<Entry>,
    stage: &Reflection,
    layout: &Layout,
    mode: Mode,
    fixes: &mut Vec<Fix>,
) -> Result<(), Error> {
    let lenient = mode == Mode::Lenient;
    for ubo in &stage.uniform_buffers {
        let declared = layout.uniforms.iter().any(|u| u.name == ubo.name);
        if !declared {
            if !lenient {
                return Err(Error::Link(format!(
                    "Unable to find shader defined uniform ({})",
                    ubo.name
                )));
            }
            let fix = Fix::UndeclaredUniformBuffer(ubo.name.clone());
            if !fixes.contains(&fix) {
                fixes.push(fix);
            }
        }
        if !find_entry(entries, &ubo.name, |k| *k == EntryKind::UniformBuffer) {
            entries.push(Entry {
                name: ubo.name.clone(),
                kind: EntryKind::UniformBuffer,
                declared,
            });
        }
    }
    for sampler in &stage.samplers {
        if let Some(texel) = layout.uniforms.iter().find(|u| u.name == sampler.name) {
            if sampler.dim != spirv::DIM_BUFFER {
                return Err(Error::Link(format!(
                    "UTB ({}) must have type of SpvDimBuffer",
                    sampler.name
                )));
            }
            if !find_entry(entries, &sampler.name, |k| matches!(k, EntryKind::TexelBuffer { .. })) {
                entries.push(Entry {
                    name: sampler.name.clone(),
                    kind: EntryKind::TexelBuffer {
                        format: texel.texel_format.clone(),
                    },
                    declared: true,
                });
            }
            continue;
        }
        let declared = layout.samplers.contains(&sampler.name);
        if !declared {
            if !lenient {
                return Err(Error::Link(format!(
                    "Unable to find shader defined uniform ({})",
                    sampler.name
                )));
            }
            let fix = Fix::UndeclaredSampler(sampler.name.clone());
            if !fixes.contains(&fix) {
                fixes.push(fix);
            }
        }
        if sampler.dim != spirv::DIM_2D && sampler.dim != spirv::DIM_CUBE {
            return Err(Error::Link(format!(
                "Sampled texture ({}) must have type of SpvDim2D or SpvDimCube",
                sampler.name
            )));
        }
        if !find_entry(entries, &sampler.name, |k| *k == EntryKind::SampledImage) {
            entries.push(Entry {
                name: sampler.name.clone(),
                kind: EntryKind::SampledImage,
                declared,
            });
        }
    }
    Ok(())
}

fn set_text(names: &BTreeSet<&str>) -> String {
    format!("[{}]", names.iter().copied().collect::<Vec<_>>().join(", "))
}

/// `IntermediaryShaderModule.rebind(inputVariables, entries)`.
fn rebind(module: &mut Module, stage: &Reflection, input_names: &[String], entries: &[Entry]) -> Result<(), Error> {
    let mut remaining_inputs: BTreeSet<&str> = stage.inputs.iter().map(|r| r.name.as_str()).collect();
    let mut remaining_ubos: BTreeSet<&str> = stage.uniform_buffers.iter().map(|r| r.name.as_str()).collect();
    let mut remaining_samplers: BTreeSet<&str> = stage.samplers.iter().map(|r| r.name.as_str()).collect();
    let mut previous: Option<&str> = None;
    let mut location = 0;
    for name in input_names {
        if let Some(input) = stage.inputs.iter().find(|r| &r.name == name) {
            if previous != Some(name.as_str()) {
                set_literal(module, input.decoration, location);
                remaining_inputs.remove(name.as_str());
            }
            location += 1;
            previous = Some(name.as_str());
        }
    }
    for (index, entry) in entries.iter().enumerate() {
        let binding = index as u32;
        match entry.kind {
            EntryKind::UniformBuffer => {
                if let Some(ubo) = stage.uniform_buffers.iter().find(|r| r.name == entry.name) {
                    set_literal(module, ubo.decoration, binding);
                    remaining_ubos.remove(entry.name.as_str());
                }
            }
            EntryKind::SampledImage => {
                if let Some(sampler) = stage.samplers.iter().find(|r| r.name == entry.name) {
                    if sampler.dim != spirv::DIM_2D && sampler.dim != spirv::DIM_CUBE {
                        return Err(Error::Link(format!(
                            "Unsupported texture dimensions '{}' for sampler {}",
                            dim_name(sampler.dim),
                            entry.name
                        )));
                    }
                    set_literal(module, sampler.decoration, binding);
                    remaining_samplers.remove(entry.name.as_str());
                }
            }
            EntryKind::TexelBuffer { .. } => {
                if let Some(sampler) = stage.samplers.iter().find(|r| r.name == entry.name) {
                    if sampler.dim != spirv::DIM_BUFFER {
                        return Err(Error::Link(format!(
                            "Unsupported texel buffer dimensions '{}' for sampler {}",
                            dim_name(sampler.dim),
                            entry.name
                        )));
                    }
                    set_literal(module, sampler.decoration, binding);
                    remaining_samplers.remove(entry.name.as_str());
                }
            }
        }
    }
    if !remaining_inputs.is_empty() {
        return Err(Error::Link(format!(
            "Shader expects input variables which are not being provided: {}",
            set_text(&remaining_inputs)
        )));
    }
    if !remaining_ubos.is_empty() {
        return Err(Error::Link(format!(
            "Shader expects uniform buffers which are not being provided: {}",
            set_text(&remaining_ubos)
        )));
    }
    if !remaining_samplers.is_empty() {
        return Err(Error::Link(format!(
            "Shader expects samplers which are not being provided: {}",
            set_text(&remaining_samplers)
        )));
    }
    Ok(())
}

/// Parses a stage and checks its execution model (0 vertex, 4 fragment).
fn stage_module(bytes: &[u8], model: u32, what: &str) -> Result<Module, Error> {
    let module = Module::parse(&spirv::words_from_bytes(bytes)?)?;
    let (found, _) = module.entry_point()?;
    if found != model {
        return Err(Error::Link(format!("the {what} module has execution model {found}")));
    }
    Ok(module)
}

fn to_bytes(words: Vec<u32>) -> Vec<u8> {
    words.iter().flat_map(|w| w.to_le_bytes()).collect()
}

/// Links a pipeline's vertex and fragment shaderc output (see the module docs).
pub fn link(vertex: &[u8], fragment: &[u8], layout: &Layout, mode: Mode) -> Result<Linked, Error> {
    let mut vs = stage_module(vertex, 0, "vertex")?;
    let mut fs = stage_module(fragment, 4, "fragment")?;
    let mut fixes = Vec::new();

    // createFromSpirv: every stage output at its index.
    for module in [&mut vs, &mut fs] {
        let reflection = reflect(module)?;
        for (index, output) in reflection.outputs.iter().enumerate() {
            set_literal(module, output.decoration, index as u32);
        }
    }
    let vertex_outputs: Vec<String> = reflect(&vs)?.outputs.into_iter().map(|r| r.name).collect();

    if mode == Mode::Lenient {
        for input in reflect(&fs)?.inputs {
            if !vertex_outputs.contains(&input.name) && passes::dead_input::run(&mut fs, input.var)? {
                fixes.push(Fix::DroppedFragmentInput(input.name));
            }
        }
        for input in reflect(&vs)?.inputs {
            if let Some(attribute) = layout.attributes.iter().find(|a| a.name == input.name) {
                if passes::attribute_type::run(&mut vs, input.var, attribute.base)? {
                    fixes.push(Fix::AttributeBaseType {
                        name: input.name,
                        base: attribute.base,
                    });
                }
            }
        }
    }

    let vertex_reflection = reflect(&vs)?;
    let fragment_reflection = reflect(&fs)?;
    let mut entries = Vec::new();
    add_to_bind_group(&mut entries, &vertex_reflection, layout, mode, &mut fixes)?;
    add_to_bind_group(&mut entries, &fragment_reflection, layout, mode, &mut fixes)?;
    rebind(&mut fs, &fragment_reflection, &vertex_outputs, &entries)?;
    let attributes: Vec<String> = layout.attributes.iter().map(|a| a.name.clone()).collect();
    rebind(&mut vs, &vertex_reflection, &attributes, &entries)?;
    Ok(Linked {
        vertex: to_bytes(vs.emit()),
        fragment: to_bytes(fs.emit()),
        entries,
        fixes,
    })
}

/// `(vanilla uniform index, texel format)` of every texel buffer entry, for
/// [`crate::Options::texel_buffers`].
pub fn texel_buffers(entries: &[Entry]) -> Result<Vec<(u32, crate::TexelFormat)>, Error> {
    let mut out = Vec::new();
    for (index, entry) in entries.iter().enumerate() {
        if let EntryKind::TexelBuffer { format } = &entry.kind {
            let format = format.as_deref().unwrap_or("none");
            let texel = crate::TexelFormat::from_gpu_format(format)
                .ok_or_else(|| Error::Link(format!("texel buffer {} has unsupported format {format}", entry.name)))?;
            out.push((index as u32, texel));
        }
    }
    Ok(out)
}
