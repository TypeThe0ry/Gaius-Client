import { deflateSync, inflateSync } from 'node:zlib';

const PNG_SIGNATURE = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);

function crc32(buffer) {
  let crc = 0xffffffff;
  for (const byte of buffer) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit++) {
      crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1));
    }
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function paethPredictor(left, above, upperLeft) {
  const prediction = left + above - upperLeft;
  const leftDistance = Math.abs(prediction - left);
  const aboveDistance = Math.abs(prediction - above);
  const upperLeftDistance = Math.abs(prediction - upperLeft);
  if (leftDistance <= aboveDistance && leftDistance <= upperLeftDistance) return left;
  return aboveDistance <= upperLeftDistance ? above : upperLeft;
}

export function decodePng(buffer) {
  if (!Buffer.isBuffer(buffer)) buffer = Buffer.from(buffer);
  if (buffer.length < PNG_SIGNATURE.length || !buffer.subarray(0, 8).equals(PNG_SIGNATURE)) {
    throw new Error('invalid PNG signature');
  }

  let offset = 8;
  let ihdr = null;
  const idat = [];
  let sawEnd = false;
  while (offset < buffer.length) {
    if (offset + 12 > buffer.length) throw new Error('truncated PNG chunk header');
    const length = buffer.readUInt32BE(offset);
    const type = buffer.toString('ascii', offset + 4, offset + 8);
    const dataStart = offset + 8;
    const dataEnd = dataStart + length;
    const crcOffset = dataEnd;
    if (crcOffset + 4 > buffer.length) throw new Error(`truncated PNG ${type} chunk`);
    const declaredCrc = buffer.readUInt32BE(crcOffset);
    const actualCrc = crc32(buffer.subarray(offset + 4, dataEnd));
    if (declaredCrc !== actualCrc) throw new Error(`PNG ${type} CRC mismatch`);
    const data = buffer.subarray(dataStart, dataEnd);
    if (type === 'IHDR') {
      if (ihdr || length !== 13) throw new Error('invalid PNG IHDR');
      ihdr = {
        width: data.readUInt32BE(0),
        height: data.readUInt32BE(4),
        bitDepth: data[8],
        colorType: data[9],
        compression: data[10],
        filter: data[11],
        interlace: data[12],
      };
    } else if (type === 'IDAT') {
      idat.push(data);
    } else if (type === 'IEND') {
      sawEnd = true;
      break;
    }
    offset = crcOffset + 4;
  }

  if (!ihdr || !sawEnd || idat.length === 0) throw new Error('PNG lacks IHDR, IDAT, or IEND');
  if (ihdr.width < 1 || ihdr.height < 1 || ihdr.width > 16_384 || ihdr.height > 16_384) {
    throw new Error(`unsupported PNG dimensions ${ihdr.width}x${ihdr.height}`);
  }
  if (ihdr.bitDepth !== 8 || ihdr.compression !== 0 || ihdr.filter !== 0 || ihdr.interlace !== 0) {
    throw new Error(`unsupported PNG encoding depth=${ihdr.bitDepth} interlace=${ihdr.interlace}`);
  }
  const channelsByColorType = new Map([[0, 1], [2, 3], [4, 2], [6, 4]]);
  const channels = channelsByColorType.get(ihdr.colorType);
  if (!channels) throw new Error(`unsupported PNG color type ${ihdr.colorType}`);
  const stride = ihdr.width * channels;
  const expectedInflatedBytes = ihdr.height * (stride + 1);
  const inflated = inflateSync(Buffer.concat(idat), { maxOutputLength: expectedInflatedBytes });
  if (inflated.length !== expectedInflatedBytes) {
    throw new Error(`PNG inflated size mismatch ${inflated.length}/${expectedInflatedBytes}`);
  }

  const decoded = Buffer.alloc(ihdr.height * stride);
  let sourceOffset = 0;
  for (let y = 0; y < ihdr.height; y++) {
    const filter = inflated[sourceOffset++];
    const rowOffset = y * stride;
    const previousOffset = rowOffset - stride;
    for (let x = 0; x < stride; x++) {
      const raw = inflated[sourceOffset++];
      const left = x >= channels ? decoded[rowOffset + x - channels] : 0;
      const above = y > 0 ? decoded[previousOffset + x] : 0;
      const upperLeft = y > 0 && x >= channels ? decoded[previousOffset + x - channels] : 0;
      let value;
      switch (filter) {
        case 0: value = raw; break;
        case 1: value = raw + left; break;
        case 2: value = raw + above; break;
        case 3: value = raw + Math.floor((left + above) / 2); break;
        case 4: value = raw + paethPredictor(left, above, upperLeft); break;
        default: throw new Error(`unsupported PNG row filter ${filter}`);
      }
      decoded[rowOffset + x] = value & 0xff;
    }
  }

  const rgba = Buffer.alloc(ihdr.width * ihdr.height * 4);
  for (let pixel = 0, source = 0, target = 0; pixel < ihdr.width * ihdr.height; pixel++) {
    if (ihdr.colorType === 0) {
      const gray = decoded[source++];
      rgba[target++] = gray; rgba[target++] = gray; rgba[target++] = gray; rgba[target++] = 255;
    } else if (ihdr.colorType === 2) {
      rgba[target++] = decoded[source++]; rgba[target++] = decoded[source++];
      rgba[target++] = decoded[source++]; rgba[target++] = 255;
    } else if (ihdr.colorType === 4) {
      const gray = decoded[source++];
      rgba[target++] = gray; rgba[target++] = gray; rgba[target++] = gray;
      rgba[target++] = decoded[source++];
    } else {
      rgba[target++] = decoded[source++]; rgba[target++] = decoded[source++];
      rgba[target++] = decoded[source++]; rgba[target++] = decoded[source++];
    }
  }
  return { width: ihdr.width, height: ihdr.height, rgba };
}

