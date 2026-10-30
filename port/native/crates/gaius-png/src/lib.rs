//! PNG decoding that reproduces the browser STBImage Java decoder
//! (port/overrides/libraries/lwjgl-stb/src/main/java/org/lwjgl/stb/STBImage.java).
//!
//! Every input yields either exactly the bytes that decoder returns, or a [`Decline`], after
//! which the caller runs the Java decoder itself. Each case the Java decoder rejects is a
//! decline here; so is each irregular layout (repeated or misplaced PLTE/tRNS, palette
//! lengths that are not whole entries, tRNS longer than the palette) where reproducing its
//! exact output is not worth the risk. The chunk walk, inflate admission, unfiltering and
//! sample expansion follow the Java source rule by rule, and no input may panic: a panic
//! aborts the wasm instance, which the browser adapter then disables for the session.

pub const MAX_IMAGE_BYTES: usize = 64 * 1024 * 1024;
pub const MAX_DIMENSION: u32 = 16_384;

const SIGNATURE: &[u8; 8] = b"\x89PNG\r\n\x1a\n";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DecodedPng {
    pub width: u32,
    pub height: u32,
    pub source_channels: u32,
    pub output_channels: u32,
    pub pixels: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Decline(pub String);

impl std::fmt::Display for Decline {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(&self.0)
    }
}

impl std::error::Error for Decline {}

fn decline(message: impl Into<String>) -> Decline {
    Decline(message.into())
}

fn read16(bytes: &[u8], offset: usize) -> u32 {
    u32::from(bytes[offset]) << 8 | u32::from(bytes[offset + 1])
}

fn read32(bytes: &[u8], offset: usize) -> u32 {
    u32::from_be_bytes([bytes[offset], bytes[offset + 1], bytes[offset + 2], bytes[offset + 3]])
}

/// What the chunk walk keeps, already restricted to layouts whose Java result is reproduced.
struct Layout<'a> {
    width: usize,
    height: usize,
    depth: usize,
    color: u8,
    /// RGB triples; only kept for indexed images, the only ones Java reads it for.
    palette: Option<&'a [u8]>,
    /// Indexed images: the tRNS bytes (Java's paletteAlpha), never longer than the palette.
    palette_alpha: Option<&'a [u8]>,
    /// Grayscale/RGB tRNS samples at full depth (Java's transparentGray/Red/Green/Blue).
    transparent: Option<[u32; 3]>,
    idat: Vec<&'a [u8]>,
}

