// JVM differential tests of the two class-library allocation cuts of the resource reload:
//
// 1. TInputStreamReader (port/overrides/classlib): the override's bytecode must equal TeaVM 0.15's
//    class except for the byte-buffer size (javap, constant-pool indices ignored), and
//    fixtures/ReaderDietDifferential.java, run once on TeaVM's original class and once on the
//    override, must print the same trace digest for every input: the synthetic corpus (malformed
//    UTF-8, surrogates, BOMs, truncated tails, sequences across the 64/1024/8192 boundaries, six
//    charsets, eight stream kinds, four read patterns) and every .json/.mcmeta resource of the
//    given vanilla pack or client jar, read the way Resource.openAsReader reads them.
// 2. TChannels.newChannel(InputStream) (port/src): fixtures/ChannelDietDifferential.java compares
//    the current read path with the one before the cut (a new byte[remaining] per read) on
//    TextureUtil.readResource's doubling loop for every resource of the pack and on edge cases.
//
//   node port/scripts/classlib-reader-channel-diet.test.mjs [--pack <vanilla-assets.pack.gz>] [--jar <client jar>]
// Without --pack/--jar only the synthetic corpora run. No TeaVM compile, no browser.
import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {existsSync, mkdirSync, mkdtempSync, rmSync} from "node:fs";
import {tmpdir} from "node:os";
import {delimiter, join} from "node:path";
import {fileURLToPath} from "node:url";

const root = fileURLToPath(new URL("../..", import.meta.url));
const home = process.env.GAIUS_JAVA_HOME || process.env.JAVA_HOME;
const exe = name => home ? join(home, "bin", process.platform === "win32" ? `${name}.exe` : name) : name;
const m2 = process.env.GAIUS_MAVEN_REPOSITORY || join(process.env.USERPROFILE || process.env.HOME, ".m2", "repository");
const teavm = ["teavm-classlib", "teavm-interop", "teavm-core", "teavm-platform", "teavm-jso", "teavm-jso-apis"]
  .map(name => join(m2, "org", "teavm", name, "0.15.0", `${name}-0.15.0.jar`));
for (const jar of teavm) assert.ok(existsSync(jar), `missing ${jar}`);

const corpus = [];
for (let i = 2; i < process.argv.length; i += 2) {
  const [flag, value] = [process.argv[i], process.argv[i + 1]];
  assert.ok(value && existsSync(value), `${flag} needs an existing file`);
  if (flag === "--pack") corpus.push(`pack=${value}`);
  else if (flag === "--jar") corpus.push(value);
  else assert.fail(`unknown argument ${flag}`);
}
const packs = corpus.filter(entry => entry.startsWith("pack="));

function run(command, args) {
  const result = spawnSync(command, args, {encoding: "utf8", maxBuffer: 256 << 20});
  assert.equal(result.status, 0, `${command} ${args.slice(-3).join(" ")}\n${result.stdout.slice(-3000)}${result.stderr.slice(-3000)}`);
  return result.stdout;
}

function javap(classpath, name) {
  return run(exe("javap"), ["-c", "-p", "-cp", classpath, name])
    .split("\n")
    .map(line => line.replace(/#\d+(, *\d+)?/g, "").replace(/^\s+\d+: /, "").replace(/\s+/g, " ").trim())
    .filter(line => line.length > 0);
}

const out = mkdtempSync(join(tmpdir(), "gaius-reader-channel-diet-"));
try {
  const reader = join(out, "reader");
  const fixtures = join(out, "fixtures");
  const channels = join(out, "channels");
  for (const dir of [reader, fixtures, channels]) mkdirSync(dir);
  const javac = (classpath, dir, ...sources) => run(exe("javac"),
    ["-nowarn", "-proc:none", "-encoding", "UTF-8", "-cp", classpath, "-d", dir, ...sources]);
  javac(teavm.join(delimiter), reader,
    join(root, "port/overrides/classlib/src/main/java/org/teavm/classlib/java/io/TInputStreamReader.java"));
  javac(teavm.join(delimiter), channels,
    join(root, "port/src/main/java/org/teavm/classlib/java/nio/channels/TChannels.java"));
  javac([channels, ...teavm].join(delimiter), fixtures,
    join(root, "port/scripts/fixtures/ReaderDietDifferential.java"),
    join(root, "port/scripts/fixtures/ChannelDietDifferential.java"));
  rmSync(join(fixtures, "org"), {recursive: true, force: true});

  // 1a. Same bytecode as TeaVM's class apart from the buffer size.
  const name = "org.teavm.classlib.java.io.TInputStreamReader";
  const original = javap(teavm[0], name);
  let override = javap(reader, name);
  const start = override.findIndex(line => line.startsWith("private static int inDataSize("));
  assert.ok(start > 0, "inDataSize not found");
  const end = override.indexOf("ireturn", override.lastIndexOf("invokestatic // Method java/lang/Math.max:(II)I")) + 1;
  override = [...override.slice(0, start), ...override.slice(end)]
    .filter(line => !/^private static final int (DEFAULT_IN_DATA|MIN_IN_DATA);$/.test(line));
  const call = override.indexOf("invokestatic // Method inDataSize:(Lorg/teavm/classlib/java/io/TInputStream;)I");
  assert.ok(call > 0 && override[call - 1] === "aload_1", "inDataSize call site not found");
  override.splice(call - 1, 2, "sipush 8192");
  assert.deepEqual(override, original, "TInputStreamReader differs from TeaVM's class beyond the buffer size");
  console.log(`TInputStreamReader bytecode = TeaVM 0.15 except inDataSize (${original.length} lines)`);

  // 1b. Differential run: TeaVM's class, then the override.
  const traces = [[fixtures, ...teavm], [reader, fixtures, ...teavm]].map(classpath =>
    run(exe("java"), ["-Xmx3g", "-cp", classpath.join(delimiter), "ReaderDietDifferential", ...corpus]).trimEnd().split("\n"));
  assert.match(traces[0][0], /^PROBE inData\(10 bytes\)=8192 inData\(9000 bytes\)=8192 inData\(chunked\)=8192$/);
  assert.match(traces[1][0], /^PROBE inData\(10 bytes\)=64 inData\(9000 bytes\)=8192 inData\(chunked\)=8192$/,
    "the override was not the class under test");
  assert.equal(traces[1].length, traces[0].length);
  for (let i = 1; i < traces[0].length; i++) {
    assert.equal(traces[1][i], traces[0][i], `reader trace differs: ${traces[0][i].split(" ")[0]}`);
  }
  const summary = traces[1].at(-1);
  assert.match(summary, /^READER_DIET corpus=\d+ synthetic=\d+ checks=\d+ sha256=[0-9a-f]{64}$/);
  if (corpus.length) assert.doesNotMatch(summary, / corpus=0 /);
  console.log(`${summary} (same as TeaVM's TInputStreamReader)`);

  // 2. TChannels against the read path before the cut.
  const channel = run(exe("java"), ["-Xmx3g", "-cp", [channels, fixtures, ...teavm].join(delimiter),
    "ChannelDietDifferential", ...packs]).trimEnd().split("\n").at(-1);
  assert.match(channel, /^CHANNEL_DIET resources=\d+ bytes=\d+ synthetic=\d+ checks=\d+ mismatches=0 sha256=[0-9a-f]{64}$/);
  if (packs.length) assert.doesNotMatch(channel, / resources=0 /);
  console.log(channel);
} finally {
  rmSync(out, {recursive: true, force: true});
}
