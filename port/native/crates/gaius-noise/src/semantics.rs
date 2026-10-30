//! Kernel semantics: the arithmetic and data-shape choices of a Minecraft profile that the
//! kernels reproduce. Kernels never see a version id. Every profile declares its semantics
//! as `kernelSemantics` in `port/versions/<id>.json`, including the wire `code` that names
//! the tuple in job and IR headers; `build.rs` compiles those declarations into
//! [`Semantics::from_code`] and [`profiles`]. A new profile with known semantics therefore
//! needs no Rust change, and one with new semantics fails the build until a kernel
//! implements them.

use crate::mth::FloorMode;

/// The noise and surface number type of a profile.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Synth {
    /// `ImprovedNoise` / octave `PerlinNoise`, double results (`synth64`).
    F64,
    /// `GradientNoise` family and `NoiseStack`, float results and float surface math
    /// (`synth32`).
    F32,
}

/// How a profile evaluates its density functions.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum DensityGraph {
    /// `DensityFunction.compute` / `fillArray` over the noise router as `NoiseChunk` wraps it.
    Router64,
    /// Density graphs compiled to float samplers (`DensityFunctionCompiler`).
    Sampler32,
}

/// One `kernelSemantics` tuple of the version profiles, with its wire code.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct Semantics {
    /// The byte that names this tuple in job and IR headers.
    pub code: u8,
    pub floor_mode: FloorMode,
    pub synth: Synth,
    pub density_graph: DensityGraph,
    /// The generator IR version the profile's exporter writes.
    pub ir_version: u16,
}

mod table {
    use super::{DensityGraph, FloorMode, Semantics, Synth};
    include!(concat!(env!("OUT_DIR"), "/semantics_table.rs"));
}

impl Semantics {
    /// The semantics a wire code names, or `None` for a code no version profile declares.
    pub fn from_code(code: u8) -> Option<Semantics> {
        table::SEMANTICS.iter().copied().find(|s| s.code == code)
    }

    /// Every distinct semantics of the version profiles, by code.
    pub fn all() -> &'static [Semantics] {
        table::SEMANTICS
    }

    /// Float synth classes instead of the double ones.
    pub fn uses_synth32(self) -> bool {
        self.synth == Synth::F32
    }

    /// Density graphs compiled to float samplers instead of the router.
    pub fn uses_sampler32(self) -> bool {
        self.density_graph == DensityGraph::Sampler32
    }
}

/// Every version profile (`port/versions/*.json`, in file name order) with its semantics.
pub fn profiles() -> impl Iterator<Item = (&'static str, Semantics)> {
    table::PROFILES.iter().map(|&(id, code)| {
        (
            id,
            Semantics::from_code(code).expect("build.rs only lists declared codes"),
        )
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn codes_are_unique_and_profiles_resolve() {
        let all = Semantics::all();
        assert!(!all.is_empty(), "no version profile declares kernelSemantics");
        for (i, s) in all.iter().enumerate() {
            assert!(all[i + 1..].iter().all(|o| o.code != s.code), "code {} twice", s.code);
            assert_eq!(Semantics::from_code(s.code), Some(*s));
            // Each density graph runs on the matching synth classes.
            assert_eq!(s.uses_synth32(), s.uses_sampler32(), "{s:?}");
        }
        assert!(profiles().count() >= all.len());
        assert!(all.iter().all(|s| profiles().any(|(_, p)| p == *s)));
    }
}
