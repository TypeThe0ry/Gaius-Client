//! `Climate.ParameterList` / `Climate.RTree`: the multi-noise biome lookup.
//!
//! The tree is rebuilt from the parameter list with vanilla's algorithm
//! (stable sorts, the same bucket sizes and cost function), so its shape and
//! therefore its tie-breaking are vanilla's. Vanilla warm-starts each search
//! with the previous result of the calling thread; [`Searcher`] keeps that
//! candidate per job, which cannot preserve vanilla's cross-chunk thread history.
//! Ambiguous biome values therefore require a vanilla context or a declined job.

use crate::ir::ClimateEntry;

const DIMENSIONS: usize = 7;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct Param {
    min: i64,
    max: i64,
}

impl Param {
    #[inline]
    fn distance(self, target: i64) -> i64 {
        let above = target.wrapping_sub(self.max);
        let below = self.min.wrapping_sub(target);
        if above > 0 {
            above
        } else {
            below.max(0)
        }
    }

    fn span(self, other: Option<Param>) -> Param {
        match other {
            None => self,
            Some(o) => Param {
                min: self.min.min(o.min),
                max: self.max.max(o.max),
            },
        }
    }

    #[inline]
    fn center(self) -> i64 {
        self.min.wrapping_add(self.max) / 2
    }
}

#[derive(Clone, Debug)]
enum Kind {
    Leaf(u32),
    SubTree(Vec<u32>),
}

#[derive(Clone, Debug)]
struct Node {
    space: [Param; DIMENSIONS],
    kind: Kind,
}

/// A built `Climate.RTree`; values are biome table indices.
#[derive(Clone, Debug)]
pub struct RTree {
    nodes: Vec<Node>,
    root: u32,
}

/// `Climate.quantizeCoord(float)`.
#[inline]
pub fn quantize(coord: f32) -> i64 {
    (coord * 10000.0f32) as i64
}

impl RTree {
    pub fn new(entries: &[ClimateEntry], children_per_node: usize) -> RTree {
        let mut tree = RTree {
            nodes: Vec::with_capacity(entries.len() * 2),
            root: 0,
        };
        let leaves: Vec<u32> = entries
            .iter()
            .map(|e| {
                let mut space = [Param { min: 0, max: 0 }; DIMENSIONS];
                for (d, p) in e.params.iter().enumerate() {
                    space[d] = Param { min: p[0], max: p[1] };
                }
                tree.push(Node {
                    space,
                    kind: Kind::Leaf(e.biome),
                })
            })
            .collect();
        tree.root = tree.build(leaves, children_per_node.max(2));
        tree
    }

    fn push(&mut self, node: Node) -> u32 {
        self.nodes.push(node);
        (self.nodes.len() - 1) as u32
    }

    fn sub_tree(&mut self, children: Vec<u32>) -> u32 {
        let space = self.parameter_space(&children);
        self.push(Node {
            space,
            kind: Kind::SubTree(children),
        })
    }

    fn parameter_space(&self, children: &[u32]) -> [Param; DIMENSIONS] {
        let mut bounds: [Option<Param>; DIMENSIONS] = [None; DIMENSIONS];
        for &child in children {
            for (d, bound) in bounds.iter_mut().enumerate() {
                *bound = Some(self.nodes[child as usize].space[d].span(*bound));
            }
        }
        bounds.map(|b| b.expect("SubTree needs at least one child"))
    }

    /// `Comparator.comparingLong(center of dimension d)` chained over the following dimensions.
    fn sort(&self, children: &mut [u32], dimension: usize, absolute: bool) {
        let key = |node: u32, d: usize| {
            let c = self.nodes[node as usize].space[d].center();
            if absolute {
                c.wrapping_abs()
            } else {
                c
            }
        };
        children.sort_by(|&a, &b| {
            for offset in 0..DIMENSIONS {
                let d = (dimension + offset) % DIMENSIONS;
                let ord = key(a, d).cmp(&key(b, d));
                if ord != core::cmp::Ordering::Equal {
                    return ord;
                }
            }
            core::cmp::Ordering::Equal
        });
    }

