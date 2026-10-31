import {analyzeTerrainPng, decodePng, encodeRgbaPng} from "../../tools/terrain-visual-metrics.mjs";

export function analyzeStartupTerrain(pixels) {
  const image = decodePng(pixels);
  const region = {x: Math.floor(image.width * 0.10), y: Math.floor(image.height * 0.25),
    width: Math.floor(image.width * 0.40), height: Math.floor(image.height * 0.48)};
  const rgba = Buffer.alloc(region.width * region.height * 4);
  for (let row = 0; row < region.height; row++) {
    const start = ((region.y + row) * image.width + region.x) * 4;
    image.rgba.copy(rgba, row * region.width * 4, start, start + region.width * 4);
  }
  return {...analyzeTerrainPng(encodeRgbaPng(region.width, region.height, rgba)),
    viewportWidth: image.width, viewportHeight: image.height, sceneRegion: region};
}
