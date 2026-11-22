import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";

const browserSourceDir = new URL(
  "../overrides/libraries/netty-transport/src/main/java/io/netty/channel/browser/",
  import.meta.url,
);

export function extractJsBody(source, marker) {
  const markerOffset = source.indexOf(marker);
  const annotations = source.slice(0, markerOffset).matchAll(
    /^[ \t]*@JSBody\([^\n]*?\bscript\s*=\s*"""/gm,
  );
  const annotation = Array.from(annotations).at(-1);
  const scriptOffset = annotation ? annotation.index + annotation[0].length : -1;
  const scriptEnd = source.lastIndexOf('""")', markerOffset);
  assert.ok(markerOffset > 0 && scriptOffset >= 0 && scriptEnd > scriptOffset,
    `JSBody could not be extracted for ${marker}`);
  return source.slice(scriptOffset, scriptEnd).replaceAll("\\\\", "\\");
}

export async function loadBridgeSmokeSource() {
  const [source, helpers] = await Promise.all([
    readFile(new URL("BrowserWebSocketChannel.java", browserSourceDir), "utf8"),
    readFile(new URL("BrowserRelayRaceHelpers.java", browserSourceDir), "utf8"),
  ]);
  // Match the Java init order and keep each JSBody in its own scope.
  const bridgeScript = [
    extractJsBody(source, "private static native void initBridge();"),
    extractJsBody(helpers, "static native void init();"),
    extractJsBody(source, "private static native void initBridgeTail();"),
  ].map((body) => `{\n${body}\n}`).join("\n");
  return {source, bridgeScript};
}
