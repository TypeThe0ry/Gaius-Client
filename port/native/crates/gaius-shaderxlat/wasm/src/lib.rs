//! wasm32 entry point for `gaius-shaderxlat`, framed by `gaius-kernel-abi`.
//!
//! Exports (wasm32 only): `memory`, `alloc`, `dealloc`, `release`,
//! `gaius_abi_version`, `run_shader_xlat` (job kind [`SHADER_XLAT`]) and
//! `run_shader_link` (job kind [`SHADER_LINK`], see [`run_link`]; cargo feature `link`, on by
//! default).
//!
//! The same module serves the kernel workers (prefetch at `compilePipeline`)
//! and a synchronous instance on the main thread (`finishCompile` on a cache
//! miss); nothing is kept between jobs.
//!
//! `run_shader_xlat` job payload (little-endian):
//! - `u32` flags: bit 0 [`Options::flip_vertex_y`](gaius_shaderxlat::Options), bit 1
//!   [`BindingLayout::Flat`] (clear: [`BindingLayout::Split`], the v0.4.0 layout), bit 2
//!   `Options::gl_depth_remap`;
//! - `u32` n, then n pairs `u32` uniform index, `u32` [`TexelFormat`] code
//!   (texel buffers of the pipeline);
//! - the SPIR-V module, to the end of the payload.
//!
//! Result payload:
//! - `u32` stage (0 vertex, 1 fragment);
//! - `u32` n, then n binding records of 8 `u32`: group, binding, vanilla
//!   uniform index, kind, a, b, c, used, where kind 0 is a uniform buffer
//!   (a = size), 1 a read-only storage buffer, 2 a texture (a = dimension 0 1d,
//!   1 2d, 2 2d-array, 3 3d, 4 cube, 5 cube-array; b = sample kind 0 float,
//!   1 sint, 2 uint, 3 depth; c = bit 0 multisampled, bit 1 sampled by
//!   `textureSample*`), 3 a sampler (a = comparison);
//! - `u32` WGSL length, then the WGSL text (UTF-8).

use gaius_kernel_abi::{KernelError, Reader, Status};
#[cfg(feature = "link")]
use gaius_shaderxlat::frontend::{self, Attribute, BaseType, EntryKind, Fix, Layout, Mode, Uniform};
use gaius_shaderxlat::{BindingKind, BindingLayout, Options, SampleKind, Stage, TexelFormat, TextureDim};

pub use gaius_kernel_abi::kind::{SHADER_FAMILY, SHADER_LINK, SHADER_XLAT};

const MAX_LAYOUT_ENTRIES: u32 = 4096;
const MAX_STRING_BYTES: usize = 64 * 1024;
const MAX_SPIRV_BYTES: usize = 64 * 1024 * 1024;

/// Decodes a job payload, translates it and encodes the result payload.
pub fn run(payload: &[u8]) -> Result<Vec<u8>, KernelError> {
    let mut reader = Reader::new(payload);
    let flags = reader.u32()?;
    if flags & !7 != 0 {
        return Err(KernelError::new(Status::BadPayload, "unknown flags"));
    }
    let count = reader.u32()? as usize;
    if count > 64 {
        return Err(KernelError::new(Status::BadPayload, "too many texel buffers"));
    }
    let mut texel_buffers = Vec::with_capacity(count);
    for _ in 0..count {
        let uniform = reader.u32()?;
        let code = reader.u32()?;
        let format =
            TexelFormat::from_code(code).ok_or_else(|| KernelError::new(Status::BadPayload, "unknown texel format"))?;
        texel_buffers.push((uniform, format));
    }
    let spirv = reader.take(reader.remaining())?;
    let options = Options {
        texel_buffers,
        flip_vertex_y: flags & 1 != 0,
        binding_layout: if flags & 2 != 0 {
            BindingLayout::Flat
        } else {
            BindingLayout::Split
        },
        gl_depth_remap: flags & 4 != 0,
    };
    let translation = gaius_shaderxlat::translate(spirv, &options)
        .map_err(|e| KernelError::new(Status::BadPayload, e.to_string()))?;

    let mut out = Vec::with_capacity(16 + translation.bindings.len() * 32 + translation.wgsl.len());
    let put = |out: &mut Vec<u8>, value: u32| out.extend_from_slice(&value.to_le_bytes());
    put(&mut out, matches!(translation.stage, Stage::Fragment) as u32);
    put(&mut out, translation.bindings.len() as u32);
    for b in &translation.bindings {
        let (kind, a, c, d) = match b.kind {
            BindingKind::UniformBuffer { size } => (0, size, 0, 0),
            BindingKind::StorageBuffer => (1, 0, 0, 0),
            BindingKind::Texture {
                dim,
                kind,
                multisampled,
                sampled,
            } => (
                2,
                match dim {
                    TextureDim::D1 => 0,
                    TextureDim::D2 => 1,
                    TextureDim::D2Array => 2,
                    TextureDim::D3 => 3,
                    TextureDim::Cube => 4,
                    TextureDim::CubeArray => 5,
                },
                match kind {
                    SampleKind::Float => 0,
                    SampleKind::Sint => 1,
                    SampleKind::Uint => 2,
                    SampleKind::Depth => 3,
                },
                multisampled as u32 | (sampled as u32) << 1,
            ),
            BindingKind::Sampler { comparison } => (3, comparison as u32, 0, 0),
        };
        for word in [b.group, b.binding, b.uniform, kind, a, c, d, b.used as u32] {
            put(&mut out, word);
        }
    }
    put(&mut out, translation.wgsl.len() as u32);
    out.extend_from_slice(translation.wgsl.as_bytes());
    Ok(out)
}

