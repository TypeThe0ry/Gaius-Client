import {createHash} from "node:crypto";

export function stageLinearMipmapSampler(source) {
  const constructor = source.match(/[\w$.]+_CoreSampler__init_ = [^\n]*=> \{/);
  if (!constructor) throw new Error("sampler staging requires the compiled core constructor");
  const start = constructor.index, end = source.indexOf("\n};", start);
  const body = source.slice(start, end);
  const call = body.match(/globalThis\.GaiusWebGpu\.createSampler\(([^;]+)\)/);
  if (!call) throw new Error("sampler staging cannot find the native descriptor call");
  const args = call[1].split(", ");
  if (args.length !== 7 || !/^\$rt_ustr\([\w$]+\)$/.test(args[4])) throw new Error("sampler staging received an unexpected descriptor");
  const mipVariable = args[4].slice(9, -1);
  const minVariable = args[2].slice(9, -1);
  const mipAssignment = body.match(new RegExp(mipVariable.replaceAll("$", "\\$") + " = ([\\w$.]+_CoreFilterMode_webName)\\(([^)]+)\\);"));
  const minAssignment = body.match(new RegExp(minVariable.replaceAll("$", "\\$") + " = ([\\w$.]+_CoreFilterMode_webName)\\(([^)]+)\\);"));
  if (!mipAssignment || !minAssignment || mipAssignment[2] !== minAssignment[2]) throw new Error("sampler staging requires the old min-filter-dependent mip policy");
  args[4] = '"linear"';
  const replacement = "globalThis.GaiusWebGpu.createSampler(" + args.join(", ") + ")";
  const at = start + call.index;
  source = source.slice(0, at) + replacement + source.slice(at + call[0].length);
  return {source, receipt: {mode: "diagnostic-response-only", policy: "linear-mipmap-only", nativeCallsChanged: 1,
    stagedSha256: createHash("sha256").update(source).digest("hex")}};
}
