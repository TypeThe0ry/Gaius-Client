#!/usr/bin/env node

/**
 * Static/overlay smoke for the browser authlib public-key response patch.
 *
 * The browser build uses Gson to materialize authlib's nested record-like
 * KeySetResponse/KeyData classes.  Their canonical constructors are private
 * and neither class originally had a no-argument constructor, which makes
 * the browser Gson path fail before a multiplayer session can start.  This
 * smoke checks both the patch source contract and, when --class-dir is given,
 * the actual class files emitted by AuthlibBrowserPatcher.  It deliberately
 * does not start Chrome, a Minecraft server, or a network endpoint.
 *
 * AuthlibBrowserPatcher serves authlib 9 (Minecraft 26.2, yggdrasil package)
 * and authlib 10 (Minecraft 26.3, services package).  --run <profile> compiles
 * the patcher, runs it on that profile's vanilla authlib jar from
 * port/work/<profile>, inspects the emitted classes and then loads them in a
 * JVM (bytecode verification of every patched class) to exercise the texture
 * URL helper and unpackTextures end to end: uploaded data: skins, the
 * discovery document's textures.minecraft.net rules, and for authlib 10 the
 * static fallback when discovery is unavailable (offline session).
 */

import assert from "node:assert/strict";
import {execFileSync, spawnSync} from "node:child_process";
import {existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync} from "node:fs";
import {homedir, tmpdir} from "node:os";
import {delimiter, join, isAbsolute, resolve, relative} from "node:path";
import {fileURLToPath} from "node:url";
import {sourceFile} from "../tools/sourcesets/sourcesets.mjs";

const root = fileURLToPath(new URL("../..", import.meta.url));
const args = process.argv.slice(2);

function usage() {
  console.log(`Usage: node port/scripts/authlib-keyset-constructor-smoke.mjs [options]

Options:
  --class-dir <path>  Inspect patched .class files under this directory.
  --authlib <9|10>    authlib major of --class-dir (default: detected from the
                      class files present).
  --run <profile>     Run AuthlibBrowserPatcher on port/work/<profile>'s authlib
                      jar, inspect its output and exercise it in a JVM
                      (for example --run 26.2 or --run 26.3; repeatable).
  --json              Print machine-readable JSON after the summary.
  --help              Show this help.

The default run is source/build-script static verification only.  A class
directory may be the AuthlibBrowserPatcher output directory, for example:
  --class-dir _tmp/authlib-patched26/patches
`);
}

if (args.includes("--help")) {
  usage();
  process.exit(0);
}

function fail(message) {
  throw new Error(`Authlib keyset constructor smoke failed: ${message}`);
}

function pathFromRoot(value) {
  const normalized = value.replaceAll("\\", "/");
  return isAbsolute(normalized) ? resolve(normalized) : resolve(root, normalized);
}

let classDir;
let classDirAuthlib;
const runProfiles = [];
let printJson = false;
for (let index = 0; index < args.length; index++) {
  switch (args[index]) {
    case "--class-dir":
      if (!args[index + 1] || args[index + 1].startsWith("--")) {
        fail("--class-dir needs a value");
      }
      classDir = pathFromRoot(args[++index]);
      break;
    case "--authlib":
      if (!["9", "10"].includes(args[index + 1])) fail("--authlib needs 9 or 10");
      classDirAuthlib = args[++index];
      break;
    case "--run":
      if (!/^[A-Za-z0-9._-]+$/.test(args[index + 1] || "")) fail("--run needs a profile id");
      runProfiles.push(args[++index]);
      break;
    case "--json":
      printJson = true;
      break;
    default:
      if (args[index].startsWith("--")) fail(`unknown option ${args[index]}`);
      fail(`unexpected argument ${args[index]}`);
  }
}

const patcherPath = join(root, "port/tools/src/main/java/dev/gaius/tools/AuthlibBrowserPatcher.java");
const overlaysPath = join(root, "port/scripts/build-overlays.sh");
const patcher = readFileSync(patcherPath, "utf8");
const overlays = readFileSync(overlaysPath, "utf8");