    fn bucketize(&mut self, nodes: &[u32], children_per_node: usize) -> Vec<u32> {
        let cpn = children_per_node as f64;
        let expected = cpn.powf(((nodes.len() as f64 - 0.01).ln() / cpn.ln()).floor()) as i32;
        let mut buckets = Vec::new();
        let mut children = Vec::new();
        for &child in nodes {
            children.push(child);
            if children.len() as i32 >= expected {
                buckets.push(self.sub_tree(core::mem::take(&mut children)));
            }
        }
        if !children.is_empty() {
            buckets.push(self.sub_tree(children));
        }
        buckets
    }

    fn cost(space: &[Param; DIMENSIONS]) -> i64 {
        space.iter().fold(0i64, |acc, p| {
            acc.wrapping_add(p.max.wrapping_sub(p.min).wrapping_abs())
        })
    }

    fn build(&mut self, mut children: Vec<u32>, children_per_node: usize) -> u32 {
        assert!(!children.is_empty(), "Need at least one child to build a node");
        if children.len() == 1 {
            return children[0];
        }
        if children.len() <= children_per_node {
            let magnitude = |tree: &RTree, node: u32| {
                tree.nodes[node as usize]
                    .space
                    .iter()
                    .fold(0i64, |acc, p| acc.wrapping_add(p.center().wrapping_abs()))
            };
            children.sort_by_key(|&c| magnitude(self, c));
            return self.sub_tree(children);
        }
        let mut min_cost = i64::MAX;
        let mut min_dimension = 0;
        let mut min_buckets: Vec<u32> = Vec::new();
        for d in 0..DIMENSIONS {
            self.sort(&mut children, d, false);
            let buckets = self.bucketize(&children, children_per_node);
            let total = buckets.iter().fold(0i64, |acc, &b| {
                acc.wrapping_add(Self::cost(&self.nodes[b as usize].space))
            });
            if min_cost > total {
                min_cost = total;
                min_dimension = d;
                min_buckets = buckets;
            }
        }
        self.sort(&mut min_buckets, min_dimension, true);
        let built: Vec<u32> = min_buckets
            .iter()
            .map(|&b| {
                let Kind::SubTree(grand) = self.nodes[b as usize].kind.clone() else {
                    unreachable!("buckets are sub trees")
                };
                self.build(grand, children_per_node)
            })
            .collect();
        self.sub_tree(built)
    }

    #[inline]
    fn distance(&self, node: u32, target: &[i64; DIMENSIONS]) -> i64 {
        let space = &self.nodes[node as usize].space;
        let mut d = 0i64;
        for i in 0..DIMENSIONS {
            let v = space[i].distance(target[i]);
            d = d.wrapping_add(v.wrapping_mul(v));
        }
        d
    }

    /// `Node.search(target, candidate, Node::distance)`; returns a leaf node.
    fn search_node(&self, node: u32, target: &[i64; DIMENSIONS], candidate: Option<u32>) -> u32 {
        match &self.nodes[node as usize].kind {
            Kind::Leaf(_) => node,
            Kind::SubTree(children) => {
                let mut min_distance = candidate.map_or(i64::MAX, |c| self.distance(c, target));
                let mut closest = candidate;
                for &child in children {
                    let child_distance = self.distance(child, target);
                    if min_distance > child_distance {
                        let leaf = self.search_node(child, target, closest);
                        let leaf_distance = if child == leaf {
                            child_distance
                        } else {
                            self.distance(leaf, target)
                        };
                        if min_distance > leaf_distance {
                            min_distance = leaf_distance;
                            closest = Some(leaf);
                        }
                    }
                }
                closest.expect("a sub tree always finds a leaf")
            }
        }
    }

    fn value(&self, leaf: u32) -> u32 {
        match self.nodes[leaf as usize].kind {
            Kind::Leaf(v) => v,
            Kind::SubTree(_) => unreachable!("search returns leaves"),
        }
    }

