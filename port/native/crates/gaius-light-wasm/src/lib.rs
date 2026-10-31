//! wasm32 entry point for `gaius-light`, framed by `gaius-kernel-abi`.
//!
//! Exports (wasm32 only): `memory`, `alloc`, `dealloc`, `release`,
//! `gaius_abi_version` and `run_light_column` (job kind [`job::LIGHT_COLUMN`],
//! payload in [`job`]).
//!
//! The instance keeps one [`job::Kernel`] alive between jobs: the column
//! buffers (about 0.6 MB for a 384-block world) and the decoded light table
//! are reused, so a job allocates nothing but its result.
//!
//! Two builds ship: `+simd128` (nibble packing in SIMD) and a baseline build
//! without SIMD for engines that lack it; `port/web/kernels/light-job.js`
//! picks one by feature detection.

pub mod job;

#[cfg(target_arch = "wasm32")]
mod exports {
    use core::cell::RefCell;
    use gaius_kernel_abi::{job_bytes, run_kernel};

    gaius_kernel_abi::export_runtime!();

    std::thread_local! {
        static KERNEL: RefCell<super::job::Kernel> = RefCell::new(super::job::Kernel::new());
    }

    /// # Safety
    /// `ptr` and `len` must describe a job buffer obtained from `alloc`.
    #[no_mangle]
    pub unsafe extern "C" fn run_light_column(ptr: *const u8, len: u32) -> *mut u8 {
        // SAFETY: forwarded from the caller.
        let job = unsafe { job_bytes(ptr, len) };
        KERNEL.with(|kernel| {
            run_kernel(job, super::job::LIGHT_COLUMN, |payload| {
                kernel.borrow_mut().run(payload)
            })
        })
    }

    /// Drops the cached light table and shrinks the column buffers (memory
    /// pressure; the next job rebuilds them).
    #[no_mangle]
    pub extern "C" fn light_trim() {
        KERNEL.with(|kernel| *kernel.borrow_mut() = super::job::Kernel::new());
    }
}
