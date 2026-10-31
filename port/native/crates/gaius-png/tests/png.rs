use flate2::{write::ZlibEncoder, Compression};
use gaius_png::decode;
use std::io::Write;

struct Image {
    width: usize,
    height: usize,
    depth: u8,
    color: u8,
    samples: Vec<u16>,
    extras: Vec<([u8; 4], Vec<u8>)>,
}

fn chunk(output: &mut Vec<u8>, kind: &[u8; 4], bytes: &[u8]) {
    output.extend_from_slice(&(bytes.len() as u32).to_be_bytes());
    let start = output.len();
    output.extend_from_slice(kind);
    output.extend_from_slice(bytes);
    output.extend_from_slice(&crc32fast::hash(&output[start..]).to_be_bytes());
}

fn pack(samples: &[u16], depth: u8) -> Vec<u8> {
    if depth == 16 {
        return samples.iter().flat_map(|sample| sample.to_be_bytes()).collect();
    }
    let mut packed = vec![0; (samples.len() * depth as usize).div_ceil(8)];
    for (index, &sample) in samples.iter().enumerate() {
        let offset = index * depth as usize;
        packed[offset / 8] |= (sample as u8) << (8 - depth as usize - offset % 8);
    }
    packed
}

fn paeth(left: i32, above: i32, corner: i32) -> u8 {
    let prediction = left + above - corner;
    let distances = [
        (prediction - left).abs(),
        (prediction - above).abs(),
        (prediction - corner).abs(),
    ];
    if distances[0] <= distances[1] && distances[0] <= distances[2] {
        left as u8
    } else if distances[1] <= distances[2] {
        above as u8
    } else {
        corner as u8
    }
}

fn encode(image: &Image, interlaced: bool, filter: u8) -> Vec<u8> {
    let channels = match image.color {
        0 | 3 => 1,
        2 => 3,
        4 => 2,
        6 => 4,
        _ => unreachable!(),
    };
    let steps: &[(usize, usize, usize, usize)] = if interlaced {
        &[
            (0, 0, 8, 8),
            (4, 0, 8, 8),
            (0, 4, 4, 8),
            (2, 0, 4, 4),
            (0, 2, 2, 4),
            (1, 0, 2, 2),
            (0, 1, 1, 2),
        ]
    } else {
        &[(0, 0, 1, 1)]
    };
    let mut raw = Vec::new();
    let bpp = (channels * image.depth as usize).div_ceil(8).max(1);
    for &(first_x, first_y, step_x, step_y) in steps {
        if first_x >= image.width || first_y >= image.height {
            continue;
        }
        let mut previous = Vec::<u8>::new();
        for row_y in (first_y..image.height).step_by(step_y) {
            let mut samples = Vec::new();
            for column_x in (first_x..image.width).step_by(step_x) {
                let start = (row_y * image.width + column_x) * channels;
                samples.extend_from_slice(&image.samples[start..start + channels]);
            }
            let row = pack(&samples, image.depth);
            raw.push(filter);
            for (index, &sample) in row.iter().enumerate() {
                let left = if index >= bpp { row[index - bpp] } else { 0 };
                let above = previous.get(index).copied().unwrap_or(0);
                let corner = if index >= bpp {
                    previous.get(index - bpp).copied().unwrap_or(0)
                } else {
                    0
                };
                let predictor = match filter {
                    0 => 0,
                    1 => left,
                    2 => above,
                    3 => ((left as u16 + above as u16) / 2) as u8,
                    4 => paeth(left as i32, above as i32, corner as i32),
                    _ => 0,
                };
                raw.push(sample.wrapping_sub(predictor));
            }
            previous = row;
        }
    }
    let mut zlib = ZlibEncoder::new(Vec::new(), Compression::default());
    zlib.write_all(&raw).unwrap();
    let mut output = b"\x89PNG\r\n\x1a\n".to_vec();
    let mut header = (image.width as u32).to_be_bytes().to_vec();
    header.extend_from_slice(&(image.height as u32).to_be_bytes());
    header.extend_from_slice(&[image.depth, image.color, 0, 0, u8::from(interlaced)]);
    chunk(&mut output, b"IHDR", &header);
    for (kind, value) in &image.extras {
        chunk(&mut output, kind, value);
    }
    let compressed = zlib.finish().unwrap();
    for part in compressed.chunks(7) {
        chunk(&mut output, b"IDAT", part);
    }
    chunk(&mut output, b"IEND", &[]);
    output
}