// Internal names per authlib major (the patcher's Names table).
const FLAVOURS = {
  "9": {
    keyInfo: "com/mojang/authlib/yggdrasil/YggdrasilServicesKeyInfo",
    keyInfoInterface: "com/mojang/authlib/yggdrasil/ServicesKeyInfo",
    session: "com/mojang/authlib/yggdrasil/YggdrasilMinecraftSessionService",
    payload: "com/mojang/authlib/yggdrasil/response/MinecraftTexturesPayload",
    helperDescriptor: "(Ljava/lang/String;)Z",
    discoveryRecords: [],
  },
  "10": {
    keyInfo: "com/mojang/authlib/services/MinecraftServicesKeyInfo",
    keyInfoInterface: "com/mojang/authlib/services/ServicesKeyInfo",
    session: "com/mojang/authlib/services/MinecraftServicesSessionService",
    payload: "com/mojang/authlib/services/response/MinecraftTexturesPayload",
    helperDescriptor:
      "(Lcom/mojang/authlib/services/MinecraftServicesDiscoveryService;Ljava/lang/String;)Z",
    discoveryRecords: [
      ["com/mojang/authlib/services/response/discovery/DiscoveryResponse",
        "(Ljava/lang/String;Ljava/lang/String;"
          + "Lcom/mojang/authlib/services/response/discovery/Discovery;)V"],
      ["com/mojang/authlib/services/response/discovery/Discovery",
        "(Ljava/lang/String;" + "Lcom/mojang/authlib/services/response/discovery/Endpoints;".repeat(5)
          + ")V"],
      ["com/mojang/authlib/services/response/discovery/Endpoints", "(Ljava/util/Map;)V"],
      ["com/mojang/authlib/services/response/discovery/Endpoint",
        "(Ljava/lang/String;Ljava/util/List;)V"],
    ],
  },
};
for (const flavour of Object.values(FLAVOURS)) {
  flavour.keySet = `${flavour.keyInfo}$KeySetResponse`;
  flavour.keyData = `${flavour.keyInfo}$KeyData`;
}
const keySetInternal = FLAVOURS["9"].keySet;
const keyDataInternal = FLAVOURS["9"].keyData;
const keyInfoInternal = FLAVOURS["9"].keyInfo;
const keySetEntry = `${keySetInternal}.class`;
const keyDataEntry = `${keyDataInternal}.class`;
const keyInfoEntry = `${keyInfoInternal}.class`;

function requireText(text, marker, label) {
  assert.ok(text.includes(marker), `${label} is missing marker: ${marker}`);
}

// Keep these checks deliberately specific: a broad “contains ByteBuffer”
// assertion would pass even if the constructor body or output path regressed.
for (const marker of [
  `KEY_SET_RESPONSE_ENTRY =\n            "${keySetEntry}"`,
  `KEY_DATA_ENTRY =\n            "${keyDataEntry}"`,
  `KEY_INFO_ENTRY =\n            "${keyInfoEntry}"`,
  `AUTHLIB10_KEY_SET_RESPONSE_ENTRY =\n            "${FLAVOURS["10"].keySet}.class"`,
  `AUTHLIB10_KEY_DATA_ENTRY =\n            "${FLAVOURS["10"].keyData}.class"`,
  `AUTHLIB10_KEY_INFO_ENTRY =\n            "${FLAVOURS["10"].keyInfo}.class"`,
  `AUTHLIB10_SESSION_ENTRY =\n            "${FLAVOURS["10"].session}.class"`,
  "ensureNoArgsConstructor(",
  '"java/util/Collections"',
  '"emptyList"',
  '"java/nio/ByteBuffer"',
  '"allocate"',
  '"(Ljava/util/List;Ljava/util/List;)V"',
  '"(Ljava/nio/ByteBuffer;)V"',
  '"dev/gaius/browser/BrowserCrypto"',
  '"parseRsaPublicKey"',
  '"([B)Ljava/security/PublicKey;"',
  // Every patched class is written to its own input entry path, so build-overlays'
  // `-C "$authlib_patch_classes" .` folds exactly those entries back into the jar.
  "writeEntry(authlibRoot, names.keySetResponseEntry(), keySetResponseWriter.toByteArray());",
  "writeEntry(authlibRoot, names.keyDataEntry(), keyDataWriter.toByteArray());",
  "writeEntry(authlibRoot, names.keyInfoEntry(), keyInfoWriter.toByteArray());",
  "writeEntry(authlibRoot, names.sessionEntry(), sessionWriter.toByteArray());",
  "writeEntry(authlibRoot, names.texturesPayloadEntry(), payloadWriter.toByteArray());",
  "boolean authlib10 = jar.getEntry(AUTHLIB10_SESSION_ENTRY) != null;",
]) {
  requireText(patcher, marker, "AuthlibBrowserPatcher.java");
}

// build-overlays.sh empties the authlib patch directory before the patcher
// runs and then folds the whole directory into the jar, so every KeyInfo class
// the patcher writes above is included.
for (const marker of [
  'find "$authlib_patch_classes" -type f -delete',
  `-C "$authlib_patch_classes" .`,
]) {
  requireText(overlays, marker, "build-overlays.sh");
}

