//! wasm32 entry points for `gaius-noise`, framed by `gaius-kernel-abi`.
//!
//! Exports (wasm32 only): `memory`, `alloc`, `dealloc`, `release`,
//! `gaius_abi_version` and `run_noise_points` (job kind
//! [`gaius_kernel_abi::kind::NOISE_POINTS`], payload in [`job`]).

pub mod job;

#[cfg(target_arch = "wasm32")]
mod exports {
    use gaius_kernel_abi::{job_bytes, kind, run_kernel};

    gaius_kernel_abi::export_runtime!();

    /// # Safety
    /// `ptr` and `len` must describe a job buffer obtained from `alloc`.
    #[no_mangle]
    pub unsafe extern "C" fn run_noise_points(ptr: *const u8, len: u32) -> *mut u8 {
        // SAFETY: forwarded from the caller.
        let job = unsafe { job_bytes(ptr, len) };
        run_kernel(job, kind::NOISE_POINTS, super::job::run_noise_points)
    }
}