function sampleRgba(decoded, width = 192, height = 108) {
  const sampleWidth = Math.min(width, decoded.width);
  const sampleHeight = Math.min(height, decoded.height);
  const sampled = Buffer.alloc(sampleWidth * sampleHeight * 4);
  for (let y = 0; y < sampleHeight; y++) {
    const sourceY = Math.min(decoded.height - 1, Math.floor((y + 0.5) * decoded.height / sampleHeight));
    for (let x = 0; x < sampleWidth; x++) {
      const sourceX = Math.min(decoded.width - 1, Math.floor((x + 0.5) * decoded.width / sampleWidth));
      const source = (sourceY * decoded.width + sourceX) * 4;
      const target = (y * sampleWidth + x) * 4;
      sampled[target] = decoded.rgba[source];
      sampled[target + 1] = decoded.rgba[source + 1];
      sampled[target + 2] = decoded.rgba[source + 2];
      sampled[target + 3] = decoded.rgba[source + 3];
    }
  }
  return { width: sampleWidth, height: sampleHeight, rgba: sampled };
}

function luminanceAt(rgba, pixel) {
  const offset = pixel * 4;
  return 0.2126 * rgba[offset] + 0.7152 * rgba[offset + 1] + 0.0722 * rgba[offset + 2];
}

function regionMetrics(image, bounds) {
  const left = Math.max(0, Math.min(image.width - 1, Math.floor(bounds.left)));
  const right = Math.max(left + 1, Math.min(image.width, Math.ceil(bounds.right)));
  const top = Math.max(0, Math.min(image.height - 1, Math.floor(bounds.top)));
  const bottom = Math.max(top + 1, Math.min(image.height, Math.ceil(bounds.bottom)));
  const buckets = new Uint32Array(4096);
  let pixels = 0;
  let nonBlack = 0;
  let sum = 0;
  let squareSum = 0;
  let colorBuckets = 0;
  let dominant = 0;
  let edges = 0;
  let edgeComparisons = 0;
  let horizontalEdges = 0;
  let horizontalComparisons = 0;
  let verticalEdges = 0;
  let verticalComparisons = 0;
  for (let y = top; y < bottom; y++) {
    for (let x = left; x < right; x++) {
      const pixel = y * image.width + x;
      const offset = pixel * 4;
      const luminance = luminanceAt(image.rgba, pixel);
      if (image.rgba[offset + 3] > 0 && luminance > 4) nonBlack++;
      sum += luminance;
      squareSum += luminance * luminance;
      const bucket = ((image.rgba[offset] >>> 4) << 8)
        | ((image.rgba[offset + 1] >>> 4) << 4)
        | (image.rgba[offset + 2] >>> 4);
      const count = ++buckets[bucket];
      if (count === 1) colorBuckets++;
      if (count > dominant) dominant = count;
      if (x > left) {
        horizontalComparisons++;
        if (Math.abs(luminance - luminanceAt(image.rgba, pixel - 1)) >= 10) horizontalEdges++;
      }
      if (y > top) {
        verticalComparisons++;
        if (Math.abs(luminance - luminanceAt(image.rgba, pixel - image.width)) >= 10) verticalEdges++;
      }
      pixels++;
    }
  }
  edges = horizontalEdges + verticalEdges;
  edgeComparisons = horizontalComparisons + verticalComparisons;
  const mean = pixels ? sum / pixels : 0;
  return {
    pixelCount: pixels,
    nonBlackRatio: pixels ? nonBlack / pixels : 0,
    luminanceStdDev: Math.sqrt(Math.max(0, pixels ? squareSum / pixels - mean * mean : 0)),
    colorBuckets,
    dominantColorRatio: pixels ? dominant / pixels : 1,
    edgeDensity: edgeComparisons ? edges / edgeComparisons : 0,
    horizontalEdgeDensity: horizontalComparisons ? horizontalEdges / horizontalComparisons : 0,
    verticalEdgeDensity: verticalComparisons ? verticalEdges / verticalComparisons : 0,
  };
}

