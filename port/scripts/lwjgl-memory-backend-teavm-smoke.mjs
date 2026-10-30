import {CANONICAL_PROFILE, RENDERPEARL_PROFILE} from "./verification-profiles.mjs";
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {existsSync} from 'node:fs';
import {mkdir, mkdtemp, readFile, readdir, rm, writeFile} from 'node:fs/promises';
import {homedir, tmpdir} from 'node:os';
import {delimiter, join, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';

// LWJGL 3.4.3 memory backend smoke (work package P1, risk R3).
//
// Compiles a small program against the patched LWJGL core and lwjgl-sdl jars
// of a build-overlays.sh output, then runs it twice:
//   1. on the JVM with multi-release lookup disabled (the base entries TeaVM
//      reads), as a quick cross-check;
//   2. compiled by TeaVM 0.15 (the same patched classlib and teavm-core the
//      client build uses, class path resolved like the Maven plugin: base jar
//      entries, first match wins) and executed in Node.
// Both runs must pass the same checks: BrowserMemoryBackend installed as
// MemoryUtil.BACKEND, Platform reports Linux, MemoryStack frames (UTF8 on the
// stack allocator, ASCII, UTF16, nested push/pop restoring the stack
// pointer), memASCII/memUTF8/memUTF16 round trips over exactly sized heap
// allocations, PointerBuffer, SDL_Event-sized struct access, and
// SDL_DisplayMode.create(address).
//
// usage: node port/scripts/lwjgl-memory-backend-teavm-smoke.mjs
//          [--profile 26.3] [--overlay-dir DIR] [--optimization SIMPLE|ADVANCED|FULL]
//          [--jvm-only | --skip-jvm] [--keep DIR]
// Requires build-overlays.sh output for the profile.  The TeaVM step needs
// about 2 GB of heap; run it under the machine's TeaVM serialisation lock.

const root = fileURLToPath(new URL('../../', import.meta.url));
const args = process.argv.slice(2);
const option = (name, fallback) => {
  const index = args.indexOf(name);
  return index >= 0 ? args[index + 1] : fallback;
};
const profile = option('--profile', RENDERPEARL_PROFILE.id);
const overlayDirectory = resolve(option('--overlay-dir',
  process.env.GAIUS_OVERLAY_DIRECTORY || join(root, 'port', 'work', 'overlays', profile)));
const optimization = option('--optimization', 'SIMPLE');
const jvmOnly = args.includes('--jvm-only');
const skipJvm = args.includes('--skip-jvm');
assert.ok(!(jvmOnly && skipJvm), '--jvm-only and --skip-jvm exclude each other');
const keep = option('--keep', null);
assert.match(optimization, /^(SIMPLE|ADVANCED|FULL)$/, 'invalid --optimization');

const suffix = process.platform === 'win32' ? '.exe' : '';
const javaHome = process.env.GAIUS_JAVA_HOME || process.env.JAVA_HOME;
const tool = name => (javaHome ? join(javaHome, 'bin', `${name}${suffix}`) : name);
const repository = process.env.M2_REPO || join(homedir(), '.m2', 'repository');
const config = JSON.parse(await readFile(join(root, 'port', 'config.json'), 'utf8'));
const teavmVersion = config.teaVMVersion;

const metadataPath = join(root, 'port', 'work', profile, 'version.json');
const metadata = JSON.parse(await readFile(metadataPath, 'utf8'));
// Same selection as gaius_library_path: the plain coordinate first, then the
// fallback classifier (26.2 ships only org.lwjgl:lwjgl:3.4.1:unsafe).
const libraryPath = (coordinate, fallbackClassifier = null) => {
  const candidates = metadata.libraries.filter(library =>
    library.name.startsWith(`${coordinate}:`) && library.downloads?.artifact?.path);
  const match = candidates.find(library => library.name.split(':').length === 3)
    ?? candidates.find(library => fallbackClassifier && library.name.split(':')[3] === fallbackClassifier);
  assert.ok(match, `${coordinate} is not a library of Minecraft ${profile}`);
  const jar = join(overlayDirectory, 'libraries', match.downloads.artifact.path);
  assert.ok(existsSync(jar), `missing ${jar}; run build-overlays.sh for ${profile} first`);
  return jar;
};
const lwjglJar = libraryPath('org.lwjgl:lwjgl', 'unsafe');
const listing = execFileSync(tool('jar'), ['--list', '--file', lwjglJar], {encoding: 'utf8', maxBuffer: 64 << 20});
assert.ok(listing.includes('org/lwjgl/system/BrowserMemoryBackend.class'),
  `${lwjglJar} has no BrowserMemoryBackend: this smoke covers the LWJGL 3.4.2+ MemoryBackend shape`
  + ' (' + RENDERPEARL_PROFILE.id + '); LWJGL 3.4.1 (' + CANONICAL_PROFILE.id + ') is covered by browser-memory-lifecycle-smoke.sh');
const sdlJar = libraryPath('org.lwjgl:lwjgl-sdl');
const jomlJar = libraryPath('org.joml:joml');

const teavmJars = [];
for (const artifact of await readdir(join(repository, 'org', 'teavm'))) {
  const directory = join(repository, 'org', 'teavm', artifact, teavmVersion);
  if (!existsSync(directory)) continue;
  for (const name of await readdir(directory)) {
    if (name.endsWith('.jar') && !name.includes('-sources') && !name.includes('-javadoc')) {
      teavmJars.push(join(directory, name));
    }
  }
}
assert.ok(teavmJars.some(name => name.includes('teavm-tooling')), `TeaVM ${teavmVersion} tooling must be installed`);
const patchedClasslib = join(overlayDirectory, `teavm-classlib-${teavmVersion}-gaius.jar`);
const patchedCore = join(overlayDirectory, `teavm-core-${teavmVersion}-gaius.jar`);
for (const jar of [patchedClasslib, patchedCore]) {
  assert.ok(existsSync(jar), `missing ${jar}; run build-overlays.sh for ${profile} first`);
}
const teavmArtifact = name => {
  const jar = teavmJars.find(candidate => candidate.endsWith(`${name}-${teavmVersion}.jar`));
  assert.ok(jar, `missing TeaVM artifact ${name}`);
  return jar;
};
const jzlib = join(repository, 'com', 'jcraft', 'jzlib', '1.1.3', 'jzlib-1.1.3.jar');

const smokeSource = String.raw`
package dev.gaius.smoke;

import static org.lwjgl.system.MemoryUtil.*;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.sdl.SDL_DisplayMode;
import org.lwjgl.sdl.SDL_Event;
import org.lwjgl.sdl.SDL_KeyboardEvent;
import org.lwjgl.sdl.SDL_Rect;
import org.lwjgl.system.BrowserMemory;
import org.lwjgl.system.BrowserMemoryBackend;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Platform;

public final class LwjglMemoryBackendSmoke {
    private static final String UTF8_TEXT = "SDL3 h\u00e9llo \u2713 \u65e5\u672c \ud83d\ude80";
    private static final String UTF16_TEXT = "SDL3 \u2713 \u65e5\u672c";
    private static final int SDL_EVENT_KEY_DOWN = 0x300;
    private static int checks;

    private LwjglMemoryBackendSmoke() {
    }

    public static void main(String[] args) {
        try {
            platformAndBackend();
            memoryStack();
            heapStrings();
            buffersAndArrays();
            pointerBuffers();
            sdlStructs();
            System.out.println("LWJGL_MEMORY_BACKEND_SMOKE_OK checks=" + checks);
        } catch (Throwable failure) {
            System.out.println("LWJGL_MEMORY_BACKEND_SMOKE_FAIL " + failure);
            throw failure instanceof RuntimeException runtime ? runtime : new RuntimeException(failure);
        }
    }

    private static void platformAndBackend() {
        check(Platform.get() == Platform.LINUX, "Platform.get() is " + Platform.get() + ", not LINUX");
        check(Platform.getJavaVersion() >= 8, "Platform.getJavaVersion() = " + Platform.getJavaVersion());
        check(memBackend() == BrowserMemoryBackend.INSTANCE,
                "MemoryUtil.BACKEND is " + memBackend() + ", not BrowserMemoryBackend");
        check(PAGE_SIZE == 65536, "PAGE_SIZE = " + PAGE_SIZE);
        check(CACHE_LINE_SIZE == 64, "CACHE_LINE_SIZE = " + CACHE_LINE_SIZE);
        byte[] word = {1, 2, 3, 4, 5, 6, 7, 8, 9};
        check(memBackend().getLong(word, 1) == 0x0908070605040302L,
                "MemoryBackend.getLong(byte[], long) is not little-endian: "
                        + Long.toHexString(memBackend().getLong(word, 1)));
    }

    private static void memoryStack() {
        MemoryStack stack = MemoryStack.stackGet();
        long top = stack.getPointerAddress();
        int frame = stack.getFrameIndex();
        long base = stack.getAddress();
        long end = base + stack.getSize();
        stack.push();
        try {
            ByteBuffer utf8 = stack.UTF8(UTF8_TEXT, true);
            long utf8Address = memAddress(utf8);
            check(utf8Address >= base && utf8Address + utf8.remaining() <= end,
                    "MemoryStack.UTF8(String) is not stack memory: allocateUTF8 ignored the stack allocator");
            check(stack.getPointerAddress() == utf8Address && utf8Address < top,
                    "MemoryStack.UTF8(String) did not move the stack pointer down to the string");
            check(utf8.remaining() == memLengthUTF8(UTF8_TEXT, true), "UTF8 length " + utf8.remaining());
            check(memGetByte(utf8Address + utf8.remaining() - 1) == 0, "UTF8 is not NUL-terminated");
            check(UTF8_TEXT.equals(memUTF8(utf8Address)), "memUTF8(stack UTF8) = " + memUTF8(utf8Address));
            // memUTF8(ByteBuffer) decodes all remaining bytes, the terminator included.
            check((UTF8_TEXT + "\0").equals(memUTF8(utf8)), "memUTF8(ByteBuffer) = " + memUTF8(utf8));
            check(UTF8_TEXT.equals(memUTF8(utf8, utf8.remaining() - 1)), "memUTF8(ByteBuffer, int)");

            ByteBuffer sequence = stack.UTF8(new StringBuilder(UTF8_TEXT), true);
            check(UTF8_TEXT.equals(memUTF8(memAddress(sequence))), "MemoryStack.UTF8(CharSequence) round trip");
            check(sequence.remaining() == utf8.remaining(), "CharSequence and String UTF-8 lengths differ");

            ByteBuffer ascii = stack.ASCII("gaius-${RENDERPEARL_PROFILE.id}", true);
            check("gaius-${RENDERPEARL_PROFILE.id}".equals(memASCII(memAddress(ascii))), "memASCII(stack ASCII)");
            ByteBuffer utf16 = stack.UTF16(UTF16_TEXT, true);
            check(utf16.remaining() == (UTF16_TEXT.length() + 1) * 2, "UTF16 length " + utf16.remaining());
            check(UTF16_TEXT.equals(memUTF16(memAddress(utf16))), "memUTF16(stack UTF16)");
            int encoded = stack.nUTF8("abc", false);
            check(encoded == 3 && memUTF8(stack.getPointerAddress(), 3).equals("abc"), "MemoryStack.nUTF8");

            IntBuffer ints = stack.mallocInt(4);
            ints.put(0, 7).put(3, -9);
            check(memGetInt(memAddress(ints)) == 7 && memGetInt(memAddress(ints) + 12) == -9,
                    "stack IntBuffer does not alias its address");
            check(stack.callocInt(8).get(7) == 0, "MemoryStack.callocInt did not zero");

            long inner = stack.getPointerAddress();
            try (MemoryStack nested = stack.push()) {
                ByteBuffer block = nested.malloc(4096);
                check(memAddress(block) < inner, "nested frame did not allocate below the outer frame");
                check(nested.UTF8("nested", true).remaining() == 7, "nested UTF8");
            }
            check(stack.getPointerAddress() == inner, "nested pop did not restore the stack pointer");
            SDL_Rect rect = SDL_Rect.malloc(stack).set(1, 2, 3, 4);
            check(rect.x() == 1 && rect.y() == 2 && rect.w() == 3 && rect.h() == 4, "SDL_Rect on the stack");
        } finally {
            stack.pop();
        }
        check(stack.getPointerAddress() == top, "pop did not restore the stack pointer: "
                + Long.toHexString(stack.getPointerAddress()) + " != " + Long.toHexString(top));
        check(stack.getFrameIndex() == frame, "pop did not restore the frame index");
    }

    private static void heapStrings() {
        int regions = BrowserMemory.liveRegions();
        long bytes = BrowserMemory.liveBytes();
        String[] samples = {"", "a", "abcdefg", "abcdefgh", "abcdefghi", "0123456789abcdefghij", UTF8_TEXT};
        for (String sample : samples) {
            // memUTF8(CharSequence) allocates through MemoryUtil$LazyInit.ALLOCATOR, and the
            // length-less memUTF8(long) scans the exactly sized region for the terminator.
            ByteBuffer utf8 = memUTF8(sample);
            check(utf8.remaining() == memLengthUTF8(sample, true), "memUTF8 length for \"" + sample + "\"");
            check(sample.equals(memUTF8(memAddress(utf8))), "memUTF8 round trip for \"" + sample + "\"");
            check(memLengthNT1(utf8) == utf8.remaining() - 1, "memLengthNT1 for \"" + sample + "\"");
            memFree(utf8);
            if (sample.chars().allMatch(c -> c < 0x80)) {
                ByteBuffer ascii = memASCII(sample);
                check(sample.equals(memASCII(memAddress(ascii))), "memASCII round trip for \"" + sample + "\"");
                memFree(ascii);
            }
            ByteBuffer utf16 = memUTF16(sample);
            check(sample.equals(memUTF16(memAddress(utf16))), "memUTF16 round trip for \"" + sample + "\"");
            memFree(utf16);
        }
        check(BrowserMemory.liveRegions() == regions && BrowserMemory.liveBytes() == bytes,
                "string allocations leaked: regions " + regions + " -> " + BrowserMemory.liveRegions());
    }

    private static void buffersAndArrays() {
        int regions = BrowserMemory.liveRegions();
        ByteBuffer heap = memCalloc(128);
        check(BrowserMemory.liveRegions() == regions + 1, "memCalloc did not allocate one region");
        long address = memAddress(heap);
        IntBuffer view = memIntBuffer(address + 16, 4);
        view.put(2, 0x12345678);
        check(memGetInt(address + 24) == 0x12345678, "memIntBuffer does not alias memory");
        check(memAddress(view) == address + 16, "memIntBuffer address");
        FloatBuffer floats = memFloatBuffer(address, 4);
        memPutFloat(address + 4, 1.5f);
        check(floats.get(1) == 1.5f, "memFloatBuffer read");
        check(memAddress(memSlice(heap, 8, 16)) == address + 8, "memSlice address");
        check(memAddress(memDuplicate(heap)) == address, "memDuplicate address");

        int[] ints = {1, -2, 3, 0x7fffffff};
        memCopy(ints, address + 32);
        int[] intsBack = new int[4];
        memCopy(address + 32, intsBack);
        check(Arrays.equals(ints, intsBack) && memGetInt(address + 36) == -2, "int[] memCopy round trip");
        byte[] raw = {9, 8, 7, 6, 5};
        memCopy(raw, address + 64, 1, 3);
        byte[] rawBack = new byte[5];
        memCopy(address + 64, rawBack, 2, 3);
        check(Arrays.equals(rawBack, new byte[] {0, 0, 8, 7, 6}), "byte[] memCopy round trip " + Arrays.toString(rawBack));
        double[] doubles = {Math.PI, -0.25};
        memCopy(doubles, address + 80);
        double[] doublesBack = new double[2];
        memCopy(address + 80, doublesBack);
        check(Arrays.equals(doubles, doublesBack), "double[] memCopy round trip");
        char[] chars = "ok\u2713".toCharArray();
        memCopy(chars, address + 96);
        char[] charsBack = new char[3];
        memCopy(address + 96, charsBack);
        check(Arrays.equals(chars, charsBack), "char[] memCopy round trip");
        try {
            memCopy(new long[4], address + 120);
            throw new AssertionError("memCopy past the region end was accepted");
        } catch (IndexOutOfBoundsException expected) {
            checks++;
        }

        memCopy(address + 32, address + 100, 16);
        check(memGetInt(address + 104) == -2, "memCopy(long, long, long)");
        memSet(address + 32, 0xab, 8);
        check(memGetLong(address + 32) == 0xababababababababL, "memSet");

        ByteBuffer grown = memRealloc(heap, 4096);
        check(memGetInt(memAddress(grown) + 24) == 0x12345678, "memRealloc lost contents");
        memFree(grown);
        check(BrowserMemory.liveRegions() == regions, "memFree after memRealloc leaked a region");
        try {
            memGetInt(0x7ffe_0000_0000L);
            throw new AssertionError("read of an unallocated address succeeded");
        } catch (IllegalStateException expected) {
            checks++;
        }
    }

    private static void pointerBuffers() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer first = stack.UTF8("first", true);
            ByteBuffer second = stack.ASCII("second", true);
            PointerBuffer pointers = stack.mallocPointer(3);
            pointers.put(0, memAddress(first)).put(1, memAddress(second)).put(2, 0L);
            check(pointers.get(0) == memAddress(first), "PointerBuffer.get");
            check(memGetAddress(pointers.address(1)) == memAddress(second), "PointerBuffer element address");
            check("first".equals(pointers.getStringUTF8(0)), "PointerBuffer.getStringUTF8");
            check("second".equals(pointers.getStringASCII(1)), "PointerBuffer.getStringASCII");
            check(pointers.get(2) == 0L && pointers.capacity() == 3, "PointerBuffer NULL element");
        }
        PointerBuffer heap = BufferUtils.createPointerBuffer(2);
        heap.put(0, 42L).put(1, -1L);
        check(heap.get(0) == 42L && heap.get(1) == -1L, "BufferUtils PointerBuffer");
        check(memGetAddress(heap.address(1)) == -1L, "BufferUtils PointerBuffer memory");
        ByteBuffer direct = BufferUtils.createByteBuffer(32);
        direct.putInt(0, 99);
        check(memGetInt(memAddress(direct)) == 99, "BufferUtils ByteBuffer address");
    }

    private static void sdlStructs() {
        check(SDL_Event.SIZEOF == 128, "SDL_Event.SIZEOF = " + SDL_Event.SIZEOF);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            SDL_Event event = SDL_Event.calloc(stack);
            long address = event.address();
            memPutInt(address + SDL_Event.TYPE, SDL_EVENT_KEY_DOWN);
            memPutLong(address + SDL_KeyboardEvent.TIMESTAMP, 123_456_789_012_345L);
            memPutInt(address + SDL_KeyboardEvent.WINDOWID, 7);
            memPutInt(address + SDL_KeyboardEvent.SCANCODE, 4);
            memPutInt(address + SDL_KeyboardEvent.KEY, 'a');
            memPutByte(address + SDL_KeyboardEvent.DOWN, (byte) 1);
            memPutInt(address + SDL_Event.SIZEOF - 4, 0x5a5a5a5a);
            check(event.type() == SDL_EVENT_KEY_DOWN, "SDL_Event.type()");
            SDL_KeyboardEvent key = event.key();
            check(key.timestamp() == 123_456_789_012_345L, "SDL_KeyboardEvent.timestamp()");
            check(key.windowID() == 7 && key.scancode() == 4 && key.key() == 'a' && key.down(),
                    "SDL_KeyboardEvent fields");
            check(memGetInt(address + SDL_Event.SIZEOF - 4) == 0x5a5a5a5a, "last int of SDL_Event");
            SDL_Event copy = SDL_Event.malloc(stack);
            memCopy(address, copy.address(), SDL_Event.SIZEOF);
            check(copy.key().key() == 'a' && copy.type() == SDL_EVENT_KEY_DOWN, "SDL_Event memCopy");
            memSet(copy.address(), 0, SDL_Event.SIZEOF);
            check(copy.type() == 0 && memGetInt(copy.address() + SDL_Event.SIZEOF - 4) == 0, "SDL_Event memSet");
        }

        long mode = nmemCalloc(1, SDL_DisplayMode.SIZEOF);
        memPutInt(mode + SDL_DisplayMode.DISPLAYID, 3);
        memPutInt(mode + SDL_DisplayMode.FORMAT, 0x16161804);
        memPutInt(mode + SDL_DisplayMode.W, 1920);
        memPutInt(mode + SDL_DisplayMode.H, 1080);
        memPutFloat(mode + SDL_DisplayMode.PIXEL_DENSITY, 2.0f);
        memPutFloat(mode + SDL_DisplayMode.REFRESH_RATE, 59.94f);
        memPutInt(mode + SDL_DisplayMode.REFRESH_RATE_NUMERATOR, 60000);
        memPutInt(mode + SDL_DisplayMode.REFRESH_RATE_DENOMINATOR, 1001);
        SDL_DisplayMode displayMode = SDL_DisplayMode.create(mode);
        check(displayMode.address() == mode, "SDL_DisplayMode.create(address) address");
        check(displayMode.displayID() == 3 && displayMode.format() == 0x16161804, "SDL_DisplayMode id/format");
        check(displayMode.w() == 1920 && displayMode.h() == 1080, "SDL_DisplayMode size");
        check(displayMode.pixel_density() == 2.0f && displayMode.refresh_rate() == 59.94f,
                "SDL_DisplayMode density/refresh rate");
        check(displayMode.refresh_rate_numerator() == 60000 && displayMode.refresh_rate_denominator() == 1001,
                "SDL_DisplayMode refresh rational");
        displayMode.w(2560);
        check(memGetInt(mode + SDL_DisplayMode.W) == 2560, "SDL_DisplayMode setter");
        nmemFree(mode);
        SDL_DisplayMode heapMode = SDL_DisplayMode.calloc();
        check(heapMode.w() == 0 && heapMode.refresh_rate() == 0f, "SDL_DisplayMode.calloc()");
        heapMode.free();
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
        checks++;
    }
}
`;

const compileSource = String.raw`
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.teavm.backend.javascript.JSModuleType;
import org.teavm.tooling.TeaVMTool;
import org.teavm.vm.TeaVMOptimizationLevel;

public class CompileLwjglMemorySmoke {
    public static void main(String[] args) throws Exception {
        TeaVMTool tool = new TeaVMTool();
        tool.setMainClass("dev.gaius.smoke.LwjglMemoryBackendSmoke");
        tool.setTargetDirectory(new File(args[0]));
        tool.setTargetFileName("smoke.cjs");
        tool.setJsModuleType(JSModuleType.COMMON_JS);
        tool.setObfuscated(false);
        tool.setOptimizationLevel(TeaVMOptimizationLevel.valueOf(args[1]));
        tool.setClassLoader(ClassLoader.getSystemClassLoader());
        List<File> classPath = new ArrayList<>();
        for (int index = 2; index < args.length; index++) {
            classPath.add(new File(args[index]));
        }
        // Like the Maven plugin: base jar entries (no multi-release lookup), first match wins.
        tool.setClassPath(classPath);
        tool.generate();
        for (var problem : tool.getProblemProvider().getSevereProblems()) {
            System.err.println(problem.getText() + " " + java.util.Arrays.toString(problem.getParams())
                    + " at " + problem.getLocation());
        }
        if (!tool.getProblemProvider().getSevereProblems().isEmpty()) {
            throw new AssertionError("TeaVM compilation failed");
        }
    }
}
`;

const expectLine = (output, label) => {
  const failure = output.match(/LWJGL_MEMORY_BACKEND_SMOKE_FAIL .*/);
  assert.ok(!failure, `${label}: ${failure?.[0]}`);
  const match = output.match(/LWJGL_MEMORY_BACKEND_SMOKE_OK checks=(\d+)/);
  assert.ok(match, `${label} did not report success:\n${output}`);
  return Number(match[1]);
};

const temp = keep ? resolve(keep) : await mkdtemp(join(tmpdir(), 'gaius-lwjgl-memory-'));
try {
  await mkdir(join(temp, 'src', 'dev', 'gaius', 'smoke'), {recursive: true});
  await mkdir(join(temp, 'classes'), {recursive: true});
  const smokeFile = join(temp, 'src', 'dev', 'gaius', 'smoke', 'LwjglMemoryBackendSmoke.java');
  const compileFile = join(temp, 'src', 'CompileLwjglMemorySmoke.java');
  await writeFile(smokeFile, smokeSource);
  await writeFile(compileFile, compileSource);
  const classes = join(temp, 'classes');
  const libraries = [lwjglJar, sdlJar, jomlJar];
  execFileSync(tool('javac'), [
    '--release', '21', '-proc:none', '-encoding', 'UTF-8', '-d', classes,
    '-cp', [...libraries, teavmArtifact('teavm-jso'), teavmArtifact('teavm-interop')].join(delimiter),
    smokeFile,
  ], {stdio: 'pipe'});

  // The patched jars are written for TeaVM, which does not verify bytecode:
  // several library patchers (for example LwjglAPIUtilBrowserPatcher) emit
  // branches without stack map frames, so the JVM run skips verification.
  let jvmChecks = null;
  if (!skipJvm) {
    const jvmOutput = execFileSync(tool('java'), [
      '-Djdk.util.jar.enableMultiRelease=false', '-XX:+UnlockDiagnosticVMOptions',
      '-XX:-BytecodeVerificationRemote', '-Xmx256m',
      '-cp', [classes, ...libraries].join(delimiter),
      'dev.gaius.smoke.LwjglMemoryBackendSmoke',
    ], {encoding: 'utf8', timeout: 120000});
    jvmChecks = expectLine(jvmOutput, 'JVM run');
    console.log(`JVM (base entries): ${jvmChecks} checks passed`);
  }
  if (jvmOnly) process.exit(0);

  const driverClasses = join(temp, 'driver');
  await mkdir(driverClasses, {recursive: true});
  const driverClassPath = [patchedCore, patchedClasslib, ...teavmJars, jzlib];
  execFileSync(tool('javac'), [
    '--release', '21', '-proc:none', '-d', driverClasses,
    '-cp', driverClassPath.join(delimiter), compileFile,
  ], {stdio: 'pipe'});
  const teavmClassPath = [
    classes, ...libraries, patchedClasslib,
    ...teavmJars.filter(jar => !jar.includes('teavm-classlib-') && !jar.includes('teavm-core-')),
    patchedCore, jzlib,
  ];
  const output = join(temp, 'out');
  const started = Date.now();
  execFileSync(tool('java'), [
    '-Xmx2g', '-cp', [driverClasses, ...driverClassPath].join(delimiter),
    'CompileLwjglMemorySmoke', output, optimization, ...teavmClassPath,
  ], {stdio: ['ignore', 'pipe', 'inherit'], timeout: 600000, maxBuffer: 64 << 20});
  console.log(`TeaVM ${teavmVersion} (${optimization}) compiled the smoke in ${((Date.now() - started) / 1000).toFixed(1)} s`);

  const runner = join(output, 'run.cjs');
  await writeFile(runner, `require('./smoke.cjs').main([], function(error) {
    if (error) { console.error('TEAVM_MAIN_ERROR', error && (error.stack || error)); process.exitCode = 1; }
  });\n`);
  const nodeOutput = execFileSync(process.execPath, [runner], {encoding: 'utf8', timeout: 60000});
  const teavmChecks = expectLine(nodeOutput, 'TeaVM run in Node');
  if (jvmChecks !== null) {
    assert.equal(teavmChecks, jvmChecks, 'TeaVM and the JVM ran a different number of checks');
  }
  console.log(`TeaVM in Node ${process.version}: ${teavmChecks} checks passed`);
  console.log(`lwjgl memory backend smoke passed (${profile}, ${teavmChecks} checks)`);
} finally {
  if (!keep) await rm(temp, {recursive: true, force: true});
}
