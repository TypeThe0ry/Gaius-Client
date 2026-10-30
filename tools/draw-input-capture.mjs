import {createHash} from "node:crypto";

function installDrawCapture() {
  const shadows = new WeakMap(), mapped = new WeakMap(), uniforms = new WeakMap(), textureWrites = new WeakMap(), indexWidths = new WeakMap();
  const shaders = [], failures = [], samplerStates = [], pipelineStates = [];
  const textureDescriptors = new WeakMap(), textureSizes = new WeakMap();
  const textureDevices = new WeakMap(), textureContexts = new WeakMap(), samplerDescriptors = new WeakMap();
  let textureBytes = 0;
  let uploadCount = 0;
  let presentSequence = 0;
  let active = null, pending = null;
  const hash = async data => Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", data)), value => value.toString(16).padStart(2, "0")).join("");
  const bytes = (data, offset = 0, length) => {
    const view = ArrayBuffer.isView(data) ? new Uint8Array(data.buffer, data.byteOffset, data.byteLength) : new Uint8Array(data);
    return view.subarray(offset, length === undefined ? view.length : offset + length);
  };
  const write = (buffer, offset, data) => {
    let shadow = shadows.get(buffer);
    const size = Math.max(shadow?.data.length || 0, offset + data.length);
    if (size > 64 * 1024 * 1024) throw new Error("draw capture buffer bound exceeded");
    if (!shadow || shadow.data.length < size) {
      const replacement = new Uint8Array(size);
      if (shadow) replacement.set(shadow.data);
      shadow = {data: replacement, revision: shadow?.revision || 0};
      shadows.set(buffer, shadow);
    }
    shadow.data.set(data, offset); shadow.revision++;
  };
  const refreshMapped = (buffer, start = 0, length = Infinity) => {
    for (const [offset, data] of mapped.get(buffer) || []) {
      const first = Math.max(offset, start), last = Math.min(offset + data.byteLength, start + length);
      if (last > first) write(buffer, first, new Uint8Array(data, first - offset, last - first));
    }
  };
  const copy = (source, sourceOffset, target, targetOffset, length) => {
    refreshMapped(source, sourceOffset, length);
    const data = shadows.get(source)?.data;
    if (!data || sourceOffset + length > data.length) { failures.push("unknown buffer copy"); return; }
    write(target, targetOffset, data.slice(sourceOffset, sourceOffset + length));
  };
  const wrap = (prototype, name, before) => {
    if (!prototype?.[name]) return;
    const original = prototype[name];
    prototype[name] = function(...args) {
      try { before(this, args); } catch (error) { failures.push(name + ": " + error.message); }
      return original.apply(this, args);
    };
  };
  const textureUpload = (destination, raw, layout, extent, format) => {
    if (globalThis.__gaiusCaptureBufferOnly) return;
    const width = extent.width ?? extent[0], height = extent.height ?? extent[1] ?? 1;
    if (!width || !height || !raw || !["rgba8unorm", "rgba8unorm-srgb"].includes(format)) return;
    const stride = layout.bytesPerRow || width * 4;
    const origin = destination.origin || {};
    const mip = destination.mipLevel || 0, x = origin.x ?? origin[0] ?? 0, y = origin.y ?? origin[1] ?? 0;
    const descriptor = textureDescriptors.get(destination.texture);
    const previous = textureWrites.get(destination.texture)?.get([mip, origin.z ?? origin[2] ?? 0].join(":"));
    const levelWidth = descriptor ? Math.max(1, descriptor.width >> mip) : Math.max(previous?.width || 0, x + width);
    const levelHeight = descriptor ? Math.max(1, descriptor.height >> mip) : Math.max(previous?.height || 0, y + height);
    if (levelWidth * levelHeight > 4194304 || raw.length < (height - 1) * stride + width * 4
        || x < 0 || y < 0 || width < 1 || height < 1
        || x + width > levelWidth || y + height > levelHeight) throw new Error("invalid texture upload extent");
    uploadCount++;
    if (destination.texture) {
      if (!textureWrites.has(destination.texture)) textureWrites.set(destination.texture, new Map());
      const writes = textureWrites.get(destination.texture);
      const key = [mip, origin.z ?? origin[2] ?? 0].join(":");
      let entry = writes.get(key);
      if (!entry || entry.width < levelWidth || entry.height < levelHeight) {
        const added = levelWidth * levelHeight * 5 - (entry ? entry.width * entry.height * 5 : 0);
        if (textureBytes + added > 128 * 1024 * 1024) throw new Error("draw capture texture bound exceeded");
        textureBytes += added;
        textureSizes.set(destination.texture, (textureSizes.get(destination.texture) || 0) + added);
        const replacement = {mip, width: levelWidth, height: levelHeight, layer: origin.z ?? origin[2] ?? 0,
          format, data: new Uint8Array(levelWidth * levelHeight * 4), covered: new Uint8Array(levelWidth * levelHeight)};
        if (entry) for (let row = 0; row < entry.height; row++) {
          replacement.data.set(entry.data.subarray(row * entry.width * 4, (row + 1) * entry.width * 4), row * levelWidth * 4);
          replacement.covered.set(entry.covered.subarray(row * entry.width, (row + 1) * entry.width), row * levelWidth);
        }
        entry = replacement; writes.set(key, entry);
      }
      for (let row = 0; row < height; row++) {
        entry.data.set(raw.subarray(row * stride, row * stride + width * 4), ((y + row) * entry.width + x) * 4);
        entry.covered.fill(1, (y + row) * entry.width + x, (y + row) * entry.width + x + width);
      }
      entry.sha256 = null;
      writes.revision = (writes.revision || 0) + 1;
    }
  };
  const copyTexture = (source, destination, extent) => {
    if (globalThis.__gaiusCaptureBufferOnly) return;
    const origin = source.origin || {}, width = extent.width ?? extent[0], height = extent.height ?? extent[1] ?? 1;
    const mip = source.mipLevel || 0, layer = origin.z ?? origin[2] ?? 0;
    const entry = textureWrites.get(source.texture)?.get([mip, layer].join(":"));
    if (!entry) { failures.push("unknown texture copy source"); return; }
    const x = origin.x ?? origin[0] ?? 0, y = origin.y ?? origin[1] ?? 0;
    if (x < 0 || y < 0 || width < 1 || height < 1 || x + width > entry.width || y + height > entry.height) {
      failures.push("out-of-bounds texture copy source"); return;
    }
    const raw = new Uint8Array(width * height * 4);
    for (let row = 0; row < height; row++) {
      const first = (y + row) * entry.width + x;
      if (entry.covered.subarray(first, first + width).some(value => !value)) { failures.push("incomplete texture copy source"); return; }
      raw.set(entry.data.subarray(first * 4, (first + width) * 4), row * width * 4);
    }
    textureUpload(destination, raw, {}, extent, entry.format);
  };
  const forgetTexture = texture => {
    textureBytes -= textureSizes.get(texture) || 0;
    textureSizes.delete(texture); textureWrites.delete(texture); textureDescriptors.delete(texture);
  };
  const readTextureMips = async (texture, dimensions) => {
    const device = textureDevices.get(texture), gl = textureContexts.get(texture);
    const width = texture?.width || dimensions.width, height = texture?.height || dimensions.height;
    const mipLevels = texture?.mipLevelCount || dimensions.mipLevels;
    if (!Number.isInteger(mipLevels) || mipLevels < 1 || mipLevels > 16
        || width * height > 4194304 || !width || !height) throw new Error("invalid texture readback extent");
    if (!device && !gl) throw new Error("texture readback has no native owner");
    const format = device ? texture.format : textureDescriptors.get(texture)?.format;
    if (!["rgba8unorm", "rgba8unorm-srgb"].includes(format)) throw new Error("texture readback requires a verified RGBA8 format");
    if (device && (!(texture.usage & 1) || !["rgba8unorm", "rgba8unorm-srgb"].includes(texture.format)))
      throw new Error("texture readback requires copyable RGBA8");
    const result = [];
    for (let mip = 0; mip < mipLevels; mip++) {
      const levelWidth = Math.max(1, width >> mip), levelHeight = Math.max(1, height >> mip);
      const raw = new Uint8Array(levelWidth * levelHeight * 4);
      if (device) {
        const stride = Math.ceil(levelWidth * 4 / 256) * 256;
        const buffer = device.createBuffer({size: stride * levelHeight, usage: 9});
        try {
          const encoder = device.createCommandEncoder();
          encoder.copyTextureToBuffer({texture, mipLevel: mip}, {buffer, bytesPerRow: stride},
            {width: levelWidth, height: levelHeight, depthOrArrayLayers: 1});
          device.queue.submit([encoder.finish()]);
          await buffer.mapAsync(1);
          const padded = new Uint8Array(buffer.getMappedRange());
          for (let row = 0; row < levelHeight; row++)
            raw.set(padded.subarray(row * stride, row * stride + levelWidth * 4), row * levelWidth * 4);
          buffer.unmap();
        } finally { buffer.destroy(); }
      } else {
        const framebuffer = gl.createFramebuffer(), previous = gl.getParameter(gl.READ_FRAMEBUFFER_BINDING);
        const packBuffer = gl.getParameter(gl.PIXEL_PACK_BUFFER_BINDING);
        const pack = [gl.PACK_ALIGNMENT, gl.PACK_ROW_LENGTH, gl.PACK_SKIP_ROWS, gl.PACK_SKIP_PIXELS]
          .map(parameter => [parameter, gl.getParameter(parameter)]);
        try {
          gl.bindBuffer(gl.PIXEL_PACK_BUFFER, null);
          for (const [parameter] of pack) gl.pixelStorei(parameter, parameter === gl.PACK_ALIGNMENT ? 1 : 0);
          gl.bindFramebuffer(gl.READ_FRAMEBUFFER, framebuffer);
          gl.framebufferTexture2D(gl.READ_FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, texture, mip);
          gl.readBuffer(gl.COLOR_ATTACHMENT0);
          if (gl.checkFramebufferStatus(gl.READ_FRAMEBUFFER) !== gl.FRAMEBUFFER_COMPLETE)
            throw new Error("texture readback framebuffer is incomplete");
          if (gl.getError() !== gl.NO_ERROR) throw new Error("texture readback inherited a GL error");
          gl.readPixels(0, 0, levelWidth, levelHeight, gl.RGBA, gl.UNSIGNED_BYTE, raw);
          if (gl.getError() !== gl.NO_ERROR) throw new Error("texture readback failed");
        } finally {
          gl.bindFramebuffer(gl.READ_FRAMEBUFFER, previous);
          gl.bindBuffer(gl.PIXEL_PACK_BUFFER, packBuffer);
          for (const [parameter, value] of pack) gl.pixelStorei(parameter, value);
          gl.deleteFramebuffer(framebuffer);
        }
      }
      result.push({mip, layer: 0, width: levelWidth, height: levelHeight,
        format, covered: levelWidth * levelHeight,
        unknown: false, sha256: await hash(raw)});
    }
    return result;
  };
  if (globalThis.GPUTexture) wrap(GPUTexture.prototype, "destroy", texture => forgetTexture(texture));
  if (globalThis.GPUBuffer) {
    const original = GPUBuffer.prototype.getMappedRange;
    GPUBuffer.prototype.getMappedRange = function(...args) {
      const result = original.apply(this, args);
      if (!mapped.has(this)) mapped.set(this, new Map());
      mapped.get(this).set(args[0] || 0, result);
      return result;
    };
    wrap(GPUBuffer.prototype, "unmap", buffer => {
      refreshMapped(buffer);
      mapped.delete(buffer);
    });
    wrap(GPUQueue.prototype, "writeBuffer", (_, [buffer, offset, data, dataOffset = 0, size]) => {
      const stride = ArrayBuffer.isView(data) ? data.BYTES_PER_ELEMENT || 1 : 1;
      write(buffer, offset, bytes(data, dataOffset * stride, size === undefined ? undefined : size * stride));
    });
    wrap(GPUCommandEncoder.prototype, "copyBufferToBuffer", (_, args) => copy(...args));
    const createTexture = GPUDevice.prototype.createTexture;
    GPUDevice.prototype.createTexture = function(descriptor) {
      if (globalThis.__gaiusCaptureReadTextures && ["rgba8unorm", "rgba8unorm-srgb"].includes(descriptor.format))
        descriptor = {...descriptor, usage: descriptor.usage | 1};
      const texture = createTexture.call(this, descriptor);
      textureDescriptors.set(texture, {width: descriptor.size.width ?? descriptor.size[0], height: descriptor.size.height ?? descriptor.size[1], format: descriptor.format});
      textureDevices.set(texture, this);
      return texture;
    };
    wrap(GPUCommandEncoder.prototype, "copyTextureToTexture", (_, args) => copyTexture(...args));
    wrap(GPUDevice.prototype, "createShaderModule", (_, [descriptor]) => shaders.push(descriptor.code));
    const createSampler = GPUDevice.prototype.createSampler;
    if (createSampler) GPUDevice.prototype.createSampler = function(descriptor = {}) {
      const sampler = createSampler.call(this, descriptor);
      samplerStates.push({...descriptor});
      samplerDescriptors.set(sampler, {backend: "webgpu", ...descriptor});
      return sampler;
    };
    for (const method of ["createRenderPipeline", "createRenderPipelineAsync"]) wrap(GPUDevice.prototype, method, (_, [descriptor]) => {
      pipelineStates.push({label: descriptor.label, primitive: descriptor.primitive, depthStencil: descriptor.depthStencil,
        targets: descriptor.fragment?.targets, multisample: descriptor.multisample});
    });
    wrap(GPUQueue.prototype, "writeTexture", (_, [destination, data, layout, extent]) => {
      textureUpload(destination, bytes(data, layout.offset || 0), layout, extent, destination.texture.format);
    });
    wrap(GPUCommandEncoder.prototype, "copyBufferToTexture", (_, [source, destination, extent]) => {
      const width = extent.width ?? extent[0], height = extent.height ?? extent[1] ?? 1;
      refreshMapped(source.buffer, source.offset || 0, (source.bytesPerRow || width * 4) * height);
      const raw = shadows.get(source.buffer)?.data;
      textureUpload(destination, raw?.subarray(source.offset || 0), source, extent, destination.texture.format);
    });
  }
  const bindings = new WeakMap();
  const textureBindings = new WeakMap(), textureUnits = new WeakMap(), unpackStates = new WeakMap();
  const framebuffers = new WeakMap(), attachments = new WeakMap(), readAttachments = new WeakMap();
  const binding = (gl, target) => bindings.get(gl)?.get(target);
  if (globalThis.WebGL2RenderingContext) {
    for (const method of ["samplerParameteri", "samplerParameterf"])
      wrap(WebGL2RenderingContext.prototype, method, (_, [sampler, parameter, value]) => {
        if (!samplerDescriptors.has(sampler)) samplerDescriptors.set(sampler, {});
        samplerDescriptors.get(sampler)[parameter] = value;
      });
    wrap(WebGL2RenderingContext.prototype, "deleteTexture", (_, [texture]) => { if (texture) forgetTexture(texture); });
    wrap(WebGL2RenderingContext.prototype, "bindFramebuffer", (gl, [target, framebuffer]) => {
      if (!framebuffers.has(gl)) framebuffers.set(gl, new Map());
      const state = framebuffers.get(gl);
      if (target === gl.FRAMEBUFFER) { state.set(gl.READ_FRAMEBUFFER, framebuffer); state.set(gl.DRAW_FRAMEBUFFER, framebuffer); }
      else state.set(target, framebuffer);
    });
    wrap(WebGL2RenderingContext.prototype, "framebufferTexture2D", (gl, [target, attachment, , texture, mip]) => {
      const framebuffer = framebuffers.get(gl)?.get(target === gl.FRAMEBUFFER ? gl.DRAW_FRAMEBUFFER : target);
      if (!framebuffer) return;
      if (!attachments.has(framebuffer)) attachments.set(framebuffer, new Map());
      attachments.get(framebuffer).set(attachment, {texture, mipLevel: mip});
    });
    wrap(WebGL2RenderingContext.prototype, "readBuffer", (gl, [attachment]) => readAttachments.set(gl, attachment));
    const readTexture = gl => attachments.get(framebuffers.get(gl)?.get(gl.READ_FRAMEBUFFER))
      ?.get(readAttachments.get(gl) ?? gl.COLOR_ATTACHMENT0);
    wrap(WebGL2RenderingContext.prototype, "copyTexSubImage2D", (gl, [target, mip, x, y, sourceX, sourceY, width, height]) => {
      const source = readTexture(gl);
      if (source) copyTexture({...source, origin: {x: sourceX, y: sourceY}},
        {texture: textureBindings.get(gl)?.get([textureUnits.get(gl) || gl.TEXTURE0, target].join(":")),
          mipLevel: mip, origin: {x, y}}, {width, height});
    });
    wrap(WebGL2RenderingContext.prototype, "blitFramebuffer", (gl, [sourceX, sourceY, sourceEndX, sourceEndY, x, y, endX, endY, mask]) => {
      if (!(mask & gl.COLOR_BUFFER_BIT)) return;
      const source = readTexture(gl), destination = attachments.get(framebuffers.get(gl)?.get(gl.DRAW_FRAMEBUFFER))?.get(gl.COLOR_ATTACHMENT0);
      if (!source || !destination) return;
      const width = sourceEndX - sourceX, height = sourceEndY - sourceY;
      if (width !== endX - x || height !== endY - y || width < 1 || height < 1) {
        failures.push("unsupported scaled or flipped texture blit"); return;
      }
      copyTexture({...source, origin: {x: sourceX, y: sourceY}}, {...destination, origin: {x, y}}, {width, height});
    });
    wrap(WebGL2RenderingContext.prototype, "pixelStorei", (gl, [parameter, value]) => {
      if (!unpackStates.has(gl)) unpackStates.set(gl, new Map());
      unpackStates.get(gl).set(parameter, value);
    });
    wrap(WebGL2RenderingContext.prototype, "activeTexture", (gl, [unit]) => textureUnits.set(gl, unit));
    wrap(WebGL2RenderingContext.prototype, "bindTexture", (gl, [target, texture]) => {
      if (!textureBindings.has(gl)) textureBindings.set(gl, new Map());
      textureBindings.get(gl).set([textureUnits.get(gl) || gl.TEXTURE0, target].join(":"), texture);
      if (texture) textureContexts.set(texture, gl);
    });
    wrap(WebGL2RenderingContext.prototype, "texStorage2D", (gl, [target, mipLevelCount, internalFormat, width, height]) => {
      const texture = textureBindings.get(gl)?.get([textureUnits.get(gl) || gl.TEXTURE0, target].join(":"));
      const format = ({32856: "rgba8unorm", 35907: "rgba8unorm-srgb", 33190: "depth24unorm", 36012: "depth32float"})[internalFormat] || "unknown";
      if (texture) textureDescriptors.set(texture, {width, height, mipLevelCount, format});
    });
    wrap(WebGL2RenderingContext.prototype, "samplerParameteri", (_, [, parameter, value]) => samplerStates.push({parameter, value}));
    wrap(WebGL2RenderingContext.prototype, "samplerParameterf", (_, [, parameter, value]) => samplerStates.push({parameter, value}));
    wrap(WebGL2RenderingContext.prototype, "bindBuffer", (gl, [target, buffer]) => {
      if (!bindings.has(gl)) bindings.set(gl, new Map());
      bindings.get(gl).set(target, buffer);
    });
    wrap(WebGL2RenderingContext.prototype, "bufferData", (gl, [target, data, , offset = 0, length]) => {
      if (typeof data === "number" || !data) return;
      const stride = data.BYTES_PER_ELEMENT || 1;
      write(binding(gl, target), 0, bytes(data, offset * stride, length ? length * stride : undefined));
    });
    wrap(WebGL2RenderingContext.prototype, "bufferSubData", (gl, [target, offset, data, dataOffset = 0, length]) => {
      const stride = data.BYTES_PER_ELEMENT || 1;
      write(binding(gl, target), offset, bytes(data, dataOffset * stride, length ? length * stride : undefined));
    });
    wrap(WebGL2RenderingContext.prototype, "copyBufferSubData", (gl, [read, target, sourceOffset, targetOffset, length]) => copy(binding(gl, read), sourceOffset, binding(gl, target), targetOffset, length));
    const textureImage = (gl, args, allocate) => {
      const [target, mip, x, y, width, height, format, type, data, offset = 0] = allocate
        ? [args[0], args[1], 0, 0, args[3], args[4], args[6], args[7], args[8], args[9]] : args;
      if (format !== gl.RGBA || type !== gl.UNSIGNED_BYTE) return;
      const unpack = unpackStates.get(gl), rowLength = unpack?.get(gl.UNPACK_ROW_LENGTH) || width;
      const alignment = unpack?.get(gl.UNPACK_ALIGNMENT) || 4;
      const stride = Math.ceil(rowLength * 4 / alignment) * alignment;
      const skip = (unpack?.get(gl.UNPACK_SKIP_ROWS) || 0) * stride + (unpack?.get(gl.UNPACK_SKIP_PIXELS) || 0) * 4;
      const raw = typeof data === "number" ? shadows.get(binding(gl, gl.PIXEL_UNPACK_BUFFER))?.data.subarray(data + skip) : ArrayBuffer.isView(data) ? bytes(data, offset + skip) : null;
      textureUpload({texture: textureBindings.get(gl)?.get([textureUnits.get(gl) || gl.TEXTURE0, target].join(":")), mipLevel: mip, origin: {x, y}}, raw, {bytesPerRow: stride}, {width, height}, "rgba8unorm");
    };
    wrap(WebGL2RenderingContext.prototype, "texSubImage2D", (gl, args) => textureImage(gl, args, false));
    wrap(WebGL2RenderingContext.prototype, "texImage2D", (gl, args) => {
      // The client allocates every mip with texImage2D (it never calls texStorage2D); level 0
      // of an RGBA8 allocation is what makes a GL texture eligible for verified readback.
      const [target, mip, internalFormat, width, height, , format, type] = args;
      const texture = textureBindings.get(gl)?.get([textureUnits.get(gl) || gl.TEXTURE0, target].join(":"));
      if (texture && mip === 0 && (internalFormat === gl.RGBA8 || internalFormat === gl.RGBA)
          && format === gl.RGBA && type === gl.UNSIGNED_BYTE) {
        textureDescriptors.set(texture, {width, height, format: "rgba8unorm"});
      }
      textureImage(gl, args, true);
    });
  }
  globalThis.__gaiusDrawCapture = {
    boundary() {
      presentSequence++;
      if (active) {
        const completed = active;
        completed.endPresent = presentSequence;
        active = null;
        completed.complete();
      } else if (pending) {
        active = pending;
        active.beginPresent = presentSequence;
        pending = null;
      }
    },
    uniform(pass, index, value) {
      if (!uniforms.has(pass)) uniforms.set(pass, new Map());
      if (value === null) uniforms.get(pass).delete(index);
      else uniforms.get(pass).set(index, value);
    },
    logical(pass) { if (active) active.logical.add(pass); },
    physical(pass) { return active && !active.logical.has(pass); },
    indexWidth(pass, width) { indexWidths.set(pass, width); },
    isActive() { return active !== null; },
    indexSlice(buffer, offset, first, count, width) {
      const data = shadows.get(buffer)?.data;
      const start = offset + first * width, length = count * width;
      if (![2, 4].includes(width) || !data || start + length > data.length) return {unknown: true};
      const view = new DataView(data.buffer, data.byteOffset + start, length), values = [];
      for (let index = 0; index < count; index++) values.push(width === 2 ? view.getUint16(index * width, true) : view.getUint32(index * width, true));
      let firstVertex = count ? Infinity : 0, lastVertex = count ? -Infinity : -1;
      for (const value of values) { firstVertex = Math.min(firstVertex, value); lastVertex = Math.max(lastVertex, value); }
      const normalized = new Uint32Array(values.map(value => value - firstVertex));
      return {length: normalized.byteLength, sha256: hash(normalized), firstVertex, lastVertex};
    },
    sampler(sampler) {
      const descriptor = samplerDescriptors.get(sampler);
      if (!descriptor) return {unknown: true};
      if (descriptor.backend === "webgpu") return {
        addressModeU: descriptor.addressModeU || "clamp-to-edge", addressModeV: descriptor.addressModeV || "clamp-to-edge",
        minFilter: descriptor.minFilter || "nearest", magFilter: descriptor.magFilter || "nearest",
        mipmapFilter: descriptor.mipmapFilter || "nearest", maxAnisotropy: descriptor.maxAnisotropy || 1,
        lodMinClamp: descriptor.lodMinClamp ?? 0, lodMaxClamp: descriptor.lodMaxClamp ?? 32};
      const addresses = {10497: "repeat", 33071: "clamp-to-edge", 33648: "mirror-repeat"};
      const min = descriptor[10241] ?? 9986;
      return {addressModeU: addresses[descriptor[10242] ?? 10497], addressModeV: addresses[descriptor[10243] ?? 10497],
        minFilter: [9728, 9984, 9986].includes(min) ? "nearest" : "linear",
        magFilter: (descriptor[10240] ?? 9729) === 9728 ? "nearest" : "linear",
        mipmapFilter: [9986, 9987].includes(min) ? "linear" : "nearest",
        maxAnisotropy: descriptor[34046] ?? 1, lodMinClamp: descriptor[33082] ?? -1000,
        lodMaxClamp: descriptor[33083] ?? 1000};
    },
    readTexture(texture, dimensions) {
      if (!globalThis.__gaiusCaptureReadTextures) return undefined;
      if (!active.readbacks.has(texture)) {
        let resolve;
        const result = new Promise(complete => { resolve = complete; });
        active.readbacks.set(texture, {texture, dimensions, resolve, result});
      }
      return active.readbacks.get(texture).result;
    },
    texture(texture) {
      const writes = textureWrites.get(texture);
      if (!writes) return [{unknown: true, objectType: texture?.constructor?.name, format: texture?.format, width: texture?.width, height: texture?.height}];
      const cached = active.textureCache.get(texture);
      if (cached?.revision === writes.revision) return cached.result;
      const entries = [...writes.values()].sort((left, right) => left.mip - right.mip || left.layer - right.layer);
      const result = Promise.all(entries.map(async entry => {
        entry.sha256 ||= hash(entry.data.slice());
        const covered = entry.covered.reduce((sum, value) => sum + value, 0);
        return {mip: entry.mip, layer: entry.layer, width: entry.width, height: entry.height, format: entry.format,
          covered, unknown: covered !== entry.width * entry.height, sha256: await entry.sha256};
      }));
      active.textureCache.set(texture, {revision: writes.revision, result});
      return result;
    },
    snapshot(buffer, offset, length, stride) {
      if (!active) return null;
      const shadow = shadows.get(buffer);
      if (!shadow || offset + length > shadow.data.length) return {length, sha256: null, unknown: true};
      let entries = active.cache.get(buffer);
      if (!entries) { entries = new Map(); active.cache.set(buffer, entries); }
      const key = [shadow.revision, offset, length].join(":");
      if (!entries.has(key)) entries.set(key, hash(shadow.data.slice(offset, offset + length)));
      const result = {length, sha256: entries.get(key), ...(length <= 256 ? {hex: Array.from(shadow.data.subarray(offset, offset + length), value => value.toString(16).padStart(2, "0")).join("")} : {})};
      // Diagnostics only: per 4-byte vertex column hashes (which attribute differs) and, within
      // a bounded budget, the raw vertex window itself.
      if (globalThis.__gaiusCaptureVertexColumns === true && Number.isInteger(stride) && stride > 0
          && stride <= 64 && stride % 4 === 0 && length % stride === 0) {
        const data = shadow.data.subarray(offset, offset + length), words = stride / 4, count = length / stride;
        result.stride = stride;
        result.columns = Array.from({length: words}, (_, word) => {
          const column = new Uint8Array(count * 4);
          for (let vertex = 0; vertex < count; vertex++) column.set(data.subarray(vertex * stride + word * 4, vertex * stride + word * 4 + 4), vertex * 4);
          return hash(column);
        });
        const budget = globalThis.__gaiusCaptureVertexDumpBytes || 0;
        if (budget > 0 && (active.dumped || 0) + length <= budget) {
          active.dumped = (active.dumped || 0) + length;
          let binary = "";
          for (let index = 0; index < data.length; index += 0x8000) binary += String.fromCharCode.apply(null, data.subarray(index, index + 0x8000));
          result.base64 = btoa(binary);
        }
      }
      return result;
    },
    record(pipeline, pass, args, vertices, indices, resolve, formats, slots, overrides = []) {
      if (!active) return;
      if (active.draws.length >= 4096) { failures.push("draw capture draw bound exceeded"); return; }
      const values = [...new Map([...(uniforms.get(pass) || []), ...overrides])].filter(([name]) => !slots || slots(name) >= 0).sort((left, right) => String(left[0]).localeCompare(String(right[0])));
      try {
        const index = indices && args.length === 5 ? resolve(indices, {kind: "indices", first: args[2], count: args[0], width: indexWidths.get(pass)}) : null;
        const firstVertex = index ? index.firstVertex + args[3] : args[2];
        const vertexCount = index ? index.lastVertex - index.firstVertex + 1 : args[0];
        const ranges = formats?.map(format => !format ? undefined : format.stepRate
          ? {offset: Math.floor(args.at(-1) / format.stepRate) * format.stride, length: Math.ceil(args[1] / format.stepRate) * format.stride}
          : {offset: firstVertex * format.stride, length: vertexCount * format.stride, stride: format.stride});
        const canonicalArgs = args.length === 5 ? [args[0], args[1], 0, 0, args[4]] : [args[0], args[1], 0, args[3]];
        active.draws.push({pipeline, args: formats ? canonicalArgs : args, rawArgs: args, formats,
          vertices: vertices.map((value, slot) => resolve(value, ranges?.[slot])),
          indices: index ? {length: index.length, sha256: index.sha256, unknown: index.unknown} : null,
          uniforms: values.map(([name, value]) => ({name, ...resolve(value)}))});
      } catch (error) {
        failures.push("draw capture resolver: " + error.message);
      }
    },
    async frame() {
      if (active || pending) throw new Error("draw capture already active");
      const frame = pending = {draws: [], cache: new WeakMap(), textureCache: new WeakMap(), logical: new WeakSet(), readbacks: new Map()};
      await new Promise((resolve, reject) => {
        const timer = setTimeout(() => { active = null; pending = null; reject(new Error("draw capture frame boundary timed out")); }, 10000);
        frame.complete = () => { clearTimeout(timer); resolve(); };
      });
      for (const request of frame.readbacks.values()) {
        try { request.resolve(await readTextureMips(request.texture, request.dimensions)); }
        catch (error) { failures.push("texture readback: " + error.message); request.resolve([{unknown: true, reason: error.message}]); }
      }
      const settle = async value => {
        if (value instanceof Promise) return await value;
        if (Array.isArray(value)) return await Promise.all(value.map(settle));
        if (value && typeof value === "object") return Object.fromEntries(await Promise.all(Object.entries(value).map(async ([key, entry]) => [key, await settle(entry)])));
        return value;
      };
      const draws = await settle(frame.draws);
      for (const draw of draws) draw.sha256 = await hash(new TextEncoder().encode(JSON.stringify(draw)));
      return {schema: 2, normalization: "referenced-index-window", boundary: "surface-present", backend: globalThis.__gaiusGpuBackend,
        beginPresent: frame.beginPresent, endPresent: frame.endPresent,
        readTextures: globalThis.__gaiusCaptureReadTextures === true, textureReadbacks: frame.readbacks.size,
        draws, failures: failures.slice(-20), textureUploadCount: uploadCount, shaders, samplerStates, pipelineStates};
    }
  };
}

