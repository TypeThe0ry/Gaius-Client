#!/usr/bin/env node
// Minecraft 26.3 browser vanilla pack smoke (work package P8, risk R21).
//
// 26.3 builds the vanilla pack as one "full" FixedPathPackResources plus one per layer (the
// client pushes a jar layer and an asset-index layer; the server one). Gaius overrides
// FixedPathPackResources with the browser-archive implementation, which ignores the builder's
// paths, so without UiPatches263.patchVanillaPackResourcesSingleLayer every layer would expose
// the whole archive again. This smoke runs the patched 26.3 overlay jar on the JVM (no TeaVM,
// no browser) and checks, through the vanilla code paths:
//   - ClientPackSource and ServerPacksSource build a VanillaPackResources whose only layer is
//     its full resources, and that object is the Gaius override (package-private ctor called
//     by the vanilla FixedPathPackResources$Builder, no IllegalAccessError on HotSpot);
//   - the vanilla Pack opens exactly one PackResources, and a MultiPackResourceManager over it
//     returns every listed resource once (resource stacks of size 1), with lazy suppliers only;
//   - the built-in pack metadata still comes from the builder.
// A small browser resource list (dev/gaius/browser/minecraft-resources.txt) is put on the class
// path, as build-teavm.sh does for the real build.
//
//   node port/scripts/legacy/vanilla-pack-layers-263-smoke.mjs [--jar <patched 26.3 jar>]
//        [--before-builder-jar <jar>]
//
// --before-builder-jar replaces VanillaPackResourcesBuilder.class in a copy of the patched jar
// with the one from <jar> (for example a jar built without UiPatches263) and reports the same
// measurements for it, to show what the single-layer patch changes; it does not fail the smoke.
// Prerequisite: GAIUS_BRINGUP=1 GAIUS_VERSION_PROFILE_PATH=versions/26.3.json build-overlays.sh.
import assert from "node:assert/strict";
import {execFileSync, spawnSync} from "node:child_process";
import {copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {isAbsolute, join, resolve} from "node:path";
import {fileURLToPath} from "node:url";

const root = resolve(fileURLToPath(new URL("../../..", import.meta.url)));
const nativePath = (value) => {
  if (!value) return value;
  const text = String(value);
  return process.platform === "win32" && /^\/[A-Za-z](?:\/|$)/.test(text)
    ? `${text[1].toUpperCase()}:${text.slice(2)}` : text;
};
const fromRoot = (value) => (isAbsolute(nativePath(value)) ? nativePath(value) : resolve(root, value));

function option(name, fallback) {
  const index = process.argv.indexOf(name);
  return index >= 0 && index + 1 < process.argv.length ? process.argv[index + 1] : fallback;
}

const patchedJar = fromRoot(option("--jar", "port/work/overlays/26.3/client-named-26.3-gaius.jar"));
const beforeBuilderJar = option("--before-builder-jar", null);
const work263 = resolve(root, "port/work/26.3");

function jdkTool(name) {
  for (const home of [process.env.GAIUS_JAVA_HOME, process.env.JAVA_HOME].filter(Boolean).map(nativePath)) {
    const candidate = join(home, "bin", name);
    if (existsSync(candidate) || existsSync(`${candidate}.exe`)) return candidate;
  }
  return name;
}

function libraries() {
  // classpath.txt holds absolute /d/... paths of the machine that fetched the profile; rebase
  // every entry onto this checkout's port/work/26.3.
  const text = readFileSync(join(work263, "classpath.txt"), "utf8").trim();
  const entries = text.split(/[:;](?=\/|[A-Za-z]:)/).filter(Boolean);
  return entries.map((entry) => {
    const marker = entry.replace(/\\/g, "/").indexOf("/port/work/26.3/");
    const rebased = marker >= 0 ? join(work263, entry.slice(marker + "/port/work/26.3/".length))
      : nativePath(entry);
    if (!existsSync(rebased)) throw new Error(`missing 26.3 library ${rebased}`);
    return rebased;
  }).filter((path) => !/client(-named|-original)?\.jar$/.test(path));
}

const RESOURCES = [
  "assets/minecraft/lang/de_de.json",
  "assets/minecraft/lang/en_us.json",
  "assets/minecraft/sounds.json",
  "assets/minecraft/textures/misc/unknown_pack.png",
  "data/minecraft/recipe/stick.json",
];

const DRIVER = String.raw`
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.client.resources.ClientPackSource;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.VanillaPackResources;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.validation.DirectoryValidator;

public final class VanillaPackLayersDriver {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Path assets = Files.createTempDirectory("gaius-263-assets");
        ClientPackSource client = new ClientPackSource(assets, new DirectoryValidator(path -> false));
        report("client", client.getVanillaPack());
        report("server", ServerPacksSource.createVanillaPackSource());

        List<Pack> packs = new ArrayList<>();
        client.loadPacks(packs::add);
        Pack vanilla = packs.stream().filter(pack -> pack.getId().equals("vanilla")).findFirst()
                .orElseThrow(() -> new IllegalStateException("no vanilla pack in " + packs));
        List<PackResources> opened = vanilla.open().toList();
        System.out.println("PACK_OPENED " + opened.size() + " distinct=" + distinct(opened));
        try (MultiPackResourceManager manager = new MultiPackResourceManager(PackType.CLIENT_RESOURCES, opened)) {
            Map<Identifier, List<Resource>> lang = manager.listResourceStacks("lang", id -> id.getPath().endsWith(".json"));
            lang.forEach((id, stack) -> System.out.println("STACK " + id + " " + stack.size()));
            System.out.println("SOUNDS_STACK " + manager.getResourceStack(Identifier.withDefaultNamespace("sounds.json")).size());
            System.out.println("LANG_FILES " + lang.size());
        }
        Object section = vanilla.openMetadata().getMetadataSection(PackMetadataSection.CLIENT_TYPE);
        System.out.println("METADATA " + (section != null));
    }

    private static void report(String side, VanillaPackResources pack) throws Exception {
        Field field = VanillaPackResources.class.getDeclaredField("resourceLayers");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<PackResources> layers = (List<PackResources>) field.get(pack);
        PackResources full = pack.fullResources();
        List<PackResources> all = new ArrayList<>(layers);
        all.add(full);
        boolean override = false;
        for (Field declared : full.getClass().getDeclaredFields()) {
            override |= declared.getName().equals("resourceSet");
        }
        System.out.println("LAYERS " + side + " count=" + layers.size()
                + " sameAsFull=" + layers.stream().allMatch(layer -> layer == full)
                + " instances=" + distinct(all) + " class=" + full.getClass().getName()
                + " override=" + override);
    }

    private static int distinct(List<PackResources> packs) {
        Map<PackResources, Boolean> seen = new IdentityHashMap<>();
        packs.forEach(pack -> seen.put(pack, true));
        return seen.size();
    }
}
`;

// 26.3's StrictMath263 rewrite makes Mth (reached from Util.<clinit>) call
// dev.gaius.browser.BrowserStrictMath, which lives in port/src. The driver compiles its JVM
// twin, as section-task-queue-smoke.mjs does: the Math.fround JSBody natives become float
// casts, which round the same way on the JVM.
function writeStrictMathTwin(work) {
  const source = readFileSync(join(root, "port/src/main/java/dev/gaius/browser/BrowserStrictMath.java"), "utf8");
  const twin = source
    .replace(/^import org\.teavm\.jso\.JSBody;\r?\n/m, "")
    .replace(/@JSBody\(.*\)\r?\n\s*public static native float (\w+)\((\w+) value\);/g,
      "public static float $1($2 value) {\n        return (float) value;\n    }");
  assert.ok(!/\bnative\b/.test(twin.replace(/\/\*[\s\S]*?\*\/|\/\/.*$/gm, "")),
    "BrowserStrictMath gained a native the JVM twin does not cover");
  const file = join(work, "strict-math/dev/gaius/browser/BrowserStrictMath.java");
  mkdirSync(join(work, "strict-math/dev/gaius/browser"), {recursive: true});
  writeFileSync(file, twin);
  return file;
}

// The pack override reads the browser asset archive (dev.gaius.browser.AssetArchive), whose
// natives only exist in the browser. The driver holds no archive, so a stub that reports every
// resource as absent (the classpath fallback) shadows the class of the patched jar.
function writeAssetArchiveStub(work) {
  const file = join(work, "asset-archive/dev/gaius/browser/AssetArchive.java");
  mkdirSync(join(work, "asset-archive/dev/gaius/browser"), {recursive: true});
  writeFileSync(file, [
    "package dev.gaius.browser;",
    "public final class AssetArchive {",
    "    public byte[] read(String resource) { return null; }",
    "    public static int externalResourceLength(String resource) { return -1; }",
    "    public static boolean copyExternalResource(String resource, byte[] output) { return false; }",
    "}",
    "",
  ].join("\n"));
  return file;
}

function runDriver(label, jar, work) {
  const classes = join(work, `classes-${label}`);
  const resources = join(work, `resources-${label}`);
  mkdirSync(classes, {recursive: true});
  mkdirSync(join(resources, "dev/gaius/browser"), {recursive: true});
  writeFileSync(join(resources, "dev/gaius/browser/minecraft-resources.txt"), RESOURCES.join("\n") + "\n");
  const separator = process.platform === "win32" ? ";" : ":";
  const classpath = [jar, ...libraries()].join(separator);
  execFileSync(jdkTool("javac"), ["--release", "25", "-proc:none", "-nowarn", "-cp", classpath,
    "-d", classes, join(work, "VanillaPackLayersDriver.java"), writeStrictMathTwin(work), writeAssetArchiveStub(work)],
    {stdio: "inherit"});
  const result = spawnSync(jdkTool("java"), ["-cp", [classes, resources, classpath].join(separator),
    "VanillaPackLayersDriver"], {encoding: "utf8", timeout: 180_000, maxBuffer: 64 << 20});
  if (result.status !== 0) {
    throw new Error(`${label} driver failed (${result.status}):\n${result.stdout}\n${result.stderr}`);
  }
  return result.stdout.split(/\r?\n/).filter((line) => /^[A-Z_]+ /.test(line));
}

if (!existsSync(patchedJar)) {
  console.error(`vanilla-pack-layers-263-smoke: missing ${patchedJar}; run the 26.3 build-overlays.sh first`);
  process.exit(2);
}
const work = mkdtempSync(join(tmpdir(), "gaius-263-pack-layers-"));
try {
  writeFileSync(join(work, "VanillaPackLayersDriver.java"), DRIVER);
  const lines = runDriver("patched", patchedJar, work);
  lines.forEach((line) => console.log(`patched: ${line}`));
  const find = (prefix) => lines.filter((line) => line.startsWith(prefix));
  for (const side of ["client", "server"]) {
    const [layers] = find(`LAYERS ${side} `);
    assert.ok(layers, `no LAYERS line for ${side}`);
    assert.match(layers, / count=1 sameAsFull=true instances=1 class=net\.minecraft\.server\.packs\.FixedPathPackResources override=true$/,
      `${side} vanilla pack must expose the Gaius FixedPathPackResources once: ${layers}`);
  }
  assert.deepEqual(find("PACK_OPENED "), ["PACK_OPENED 1 distinct=1"]);
  assert.deepEqual(find("LANG_FILES "), ["LANG_FILES 2"]);
  assert.deepEqual(find("STACK ").sort(), ["STACK minecraft:lang/de_de.json 1", "STACK minecraft:lang/en_us.json 1"]);
  assert.deepEqual(find("SOUNDS_STACK "), ["SOUNDS_STACK 1"]);
  assert.deepEqual(find("METADATA "), ["METADATA true"]);

  if (beforeBuilderJar) {
    const before = join(work, "before.jar");
    copyFileSync(patchedJar, before);
    const entryRoot = join(work, "before-entry");
    mkdirSync(entryRoot, {recursive: true});
    execFileSync(jdkTool("jar"), ["--extract", "--file", fromRoot(beforeBuilderJar),
      "net/minecraft/server/packs/VanillaPackResourcesBuilder.class"], {cwd: entryRoot});
    execFileSync(jdkTool("jar"), ["--update", "--file", before, "-C", entryRoot, "."]);
    runDriver("before", before, work).forEach((line) => console.log(`before-single-layer: ${line}`));
  }
  console.log("vanilla-pack-layers-263-smoke: OK");
} finally {
  rmSync(work, {recursive: true, force: true});
}
