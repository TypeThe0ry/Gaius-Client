#!/usr/bin/env node

/**
 * Read-only regression smoke for the browser TypeToken overlay.
 *
 * This intentionally inspects the class files in the generated overlay JAR
 * instead of recompiling or opening the browser.  A green build identity is
 * not enough here: the old 26.2 artifact had current sidecars but still
 * contained the anonymous Gson TypeToken allocations that crash during client
 * bootstrap.  Keeping this check independent of javap also makes it usable
 * from PowerShell, Git Bash, and a machine with only Node installed.
 */

import assert from "node:assert/strict";
import {inflateRawSync} from "node:zlib";
import {readFile, readdir} from "node:fs/promises";
import {readFileSync} from "node:fs";
import {existsSync} from "node:fs";
import {basename, extname, isAbsolute, join, relative, resolve} from "node:path";
import {fileURLToPath} from "node:url";

const root = fileURLToPath(new URL("../..", import.meta.url));
const args = process.argv.slice(2);
// port/config.json's versionProfile is the default profile; there is no
// hard-coded fallback, so an unreadable config fails the smoke.
const configuredProfilePath = JSON.parse(
  readFileSync(join(root, "port/config.json"), "utf8"),
).versionProfile;

function usage() {
  console.log(`Usage: node port/scripts/gson-type-token-smoke.mjs [options]

Options:
  --profile <id-or-path>  Check one profile; may be repeated.
  --all                  Check every JSON profile under port/versions.
  --overlay <path>       Read one explicit overlay directory (single profile).
  --jar <path>           Read one explicit overlay JAR (single profile).
  --json                 Print machine-readable results after the summary.
  --help                 Show this help.

Environment:
  GAIUS_VERSION_PROFILE_PATH  Active profile path when --profile is omitted.
  GAIUS_OVERLAY_DIRECTORY     Explicit overlay directory for one profile.
`);
}

if (args.includes("--help")) {
  usage();
  process.exit(0);
}

function nativePath(value) {
  // Git Bash exports /c/Users/... while Node on Windows expects C:/Users/...
  // Keep this local to the smoke so it never mutates the caller's environment.
  if (process.platform === "win32" && /^\/[A-Za-z](?:\/|$)/u.test(value)) {
    return `${value[1].toUpperCase()}:${value.slice(2)}`;
  }
  return value;
}

function pathFromRoot(value) {
  const native = nativePath(value);
  return isAbsolute(native) ? resolve(native) : resolve(root, native);
}

function fail(message) {
  throw new Error(`Gson TypeToken smoke failed: ${message}`);
}

function requireArg(index, option) {
  const value = args[index + 1];
  if (!value || value.startsWith("--")) fail(`${option} needs a value`);
  return value;
}

const requestedProfiles = [];
let allProfiles = false;
let explicitOverlay;
let explicitJar;
let printJson = false;
for (let index = 0; index < args.length; index++) {
  switch (args[index]) {
    case "--profile":
      requestedProfiles.push(requireArg(index++, "--profile"));
      break;
    case "--all":
      allProfiles = true;
      break;
    case "--overlay":
      explicitOverlay = pathFromRoot(requireArg(index++, "--overlay"));
      break;
    case "--jar":
      explicitJar = pathFromRoot(requireArg(index++, "--jar"));
      break;
    case "--json":
      printJson = true;
      break;
    default:
      if (args[index].startsWith("--")) fail(`unknown option ${args[index]}`);
      fail(`unexpected argument ${args[index]}`);
  }
}
if (allProfiles && requestedProfiles.length > 0) {
  fail("--all and --profile cannot be combined");
}
if ((explicitOverlay || explicitJar) && allProfiles) {
  fail("--overlay/--jar cannot be combined with --all");
}
if (explicitOverlay && explicitJar) fail("--overlay and --jar are mutually exclusive");

async function readJson(path, label) {
  try {
    return JSON.parse(await readFile(path, "utf8"));
  } catch (error) {
    fail(`cannot read ${label} ${path}: ${error.message}`);
  }
}

function profilePath(value) {
  // Match version-profile.sh's accepted relative form, while also accepting
  // an absolute path for CI/PowerShell callers.
  if (value === undefined) {
    const configured = process.env.GAIUS_VERSION_PROFILE_PATH;
    if (configured) value = configured;
    else value = undefined;
  }
  if (value === undefined) {
    value = configuredProfilePath;
  }
  value = value.replaceAll("\\", "/");
  if (/^\d+(?:\.\d+)+$/u.test(value)) {
    value = `port/versions/${value}.json`;
  } else if (value.startsWith("versions/")) {
    value = `port/${value}`;
  }
  const path = pathFromRoot(value);
  const versions = resolve(root, "port/versions");
  const relativePath = relative(versions, path);
  if (relativePath.startsWith("..") || isAbsolute(relativePath) || extname(path) !== ".json") {
    fail(`profile must be a JSON file under ${versions}: ${path}`);
  }
  return path;
}

