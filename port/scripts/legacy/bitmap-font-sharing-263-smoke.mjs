#!/usr/bin/env node
// Minecraft 26.3 bitmap font image sharing smoke (work package P8).
//
// 26.3 bitmap providers read their sheet with NativeImage.read(InputStream) (26.2 used
// read(Format, InputStream), which 26.3 removed). MinecraftClientPatcher's
// patchBitmapFontImageSharing redirects that read to the three-argument
// BrowserFontBitmapCache.read (the single source in port/src/main, which decodes through
// VersionOps), shares the ImageDataHolder, makes BitmapProvider.close idempotent and lets only the last owner close the pixels. This smoke
// runs the patched 26.3 overlay jar and the compiled 26.3 Gaius runtime classes on the JVM (the
// patched bytecode must pass the HotSpot verifier; NativeImage decodes through LWJGL/STB) and
// checks the lifecycle with a real PNG:
//   - two loads of one definition in one resource-manager generation share one holder/image;
//   - the first close (twice) keeps the image, the last owner's close frees it;
//   - a new resource manager is a new generation with its own image;
//   - a failed decode leaves no cache entry behind.
//
//   node port/scripts/legacy/bitmap-font-sharing-263-smoke.mjs [--jar <patched 26.3 jar>]
//        [--runtime-classes <port/target/26.3/maven/classes>]
//
// Prerequisites: the 26.3 build-overlays.sh, generate-pom.sh and a javac-only Maven compile
// (mvnw -o -f port/target/26.3/generated-pom.xml compile).
import assert from "node:assert/strict";
import {execFileSync, spawnSync} from "node:child_process";
import {existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync} from "node:fs";
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
const runtimeClasses = fromRoot(option("--runtime-classes", "port/target/26.3/maven/classes"));
const work263 = resolve(root, "port/work/26.3");

function jdkTool(name) {
  for (const home of [process.env.GAIUS_JAVA_HOME, process.env.JAVA_HOME].filter(Boolean).map(nativePath)) {
    const candidate = join(home, "bin", name);
    if (existsSync(candidate) || existsSync(`${candidate}.exe`)) return candidate;
  }
  return name;
}

function libraries() {
  const text = readFileSync(join(work263, "classpath.txt"), "utf8").trim();
  return text.split(/[:;](?=\/|[A-Za-z]:)/).filter(Boolean).map((entry) => {
    const marker = entry.replace(/\\/g, "/").indexOf("/port/work/26.3/");
    const rebased = marker >= 0 ? join(work263, entry.slice(marker + "/port/work/26.3/".length))
      : nativePath(entry);
    if (!existsSync(rebased)) throw new Error(`missing 26.3 library ${rebased}`);
    return rebased;
  });
}

