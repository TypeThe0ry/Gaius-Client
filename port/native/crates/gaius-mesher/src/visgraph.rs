//! `VisGraph`: which section faces see each other through non-opaque blocks.
//!
//! The result is the `VisibilitySet` bit set as one `u64`: bit `a + 6 * b`
//! (Direction ordinals) is set when faces `a` and `b` are connected; the set is
//! symmetric, so `BitSet.valueOf(new long[] {bits})` rebuilds it.

/// Every visibility bit (`VisibilitySet.setAll(true)`).
pub const ALL_VISIBLE: u64 = (1 << 36) - 1;

pub struct VisGraph {
    opaque: [u64; 64],
    empty: u32,
    queue: Vec<u16>,
}

impl Default for VisGraph {
    fn default() -> Self {
        VisGraph {
            opaque: [0; 64],
            empty: 4096,
            queue: Vec::with_capacity(4096),
        }
    }
}

/// `VisGraph.getIndex(x, y, z)`: x | z << 4 | y << 8.
#[inline]
fn index(x: i32, y: i32, z: i32) -> usize {
    (x | (z << 4) | (y << 8)) as usize
}

impl VisGraph {
    pub fn reset(&mut self) {
        self.opaque = [0; 64];
        self.empty = 4096;
    }

    #[inline]
    pub fn set_opaque(&mut self, x: i32, y: i32, z: i32) {
        let i = index(x, y, z);
        self.opaque[i >> 6] |= 1 << (i & 63);
        self.empty -= 1;
    }

    #[inline]
    fn get(&self, i: usize) -> bool {
        self.opaque[i >> 6] & (1 << (i & 63)) != 0
    }

    #[inline]
    fn mark(&mut self, i: usize) {
        self.opaque[i >> 6] |= 1 << (i & 63);
    }

    /// `VisGraph.resolve()`; consumes the opaque set (flood fills mark it, as vanilla does).
    pub fn resolve(&mut self) -> u64 {
        if 4096 - self.empty < 256 {
            return ALL_VISIBLE;
        }
        if self.empty == 0 {
            return 0;
        }
        let mut bits = 0u64;
        for i in 0..4096usize {
            let (x, y, z) = (i & 15, i >> 8, (i >> 4) & 15);
            let edge = x == 0 || x == 15 || y == 0 || y == 15 || z == 0 || z == 15;
            if edge && !self.get(i) {
                let faces = self.flood_fill(i);
                for a in 0..6 {
                    if faces & (1 << a) == 0 {
                        continue;
                    }
                    for b in 0..6 {
                        if faces & (1 << b) != 0 {
                            bits |= 1 << (a + 6 * b);
                            bits |= 1 << (b + 6 * a);
                        }
                    }
                }
            }
        }
        bits
    }

    /// Breadth-first fill from `start`; returns the faces it touched (bit per Direction ordinal).
    fn flood_fill(&mut self, start: usize) -> u8 {
        let mut faces = 0u8;
        self.queue.clear();
        self.queue.push(start as u16);
        self.mark(start);
        let mut head = 0;
        while head < self.queue.len() {
            let i = self.queue[head] as usize;
            head += 1;
            let (x, y, z) = (i & 15, i >> 8, (i >> 4) & 15);
            faces |= match x {
                0 => 1 << 4,
                15 => 1 << 5,
                _ => 0,
            } | match y {
                0 => 1 << 0,
                15 => 1 << 1,
                _ => 0,
            } | match z {
                0 => 1 << 2,
                15 => 1 << 3,
                _ => 0,
            };
            // DOWN, UP, NORTH, SOUTH, WEST, EAST
            let neighbors = [
                (y > 0).then(|| i - 256),
                (y < 15).then(|| i + 256),
                (z > 0).then(|| i - 16),
                (z < 15).then(|| i + 16),
                (x > 0).then(|| i - 1),
                (x < 15).then(|| i + 1),
            ];
            for n in neighbors.into_iter().flatten() {
                if !self.get(n) {
                    self.mark(n);
                    self.queue.push(n as u16);
                }
            }
        }
        faces
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_section_sees_everything_and_a_wall_splits_it() {
        let mut g = VisGraph::default();
        assert_eq!(g.resolve(), ALL_VISIBLE);

        // A full horizontal slab at y = 8 separates DOWN from UP.
        let mut g = VisGraph::default();
        for x in 0..16 {
            for z in 0..16 {
                g.set_opaque(x, 8, z);
            }
        }
        let bits = g.resolve();
        let connected = |a: u32, b: u32| bits & (1 << (a + 6 * b)) != 0;
        assert!(!connected(0, 1));
        assert!(connected(0, 2) && connected(1, 2) && connected(4, 5));
    }
}
