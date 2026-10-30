use gaius_kernel_abi::{KernelError, Reader, Status};

pub fn run_png_decode(payload: &[u8]) -> Result<Vec<u8>, KernelError> {
    let mut reader = Reader::new(payload);
    let desired_channels = reader.u32()?;
    let image = gaius_png::decode(&payload[4..], desired_channels)
        .map_err(|error| KernelError::new(Status::BadPayload, error.to_string()))?;
    let mut result = Vec::new();
    result
        .try_reserve_exact(16 + image.pixels.len())
        .map_err(|_| KernelError::new(Status::OutOfMemory, "cannot allocate PNG result"))?;
    for value in [image.width, image.height, image.source_channels, image.output_channels] {
        result.extend_from_slice(&value.to_le_bytes());
    }
    result.extend_from_slice(&image.pixels);
    Ok(result)
}
