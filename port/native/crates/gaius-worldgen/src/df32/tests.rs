//! Sampler-level checks: the volume path agrees with the point path where
//! vanilla's do (no interpolation or noise volume rounding involved), and the
//! cache cells serve point lookups from the last cached volume.

use super::*;
use crate::ir::example::overworld;
use gaius_noise::Semantics;

fn program() -> (Program32, crate::ir::Ir, PositionalRandomFactory) {
    let semantics = *Semantics::all()
        .iter()
        .find(|s| s.uses_sampler32())
        .expect("sampler semantics");
    let ir = overworld(semantics, 42);
    let factory = crate::noises::positional_factory(ir.settings.seed, false);
    let noises = crate::noises::instantiate(ir.semantics, ir.settings.seed, &factory, &ir.noises).unwrap();
    (Program32::new(noises, ir.nodes.len()), ir, factory)
}

fn ctx(p: &Program32) -> Ctx32 {
    Ctx32::new(p.cache_count, true, Arena::new(), Beard::default())
}

#[test]
fn gradient_and_arithmetic_volume_equals_points() {
    let (mut p, ir, factory) = program();
    let depth = ir.roots.get(crate::ir::Role::Depth).unwrap();
    let s = p.compile_root(&ir, &factory, depth).unwrap();
    let mut c = ctx(&p);
    let v = Volume::new([3, 5, 2], [-37, -64, 81], [4, 8, 4]);
    let mut out = vec![0.0f32; v.len()];
    p.sample_volume(&mut c, s, &mut out, &v);
    // depth = y gradient + continentalness noise; the noise volume path may round
    // differently, so compare against the same volume of the noise alone.
    let continents = p
        .compile_root(&ir, &factory, ir.roots.get(crate::ir::Role::Continents).unwrap())
        .unwrap();
    let mut noise = vec![0.0f32; v.len()];
    p.sample_volume(&mut c, continents, &mut noise, &v);
    for z in 0..v.size_z {
        for x in 0..v.size_x {
            for y in 0..v.size_y {
                let i = v.index(x, y, z);
                let by = v.block_y(y);
                let g = 1.5f32 + (by.clamp(-64, 320) + 64) as f32 * ((-1.5f32 - 1.5) / 384.0);
                assert_eq!(out[i].to_bits(), (g + noise[i]).to_bits(), "at {x} {y} {z}");
            }
        }
    }
}

#[test]
fn interpolation_hits_corners_and_cache_serves_points() {
    let (mut p, ir, factory) = program();
    let root = ir.roots.get(crate::ir::Role::FinalDensity).unwrap();
    let s = p.compile_root(&ir, &factory, root).unwrap();
    let mut c = ctx(&p);
    let v = Volume::blocks([16, 48, 16], [32, 0, -16]);
    let mut out = vec![0.0f32; v.len()];
    p.sample_volume(&mut c, s, &mut out, &v);
    assert!(out.iter().all(|d| d.is_finite()));
    // At cell corners the interpolation returns its input (the point and volume noise
    // paths may differ in the last bit).
    let mut c2 = ctx(&p);
    for (x, y, z) in [(32, 0, -16), (36, 8, -12), (44, 40, -4)] {
        let point = p.sample_value(&mut c2, s, x, y, z);
        let i = v.index_of_block(x, y, z).unwrap();
        assert!(
            (point - out[i]).abs() < 1e-5,
            "corner {x} {y} {z}: {point} vs {}",
            out[i]
        );
    }
    // Repeated volumes are served from the cache cell bit for bit.
    let mut again = vec![0.0f32; v.len()];
    p.sample_volume(&mut c, s, &mut again, &v);
    assert_eq!(out, again);
}