function u1(bytes, offset) {
  assert.ok(offset < bytes.length, "truncated class file");
  return bytes[offset];
}

function u2(bytes, offset) {
  assert.ok(offset + 2 <= bytes.length, "truncated class file");
  return bytes.readUInt16BE(offset);
}

function u4(bytes, offset) {
  assert.ok(offset + 4 <= bytes.length, "truncated class file");
  return bytes.readUInt32BE(offset);
}

function parseClass(bytes, label) {
  assert.equal(bytes.readUInt32BE(0), 0xcafebabe, `${label} is not a JVM class file`);
  let offset = 8;
  const cpCount = u2(bytes, offset);
  offset += 2;
  const cp = Array(cpCount).fill(null);
  for (let index = 1; index < cpCount; index++) {
    const tag = u1(bytes, offset++);
    switch (tag) {
      case 1: {
        const length = u2(bytes, offset);
        offset += 2;
        assert.ok(offset + length <= bytes.length, `${label} has truncated UTF-8 constant`);
        cp[index] = {tag, value: bytes.toString("utf8", offset, offset + length)};
        offset += length;
        break;
      }
      case 3:
      case 4:
        cp[index] = {tag};
        offset += 4;
        break;
      case 5:
      case 6:
        cp[index] = {tag};
        offset += 8;
        index++;
        break;
      case 7:
      case 8:
      case 16:
      case 19:
      case 20:
        cp[index] = {tag, index: u2(bytes, offset)};
        offset += 2;
        break;
      case 9:
      case 10:
      case 11:
      case 12:
      case 17:
      case 18:
        cp[index] = {tag, first: u2(bytes, offset), second: u2(bytes, offset + 2)};
        offset += 4;
        break;
      case 15:
        cp[index] = {tag, kind: u1(bytes, offset), index: u2(bytes, offset + 1)};
        offset += 3;
        break;
      default:
        fail(`${label} has unsupported constant-pool tag ${tag}`);
    }
  }

  const utf8 = index => cp[index]?.tag === 1 ? cp[index].value : undefined;
  const className = index => {
    const entry = cp[index];
    return entry?.tag === 7 ? utf8(entry.index) : undefined;
  };
  const nameAndType = index => {
    const entry = cp[index];
    return entry?.tag === 12
      ? {name: utf8(entry.first), descriptor: utf8(entry.second)}
      : undefined;
  };
  const methodRef = index => {
    const entry = cp[index];
    if (!entry || ![10, 11].includes(entry.tag)) return undefined;
    const member = nameAndType(entry.second);
    return member ? {index, owner: className(entry.first), ...member} : undefined;
  };

  // access_flags, this_class, super_class, interfaces
  const thisClassIndex = u2(bytes, offset + 2);
  offset += 6;
  const interfaceCount = u2(bytes, offset);
  offset += 2 + interfaceCount * 2;

  function skipAttributes(count) {
    for (let index = 0; index < count; index++) {
      offset += 2;
      const length = u4(bytes, offset);
      offset += 4 + length;
      assert.ok(offset <= bytes.length, `${label} has a truncated attribute`);
    }
  }

  const fieldCount = u2(bytes, offset);
  offset += 2;
  for (let index = 0; index < fieldCount; index++) {
    offset += 6;
    const attributes = u2(bytes, offset);
    offset += 2;
    skipAttributes(attributes);
  }

  const methods = [];
  const methodCount = u2(bytes, offset);
  offset += 2;
  for (let index = 0; index < methodCount; index++) {
    const access = u2(bytes, offset);
    const name = utf8(u2(bytes, offset + 2));
    const descriptor = utf8(u2(bytes, offset + 4));
    const attributes = u2(bytes, offset + 6);
    offset += 8;
    let code;
    for (let attribute = 0; attribute < attributes; attribute++) {
      const attributeName = utf8(u2(bytes, offset));
      const length = u4(bytes, offset + 2);
      offset += 6;
      if (attributeName !== "Code") {
        offset += length;
        continue;
      }
      const codeStart = offset;
      const maxStack = u2(bytes, offset);
      const maxLocals = u2(bytes, offset + 2);
      const codeLength = u4(bytes, offset + 4);
      const codeStartBytes = offset + 8;
      code = {
        maxStack,
        maxLocals,
        bytes: bytes.subarray(codeStartBytes, codeStartBytes + codeLength),
      };
      // exception_table_length plus nested Code attributes
      let nested = codeStartBytes + codeLength;
      const exceptionCount = u2(bytes, nested);
      nested += 2 + exceptionCount * 8;
      const nestedCount = u2(bytes, nested);
      nested += 2;
      for (let nestedIndex = 0; nestedIndex < nestedCount; nestedIndex++) {
        const nestedLength = u4(bytes, nested + 2);
        nested += 6 + nestedLength;
      }
      assert.equal(nested - codeStart, length, `${label} Code attribute length mismatch`);
      offset = codeStart + length;
    }
    methods.push({access, name, descriptor, code});
  }

  return {
    thisClass: className(thisClassIndex),
    methods,
    methodRefs: cp.map((_, index) => methodRef(index)).filter(Boolean),
  };
}