fn walk_chunks(png: &[u8]) -> Result<Layout<'_>, Decline> {
    let mut layout: Option<Layout<'_>> = None;
    let mut offset = 8;
    // Java's loop condition: fewer than 12 trailing bytes end the walk without an error.
    while offset + 12 <= png.len() {
        let length = read32(png, offset);
        // Java reads the length as a signed int and rejects negative or overrunning lengths.
        if length > i32::MAX as u32 {
            return Err(decline("invalid PNG chunk length"));
        }
        let data_start = offset + 8;
        let data_end = data_start + length as usize;
        if data_end + 4 > png.len() {
            return Err(decline("invalid PNG chunk length"));
        }
        let kind = &png[offset + 4..data_start];
        let data = &png[data_start..data_end];
        // Like Java, the CRC is not checked.
        offset = data_end + 4;
        let Some(layout) = layout.as_mut() else {
            // Java accepts chunks before IHDR and repeated IHDRs; both decline here.
            if kind != b"IHDR" || data.len() != 13 {
                return Err(decline("PNG does not start with a 13-byte IHDR"));
            }
            layout = Some(parse_header(data)?);
            continue;
        };
        match kind {
            b"IHDR" => return Err(decline("repeated PNG IHDR")),
            b"PLTE" => {
                // Java ignores the palette of non-indexed images.
                if layout.color == 3 {
                    if layout.palette.is_some() || !layout.idat.is_empty() {
                        return Err(decline("repeated or late PNG PLTE"));
                    }
                    if data.is_empty() || !data.len().is_multiple_of(3) || data.len() > 256 * 3 {
                        return Err(decline("PNG PLTE is not 1 to 256 whole entries"));
                    }
                    layout.palette = Some(data);
                }
            }
            b"tRNS" => match layout.color {
                3 => {
                    // Java applies tRNS bytes in any position and of any length, unlike other
                    // decoders; the irregular ones decline.
                    let Some(palette) = layout.palette else {
                        return Err(decline("PNG tRNS before PLTE"));
                    };
                    if layout.palette_alpha.is_some() || !layout.idat.is_empty() {
                        return Err(decline("repeated or late PNG tRNS"));
                    }
                    if data.len() > palette.len() / 3 {
                        return Err(decline("PNG tRNS longer than the palette"));
                    }
                    layout.palette_alpha = Some(data);
                }
                0 | 2 => {
                    let samples = if layout.color == 0 { 1 } else { 3 };
                    if layout.transparent.is_some() || !layout.idat.is_empty() || data.len() != samples * 2 {
                        return Err(decline("irregular PNG tRNS"));
                    }
                    let mut transparent = [0; 3];
                    for (index, value) in transparent.iter_mut().take(samples).enumerate() {
                        *value = read16(data, index * 2);
                    }
                    layout.transparent = Some(transparent);
                }
                // Java ignores tRNS for grayscale-alpha and RGBA.
                _ => {}
            },
            // Java concatenates every IDAT before IEND, wherever it appears.
            b"IDAT" => layout.idat.push(data),
            b"IEND" => break,
            // Java ignores every other chunk, critical or not.
            _ => {}
        }
    }
    let layout = layout.ok_or_else(|| decline("missing PNG header"))?;
    if layout.color == 3 && layout.palette.is_none() {
        return Err(decline("invalid PNG palette index"));
    }
    Ok(layout)
}

fn parse_header(data: &[u8]) -> Result<Layout<'_>, Decline> {
    let width = read32(data, 0);
    let height = read32(data, 4);
    let depth = data[8];
    let color = data[9];
    // Java rejects Adam7 and nonzero compression/filter methods.
    if width == 0 || height == 0 || data[10..13] != [0, 0, 0] {
        return Err(decline("unsupported PNG header"));
    }
    let valid = match color {
        0 => matches!(depth, 1 | 2 | 4 | 8 | 16),
        2 | 4 | 6 => matches!(depth, 8 | 16),
        3 => matches!(depth, 1 | 2 | 4 | 8),
        _ => false,
    };
    if !valid {
        return Err(decline("unsupported PNG color type/depth"));
    }
    if width > MAX_DIMENSION || height > MAX_DIMENSION {
        return Err(decline("PNG dimensions exceed the decode budget"));
    }
    let width = width as usize;
    let height = height as usize;
    if width * height * 4 > MAX_IMAGE_BYTES {
        return Err(decline("decoded PNG exceeds the decode budget"));
    }
    Ok(Layout {
        width,
        height,
        depth: depth as usize,
        color,
        palette: None,
        palette_alpha: None,
        transparent: None,
        idat: Vec::new(),
    })
}

fn source_samples(color: u8) -> usize {
    match color {
        0 | 3 => 1,
        2 => 3,
        4 => 2,
        _ => 4,
    }
}

/// java.util.zip.Inflater admission: a complete zlib stream without a preset dictionary whose
/// Adler-32 matches and which inflates to exactly `size` bytes. Bytes after the stream are
/// ignored, as Java does once the inflater has finished.
fn inflate(idat: &[&[u8]], size: usize) -> Result<Vec<u8>, Decline> {
    let mut output = Vec::new();
    output
        .try_reserve_exact(size)
        .map_err(|_| decline("cannot allocate PNG scanlines"))?;
    output.resize(size, 0);
    let mut decompressor = fdeflate::Decompressor::new();
    let mut filled = 0;
    for (index, &part) in idat.iter().enumerate() {
        let last = index + 1 == idat.len();
        let mut input = part;
        // The last part is also called with no input left: fdeflate may still hold buffered bits.
        while !decompressor.is_done() && (last || !input.is_empty()) {
            let (consumed, produced) = decompressor
                .read(input, &mut output, filled, last)
                .map_err(|error| decline(format!("PNG zlib stream: {error:?}")))?;
            filled += produced;
            input = &input[consumed..];
            if consumed == 0 && produced == 0 {
                break;
            }
        }
        if decompressor.is_done() {
            break;
        }
    }
    // Not done: either the data ended early (Java's "truncated") or the image is full while the
    // stream goes on (Java's "stalled").
    if !decompressor.is_done() {
        return Err(decline("PNG zlib stream truncated or longer than the image"));
    }
    if filled != size {
        return Err(decline("PNG inflated length mismatch"));
    }
    Ok(output)
}

