//! Wire format between the JS kernel workers (`port/web/kernels`) and the
//! Gaius wasm kernels.
//!
//! Every worker owns one wasm instance; nothing is shared between instances,
//! so the same build runs on GitHub Pages and from `file://`. A kernel module
//! exports `memory` and:
//!
//! - `alloc(len) -> ptr` / `dealloc(ptr, len)`: job buffers;
//! - `run_<kind>(ptr, len) -> desc`: runs one job and returns a run
//!   descriptor (see [`RunDescriptor`]);
//! - `release(desc)`: frees a descriptor and the data it points at;
//! - `gaius_abi_version() -> u32`: [`ABI_VERSION`].
//!
//! The job buffer is a [`JobHeader`] followed by the payload. On success the
//! descriptor's data is a [`ResultHeader`] followed by the result payload; on
//! failure it is a UTF-8 message and the descriptor status is a [`Status`].
//! All integers are little-endian, and buffers are [`BUFFER_ALIGN`]-aligned
//! with 16-byte headers, so an `f64` payload can be viewed as a
//! `Float64Array` without copying.
//!
//! [`export_runtime!`] emits the shared exports; a kernel crate adds one
//! `run_<kind>` per job kind by calling [`run_kernel`].
#![no_std]
#![deny(unsafe_op_in_unsafe_fn)]

extern crate alloc;

use alloc::string::String;
use alloc::vec::Vec;

/// Version of this framing; bumped on any incompatible layout change.
pub const ABI_VERSION: u16 = 1;
/// `"GKJB"` read as a little-endian `u32`.
pub const JOB_MAGIC: u32 = u32::from_le_bytes(*b"GKJB");
/// `"GKRS"` read as a little-endian `u32`.
pub const RESULT_MAGIC: u32 = u32::from_le_bytes(*b"GKRS");
pub const JOB_HEADER_LEN: usize = 16;
pub const RESULT_HEADER_LEN: usize = 16;
pub const BUFFER_ALIGN: usize = 8;

/// Job kinds carried in [`JobHeader::kind`]. The high byte names the kernel
/// family, the low byte the job.
pub mod kind {
    /// `run_noise_points`: world-generation noise at explicit positions.
    pub const NOISE_POINTS: u16 = 0x0101;
    /// Chunk section meshing.
    pub const MESH_FAMILY: u16 = 0x0200;
    /// `run_load_model_table`: replaces the mesher's resident model table.
    pub const LOAD_MODEL_TABLE: u16 = MESH_FAMILY | 0x01;
    /// `run_mesh_section`: meshes one chunk section.
    pub const MESH_SECTION: u16 = MESH_FAMILY | 0x02;
    /// Light propagation.
    pub const LIGHT_FAMILY: u16 = 0x0300;
    /// `run_light_column`: first light / checkBlock of one chunk column.
    pub const LIGHT_COLUMN: u16 = LIGHT_FAMILY | 0x01;
    /// Chunk generation.
    pub const WORLDGEN_FAMILY: u16 = 0x0400;
    /// `run_load_generator`: installs a generator description.
    pub const LOAD_GENERATOR: u16 = WORLDGEN_FAMILY | 0x01;
    /// `run_biomes`: biome sampling of one chunk.
    pub const BIOMES: u16 = WORLDGEN_FAMILY | 0x02;
    /// `run_terrain`: noise terrain fill of one chunk.
    pub const TERRAIN: u16 = WORLDGEN_FAMILY | 0x03;
    /// `run_surface`: surface rules over a filled chunk.
    pub const SURFACE: u16 = WORLDGEN_FAMILY | 0x04;
    pub const ORE: u16 = WORLDGEN_FAMILY | 0x05;
    /// Shader translation.
    pub const SHADER_FAMILY: u16 = 0x0500;
    /// `run_shader_xlat`: one SPIR-V module to WGSL.
    pub const SHADER_XLAT: u16 = SHADER_FAMILY | 0x01;
    /// `run_shader_link`: reflects and rebinds a blaze3d pipeline's two shaderc modules.
    pub const SHADER_LINK: u16 = SHADER_FAMILY | 0x02;
    /// PNG image decoding.
    pub const PNG_FAMILY: u16 = 0x0700;
    /// `run_png_decode`: encoded PNG to the browser STB override pixel layout.
    pub const PNG_DECODE: u16 = PNG_FAMILY | 0x01;
}

/// Run status, stored in [`RunDescriptor`] and [`ResultHeader`].
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u16)]
pub enum Status {
    Ok = 0,
    BadMagic = 1,
    BadVersion = 2,
    /// The header names a different kind than the `run_<kind>` export.
    WrongKind = 3,
    /// The buffer is shorter than its header or layout claims.
    Truncated = 4,
    /// The payload decoded but its values are invalid for the job.
    BadPayload = 5,
    OutOfMemory = 6,
}