async function selectedProfilePaths() {
  if (allProfiles) {
    const entries = await readdir(resolve(root, "port/versions"), {withFileTypes: true});
    return entries.filter(entry => entry.isFile() && entry.name.endsWith(".json"))
      .map(entry => resolve(root, "port/versions", entry.name)).sort();
  }
  if (requestedProfiles.length > 0) return requestedProfiles.map(profilePath);
  return [profilePath()];
}

function parseU2(buffer, offset) {
  return buffer.readUInt16BE(offset);
}

function parseU4(buffer, offset) {
  return buffer.readUInt32BE(offset);
}

function parseClass(bytes, entryName) {
  if (bytes.length < 10 || bytes.readUInt32BE(0) !== 0xcafebabe) {
    fail(`${entryName} is not a JVM class file`);
  }
  let offset = 8;
  const constantPoolCount = parseU2(bytes, offset);
  offset += 2;
  const constantPool = Array(constantPoolCount).fill(null);
  for (let index = 1; index < constantPoolCount; index++) {
    const tag = bytes[offset++];
    switch (tag) {
      case 1: {
        const length = parseU2(bytes, offset);
        offset += 2;
        constantPool[index] = {tag, value: bytes.toString("utf8", offset, offset + length)};
        offset += length;
        break;
      }
      case 3:
      case 4:
        offset += 4;
        constantPool[index] = {tag};
        break;
      case 5:
      case 6:
        offset += 8;
        constantPool[index] = {tag};
        index++;
        break;
      case 7:
      case 8:
      case 16:
      case 19:
      case 20:
        constantPool[index] = {tag, index: parseU2(bytes, offset)};
        offset += 2;
        break;
      case 9:
      case 10:
      case 11:
      case 12:
      case 17:
      case 18:
        constantPool[index] = {
          tag,
          first: parseU2(bytes, offset),
          second: parseU2(bytes, offset + 2),
        };
        offset += 4;
        break;
      case 15:
        constantPool[index] = {tag, kind: bytes[offset], index: parseU2(bytes, offset + 1)};
        offset += 3;
        break;
      default:
        fail(`${entryName} has unsupported constant-pool tag ${tag}`);
    }
  }

  const utf8 = index => constantPool[index]?.tag === 1 ? constantPool[index].value : undefined;
  const className = index => {
    const entry = constantPool[index];
    return entry?.tag === 7 ? utf8(entry.index) : undefined;
  };
  const nameAndType = index => {
    const entry = constantPool[index];
    return entry?.tag === 12
      ? {name: utf8(entry.first), descriptor: utf8(entry.second)} : undefined;
  };
  const methodRef = index => {
    const entry = constantPool[index];
    if (!entry || ![10, 11].includes(entry.tag)) return undefined;
    const member = nameAndType(entry.second);
    return member ? {owner: className(entry.first), ...member} : undefined;
  };

  offset += 2; // access_flags
  const thisClass = className(parseU2(bytes, offset));
  offset += 2;
  const superClass = className(parseU2(bytes, offset));
  offset += 2;
  const interfaceCount = parseU2(bytes, offset);
  offset += 2 + interfaceCount * 2;

  function skipAttributes(count) {
    for (let index = 0; index < count; index++) {
      offset += 2;
      const length = parseU4(bytes, offset);
      offset += 4 + length;
    }
  }

  const fieldCount = parseU2(bytes, offset);
  offset += 2;
  for (let index = 0; index < fieldCount; index++) {
    offset += 6;
    const attributeCount = parseU2(bytes, offset);
    offset += 2;
    skipAttributes(attributeCount);
  }

  const methods = [];
  const methodCount = parseU2(bytes, offset);
  offset += 2;
  for (let index = 0; index < methodCount; index++) {
    const access = parseU2(bytes, offset);
    const name = utf8(parseU2(bytes, offset + 2));
    const descriptor = utf8(parseU2(bytes, offset + 4));
    offset += 6;
    const code = [];
    const attributeCount = parseU2(bytes, offset);
    offset += 2;
    for (let attribute = 0; attribute < attributeCount; attribute++) {
      const attributeName = utf8(parseU2(bytes, offset));
      const length = parseU4(bytes, offset + 2);
      const start = offset + 6;
      if (attributeName === "Code" && length >= 8) {
        const codeLength = parseU4(bytes, start + 4);
        code.push(bytes.subarray(start + 8, start + 8 + codeLength));
      }
      offset = start + length;
    }
    methods.push({access, name, descriptor, code: Buffer.concat(code)});
  }

  return {entryName, thisClass, superClass, methods, constantPool, methodRef, className};
}

