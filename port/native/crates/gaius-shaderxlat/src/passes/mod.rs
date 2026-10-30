//! SPIR-V rewrites that make vanilla modules acceptable to naga and WGSL.
//! [`crate::patch_spirv`] runs [`strip`], [`point_size`], [`texel_buffer`],
//! [`split_sampler`], [`output_array`] and, when asked, [`depth_remap`], in
//! this order (texel buffers must go before the sampler split, which would
//! otherwise pair them with a sampler). [`dead_input`] and [`attribute_type`]
//! belong to the GLSL front-end's lenient link ([`crate::frontend`]).

pub mod attribute_type;
pub mod dead_input;
pub mod depth_remap;
pub mod output_array;
pub mod point_size;
pub mod split_sampler;
pub mod strip;
pub mod texel_buffer;
