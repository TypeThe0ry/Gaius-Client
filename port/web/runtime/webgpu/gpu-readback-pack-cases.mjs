/** Byte-exact GPU readback checks, shared by the Node mock and a real WebGPU browser run. */
export async function runReadbackPackCases(gpu, device, queue) {
  let count = 0;
  const cases = [
    ['r8unorm', 1, 1, 3, 0], ['r8unorm', 1, 3, 3, 1],
    ['r8unorm', 1, 257, 3, 3], ['rg8unorm', 2, 3, 3, 2],
    ['rgba8unorm', 4, 3, 3, 4], ['rgba8unorm', 4, 64, 2, 4],
    ['rgba8unorm', 4, 64, 2, 0], ['r8unorm', 1, 1, 1, 3],
    ['rg8unorm', 2, 128, 1, 1], ['rgba8unorm', 4, 1, 1, 4]
  ];
  for (const [format, texel, width, height, offset] of cases) {
    const length = width * height * texel;
    const size = Math.ceil((offset + length + 7) / 4) * 4;
    const initial = Uint8Array.from({length: size}, (_, i) => (i * 17 + 91) & 255);
    const pixels = Uint8Array.from({length: length * 2}, (_, i) => (i * 29 + Math.floor(i / length) * 37) & 255);
    const expected = initial.slice();
    expected.set(pixels.subarray(length), offset);
    const texture = gpu.createTexture(width, height, 2, 1, format, 0x03, 'pack readback test');
    queue.writeTexture({texture: gpu.debugObject(texture)}, pixels,
      {bytesPerRow: width * texel, rowsPerImage: height}, {width, height, depthOrArrayLayers: 2});
    const destination = gpu.createBuffer(size, 0x0c, 'pack readback destination');
    gpu.writeBuffer(destination, 0, initial);
    // Overwrite the same region twice in one submit: the final layer must win.
    gpu.copyTextureToBufferRows(texture, 0, 0, 0, 0, width, height, texel, destination, offset);
    gpu.copyTextureToBufferRows(texture, 0, 1, 0, 0, width, height, texel, destination, offset);
    gpu.submit();
    const readback = device.createBuffer({size, usage: 9});
    const commands = device.createCommandEncoder();
    commands.copyBufferToBuffer(gpu.debugObject(destination), 0, readback, 0, size);
    queue.submit([commands.finish()]);
    await readback.mapAsync(1);
    const actual = new Uint8Array(readback.getMappedRange()).slice();
    readback.unmap();
    readback.destroy();
    gpu.destroyBuffer(destination);
    gpu.destroyTexture(texture);
    for (let i = 0; i < size; i++) {
      if (actual[i] !== expected[i]) {
        throw new Error(`${format} ${width}x${height} offset=${offset} byte=${i}: ${actual[i]} != ${expected[i]}`);
      }
    }
    count++;
  }
  return {cases: count};
}