const DRIVER = String.raw`
import com.mojang.blaze3d.font.GlyphProvider;
import com.mojang.blaze3d.platform.NativeImage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.font.providers.BitmapProvider;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

public final class BitmapFontSharingDriver {
    static final class Manager implements ResourceManager {
        final Map<Identifier, byte[]> files;
        int opens;
        Manager(Map<Identifier, byte[]> files) { this.files = files; }
        @Override public Optional<Resource> getResource(Identifier id) {
            byte[] bytes = files.get(id);
            return bytes == null ? Optional.empty() : Optional.of(new Resource(null, () -> {
                opens++;
                return new ByteArrayInputStream(bytes);
            }));
        }
        @Override public Set<String> getNamespaces() { return Set.of("minecraft"); }
        @Override public List<Resource> getResourceStack(Identifier id) { return getResource(id).stream().toList(); }
        @Override public Map<Identifier, Resource> listResources(String path, ResourceManager.Selector filter) { return Map.of(); }
        @Override public Map<Identifier, List<Resource>> listResourceStacks(String path, ResourceManager.Selector filter) { return Map.of(); }
        @Override public Stream<PackResources> listPacks() { return Stream.empty(); }
    }

    public static void main(String[] args) throws Throwable {
        SharedConstants.tryDetectVersion();
        BufferedImage sheet = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                if ((x % 8) < 3 + (x / 8) + 2 * (y / 8)) sheet.setRGB(x, y, 0xFFFFFFFF);
            }
        }
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(sheet, "png", encoded);
        byte[] png = encoded.toByteArray();

        Identifier file = Identifier.withDefaultNamespace("font/gaius_smoke.png");
        Identifier texture = file.withPrefix("textures/");
        BitmapProvider.Definition definition = new BitmapProvider.Definition(
                file, 8, 7, new int[][] {{'A', 'B'}, {'C', 'D'}});
        Method load = BitmapProvider.Definition.class.getDeclaredMethod("load", ResourceManager.class);
        load.setAccessible(true);

        Manager first = new Manager(Map.of(texture, png));
        GlyphProvider a = (GlyphProvider) load.invoke(definition, first);
        GlyphProvider b = (GlyphProvider) load.invoke(definition, first);
        Object holderA = get(a, "imageData");
        NativeImage image = (NativeImage) get(holderA, "image");
        System.out.println("SHARED holder=" + (holderA == get(b, "imageData"))
                + " image=" + (image == get(get(b, "imageData"), "image"))
                + " glyphs=" + a.getSupportedGlyphs().size()
                + " glyphA=" + (a.getGlyph('A') != null) + " opens=" + first.opens);
        a.close();
        System.out.println("CLOSE_FIRST imageClosed=" + image.isClosed());
        a.close();
        System.out.println("CLOSE_FIRST_AGAIN imageClosed=" + image.isClosed());
        b.close();
        System.out.println("CLOSE_LAST imageClosed=" + image.isClosed());

        Manager second = new Manager(Map.of(texture, png));
        GlyphProvider c = (GlyphProvider) load.invoke(definition, second);
        NativeImage imageC = (NativeImage) get(get(c, "imageData"), "image");
        System.out.println("GENERATION newImage=" + (imageC != image) + " open=" + !imageC.isClosed());
        c.close();
        System.out.println("CLOSE_GENERATION imageClosed=" + imageC.isClosed());

        Manager broken = new Manager(Map.of(texture, new byte[] {1, 2, 3, 4}));
        try {
            load.invoke(definition, broken);
            System.out.println("BROKEN loaded");
        } catch (InvocationTargetException failure) {
            System.out.println("BROKEN " + failure.getCause().getClass().getSimpleName());
        }
        Class<?> cache = Class.forName("dev.gaius.browser.BrowserFontBitmapCache");
        System.out.println("CACHE images=" + ((Map<?, ?>) getStatic(cache, "IMAGES")).size()
                + " generations=" + ((Map<?, ?>) getStatic(cache, "GENERATIONS")).size());
        boolean threeArgument = false;
        for (Method method : cache.getDeclaredMethods()) {
            threeArgument |= method.getName().equals("read") && method.getParameterCount() == 3;
        }
        System.out.println("READ_3ARG " + threeArgument);
    }

    static Object get(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    static Object getStatic(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }
}
`;

for (const [label, path] of [["patched jar", patchedJar], ["runtime classes", runtimeClasses]]) {
  if (!existsSync(path)) {
    console.error(`bitmap-font-sharing-263-smoke: missing ${label} ${path}`);
    process.exit(2);
  }
}
const work = mkdtempSync(join(tmpdir(), "gaius-263-bitmap-font-"));
try {
  const separator = process.platform === "win32" ? ";" : ":";
  const classpath = [patchedJar, runtimeClasses, ...libraries()].join(separator);
  const classes = join(work, "classes");
  mkdirSync(classes, {recursive: true});
  writeFileSync(join(work, "BitmapFontSharingDriver.java"), DRIVER);
  execFileSync(jdkTool("javac"), ["--release", "25", "-proc:none", "-nowarn", "-cp", classpath,
    "-d", classes, join(work, "BitmapFontSharingDriver.java")], {stdio: "inherit"});
  const result = spawnSync(jdkTool("java"), ["-Djava.awt.headless=true",
    "-Dorg.lwjgl.system.SharedLibraryExtractPath=" + join(work, "natives"),
    "-cp", [classes, classpath].join(separator), "BitmapFontSharingDriver"],
    {encoding: "utf8", timeout: 180_000, maxBuffer: 64 << 20});
  if (result.status !== 0) {
    throw new Error(`driver failed (${result.status}):\n${result.stdout}\n${result.stderr}`);
  }
  const lines = result.stdout.split(/\r?\n/).filter((line) => /^[A-Z_0-9]+ /.test(line));
  lines.forEach((line) => console.log(line));
  assert.deepEqual(lines, [
    "SHARED holder=true image=true glyphs=4 glyphA=true opens=2",
    "CLOSE_FIRST imageClosed=false",
    "CLOSE_FIRST_AGAIN imageClosed=false",
    "CLOSE_LAST imageClosed=true",
    "GENERATION newImage=true open=true",
    "CLOSE_GENERATION imageClosed=true",
    "BROKEN IOException",
    "CACHE images=0 generations=0",
    "READ_3ARG true",
  ]);
  console.log("bitmap-font-sharing-263-smoke: OK");
} finally {
  rmSync(work, {recursive: true, force: true});
}
