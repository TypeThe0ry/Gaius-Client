export async function run(context) {
  const {command, evaluate, sleep, screenshot, log} = context;
  const noAi = process.env.DRAGON_NOAI === "1";
  const nbt = noAi ? " {NoAI:1b}" : "";
  const summon = `/summon minecraft:ender_dragon 7.8 67 21.0${nbt}`;
  const result = {
    entity: "minecraft:ender_dragon",
    dimension: "minecraft:overworld",
    noAi,
    command: summon,
    startedAt: new Date().toISOString(),
    findings: [],
  };

  await command("/gamemode creative", /Set own game mode to (?:Spectator|Creative|Survival|Adventure) Mode/i);
  const response = await command(summon, /Summoned new|Successfully summoned/i);
  result.commandResponse = response;
  await log("dragon-summon", JSON.stringify({noAi, response}));

  const end = Date.now() + 15000;
  let state = null;
  while (Date.now() < end) {
    state = await evaluate("(()=>{const s=globalThis.__gaiusMinecraftState||{};return{screen:String(s.screen||''),level:!!s.level,player:!!s.player,playerPos:s.player||null}})()");
    if (/DisconnectedScreen|ConnectScreen|ErrorScreen/.test(state.screen)) break;
    await sleep(500);
  }
  result.stateAfterSummon = state;
  result.serverFailures = await evaluate("(()=>{const m=globalThis.__gaiusSingleplayerFailures;return m&&typeof m.values==='function'?Array.from(m.values()).slice(-4):[]})()");
  result.workerEvents = await evaluate("(()=>Array.isArray(globalThis.__gaiusMinecraftEvents)?globalThis.__gaiusMinecraftEvents.filter(x=>x&&x.event==='singleplayer:worker'&&/crash|error|runtime-error|bootstrap-crash|stopping|stopped/i.test(JSON.stringify(x.detail))).slice(-40):[])()");

  if (/DisconnectedScreen|ConnectScreen|ErrorScreen/.test(String(state?.screen || ""))) {
    result.findings.push({kind: "disconnect-after-summon", screen: state.screen});
  } else if (!response?.ok) {
    result.findings.push({kind: "summon-not-acknowledged", response});
  } else {
    const check = await command(
      "/data get entity @e[type=minecraft:ender_dragon,sort=nearest,limit=1] Pos",
      /\[.*\]| has the following data|\d+\.\d+/i,
    );
    result.entityData = check;
    if (!check?.ok) result.findings.push({kind: "spawned-entity-not-readable", response: check});
  }

  result.screenshot = await screenshot(`dragon-${noAi ? "noai" : "ai"}.png`).catch(error => null);
  await log("dragon-summon-result", JSON.stringify(result).slice(0, 3500));
  return result;
}