function scanInstructions(parsed, code) {
  const newClasses = [];
  const methodRefs = [];
  let offset = 0;
  const readIndex = position => code.readUInt16BE(position + 1);
  while (offset < code.length) {
    const opcode = code[offset];
    if (opcode === 0xbb && offset + 2 < code.length) {
      newClasses.push(parsed.className(code.readUInt16BE(offset + 1)));
    } else if ([0xb6, 0xb7, 0xb8, 0xb9, 0xba].includes(opcode) && offset + 2 < code.length) {
      methodRefs.push(parsed.methodRef(readIndex(offset)));
    }
    let operands = 0;
    if (opcode === 0x10 || opcode === 0x12 || (opcode >= 0x15 && opcode <= 0x19)
        || (opcode >= 0x36 && opcode <= 0x3a) || opcode === 0xa9 || opcode === 0xbc) {
      operands = 1;
    } else if ([0x11, 0x13, 0x14, 0x84, 0x99, 0x9a, 0x9b, 0x9c, 0x9d, 0x9e,
      0x9f, 0xa0, 0xa1, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7, 0xa8, 0xb2,
      0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xbb, 0xbd, 0xc0, 0xc1,
      0xc6, 0xc7].includes(opcode)) {
      operands = 2;
    } else if (opcode === 0xb9 || opcode === 0xba || opcode === 0xc8 || opcode === 0xc9) {
      operands = 4;
    } else if (opcode === 0xc5) {
      operands = 3;
    } else if (opcode === 0xaa || opcode === 0xab) {
      // Switches are not expected in the tiny methods under test, but skip
      // them correctly so a future compiler change cannot desynchronise the
      // rest of the method scan.
      const padding = (4 - ((offset + 1) & 3)) & 3;
      const base = offset + 1 + padding;
      if (base + 12 > code.length) break;
      if (opcode === 0xaa) {
        const low = code.readInt32BE(base + 4);
        const high = code.readInt32BE(base + 8);
        operands = padding + 12 + Math.max(0, high - low + 1) * 4;
      } else {
        const pairs = code.readInt32BE(base + 4);
        operands = padding + 8 + Math.max(0, pairs) * 8;
      }
    } else if (opcode === 0xc4) {
      operands = code[offset + 1] === 0x84 ? 5 : 3;
    }
    offset += 1 + operands;
  }
  return {newClasses: newClasses.filter(Boolean), methodRefs: methodRefs.filter(Boolean)};
}

class ZipReader {
  constructor(buffer) {
    this.buffer = buffer;
    this.entries = [];
    const endSignature = Buffer.from([0x50, 0x4b, 0x05, 0x06]);
    const end = buffer.lastIndexOf(endSignature);
    if (end < 0) fail("overlay JAR has no ZIP end record");
    const count = buffer.readUInt16LE(end + 10);
    let offset = buffer.readUInt32LE(end + 16);
    for (let index = 0; index < count; index++) {
      if (buffer.readUInt32LE(offset) !== 0x02014b50) fail("malformed ZIP central directory");
      const method = buffer.readUInt16LE(offset + 10);
      const compressedSize = buffer.readUInt32LE(offset + 20);
      const nameLength = buffer.readUInt16LE(offset + 28);
      const extraLength = buffer.readUInt16LE(offset + 30);
      const commentLength = buffer.readUInt16LE(offset + 32);
      const name = buffer.toString("utf8", offset + 46, offset + 46 + nameLength);
      const localOffset = buffer.readUInt32LE(offset + 42);
      this.entries.push({name, method, compressedSize, localOffset});
      offset += 46 + nameLength + extraLength + commentLength;
    }
  }

  names() {
    return this.entries.map(entry => entry.name);
  }

  read(name) {
    const entry = [...this.entries].reverse().find(candidate => candidate.name === name);
    if (!entry) return undefined;
    const offset = entry.localOffset;
    if (this.buffer.readUInt32LE(offset) !== 0x04034b50) fail(`bad ZIP entry ${name}`);
    const nameLength = this.buffer.readUInt16LE(offset + 26);
    const extraLength = this.buffer.readUInt16LE(offset + 28);
    const start = offset + 30 + nameLength + extraLength;
    const compressed = this.buffer.subarray(start, start + entry.compressedSize);
    if (entry.method === 0) return compressed;
    if (entry.method === 8) return inflateRawSync(compressed);
    fail(`unsupported ZIP compression method ${entry.method} for ${name}`);
  }
}