export function terrainVisualPass(visual) {
  return visual?.available === true
    && Number(visual.nonBlackRatio) >= 0.01
    && Number(visual.luminanceStdDev) >= 1
    && Number(visual.colorBuckets) >= 8
    && Number(visual.centralLuminanceStdDev) >= 1
    && Number(visual.centralColorBuckets) >= 8
    && Number(visual.centralDominantColorRatio) <= 0.995
    && Number(visual.activeTileCount) >= 6
    && Number(visual.lowerLuminanceStdDev) >= 4
    && Number(visual.lowerColorBuckets) >= 12
    && Number(visual.lowerEdgeDensity) >= 0.018
    && Number(visual.lowerTexturedTileCount) >= 4
    && Number(visual.lowerTexturedRowCount) >= 2
    && Number(visual.lowerTexturedColumnCount) >= 2;
}

export function analyzeTerrainPng(png) {
  const decoded = decodePng(png);
  const image = sampleRgba(decoded);
  const full = regionMetrics(image, { left: 0, right: image.width, top: 0, bottom: image.height });
  const central = regionMetrics(image, {
    left: image.width * 0.08,
    right: image.width * 0.92,
    top: image.height * 0.08,
    bottom: image.height * 0.80,
  });
  // Exclude the hotbar/chat strip at the bottom while forcing texture across
  // the lower scene, where real Minecraft terrain must be visible. A sky plus
  // crosshair/HUD can satisfy whole-frame color variance but not this region.
  const lowerBounds = {
    left: image.width * 0.05,
    right: image.width * 0.95,
    top: image.height * 0.45,
    bottom: image.height * 0.88,
  };
  const lower = regionMetrics(image, lowerBounds);
  const columns = 4;
  const rows = 3;
  const tiles = [];
  const texturedByRow = Array(rows).fill(0);
  const texturedByColumn = Array(columns).fill(0);
  for (let row = 0; row < rows; row++) {
    for (let column = 0; column < columns; column++) {
      const left = lowerBounds.left + (lowerBounds.right - lowerBounds.left) * column / columns;
      const right = lowerBounds.left + (lowerBounds.right - lowerBounds.left) * (column + 1) / columns;
      const top = lowerBounds.top + (lowerBounds.bottom - lowerBounds.top) * row / rows;
      const bottom = lowerBounds.top + (lowerBounds.bottom - lowerBounds.top) * (row + 1) / rows;
      const metrics = regionMetrics(image, { left, right, top, bottom });
      const textured = metrics.luminanceStdDev >= 3
        && metrics.colorBuckets >= 6
        && metrics.edgeDensity >= 0.025;
      if (textured) {
        texturedByRow[row]++;
        texturedByColumn[column]++;
      }
      tiles.push({ row, column, textured, ...metrics });
    }
  }
  const lowerTexturedTileCount = tiles.filter((tile) => tile.textured).length;
  const lowerTexturedRowCount = texturedByRow.filter((count) => count >= 2).length;
  const lowerTexturedColumnCount = texturedByColumn.filter((count) => count >= 1).length;
  // Compatibility metric: a broad non-black tile count used by older evidence.
  const activeTileCount = tiles.filter((tile) => tile.nonBlackRatio >= 0.05).length;
  const visual = {
    schema: 'gaius.terrain-visual-metrics.v2',
    available: true,
    sourceWidth: decoded.width,
    sourceHeight: decoded.height,
    sampleWidth: image.width,
    sampleHeight: image.height,
    nonBlackRatio: full.nonBlackRatio,
    luminanceStdDev: full.luminanceStdDev,
    colorBuckets: full.colorBuckets,
    dominantColorRatio: full.dominantColorRatio,
    centralLuminanceStdDev: central.luminanceStdDev,
    centralColorBuckets: central.colorBuckets,
    centralDominantColorRatio: central.dominantColorRatio,
    activeTileCount,
    tileCount: tiles.length,
    lowerNonBlackRatio: lower.nonBlackRatio,
    lowerLuminanceStdDev: lower.luminanceStdDev,
    lowerColorBuckets: lower.colorBuckets,
    lowerDominantColorRatio: lower.dominantColorRatio,
    lowerEdgeDensity: lower.edgeDensity,
    lowerHorizontalEdgeDensity: lower.horizontalEdgeDensity,
    lowerVerticalEdgeDensity: lower.verticalEdgeDensity,
    lowerTexturedTileCount,
    lowerTexturedRowCount,
    lowerTexturedColumnCount,
    lowerTexturedByRow: texturedByRow,
    lowerTexturedByColumn: texturedByColumn,
    lowerTiles: tiles,
  };
  visual.terrainVisualPass = terrainVisualPass(visual);
  return visual;
}

