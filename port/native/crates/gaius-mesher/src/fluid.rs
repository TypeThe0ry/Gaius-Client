//! `FluidRenderer.tesselate`: still and flowing fluid faces, corner heights,
//! the flow direction of `FlowingFluid.getFlow` and the overlay sprite next to
//! glass and leaves.

use crate::mesher::{step, Mesher, DOWN, EAST, NORTH, SOUTH, UP, WEST};
use crate::mth::{fuzzy_equals, light_max, mth_atan2, mth_cos, mth_sin, normalize, red, scale_rgb};
use crate::output::Vertex;
use crate::table::{flags, StateInfo, FACE_EMPTY, FACE_FULL, NO_TINT};

/// `FluidRenderer.MAX_FLUID_HEIGHT`.
const MAX_FLUID_HEIGHT: f32 = 0.8888889;

/// `FluidRenderer.isFaceOccludedByState(dir, height, state)` with `face` the occluder's
/// `getFaceOcclusionShape(dir.getOpposite())`.
pub(crate) fn occluded(masks: &[[u64; 4]], dir: usize, height: f32, face: u16) -> bool {
    if face == FACE_EMPTY {
        return false;
    }
    if face == FACE_FULL {
        return dir != UP || height == 1.0;
    }
    // Shapes.blockOccludes(box(0, 0, 0, 1, height, 1), occluder, dir): on UP the fluid only
    // reaches the boundary at full height, and its boundary slice must be covered.
    if dir == UP && !fuzzy_equals(height as f64, 1.0, 1.0E-7) {
        return false;
    }
    let mask = &masks[face as usize];
    let rows = if dir <= UP {
        16
    } else {
        ((height as f64 * 16.0).ceil() as i32).clamp(0, 16) as usize
    };
    // Rows 0..rows must be fully covered (row v = 16 bits at v * 16).
    for v in 0..rows {
        let bits = (mask[v / 4] >> ((v % 4) * 16)) & 0xFFFF;
        if bits != 0xFFFF {
            return false;
        }
    }
    true
}

impl<'s> Mesher<'s, '_, '_> {
    /// `FluidRenderer.shouldRenderFace(fluid, state, dir, neighborFluid)`.
    fn fluid_face(&self, group: u8, st: &StateInfo, dir: usize, neighbor: &StateInfo) -> bool {
        // !isNeighborSameFluid && !isFaceOccludedBySelf (state's own face at full height)
        neighbor.fluid_group != group && !occluded(&self.t.masks, dir ^ 1, 1.0, st.faces[dir])
    }

    /// `FluidRenderer.getHeight(level, fluid, pos, state, fluidState)`.
    fn fluid_height(&self, group: u8, i: usize) -> f32 {
        let st = self.st(i);
        if st.fluid_group == group {
            if self.st(step(i, UP)).fluid_group == group {
                1.0
            } else {
                st.fluid_height
            }
        } else if !st.has(flags::SOLID) {
            0.0
        } else {
            -1.0
        }
    }

    /// `FluidRenderer.calculateAverageHeight(level, fluid, own, a, b, corner)`.
    fn average_height(&self, group: u8, own: f32, a: f32, b: f32, corner: usize) -> f32 {
        if b >= 1.0 || a >= 1.0 {
            return 1.0;
        }
        let mut w = [0f32; 2];
        let add = |w: &mut [f32; 2], h: f32| {
            if h >= 0.8 {
                w[0] += h * 10.0;
                w[1] += 10.0;
            } else if h >= 0.0 {
                w[0] += h;
                w[1] += 1.0;
            }
        };
        if b > 0.0 || a > 0.0 {
            let h = self.fluid_height(group, corner);
            if h >= 1.0 {
                return 1.0;
            }
            add(&mut w, h);
        }
        add(&mut w, own);
        add(&mut w, b);
        add(&mut w, a);
        w[0] / w[1]
    }

    /// `FluidRenderer.getLightCoords(level, pos)`: the brighter of pos and pos.above().
    fn fluid_light(&self, i: usize) -> i32 {
        let above = step(i, UP);
        light_max(
            self.light_coords(self.st(i), i),
            self.light_coords(self.st(above), above),
        )
    }