function method(parsed, name, descriptor) {
  const found = parsed.methods.find(candidate => candidate.name === name
    && candidate.descriptor === descriptor);
  assert.ok(found, `${parsed.thisClass} is missing ${name}${descriptor}`);
  return found;
}

function hasMethodRef(scan, owner, name, descriptor) {
  return scan.methodRefs.some(ref => ref.owner === owner && ref.name === name
    && ref.descriptor === descriptor);
}

function checkTypeToken(parsed, jarName) {
  const typeDescriptor = "Ljava/lang/reflect/Type;";
  const typeTokenDescriptor = "Lcom/google/gson/reflect/TypeToken;";
  const parameterized = method(parsed, "getParameterized",
    `(Ljava/lang/reflect/Type;[Ljava/lang/reflect/Type;)${typeTokenDescriptor}`);
  const constructor = method(parsed, "<init>", `(${typeDescriptor})V`);
  const scan = scanInstructions(parsed, parameterized.code);
  assert.ok(scan.newClasses.includes("com/google/gson/reflect/TypeToken"),
    `${jarName}: Gson getParameterized no longer constructs an explicit TypeToken`);
  assert.ok(hasMethodRef(scan, "com/google/gson/internal/GsonTypes",
    "newParameterizedTypeWithOwner",
    "(Ljava/lang/reflect/Type;Ljava/lang/Class;[Ljava/lang/reflect/Type;)Ljava/lang/reflect/ParameterizedType;"),
  `${jarName}: Gson getParameterized lost GsonTypes.newParameterizedTypeWithOwner`);
  assert.ok(hasMethodRef(scan, "com/google/gson/reflect/TypeToken", "<init>",
    `(${typeDescriptor})V`),
  `${jarName}: Gson getParameterized lost the explicit TypeToken(Type) constructor`);
  return {
    parameterizedCodeBytes: parameterized.code.length,
    constructorAccess: constructor.access,
  };
}

function checkAnonymousInitializers(zip) {
  const result = {};
  for (const className of ["net/minecraft/client/Options", "net/minecraft/client/sounds/SoundManager"]) {
    const entry = `${className}.class`;
    const bytes = zip.read(entry);
    assert.ok(bytes, `overlay is missing ${entry}`);
    const parsed = parseClass(bytes, entry);
    const initializer = method(parsed, "<clinit>", "()V");
    const scan = scanInstructions(parsed, initializer.code);
    assert.ok(!scan.newClasses.some(name => /(?:Options|SoundManager)\$1$/u.test(name)),
      `${entry}: <clinit> still allocates an anonymous TypeToken`);
    assert.ok(hasMethodRef(scan, "com/google/gson/internal/GsonTypes",
      "newParameterizedTypeWithOwner",
      "(Ljava/lang/reflect/Type;Ljava/lang/Class;[Ljava/lang/reflect/Type;)Ljava/lang/reflect/ParameterizedType;"),
    `${entry}: <clinit> has no explicit Gson parameterized type construction`);
    assert.ok(hasMethodRef(scan, "com/google/gson/reflect/TypeToken", "get",
      "(Ljava/lang/reflect/Type;)Lcom/google/gson/reflect/TypeToken;"),
    `${entry}: <clinit> has no Gson TypeToken.get(Type) call`);
    result[className] = {initializerCodeBytes: initializer.code.length};
  }
  return result;
}

function checkGuavaAndDfu(zip) {
  const guavaEntry = "com/google/common/reflect/TypeToken.class";
  const guavaBytes = zip.read(guavaEntry);
  assert.ok(guavaBytes, `overlay is missing ${guavaEntry}`);
  const guava = parseClass(guavaBytes, guavaEntry);
  const guavaConstructor = method(guava, "<init>", "(Ljava/lang/reflect/Type;)V");
  assert.ok((guavaConstructor.access & 0x0004) !== 0,
    "Guava TypeToken(Type) constructor is not protected for browser witnesses");
  assert.equal(guavaConstructor.access & 0x0002, 0,
    "Guava TypeToken(Type) constructor is still private");

  const witnesses = [];
  for (const entry of zip.names()) {
    if (!entry.endsWith(".class")) continue;
    // DataFixerUpper's witnesses are the only relevant subclasses in this
    // overlay.  Limit parsing to its package to keep this check quick even on
    // a jar that carries a large Minecraft client class graph.
    if (!entry.startsWith("com/mojang/datafixers/")) continue;
    const parsed = parseClass(zip.read(entry), entry);
    if (parsed.superClass !== "com/google/common/reflect/TypeToken") continue;
    const patched = parsed.methods.filter(candidate => candidate.name === "<init>")
      .some(candidate => hasMethodRef(scanInstructions(parsed, candidate.code),
        "com/google/common/reflect/TypeToken", "<init>", "(Ljava/lang/reflect/Type;)V"));
    witnesses.push({entry, patched});
  }
  assert.ok(witnesses.length > 0, "no DataFixerUpper Guava TypeToken witness was found");
  assert.ok(witnesses.every(witness => witness.patched),
    `unpatched DataFixerUpper TypeToken witness: ${witnesses.filter(witness => !witness.patched).map(witness => witness.entry).join(", ")}`);
  return {witnesses: witnesses.length, patchedWitnesses: witnesses.filter(witness => witness.patched).length};
}