function pngChunk(type, data) {
  const typeBuffer = Buffer.from(type, 'ascii');
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(Buffer.concat([typeBuffer, data])));
  return Buffer.concat([length, typeBuffer, data, crc]);
}

/** Test-fixture encoder. Production screenshots are decoded, never re-encoded. */
export function encodeRgbaPng(width, height, rgba) {
  if (!Number.isInteger(width) || !Number.isInteger(height) || width < 1 || height < 1) {
    throw new Error('invalid PNG fixture dimensions');
  }
  const pixels = Buffer.from(rgba);
  if (pixels.length !== width * height * 4) throw new Error('invalid RGBA fixture length');
  const raw = Buffer.alloc(height * (width * 4 + 1));
  for (let y = 0; y < height; y++) {
    const output = y * (width * 4 + 1);
    raw[output] = 0;
    pixels.copy(raw, output + 1, y * width * 4, (y + 1) * width * 4);
  }
  const header = Buffer.alloc(13);
  header.writeUInt32BE(width, 0);
  header.writeUInt32BE(height, 4);
  header[8] = 8;
  header[9] = 6;
  return Buffer.concat([
    PNG_SIGNATURE,
    pngChunk('IHDR', header),
    pngChunk('IDAT', deflateSync(raw, { level: 9 })),
    pngChunk('IEND', Buffer.alloc(0)),
  ]);
}

export function createTerrainVisualFixture({ skyOnly = false, width = 320, height = 180 } = {}) {
  const rgba = Buffer.alloc(width * height * 4);
  const setPixel = (x, y, red, green, blue, alpha = 255) => {
    const offset = (y * width + x) * 4;
    rgba[offset] = red; rgba[offset + 1] = green; rgba[offset + 2] = blue; rgba[offset + 3] = alpha;
  };
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      if (!skyOnly && y >= Math.floor(height * 0.42)) {
        const blockX = Math.floor(x / 16), blockY = Math.floor(y / 12);
        // Span several 4-bit RGB buckets in every lower-scene tile. This is
        // intentionally more varied than the sky/HUD negative fixture so the
        // positive exercises the same >=12 color-bucket gate as real terrain.
        const grain = ((x * 17 + y * 31 + (x ^ y) * 7) & 63) - 31;
        const crossGrain = ((x * 29 + y * 11 + (x ^ (y * 3)) * 5) & 47) - 23;
        const checker = (blockX + blockY) & 1;
        const base = checker ? [74, 128, 48] : [112, 91, 48];
        setPixel(x, y,
          Math.max(0, Math.min(255, base[0] + grain)),
          Math.max(0, Math.min(255, base[1] + crossGrain)),
          Math.max(0, Math.min(255, base[2] + Math.floor((grain - crossGrain) / 2))));
      } else {
        const gradient = Math.floor(y * 18 / height);
        setPixel(x, y, 82 + gradient, 151 + gradient, 224 + gradient);
      }
    }
  }
  // A deliberately colorful HUD/crosshair makes the sky fixture pass broad
  // whole-frame color-count checks while the lower texture gate still rejects it.
  for (let y = height - 14; y < height - 3; y++) {
    for (let x = Math.floor(width * 0.25); x < Math.floor(width * 0.75); x++) {
      const slot = Math.floor((x - width * 0.25) / Math.max(1, width * 0.5 / 9));
      const palette = [[30, 30, 30], [220, 180, 40], [160, 70, 40], [80, 180, 80]];
      const color = palette[slot % palette.length];
      setPixel(x, y, color[0], color[1], color[2]);
    }
  }
  const centerX = Math.floor(width / 2), centerY = Math.floor(height / 2);
  for (let offset = -6; offset <= 6; offset++) {
    setPixel(centerX + offset, centerY, 255, 255, 255);
    setPixel(centerX, centerY + offset, 255, 255, 255);
  }
  return encodeRgbaPng(width, height, rgba);
}