    /// `FlowingFluid.isSolidFace(level, pos, dir)`.
    fn solid_face(&self, group: u8, i: usize, dir: usize) -> bool {
        let st = self.st(i);
        if st.fluid_group == group {
            return false;
        }
        if dir == UP {
            return true;
        }
        if st.has(flags::ICE) {
            return false;
        }
        st.sturdy & (1 << dir) != 0
    }

    /// `FlowingFluid.getFlow(level, pos, fluidState)`, normalized.
    fn flow(&self, group: u8, i: usize, st: &StateInfo) -> (f64, f64, f64) {
        let own = st.fluid_height;
        let mut d = 0.0f64;
        let mut e = 0.0f64;
        // Direction.Plane.HORIZONTAL: NORTH, EAST, SOUTH, WEST.
        for (dir, sx, sz) in [(NORTH, 0, -1), (EAST, 1, 0), (SOUTH, 0, 1), (WEST, -1, 0)] {
            let n = step(i, dir);
            let ns = self.st(n);
            let affects = |s: &StateInfo| s.fluid_group == 0 || s.fluid_group == group;
            if !affects(ns) {
                continue;
            }
            let mut f = ns.fluid_height;
            let mut g = 0.0f32;
            if f == 0.0 {
                if !ns.has(flags::BLOCKS_FLUID_FLOW) {
                    let below = self.st(step(n, DOWN));
                    if affects(below) {
                        f = below.fluid_height;
                        if f > 0.0 {
                            g = own - (f - MAX_FLUID_HEIGHT);
                        }
                    }
                }
            } else if f > 0.0 {
                g = own - f;
            }
            if g != 0.0 {
                d += (sx as f32 * g) as f64;
                e += (sz as f32 * g) as f64;
            }
        }
        let mut v = (d, 0.0, e);
        if st.has(flags::FLUID_FALLING) {
            for dir in [NORTH, EAST, SOUTH, WEST] {
                let n = step(i, dir);
                if self.solid_face(group, n, dir) || self.solid_face(group, step(n, UP), dir) {
                    let (x, y, z) = normalize(v.0, v.1, v.2);
                    v = (x + 0.0, y + -6.0, z + 0.0);
                    break;
                }
            }
        }
        normalize(v.0, v.1, v.2)
    }

    /// `FluidState.shouldRenderBackwardUpFace(level, above)`.
    fn backward_up_face(&self, group: u8, above: usize) -> bool {
        for dx in [-1isize, 0, 1] {
            for dz in [-1isize, 0, 1] {
                let p = (above as isize + dx + dz * crate::job::REGION as isize) as usize;
                let s = self.st(p);
                if s.fluid_group != group && !s.has(flags::SOLID_RENDER) {
                    return true;
                }
            }
        }
        false
    }

    /// `FluidRenderer.addFace`: four vertices, then the reverse winding when `backface`.
    fn fluid_quad(
        &mut self,
        layer: u8,
        v: [(f32, f32, f32, f32, f32); 4],
        color: i32,
        light: i32,
        tint: i32,
        backface: bool,
    ) {
        let mk = |p: (f32, f32, f32, f32, f32)| Vertex {
            x: p.0,
            y: p.1,
            z: p.2,
            color,
            u: p.3,
            v: p.4,
            light,
            shade: 0,
        };
        let mut q = [mk(v[0]), mk(v[1]), mk(v[2]), mk(v[3])];
        let gray = fluid_shade(color, tint);
        for vert in &mut q {
            vert.shade = gray;
        }
        self.out.quad(layer, &q, tint);
        if backface {
            let r = [q[0], q[3], q[2], q[1]];
            self.out.quad(layer, &r, tint);
        }
    }

