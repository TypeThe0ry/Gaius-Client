import {CANONICAL_PROFILE, LEGACY_PROFILE, escapeRegExp} from "./verification-profiles.mjs";
import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {mkdir, mkdtemp, readFile, rm, writeFile} from "node:fs/promises";
import {existsSync} from "node:fs";
import {homedir, tmpdir} from "node:os";
import {delimiter, join} from "node:path";
import {fileURLToPath} from "node:url";

const repositoryRoot = fileURLToPath(new URL("../..", import.meta.url));
const javaSuffix = process.platform === "win32" ? ".exe" : "";
const nativePath = (value) => {
  const text = String(value);
  if (process.platform === "win32" && /^\/[A-Za-z](?:\/|$)/.test(text)) {
    return `${text[1].toUpperCase()}:${text.slice(2)}`;
  }
  return text;
};
function jdkTool(name) {
  const configured = [process.env.GAIUS_JAVA_HOME, process.env.JAVA_HOME]
    .filter(Boolean).map(nativePath);
  if (process.platform === "win32") configured.push(
    "C:\\Program Files\\Java\\jdk-26.0.1",
    "C:\\Program Files\\Java\\jdk-21",
  );
  for (const home of [...new Set(configured)]) {
    const candidate = join(home, "bin", `${name}${javaSuffix}`);
    if (!existsSync(candidate)) continue;
    for (const versionArg of ["--version", "-version"]) {
      try {
        const version = execFileSync(candidate, [versionArg], {encoding: "utf8", stdio: ["ignore", "pipe", "pipe"]});
        const major = Number((version.match(/(?:version\s+|\s|^)(\d+)(?:\.|\s|$)/i) || [])[1]);
        if (Number.isInteger(major) && major >= 21) return candidate;
      } catch {
        // Try the alternate version flag, then the next configured JDK.
      }
    }
  }
  return `${name}${javaSuffix}`;
}
function splitClasspathText(raw) {
  if (raw.includes(";")) return raw.split(";");
  const parts = [];
  let start = 0;
  for (let index = 0; index < raw.length; index++) {
    if (raw[index] !== ":") continue;
    const isDriveColon = index === start + 1
      && /^[A-Za-z]$/.test(raw[start] ?? "")
      && /[\\/]/.test(raw[index + 1] ?? "");
    if (isDriveColon) continue;
    parts.push(raw.slice(start, index));
    start = index + 1;
  }
  parts.push(raw.slice(start));
  return parts;
}
assert.deepEqual(splitClasspathText("D:/a.jar:D:/b.jar"), ["D:/a.jar", "D:/b.jar"]);
assert.deepEqual(splitClasspathText("D:\\a.jar;D:\\b.jar"), ["D:\\a.jar", "D:\\b.jar"]);
assert.deepEqual(splitClasspathText("/d/a.jar:/d/b.jar"), ["/d/a.jar", "/d/b.jar"]);

function normalizeClasspath(text) {
  // CI classpath.txt is usually Unix-style ':' with /d/... paths, while a
  // local Windows rehearsal may use ';' or native D:\... entries.  Split only
  // separator colons; never split the colon belonging to a drive letter.
  const raw = text.trim();
  const parts = splitClasspathText(raw).map(nativePath).filter(Boolean);
  for (const entry of parts) assert.ok(existsSync(entry), `classpath entry does not exist: ${entry}`);
  return parts;
}
function run(tool, args, options = {}) {
  return execFileSync(tool, args, {encoding: "utf8", stdio: ["ignore", "pipe", "pipe"], ...options});
}
function javapMethod(text, signature) {
  const start = text.indexOf(signature);
  assert.notEqual(start, -1, `missing javap method ${signature}`);
  const next = text.indexOf("\n  ", start + signature.length);
  return text.slice(start, next < 0 ? text.length : next);
}

