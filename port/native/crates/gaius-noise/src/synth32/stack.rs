//! `synth.NoiseStack`: a weighted sum of single-octave noises.
//!
//! `NoiseStack.Builder.build()` returns a `Perlin` or `SmearedPerlin`
//! subclass when every layer has that exact class; both evaluate exactly like
//! the base class, so one type covers all three.

use super::perlin::{PerlinNoise, SmearedPerlinNoise};
use super::simplex::SimplexNoise;
use super::volume::DensityVolume;

#[derive(Clone, Debug)]
pub enum LayerNoise {
    Perlin(PerlinNoise),
    SmearedPerlin(SmearedPerlinNoise),
    Simplex(SimplexNoise),
}

impl LayerNoise {
    #[inline]
    pub fn get(&self, x: f64, y: f64, z: f64) -> f32 {
        match self {
            LayerNoise::Perlin(noise) => noise.get(x, y, z),
            LayerNoise::SmearedPerlin(noise) => noise.get(x, y, z),
            LayerNoise::Simplex(noise) => noise.get(x, y, z),
        }
    }

    #[inline]
    pub fn get_2d(&self, x: f64, z: f64) -> f32 {
        match self {
            LayerNoise::Perlin(noise) => noise.get_2d(x, z),
            LayerNoise::SmearedPerlin(noise) => noise.get_2d(x, z),
            LayerNoise::Simplex(noise) => noise.get_2d(x, z),
        }
    }

    pub fn add_to_volume(&self, buffer: &mut [f32], volume: &DensityVolume, xz_scale: f64, y_scale: f64, scale: f32) {
        match self {
            LayerNoise::Perlin(noise) => noise.add_to_volume(buffer, volume, xz_scale, y_scale, scale),
            LayerNoise::SmearedPerlin(noise) => noise.add_to_volume(buffer, volume, xz_scale, y_scale, scale),
            LayerNoise::Simplex(noise) => noise.add_to_volume(buffer, volume, xz_scale, y_scale, scale),
        }
    }
}

/// `NoiseStack.Layer`.
#[derive(Clone, Debug)]
pub struct Layer {
    pub noise: LayerNoise,
    pub frequency: f64,
    pub amplitude: f32,
}

#[derive(Clone, Debug, Default)]
pub struct NoiseStack {
    layers: Vec<Layer>,
}

impl NoiseStack {
    pub fn builder() -> NoiseStackBuilder {
        NoiseStackBuilder::default()
    }

    pub fn layers(&self) -> &[Layer] {
        &self.layers
    }

    pub fn get(&self, x: f64, y: f64, z: f64) -> f32 {
        let mut value = 0.0f32;
        for layer in &self.layers {
            let f = layer.frequency;
            value += layer.amplitude * layer.noise.get(x * f, y * f, z * f);
        }
        value
    }

    pub fn get_2d(&self, x: f64, z: f64) -> f32 {
        let mut value = 0.0f32;
        for layer in &self.layers {
            let f = layer.frequency;
            value += layer.amplitude * layer.noise.get_2d(x * f, z * f);
        }
        value
    }

    /// `addToVolume(buffer, volume, xzScale, yScale, scale)`.
    pub fn add_to_volume(&self, buffer: &mut [f32], volume: &DensityVolume, xz_scale: f64, y_scale: f64, scale: f32) {
        for layer in &self.layers {
            let f = layer.frequency;
            layer
                .noise
                .add_to_volume(buffer, volume, xz_scale * f, y_scale * f, scale * layer.amplitude);
        }
    }
}

#[derive(Clone, Debug, Default)]
pub struct NoiseStackBuilder {
    layers: Vec<Layer>,
}

impl NoiseStackBuilder {
    pub fn add(mut self, noise: LayerNoise, frequency: f64, amplitude: f32) -> Self {
        self.layers.push(Layer {
            noise,
            frequency,
            amplitude,
        });
        self
    }

    pub fn add_stack(mut self, stack: &NoiseStack, frequency: f64, amplitude: f32) -> Self {
        for layer in &stack.layers {
            self.layers.push(Layer {
                noise: layer.noise.clone(),
                frequency: layer.frequency * frequency,
                amplitude: layer.amplitude * amplitude,
            });
        }
        self
    }

    pub fn build(self) -> NoiseStack {
        NoiseStack { layers: self.layers }
    }
}