/// Reverses the scanline filters in place; row y keeps its filter byte at y * (row_bytes + 1).
fn unfilter(data: &mut [u8], height: usize, row_bytes: usize, bpp: usize) -> Result<(), Decline> {
    let stride = row_bytes + 1;
    let lead = bpp.min(row_bytes);
    for y in 0..height {
        let (before, rest) = data.split_at_mut(y * stride);
        let (filter, row) = rest[..stride].split_first_mut().unwrap();
        let prior = if y == 0 {
            None
        } else {
            Some(&before[(y - 1) * stride + 1..])
        };
        match (*filter, prior) {
            (0, _) | (2, None) => {}
            (1, _) | (4, None) => {
                for x in lead..row_bytes {
                    row[x] = row[x].wrapping_add(row[x - bpp]);
                }
            }
            (2, Some(prior)) => {
                for (value, &up) in row.iter_mut().zip(prior) {
                    *value = value.wrapping_add(up);
                }
            }
            (3, prior) => {
                for x in 0..row_bytes {
                    let left = if x >= lead { u32::from(row[x - bpp]) } else { 0 };
                    let up = prior.map_or(0, |prior| u32::from(prior[x]));
                    row[x] = row[x].wrapping_add(((left + up) >> 1) as u8);
                }
            }
            (4, Some(prior)) => {
                for x in 0..lead {
                    row[x] = row[x].wrapping_add(prior[x]);
                }
                for x in lead..row_bytes {
                    let left = i32::from(row[x - bpp]);
                    let up = i32::from(prior[x]);
                    let corner = i32::from(prior[x - bpp]);
                    let estimate = left + up - corner;
                    let to_left = (estimate - left).abs();
                    let to_up = (estimate - up).abs();
                    let to_corner = (estimate - corner).abs();
                    let predictor = if to_left <= to_up && to_left <= to_corner {
                        left
                    } else if to_up <= to_corner {
                        up
                    } else {
                        corner
                    };
                    row[x] = row[x].wrapping_add(predictor as u8);
                }
            }
            (filter, _) => return Err(decline(format!("unsupported PNG filter: {filter}"))),
        }
    }
    Ok(())
}

fn scale(sample: u32, depth: usize) -> u8 {
    match depth {
        1 => {
            if sample == 0 {
                0
            } else {
                255
            }
        }
        2 => (sample * 85) as u8,
        4 => (sample * 17) as u8,
        8 => sample as u8,
        _ => (sample >> 8) as u8,
    }
}

/// Expands one unfiltered row to RGBA exactly as STBImage.decodeRows does.
fn expand_row(layout: &Layout<'_>, row: &[u8], rgba: &mut [u8]) -> Result<(), Decline> {
    let depth = layout.depth;
    let samples = source_samples(layout.color);
    let sample = |index: usize| -> u32 {
        match depth {
            8 => u32::from(row[index]),
            16 => read16(row, index * 2),
            _ => {
                let bit = index * depth;
                (u32::from(row[bit / 8]) >> (8 - depth - bit % 8)) & ((1 << depth) - 1)
            }
        }
    };
    let palette = layout.palette.unwrap_or_default();
    let palette_alpha = layout.palette_alpha.unwrap_or_default();
    for (x, pixel) in rgba.as_chunks_mut::<4>().0.iter_mut().enumerate() {
        let base = x * samples;
        let value = match layout.color {
            0 => {
                let raw = sample(base);
                let gray = scale(raw, depth);
                // Java compares the raw sample with the full 16-bit tRNS value.
                let alpha = if layout.transparent.is_some_and(|key| key[0] == raw) {
                    0
                } else {
                    255
                };
                [gray, gray, gray, alpha]
            }
            2 => {
                let raw = [sample(base), sample(base + 1), sample(base + 2)];
                let alpha = if layout.transparent == Some(raw) { 0 } else { 255 };
                [scale(raw[0], depth), scale(raw[1], depth), scale(raw[2], depth), alpha]
            }
            3 => {
                let index = sample(base) as usize;
                let Some(rgb) = palette.get(index * 3..index * 3 + 3) else {
                    return Err(decline("invalid PNG palette index"));
                };
                let alpha = palette_alpha.get(index).copied().unwrap_or(255);
                [rgb[0], rgb[1], rgb[2], alpha]
            }
            4 => {
                let gray = scale(sample(base), depth);
                [gray, gray, gray, scale(sample(base + 1), depth)]
            }
            _ => [
                scale(sample(base), depth),
                scale(sample(base + 1), depth),
                scale(sample(base + 2), depth),
                scale(sample(base + 3), depth),
            ],
        };
        pixel.copy_from_slice(&value);
    }
    Ok(())
}