fn convert(rgba: &[u8], channels: u32) -> Vec<u8> {
    let mut expected = Vec::new();
    for sample in rgba.as_chunks::<4>().0 {
        let gray = ((sample[0] as u32 * 299 + sample[1] as u32 * 587 + sample[2] as u32 * 114 + 500) / 1000) as u8;
        match channels {
            1 => expected.push(gray),
            2 => expected.extend_from_slice(&[gray, sample[3]]),
            3 => expected.extend_from_slice(&sample[..3]),
            4 => expected.extend_from_slice(sample),
            _ => unreachable!(),
        }
    }
    expected
}

#[test]
fn every_legal_type_depth_filter_and_channel() {
    for color in [0, 2, 3, 4, 6] {
        let depths: &[u8] = match color {
            0 => &[1, 2, 4, 8, 16],
            3 => &[1, 2, 4, 8],
            _ => &[8, 16],
        };
        for &depth in depths {
            let channels = match color {
                0 | 3 => 1,
                2 => 3,
                4 => 2,
                _ => 4,
            };
            let maximum = if depth == 16 { 65535 } else { (1 << depth) - 1 };
            let mut image = Image {
                width: 7,
                height: 5,
                depth,
                color,
                samples: Vec::new(),
                extras: Vec::new(),
            };
            let palette: Vec<u8> = (0..=maximum.min(255))
                .flat_map(|index| [index as u8, 255 - index as u8, (index * 13) as u8])
                .collect();
            if color == 3 {
                image.extras.push((*b"PLTE", palette.clone()));
            }
            let mut rgba = Vec::new();
            for pixel in 0..35 {
                let samples: Vec<u16> = (0..channels)
                    .map(|component| ((pixel * 101 + component * 37) & maximum) as u16)
                    .collect();
                let scale = |sample: u16| {
                    if depth == 16 {
                        (sample >> 8) as u8
                    } else {
                        (sample as usize * 255 / maximum) as u8
                    }
                };
                let gray = scale(samples[0]);
                let expected = match color {
                    0 => [gray, gray, gray, 255],
                    2 => [gray, scale(samples[1]), scale(samples[2]), 255],
                    3 => {
                        let start = samples[0] as usize * 3;
                        [palette[start], palette[start + 1], palette[start + 2], 255]
                    }
                    4 => [gray, gray, gray, scale(samples[1])],
                    _ => [gray, scale(samples[1]), scale(samples[2]), scale(samples[3])],
                };
                image.samples.extend_from_slice(&samples);
                rgba.extend_from_slice(&expected);
            }
            let source_channels = if color == 3 { 3 } else { channels as u32 };
            assert!(decode(&encode(&image, true, 0), 4).is_err(), "Java rejects Adam7");
            for filter in 0..5 {
                let encoded = encode(&image, false, filter);
                for desired in 0..5 {
                    let actual = decode(&encoded, desired).unwrap();
                    let output_channels = if desired == 0 { source_channels } else { desired };
                    assert_eq!(actual.source_channels, source_channels);
                    assert_eq!(actual.output_channels, output_channels);
                    assert_eq!(
                        actual.pixels,
                        convert(&rgba, output_channels),
                        "color={color} depth={depth} filter={filter} desired={desired}"
                    );
                }
            }
        }
    }
}

#[test]
fn transparency_compares_full_samples_before_stripping_and_keeps_source_channels() {
    for color in [0, 2] {
        let channels = if color == 0 { 1 } else { 3 };
        let samples: Vec<u16> = (0..channels).map(|component| 0x1234 + component * 0x1010).collect();
        let mut image = Image {
            width: 2,
            height: 1,
            depth: 16,
            color,
            samples: samples.clone(),
            extras: vec![(
                *b"tRNS",
                samples.iter().flat_map(|sample| sample.to_be_bytes()).collect(),
            )],
        };
        image.samples.extend(samples.iter().map(|sample| sample + 1));
        let encoded = encode(&image, false, 0);
        let actual = decode(&encoded, 4).unwrap();
        assert_eq!(actual.source_channels, channels as u32);
        assert_eq!(actual.pixels[3], 0);
        assert_eq!(actual.pixels[7], 255);
        assert_eq!(&actual.pixels[..3], &actual.pixels[4..7]);
        assert_eq!(decode(&encoded, 0).unwrap().pixels.len(), 2 * channels as usize);
    }
}