function findClassFile(directory, internalName) {
  const candidates = [
    join(directory, `${internalName}.class`),
    join(directory, `${internalName.replace("com/mojang/authlib/", "")}.class`),
  ];
  return candidates.find(existsSync);
}

function inspectClass(directory, internalName, canonicalDescriptor) {
  const path = findClassFile(directory, internalName);
  assert.ok(path, `${internalName}.class not found under ${directory}`);
  const parsed = parseClass(readFileSync(path), relative(root, path));
  assert.equal(parsed.thisClass, internalName, `${path} has unexpected this_class`);
  const constructor = parsed.methods.find(method =>
    method.name === "<init>" && method.descriptor === "()V");
  assert.ok(constructor, `${path} has no no-argument constructor`);
  assert.ok((constructor.access & 0x0001) !== 0, `${path} no-argument constructor is not public`);
  const canonical = parsed.methodRefs.some(ref =>
    ref.owner === internalName && ref.name === "<init>" && ref.descriptor === canonicalDescriptor);
  assert.ok(canonical, `${path} has no canonical constructor reference ${canonicalDescriptor}`);
  const invokespecial = [];
  const code = constructor.code?.bytes ?? Buffer.alloc(0);
  for (let index = 0; index + 2 < code.length; index++) {
    if (code[index] !== 0xb7) continue;
    const constantPoolIndex = code.readUInt16BE(index + 1);
    const ref = parsed.methodRefs.find(candidate => candidate.index === constantPoolIndex
      && candidate.owner === internalName
      && candidate.name === "<init>" && candidate.descriptor === canonicalDescriptor);
    if (ref) invokespecial.push(ref.descriptor);
  }
  assert.ok(invokespecial.length > 0,
    `${path} no-argument constructor does not contain invokespecial canonical call`);
  return {
    path,
    noArgsPublic: true,
    canonicalDescriptor,
    invokespecialCanonical: true,
    codeBytes: code.length,
  };
}

function inspectKeyInfoClass(directory, flavour = FLAVOURS["9"]) {
  const keyInfoInternal = flavour.keyInfo;
  const path = findClassFile(directory, keyInfoInternal);
  assert.ok(path, `${keyInfoInternal}.class not found under ${directory}`);
  const parsed = parseClass(readFileSync(path), relative(root, path));
  assert.equal(parsed.thisClass, keyInfoInternal, `${path} has unexpected this_class`);
  const parseMethod = parsed.methods.find(method =>
    method.name === "parse" && method.descriptor ===
      `([B)L${flavour.keyInfoInterface};`);
  assert.ok(parseMethod, `${path} has no services-key parse method`);
  const browserParse = parsed.methodRefs.some(ref =>
    ref.owner === "dev/gaius/browser/BrowserCrypto"
      && ref.name === "parseRsaPublicKey"
      && ref.descriptor === "([B)Ljava/security/PublicKey;");
  assert.ok(browserParse, `${path} parse does not call BrowserCrypto.parseRsaPublicKey`);
  const canonical = parsed.methodRefs.some(ref =>
    ref.owner === keyInfoInternal && ref.name === "<init>"
      && ref.descriptor === "(Ljava/security/PublicKey;)V");
  assert.ok(canonical, `${path} parse has no PublicKey canonical constructor reference`);
  const code = parseMethod.code?.bytes ?? Buffer.alloc(0);
  let browserInvoke = false;
  let canonicalInvoke = false;
  for (let index = 0; index + 2 < code.length; index++) {
    if (![0xb7, 0xb8].includes(code[index])) continue;
    const constantPoolIndex = code.readUInt16BE(index + 1);
    const ref = parsed.methodRefs.find(candidate => candidate.index === constantPoolIndex);
    if (!ref) continue;
    if (code[index] === 0xb8 && ref.owner === "dev/gaius/browser/BrowserCrypto"
        && ref.name === "parseRsaPublicKey"
        && ref.descriptor === "([B)Ljava/security/PublicKey;") {
      browserInvoke = true;
    }
    if (code[index] === 0xb7 && ref.owner === keyInfoInternal
        && ref.name === "<init>"
        && ref.descriptor === "(Ljava/security/PublicKey;)V") {
      canonicalInvoke = true;
    }
  }
  assert.ok(browserInvoke, `${path} parse bytecode lacks BrowserCrypto invokestatic`);
  assert.ok(canonicalInvoke, `${path} parse bytecode lacks canonical invokespecial`);
  return {
    path,
    browserCryptoParse: true,
    canonicalConstructor: true,
    codeBytes: code.length,
  };
}

