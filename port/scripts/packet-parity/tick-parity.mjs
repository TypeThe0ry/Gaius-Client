import {spawn, spawnSync} from "node:child_process";
import {createHash} from "node:crypto";
import {createReadStream, createWriteStream, existsSync, readFileSync, writeFileSync, mkdirSync, readdirSync, appendFileSync} from "node:fs";
import {delimiter, dirname, join, resolve} from "node:path";
import {fileURLToPath} from "node:url";
import {compareTickExact, validateTickCapture, validateTickScript} from "./logical-compare.mjs";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../../..");
const options = Object.fromEntries(process.argv.slice(3).reduce((entries, value, index, values) => {
  if (value.startsWith("--")) entries.push([value.slice(2), values[index + 1]]);
  return entries;
}, []));
const mode = process.argv[2];
if (!["prepare", "prove", "compare"].includes(mode) || !options.out) {
  throw new Error("tick-parity.mjs prepare|prove --profile <profile> --out <fresh directory>; compare --left <events.jsonl> --right <events.jsonl> --out <fresh directory>");
}
const out = resolve(options.out);
if (out.toLowerCase().includes(`${join("port", "work").toLowerCase()}`)) throw new Error("output must not be in shared port/work");
mkdirSync(out, {recursive: true});
const json = path => JSON.parse(readFileSync(path, "utf8"));
const save = (name, value) => writeFileSync(join(out, name), JSON.stringify(value, null, 2) + "\n", {flag: "wx"});
const events = path => readFileSync(path, "utf8").trim().split(/\r?\n/).map(line => JSON.parse(line));
const sha = async path => {
  const hash = createHash("sha256");
  for await (const chunk of createReadStream(path)) hash.update(chunk);
  return hash.digest("hex");
};

async function headroom() {
  const minimumGiB = Number(process.env.GAIUS_JVM_MIN_HEADROOM_GIB || 18);
  if (!Number.isSafeInteger(minimumGiB) || minimumGiB < 8) throw new Error("JVM commit headroom must be at least 8 GiB");
  const minimum = minimumGiB * 1024 ** 3;
  const started = Date.now();
  const waitLimit = Number(options["headroom-wait-ms"] ?? 0);
  if (!Number.isSafeInteger(waitLimit) || waitLimit < 0) throw new Error("invalid headroom wait limit");
  while (true) {
    const result = spawnSync("powershell.exe", ["-NoProfile", "-NonInteractive", "-Command",
      "$memory=Get-CimInstance Win32_PerfFormattedData_PerfOS_Memory; [Console]::Write($memory.CommitLimit-$memory.CommittedBytes)"], {encoding: "utf8"});
    const bytes = Number(result.stdout?.trim());
    if (result.status !== 0 || !/^\d+$/.test(result.stdout?.trim() ?? "") || !Number.isSafeInteger(bytes)) {
      throw new Error("cannot verify commit headroom");
    }
    appendFileSync(join(out, "headroom.jsonl"), JSON.stringify({at: new Date().toISOString(), bytes, minimum}) + "\n");
    console.log(`COMMIT_HEADROOM ${(bytes / 1024 ** 3).toFixed(3)} GiB`);
    if (bytes >= minimum) return;
    if (waitLimit && Date.now() - started >= waitLimit) throw new Error(`JVM blocked: commit headroom below ${minimumGiB} GiB; next JVM not started`);
    await new Promise(done => setTimeout(done, 60000));
  }
}

async function jvm(executable, args, name, cwd = out) {
  await headroom();
  appendFileSync(join(out, "commands.jsonl"), JSON.stringify({executable, args, cwd}) + "\n");
  const log = createWriteStream(join(out, name + ".log"), {flags: "wx"});
  const child = spawn(executable, args, {cwd, stdio: ["ignore", "pipe", "pipe"], windowsHide: true});
  child.stdout.pipe(log, {end: false});
  child.stderr.pipe(log, {end: false});
  let expired = false;
  const timeout = setTimeout(() => { expired = true; child.kill(); }, 15 * 60 * 1000);
  const code = await new Promise((done, reject) => { child.on("error", reject); child.on("close", done); })
    .finally(() => { clearTimeout(timeout); log.end(); });
  if (code !== 0 || expired) throw new Error(`${name} failed (${code}); inspect ${name}.log`);
}

function varint(value) {
  const bytes = [];
  do { let byte = value & 127; value >>>= 7; if (value) byte |= 128; bytes.push(byte); } while (value);
  return Buffer.from(bytes);
}