export const DRAW_CAPTURE_BOOT_SOURCE = "(" + installDrawCapture.toString() + ")();";

export function stageDrawCapture(source) {
  const find = suffix => {
    const escaped = suffix.replaceAll("$", "\\$");
    const match = source.match(new RegExp("(?:let |[ ,\\n]|\\$rt_java\\.)([\\w$]+_" + escaped + ") = [^\\n]*?=> \\{"));
    if (!match) throw new Error("draw capture missing " + suffix);
    return match[0].includes("$rt_java.") ? "$rt_java." + match[1] : match[1];
  };
  const field = (suffix, pattern) => {
    const name = find(suffix), start = source.indexOf(name + " =");
    const match = source.slice(start, start + 3500).match(pattern);
    if (!match) throw new Error("draw capture cannot resolve " + suffix);
    return match[1];
  };
  const buffer = find("GpuBufferSlice_buffer"), offset = find("GpuBufferSlice_offset"), length = find("GpuBufferSlice_length");
  const glHandle = find("GlBuffer_handle"), core = find("RpBuffer_core");
  const coreHandle = field("CorePass_setVertexBuffer", /\.\$vertexHandles\.data\[[\w$]+\] = [\w$]+\.(\$\w+);/);
  const vertices = field("FrontendRenderPass_setVertexBuffer", /\$this\.(\$\w+)\.data\[\$slot\] =/);
  const indices = field("FrontendRenderPass_setIndexBuffer", /\$this\.(\$\w+) = \$indexBuffer;/);
  const pipeline = field("FrontendRenderPass_setPipeline", /\$this\.(\$\w+) = \$frontendPipeline;/);
  const pipelineName = field("FrontendRenderPipeline_name", /return \$this\.(\$\w+);/);
  const vertexFormats = find("FrontendRenderPipeline_vertexFormats"), vertexSize = find("VertexFormat_getVertexSize"), stepRate = find("VertexFormat_getStepRate");
  const uniformIndices = find("FrontendRenderPipeline_uniformIndices");
  const slots = "name=>" + uniformIndices + "($this." + pipeline + ").$getOrDefault1($rt_str(name),-1)";
  const uniformSetter = source.match(/let (\w+_FrontendRenderPass_setUniform) =/)[1];
  const uniformStart = source.indexOf("let " + uniformSetter + " =");
  const uniformMap = source.slice(uniformStart, uniformStart + 1400).match(/var\$3 = \$this\.(\$\w+);/)[1];
  const uniformValues = "(()=>{const values=[],iterator=$this." + uniformMap + ".$entrySet().$iterator();while(iterator.$hasNext()){const entry=iterator.$next();values.push([$rt_ustr(entry.$getKey()),entry.$getValue()]);}return values;})()";
  const formatList = "(()=>{const formats=[],iterator=" + vertexFormats + "($this." + pipeline + ").$iterator();while(iterator.$hasNext()){const format=iterator.$next();formats.push(format?{stride:" + vertexSize + "(format),stepRate:" + stepRate + "(format)}:null);}return formats;})()";
  const indexWidth = field("IndexType__init_", /\$this\.(\$\w+) = \$bytes;/);
  const sliceClass = buffer.slice(0, buffer.lastIndexOf("_buffer"));
  const pairView = find("TextureViewAndSampler_view"), pairSampler = find("TextureViewAndSampler_sampler");
  const samplerCore = find("RpSampler_core"), glSampler = find("GlSampler_getId");
  const samplerHandle = field("CoreSampler__init_", /\.(\$\w+) = globalThis\.GaiusWebGpu\.createSampler\(/);
  const textureHandle = find("CoreTexture_handle"), textureCore = find("RpTexture_core"), glTexture = find("GlTexture_glId");
  const resolveSlice = "(value,range)=>{if(!value)return null;if(value instanceof cmrbo_GlBuffer||value instanceof dgmsr_RpBuffer)value=value.$slice();if(value instanceof " + sliceClass + "){const resource=" + buffer + "(value);const native=resource instanceof cmrbo_GlBuffer?globalThis.__gaiusGL.buffers.get(" + glHandle + "(resource)):globalThis.__gaiusDrawCapture.gpuHandle(" + core + "(resource)." + coreHandle + ");const start=Long_lo(" + offset + "(value));if(range?.kind==='indices')return globalThis.__gaiusDrawCapture.indexSlice(native,start,range.first,range.count,range.width);return globalThis.__gaiusDrawCapture.snapshot(native,start+(range?.offset||0),range?.length??Long_lo(" + length + "(value)),range?.stride);}if(value instanceof cmru_TextureViewAndSampler){const view=" + pairView + "(value),resource=view.$texture3();const native=resource instanceof cmrbo_GlTexture?globalThis.__gaiusGL.textures.get(" + glTexture + "(resource)):globalThis.__gaiusDrawCapture.gpuHandle(" + textureHandle + "(" + textureCore + "(resource)));const sampler=" + pairSampler + "(value),nativeSampler=sampler instanceof cmrbo_GlSampler?globalThis.__gaiusGL.samplers.get(" + glSampler + "(sampler)):globalThis.__gaiusDrawCapture.gpuHandle(" + samplerCore + "(sampler)." + samplerHandle + ");return {kind:'texture',sampler:globalThis.__gaiusDrawCapture.sampler(nativeSampler),label:$rt_ustr(resource.$getLabel()),width:view.$getWidth1(0),height:view.$getHeight2(0),uploads:globalThis.__gaiusDrawCapture.readTexture(native,{width:view.$getWidth1(0),height:view.$getHeight2(0),mipLevels:resource.$getMipLevels()})||globalThis.__gaiusDrawCapture.texture(native)};}return {unknown:true,type:value.constructor?.$meta?.name||value.constructor?.name,fields:Object.keys(value)};}";
  const edits = [];
  for (const suffix of ["FrontendRenderPass_drawIndexed", "FrontendRenderPass_draw", "FrontendRenderPass_drawMultipleIndexed"]) {
    const name = find(suffix), start = source.indexOf(name + " ="), end = source.indexOf("\n};", start);
    const dispatch = source.slice(start, end).match(/\n        [\w$]+\.\$draw(?:Indexed|0)\(([^)]*)\)/);
    if (!dispatch) throw new Error("draw capture missing dispatch " + suffix);
    const args = "[" + dispatch[1] + "]";
    edits.push({at: start + dispatch.index, text: "\n        if(globalThis.__gaiusDrawCapture?.physical($this))globalThis.__gaiusDrawCapture.record($rt_ustr($this." + pipeline + "." + pipelineName + "),$this," + args + ",Array.from($this." + vertices + ".data),$this." + indices + "," + resolveSlice + "," + formatList + "," + slots + "," + uniformValues + ");"});
  }
  {
    const name = find("FrontendRenderPass_setIndexBuffer"), start = source.indexOf(name + " =");
    const header = source.slice(start, start + 200).match(/= \([^)]*\) => \{/);
    edits.push({at: start + header.index + header[0].length, text: "\n    if(globalThis.__gaiusDrawCapture)globalThis.__gaiusDrawCapture.indexWidth($this,$indexType." + indexWidth + ");"});
  }
  {
    const name = find("BrowserTerrainBatchGlue_drawMultipleIndexed"), start = source.indexOf(name + " =");
    const header = source.slice(start, start + 230).match(/= \([^)]*\) => \{/);
    const sectionIndex = field("BrowserTerrainBatchGlue$ChunkUniform__init_0", /\$this\.(\$\w+) = \$index;/);
    const getters = Object.fromEntries(["vertexBuffer", "slot", "indexBuffer", "indexType", "indexCount", "firstIndex", "baseVertex", "uniformUploaderConsumer"].map(key => [key, find("RenderPass$Draw_" + key)]));
    const prefix = "\n    if(globalThis.__gaiusDrawCapture?.isActive()&&!$rt_resuming()){const $this=$pass;globalThis.__gaiusDrawCapture.logical($pass);const iterator=$draws.$iterator();while(iterator.$hasNext()){const draw=iterator.$next(),vertices=Array.from($pass." + vertices + ".data),indexType=" + getters.indexType + "(draw)||$indexType;vertices[" + getters.slot + "(draw)]=" + getters.vertexBuffer + "(draw).$slice();globalThis.__gaiusDrawCapture.indexWidth($pass,indexType." + indexWidth + ");globalThis.__gaiusDrawCapture.record($rt_ustr($pass." + pipeline + "." + pipelineName + "),$pass,[" + getters.indexCount + "(draw),1," + getters.firstIndex + "(draw)," + getters.baseVertex + "(draw),0],vertices," + getters.indexBuffer + "(draw)||$indexBuffer," + resolveSlice + "," + formatList + "," + slots + ",[..." + uniformValues + ",['ChunkSection',$sectionSlices.data[" + getters.uniformUploaderConsumer + "(draw)." + sectionIndex + "]]]);}}";
    edits.push({at: start + header.index + header[0].length, text: prefix});
  }
  for (const prefix of ["GlSurface", "RpSurface"]) {
    const name = find(prefix + "_present"), start = source.indexOf(name + " =");
    const header = source.slice(start, start + 200).match(/= [^\n]*?=> \{/);
    edits.push({at: start + header.index + header[0].length, text: "\n    if(globalThis.__gaiusDrawCapture&&!$rt_resuming())globalThis.__gaiusDrawCapture.boundary();"});
  }
  const parts = [];
  let previous = 0;
  for (const edit of edits.sort((left, right) => left.at - right.at)) {
    parts.push(source.slice(previous, edit.at), edit.text);
    previous = edit.at;
  }
  parts.push(source.slice(previous));
  source = parts.join("");
  return {source, receipt: {mode: "diagnostic-response-only", hooks: edits.length,
    bootSha256: createHash("sha256").update(DRAW_CAPTURE_BOOT_SOURCE).digest("hex"),
    stagedSha256: createHash("sha256").update(source).digest("hex")}};
}