function detectFlavour(directory) {
  const nine = Boolean(findClassFile(directory, FLAVOURS["9"].session));
  const ten = Boolean(findClassFile(directory, FLAVOURS["10"].session));
  assert.ok(nine !== ten, `${directory} must hold exactly one authlib session service (9=${nine}, 10=${ten})`);
  return ten ? "10" : "9";
}

function inspectSessionClass(directory, flavour) {
  const path = findClassFile(directory, flavour.session);
  assert.ok(path, `${flavour.session}.class not found under ${directory}`);
  const parsed = parseClass(readFileSync(path), relative(root, path));
  const helper = parsed.methods.find(method =>
    method.name === "gaiusAllowedTextureUrl" && method.descriptor === flavour.helperDescriptor);
  assert.ok(helper, `${path} has no gaiusAllowedTextureUrl${flavour.helperDescriptor}`);
  assert.ok((helper.access & 0x0008) !== 0, `${path} gaiusAllowedTextureUrl is not static`);
  const decode = parsed.methodRefs.some(ref => ref.owner === "dev/gaius/browser/BrowserAuthlibGson"
    && ref.name === "decodeTextures" && ref.descriptor === `(Ljava/lang/String;)L${flavour.payload};`);
  assert.ok(decode, `${path} does not decode textures through BrowserAuthlibGson returning ${flavour.payload}`);
  const helperCall = parsed.methodRefs.some(ref => ref.owner === flavour.session
    && ref.name === "gaiusAllowedTextureUrl" && ref.descriptor === flavour.helperDescriptor);
  assert.ok(helperCall, `${path} unpackTextures does not call gaiusAllowedTextureUrl`);
  return {path, helperDescriptor: flavour.helperDescriptor, decodeTextures: flavour.payload};
}

function inspectClassEvidence(directory, major) {
  const flavour = FLAVOURS[major];
  const evidence = {
    classDir: directory,
    authlib: major,
    keyInfo: inspectKeyInfoClass(directory, flavour),
    keySetResponse: inspectClass(directory, flavour.keySet, "(Ljava/util/List;Ljava/util/List;)V"),
    keyData: inspectClass(directory, flavour.keyData, "(Ljava/nio/ByteBuffer;)V"),
    textures: inspectClass(directory, flavour.payload,
      "(JLjava/util/UUID;Ljava/lang/String;ZLjava/util/Map;)V"),
    session: inspectSessionClass(directory, flavour),
    discoveryRecords: flavour.discoveryRecords.map(([record, canonical]) =>
      inspectClass(directory, record, canonical)),
  };
  // A 26.2 output must not contain authlib 10 names and vice versa.
  const other = FLAVOURS[major === "9" ? "10" : "9"];
  assert.equal(findClassFile(directory, other.session), undefined,
    `${directory} mixes authlib 9 and 10 output`);
  return evidence;
}

// ---- --run <profile>: patch the profile's vanilla authlib jar and exercise it in a JVM ----