#[test]
fn palette_transparency_gamma_and_partial_alpha() {
    let mut image = Image {
        width: 4,
        height: 1,
        depth: 2,
        color: 3,
        samples: vec![0, 1, 2, 3],
        extras: vec![
            (*b"PLTE", vec![255, 0, 0, 0, 255, 0, 0, 0, 255, 80, 80, 80]),
            (*b"tRNS", vec![0, 127]),
        ],
    };
    let plain = decode(&encode(&image, false, 0), 0).unwrap();
    assert_eq!(plain.source_channels, 4);
    assert_eq!(
        plain.pixels,
        vec![255, 0, 0, 0, 0, 255, 0, 127, 0, 0, 255, 255, 80, 80, 80, 255]
    );
    image.extras.push((*b"gAMA", 45455u32.to_be_bytes().to_vec()));
    image.extras.push((*b"sRGB", vec![0]));
    assert_eq!(decode(&encode(&image, false, 4), 0).unwrap(), plain);
    assert_eq!(
        decode(&encode(&image, false, 0), 1).unwrap().pixels,
        vec![76, 150, 29, 80]
    );
}

#[test]
fn rejects_corruption_truncation_and_resource_bombs() {
    let image = Image {
        width: 1,
        height: 1,
        depth: 8,
        color: 6,
        samples: vec![1, 2, 3, 4],
        extras: Vec::new(),
    };
    let encoded = encode(&image, false, 0);
    assert!(decode(&encoded, 5).is_err());
    for end in 0..encoded.len() - 12 {
        assert!(decode(&encoded[..end], 4).is_err(), "end={end}");
    }
    // Java does not check chunk CRCs, so neither does the kernel.
    let mut bad_crc = encoded.clone();
    bad_crc[29] ^= 1;
    assert_eq!(decode(&bad_crc, 4).unwrap(), decode(&encoded, 4).unwrap());
    let mut oversized = encoded.clone();
    oversized[16..20].copy_from_slice(&u32::MAX.to_be_bytes());
    assert!(decode(&oversized, 4).is_err());
    assert!(decode(&encode(&image, false, 5), 4).is_err());
}

fn zlib(raw: &[u8]) -> Vec<u8> {
    let mut encoder = ZlibEncoder::new(Vec::new(), Compression::default());
    encoder.write_all(raw).unwrap();
    encoder.finish().unwrap()
}

fn assemble(chunks: &[(&[u8; 4], Vec<u8>)]) -> Vec<u8> {
    let mut output = b"\x89PNG\r\n\x1a\n".to_vec();
    for (kind, bytes) in chunks {
        chunk(&mut output, kind, bytes);
    }
    output
}

fn header(width: u32, height: u32, depth: u8, color: u8) -> Vec<u8> {
    let mut header = width.to_be_bytes().to_vec();
    header.extend_from_slice(&height.to_be_bytes());
    header.extend_from_slice(&[depth, color, 0, 0, 0]);
    header
}

/// 4x2 indexed 8-bit rows (filter 0) using the given palette indices.
fn indexed(indices: [u8; 8], extras: &[(&[u8; 4], Vec<u8>)]) -> Vec<u8> {
    let mut raw = vec![0];
    raw.extend_from_slice(&indices[..4]);
    raw.push(0);
    raw.extend_from_slice(&indices[4..]);
    let mut chunks = vec![(b"IHDR", header(4, 2, 8, 3))];
    chunks.extend(extras.iter().cloned());
    chunks.push((b"IDAT", zlib(&raw)));
    chunks.push((b"IEND", Vec::new()));
    assemble(&chunks)
}

fn rgba_png(compressed: Vec<u8>) -> Vec<u8> {
    assemble(&[
        (b"IHDR", header(2, 2, 8, 6)),
        (b"IDAT", compressed),
        (b"IEND", Vec::new()),
    ])
}

const RGBA_ROWS: [u8; 18] = [0, 1, 2, 3, 4, 5, 6, 7, 8, 0, 9, 10, 11, 12, 13, 14, 15, 16];

