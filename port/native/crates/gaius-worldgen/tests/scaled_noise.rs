use gaius_noise::{DensityGraph, Semantics};
use gaius_worldgen::ir::{example::overworld, Ir, Node, Op, Role};
use gaius_worldgen::Generator;

fn scaled_ir(semantics: Semantics, value: f64) -> Ir {
    let mut ir = overworld(semantics, 7);
    let scale = ir.nodes.len() as u32;
    ir.nodes.push(Node {
        op: Op::Const(value),
        axes: 0,
        min: 0.5,
        max: 3.0,
    });
    let root = ir.nodes.len() as u32;
    ir.nodes.push(Node {
        op: Op::ScaledNoise { noise: 0, scale },
        axes: 7,
        min: 0.0,
        max: 10.0,
    });
    ir.roots.nodes[Role::FinalDensity as usize] = Some(root);
    ir
}

#[test]
fn scaled_noise_round_trips_and_declines_float_sampler() {
    for &semantics in Semantics::all() {
        let ir = scaled_ir(semantics, 1.5);
        let decoded = Ir::decode(&ir.encode()).unwrap();
        assert_eq!(decoded, ir);
        let loaded = Generator::load(&ir.encode());
        assert_eq!(loaded.is_ok(), semantics.density_graph == DensityGraph::Router64);
    }
}

#[test]
fn scaled_noise_rejects_unsafe_scales_even_with_positive_declared_bounds() {
    for value in [0.0, -1.0, f64::NAN, f64::INFINITY] {
        let ir = scaled_ir(Semantics::all()[0], value);
        assert!(Ir::decode(&ir.encode()).is_err());
    }
}
