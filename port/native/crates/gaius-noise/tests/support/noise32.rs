//! Noise fixture kinds of 26.3 (float `synth`, `NoiseStack`, volume fills).

use super::json::{enc_f32, expect_param_bits, f32_of, f64_of, i32_of, Case, Outcome};
use gaius_noise::synth32::{
    create_fbm, create_for_legacy_nether_biome, BlendedNoiseParameters, DensityVolume, GradientBase, NoiseStack,
    NormalNoise, NormalNoiseParameters, Normalization, PerlinNoise, SimplexNoise, SmearedPerlinNoise,
};
use serde_json::Value;

fn volume(case: &Case) -> Result<DensityVolume, String> {
    let spec = case.param("volume")?;
    let axis = |name: &str| -> Result<[i32; 3], String> {
        let values = spec
            .get(name)
            .and_then(Value::as_array)
            .ok_or_else(|| format!("volume.{name} missing"))?;
        if values.len() != 3 {
            return Err(format!("volume.{name} needs 3 values"));
        }
        Ok([i32_of(&values[0])?, i32_of(&values[1])?, i32_of(&values[2])?])
    };
    DensityVolume::new(axis("size")?, axis("min")?, axis("step")?).ok_or_else(|| "invalid volume".to_string())
}

/// `addToVolume` on a zeroed buffer with the case's scales.
fn add_to_volume(case: &Case, fill: impl FnOnce(&mut [f32], &DensityVolume, f64, f64, f32)) -> Outcome {
    let volume = volume(case)?;
    let mut buffer = vec![0.0f32; volume.len()];
    fill(
        &mut buffer,
        &volume,
        case.f64("xz_scale")?,
        case.f64("y_scale")?,
        f32_of(case.param("amplitude")?)?,
    );
    Ok(buffer.into_iter().map(enc_f32).collect())
}

fn check_origin(case: &Case, base: &GradientBase) -> Result<(), String> {
    expect_param_bits(case, "xo", base.offset_x)?;
    expect_param_bits(case, "yo", base.offset_y)?;
    expect_param_bits(case, "zo", base.offset_z)
}

fn points3(case: &Case, get: impl Fn(f64, f64, f64) -> f32) -> Outcome {
    Ok(case
        .f64_rows(3)?
        .iter()
        .map(|a| enc_f32(get(a[0], a[1], a[2])))
        .collect())
}

fn stack_method(case: &Case, stack: &NoiseStack) -> Outcome {
    match case.str("method")? {
        "value3" => points3(case, |x, y, z| stack.get(x, y, z)),
        "volume" => add_to_volume(case, |buffer, volume, xz, y, amplitude| {
            stack.add_to_volume(buffer, volume, xz, y, amplitude)
        }),
        other => Err(format!("unknown method \"{other}\"")),
    }
}

pub fn improved_noise(case: &Case) -> Outcome {
    let mut random = case.random()?;
    match case.str("method")? {
        "perlin3" | "perlin2" | "perlin_volume" => {
            let noise = PerlinNoise::new(&mut random);
            check_origin(case, noise.base())?;
            match case.str("method")? {
                "perlin3" => points3(case, |x, y, z| noise.get(x, y, z)),
                "perlin2" => Ok(case
                    .f64_rows(2)?
                    .iter()
                    .map(|a| enc_f32(noise.get_2d(a[0], a[1])))
                    .collect()),
                _ => add_to_volume(case, |b, v, xz, y, a| noise.add_to_volume(b, v, xz, y, a)),
            }
        }
        "smeared3" | "smeared_volume" => {
            let noise = SmearedPerlinNoise::new(&mut random, case.f64("fudge_y_scale")?);
            check_origin(case, noise.base())?;
            if case.str("method")? == "smeared3" {
                points3(case, |x, y, z| noise.get(x, y, z))
            } else {
                add_to_volume(case, |b, v, xz, y, a| noise.add_to_volume(b, v, xz, y, a))
            }
        }
        other => Err(format!("unknown method \"{other}\"")),
    }
}

pub fn perlin_noise(case: &Case) -> Outcome {
    let mut random = case.random()?;
    let stack = match case.str("ctor")? {
        "fbm" => create_fbm(
            &mut random,
            case.i32("first_octave")?,
            case.f64("fudge_y_scale")?,
            case.f64("fbm_amplitude")?,
        ),
        "legacy_nether" => {
            create_for_legacy_nether_biome(&mut random, case.i32("first_octave")?, &case.f64_list("amplitudes")?)
        }
        other => return Err(format!("unknown ctor \"{other}\"")),
    };
    stack_method(case, &stack)
}

