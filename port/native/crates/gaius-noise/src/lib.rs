//! Bit-exact ports of Minecraft's random sources and world-generation noise.
//!
//! Every function mirrors the bytecode of the vanilla class it names, down to
//! the order of floating-point operations, Java's wrapping integer arithmetic,
//! saturating float-to-int casts and `Math.min` NaN/signed-zero rules. Parity
//! is checked against golden data dumped from the real client jars (see
//! `tests/fixtures.rs`).
//!
//! The noise code exists twice because 26.3 rewrote it:
//! - [`synth64`]: 1.21.11 and 26.2 (`ImprovedNoise`, octave `PerlinNoise`,
//!   double results);
//! - [`synth32`]: 26.3 (`GradientNoise` family and `NoiseStack`, float
//!   results, plus the volume fill used by chunk generation).
//!
//! Callers pick between them through [`Semantics`], which every version
//! profile declares in `port/versions/<id>.json` (`kernelSemantics`).
//!
//! Every sampler keeps a scalar reference path; [`batch`] layers the
//! many-positions-per-call entry points on top of it.
#![forbid(unsafe_code)]

pub mod batch;
pub mod java;
pub mod mth;
pub mod random;
pub mod semantics;
pub mod synth32;
pub mod synth64;

pub use random::{PositionalRandomFactory, RandomKind, RandomSource};
pub use semantics::{DensityGraph, Semantics, Synth};
