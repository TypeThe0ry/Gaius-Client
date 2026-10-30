use gaius_kernel_abi::{KernelError, Status};
use gaius_worldgen::ore::{self, Point, Sphere};

fn invalid(message: &str) -> KernelError {
    KernelError::new(Status::BadPayload, format!("worldgen-fallback: {message}"))
}

pub fn run(payload: &[u8]) -> Result<Vec<u8>, KernelError> {
    let mut offset = 0;
    let mut word = || {
        let bytes = payload.get(offset..offset + 4).ok_or(Status::Truncated)?;
        offset += 4;
        Ok::<_, KernelError>(u32::from_le_bytes(bytes.try_into().unwrap()))
    };
    let phase = word()?;
    if word()? != 1 {
        return Err(invalid("unsupported blending, retrogen or dimension context"));
    }
    let count = word()? as usize;
    let mut result = Vec::new();
    if phase == 0 {
        if count == 0 || count > 64 || payload.len() != 40 + count * 32 {
            return Err(invalid("invalid ore geometry length"));
        }
        let minimum = [word()? as i32, word()? as i32, word()? as i32];
        let width = word()? as i32;
        let height = word()? as i32;
        let build_height = [word()? as i32, word()? as i32];
        let spheres: Vec<_> = payload[40..]
            .as_chunks::<32>()
            .0
            .iter()
            .map(|bytes| {
                let value = |index: usize| f64::from_le_bytes(bytes[index..index + 8].try_into().unwrap());
                Sphere {
                    center: [value(0), value(8), value(16)],
                    radius: value(24),
                }
            })
            .collect();
        let points = ore::rasterize(&spheres, minimum, width, height, build_height).map_err(invalid)?;
        result.extend_from_slice(&(points.len() as u32).to_le_bytes());
        for Point(position) in points {
            for coordinate in position {
                result.extend_from_slice(&coordinate.to_le_bytes());
            }
        }
    } else if phase == 1 {
        if count > ore::MAX_POINTS || payload.len() != 12 + count * 16 {
            return Err(invalid("invalid ore snapshot length"));
        }
        let mut snapshot = Vec::with_capacity(count);
        for _ in 0..count {
            snapshot.push((Point([word()? as i32, word()? as i32, word()? as i32]), word()? as i32));
        }
        let delta = ore::ordered_delta(&snapshot).map_err(invalid)?;
        result.extend_from_slice(&(delta.len() as u32).to_le_bytes());
        for entry in delta {
            for coordinate in entry.position.0 {
                result.extend_from_slice(&coordinate.to_le_bytes());
            }
            result.extend_from_slice(&entry.state.to_le_bytes());
        }
    } else {
        return Err(invalid("unknown ore phase"));
    }
    Ok(result)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn declines_context_before_reading_snapshot() {
        assert!(run(&[0; 12]).unwrap_err().message.starts_with("worldgen-fallback:"));
        assert_eq!(run(&[]).unwrap_err().status, Status::Truncated);
    }

    #[test]
    fn rejects_unknown_phase_and_duplicate_snapshot() {
        let words = [2u32, 1, 0];
        let bytes: Vec<_> = words.into_iter().flat_map(u32::to_le_bytes).collect();
        assert_eq!(run(&bytes).unwrap_err().status, Status::BadPayload);
        let words = [1u32, 1, 2, 0, 0, 0, 5, 0, 0, 0, 6];
        let bytes: Vec<_> = words.into_iter().flat_map(u32::to_le_bytes).collect();
        assert_eq!(run(&bytes).unwrap_err().status, Status::BadPayload);
        assert!(run(&bytes[..bytes.len() - 1]).is_err());
    }
}
