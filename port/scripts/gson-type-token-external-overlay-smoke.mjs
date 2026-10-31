import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {dirname, resolve} from "node:path";
import {fileURLToPath} from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const build = readFileSync(resolve(root, "port/scripts/build-teavm.sh"), "utf8");
const patcher = readFileSync(resolve(
  root,
  "port/tools/src/main/java/dev/gaius/tools/GsonTypeTokenClientPatcher.java",
), "utf8");

const invocationStart = build.indexOf("dev.gaius.tools.GsonTypeTokenClientPatcher");
const invocationEnd = build.indexOf("jar --update", invocationStart);
assert.ok(invocationStart >= 0 && invocationEnd > invocationStart,
  "Gson TypeToken patcher invocation is missing");
const invocation = build.slice(invocationStart, invocationEnd);
const skipBranchStart = build.indexOf("Skipping overlay rebuild because GAIUS_SKIP_OVERLAY_BUILD=true");
const skipBranch = build.slice(skipBranchStart, invocationStart);

assert.ok(build.indexOf('work="$root/port/work/$version"') < invocationStart,
  "profile work root must be resolved before the patcher invocation");
assert.ok(skipBranchStart >= 0,
  "skip-overlay branch is missing");
assert.match(skipBranch, /find "\$tool_classes" -type f -delete/,
  "skip-overlay builds must remove stale compiled patch tools");
assert.match(skipBranch, /javac --release 21 -proc:none[\s\S]*"\$root\/port\/tools\/src\/main\/java\/dev\/gaius\/tools\/"\*\.java/,
  "skip-overlay builds must compile the current patch-tool sources");
assert.ok(skipBranch.indexOf("javac --release 21 -proc:none")
    < invocationStart - skipBranchStart,
  "skip-overlay tool refresh must happen before the TypeToken patcher runs");
assert.match(invocation, /"\$work\/libraries"/,
  "patcher must receive the profile library root explicitly");
assert.match(patcher, /args\.length != 3/,
  "patcher must reject ambiguous two-argument invocations");
assert.match(patcher, /Path versionLibraries = Path\.of\(args\[2\]\);/,
  "patcher must use the explicit profile library root");
assert.match(patcher, /findJar\(versionLibraries,/,
  "patcher must resolve dependencies from the explicit library root");
assert.doesNotMatch(patcher, /workLibrariesFor|clientJar\.getParent\(\)/,
  "patcher must not infer port/work from an external overlay path");
assert.match(patcher,
  /replacements\s*==\s*0\s*&&\s*hasExplicitTypeTokenConstruction\(initializer,\s*rawType\)/,
  "patcher must accept an overlay that was already rewritten by an earlier pass");
assert.match(patcher,
  /call\.owner\.equals\("com\/google\/gson\/internal\/GsonTypes"\)[\s\S]*call\.name\.equals\("newParameterizedTypeWithOwner"\)/,
  "idempotency detection must require GsonTypes.newParameterizedTypeWithOwner");
assert.match(patcher,
  /call\.owner\.equals\("com\/google\/gson\/reflect\/TypeToken"\)[\s\S]*call\.name\.equals\("get"\)/,
  "idempotency detection must require TypeToken.get");

console.log(JSON.stringify({
  ok: true,
  explicitVersionLibraries: true,
  externalOverlayIndependent: true,
  skipOverlayRefreshesTools: true,
  repeatedPatchIdempotent: true,
}));