fn recipe(case: &Case) -> Result<NormalNoiseParameters, String> {
    let normalize = match case.str("normalize")? {
        "disabled" => Normalization::Disabled,
        "enabled" => Normalization::Enabled,
        "legacy" => Normalization::Legacy,
        other => return Err(format!("unknown normalize \"{other}\"")),
    };
    Ok(NormalNoiseParameters {
        base_amplitude: case.f64("base_amplitude")?,
        base_octave: case.i32("base_octave")?,
        octave_count: case.i32("octave_count")?,
        normalize,
        amplitude_modifiers: case.f64_list("amplitude_modifiers")?,
    })
}

/// Checks the derived octave plan and normalization against the recorded ones.
fn check_plan(case: &Case, noise: &NormalNoise) -> Result<(), String> {
    expect_param_bits(case, "normalization_factor", noise.normalization_factor())?;
    let recorded = case
        .param("octaves")?
        .as_array()
        .ok_or("param \"octaves\" is not an array")?;
    if recorded.len() != noise.octaves().len() {
        return Err(format!(
            "octave count: expected {}, computed {}",
            recorded.len(),
            noise.octaves().len()
        ));
    }
    for (entry, octave) in recorded.iter().zip(noise.octaves()) {
        let field = |name: &str| entry.get(name).ok_or_else(|| format!("octave entry lacks \"{name}\""));
        let index = i32_of(field("index")?)?;
        let frequency = f64_of(field("frequency")?)?;
        let amplitude = f64_of(field("amplitude")?)?;
        let seed = field("seed")?.as_str().unwrap_or_default();
        if index != octave.octave_index
            || frequency.to_bits() != octave.frequency.to_bits()
            || amplitude.to_bits() != octave.amplitude.to_bits()
            || seed != octave.seed()
        {
            return Err(format!("octave plan differs: expected {entry}, computed {octave:?}"));
        }
    }
    Ok(())
}

fn check_parity(case: &Case, params: &NormalNoiseParameters) -> Result<(), String> {
    let parity = NormalNoiseParameters::parity(case.i32("parity_first_octave")?, &case.f64_list("parity_amplitudes")?);
    let same_modifiers = parity.amplitude_modifiers.len() == params.amplitude_modifiers.len()
        && parity
            .amplitude_modifiers
            .iter()
            .zip(&params.amplitude_modifiers)
            .all(|(a, b)| a.to_bits() == b.to_bits());
    if parity.base_amplitude.to_bits() != params.base_amplitude.to_bits()
        || parity.base_octave != params.base_octave
        || parity.octave_count != params.octave_count
        || parity.normalize != params.normalize
        || !same_modifiers
    {
        return Err(format!(
            "createParity differs: recorded {params:?}, computed {parity:?}"
        ));
    }
    Ok(())
}

pub fn normal_noise(case: &Case) -> Outcome {
    let params = recipe(case)?;
    if case.params.get("noise").and_then(Value::as_str) == Some("parity") {
        check_parity(case, &params)?;
    }
    let noise = NormalNoise::new(params);
    check_plan(case, &noise)?;
    let mut random = case.random()?;
    let stack = match case.str("ctor")? {
        "create" => noise.create(&mut random),
        "legacy_nether" => noise.create_for_legacy_nether_biome(&mut random),
        other => return Err(format!("unknown ctor \"{other}\"")),
    };
    stack_method(case, &stack)
}

pub fn simplex_noise(case: &Case) -> Outcome {
    let noise = SimplexNoise::with_zero_offsets(&mut case.random()?, case.bool("zero_offset")?);
    check_origin(case, noise.base())?;
    match case.str("method")? {
        "value2" => Ok(case
            .f64_rows(2)?
            .iter()
            .map(|a| enc_f32(noise.get_2d(a[0], a[1])))
            .collect()),
        "value3" => points3(case, |x, y, z| noise.get(x, y, z)),
        other => Err(format!("unknown method \"{other}\"")),
    }
}

pub fn blended_noise(case: &Case) -> Outcome {
    let params = BlendedNoiseParameters {
        xz_scale: case.f64("xz_scale")?,
        y_scale: case.f64("y_scale")?,
        xz_factor: case.f64("xz_factor")?,
        y_factor: case.f64("y_factor")?,
        smear_scale_multiplier: case.f64("smear_scale_multiplier")?,
    };
    let sampler = params.compile(&mut case.random()?);
    match case.str("method")? {
        "value" => Ok(case
            .i32_rows(3)?
            .iter()
            .map(|a| enc_f32(sampler.sample_value(a[0], a[1], a[2])))
            .collect()),
        "volume" => {
            let volume = volume(case)?;
            let mut buffer = vec![0.0f32; volume.len()];
            sampler.sample_volume(&mut buffer, &volume);
            Ok(buffer.into_iter().map(enc_f32).collect())
        }
        other => Err(format!("unknown method \"{other}\"")),
    }
}
