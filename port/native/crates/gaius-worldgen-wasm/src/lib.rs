//! wasm32 entry points for `gaius-worldgen`, framed by `gaius-kernel-abi`.
//!
//! Exports (wasm32 only): `memory`, `alloc`, `dealloc`, `release`,
//! `gaius_abi_version`, and one `run_<kind>` per job:
//!
//! | export               | job kind | payload (see [`job`])                     |
//! | -------------------- | -------- | ----------------------------------------- |
//! | `run_load_generator` | `0x0401` | generator IR, memory budget               |
//! | `run_biomes`         | `0x0402` | chunk position                            |
//! | `run_terrain`        | `0x0403` | chunk position, flags, beardifier inputs, neighbour biomes |
//! | `run_surface`        | `0x0404` | a filled chunk to run the surface rules on, neighbour biomes |
//!
//! Generators stay loaded in the instance between jobs; `reset` is not
//! exported, so the kernel worker keeps the instance (and the generators) alive.
//! Two builds exist (`port/native/build-worldgen-wasm.sh`): `simd128` and a
//! baseline without it; the page picks one by feature detection.

pub mod job;
pub mod ore_job;

#[cfg(any(target_arch = "wasm32", feature = "native-host"))]
mod exports {
    use super::job;
    use gaius_kernel_abi::{job_bytes, run_kernel};

    gaius_kernel_abi::export_runtime!();

    /// # Safety
    /// The buffer must be an allocation returned by this runtime.
    #[no_mangle]
    pub unsafe extern "C" fn run_ore(ptr: *const u8, len: u32) -> *mut u8 {
        let bytes = unsafe { job_bytes(ptr, len) };
        run_kernel(bytes, gaius_kernel_abi::kind::ORE, super::ore_job::run)
    }

    /// # Safety
    /// `ptr` and `len` must describe a job buffer obtained from `alloc`.
    #[no_mangle]
    pub unsafe extern "C" fn run_load_generator(ptr: *const u8, len: u32) -> *mut u8 {
        // SAFETY: forwarded from the caller.
        let bytes = unsafe { job_bytes(ptr, len) };
        run_kernel(bytes, job::KIND_LOAD_GENERATOR, job::load_generator)
    }

    /// # Safety
    /// `ptr` and `len` must describe a job buffer obtained from `alloc`.
    #[no_mangle]
    pub unsafe extern "C" fn run_biomes(ptr: *const u8, len: u32) -> *mut u8 {
        // SAFETY: forwarded from the caller.
        let bytes = unsafe { job_bytes(ptr, len) };
        run_kernel(bytes, job::KIND_BIOMES, job::biomes)
    }

    /// # Safety
    /// `ptr` and `len` must describe a job buffer obtained from `alloc`.
    #[no_mangle]
    pub unsafe extern "C" fn run_terrain(ptr: *const u8, len: u32) -> *mut u8 {
        // SAFETY: forwarded from the caller.
        let bytes = unsafe { job_bytes(ptr, len) };
        run_kernel(bytes, job::KIND_TERRAIN, job::terrain)
    }

    /// # Safety
    /// `ptr` and `len` must describe a job buffer obtained from `alloc`.
    #[no_mangle]
    pub unsafe extern "C" fn run_surface(ptr: *const u8, len: u32) -> *mut u8 {
        // SAFETY: forwarded from the caller.
        let bytes = unsafe { job_bytes(ptr, len) };
        run_kernel(bytes, job::KIND_SURFACE, job::surface)
    }
}
