import {createHash} from "node:crypto";

export function stagePresentCapture(source) {
  const matches = [...source.matchAll(/(?:\$rt_java\.)?[\w$]+_BrowserSdlDom_presentYield = \([^\n]*\) => \{/g)];
  if (matches.length !== 1) throw new Error("present capture requires one SDL yield entry");
  const match = matches[0], at = match.index + match[0].length;
  const guard = '\n    if($rt_nativeThread()===null)return;';
  if (source.slice(at, at + guard.length) === guard) throw new Error("present capture already staged");
  source = source.slice(0, at) + guard + source.slice(at);
  return {source, receipt: {mode: "diagnostic-response-only", policy: "skip-native-callback-yield",
    stagedSha256: createHash("sha256").update(source).digest("hex")}};
}