#[cfg(feature = "link")]
fn string(reader: &mut Reader<'_>) -> Result<String, KernelError> {
    let len = reader.u32()? as usize;
    if len > MAX_STRING_BYTES {
        return Err(KernelError::new(Status::BadPayload, "shader string is too long"));
    }
    let bytes = reader.take(len)?;
    String::from_utf8(bytes.to_vec()).map_err(|_| KernelError::new(Status::BadPayload, "string is not UTF-8"))
}

#[cfg(feature = "link")]
fn put_string(out: &mut Vec<u8>, text: &str) {
    out.extend_from_slice(&(text.len() as u32).to_le_bytes());
    out.extend_from_slice(text.as_bytes());
}

/// `run_shader_link`: [`frontend::link`] on a blaze3d pipeline's two shaderc modules.
///
/// Job payload (little-endian; a string is `u32` byte length + UTF-8):
/// - `u32` flags: bit 0 [`Mode::Lenient`] (clear: [`Mode::Strict`]);
/// - `u32` n, then n uniforms: string name, string texel `GpuFormat` (empty for a uniform buffer);
/// - `u32` n, then n sampler names;
/// - `u32` n, then n vertex attributes: string name, `u32` base type (0 float, 1 sint, 2 uint);
/// - `u32` length + vertex SPIR-V, `u32` length + fragment SPIR-V.
///
/// Result payload:
/// - `u32` n, then n entries (index = binding = vanilla uniform index): `u32` kind (0 uniform
///   buffer, 1 texel buffer, 2 sampled image), `u32` declared, string name, string texel format;
/// - `u32` n, then n fixes: `u32` kind (0 undeclared uniform buffer, 1 undeclared sampler,
///   2 dropped fragment input, 3 attribute base type), `u32` base type (kind 3), string name;
/// - `u32` length + linked vertex SPIR-V, `u32` length + linked fragment SPIR-V.
#[cfg(feature = "link")]
pub fn run_link(payload: &[u8]) -> Result<Vec<u8>, KernelError> {
    let mut reader = Reader::new(payload);
    let flags = reader.u32()?;
    if flags & !1 != 0 {
        return Err(KernelError::new(Status::BadPayload, "unknown flags"));
    }
    let mode = if flags & 1 != 0 { Mode::Lenient } else { Mode::Strict };
    let mut layout = Layout::default();
    let uniform_count = reader.u32()?;
    if uniform_count > MAX_LAYOUT_ENTRIES {
        return Err(KernelError::new(Status::BadPayload, "too many uniforms"));
    }
    for _ in 0..uniform_count {
        let name = string(&mut reader)?;
        let format = string(&mut reader)?;
        layout.uniforms.push(Uniform {
            name,
            texel_format: (!format.is_empty()).then_some(format),
        });
    }
    let sampler_count = reader.u32()?;
    if sampler_count > MAX_LAYOUT_ENTRIES {
        return Err(KernelError::new(Status::BadPayload, "too many samplers"));
    }
    for _ in 0..sampler_count {
        layout.samplers.push(string(&mut reader)?);
    }
    let attribute_count = reader.u32()?;
    if attribute_count > MAX_LAYOUT_ENTRIES {
        return Err(KernelError::new(Status::BadPayload, "too many attributes"));
    }
    for _ in 0..attribute_count {
        let name = string(&mut reader)?;
        let base = BaseType::from_code(reader.u32()?)
            .ok_or_else(|| KernelError::new(Status::BadPayload, "unknown attribute base type"))?;
        layout.attributes.push(Attribute { name, base });
    }
    let len = reader.u32()? as usize;
    if len > MAX_SPIRV_BYTES {
        return Err(KernelError::new(Status::BadPayload, "vertex SPIR-V is too large"));
    }
    let vertex = reader.take(len)?;
    let len = reader.u32()? as usize;
    if len > MAX_SPIRV_BYTES {
        return Err(KernelError::new(Status::BadPayload, "fragment SPIR-V is too large"));
    }
    let fragment = reader.take(len)?;
    if reader.remaining() != 0 {
        return Err(KernelError::new(Status::BadPayload, "trailing bytes"));
    }
    let linked = frontend::link(vertex, fragment, &layout, mode)
        .map_err(|e| KernelError::new(Status::BadPayload, e.to_string()))?;

    let mut out = Vec::with_capacity(64 + linked.vertex.len() + linked.fragment.len());
    let put = |out: &mut Vec<u8>, value: u32| out.extend_from_slice(&value.to_le_bytes());
    put(&mut out, linked.entries.len() as u32);
    for entry in &linked.entries {
        let (kind, format) = match &entry.kind {
            EntryKind::UniformBuffer => (0, ""),
            EntryKind::TexelBuffer { format } => (1, format.as_deref().unwrap_or("")),
            EntryKind::SampledImage => (2, ""),
        };
        put(&mut out, kind);
        put(&mut out, entry.declared as u32);
        put_string(&mut out, &entry.name);
        put_string(&mut out, format);
    }
    put(&mut out, linked.fixes.len() as u32);
    for fix in &linked.fixes {
        let (kind, base, name) = match fix {
            Fix::UndeclaredUniformBuffer(name) => (0, 0, name),
            Fix::UndeclaredSampler(name) => (1, 0, name),
            Fix::DroppedFragmentInput(name) => (2, 0, name),
            Fix::AttributeBaseType { name, base } => (3, base.code(), name),
        };
        put(&mut out, kind);
        put(&mut out, base);
        put_string(&mut out, name);
    }
    put(&mut out, linked.vertex.len() as u32);
    out.extend_from_slice(&linked.vertex);
    put(&mut out, linked.fragment.len() as u32);
    out.extend_from_slice(&linked.fragment);
    Ok(out)
}