/// A failed job: the status plus a message for the worker's error event.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct KernelError {
    pub status: Status,
    pub message: String,
}

impl KernelError {
    pub fn new(status: Status, message: impl Into<String>) -> Self {
        KernelError {
            status,
            message: message.into(),
        }
    }
}

impl From<Status> for KernelError {
    fn from(status: Status) -> Self {
        let message = match status {
            Status::Ok => "ok",
            Status::BadMagic => "bad job magic",
            Status::BadVersion => "unsupported abi version",
            Status::WrongKind => "job kind does not match the export",
            Status::Truncated => "job buffer is truncated",
            Status::BadPayload => "invalid job payload",
            Status::OutOfMemory => "out of memory",
        };
        KernelError::new(status, message)
    }
}

/// Header in front of every job payload.
///
/// Layout: magic `u32` @0, abi version `u16` @4, kind `u16` @6,
/// job id `u32` @8, payload length `u32` @12.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct JobHeader {
    pub kind: u16,
    pub job_id: u32,
    pub payload_len: u32,
}

impl JobHeader {
    /// Validates the header and returns it with exactly its payload.
    pub fn parse(buf: &[u8]) -> Result<(JobHeader, &[u8]), Status> {
        if buf.len() < JOB_HEADER_LEN {
            return Err(Status::Truncated);
        }
        if read_u32(buf, 0) != JOB_MAGIC {
            return Err(Status::BadMagic);
        }
        if read_u16(buf, 4) != ABI_VERSION {
            return Err(Status::BadVersion);
        }
        let header = JobHeader {
            kind: read_u16(buf, 6),
            job_id: read_u32(buf, 8),
            payload_len: read_u32(buf, 12),
        };
        let end = JOB_HEADER_LEN
            .checked_add(header.payload_len as usize)
            .filter(|&end| end <= buf.len())
            .ok_or(Status::Truncated)?;
        Ok((header, &buf[JOB_HEADER_LEN..end]))
    }

    /// Encodes the header followed by `payload`.
    pub fn frame(kind: u16, job_id: u32, payload: &[u8]) -> Vec<u8> {
        let mut buf = Vec::with_capacity(JOB_HEADER_LEN + payload.len());
        buf.extend_from_slice(&JOB_MAGIC.to_le_bytes());
        buf.extend_from_slice(&ABI_VERSION.to_le_bytes());
        buf.extend_from_slice(&kind.to_le_bytes());
        buf.extend_from_slice(&job_id.to_le_bytes());
        buf.extend_from_slice(&(payload.len() as u32).to_le_bytes());
        buf.extend_from_slice(payload);
        buf
    }
}

/// Header in front of every successful result payload.
///
/// Layout: magic `u32` @0, abi version `u16` @4, status `u16` @6,
/// job id `u32` @8, payload length `u32` @12.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ResultHeader {
    pub status: u16,
    pub job_id: u32,
    pub payload_len: u32,
}

impl ResultHeader {
    pub fn write(&self, out: &mut [u8]) {
        out[0..4].copy_from_slice(&RESULT_MAGIC.to_le_bytes());
        out[4..6].copy_from_slice(&ABI_VERSION.to_le_bytes());
        out[6..8].copy_from_slice(&self.status.to_le_bytes());
        out[8..12].copy_from_slice(&self.job_id.to_le_bytes());
        out[12..16].copy_from_slice(&self.payload_len.to_le_bytes());
    }

    pub fn parse(buf: &[u8]) -> Option<ResultHeader> {
        if buf.len() < RESULT_HEADER_LEN || read_u32(buf, 0) != RESULT_MAGIC || read_u16(buf, 4) != ABI_VERSION {
            return None;
        }
        Some(ResultHeader {
            status: read_u16(buf, 6),
            job_id: read_u32(buf, 8),
            payload_len: read_u32(buf, 12),
        })
    }
}

/// What `run_<kind>` returns a pointer to: `status u32 @0, data_ptr u32 @4,
/// data_len u32 @8`, then 4 reserved bytes. The data lives in the same
/// allocation, 16 bytes after the descriptor; `release(desc)` frees both.
pub struct RunDescriptor;

impl RunDescriptor {
    pub const LEN: usize = 16;
}

fn read_u16(buf: &[u8], at: usize) -> u16 {
    u16::from_le_bytes([buf[at], buf[at + 1]])
}

fn read_u32(buf: &[u8], at: usize) -> u32 {
    u32::from_le_bytes([buf[at], buf[at + 1], buf[at + 2], buf[at + 3]])
}

/// Little-endian cursor over a job payload.
pub struct Reader<'a> {
    buf: &'a [u8],
    pos: usize,
}