const java = jdkTool("java");
const javac = jdkTool("javac");
const javap = jdkTool("javap");
const asmRoot = join(homedir(), ".m2", "repository", "org", "ow2", "asm");
const asm = join(asmRoot, "asm", "9.8", "asm-9.8.jar");
const asmTree = join(asmRoot, "asm-tree", "9.8", "asm-tree-9.8.jar");
assert.ok(existsSync(asm) && existsSync(asmTree), "ASM 9.8 jars are required");
const toolsSource = join(repositoryRoot, "port", "tools", "src", "main", "java");
const helperSource = join(repositoryRoot, "port", "src", "main", "java", "dev", "gaius", "browser", "BrowserZipResourceIndex.java");
const fixtureSource = join(repositoryRoot, "port", "scripts", "fixtures", "BrowserZipResourcePatcherFixture.java");
const patcherSource = join(toolsSource, "dev", "gaius", "tools", "MinecraftClientPatcher.java");
const profiles = [
  {id: CANONICAL_PROFILE.id, jar: join(repositoryRoot, "port", "work", CANONICAL_PROFILE.id, "client-named.jar"), cpFile: join(repositoryRoot, "port", "work", CANONICAL_PROFILE.id, "classpath.txt")},
  {id: LEGACY_PROFILE.id, jar: join(repositoryRoot, "port", "work", LEGACY_PROFILE.id, "client-named.jar"), cpFile: join(repositoryRoot, "port", "work", LEGACY_PROFILE.id, "classpath.txt")},
];
const profileFromPath = (value) => String(value).replaceAll("\\", "/").split("/").pop()?.replace(/\.json$/, "");
const requestedProfile = process.env.GAIUS_MINECRAFT_VERSION
  || process.env.GAIUS_VERSION_PROFILE
  || (process.env.GAIUS_VERSION_PROFILE_PATH ? profileFromPath(process.env.GAIUS_VERSION_PROFILE_PATH) : undefined);
const selectedProfiles = requestedProfile ? profiles.filter((profile) => profile.id === requestedProfile) : profiles;
if (requestedProfile && selectedProfiles.length !== 1) {
  throw new Error(`unsupported GAIUS_MINECRAFT_VERSION=${requestedProfile}`);
}
const temp = await mkdtemp(join(tmpdir(), "gaius-browser-zip-patcher-"));
try {
  const classes = join(temp, "classes");
  await mkdir(classes, {recursive: true});
  const compileCp = [asm, asmTree, ...selectedProfiles.map((profile) => profile.jar)].join(delimiter);
  run(javac, ["--release", "21", "-proc:none", "-classpath", compileCp, "-sourcepath", toolsSource,
    "-d", classes, patcherSource, helperSource, fixtureSource], {timeout: 60_000});
  const results = [];
  for (const profile of selectedProfiles) {
    assert.ok(existsSync(profile.jar), `missing named jar ${profile.jar}`);
    const patchOutput = join(temp, profile.id.replaceAll(".", "_"));
    await mkdir(patchOutput, {recursive: true});
    const runtimeLibs = normalizeClasspath(await readFile(profile.cpFile, "utf8"));
    const runtimeCp = [classes, patchOutput, asm, asmTree, profile.jar, ...runtimeLibs].join(delimiter);
    const output = run(java, ["-Xverify:all", "-classpath", runtimeCp,
      "dev.gaius.browser.BrowserZipResourcePatcherFixture", profile.jar, patchOutput, profile.id], {timeout: 60_000});
    assert.match(output, new RegExp(`BROWSER_ZIP_RESOURCE_PATCHER_OK profile=${escapeRegExp(profile.id)}`));

    const fileBytecode = run(javap, ["-p", "-c", "-classpath", runtimeCp,
      "net.minecraft.server.packs.FilePackResources"]);
    const sharedBytecode = run(javap, ["-p", "-c", "-classpath", runtimeCp,
      "net.minecraft.server.packs.FilePackResources$SharedZipFileAccess"]);
    const list = fileBytecode.slice(fileBytecode.indexOf("listResources("));
    const close = sharedBytecode.slice(sharedBytecode.indexOf("close();"));
    assert.match(sharedBytecode, /browserResourceIndex/);
    assert.match(list, /BrowserZipResourceIndex\.forZip/);
    assert.match(list, /Collections\.enumeration/);
    assert.match(close, /browserResourceIndex/);
    assert.match(close, /aconst_null/);
    await writeFile(join(temp, `${profile.id}-FilePackResources-patched-javap.txt`), fileBytecode + "\n===== Shared =====\n" + sharedBytecode);
    results.push({profile: profile.id, fixture: output.trim(), jvmVerifyAll: true,
      patchedClasses: ["net.minecraft.server.packs.FilePackResources", "net.minecraft.server.packs.FilePackResources$SharedZipFileAccess"],
      structural: {browserResourceIndexField: true, helperForZip: true, collectionsEnumeration: true, closeClearsIndex: true}});
  }
  console.log(JSON.stringify({schema: "gaius.browser-zip-resource-patcher-smoke.v1", profiles: results}, null, 2));
} finally {
  await rm(temp, {recursive: true, force: true});
}
