//! SPIR-V to WGSL for the WebGPU backend of the renderpearl SPI.
//!
//! Input is exactly what a renderpearl backend receives in
//! `BackendRenderPipeline.CreateInfo.Shader.module().spv()`: the vanilla
//! frontend's shaderc output (Vulkan 1.2 / SPIR-V 1.5, `-O0`, debug info)
//! with every resource in descriptor set 0 at the pipeline's uniform index
//! (`CreateInfo.uniforms()`), shared by the vertex and fragment stages.
//!
//! Pipeline:
//! 1. SPIR-V patches ([`passes`]): strip debug info, drop `gl_PointSize`
//!    writes, texel buffers to storage buffers, split combined image samplers,
//!    split output arrays, and with [`Options::gl_depth_remap`] map GL's depth
//!    range at the vertex output (1.21.11; 26.2 and 26.3 already target 0..w);
//! 2. naga's SPIR-V reader ([`Options::flip_vertex_y`] negates the vertex
//!    position's y);
//! 3. the WebGPU binding layout ([`BindingLayout`]);
//! 4. naga validation and the WGSL writer.
//!
//! The WGSL binding layout is a pure function of the vanilla uniform index
//! and the kind of resource, so both stages of a pipeline agree without
//! seeing each other. The default [`BindingLayout::Split`] puts uniform
//! buffer `i` at `@group(0) @binding(i)` (bound with a dynamic offset) and
//! texture or texel buffer `i` at `@group(1) @binding(2i)`, its split-off
//! sampler at `@group(1) @binding(2i + 1)`.
//!
//! The 26.2 and 1.21.11 backends receive GLSL instead of bound SPIR-V: their
//! shaderc output goes through [`frontend::link`] first (vanilla 26.2
//! reflection and rebind, plus the GL compatibility passes of 1.21.11).

pub mod frontend;
pub mod passes;
pub mod spirv;

use naga::valid::{Capabilities, ModuleInfo, ValidationFlags, Validator};
use std::fmt;

/// Format of a texel buffer's texels (`UniformDescription.gpuFormat()`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TexelFormat {
    R8Sint,
    R8Uint,
    R16Sint,
    R16Uint,
    R32Sint,
    R32Uint,
    R32Float,
}

impl TexelFormat {
    /// Parses the renderpearl `GpuFormat` name.
    pub fn from_gpu_format(name: &str) -> Option<TexelFormat> {
        Some(match name {
            "R8_SINT" => TexelFormat::R8Sint,
            "R8_UINT" => TexelFormat::R8Uint,
            "R16_SINT" => TexelFormat::R16Sint,
            "R16_UINT" => TexelFormat::R16Uint,
            "R32_SINT" => TexelFormat::R32Sint,
            "R32_UINT" => TexelFormat::R32Uint,
            "R32_FLOAT" => TexelFormat::R32Float,
            _ => return None,
        })
    }

    pub fn code(self) -> u32 {
        self as u32
    }

    pub fn from_code(code: u32) -> Option<TexelFormat> {
        [
            TexelFormat::R8Sint,
            TexelFormat::R8Uint,
            TexelFormat::R16Sint,
            TexelFormat::R16Uint,
            TexelFormat::R32Sint,
            TexelFormat::R32Uint,
            TexelFormat::R32Float,
        ]
        .get(code as usize)
        .copied()
    }
}

/// Where [`translate`] puts the resources of a pipeline's vanilla uniform list.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub enum BindingLayout {
    /// The v0.4.0 layout (DECISIONS 2.2): uniform buffer `i` at `@group(0) @binding(i)`,
    /// which the backend binds from its uniform ring with a dynamic offset; texture or texel
    /// buffer (read-only storage buffer) `i` at `@group(1) @binding(2i)` and the sampler split
    /// off a combined image sampler at `@group(1) @binding(2i + 1)`. Group 1 can be cached
    /// per texture combination while draws only move group 0's offsets.
    #[default]
    Split,
    /// The first prototype's single group: every resource of uniform `i` at `@group(0)
    /// @binding(2i)`, its sampler at `2i + 1`. Kept to compare with that prototype's corpus output.
    Flat,
}