    /// `FluidRenderer.tesselate` for the fluid of the block at region index `i`.
    pub fn fluid(&mut self, i: usize, x: i32, y: i32, z: i32, st: &'s StateInfo) {
        let t = self.t;
        let group = st.fluid_group;
        let (below_i, above_i) = (step(i, DOWN), step(i, UP));
        let (north_i, south_i) = (step(i, NORTH), step(i, SOUTH));
        let (west_i, east_i) = (step(i, WEST), step(i, EAST));
        let below = self.st(below_i);
        let above = self.st(above_i);
        let north = self.st(north_i);
        let south = self.st(south_i);
        let west = self.st(west_i);
        let east = self.st(east_i);

        let render_up = above.fluid_group != group;
        let render_down =
            self.fluid_face(group, st, DOWN, below) && !occluded(&t.masks, DOWN, MAX_FLUID_HEIGHT, below.faces[UP]);
        let render_north = self.fluid_face(group, st, NORTH, north);
        let render_south = self.fluid_face(group, st, SOUTH, south);
        let render_west = self.fluid_face(group, st, WEST, west);
        let render_east = self.fluid_face(group, st, EAST, east);
        if !render_up && !render_down && !render_east && !render_west && !render_north && !render_south {
            return;
        }

        let model = &t.fluids[st.fluid_model as usize];
        let layer = model.layer;
        let (wx, wy, wz) = self.world(x, y, z);
        let tint = if model.tint_kind != NO_TINT {
            self.tinter
                .color(self.biome, model.tint_kind, model.tint_argb, wx, wy, wz)
        } else {
            -1
        };
        let card = self.cardinal;
        let own = self.fluid_height(group, i);
        let (mut ne, mut nw, mut se, mut sw);
        if own >= 1.0 {
            ne = 1.0;
            nw = 1.0;
            se = 1.0;
            sw = 1.0;
        } else {
            let hn = self.fluid_height(group, north_i);
            let hs = self.fluid_height(group, south_i);
            let he = self.fluid_height(group, east_i);
            let hw = self.fluid_height(group, west_i);
            ne = self.average_height(group, own, hn, he, step(north_i, EAST));
            nw = self.average_height(group, own, hn, hw, step(north_i, WEST));
            se = self.average_height(group, own, hs, he, step(south_i, EAST));
            sw = self.average_height(group, own, hs, hw, step(south_i, WEST));
        }
        let fx = x as f32;
        let fy = y as f32;
        let fz = z as f32;
        let bottom = if render_down { 0.001f32 } else { 0.0 };

        if render_up && !occluded(&t.masks, UP, nw.min(sw).min(se.min(ne)), above.faces[DOWN]) {
            nw -= 0.001;
            sw -= 0.001;
            se -= 0.001;
            ne -= 0.001;
            let (flow_x, _, flow_z) = self.flow(group, i, st);
            let (u0, v0, u1, v1, u2, v2, u3, v3);
            if flow_x == 0.0 && flow_z == 0.0 {
                let s = &model.still;
                u0 = s.u0;
                v0 = s.v0;
                u1 = u0;
                v1 = s.v1;
                u2 = s.u1;
                v2 = v1;
                u3 = u2;
                v3 = v0;
            } else {
                let angle = mth_atan2(flow_z, flow_x) as f32 - 1.5707964;
                let sn = mth_sin(angle as f64) * 0.25;
                let cs = mth_cos(angle as f64) * 0.25;
                let s = &model.flowing;
                u0 = s.u(0.5 + (-cs - sn));
                v0 = s.v(0.5 + (-cs + sn));
                u1 = s.u(0.5 + (-cs + sn));
                v1 = s.v(0.5 + (cs + sn));
                u2 = s.u(0.5 + (cs + sn));
                v2 = s.v(0.5 + (cs - sn));
                u3 = s.u(0.5 + (cs - sn));
                v3 = s.v(0.5 + (-cs - sn));
            }
            let light = self.fluid_light(i);
            let color = scale_rgb(tint, card[UP]);
            let backface = self.backward_up_face(group, above_i);
            self.fluid_quad(
                layer,
                [
                    (fx + 0.0, fy + nw, fz + 0.0, u0, v0),
                    (fx + 0.0, fy + sw, fz + 1.0, u1, v1),
                    (fx + 1.0, fy + se, fz + 1.0, u2, v2),
                    (fx + 1.0, fy + ne, fz + 0.0, u3, v3),
                ],
                color,
                light,
                tint,
                backface,
            );
        }

        if render_down {
            let s = &model.still;
            let light = self.fluid_light(below_i);
            let color = scale_rgb(tint, card[DOWN]);
            let yb = fy + bottom;
            self.fluid_quad(
                layer,
                [
                    (fx, yb, fz, s.u0, s.v0),
                    (fx + 1.0, yb, fz, s.u1, s.v0),
                    (fx + 1.0, yb, fz + 1.0, s.u1, s.v1),
                    (fx, yb, fz + 1.0, s.u0, s.v1),
                ],
                color,
                light,
                tint,
                false,
            );
        }

        let light = self.fluid_light(i);
        for dir in [NORTH, EAST, SOUTH, WEST] {
            let (h0, h1, x0, x1, z0, z1, render, nb) = match dir {
                NORTH => (nw, ne, fx, fx + 1.0, fz + 0.001, fz + 0.001, render_north, north),
                SOUTH => (
                    se,
                    sw,
                    fx + 1.0,
                    fx,
                    fz + 1.0 - 0.001,
                    fz + 1.0 - 0.001,
                    render_south,
                    south,
                ),
                WEST => (sw, nw, fx + 0.001, fx + 0.001, fz + 1.0, fz, render_west, west),
                _ => (
                    ne,
                    se,
                    fx + 1.0 - 0.001,
                    fx + 1.0 - 0.001,
                    fz,
                    fz + 1.0,
                    render_east,
                    east,
                ),
            };
            if !render || occluded(&t.masks, dir, h0.max(h1), nb.faces[dir ^ 1]) {
                continue;
            }
            let overlay = model.has_overlay && (nb.has(flags::HALF_TRANSPARENT) || nb.has(flags::LEAVES));
            let sprite = if overlay { &model.overlay } else { &model.flowing };
            let su0 = sprite.u(0.0);
            let su1 = sprite.u(0.5);
            let sv0 = sprite.v((1.0 - h0) * 0.5);
            let sv1 = sprite.v((1.0 - h1) * 0.5);
            let sv2 = sprite.v(0.5);
            let side = if dir == NORTH || dir == SOUTH {
                card[NORTH]
            } else {
                card[WEST]
            };
            let color = scale_rgb(tint, card[UP] * side);
            let yb = fy + bottom;
            self.fluid_quad(
                layer,
                [
                    (x0, fy + h0, z0, su0, sv0),
                    (x1, fy + h1, z1, su1, sv1),
                    (x1, yb, z1, su1, sv2),
                    (x0, yb, z0, su0, sv2),
                ],
                color,
                light,
                tint,
                !overlay,
            );
        }
    }
}