const JVM_DRIVER = String.raw`
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import com.mojang.authlib.properties.Property;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public final class AuthlibBrowserSmokeDriver {
    static final String TEXTURE = "https://textures.minecraft.net/texture/0123abcd";
    static final String HTTP_TEXTURE = "http://textures.minecraft.net/texture/0123abcd";
    static final String DATA_SKIN = "data:image/png;base64,iVBORw0KGgo=";

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static String payload(String url) {
        String json = "{\"timestamp\":0,\"profileId\":\"00000000000000000000000000000001\","
                + "\"profileName\":\"Smoke\",\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}}}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    // The session service interface differs between authlib 9 and 10, so it is called reflectively
    // (the driver compiles against either jar).
    static String unpackedSkin(Object service, String url) throws Exception {
        Method unpack = service.getClass().getMethod("unpackTextures", Property.class);
        MinecraftProfileTextures textures =
                (MinecraftProfileTextures) unpack.invoke(service, new Property("textures", payload(url)));
        MinecraftProfileTexture skin = textures.skin();
        return skin == null ? null : skin.getUrl();
    }

    public static void main(String[] args) throws Exception {
        String major = args[0];
        String longData = "data:image/png;base64," + "A".repeat(16_400);
        String evilHost = "https://textures.minecraft.net.evil.example/texture/0123abcd";
        String evil = "https://evil.example/texture/0123abcd";
        // No-argument constructors for Gson in the browser (gson UnsafeAllocator overlay).
        String keyInfo = major.equals("10")
                ? "com.mojang.authlib.services.MinecraftServicesKeyInfo"
                : "com.mojang.authlib.yggdrasil.YggdrasilServicesKeyInfo";
        String payload = major.equals("10")
                ? "com.mojang.authlib.services.response.MinecraftTexturesPayload"
                : "com.mojang.authlib.yggdrasil.response.MinecraftTexturesPayload";
        java.util.List<String> records = new java.util.ArrayList<>(List.of(
                keyInfo + "$KeySetResponse", keyInfo + "$KeyData", payload,
                "com.mojang.authlib.minecraft.MinecraftProfileTexture"));
        if (major.equals("10")) {
            records.addAll(List.of(
                    "com.mojang.authlib.services.response.discovery.DiscoveryResponse",
                    "com.mojang.authlib.services.response.discovery.Discovery",
                    "com.mojang.authlib.services.response.discovery.Endpoints",
                    "com.mojang.authlib.services.response.discovery.Endpoint"));
        }
        for (String record : records) {
            Constructor<?> constructor = Class.forName(record).getDeclaredConstructor();
            constructor.setAccessible(true);
            check(constructor.newInstance() != null, record + " no-argument constructor failed");
        }
        int checks = records.size();

        if (major.equals("9")) {
            Class<?> session = Class.forName("com.mojang.authlib.yggdrasil.YggdrasilMinecraftSessionService");
            Method helper = session.getDeclaredMethod("gaiusAllowedTextureUrl", String.class);
            helper.setAccessible(true);
            check((boolean) helper.invoke(null, DATA_SKIN), "authlib 9: uploaded data skin rejected");
            check((boolean) helper.invoke(null, TEXTURE), "authlib 9: textures.minecraft.net rejected");
            check(!(boolean) helper.invoke(null, evil), "authlib 9: foreign host accepted");
            check(!(boolean) helper.invoke(null, longData), "authlib 9: oversized data URL accepted");
            Object authentication = Class.forName("com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService")
                    .getMethod("createOffline", Proxy.class).invoke(null, Proxy.NO_PROXY);
            Object service = authentication.getClass().getMethod("createMinecraftSessionService")
                    .invoke(authentication);
            check(service.getClass() == session, "session service is " + service.getClass());
            check(TEXTURE.equals(unpackedSkin(service, TEXTURE)), "authlib 9: unpackTextures dropped a Mojang skin");
            check(DATA_SKIN.equals(unpackedSkin(service, DATA_SKIN)), "authlib 9: unpackTextures dropped an uploaded skin");
            check(unpackedSkin(service, evil) == null, "authlib 9: unpackTextures kept a foreign skin URL");
            checks += 8;
            System.out.println("AUTHLIB_JVM_OK major=9 checks=" + checks);
            return;
        }

        Class<?> discoveryType = Class.forName("com.mojang.authlib.services.MinecraftServicesDiscoveryService");
        Class<?> session = Class.forName("com.mojang.authlib.services.MinecraftServicesSessionService");
        Method helper = session.getDeclaredMethod("gaiusAllowedTextureUrl", discoveryType, String.class);
        helper.setAccessible(true);

        // Offline discovery (createOffline): isAllowedTextureDomain throws
        // MinecraftClientException, so the helper falls back to the static prefixes.
        Object offline = discoveryType.getMethod("createOffline", Proxy.class).invoke(null, Proxy.NO_PROXY);
        check((boolean) helper.invoke(null, offline, DATA_SKIN), "offline: uploaded data skin rejected");
        check((boolean) helper.invoke(null, offline, TEXTURE), "offline: https textures fallback rejected");
        check((boolean) helper.invoke(null, offline, HTTP_TEXTURE), "offline: http textures fallback rejected");
        check(!(boolean) helper.invoke(null, offline, evilHost), "offline: look-alike host accepted");
        check(!(boolean) helper.invoke(null, offline, evil), "offline: foreign host accepted");
        check(!(boolean) helper.invoke(null, offline,
                "https://textures.minecraft.net/other/0123abcd"), "offline: non-texture path accepted");
        check(!(boolean) helper.invoke(null, offline, longData), "offline: oversized data URL accepted");
        checks += 7;

        // Live discovery document that only lists the https texture URI: its answer wins,
        // there is no fallback when discovery works.
        Class<?> endpointType = Class.forName("com.mojang.authlib.services.response.discovery.Endpoint");
        Class<?> endpointsType = Class.forName("com.mojang.authlib.services.response.discovery.Endpoints");
        Class<?> discoveryDocType = Class.forName("com.mojang.authlib.services.response.discovery.Discovery");
        Class<?> responseType = Class.forName("com.mojang.authlib.services.response.discovery.DiscoveryResponse");
        Object endpoint = endpointType.getConstructor(String.class, List.class)
                .newInstance(null, List.of("https://textures.minecraft.net/texture/{textureId}"));
        Object profiles = endpointsType.getConstructor(Map.class).newInstance(Map.of("getTexture", endpoint));
        Object empty = endpointsType.getMethod("empty").invoke(null);
        Object document = discoveryDocType.getConstructor(String.class, endpointsType, endpointsType,
                endpointsType, endpointsType, endpointsType)
                .newInstance("minecraft", empty, empty, empty, profiles, empty);
        Object response = responseType.getConstructor(String.class, String.class, discoveryDocType)
                .newInstance("prod", "minecraft", document);
        Constructor<?> discoveryConstructor = discoveryType.getDeclaredConstructor(
                Proxy.class, boolean.class, Supplier.class);
        discoveryConstructor.setAccessible(true);
        Supplier<Object> supplier = () -> response;
        Object online = discoveryConstructor.newInstance(Proxy.NO_PROXY, false, supplier);
        check((boolean) helper.invoke(null, online, TEXTURE), "online: listed https texture rejected");
        check(!(boolean) helper.invoke(null, online, HTTP_TEXTURE), "online: unlisted http texture accepted");
        check((boolean) helper.invoke(null, online, DATA_SKIN), "online: uploaded data skin rejected");
        check(!(boolean) helper.invoke(null, online, evil), "online: foreign host accepted");
        checks += 4;

        // unpackTextures end to end through the patched session service (BrowserAuthlibGson
        // decode, then the helper) with offline discovery.
        Object service = discoveryType.getMethod("createMinecraftSessionService").invoke(offline);
        check(service.getClass() == session, "session service is " + service.getClass());
        check(TEXTURE.equals(unpackedSkin(service, TEXTURE)), "unpackTextures dropped a Mojang skin offline");
        check(DATA_SKIN.equals(unpackedSkin(service, DATA_SKIN)), "unpackTextures dropped an uploaded skin");
        check(unpackedSkin(service, evil) == null, "unpackTextures kept a foreign skin URL");
        checks += 4;
        System.out.println("AUTHLIB_JVM_OK major=10 checks=" + checks);
    }
}
`;