macro_rules! read_le {
    ($name:ident, $ty:ty) => {
        pub fn $name(&mut self) -> Result<$ty, Status> {
            let bytes = self.take(core::mem::size_of::<$ty>())?;
            let mut raw = [0u8; core::mem::size_of::<$ty>()];
            raw.copy_from_slice(bytes);
            Ok(<$ty>::from_le_bytes(raw))
        }
    };
}

impl<'a> Reader<'a> {
    pub fn new(buf: &'a [u8]) -> Self {
        Reader { buf, pos: 0 }
    }

    pub fn take(&mut self, len: usize) -> Result<&'a [u8], Status> {
        let end = self
            .pos
            .checked_add(len)
            .filter(|&end| end <= self.buf.len())
            .ok_or(Status::Truncated)?;
        let bytes = &self.buf[self.pos..end];
        self.pos = end;
        Ok(bytes)
    }

    /// Skips to the next multiple of `align` bytes from the payload start.
    pub fn align(&mut self, align: usize) -> Result<(), Status> {
        let pad = (align - self.pos % align) % align;
        self.take(pad).map(|_| ())
    }

    pub fn remaining(&self) -> usize {
        self.buf.len() - self.pos
    }

    read_le!(u8, u8);
    read_le!(u16, u16);
    read_le!(u32, u32);
    read_le!(i32, i32);
    read_le!(i64, i64);
    read_le!(f64, f64);
}

/// Aligned buffers shared with the JS side.
pub mod buffer {
    use super::BUFFER_ALIGN;
    use alloc::alloc::{alloc, dealloc, Layout};

    fn layout(len: usize) -> Option<Layout> {
        Layout::from_size_align(len, BUFFER_ALIGN).ok()
    }

    /// Returns an aligned buffer of `len` bytes, or null when out of memory.
    /// A zero-length request yields a dangling, aligned, non-null pointer.
    pub fn allocate(len: usize) -> *mut u8 {
        if len == 0 {
            return BUFFER_ALIGN as *mut u8;
        }
        match layout(len) {
            // SAFETY: the layout has a non-zero size.
            Some(layout) => unsafe { alloc(layout) },
            None => core::ptr::null_mut(),
        }
    }

    /// Releases a buffer from [`allocate`].
    ///
    /// # Safety
    /// `ptr` must come from [`allocate`] with the same `len` and must not be
    /// used afterwards.
    pub unsafe fn release(ptr: *mut u8, len: usize) {
        if len == 0 || ptr.is_null() {
            return;
        }
        if let Some(layout) = layout(len) {
            // SAFETY: guaranteed by the caller.
            unsafe { dealloc(ptr, layout) }
        }
    }
}

/// Runs one framed job through `handler` and returns a run descriptor
/// allocated with [`buffer::allocate`] (null only when out of memory).
///
/// `handler` receives the payload and returns the result payload.
pub fn run_kernel<F>(job: &[u8], expected_kind: u16, handler: F) -> *mut u8
where
    F: FnOnce(&[u8]) -> Result<Vec<u8>, KernelError>,
{
    let outcome = JobHeader::parse(job)
        .map_err(KernelError::from)
        .and_then(|(header, payload)| {
            if header.kind != expected_kind {
                return Err(KernelError::from(Status::WrongKind));
            }
            handler(payload).map(|out| (header.job_id, out))
        });
    match outcome {
        Ok((job_id, payload)) => {
            let mut data = Vec::new();
            if data.try_reserve_exact(RESULT_HEADER_LEN + payload.len()).is_err() {
                return describe(Status::OutOfMemory, b"out of memory");
            }
            data.resize(RESULT_HEADER_LEN, 0);
            ResultHeader {
                status: Status::Ok as u16,
                job_id,
                payload_len: payload.len() as u32,
            }
            .write(&mut data);
            data.extend_from_slice(&payload);
            describe(Status::Ok, &data)
        }
        Err(error) => describe(error.status, error.message.as_bytes()),
    }
}

fn describe(status: Status, data: &[u8]) -> *mut u8 {
    let total = RunDescriptor::LEN + data.len();
    if u32::try_from(total).is_err() {
        return core::ptr::null_mut();
    }
    let ptr = buffer::allocate(total);
    if ptr.is_null() {
        return ptr;
    }
    // SAFETY: `ptr` is a fresh allocation of `total` bytes.
    let out = unsafe { core::slice::from_raw_parts_mut(ptr, total) };
    let data_ptr = (ptr as usize).wrapping_add(RunDescriptor::LEN) as u32;
    out[0..4].copy_from_slice(&(status as u32).to_le_bytes());
    out[4..8].copy_from_slice(&data_ptr.to_le_bytes());
    out[8..12].copy_from_slice(&(data.len() as u32).to_le_bytes());
    out[12..16].fill(0);
    out[RunDescriptor::LEN..].copy_from_slice(data);
    ptr
}