    fn competing_value(&self, node: u32, target: &[i64; DIMENSIONS], fitness: i64, value: u32) -> bool {
        if self.distance(node, target) > fitness {
            return false;
        }
        match &self.nodes[node as usize].kind {
            Kind::Leaf(candidate) => *candidate != value && self.distance(node, target) == fitness,
            Kind::SubTree(children) => children
                .iter()
                .any(|&child| self.competing_value(child, target, fitness, value)),
        }
    }
}

/// One job's view of a tree: the warm-start candidate of `RTree.search`.
pub struct Searcher<'a> {
    tree: &'a RTree,
    last: Option<u32>,
    ambiguous: bool,
}

impl<'a> Searcher<'a> {
    pub fn new(tree: &'a RTree) -> Self {
        Searcher {
            tree,
            last: None,
            ambiguous: false,
        }
    }

    /// `findValue(Climate.target(...))` for an already quantized target.
    pub fn find(&mut self, target: [i64; 6]) -> u32 {
        let t = [target[0], target[1], target[2], target[3], target[4], target[5], 0];
        let leaf = self.tree.search_node(self.tree.root, &t, self.last);
        self.last = Some(leaf);
        let value = self.tree.value(leaf);
        self.ambiguous |= self
            .tree
            .competing_value(self.tree.root, &t, self.tree.distance(leaf, &t), value);
        value
    }

    pub fn is_ambiguous(&self) -> bool {
        self.ambiguous
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn competing_biomes_require_thread_context_but_duplicate_values_do_not() {
        let tree = RTree::new(&[entry(0.0, 0.0, 7), entry(0.0, 0.0, 8)], 6);
        let mut searcher = Searcher::new(&tree);
        searcher.find([0; 6]);
        assert!(searcher.is_ambiguous());
        let tree = RTree::new(&[entry(0.0, 0.0, 7), entry(0.0, 0.0, 7)], 6);
        let mut searcher = Searcher::new(&tree);
        assert_eq!(searcher.find([0; 6]), 7);
        assert!(!searcher.is_ambiguous());
    }

    fn entry(t: f32, h: f32, biome: u32) -> ClimateEntry {
        let q = quantize;
        ClimateEntry {
            params: [
                [q(t - 0.1), q(t + 0.1)],
                [q(h - 0.1), q(h + 0.1)],
                [0, 0],
                [0, 0],
                [0, 0],
                [0, 0],
                [0, 0],
            ],
            biome,
        }
    }

    /// The tree must agree with the brute-force fitness search wherever the best fit is unique.
    #[test]
    fn tree_matches_brute_force() {
        let mut entries = Vec::new();
        let mut b = 0;
        for i in 0..9 {
            for j in 0..7 {
                entries.push(entry(-1.0 + i as f32 * 0.25, -0.9 + j as f32 * 0.3, b));
                b += 1;
            }
        }
        for cpn in [6, 19] {
            let tree = RTree::new(&entries, cpn);
            let mut searcher = Searcher::new(&tree);
            for k in 0..400 {
                let t = quantize(((k * 37) % 200) as f32 / 100.0 - 1.0);
                let h = quantize(((k * 53) % 200) as f32 / 100.0 - 1.0);
                let target = [t, h, 0, 0, 0, 0];
                let fitness = |e: &ClimateEntry| {
                    let p = |i: usize, v: i64| {
                        Param {
                            min: e.params[i][0],
                            max: e.params[i][1],
                        }
                        .distance(v)
                    };
                    let (a, b) = (p(0, t), p(1, h));
                    a * a + b * b
                };
                let best = entries.iter().map(fitness).min().unwrap();
                let winners: Vec<u32> = entries.iter().filter(|e| fitness(e) == best).map(|e| e.biome).collect();
                let found = searcher.find(target);
                assert!(
                    winners.contains(&found),
                    "cpn {cpn} target {target:?}: {found} not in {winners:?}"
                );
            }
        }
    }
}