#[cfg(target_arch = "wasm32")]
mod exports {
    use gaius_kernel_abi::{job_bytes, run_kernel};

    gaius_kernel_abi::export_runtime!();

    /// # Safety
    /// `ptr` and `len` must describe a job buffer obtained from `alloc`.
    #[no_mangle]
    pub unsafe extern "C" fn run_shader_xlat(ptr: *const u8, len: u32) -> *mut u8 {
        // SAFETY: forwarded from the caller.
        let job = unsafe { job_bytes(ptr, len) };
        run_kernel(job, super::SHADER_XLAT, super::run)
    }

    /// # Safety
    /// `ptr` and `len` must describe a job buffer obtained from `alloc`.
    #[cfg(feature = "link")]
    #[no_mangle]
    pub unsafe extern "C" fn run_shader_link(ptr: *const u8, len: u32) -> *mut u8 {
        // SAFETY: forwarded from the caller.
        let job = unsafe { job_bytes(ptr, len) };
        run_kernel(job, super::SHADER_LINK, super::run_link)
    }
}

#[cfg(all(test, feature = "link"))]
mod tests {
    use super::*;

    fn put(out: &mut Vec<u8>, value: u32) {
        out.extend_from_slice(&value.to_le_bytes());
    }

    fn take<'a>(bytes: &mut &'a [u8], len: usize) -> &'a [u8] {
        let (head, tail) = bytes.split_at(len);
        *bytes = tail;
        head
    }

    fn word(bytes: &mut &[u8]) -> u32 {
        u32::from_le_bytes(take(bytes, 4).try_into().unwrap())
    }

    fn text(bytes: &mut &[u8]) -> String {
        let len = word(bytes) as usize;
        String::from_utf8(take(bytes, len).to_vec()).unwrap()
    }

    #[test]
    fn link_job_round_trips() {
        let vertex = include_bytes!("../../tests/frontend/legacy.vert.spv");
        let fragment = include_bytes!("../../tests/frontend/legacy.frag.spv");
        let mut payload = Vec::new();
        put(&mut payload, 1); // lenient
        put(&mut payload, 1);
        put_string(&mut payload, "Projection");
        put_string(&mut payload, "");
        put(&mut payload, 0);
        put(&mut payload, 2);
        put_string(&mut payload, "Position");
        put(&mut payload, 0);
        put_string(&mut payload, "UV1");
        put(&mut payload, 1);
        put(&mut payload, vertex.len() as u32);
        payload.extend_from_slice(vertex);
        put(&mut payload, fragment.len() as u32);
        payload.extend_from_slice(fragment);

        let layout = Layout {
            uniforms: vec![Uniform {
                name: "Projection".into(),
                texel_format: None,
            }],
            samplers: Vec::new(),
            attributes: vec![
                Attribute {
                    name: "Position".into(),
                    base: BaseType::Float,
                },
                Attribute {
                    name: "UV1".into(),
                    base: BaseType::Sint,
                },
            ],
        };
        let expected = frontend::link(vertex, fragment, &layout, Mode::Lenient).unwrap();

        let result = run_link(&payload).unwrap();
        let mut at = result.as_slice();
        let entries = word(&mut at);
        assert_eq!(entries as usize, expected.entries.len());
        for entry in &expected.entries {
            let kind = word(&mut at);
            let declared = word(&mut at) == 1;
            let name = text(&mut at);
            let _format = text(&mut at);
            assert_eq!(name, entry.name);
            assert_eq!(declared, entry.declared);
            assert_eq!(kind == 0, entry.kind == EntryKind::UniformBuffer);
        }
        let fixes = word(&mut at);
        assert_eq!(fixes as usize, expected.fixes.len());
        for _ in 0..fixes {
            word(&mut at);
            word(&mut at);
            text(&mut at);
        }
        let len = word(&mut at) as usize;
        assert_eq!(take(&mut at, len), expected.vertex.as_slice());
        let len = word(&mut at) as usize;
        assert_eq!(take(&mut at, len), expected.fragment.as_slice());
        assert!(at.is_empty());

        // Strict mode refuses the undeclared block with the vanilla message.
        payload[0] = 0;
        let error = run_link(&payload).unwrap_err();
        assert_eq!(error.status, Status::BadPayload);
        assert!(
            error
                .message
                .contains("Unable to find shader defined uniform (Globals)"),
            "{}",
            error.message
        );
        // Unknown flags and truncated payloads are refused.
        payload[0] = 2;
        assert!(run_link(&payload).is_err());
        payload[0] = 1;
        assert!(run_link(&payload[..payload.len() - 1]).is_err());
    }

    #[test]
    fn xlat_flags_select_layout_and_depth() {
        let layout = Layout {
            uniforms: ["Transform", "Globals", "Fog"]
                .iter()
                .map(|name| Uniform {
                    name: name.to_string(),
                    texel_format: None,
                })
                .chain([Uniform {
                    name: "Offsets".into(),
                    texel_format: Some("R8_SINT".into()),
                }])
                .collect(),
            samplers: vec!["Sampler0".into(), "Sky".into()],
            attributes: ["Position", "Color", "UV0"]
                .iter()
                .map(|name| Attribute {
                    name: name.to_string(),
                    base: BaseType::Float,
                })
                .collect(),
        };
        let linked = frontend::link(
            include_bytes!("../../tests/frontend/link.vert.spv"),
            include_bytes!("../../tests/frontend/link.frag.spv"),
            &layout,
            Mode::Strict,
        )
        .unwrap();
        let offsets = linked.entries.iter().position(|e| e.name == "Offsets").unwrap() as u32;
        let job = |flags: u32| {
            let mut payload = Vec::new();
            put(&mut payload, flags);
            put(&mut payload, 1);
            put(&mut payload, offsets);
            put(&mut payload, TexelFormat::R8Sint.code());
            payload.extend_from_slice(&linked.vertex);
            run(&payload)
        };
        assert!(job(8).is_err(), "unknown flag");
        let wgsl = |flags: u32| String::from_utf8_lossy(&job(flags).unwrap()).into_owned();
        let (split, flat, remapped) = (wgsl(1), wgsl(1 | 2), wgsl(1 | 4));
        assert!(
            split.contains(&format!("@group(1) @binding({}) ", offsets * 2)),
            "{split}"
        );
        assert!(
            flat.contains(&format!("@group(0) @binding({}) ", offsets * 2)),
            "{flat}"
        );
        assert!(remapped.contains("0.5f") && !split.contains("0.5f"));
    }

    #[test]
    fn link_rejects_unbounded_layout_counts() {
        let mut payload = Vec::new();
        put(&mut payload, 0); // strict
        put(&mut payload, MAX_LAYOUT_ENTRIES + 1);
        assert_eq!(run_link(&payload).unwrap_err().status, Status::BadPayload);
    }
}
