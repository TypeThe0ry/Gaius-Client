import {createHash} from "node:crypto";

export function stageCaptureCreation(source) {
  const symbol = (suffix, assignment = " =") => {
    const at = source.indexOf("_" + suffix + assignment);
    if (at < 0) throw new Error("capture creation missing " + suffix);
    const before = source.slice(Math.max(0, at - 160), at);
    const name = before.match(/(?:\$rt_java\.)?[\w$]+$/)?.[0];
    if (!name) throw new Error("capture creation symbol " + suffix);
    return name + "_" + suffix;
  };
  const onCreate = symbol("CreateWorldScreen_onCreate");
  const start = source.indexOf(onCreate + " =");
  const end = source.indexOf("\n};", start);
  if (end < 0) throw new Error("capture creation has no complete method body");
  const body = source.slice(start, end);
  const state = body.match(/\$context = \$this\.(\$\w+)\.\$getSettings\(\);/)?.[1];
  if (!state) throw new Error("capture creation has no vanilla UI state");
  const rules = symbol("WorldCreationUiState_getGameRules");
  const get = symbol("GameRules_get"), getStart = source.indexOf(get + " =");
  const map = source.slice(getStart, getStart + 400).match(/GameRuleMap_get\(\$this\.(\$\w+),/)?.[1];
  if (!map) throw new Error("capture creation has no vanilla rule map");
  const integer = source.match(/(?:let |[ ,\n])([\w$]+_Integer_valueOf\w*) = \$i => \{/)?.[1];
  if (!integer) throw new Error("capture creation has no integer boxing overload");
  const set = symbol("GameRuleMap_set"), boolean = symbol("Boolean_valueOf");
  // Everything that changes blocks or the sky from unseeded randomness before the capture can
  // run its own commands: random ticks (grass, leaves, vines, crops, ice), mob and event
  // spawning, mob griefing (sheep eating grass), vine spreading, fire spread and the clocks.
  const entries = [["RANDOM_TICK_SPEED", integer + "(0)"], ["SPAWN_MOBS", boolean + "(0)"],
    ["SPAWN_MONSTERS", boolean + "(0)"], ["SPAWN_PATROLS", boolean + "(0)"], ["SPAWN_PHANTOMS", boolean + "(0)"],
    ["SPAWN_WANDERING_TRADERS", boolean + "(0)"], ["MOB_GRIEFING", boolean + "(0)"], ["SPREAD_VINES", boolean + "(0)"],
    ["FIRE_SPREAD_RADIUS_AROUND_PLAYER", integer + "(0)"],
    ["ADVANCE_TIME", boolean + "(0)"], ["ADVANCE_WEATHER", boolean + "(0)"]];
  const calls = entries.map(([name, value]) => set + "(captureRules." + map + "," + symbol("GameRules_" + name) + "," + value + ");").join("\n");
  const marker = "const captureRules=";
  if (body.includes(marker)) throw new Error("capture creation already staged");
  const firstState = body.indexOf("    case 0:");
  if (firstState < 0) throw new Error("capture creation has no initial coroutine state");
  const at = start + firstState + "    case 0:".length;
  const patch = "\n        if(globalThis.__gaiusCapture?.creationRules===true){const captureRules=" + rules + "($this." + state + ");" + calls + "globalThis.__gaiusCapture.creationRulesApplied=true;}";
  source = source.slice(0, at) + patch + source.slice(at);
  return {source, receipt: {mode: "diagnostic-response-only", policy: "pin-client-ui-rules-before-world-creation",
    rules: entries.map(([name]) => name), stagedSha256: createHash("sha256").update(source).digest("hex")}};
}