/// The gray factor of a fluid face color for the compact format: the brightness the face
/// was scaled by, recovered from the white-tinted (or tinted) color.
fn fluid_shade(color: i32, tint: i32) -> u8 {
    let reference = red(tint).max(1);
    let scaled = red(color);
    if tint == -1 {
        scaled as u8
    } else {
        ((scaled * 255 + reference / 2) / reference).clamp(0, 255) as u8
    }
}

#[cfg(test)]
mod tests {
    use super::occluded;
    use crate::mesher::{NORTH, UP};
    use crate::table::{MASK_ALL, MASK_NONE};

    #[test]
    fn occlusion_follows_block_occludes() {
        // A bottom slab face (rows 0..8 covered) on a horizontal side.
        let mut slab = MASK_NONE;
        slab[0] = u64::MAX;
        slab[1] = u64::MAX;
        let masks = [MASK_NONE, MASK_ALL, slab];
        assert!(!occluded(&masks, NORTH, 0.8888889, 0));
        assert!(occluded(&masks, NORTH, 0.8888889, 1));
        assert!(occluded(&masks, NORTH, 0.5, 2));
        assert!(!occluded(&masks, NORTH, 0.8888889, 2));
        assert!(!occluded(&masks, UP, 0.8888889, 1));
        assert!(occluded(&masks, UP, 1.0, 1));
    }
}