#[test]
fn declines_what_java_rejects() {
    let good = zlib(&RGBA_ROWS);
    assert_eq!(
        decode(&rgba_png(good.clone()), 4).unwrap().pixels,
        [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16]
    );
    // Java's Inflater verifies the Adler-32 trailer.
    let mut bad_adler = good.clone();
    *bad_adler.last_mut().unwrap() ^= 0xff;
    assert!(decode(&rgba_png(bad_adler), 4).is_err());
    // A stream missing its trailer is "truncated" for Java.
    assert!(decode(&rgba_png(good[..good.len() - 4].to_vec()), 4).is_err());
    // More inflated data than the image needs is "stalled"; less is a length mismatch.
    let mut extra = RGBA_ROWS.to_vec();
    extra.extend_from_slice(&[0; 40]);
    assert!(decode(&rgba_png(zlib(&extra)), 4).is_err());
    assert!(decode(&rgba_png(zlib(&RGBA_ROWS[..17])), 4).is_err());
    // Bytes after the end of the zlib stream are ignored by Java.
    let mut trailing = good.clone();
    trailing.extend_from_slice(b"junk");
    assert_eq!(decode(&rgba_png(trailing), 4), decode(&rgba_png(good.clone()), 4));
    // Preset dictionary and unknown filter types.
    let mut dictionary = vec![0x78, 0xbb, 0, 0, 0, 1];
    dictionary.extend_from_slice(&good[2..]);
    assert!(decode(&rgba_png(dictionary), 4).is_err());
    let mut filter = RGBA_ROWS;
    filter[9] = 5;
    assert!(decode(&rgba_png(zlib(&filter)), 4).is_err());
    let palette = vec![255, 0, 0, 0, 255, 0];
    // Palette index 2 of a two-entry palette, and a missing palette.
    assert!(decode(&indexed([0, 1, 2, 0, 1, 0, 1, 0], &[(b"PLTE", palette.clone())]), 4).is_err());
    assert!(decode(&indexed([0; 8], &[]), 4).is_err());
    // Interlaced images and chunk lengths past the end of the file.
    let mut interlaced = rgba_png(good.clone());
    interlaced[28] = 1;
    assert!(decode(&interlaced, 4).is_err());
    let mut overrun = rgba_png(good);
    overrun[33..37].copy_from_slice(&1000u32.to_be_bytes());
    assert!(decode(&overrun, 4).is_err());
}

#[test]
fn declines_palette_layouts_java_reads_differently() {
    let palette = vec![255, 0, 0, 0, 255, 0, 0, 0, 255, 9, 9, 9];
    let indices = [0, 1, 2, 3, 3, 2, 1, 0];
    let plain = decode(&indexed(indices, &[(b"PLTE", palette.clone())]), 4).unwrap();
    assert_eq!(&plain.pixels[..8], &[255, 0, 0, 255, 0, 255, 0, 255]);
    let alpha = decode(
        &indexed(indices, &[(b"PLTE", palette.clone()), (b"tRNS", vec![7, 8])]),
        0,
    )
    .unwrap();
    assert_eq!(alpha.source_channels, 4);
    assert_eq!(&alpha.pixels[..12], &[255, 0, 0, 7, 0, 255, 0, 8, 0, 0, 255, 255]);
    let declined = [
        // tRNS before PLTE, longer than the palette, or repeated.
        vec![(b"tRNS", vec![1]), (b"PLTE", palette.clone())],
        vec![(b"PLTE", palette.clone()), (b"tRNS", vec![1, 2, 3, 4, 5])],
        vec![(b"PLTE", palette.clone()), (b"tRNS", vec![1]), (b"tRNS", vec![2])],
        // PLTE that is not whole entries (png 0.18.1 panicked here), empty, or repeated.
        vec![(b"PLTE", palette[..10].to_vec())],
        vec![(b"PLTE", palette[..11].to_vec())],
        vec![(b"PLTE", Vec::new())],
        vec![(b"PLTE", palette.clone()), (b"PLTE", palette.clone())],
    ];
    for extras in declined {
        assert!(decode(&indexed(indices, &extras), 4).is_err(), "{extras:?}");
    }
}

#[test]
fn arbitrary_mutations_never_panic() {
    let palette: Vec<u8> = (0..48).collect();
    let mut seeds = vec![
        indexed(
            [0, 1, 2, 3, 4, 5, 6, 7],
            &[(b"PLTE", palette), (b"tRNS", vec![1, 2, 3])],
        ),
        rgba_png(zlib(&RGBA_ROWS)),
    ];
    for (depth, color) in [(1, 0), (16, 2), (16, 4), (4, 3)] {
        let channels = [1, 0, 3, 1, 2, 0, 4][color as usize];
        let image = Image {
            width: 5,
            height: 3,
            depth,
            color,
            samples: (0..15 * channels).map(|index| (index * 37 % 15) as u16).collect(),
            extras: if color == 3 {
                vec![(*b"PLTE", (0..48).collect())]
            } else {
                Vec::new()
            },
        };
        seeds.push(encode(&image, false, 4));
    }
    let mut state = 0x9e37_79b9_7f4a_7c15u64;
    let mut next = move |bound: usize| {
        state ^= state << 13;
        state ^= state >> 7;
        state ^= state << 17;
        (state % bound as u64) as usize
    };
    for round in 0..20_000 {
        let mut mutant = seeds[round % seeds.len()].clone();
        for _ in 0..1 + next(4) {
            let position = 8 + next(mutant.len() - 8);
            match next(4) {
                0 => mutant[position] ^= 1 << next(8),
                1 => mutant[position] = next(256) as u8,
                2 => mutant.truncate(position.max(9)),
                _ => mutant.insert(position, next(256) as u8),
            }
        }
        for desired in [0, 4] {
            let _ = decode(&mutant, desired);
        }
    }
}