/// What a resource binding holds, as far as its WGSL slot is concerned.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Slot {
    UniformBuffer,
    /// A texture or a texel buffer's storage buffer.
    Resource,
    /// The sampler split off a combined image sampler.
    Sampler,
}

impl BindingLayout {
    /// `(group, binding)` of vanilla uniform `uniform`.
    pub const fn place(self, uniform: u32, slot: Slot) -> (u32, u32) {
        match (self, slot) {
            (BindingLayout::Split, Slot::UniformBuffer) => (0, uniform),
            (BindingLayout::Split, Slot::Resource) => (1, uniform * 2),
            (BindingLayout::Split, Slot::Sampler) => (1, uniform * 2 + 1),
            (BindingLayout::Flat, Slot::Sampler) => (0, uniform * 2 + 1),
            (BindingLayout::Flat, _) => (0, uniform * 2),
        }
    }

    /// The vanilla uniform index of a binding placed by [`BindingLayout::place`].
    pub const fn uniform(self, group: u32, binding: u32) -> u32 {
        match (self, group) {
            (BindingLayout::Split, 0) => binding,
            _ => binding / 2,
        }
    }
}

#[derive(Clone, Debug)]
pub struct Options {
    /// Texel format of each texel buffer, by vanilla uniform index.
    pub texel_buffers: Vec<(u32, TexelFormat)>,
    /// Negate the vertex position's y (naga's `adjust_coordinate_space`). The v0.4.0 backend
    /// renders with GL's row order (row 0 = bottom of clip space, as the GL backend and
    /// every `gl_FragCoord`/`texelFetch` in the vanilla shaders assume): it sets this flag,
    /// uses `frontFace: "cw"` and flips once when presenting; see the crate README.
    pub flip_vertex_y: bool,
    pub binding_layout: BindingLayout,
    /// Remap GL's clip-space depth (`-w..w`) to WebGPU's `0..w` at the vertex output:
    /// `z = (z + w) / 2` ([`passes::depth_remap`]). For profiles whose projection does not ask
    /// the device for its depth range (1.21.11).
    pub gl_depth_remap: bool,
}

