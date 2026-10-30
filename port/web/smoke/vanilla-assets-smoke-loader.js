window.__gaiusVanillaAssetsReady = (async () => {
  const response = await fetch("../dist/vanilla-assets.pack.gz?v=platform-smoke-v1", {
    cache: "force-cache",
  });
  if (!response.ok) {
    throw new Error(`Could not load the Gaius vanilla asset pack: HTTP ${response.status}`);
  }

  let bytes = new Uint8Array(await response.arrayBuffer());
  if (!hasMagic(bytes)) {
    if (typeof DecompressionStream !== "function") {
      throw new Error("This browser cannot decompress the Gaius vanilla asset pack");
    }
    const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream("gzip"));
    bytes = new Uint8Array(await new Response(stream).arrayBuffer());
  }
  if (!hasMagic(bytes)) {
    throw new Error("The Gaius vanilla asset pack has an invalid header");
  }

  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const indexLength = view.getUint32(8, true);
  const dataOffset = 12 + indexLength;
  if (indexLength <= 0 || dataOffset > bytes.length) {
    throw new Error("The Gaius vanilla asset index is truncated");
  }

  const index = JSON.parse(new TextDecoder().decode(bytes.subarray(12, dataOffset)));
  if (!index || typeof index !== "object" || Array.isArray(index)) {
    throw new Error("The Gaius vanilla asset index is not an object");
  }
  const payloadLength = bytes.length - dataOffset;
  let resourceCount = 0;
  for (const name in index) {
    if (!Object.prototype.hasOwnProperty.call(index, name)) continue;
    const range = index[name];
    if (!Array.isArray(range) || range.length !== 2 ||
        !Number.isSafeInteger(range[0]) || !Number.isSafeInteger(range[1]) ||
        range[0] < 0 || range[1] < 0 || range[0] + range[1] > payloadLength) {
      throw new Error(`The Gaius vanilla asset range is invalid: ${name}`);
    }
    resourceCount++;
  }
  if (resourceCount < 1000) {
    throw new Error("The Gaius vanilla asset pack is incomplete");
  }

  return window.__gaiusVanillaAssets = {bytes, index, dataOffset, resourceCount};
})();

function hasMagic(bytes) {
  const magic = "GAIUSVP1";
  if (!(bytes instanceof Uint8Array) || bytes.length < 12) return false;
  for (let index = 0; index < magic.length; index++) {
    if (bytes[index] !== magic.charCodeAt(index)) return false;
  }
  return true;
}
