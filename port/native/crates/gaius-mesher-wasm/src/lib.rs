//! wasm32 entry points for `gaius-mesher`, framed by `gaius-kernel-abi`.
//!
//! Exports (wasm32 only): `memory`, `alloc`, `dealloc`, `release`,
//! `gaius_abi_version`, `run_load_model_table` (job kind
//! [`gaius_mesher::kinds::LOAD_MODEL_TABLE`]), `run_mesh_section` (job kind
//! [`gaius_mesher::kinds::MESH_SECTION`]) and `mesh_trim`.
//!
//! The instance keeps one [`gaius_mesher::Kernel`] alive between jobs: the
//! model table of the current resource epoch stays resident in this worker's
//! linear memory, and the region, biome and output buffers are reused, so a
//! job allocates little beyond its result. A fresh or restarted instance
//! answers "table missing" until a job brings the table along (see
//! `port/web/kernels/mesh-job.js`).
//!
//! Two builds ship: `+simd128` (air skipping, weighted smooth lighting and
//! translucency sort keys in SIMD) and a baseline build without SIMD; both
//! emit identical bytes, and the page picks one by feature detection.

pub use gaius_mesher::{kinds, Kernel};

#[cfg(target_arch = "wasm32")]
mod exports {
    use core::cell::RefCell;
    use gaius_kernel_abi::{job_bytes, run_kernel};
    use gaius_mesher::{kinds, Kernel};

    gaius_kernel_abi::export_runtime!();

    std::thread_local! {
        static KERNEL: RefCell<Kernel> = RefCell::new(Kernel::new());
    }

    /// # Safety
    /// `ptr` and `len` must describe a job buffer obtained from `alloc`.
    #[no_mangle]
    pub unsafe extern "C" fn run_load_model_table(ptr: *const u8, len: u32) -> *mut u8 {
        // SAFETY: forwarded from the caller.
        let job = unsafe { job_bytes(ptr, len) };
        KERNEL.with(|kernel| {
            run_kernel(job, kinds::LOAD_MODEL_TABLE, |payload| {
                kernel.borrow_mut().load_model_table(payload)
            })
        })
    }

    /// # Safety
    /// `ptr` and `len` must describe a job buffer obtained from `alloc`.
    #[no_mangle]
    pub unsafe extern "C" fn run_mesh_section(ptr: *const u8, len: u32) -> *mut u8 {
        // SAFETY: forwarded from the caller.
        let job = unsafe { job_bytes(ptr, len) };
        KERNEL.with(|kernel| {
            run_kernel(job, kinds::MESH_SECTION, |payload| {
                kernel.borrow_mut().mesh_section(payload)
            })
        })
    }

    /// Drops the resident table and every buffer (memory pressure; the next mesh job
    /// answers "table missing" and is resent with the table).
    #[no_mangle]
    pub extern "C" fn mesh_trim() {
        KERNEL.with(|kernel| *kernel.borrow_mut() = Kernel::new());
    }
}