function scriptFrom(profile, table) {
  if (table.protocol !== profile.protocolVersion) throw new Error("protocol table/profile mismatch");
  const ids = Object.fromEntries(Object.entries(table["play/serverbound"]).map(([id, name]) => [name.replace(/^minecraft:/, ""), Number(id)]));
  for (const name of ["accept_teleportation", "chunk_batch_received", "keep_alive", "pong", "player_input", "move_player_pos_rot", "client_tick_end"]) {
    if (!Number.isSafeInteger(ids[name])) throw new Error(`missing protocol packet ${name}`);
  }
  const actions = [{tick: 2, connection: 0, wire: Buffer.concat([varint(ids.player_input), Buffer.from([0])]).toString("hex")}];
  for (const move of profile.tickParity.moves) {
    const body = Buffer.alloc(33);
    body.writeDoubleBE(move.x, 0); body.writeDoubleBE(move.y, 8); body.writeDoubleBE(move.z, 16);
    body.writeFloatBE(0, 24); body.writeFloatBE(0, 28); body[32] = 0;
    actions.push({tick: move.tick, connection: 0, wire: Buffer.concat([varint(ids.move_player_pos_rot), body]).toString("hex")});
  }
  // A vanilla client ends every tick in which it sent input with client_tick_end; the server resets
  // its per-tick movement state there (and rejects a second position packet before it).
  const withTickEnds = actions.flatMap((action, index) => actions[index + 1]?.tick === action.tick ? [action]
    : [action, {tick: action.tick, connection: 0, wire: varint(ids.client_tick_end).toString("hex")}]);
  actions.splice(0, actions.length, ...withTickEnds);
  const {moves, ...settings} = profile.tickParity;
  if (settings.normalization?.rules?.includes("game-rule-command-order")) {
    if (!Array.isArray(table.commandArgumentTypes)) throw new Error("protocol table lacks command argument types");
    settings.normalization = {...settings.normalization, commandArgumentTypes: table.commandArgumentTypes};
  }
  return validateTickScript({...settings, profile: profile.id, serverboundIds: ids, actions});
}