fn luminance(red: u8, green: u8, blue: u8) -> u8 {
    ((u32::from(red) * 299 + u32::from(green) * 587 + u32::from(blue) * 114 + 500) / 1000) as u8
}

pub fn decode(encoded: &[u8], desired_channels: u32) -> Result<DecodedPng, Decline> {
    if desired_channels > 4 {
        return Err(decline("unsupported desired channel count"));
    }
    if encoded.len() > MAX_IMAGE_BYTES {
        return Err(decline("encoded PNG exceeds the decode budget"));
    }
    if encoded.len() < 33 || &encoded[..8] != SIGNATURE {
        return Err(decline("not a PNG image"));
    }
    let layout = walk_chunks(encoded)?;
    let (width, height) = (layout.width, layout.height);
    let bits_per_pixel = layout.depth * source_samples(layout.color);
    let row_bytes = (width * bits_per_pixel).div_ceil(8);
    let scanlines = height * (row_bytes + 1);
    if scanlines > MAX_IMAGE_BYTES {
        return Err(decline("PNG scanlines exceed the decode budget"));
    }
    let mut data = inflate(&layout.idat, scanlines)?;
    unfilter(&mut data, height, row_bytes, bits_per_pixel.div_ceil(8).max(1))?;

    let source_channels = match layout.color {
        0 => 1,
        2 => 3,
        3 if layout.palette_alpha.is_some() => 4,
        3 => 3,
        4 => 2,
        _ => 4,
    };
    let output_channels = if desired_channels == 0 {
        source_channels
    } else {
        desired_channels
    };
    let output_size = width * height * output_channels as usize;
    let mut pixels = Vec::new();
    pixels
        .try_reserve_exact(output_size)
        .map_err(|_| decline("cannot allocate PNG pixels"))?;
    let direct = layout.color == 6 && layout.depth == 8 && output_channels == 4;
    let mut rgba = if direct { Vec::new() } else { vec![0; width * 4] };
    for y in 0..height {
        let start = y * (row_bytes + 1) + 1;
        let row = &data[start..start + row_bytes];
        if direct {
            pixels.extend_from_slice(row);
            continue;
        }
        expand_row(&layout, row, &mut rgba)?;
        match output_channels {
            4 => pixels.extend_from_slice(&rgba),
            3 => {
                for pixel in rgba.as_chunks::<4>().0 {
                    pixels.extend_from_slice(&pixel[..3]);
                }
            }
            2 => {
                for pixel in rgba.as_chunks::<4>().0 {
                    pixels.extend_from_slice(&[luminance(pixel[0], pixel[1], pixel[2]), pixel[3]]);
                }
            }
            _ => {
                for pixel in rgba.as_chunks::<4>().0 {
                    pixels.push(luminance(pixel[0], pixel[1], pixel[2]));
                }
            }
        }
    }
    if pixels.len() != output_size {
        return Err(decline("incomplete PNG frame"));
    }
    Ok(DecodedPng {
        width: width as u32,
        height: height as u32,
        source_channels,
        output_channels,
        pixels,
    })
}