impl Default for Options {
    /// The v0.4.0 convention: [`BindingLayout::Split`] and `flip_vertex_y`, no depth remap.
    fn default() -> Self {
        Options {
            texel_buffers: Vec::new(),
            flip_vertex_y: true,
            binding_layout: BindingLayout::Split,
            gl_depth_remap: false,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Stage {
    Vertex,
    Fragment,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SampleKind {
    Float,
    Sint,
    Uint,
    Depth,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TextureDim {
    D1,
    D2,
    D2Array,
    D3,
    Cube,
    CubeArray,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum BindingKind {
    /// `var<uniform>`; `size` is the WGSL size of the block.
    UniformBuffer {
        size: u32,
    },
    /// `var<storage, read>` (texel buffers).
    StorageBuffer,
    /// `textureSample*` (`sampled`) needs a filterable texture and a filtering
    /// sampler; a texture only read with `textureLoad` can take any format.
    Texture {
        dim: TextureDim,
        kind: SampleKind,
        multisampled: bool,
        sampled: bool,
    },
    Sampler {
        comparison: bool,
    },
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Binding {
    pub group: u32,
    pub binding: u32,
    /// Vanilla uniform index (`CreateInfo.uniforms()`).
    pub uniform: u32,
    pub kind: BindingKind,
    /// Statically used by the entry point.
    pub used: bool,
}

#[derive(Clone, Debug)]
pub struct Translation {
    pub stage: Stage,
    pub wgsl: String,
    pub bindings: Vec<Binding>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Error {
    /// A SPIR-V patch refused the module.
    Spirv(String),
    /// naga's SPIR-V reader.
    Parse(String),
    /// naga validation.
    Validate(String),
    /// naga's WGSL writer.
    Write(String),
    /// The GLSL front-end's reflection and rebind ([`frontend::link`]).
    Link(String),
}

impl Error {
    pub fn spirv(message: impl Into<String>) -> Error {
        Error::Spirv(message.into())
    }

    pub fn phase(&self) -> &'static str {
        match self {
            Error::Spirv(_) => "spirv",
            Error::Parse(_) => "parse",
            Error::Validate(_) => "validate",
            Error::Write(_) => "write",
            Error::Link(_) => "link",
        }
    }
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Error::Spirv(m) => write!(f, "spirv patch: {m}"),
            Error::Parse(m) => write!(f, "naga spv-in: {m}"),
            Error::Validate(m) => write!(f, "naga validation: {m}"),
            Error::Write(m) => write!(f, "naga wgsl-out: {m}"),
            Error::Link(m) => write!(f, "link: {m}"),
        }
    }
}

impl std::error::Error for Error {}

/// Step 1: the SPIR-V patches. Returns the patched words.
pub fn patch_spirv(spirv: &[u8], options: &Options) -> Result<Vec<u32>, Error> {
    let words = spirv::words_from_bytes(spirv)?;
    let mut module = spirv::Module::parse(&words)?;
    passes::strip::run(&mut module);
    passes::point_size::run(&mut module)?;
    passes::texel_buffer::run(&mut module, &options.texel_buffers)?;
    passes::split_sampler::run(&mut module)?;
    passes::output_array::run(&mut module)?;
    if options.gl_depth_remap {
        passes::depth_remap::run(&mut module)?;
    }
    Ok(module.emit())
}

/// Step 2: naga's SPIR-V reader.
pub fn parse(words: &[u32], options: &Options) -> Result<naga::Module, Error> {
    let options = naga::front::spv::Options {
        adjust_coordinate_space: options.flip_vertex_y,
        strict_capabilities: false,
        block_ctx_dump_prefix: None,
    };
    naga::front::spv::Frontend::new(words.iter().copied(), &options)
        .parse()
        .map_err(|e| Error::Parse(format!("{e}")))
}

/// Step 3: moves every resource (descriptor set 0, binding = vanilla uniform index) to its
/// WGSL group and binding.
pub fn assign_bindings(module: &mut naga::Module, layout: BindingLayout) -> Result<(), Error> {
    for (_, global) in module.global_variables.iter_mut() {
        let Some(binding) = global.binding.as_mut() else {
            continue;
        };
        if binding.group != 0 {
            return Err(Error::Validate(format!("resource in descriptor set {}", binding.group)));
        }
        let slot = match (&module.types[global.ty].inner, global.space) {
            (naga::TypeInner::Sampler { .. }, _) => Slot::Sampler,
            (naga::TypeInner::Image { .. }, _) => Slot::Resource,
            (_, naga::AddressSpace::Uniform) => Slot::UniformBuffer,
            (_, naga::AddressSpace::Storage { .. }) => Slot::Resource,
            (_, other) => return Err(Error::Validate(format!("unexpected resource space {other:?}"))),
        };
        (binding.group, binding.binding) = layout.place(binding.binding, slot);
    }
    Ok(())
}

/// Step 4a: naga validation with WebGPU's capabilities.
pub fn validate(module: &naga::Module) -> Result<ModuleInfo, Error> {
    Validator::new(ValidationFlags::all(), Capabilities::empty())
        .validate(module)
        .map_err(|e| Error::Validate(format_validation(&e)))
}

fn format_validation(error: &naga::WithSpan<naga::valid::ValidationError>) -> String {
    let mut text = format!("{}", error.as_inner());
    let mut source: Option<&dyn std::error::Error> = std::error::Error::source(error.as_inner());
    while let Some(inner) = source {
        text.push_str(": ");
        text.push_str(&inner.to_string());
        source = inner.source();
    }
    text
}

/// Step 4b: the WGSL text.
pub fn write_wgsl(module: &naga::Module, info: &ModuleInfo) -> Result<String, Error> {
    naga::back::wgsl::write_string(module, info, naga::back::wgsl::WriterFlags::empty())
        .map_err(|e| Error::Write(format!("{e}")))
}

/// Describes the bindings of the module's single entry point (placed by `layout`).
pub fn reflect(
    module: &naga::Module,
    info: &ModuleInfo,
    layout: BindingLayout,
) -> Result<(Stage, Vec<Binding>), Error> {
    let entry = module
        .entry_points
        .first()
        .ok_or_else(|| Error::Validate("no entry point".into()))?;
    let stage = match entry.stage {
        naga::ShaderStage::Vertex => Stage::Vertex,
        naga::ShaderStage::Fragment => Stage::Fragment,
        other => return Err(Error::Validate(format!("unsupported stage {other:?}"))),
    };
    let entry_info = info.get_entry_point(0);
    let mut bindings = Vec::new();
    for (handle, global) in module.global_variables.iter() {
        let Some(binding) = global.binding.as_ref() else {
            continue;
        };
        let used = !entry_info[handle].is_empty();
        let kind = match &module.types[global.ty].inner {
            naga::TypeInner::Sampler { comparison } => BindingKind::Sampler {
                comparison: *comparison,
            },
            naga::TypeInner::Image { dim, arrayed, class } => {
                let dim = match (dim, arrayed) {
                    (naga::ImageDimension::D1, _) => TextureDim::D1,
                    (naga::ImageDimension::D2, false) => TextureDim::D2,
                    (naga::ImageDimension::D2, true) => TextureDim::D2Array,
                    (naga::ImageDimension::D3, _) => TextureDim::D3,
                    (naga::ImageDimension::Cube, false) => TextureDim::Cube,
                    (naga::ImageDimension::Cube, true) => TextureDim::CubeArray,
                };
                let (kind, multisampled) = match class {
                    naga::ImageClass::Sampled { kind, multi } => (
                        match kind {
                            naga::ScalarKind::Sint => SampleKind::Sint,
                            naga::ScalarKind::Uint => SampleKind::Uint,
                            _ => SampleKind::Float,
                        },
                        *multi,
                    ),
                    naga::ImageClass::Depth { multi } => (SampleKind::Depth, *multi),
                    _ => return Err(Error::Validate("storage textures are not expected".into())),
                };
                let sampled = entry_info.sampling_set.iter().any(|key| key.image == handle);
                BindingKind::Texture {
                    dim,
                    kind,
                    multisampled,
                    sampled,
                }
            }
            _ => match global.space {
                naga::AddressSpace::Uniform => BindingKind::UniformBuffer {
                    size: module.types[global.ty].inner.size(module.to_ctx()),
                },
                naga::AddressSpace::Storage { .. } => BindingKind::StorageBuffer,
                other => return Err(Error::Validate(format!("unexpected resource space {other:?}"))),
            },
        };
        bindings.push(Binding {
            group: binding.group,
            binding: binding.binding,
            uniform: layout.uniform(binding.group, binding.binding),
            kind,
            used,
        });
    }
    bindings.sort_by_key(|b| (b.group, b.binding));
    Ok((stage, bindings))
}

/// SPIR-V of one vanilla pipeline stage to WGSL.
pub fn translate(spirv: &[u8], options: &Options) -> Result<Translation, Error> {
    let words = patch_spirv(spirv, options)?;
    let mut module = parse(&words, options)?;
    assign_bindings(&mut module, options.binding_layout)?;
    let info = validate(&module)?;
    let wgsl = write_wgsl(&module, &info)?;
    let (stage, bindings) = reflect(&module, &info, options.binding_layout)?;
    Ok(Translation { stage, wgsl, bindings })
}
