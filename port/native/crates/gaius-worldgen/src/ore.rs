use std::collections::HashSet;

#[derive(Clone, Copy, Debug)]
pub struct Sphere {
    pub center: [f64; 3],
    pub radius: f64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct Point(pub [i32; 3]);

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Delta {
    pub position: Point,
    pub state: u32,
}

pub const MAX_POINTS: usize = 262_144;

pub fn rasterize(
    spheres: &[Sphere],
    minimum: [i32; 3],
    width: i32,
    height: i32,
    build_height: [i32; 2],
) -> Result<Vec<Point>, &'static str> {
    if spheres.is_empty()
        || spheres.len() > 64
        || !(1..=64).contains(&width)
        || !(1..=64).contains(&height)
        || build_height[0] >= build_height[1]
        || minimum.iter().any(|coordinate| coordinate.abs_diff(0) > 30_000_128)
        || spheres.iter().any(|sphere| {
            !sphere.radius.is_finite()
                || sphere.radius <= 0.0
                || sphere.radius > 64.0
                || sphere
                    .center
                    .iter()
                    .any(|value| !value.is_finite() || value.abs() > 30_000_128.0)
        })
    {
        return Err("unsupported ore geometry");
    }
    let mut spheres = spheres.to_vec();
    for outer in 0..spheres.len() {
        if spheres[outer].radius <= 0.0 {
            continue;
        }
        for inner in outer + 1..spheres.len() {
            if spheres[inner].radius <= 0.0 {
                continue;
            }
            let dx = spheres[outer].center[0] - spheres[inner].center[0];
            let dy = spheres[outer].center[1] - spheres[inner].center[1];
            let dz = spheres[outer].center[2] - spheres[inner].center[2];
            let radius = spheres[outer].radius - spheres[inner].radius;
            if radius * radius > dx * dx + dy * dy + dz * dz {
                if radius > 0.0 {
                    spheres[inner].radius = -1.0;
                } else {
                    spheres[outer].radius = -1.0;
                }
            }
        }
    }
    let mut visited = vec![false; (width * height * width) as usize];
    let mut points = Vec::new();
    for sphere in spheres {
        if sphere.radius < 0.0 {
            continue;
        }
        let start = std::array::from_fn::<_, 3, _>(|axis| {
            ((sphere.center[axis] - sphere.radius).floor() as i32).max(minimum[axis])
        });
        let end = std::array::from_fn::<_, 3, _>(|axis| {
            ((sphere.center[axis] + sphere.radius).floor() as i32).max(start[axis])
        });
        if end[0] >= minimum[0] + width || end[1] >= minimum[1] + height || end[2] >= minimum[2] + width {
            return Err("ore sphere exceeds snapshot bounds");
        }
        for x in start[0]..=end[0] {
            let dx = (f64::from(x) + 0.5 - sphere.center[0]) / sphere.radius;
            if dx * dx >= 1.0 {
                continue;
            }
            for y in start[1]..=end[1] {
                let dy = (f64::from(y) + 0.5 - sphere.center[1]) / sphere.radius;
                if dx * dx + dy * dy >= 1.0 {
                    continue;
                }
                for z in start[2]..=end[2] {
                    let dz = (f64::from(z) + 0.5 - sphere.center[2]) / sphere.radius;
                    if dx * dx + dy * dy + dz * dz >= 1.0 || y < build_height[0] || y >= build_height[1] {
                        continue;
                    }
                    let index =
                        ((x - minimum[0]) + (y - minimum[1]) * width + (z - minimum[2]) * width * height) as usize;
                    if !visited[index] {
                        visited[index] = true;
                        points.push(Point([x, y, z]));
                    }
                }
            }
        }
    }
    Ok(points)
}

pub fn ordered_delta(snapshot: &[(Point, i32)]) -> Result<Vec<Delta>, &'static str> {
    if snapshot.len() > MAX_POINTS {
        return Err("ore snapshot exceeds budget");
    }
    let mut seen = HashSet::with_capacity(snapshot.len());
    let mut delta = Vec::new();
    for &(position, state) in snapshot {
        if !seen.insert(position) || state < -1 {
            return Err("invalid ore snapshot");
        }
        if state >= 0 {
            delta.push(Delta {
                position,
                state: state as u32,
            });
        }
    }
    Ok(delta)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn preserves_first_visit_and_delta_order() {
        let spheres = [Sphere {
            center: [1.5, 1.5, 1.5],
            radius: 1.1,
        }; 2];
        let points = rasterize(&spheres, [0, 0, 0], 4, 4, [0, 4]).unwrap();
        assert_eq!(points.len(), 7);
        assert_eq!(points[0], Point([0, 1, 1]));
        let snapshot: Vec<_> = points
            .iter()
            .enumerate()
            .map(|(index, &point)| (point, if index % 2 == 0 { index as i32 } else { -1 }))
            .collect();
        let delta = ordered_delta(&snapshot).unwrap();
        assert_eq!(
            delta.iter().map(|entry| entry.position).collect::<Vec<_>>(),
            points.iter().step_by(2).copied().collect::<Vec<_>>()
        );
    }

    #[test]
    fn rejects_unbounded_and_duplicate_input() {
        assert!(rasterize(&[], [0; 3], 1, 1, [0, 1]).is_err());
        assert!(rasterize(
            &[Sphere {
                center: [f64::NAN; 3],
                radius: 1.0
            }],
            [0; 3],
            4,
            4,
            [0, 4]
        )
        .is_err());
        assert!(ordered_delta(&[(Point([0; 3]), 1), (Point([0; 3]), 2)]).is_err());
    }

    #[test]
    fn prunes_contained_spheres_and_clips_build_height() {
        let spheres = [
            Sphere {
                center: [2.5; 3],
                radius: 2.1,
            },
            Sphere {
                center: [2.5; 3],
                radius: 0.6,
            },
        ];
        let points = rasterize(&spheres, [0; 3], 6, 6, [2, 3]).unwrap();
        assert!(points.iter().all(|point| point.0[1] == 2));
        assert_eq!(points, rasterize(&spheres[..1], [0; 3], 6, 6, [2, 3]).unwrap());
    }
}
