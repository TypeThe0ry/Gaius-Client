//! Sky and block light for one chunk column, following the vanilla light
//! engine (`net.minecraft.world.level.lighting`, identical in 26.2 and 26.3).
//!
//! What a job does, in vanilla terms:
//!
//! - first light of a generated chunk: `LevelLightEngine.propagateLightSources`
//!   (`BlockLightEngine` emitters, `SkyLightEngine` sky sources from
//!   `ChunkSkyLightSources`) followed by `runLightUpdates`
//!   ([`LightColumn::enqueue_block_sources`], [`LightColumn::enqueue_sky_sources`],
//!   [`LightColumn::propagate`]);
//! - block changes: `checkBlock` for every changed position, then
//!   `runLightUpdates` ([`LightColumn::check_block`], [`LightColumn::check_sky`]).
//!
//! The column carries a one-block ring of its neighbours so light is checked
//! against their states and stored levels at the border; light vanilla would
//! carry into a neighbour comes back as [`Outgoing`] records that the Java
//! side feeds into the vanilla engine, which owns the neighbours' storage.
//!
//! Inputs are flat and table driven: block states arrive as palette plus bit
//! storage per section ([`SectionStates`]), resolved once per palette entry
//! through the static [`LightTable`] (dampening, emission, face occlusion
//! classes exported from vanilla), into one `u16` per cell. Stored light
//! arrives and leaves as `DataLayer` nibble arrays.

#![cfg_attr(not(test), no_std)]
#![deny(unsafe_op_in_unsafe_fn)]

extern crate alloc;

pub mod column;
pub mod dir;
pub mod nibble;
pub mod section;
pub mod table;

pub use column::{flags, outgoing, ColumnSpec, Layer, LightColumn, Outgoing, Stats};
pub use nibble::LAYER_BYTES;
pub use section::SectionStates;
pub use table::{LightTable, Props};

#[cfg(test)]
mod tests;
