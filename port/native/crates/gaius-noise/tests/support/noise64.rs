//! Noise fixture kinds of 1.21.11 and 26.2 (double-precision `synth`).

use super::json::{enc_f64, expect_param_bits, Case, Outcome};
use gaius_noise::synth64::{BlendedNoise, ImprovedNoise, NormalNoise, PerlinNoise, SimplexNoise};
use gaius_noise::Semantics;

pub fn improved_noise(semantics: Semantics, case: &Case) -> Outcome {
    let noise = ImprovedNoise::new(&mut case.random()?, semantics.floor_mode);
    expect_param_bits(case, "xo", noise.xo)?;
    expect_param_bits(case, "yo", noise.yo)?;
    expect_param_bits(case, "zo", noise.zo)?;
    match case.str("method")? {
        "noise3" => Ok(case
            .f64_rows(3)?
            .iter()
            .map(|a| enc_f64(noise.noise(a[0], a[1], a[2])))
            .collect()),
        "noise5" => Ok(case
            .f64_rows(5)?
            .iter()
            .map(|a| enc_f64(noise.noise_smeared(a[0], a[1], a[2], a[3], a[4])))
            .collect()),
        other => Err(format!("unknown method \"{other}\"")),
    }
}

pub fn perlin_noise(semantics: Semantics, case: &Case) -> Outcome {
    let floor = semantics.floor_mode;
    let mut random = case.random()?;
    let noise = match case.str("ctor")? {
        "create" => PerlinNoise::create(
            &mut random,
            case.i32("first_octave")?,
            &case.f64_list("amplitudes")?,
            floor,
        ),
        "legacy_blended" => {
            PerlinNoise::create_legacy_for_blended_noise(&mut random, &case.i32_list("octaves")?, floor)
        }
        "legacy_nether" => PerlinNoise::create_legacy_for_legacy_nether_biome(
            &mut random,
            case.i32("first_octave")?,
            &case.f64_list("amplitudes")?,
            floor,
        ),
        other => return Err(format!("unknown ctor \"{other}\"")),
    };
    Ok(case
        .f64_rows(3)?
        .iter()
        .map(|a| enc_f64(noise.get_value(a[0], a[1], a[2])))
        .collect())
}

pub fn normal_noise(semantics: Semantics, case: &Case) -> Outcome {
    let floor = semantics.floor_mode;
    let mut random = case.random()?;
    let first_octave = case.i32("first_octave")?;
    let amplitudes = case.f64_list("amplitudes")?;
    let noise = match case.str("ctor")? {
        "create" => NormalNoise::create(&mut random, first_octave, &amplitudes, floor),
        "legacy_nether" => NormalNoise::create_legacy_nether_biome(&mut random, first_octave, &amplitudes, floor),
        other => return Err(format!("unknown ctor \"{other}\"")),
    };
    Ok(case
        .f64_rows(3)?
        .iter()
        .map(|a| enc_f64(noise.get_value(a[0], a[1], a[2])))
        .collect())
}

pub fn simplex_noise(semantics: Semantics, case: &Case) -> Outcome {
    let noise = SimplexNoise::new(&mut case.random()?, semantics.floor_mode);
    expect_param_bits(case, "xo", noise.xo)?;
    expect_param_bits(case, "yo", noise.yo)?;
    expect_param_bits(case, "zo", noise.zo)?;
    match case.str("method")? {
        "value2" => Ok(case
            .f64_rows(2)?
            .iter()
            .map(|a| enc_f64(noise.get_value_2d(a[0], a[1])))
            .collect()),
        "value3" => Ok(case
            .f64_rows(3)?
            .iter()
            .map(|a| enc_f64(noise.get_value_3d(a[0], a[1], a[2])))
            .collect()),
        other => Err(format!("unknown method \"{other}\"")),
    }
}

pub fn blended_noise(semantics: Semantics, case: &Case) -> Outcome {
    if case.str("method")? != "compute" {
        return Err(format!("unknown method \"{}\"", case.str("method")?));
    }
    let noise = BlendedNoise::new(
        &mut case.random()?,
        case.f64("xz_scale")?,
        case.f64("y_scale")?,
        case.f64("xz_factor")?,
        case.f64("y_factor")?,
        case.f64("smear_scale_multiplier")?,
        semantics.floor_mode,
    );
    Ok(case
        .i32_rows(3)?
        .iter()
        .map(|a| enc_f64(noise.compute(a[0], a[1], a[2])))
        .collect())
}