function jdkTool(name) {
  const nativePath = value => process.platform === "win32" && /^\/[A-Za-z](?:\/|$)/.test(value)
    ? `${value[1].toUpperCase()}:${value.slice(2)}` : value;
  const homes = [process.env.GAIUS_JAVA_HOME, process.env.JAVA_HOME].filter(Boolean).map(nativePath);
  for (const home of [...new Set(homes)]) {
    const candidate = join(home, "bin", name);
    if (existsSync(candidate) || existsSync(`${candidate}.exe`)) return candidate;
  }
  return name;
}

function findJars(directory, pattern) {
  const found = [];
  const walk = current => {
    for (const entry of readdirSync(current, {withFileTypes: true})) {
      const path = join(current, entry.name);
      if (entry.isDirectory()) walk(path);
      else if (pattern.test(entry.name)) found.push(path);
    }
  };
  if (existsSync(directory)) walk(directory);
  return found.sort();
}

function oneJar(libraries, pattern, label) {
  const jars = findJars(libraries, pattern);
  assert.equal(jars.length, 1, `${label}: expected one jar under ${libraries}, found ${jars.join(", ") || "none"}`);
  return jars[0];
}

function runProfile(profile, work) {
  const libraries = join(root, "port/work", profile, "libraries");
  const authlibJar = oneJar(join(libraries, "com/mojang/authlib"), /^authlib-.*\.jar$/, `${profile} authlib`);
  const major = /^authlib-10\./.test(authlibJar.split(/[\\/]/).pop()) ? "10" : "9";
  const dependencies = [
    oneJar(join(libraries, "com/google/code/gson"), /^gson-.*\.jar$/, "gson"),
    oneJar(join(libraries, "com/google/guava/guava"), /^guava-.*\.jar$/, "guava"),
    ...findJars(join(libraries, "com/google/guava/failureaccess"), /\.jar$/),
    oneJar(join(libraries, "org/slf4j/slf4j-api"), /^slf4j-api-.*\.jar$/, "slf4j"),
    // authlib 9's HttpAuthenticationService uses commons-lang3 Validate.
    ...findJars(join(libraries, "org/apache/commons/commons-lang3"), /\.jar$/),
    ...findJars(join(libraries, "commons-io"), /\.jar$/),
    ...findJars(join(libraries, "commons-codec"), /\.jar$/),
  ];
  const asmRoot = join(homedir(), ".m2/repository/org/ow2/asm");
  const asm = [join(asmRoot, "asm/9.8/asm-9.8.jar"), join(asmRoot, "asm-tree/9.8/asm-tree-9.8.jar")];
  const javac = jdkTool("javac");
  const java = jdkTool("java");
  const profileWork = join(work, profile);
  const tools = join(profileWork, "tools");
  const patched = join(profileWork, "patched");
  const runtime = join(profileWork, "runtime");
  for (const directory of [tools, patched, runtime]) mkdirSync(directory, {recursive: true});
  execFileSync(javac, ["-J-Duser.language=en", "--release", "21", "-proc:none",
    "-classpath", asm.join(delimiter), "-d", tools, patcherPath], {encoding: "utf8", timeout: 120_000});
  execFileSync(java, ["-Duser.language=en", "-classpath", [tools, ...asm].join(delimiter),
    "dev.gaius.tools.AuthlibBrowserPatcher", authlibJar,
    join(patched, "com/mojang/authlib/minecraft/client/MinecraftClient.class")],
  {encoding: "utf8", timeout: 120_000});
  const evidence = inspectClassEvidence(patched, major);

  // BrowserAuthlibGson as the profile compiles it: its port/mc/shared source sets over
  // port/src/main, with the source set relocations applied (authlib 10 moved the payloads).
  const gson = sourceFile(profile, "src", "dev/gaius/browser/BrowserAuthlibGson.java", {root});
  assert.ok(gson, `${profile} compiles no dev/gaius/browser/BrowserAuthlibGson.java`);
  const gsonSource = join(profileWork, "gson-src", "dev/gaius/browser/BrowserAuthlibGson.java");
  mkdirSync(join(profileWork, "gson-src", "dev/gaius/browser"), {recursive: true});
  writeFileSync(gsonSource, gson.text);
  const driverSource = join(profileWork, "AuthlibBrowserSmokeDriver.java");
  writeFileSync(driverSource, JVM_DRIVER);
  const classpath = [patched, authlibJar, ...dependencies].join(delimiter);
  execFileSync(javac, ["-J-Duser.language=en", "--release", "21", "-proc:none", "-nowarn",
    "-classpath", classpath, "-d", runtime, gsonSource, driverSource],
  {encoding: "utf8", timeout: 120_000});
  // Patched classes come first so they shadow the vanilla entries of the authlib jar.
  const run = spawnSync(java, ["-Duser.language=en", "-Xverify:all",
    "-classpath", [runtime, classpath].join(delimiter), "AuthlibBrowserSmokeDriver", major],
  {encoding: "utf8", timeout: 120_000});
  assert.equal(run.status, 0,
    `${profile} JVM driver failed (authlib ${major}):\n${run.stdout}\n${run.stderr}`);
  const ok = /AUTHLIB_JVM_OK major=(\d+) checks=(\d+)/.exec(run.stdout);
  assert.ok(ok && ok[1] === major, `${profile} JVM driver printed no result:\n${run.stdout}`);
  return {profile, authlibJar, major, classEvidence: evidence, jvmChecks: Number(ok[2])};
}

const result = {
  schema: "gaius.authlib-keyset-constructor-smoke.v2",
  ok: true,
  static: {
    patcher: patcherPath,
    overlays: overlaysPath,
    keyInfoEntry,
    keySetEntry,
    keyDataEntry,
    jarEntriesQuoted: true,
  },
  classEvidence: null,
  runs: [],
};

if (classDir) {
  result.classEvidence = inspectClassEvidence(classDir, classDirAuthlib || detectFlavour(classDir));
}

if (runProfiles.length > 0) {
  const work = mkdtempSync(join(tmpdir(), "gaius-authlib-smoke-"));
  try {
    for (const profile of runProfiles) {
      const run = runProfile(profile, work);
      result.runs.push(run);
      console.log(`authlib ${run.major} (${profile}): classes inspected, ${run.jvmChecks} JVM checks passed`);
    }
  } finally {
    rmSync(work, {recursive: true, force: true});
  }
}

const modes = ["static", classDir ? "class" : null, runProfiles.length ? `run ${runProfiles.join(",")}` : null]
  .filter(Boolean).join("+");
console.log(`Authlib keyset constructor smoke passed (${modes})`);
if (printJson) console.log(JSON.stringify(result, null, 2));
