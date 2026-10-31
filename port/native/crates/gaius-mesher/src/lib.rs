//! Chunk section mesher: the profile-selected vanilla `SectionCompiler.compile`
//! (`ModelBlockRenderer`, `BlockModelLighter`, `FluidRenderer`,
//! `VisGraph`, `MeshData.sortQuads`) over flat snapshots, for the mesh
//! workers of the kernel pool.
//!
//! Two job kinds (family [`gaius_kernel_abi::kind::MESH_FAMILY`]):
//!
//! - `load_model_table` ([`kinds::LOAD_MODEL_TABLE`]): the payload is a model
//!   table ([`table`]); it replaces the table resident in this worker. Result:
//!   `u32 epoch, u32 state_count, u32 quad_count, u32 table_bytes`.
//! - `mesh_section` ([`kinds::MESH_SECTION`]): the payload is a section job
//!   ([`job`]); the result is described in [`output`]. A job whose epoch is
//!   not resident and carries no inline table answers "table missing" (a
//!   successful run), so the caller can resend it with the table attached;
//!   any worker can recover that way after a restart.
//!
//! Output (a) is byte-identical to the vanilla BLOCK buffers of each layer;
//! output (b) is a 12-byte compact vertex for a later Gaius terrain shader.
//! Greedy merging is not offered: it cannot keep the vanilla output.

pub mod job;
pub mod output;
#[doc(hidden)]
pub mod synthetic;
pub mod table;

mod fluid;
mod mesher;
mod mth;
mod rng;
mod simd;
mod tint;
mod visgraph;

include!(concat!(env!("OUT_DIR"), "/mesh_layouts.rs"));

pub use mesher::Scratch;
pub use visgraph::ALL_VISIBLE;

use gaius_kernel_abi::{KernelError, Status};
use job::SectionJob;
use table::ModelTable;

/// Job kinds of the mesh family; `port/web/kernels/mesh-job.js` uses the same values.
pub mod kinds {
    pub use gaius_kernel_abi::kind::{LOAD_MODEL_TABLE, MESH_SECTION};
}

/// One worker's mesher: the resident model table and the reusable buffers.
#[derive(Default)]
pub struct Kernel {
    table: Option<ModelTable>,
    scratch: Scratch,
}

impl Kernel {
    pub fn new() -> Kernel {
        Kernel::default()
    }

    /// Epoch of the resident table.
    pub fn epoch(&self) -> Option<u32> {
        self.table.as_ref().map(|t| t.epoch)
    }

    fn install(&mut self, bytes: &[u8]) -> Result<&ModelTable, KernelError> {
        // Drop the old table first so the peak holds a single parsed table.
        self.table = None;
        let table = ModelTable::parse(bytes)?;
        Ok(self.table.insert(table))
    }

    /// Handler of `run_load_model_table`.
    pub fn load_model_table(&mut self, payload: &[u8]) -> Result<Vec<u8>, KernelError> {
        let table = self.install(payload)?;
        let mut out = Vec::with_capacity(16);
        for v in [
            table.epoch,
            table.states.len() as u32,
            table.quads.len() as u32,
            table.memory_bytes() as u32,
        ] {
            out.extend_from_slice(&v.to_le_bytes());
        }
        Ok(out)
    }

    /// Handler of `run_mesh_section`.
    pub fn mesh_section(&mut self, payload: &[u8]) -> Result<Vec<u8>, KernelError> {
        let job = SectionJob::decode(payload)?;
        if self.epoch() != Some(job.table_epoch) {
            match job.inline_table {
                Some(bytes) => {
                    let epoch = self.install(bytes)?.epoch;
                    if epoch != job.table_epoch {
                        return Err(KernelError::new(Status::BadPayload, "inline table has another epoch"));
                    }
                }
                None => {
                    let head = output::ResultHead {
                        status: output::STATUS_TABLE_MISSING,
                        request_seq: job.request_seq,
                        section_version: job.section_version,
                        section: job.section,
                        table_epoch: self.epoch().unwrap_or(0),
                        camera: job.camera,
                        ..output::ResultHead::default()
                    };
                    return Ok(output::encode_head_only(&head));
                }
            }
        }
        let table = self.table.as_ref().expect("table installed above");
        mesher::mesh_section(table, &job, &mut self.scratch)
    }
}

#[cfg(test)]
mod tests;
