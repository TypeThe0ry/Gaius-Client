pub mod job;

#[cfg(target_arch = "wasm32")]
mod exports {
    use gaius_kernel_abi::{job_bytes, kind, run_kernel};

    gaius_kernel_abi::export_runtime!();

    /// # Safety
    /// The input pointer and length must describe a job buffer obtained from alloc.
    #[no_mangle]
    pub unsafe extern "C" fn run_png_decode(ptr: *const u8, len: u32) -> *mut u8 {
        let job = unsafe { job_bytes(ptr, len) };
        run_kernel(job, kind::PNG_DECODE, super::job::run_png_decode)
    }
}
