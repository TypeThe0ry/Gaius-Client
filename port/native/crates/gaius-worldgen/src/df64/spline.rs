//! Pre-26.3 `DensityFunctions.Spline`: `CubicSpline` sampled with
//! `Coordinate.apply = (float) function.compute(context)`. Coordinates are pure
//! functions of the context, so each distinct one is computed once per sample.

use super::{Compiler, Ctx64, Did, Kind, Program64};
use crate::ir::{IrError, SplineDef};
use crate::java::binary_search;
use gaius_noise::mth::float::lerp;

#[derive(Clone, Debug)]
enum Node {
    Const(f32),
    Multi {
        coordinate: u16,
        locations: Box<[f32]>,
        derivatives: Box<[f32]>,
        values: Box<[u32]>,
    },
}

#[derive(Clone, Debug)]
pub struct SplineFn {
    nodes: Vec<Node>,
    root: u32,
    coordinates: Vec<Did>,
}

pub(super) fn compile(p: &mut Program64, c: &Compiler, root: u32) -> Result<SplineFn, IrError> {
    struct Builder {
        nodes: Vec<Node>,
        memo: Vec<Option<u32>>,
        coordinate_nodes: Vec<u32>,
    }
    fn walk(b: &mut Builder, c: &Compiler, index: u32) -> u32 {
        if let Some(n) = b.memo[index as usize] {
            return n;
        }
        let node = match &c.splines[index as usize] {
            SplineDef::Constant(v) => Node::Const(*v),
            SplineDef::Multipoint {
                coordinate,
                locations,
                derivatives,
                values,
            } => {
                let slot = match b.coordinate_nodes.iter().position(|&n| n == *coordinate) {
                    Some(s) => s,
                    None => {
                        b.coordinate_nodes.push(*coordinate);
                        b.coordinate_nodes.len() - 1
                    }
                };
                let mapped: Vec<u32> = values.iter().map(|&v| walk(b, c, v)).collect();
                Node::Multi {
                    coordinate: slot as u16,
                    locations: locations.clone().into(),
                    derivatives: derivatives.clone().into(),
                    values: mapped.into(),
                }
            }
        };
        b.nodes.push(node);
        let id = (b.nodes.len() - 1) as u32;
        b.memo[index as usize] = Some(id);
        id
    }
    let mut b = Builder {
        nodes: Vec::new(),
        memo: vec![None; c.splines.len()],
        coordinate_nodes: Vec::new(),
    };
    let root = walk(&mut b, c, root);
    let coordinates = b
        .coordinate_nodes
        .iter()
        .map(|&n| p.compile(c, n))
        .collect::<Result<Vec<_>, _>>()?;
    Ok(SplineFn {
        nodes: b.nodes,
        root,
        coordinates,
    })
}

#[inline]
fn linear_extend(input: f32, locations: &[f32], value: f32, derivatives: &[f32], index: usize) -> f32 {
    let derivative = derivatives[index];
    if derivative == 0.0 {
        value
    } else {
        value + derivative * (input - locations[index])
    }
}

fn sample(f: &SplineFn, node: u32, input: &mut dyn FnMut(usize) -> f32) -> f32 {
    match &f.nodes[node as usize] {
        Node::Const(v) => *v,
        Node::Multi {
            coordinate,
            locations,
            derivatives,
            values,
        } => {
            let x = input(*coordinate as usize);
            let n = locations.len() as i32;
            let start = binary_search(0, n, |i| x < locations[i as usize]) - 1;
            let last = (n - 1) as usize;
            if start < 0 {
                let v = sample(f, values[0], input);
                linear_extend(x, locations, v, derivatives, 0)
            } else if start as usize == last {
                let v = sample(f, values[last], input);
                linear_extend(x, locations, v, derivatives, last)
            } else {
                let s = start as usize;
                let x1 = locations[s];
                let x2 = locations[s + 1];
                let t = (x - x1) / (x2 - x1);
                let d1 = derivatives[s];
                let d2 = derivatives[s + 1];
                let y1 = sample(f, values[s], input);
                let y2 = sample(f, values[s + 1], input);
                let a = d1 * (x2 - x1) - (y2 - y1);
                let b = -d2 * (x2 - x1) + (y2 - y1);
                lerp(t, y1, y2) + t * (1.0 - t) * lerp(t, a, b)
            }
        }
    }
}

pub(super) fn compute(p: &Program64, ctx: &mut Ctx64, f: &SplineFn, x: i32, y: i32, z: i32, kind: Kind) -> f64 {
    let mut cached = [f32::NAN; 16];
    let mut known = [false; 16];
    let mut spill: Vec<Option<f32>> = if f.coordinates.len() > cached.len() {
        vec![None; f.coordinates.len()]
    } else {
        Vec::new()
    };
    let mut input = |k: usize| -> f32 {
        if spill.is_empty() {
            if !known[k] {
                cached[k] = p.compute(ctx, f.coordinates[k], x, y, z, kind) as f32;
                known[k] = true;
            }
            cached[k]
        } else {
            *spill[k].get_or_insert_with(|| p.compute(ctx, f.coordinates[k], x, y, z, kind) as f32)
        }
    };
    sample(f, f.root, &mut input) as f64
}
