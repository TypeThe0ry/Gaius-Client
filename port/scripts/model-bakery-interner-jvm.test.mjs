// JVM gates of Minecraft262BrowserPatcher.patchModelBakeryInterner (the model bake's interners over
// dev.gaius.browser.BrowserStrongInterner instead of Guava's strong interner):
//
// 1. port/mc/fixtures/ModelBakeryInternerPatchCheck.java applies the real patch to the profile's
//    vanilla client jar: exactly the two Interners.newStrongInterner() call sites of the
//    ModelBakery$InternerImpl constructor change, the patch is idempotent, and a class with another
//    Interners call or a single site fails it. It also writes the harness copy of the patched class.
// 2. port/mc/fixtures/ModelBakeInternerCheck.java runs the vanilla model bake of the client pack
//    (block models, block states, items, discovery, real SpriteLoader atlases) once per interner and
//    requires every intern call of the real bake to return the same instance with Guava's and the
//    browser interner, unchanged hashCodes of interned keys, and the same BakingResult identity graph.
//
//   node port/scripts/model-bakery-interner-jvm.test.mjs --profile <id>
// Needs port/work/<id>/client-named.jar and classpath.txt. Step 1 runs on every modern-patch-set
// profile; step 2 needs a client whose bake pipeline has the shape the harness calls.
import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync} from "node:fs";
import {tmpdir} from "node:os";
import {delimiter, join} from "node:path";
import {fileURLToPath} from "node:url";

const root = fileURLToPath(new URL("../..", import.meta.url));
const profileIndex = process.argv.indexOf("--profile");
const profile = profileIndex > 0 ? process.argv[profileIndex + 1] : process.env.GAIUS_PROFILE;
assert.ok(profile, "--profile <id> (or GAIUS_PROFILE) is required");
const work = join(root, "port/work", profile);
const jar = join(work, "client-named.jar");
assert.ok(existsSync(jar), `missing ${jar}`);
const libraries = readFileSync(join(work, "classpath.txt"), "utf8").trim().split(/[:;]/).filter(Boolean)
  .join(delimiter);
const home = process.env.GAIUS_JAVA_HOME || process.env.JAVA_HOME;
const exe = name => home ? join(home, "bin", process.platform === "win32" ? `${name}.exe` : name) : name;
const m2 = process.env.GAIUS_MAVEN_REPOSITORY || join(process.env.USERPROFILE || process.env.HOME, ".m2", "repository");
const asm = ["asm", "asm-tree", "asm-util", "asm-analysis"]
  .map(name => join(m2, "org/ow2/asm", name, "9.8", `${name}-9.8.jar`));
asm.push(join(m2, "org/teavm/teavm-core/0.15.0/teavm-core-0.15.0.jar"));
for (const file of asm) assert.ok(existsSync(file), `missing ${file}`);

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {encoding: "utf8", maxBuffer: 256 << 20, ...options});
  assert.equal(result.status, 0,
    `${command} failed\n${(result.stdout || "").slice(-4000)}${(result.stderr || "").slice(-4000)}`);
  return result.stdout;
}

const out = mkdtempSync(join(tmpdir(), "gaius-model-bakery-interner-"));
try {
  const tools = join(out, "tools");
  const patched = join(out, "patched");
  const harness = join(out, "harness");
  const classes = join(out, "classes");
  const scratch = join(out, "run");
  for (const dir of [tools, patched, harness, classes, scratch]) mkdirSync(dir);

  run(exe("javac"), ["-nowarn", "-proc:none", "--release", "21", "-cp", asm.join(delimiter),
    "-sourcepath", join(root, "port/tools/src/main/java"), "-d", tools,
    join(root, "port/mc/fixtures/ModelBakeryInternerPatchCheck.java")]);
  const patch = run(exe("java"), ["-cp", [tools, ...asm].join(delimiter), "ModelBakeryInternerPatchCheck",
    jar, patched, harness]);
  assert.match(patch, /MODEL_BAKERY_INTERNER_PATCH_OK changed=2 idempotent=true negative=2/);
  console.log(patch.trim().split("\n").at(-1));

  const classpath = [jar, libraries].join(delimiter);
  const compiled = spawnSync(exe("javac"), ["-nowarn", "-proc:none", "-encoding", "UTF-8", "-cp", classpath, "-d", classes,
    join(root, "port/src/main/java/dev/gaius/browser/BrowserStrongInterner.java"),
    join(root, "port/mc/fixtures/ModelBakeInternerCheck.java")], {encoding: "utf8"});
  // The harness calls the bake pipeline of the canonical train profile's client (model loaders,
  // MaterialBaker over two SpriteLoader preparations); a profile whose API differs fails here.
  assert.equal(compiled.status, 0, `the bake harness does not compile against ${jar} (step 1 passed):\n`
    + compiled.stdout + compiled.stderr);
  const bake = run(exe("java"), ["-Xmx12g", "--enable-native-access=ALL-UNNAMED",
    "--sun-misc-unsafe-memory-access=allow", "-Djava.awt.headless=true",
    `-Dorg.lwjgl.system.SharedLibraryExtractPath=${join(out, "lwjgl")}`,
    "-cp", [harness, classes, classpath].join(delimiter), "ModelBakeInternerCheck", scratch],
    {cwd: scratch, timeout: 1_800_000});
  const lines = bake.split("\n").filter(line => /BAKE_INPUT|BAKE |INTERN_REPLAY|MODEL_BAKE_INTERNER_OK/.test(line))
    .map(line => line.replace(/^.*\[STDOUT\]: /, ""));
  for (const line of lines) console.log(line);
  assert.ok(lines.some(line => /^MODEL_BAKE_INTERNER_OK calls=[1-9]\d* graph=/.test(line)), "bake harness did not pass");
} finally {
  rmSync(out, {recursive: true, force: true});
}
