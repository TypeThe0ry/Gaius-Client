#!/usr/bin/env node
import { existsSync, readFileSync } from 'node:fs';

const files = process.argv.slice(2);
if (files.length === 0) {
  console.error('usage: node port/scripts/audit-render-state.mjs port/target/state*.json ...');
  process.exit(2);
}

function load(file) {
  const root = JSON.parse(readFileSync(file, 'utf8'));
  if (root.state) return root.state;
  const snapshots = Array.isArray(root.interactionSnapshots) ? root.interactionSnapshots : [];
  const snapshot = snapshots.findLast(item => item && (item.minecraftState || item.glStatsLite || item.glStats));
  if (snapshot) {
    return {
      minecraftState: snapshot.minecraftState || {
        screen: snapshot.screen ?? null,
        level: snapshot.level ?? null,
        overlay: snapshot.overlay ?? null,
        player: snapshot.player ?? null,
      },
      minecraftCounters: snapshot.minecraftCounters || null,
      glStats: snapshot.glStats || snapshot.glStatsLite || null,
      fps: snapshot.fps || null,
      canvases: snapshot.canvases || null,
      centerPixel: snapshot.centerPixel || null,
    };
  }
  return root;
}

function level0(info) {
  if (Number.isFinite(info?.width) && Number.isFinite(info?.height)) return info;
  return info?.levels?.['0'] || info?.levels?.[0] || null;
}

function textureSummary(state) {
  const info = state.glStats?.textureInfo || {};
  const counts = new Map();
  const values = Array.isArray(info) ? info : Object.values(info);
  for (const value of values) {
    const l0 = level0(value);
    if (!l0) continue;
    const key = `${l0.width}x${l0.height}`;
    counts.set(key, (counts.get(key) || 0) + 1);
  }
  return [...counts.entries()].sort((a, b) => b[1] - a[1]).slice(0, 12);
}

function decodeVertices(draw) {
  const attr0 = (draw.vertexAttribs || []).find(item => item.index === 0);
  const sample = attr0?.bufferSample;
  const bytesArray = sample?.bytes;
  if (!bytesArray?.length) return null;
  const bytes = Uint8Array.from(bytesArray);
  const stride = attr0.stride || attr0.pointer?.stride || 0;
  if (stride < 20) return null;
  const view = new DataView(bytes.buffer);
  const vertices = [];
  const count = Math.min(128, Math.floor(bytes.length / stride));
  for (let i = 0; i < count; i++) {
    const offset = i * stride;
    const x = view.getFloat32(offset, true);
    const y = view.getFloat32(offset + 4, true);
    const z = view.getFloat32(offset + 8, true);
    const u = view.getFloat32(offset + 12, true);
    const v = view.getFloat32(offset + 16, true);
    if ([x, y, z, u, v].every(Number.isFinite)) {
      vertices.push({ i, x, y, z, u, v });
    }
  }
  return vertices;
}

function span(values) {
  if (!values.length) return null;
  return { min: Math.min(...values), max: Math.max(...values) };
}

function drawTextureUnits(draw) {
  return (draw.textureUnits || []).slice(0, 4).map(unit => {
    const l0 = level0(unit.info);
    return {
      unit: unit.unit,
      texture: unit.texture2D,
      width: l0?.width ?? null,
      height: l0?.height ?? null,
    };
  });
}

function samplerUniforms(draw) {
  return (draw.activeUniforms || [])
    .filter(uniform => /^Sampler\d+$/.test(uniform.name) && Number.isInteger(uniform.value))
    .map(uniform => ({
      name: uniform.name,
      textureUnit: uniform.value,
    }));
}