function plan(profile) {
  const heap = options["java-heap"] || "2g";
  if (!/^[123]g$/.test(heap)) throw new Error("JVM heap must be 1g, 2g or 3g");
  const activeProcessors = Number(options["active-processors"] || 2);
  if (!Number.isSafeInteger(activeProcessors) || activeProcessors < 1 || activeProcessors > 64) throw new Error("active processors must be 1..64");
  const work = join(root, "port/work", profile.id);
  const vanilla = join(work, "client-named.jar");
  const text = readFileSync(join(work, "classpath.txt"), "utf8").trim();
  const libraries = (text.startsWith("/") ? text.split(":") : text.split(delimiter)).filter(Boolean)
    .map(path => path.replace(/^\/([a-zA-Z])\//, "$1:/")).filter(path => !/client-(named|original)\.jar$/.test(path));
  const home = process.env.GAIUS_JAVA_HOME || process.env.JAVA_HOME;
  if (!home) throw new Error("set GAIUS_JAVA_HOME to the cached JDK");
  const repository = join(process.env.USERPROFILE, ".m2/repository/org/ow2/asm");
  const versions = readdirSync(join(repository, "asm"), {withFileTypes: true}).filter(entry => entry.isDirectory())
    .map(entry => entry.name).sort((left, right) => right.localeCompare(left, undefined, {numeric: true}));
  const version = versions.find(value => ["asm", "asm-tree"].every(artifact => existsSync(join(repository, artifact, value, `${artifact}-${value}.jar`))));
  if (!version) throw new Error("offline ASM and ASM tree unavailable; no downloads allowed");
  const asm = ["asm", "asm-tree"].map(artifact => join(repository, artifact, version, `${artifact}-${version}.jar`));
  const classpath = [vanilla, ...libraries, ...asm];
  const missing = classpath.filter(path => !existsSync(path));
  if (missing.length) throw new Error(`offline inputs missing: ${missing.join(", ")}`);
  const classes = join(out, "classes");
  const sources = [join(root, "port/tools/src/main/java/dev/gaius/tools/patches/telemetry/TickPacketParityPatches.java"),
    ...["TickPacketRecorder", "TickParityClient", "TickParityScenario", "TickParitySettle", "TickParitySettleProbe"].map(name => join(root, `port/src/main/java/dev/gaius/parity/${name}.java`)),
    ...["PacketParityServer", "TickParityServer", "TickParityExecutors", "TickParityChunkOrder"].map(name => join(root, `port/scripts/packet-parity/${name}.java`)),
    join(root, "port/scripts/worldgen-seed-parity/compat", profile.id, "ParityCompat.java")];
  return {profile: profile.id, vanilla, classes, classpath, sources, java: join(home, "bin/java.exe"), javac: join(home, "bin/javac.exe"),
    javaVersion: profile.javaVersion, heap, activeProcessors,
    flags: [`-Xmx${heap}`, `-XX:ActiveProcessorCount=${activeProcessors}`, "-Dmax.bg.threads=1", "-Duser.language=en", "-Duser.country=US"]};
}

if (mode === "compare") {
  const result = compareTickExact(events(resolve(options.left)), events(resolve(options.right)));
  const {ledger, ...summary} = result;
  save("comparison.json", summary);
  writeFileSync(join(out, "ledger.jsonl"), ledger.map(row => JSON.stringify(row)).join("\n") + "\n", {flag: "wx"});
  console.log(`TICK_PARITY events=${summary.events} unexplained=${summary.unexplained}`);
  process.exitCode = result.passed ? 0 : 1;
} else {
  if (!options.profile || !/^[A-Za-z0-9._-]+$/.test(options.profile)) throw new Error("explicit profile required");
  const profile = json(join(root, "port/versions", options.profile + ".json"));
  const preparation = plan(profile);
  const provenance = {sourceSha256: await sha(preparation.vanilla),
    harnessSourceSha256: Object.fromEntries(await Promise.all(preparation.sources.map(async path => [path, await sha(path)])))};
  if (mode === "prepare") {
    save("plan.json", {...preparation, ...provenance, jvmStarted: false});
    console.log(`TICK_PARITY_PREPARED profile=${profile.id} dependencies=offline JVMs=0`);
  } else {
    mkdirSync(preparation.classes, {recursive: true});
    const quote = value => '"' + value.replaceAll("\\", "/").replaceAll('"', '\\"') + '"';
    const argumentsFile = join(out, "javac.args");
    writeFileSync(argumentsFile, ["--release", String(preparation.javaVersion), "-proc:none", "-cp", preparation.classpath.join(delimiter),
      "-d", preparation.classes, ...preparation.sources].map(quote).join("\n") + "\n", {flag: "wx"});
    await jvm(preparation.javac, [`-J-Xmx${preparation.heap}`, "-J-XX:ActiveProcessorCount=2", "@" + argumentsFile], "compile");
    const classpath = [preparation.classes, ...preparation.classpath].join(delimiter);
    const tablePath = join(out, "protocol.json");
    await jvm(preparation.java, [...preparation.flags, "-cp", classpath, "dev.gaius.parity.PacketParityServer", "--protocol-table", tablePath], "protocol");
    await jvm(preparation.java, [...preparation.flags, "-cp", classpath, "dev.gaius.tools.patches.telemetry.TickPacketParityPatches",
      preparation.vanilla, preparation.classes], "instrument");
    const owners = ["server/MinecraftServer", "network/Connection", "server/network/ServerGamePacketListenerImpl",
      "server/network/ServerCommonPacketListenerImpl", "server/level/ChunkHolder", "world/entity/Entity", "util/Util"];
    const instrumentation = Object.fromEntries(await Promise.all(owners.map(async owner =>
      [owner, await sha(join(preparation.classes, "net/minecraft", owner + ".class"))])));
    save("instrumentation.json", {...provenance, instrumentation});
    save("script.json", scriptFrom(profile, json(tablePath)));
    const runs = [];
    const attempts = [];
    for (const name of ["run-a", "run-b"]) {
      const directory = join(out, name);
      mkdirSync(directory);
      const attempt = {name, processPassed: false, capturePassed: false};
      try {
        await jvm(preparation.java, [...preparation.flags, "-cp", classpath, "dev.gaius.parity.TickParityServer", join(out, "script.json"), directory], name, directory);
        attempt.processPassed = true;
      } catch (error) {
        attempt.processError = error.message;
      }
      let rows = [];
      try {
        rows = events(join(directory, "events.jsonl"));
        attempt.capture = validateTickCapture(rows);
        attempt.capturePassed = true;
      } catch (error) {
        attempt.captureError = error.message;
      }
      attempt.events = rows.length;
      attempt.offThreadEntities = rows.filter(row => ["entity", "entity-identity"].includes(row.kind) && row.tick === -1).length;
      attempt.complete = rows.at(-1)?.kind === "complete";
      attempts.push(attempt);
      runs.push(rows);
    }
    save("attempts.json", attempts);
    const comparison = attempts.every(attempt => attempt.processPassed && attempt.capturePassed)
      ? compareTickExact(...runs) : {method: "tick-exact-v1", passed: false, unexplained: null,
        events: Math.max(...attempts.map(attempt => attempt.events)), validationFailed: true, ledger: []};
    const {ledger, ...result} = comparison;
    save("comparison.json", {...result, ...provenance, instrumentation, scriptSha256: await sha(join(out, "script.json")),
      capturesSha256: await Promise.all(["run-a", "run-b"].map(name => sha(join(out, name, "events.jsonl"))))});
    writeFileSync(join(out, "ledger.jsonl"), ledger.map(row => JSON.stringify(row)).join("\n") + "\n", {flag: "wx"});
    console.log(`TICK_PARITY events=${result.events} unexplained=${result.unexplained}`);
    process.exitCode = result.passed ? 0 : 1;
  }
}