function defaultOverlayDirectories(profile) {
  if (process.env.GAIUS_OVERLAY_DIRECTORY) {
    return [pathFromRoot(process.env.GAIUS_OVERLAY_DIRECTORY)];
  }
  const shared = resolve(root, "port/work/overlays");
  const scoped = resolve(shared, profile.id);
  // An explicit profile selection is an isolated-build request even when the
  // caller did not also set GAIUS_VERSION_PROFILE_PATH.  Never let an
  // isolated profile check consume the historical shared 26.2 overlay.
  if (process.env.GAIUS_BUILD_ROOT || process.env.GAIUS_VERSION_PROFILE_PATH
      || requestedProfiles.length > 0 || allProfiles) {
    return [scoped];
  }
  return [shared, scoped];
}

function overlayJarCandidates(profile, directory) {
  const candidates = [
    `client-named-${profile.id}-gaius.jar`,
    `client-${profile.id}-gaius.jar`,
    `client-obfuscated-with-mappings-${profile.id}-gaius.jar`,
  ];
  return candidates.map(name => join(directory, name));
}

async function checkProfile(profilePathValue, index, total) {
  const profile = await readJson(profilePathValue, "version profile");
  if (typeof profile.id !== "string" || !profile.id) fail(`profile has no id: ${profilePathValue}`);
  const overlayDirectories = explicitOverlay
    ? [explicitOverlay] : defaultOverlayDirectories(profile);
  let jarPath = explicitJar;
  if (!jarPath) {
    for (const overlayDirectory of overlayDirectories) {
      jarPath = overlayJarCandidates(profile, overlayDirectory).find(existsSync);
      if (jarPath) break;
    }
  }
  if (!jarPath || !existsSync(jarPath)) {
    fail(`overlay JAR is missing for ${profile.id}; checked ${overlayDirectories.flatMap(
      overlayDirectory => overlayJarCandidates(profile, overlayDirectory)).join(", ")}`);
  }
  const jarBytes = await readFile(jarPath);
  const zip = new ZipReader(jarBytes);
  const names = new Set(zip.names());
  const required = [
    "net/minecraft/client/Options.class",
    "net/minecraft/client/sounds/SoundManager.class",
    "com/google/gson/reflect/TypeToken.class",
    "com/google/common/reflect/TypeToken.class",
  ];
  for (const entry of required) assert.ok(names.has(entry), `${profile.id}: overlay missing ${entry}`);
  const anonymous = checkAnonymousInitializers(zip);
  const gson = checkTypeToken(parseClass(zip.read("com/google/gson/reflect/TypeToken.class"),
    "com/google/gson/reflect/TypeToken.class"), basename(jarPath));
  const guava = checkGuavaAndDfu(zip);
  return {
    profile: profile.id,
    jar: relative(root, jarPath) || jarPath,
    bytes: jarBytes.length,
    anonymous,
    gson,
    guava,
    progress: `${index + 1}/${total}`,
  };
}

const profiles = await selectedProfilePaths();
if (profiles.length === 0) fail("no profiles found");
const results = [];
for (let index = 0; index < profiles.length; index++) {
  results.push(await checkProfile(profiles[index], index, profiles.length));
}
for (const result of results) {
  console.log(`Gson TypeToken browser smoke passed ${result.progress}: ${result.profile}`);
  console.log(JSON.stringify({
    profile: result.profile,
    jar: result.jar,
    bytes: result.bytes,
    gsonGetParameterizedCodeBytes: result.gson.parameterizedCodeBytes,
    dfuWitnesses: result.guava.witnesses,
  }));
}
if (printJson) console.log(JSON.stringify({ok: true, profiles: results}, null, 2));