function auditDraws(state) {
  const stats = state.glStats || {};
  const findings = [];
  for (const group of ['drawSamples', 'mainDrawSamples']) {
    for (const [index, draw] of (stats[group] || []).entries()) {
      const textureUnits = drawTextureUnits(draw);
      const usesLargeAtlas = textureUnits.some(unit =>
        (unit.width || 0) >= 512 && (unit.height || 0) >= 512);
      const vertices = decodeVertices(draw);
      if (vertices?.length) {
        const u = span(vertices.map(vertex => vertex.u));
        const v = span(vertices.map(vertex => vertex.v));
        const x = span(vertices.map(vertex => vertex.x));
        const y = span(vertices.map(vertex => vertex.y));
        const largeUvSpan = u && v && (u.max - u.min > 1.05 || v.max - v.min > 1.05);
        if (usesLargeAtlas && u && v && (u.max - u.min > 1.05 || v.max - v.min > 1.05)) {
          findings.push({
            severity: 'error',
            type: 'large_uv_span_on_large_atlas',
            group,
            index,
            args: draw.args,
            programId: draw.programId,
            textureUnits,
            attributeNames: (draw.activeAttributes || []).map(attr => attr.name),
            span: { x, y, u, v },
            firstVertices: vertices.slice(0, 12),
          });
        }
        if (largeUvSpan) {
          for (const sampler of samplerUniforms(draw)) {
            const sampled = textureUnits.find(unit => unit.unit === sampler.textureUnit);
            const alternateSmall = textureUnits.find(unit =>
              unit.unit !== sampler.textureUnit
              && (unit.width || 0) > 0
              && (unit.height || 0) > 0
              && (unit.width || 0) <= 64
              && (unit.height || 0) <= 64);
            if (sampled
                && (sampled.width || 0) >= 512
                && (sampled.height || 0) >= 512
                && alternateSmall) {
              findings.push({
                severity: 'error',
                type: 'repeating_uv_sampler_targets_large_texture_with_small_texture_on_other_unit',
                group,
                index,
                args: draw.args,
                programId: draw.programId,
                sampler,
                sampledTextureUnit: sampled,
                alternateSmallTextureUnit: alternateSmall,
                span: { x, y, u, v },
                firstVertices: vertices.slice(0, 12),
              });
            }
          }
        }
      }
      const enabled = (draw.vertexAttribs || []).filter(attr => attr.enabled);
      for (const attr of enabled) {
        if (attr.bufferSample?.error) {
          findings.push({
            severity: 'warning',
            type: 'attribute_sample_error',
            group,
            index,
            attribute: attr.index,
            error: attr.bufferSample.error,
          });
        }
      }
    }
  }
  return findings;
}

for (const file of files) {
  if (!existsSync(file)) {
    console.log(JSON.stringify({ file, error: 'missing' }, null, 2));
    continue;
  }
  const state = load(file);
  const findings = auditDraws(state);
  const summary = {
    file,
    screen: state.minecraftState?.screen ?? null,
    level: state.minecraftState?.level ?? null,
    overlay: state.minecraftState?.overlay ?? null,
    fps: state.fps ?? null,
    gui: state.minecraftCounters
      ? {
          submits: state.minecraftCounters.guiLastSubmits ?? null,
          drawCalls: state.minecraftCounters.guiLastDrawCalls ?? null,
          drawIndices: state.minecraftCounters.guiLastDrawIndices ?? null,
          baseVertexDraws: state.minecraftCounters.guiLastBaseVertexNonZero ?? null,
        }
      : null,
    centerPixel: state.centerPixel ?? null,
    defaultFramebuffer: state.defaultFramebufferStats
      ? {
          colorNonZero: state.defaultFramebufferStats.colorNonZero,
          bounds: state.defaultFramebufferStats.bounds,
        }
      : null,
    canvas: state.canvases ?? null,
    gl: state.glStats
      ? {
          drawArrays: state.glStats.drawArrays,
          drawElements: state.glStats.drawElements,
          drawCallsPerSecond: state.glStats.drawCallsPerSecond,
          blitFramebuffer: state.glStats.blitFramebuffer,
          lastError: state.glStats.lastError,
          textureUploads: state.glStats.textureUploads,
          textureUploadErrors: state.glStats.textureUploadErrors ?? null,
        }
      : null,
    textureSizeTop: textureSummary(state),
    findings,
  };
  console.log(JSON.stringify(summary, null, 2));
}