/// Frees a descriptor from [`run_kernel`] together with its data.
///
/// # Safety
/// `desc` must come from [`run_kernel`] and must not be used afterwards.
pub unsafe fn release_descriptor(desc: *mut u8) {
    if desc.is_null() {
        return;
    }
    // SAFETY: a descriptor is at least RunDescriptor::LEN readable bytes.
    let head = unsafe { core::slice::from_raw_parts(desc, RunDescriptor::LEN) };
    let data_len = read_u32(head, 8) as usize;
    // SAFETY: the allocation spans the descriptor and its data.
    unsafe { buffer::release(desc, RunDescriptor::LEN + data_len) }
}

/// Exports `alloc`, `dealloc`, `release` and `gaius_abi_version` from a
/// kernel cdylib.
#[macro_export]
macro_rules! export_runtime {
    () => {
        #[no_mangle]
        pub extern "C" fn gaius_abi_version() -> u32 {
            $crate::ABI_VERSION as u32
        }

        #[no_mangle]
        pub extern "C" fn alloc(len: u32) -> *mut u8 {
            $crate::buffer::allocate(len as usize)
        }

        /// # Safety
        /// `ptr` and `len` must describe a live buffer from `alloc`.
        #[no_mangle]
        pub unsafe extern "C" fn dealloc(ptr: *mut u8, len: u32) {
            // SAFETY: forwarded from the caller.
            unsafe { $crate::buffer::release(ptr, len as usize) }
        }

        /// # Safety
        /// `desc` must be a descriptor returned by a `run_<kind>` export.
        #[no_mangle]
        pub unsafe extern "C" fn release(desc: *mut u8) {
            // SAFETY: forwarded from the caller.
            unsafe { $crate::release_descriptor(desc) }
        }
    };
}

/// Borrows the job bytes behind a `run_<kind>` call.
///
/// # Safety
/// `ptr` and `len` must describe a readable buffer, or `len` must be 0.
pub unsafe fn job_bytes<'a>(ptr: *const u8, len: u32) -> &'a [u8] {
    if ptr.is_null() || len == 0 {
        &[]
    } else {
        // SAFETY: guaranteed by the caller.
        unsafe { core::slice::from_raw_parts(ptr, len as usize) }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use alloc::vec;

    const KIND: u16 = 0x0901;

    /// Reads a descriptor (data sits right after it) and releases it.
    fn take(desc: *mut u8) -> (u32, Vec<u8>) {
        assert!(!desc.is_null());
        // SAFETY: test-only; the descriptor and data were just produced by run_kernel.
        let head = unsafe { core::slice::from_raw_parts(desc, RunDescriptor::LEN) };
        let status = read_u32(head, 0);
        let len = read_u32(head, 8) as usize;
        let data = unsafe { core::slice::from_raw_parts(desc.add(RunDescriptor::LEN), len) }.to_vec();
        unsafe { release_descriptor(desc) };
        (status, data)
    }

    #[test]
    fn success_carries_result_header() {
        let job = JobHeader::frame(KIND, 42, &[1, 2]);
        let (status, data) = take(run_kernel(&job, KIND, |p| Ok(vec![p[0] + p[1]])));
        assert_eq!(status, 0);
        let header = ResultHeader::parse(&data).expect("result header");
        assert_eq!((header.status, header.job_id, header.payload_len), (0, 42, 1));
        assert_eq!(&data[RESULT_HEADER_LEN..], &[3]);
    }

    #[test]
    fn rejects_bad_framing() {
        let mut bad = JobHeader::frame(KIND, 1, b"xyz");
        bad[0] ^= 1;
        assert_eq!(
            take(run_kernel(&bad, KIND, |_| unreachable!())).0,
            Status::BadMagic as u32
        );

        let mut short = JobHeader::frame(KIND, 1, b"xyz");
        short.pop();
        assert_eq!(
            take(run_kernel(&short, KIND, |_| unreachable!())).0,
            Status::Truncated as u32
        );

        let other = JobHeader::frame(KIND + 1, 1, b"");
        assert_eq!(
            take(run_kernel(&other, KIND, |_| unreachable!())).0,
            Status::WrongKind as u32
        );
    }

    #[test]
    fn handler_error_becomes_message() {
        let job = JobHeader::frame(KIND, 7, &[]);
        let (status, data) = take(run_kernel(&job, KIND, |_| {
            Err(KernelError::new(Status::BadPayload, "nope"))
        }));
        assert_eq!((status, data.as_slice()), (Status::BadPayload as u32, &b"nope"[..]));
    }

    #[test]
    fn reader_reads_little_endian() {
        let bytes = [1u8, 0, 0, 0, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff];
        let mut reader = Reader::new(&bytes);
        assert_eq!(reader.u32(), Ok(1));
        assert_eq!(reader.i64(), Ok(-1));
        assert_eq!(reader.u8(), Err(Status::Truncated));
    }
}
