//! Random-source and `Mth` fixture kinds.

use super::json::{enc_f32, enc_f64, enc_i32, enc_i64, f32_of, Case, Outcome};
use gaius_noise::mth::{self, float};
use gaius_noise::random::{LegacyRandom, XoroshiroRandom};
use gaius_noise::{synth32, synth64, RandomSource, Semantics};

fn xoroshiro(case: &Case) -> Result<XoroshiroRandom, String> {
    match case.str("ctor")? {
        "seed" => Ok(XoroshiroRandom::new(case.i64("seed")?)),
        "seed128" => Ok(XoroshiroRandom::from_parts(case.i64("seed_lo")?, case.i64("seed_hi")?)),
        other => Err(format!("unknown ctor \"{other}\"")),
    }
}

pub fn xoroshiro_next_long(case: &Case) -> Outcome {
    let mut random = xoroshiro(case)?;
    Ok(case.outputs.iter().map(|_| enc_i64(random.next_long())).collect())
}

pub fn xoroshiro_next_double(case: &Case) -> Outcome {
    let mut random = xoroshiro(case)?;
    Ok(case.outputs.iter().map(|_| enc_f64(random.next_double())).collect())
}

pub fn legacy_next_int_bound(case: &Case) -> Outcome {
    let mut random = LegacyRandom::new(case.i64("seed")?);
    let rows = case.i32_rows(1)?;
    if rows.iter().any(|row| row[0] <= 0) {
        return Err("bounds must be positive".into());
    }
    Ok(rows.iter().map(|row| enc_i32(random.next_int_bound(row[0]))).collect())
}

pub fn legacy_next_double(case: &Case) -> Outcome {
    let mut random = LegacyRandom::new(case.i64("seed")?);
    Ok(case.outputs.iter().map(|_| enc_f64(random.next_double())).collect())
}

pub fn positional_from_hash(case: &Case) -> Outcome {
    let mut base = case.random()?;
    let mut random: RandomSource = base.fork_positional().from_hash_of(case.str("name")?);
    Ok(case.outputs.iter().map(|_| enc_i64(random.next_long())).collect())
}

pub fn mth(semantics: Semantics, case: &Case) -> Outcome {
    let name = case.str("fn")?;
    match case.str("precision")? {
        "f64" => mth_f64(semantics, name, case),
        "f32" => mth_f32(semantics, name, case),
        other => Err(format!("unknown precision \"{other}\"")),
    }
}

fn mth_f64(semantics: Semantics, name: &str, case: &Case) -> Outcome {
    let floor = semantics.floor_mode;
    let arity = match name {
        "floor" | "lfloor" | "smoothstep" | "wrap" => 1,
        "lerp" => 3,
        "lerp2" => 6,
        "lerp3" => 11,
        "clampedMap" | "map" => 5,
        other => return Err(format!("unknown f64 fn \"{other}\"")),
    };
    let rows = case.f64_rows(arity)?;
    Ok(rows
        .iter()
        .map(|a| match name {
            "floor" => enc_i32(floor.floor(a[0])),
            "lfloor" => enc_i64(floor.lfloor(a[0])),
            "smoothstep" => enc_f64(mth::smoothstep(a[0])),
            "wrap" if semantics.uses_synth32() => enc_f64(synth32::wrap(a[0])),
            "wrap" => enc_f64(synth64::wrap(floor, a[0])),
            "lerp" => enc_f64(mth::lerp(a[0], a[1], a[2])),
            "lerp2" => enc_f64(mth::lerp2(a[0], a[1], a[2], a[3], a[4], a[5])),
            "lerp3" => enc_f64(mth::lerp3(
                a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], a[8], a[9], a[10],
            )),
            "clampedMap" => enc_f64(mth::clamped_map(a[0], a[1], a[2], a[3], a[4])),
            _ => enc_f64(mth::map(a[0], a[1], a[2], a[3], a[4])),
        })
        .collect())
}

fn mth_f32(semantics: Semantics, name: &str, case: &Case) -> Outcome {
    let arity = match name {
        "floor" | "smoothstep" => 1,
        "lerp" => 3,
        "lerp2" => 6,
        "lerp3" => 11,
        "clampedMap" | "map" => 5,
        other => return Err(format!("unknown f32 fn \"{other}\"")),
    };
    let mut rows = Vec::new();
    for row in &case.inputs {
        if row.len() != arity {
            return Err(format!("expected {arity} inputs per row, got {}", row.len()));
        }
        rows.push(row.iter().map(f32_of).collect::<Result<Vec<f32>, _>>()?);
    }
    Ok(rows
        .iter()
        .map(|a| match name {
            "floor" => enc_i32(semantics.floor_mode.floor_f32(a[0])),
            "smoothstep" => enc_f32(float::smoothstep(a[0])),
            "lerp" => enc_f32(float::lerp(a[0], a[1], a[2])),
            "lerp2" => enc_f32(float::lerp2(a[0], a[1], a[2], a[3], a[4], a[5])),
            "lerp3" => enc_f32(float::lerp3(
                a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], a[8], a[9], a[10],
            )),
            "clampedMap" => enc_f32(float::clamped_map(a[0], a[1], a[2], a[3], a[4])),
            _ => enc_f32(float::map(a[0], a[1], a[2], a[3], a[4])),
        })
        .collect())
}
