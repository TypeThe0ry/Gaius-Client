#!/usr/bin/env node
import {CANONICAL_PROFILE, RENDERPEARL_PROFILE} from "./verification-profiles.mjs";
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {existsSync} from 'node:fs';
import {mkdir, mkdtemp, readFile, readdir, rm, writeFile} from 'node:fs/promises';
import {homedir, tmpdir} from 'node:os';
import {delimiter, join, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';

// Smoke for the 26.3 TeaVM class library gap helpers (milestone M2, lead package P0):
// dev.gaius.browser.BrowserJdkCompat (Math.powExact(II), ByteBuffer.slice(II),
// Reader.readAllAsString(), Duration.isPositive()) and dev.gaius.browser.BrowserScopedValue
// (java.lang.ScopedValue), both in port/mc/shared/renderpearl-1, which JdkCompatPatches263 wires
// into the 26.3 client jar.
//
// One scenario source is generated twice: against the real JDK 25 APIs and against the
// helpers. Each prints a transcript (results, exception kinds and messages, buffer state).
// Stages (--stages, default jvm,teavm,overlay):
//   jvm      both programs on the JVM; the transcripts must be identical;
//   teavm    the helper program compiled by TeaVM 0.15 (the profile's patched classlib and
//            core, as in the client build) and run in Node; its transcript must equal the JDK
//            one, so the helpers keep JDK semantics on TeaVM's class library too.  About 1 GB
//            of heap; run it under the machine's TeaVM serialisation lock;
//   overlay  the built 26.3 client jar: every gap call site redirected (26.3 counts), no
//            reference to the JDK methods or to java/lang/ScopedValue left, SolidDebugger on
//            BrowserScopedValue.
//
// usage: node port/scripts/browser-jdk-compat-smoke.mjs [--stages jvm,teavm,overlay]
//          [--profile 26.3] [--overlay-dir DIR] [--optimization SIMPLE|ADVANCED|FULL]
//          [--keep DIR]
// Needs a JDK 25+ (GAIUS_JAVA_HOME or JAVA_HOME), and for teavm/overlay the build-overlays.sh
// output of the profile.

const root = fileURLToPath(new URL('../../', import.meta.url));
const args = process.argv.slice(2);
const option = (name, fallback) => {
  const index = args.indexOf(name);
  return index >= 0 ? args[index + 1] : fallback;
};
const stages = new Set(option('--stages', 'jvm,teavm,overlay').split(',').filter(Boolean));
for (const stage of stages) {
  assert.ok(['jvm', 'teavm', 'overlay'].includes(stage), `unknown stage ${stage}`);
}
const profile = option('--profile', RENDERPEARL_PROFILE.id);
const overlayDirectory = resolve(option('--overlay-dir',
  process.env.GAIUS_OVERLAY_DIRECTORY || join(root, 'port', 'work', 'overlays', profile)));
const optimization = option('--optimization', 'SIMPLE');
assert.match(optimization, /^(SIMPLE|ADVANCED|FULL)$/, 'invalid --optimization');
const keep = option('--keep', null);

const suffix = process.platform === 'win32' ? '.exe' : '';
const javaHome = process.env.GAIUS_JAVA_HOME || process.env.JAVA_HOME;
const tool = name => (javaHome ? join(javaHome, 'bin', `${name}${suffix}`) : name);
const repository = process.env.M2_REPO || join(homedir(), '.m2', 'repository');
const config = JSON.parse(await readFile(join(root, 'port', 'config.json'), 'utf8'));
const teavmVersion = config.teaVMVersion;
const helperSources = ['BrowserJdkCompat', 'BrowserScopedValue'].map(name =>
  join(root, 'port', 'mc', 'shared', 'renderpearl-1', 'src', 'java', 'dev', 'gaius', 'browser', `${name}.java`));

// ------------------------------------------------------------------ scenario

const scenario = String.raw`
package dev.gaius.smoke;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.InvalidMarkException;
import java.nio.ReadOnlyBufferException;
import java.time.Duration;
import java.util.NoSuchElementException;
@@IMPORTS@@

public final class @@CLASS@@ {
    private static final StringBuilder OUT = new StringBuilder();
    private static int lines;

    @@BINDINGS@@

    public static void main(String[] args) throws Exception {
        powExact();
        slice();
        readAllAsString();
        isPositive();
        scopedValue();
        System.out.print(OUT);
        System.out.println("JDK_COMPAT_TRANSCRIPT_END lines=" + lines);
    }

    private static void line(String text) {
        OUT.append(text).append('\n');
        lines++;
    }

    /** Exception kind and, where the JDK and the helpers define it, the message. */
    private static String failure(Throwable t) {
        if (t instanceof ArithmeticException) return "!Arithmetic:" + t.getMessage();
        if (t instanceof IndexOutOfBoundsException) return "!IndexOutOfBounds:" + t.getMessage();
        if (t instanceof ReadOnlyBufferException) return "!ReadOnlyBuffer";
        if (t instanceof InvalidMarkException) return "!InvalidMark";
        if (t instanceof NoSuchElementException) return "!NoSuchElement:" + t.getMessage();
        if (t instanceof NullPointerException) return "!NullPointer";
        if (t instanceof IllegalStateException) return "!IllegalState:" + t.getMessage();
        if (t instanceof IOException) return "!IO:" + t.getMessage();
        if (t instanceof RuntimeException) return "!Runtime:" + t.getMessage();
        return "!Other";
    }

    // ----------------------------------------------------------------- powExact

    private static String pow(int x, int n) {
        try {
            return Integer.toString(powExactOf(x, n));
        } catch (RuntimeException e) {
            return failure(e);
        }
    }

    private static void powExact() {
        for (int x = -50; x <= 50; x++) {
            StringBuilder row = new StringBuilder("pow x=" + x + ":");
            for (int n = -2; n <= 33; n++) row.append(' ').append(pow(x, n));
            line(row.toString());
        }
        int[] bases = {Integer.MIN_VALUE, Integer.MIN_VALUE + 1, Integer.MAX_VALUE,
            Integer.MAX_VALUE - 1, 46340, 46341, -46340, -46341, 1290, 1291, -1290, -1291, 65536,
            -65536, 2, -2, 3, -3, 7, -7, 10, 1, 0, -1};
        int[] exponents = {Integer.MIN_VALUE, -1, 0, 1, 2, 3, 4, 5, 15, 16, 30, 31, 32, 33, 63, 64,
            1 << 20, Integer.MAX_VALUE - 1, Integer.MAX_VALUE};
        for (int x : bases) {
            StringBuilder row = new StringBuilder("pow edge x=" + x + ":");
            for (int n : exponents) row.append(' ').append(pow(x, n));
            line(row.toString());
        }
    }

    // -------------------------------------------------------------------- slice

    private static ByteBuffer filled(ByteBuffer buffer) {
        for (int i = 0; i < buffer.capacity(); i++) buffer.put(i, (byte) (i * 7 + 1));
        return buffer;
    }

    private static String state(ByteBuffer b) {
        return "pos=" + b.position() + " lim=" + b.limit() + " cap=" + b.capacity()
            + " order=" + (b.order() == ByteOrder.BIG_ENDIAN ? "BE" : "LE")
            + " direct=" + b.isDirect() + " ro=" + b.isReadOnly();
    }

    private static String content(ByteBuffer b) {
        StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < b.limit(); i++) text.append(i == 0 ? "" : ",").append(b.get(i));
        return text.append(']').toString();
    }

    private static void sliceCase(String name, ByteBuffer source, int index, int length) {
        String before = state(source);
        String label = "slice " + name + " (" + index + "," + length + ")";
        ByteBuffer slice;
        try {
            slice = sliceOf(source, index, length);
        } catch (RuntimeException e) {
            line(label + " " + failure(e) + " source " + state(source));
            return;
        }
        StringBuilder result = new StringBuilder(label + " " + state(slice) + " " + content(slice));
        result.append(" sourceUnchanged=").append(before.equals(state(source)));
        try {
            slice.reset();
            result.append(" mark=set");
        } catch (RuntimeException e) {
            result.append(" mark").append(failure(e));
        }
        if (length > 0) {
            // Shared content, independent positions.
            try {
                slice.put(0, (byte) 99);
                result.append(" sharedWrite=").append(source.get(index));
            } catch (RuntimeException e) {
                result.append(" write").append(failure(e));
            }
            if (!source.isReadOnly()) {
                source.put(index + length - 1, (byte) -5);
                result.append(" sharedRead=").append(slice.get(length - 1));
            }
            slice.position(length);
            result.append(" afterMove source ").append(state(source));
        }
        line(result.toString());
    }

    private static void sliceSet(String name, java.util.function.Supplier<ByteBuffer> make) {
        int[][] ranges = {{0, 0}, {0, 16}, {2, 5}, {15, 1}, {16, 0}, {12, 0}, {10, 2}, {10, 3},
            {12, 1}, {11, 1}, {-1, 2}, {2, -1}, {Integer.MAX_VALUE, 1}, {1, Integer.MAX_VALUE},
            {Integer.MIN_VALUE, 0}, {0, 17}};
        for (int[] range : ranges) sliceCase(name, make.get(), range[0], range[1]);
    }

    private static void slice() {
        sliceSet("heap", () -> filled(ByteBuffer.allocate(16)));
        sliceSet("heap-le", () -> filled(ByteBuffer.allocate(16)).order(ByteOrder.LITTLE_ENDIAN));
        sliceSet("direct", () -> filled(ByteBuffer.allocateDirect(16)));
        sliceSet("direct-le", () -> filled(ByteBuffer.allocateDirect(16))
            .order(ByteOrder.LITTLE_ENDIAN));
        sliceSet("read-only", () -> filled(ByteBuffer.allocate(16)).asReadOnlyBuffer());
        sliceSet("window", () -> {
            ByteBuffer b = filled(ByteBuffer.allocate(16));
            b.position(3).limit(12);
            b.mark();
            b.position(5);
            return b;
        });
        sliceSet("offset", () -> {
            ByteBuffer parent = filled(ByteBuffer.allocate(24));
            parent.position(4);
            ByteBuffer child = parent.slice();
            child.limit(16);
            return child;
        });
        // A slice of a slice keeps pointing into the same content.
        ByteBuffer base = filled(ByteBuffer.allocate(16));
        ByteBuffer first = sliceOf(base, 4, 10);
        ByteBuffer second = sliceOf(first, 3, 4);
        second.put(1, (byte) 42);
        line("slice nested " + state(second) + " " + content(second) + " base8=" + base.get(8));
        try {
            line("slice null = " + sliceOf(null, 0, 0));
        } catch (RuntimeException e) {
            line("slice null " + failure(e));
        }
    }

    // ---------------------------------------------------------- readAllAsString

    private static String summary(String text) {
        int hash = 0;
        for (int i = 0; i < text.length(); i++) hash = hash * 31 + text.charAt(i);
        String head = text.length() <= 24 ? text : text.substring(0, 24) + "...";
        // ASCII only: the JVM prints in the platform charset, TeaVM in UTF-8.
        StringBuilder printable = new StringBuilder();
        for (int i = 0; i < head.length(); i++) {
            char c = head.charAt(i);
            if (c == '\r') printable.append("\\r");
            else if (c == '\n') printable.append("\\n");
            else if (c < 32 || c > 126) printable.append("\\u").append(Integer.toHexString(c | 0x10000).substring(1));
            else printable.append(c);
        }
        return "len=" + text.length() + " hash=" + hash + " text=" + printable;
    }

    private static void read(String name, Reader reader) {
        try {
            String first = readAllOf(reader);
            String second = readAllOf(reader);
            line("readAll " + name + " " + summary(first) + " again=" + summary(second));
        } catch (IOException | RuntimeException e) {
            line("readAll " + name + " " + failure(e));
        }
    }

    /** Hands out at most {@code chunk} characters per read, and 0 on every third call. */
    private static final class ChunkedReader extends Reader {
        private final String text;
        private final int chunk;
        private final int failAt;
        private int next;
        private int calls;

        ChunkedReader(String text, int chunk, int failAt) {
            this.text = text;
            this.chunk = chunk;
            this.failAt = failAt;
        }

        @Override
        public int read(char[] buffer, int offset, int length) throws IOException {
            if (++calls % 3 == 0) return 0;
            if (failAt >= 0 && next >= failAt) throw new IOException("failed at " + next);
            if (next >= text.length()) return -1;
            int count = Math.min(Math.min(chunk, length), text.length() - next);
            text.getChars(next, next + count, buffer, offset);
            next += count;
            return count;
        }

        @Override
        public void close() {
        }
    }

    private static void readAllAsString() throws IOException {
        StringBuilder large = new StringBuilder();
        for (int i = 0; i < 20000; i++) large.append((char) ('a' + i % 26)).append(i % 97 == 0 ? "\r\n" : "");
        read("empty", new StringReader(""));
        read("lines", new StringReader("first line\r\nsecond\nthird\rlast"));
        read("unicode", new StringReader("h\u00e9llo \u2713 \u65e5\u672c \ud83d\ude80"));
        read("large", new StringReader(large.toString()));
        StringReader partial = new StringReader("0123456789abcdef");
        char[] skip = new char[5];
        partial.read(skip);
        read("after-read", partial);
        read("chunked", new ChunkedReader(large.toString(), 7, -1));
        read("chunked-large", new ChunkedReader(large.toString(), 10000, -1));
        read("failing", new ChunkedReader("abcdefghij", 3, 6));
        read("null", null);
    }

    // --------------------------------------------------------------- isPositive

    private static void isPositive() {
        Duration[] durations = {Duration.ZERO, Duration.ofNanos(1), Duration.ofNanos(-1),
            Duration.ofSeconds(1), Duration.ofSeconds(-1), Duration.ofSeconds(-1, 1),
            Duration.ofSeconds(0, 999_999_999), Duration.ofMillis(-500), Duration.ofMillis(500),
            Duration.ofSeconds(Long.MAX_VALUE, 999_999_999), Duration.ofSeconds(Long.MIN_VALUE),
            Duration.ofDays(-3).plusNanos(5), Duration.ofSeconds(1).minusNanos(1),
            Duration.ofSeconds(1).minusNanos(1_000_000_001), null};
        StringBuilder row = new StringBuilder("isPositive:");
        for (Duration duration : durations) {
            try {
                row.append(' ').append(positiveOf(duration));
            } catch (RuntimeException e) {
                row.append(' ').append(failure(e));
            }
        }
        line(row.toString());
    }

    // -------------------------------------------------------------- ScopedValue

    private interface Step {
        Object run() throws Exception;
    }

    private static void step(String name, Step step) {
        try {
            line("sv " + name + " = " + step.run());
        } catch (Exception e) {
            line("sv " + name + " " + failure(e));
        }
    }

    private static void scopedValue() throws Exception {
        @@SV@@<String> a = @@SV@@.newInstance();
        @@SV@@<Integer> b = @@SV@@.newInstance();
        step("unbound isBound", a::isBound);
        step("unbound get", a::get);
        step("unbound orElse", () -> a.orElse("fallback"));
        step("unbound orElse(null)", () -> a.orElse(null));
        step("unbound orElseThrow", () -> a.orElseThrow(() -> new IllegalStateException("none")));
        step("unbound orElseThrow(null)", () -> a.<RuntimeException>orElseThrow(null));
        step("where(null)", () -> @@SV@@.where(null, "x"));
        @@SV@@.where(a, "outer").run(() -> {
            step("outer get", a::get);
            step("outer b bound", b::isBound);
            @@SV@@.where(a, "inner").where(b, 7).run(() -> {
                step("inner a", a::get);
                step("inner b", b::get);
                step("inner orElse", () -> a.orElse("unused"));
                step("inner orElseThrow", () -> a.orElseThrow(() -> new IllegalStateException("x")));
            });
            step("outer again", a::get);
            step("outer b after", b::isBound);
            try {
                @@SV@@.where(a, "failing").run(() -> {
                    step("failing get", a::get);
                    throw new IllegalStateException("thrown inside");
                });
            } catch (IllegalStateException e) {
                line("sv caught " + failure(e));
            }
            step("outer after throw", a::get);
            Thread thread = new Thread(() -> step("other thread isBound", a::isBound));
            thread.start();
            try {
                thread.join();
            } catch (InterruptedException e) {
                line("sv interrupted");
            }
        });
        step("after run isBound", a::isBound);
        @@SV@@.Carrier carrier = @@SV@@.where(a, "one").where(a, "two").where(b, 3);
        step("carrier get a", () -> carrier.get(a));
        step("carrier get b", () -> carrier.get(b));
        step("carrier get missing", () -> @@SV@@.where(b, 1).get(a));
        step("carrier get null", () -> carrier.get(null));
        step("carrier where null", () -> carrier.where(null, "x"));
        carrier.run(() -> step("carrier run a", a::get));
        step("call", () -> @@SV@@.where(a, "v").call(() -> a.get() + "!" + a.isBound()));
        step("call nested", () -> @@SV@@.where(b, 1).call(
            () -> @@SV@@.where(b, b.get() + 1).call(() -> b.get() * 10) + b.get()));
        step("call checked", () -> @@SV@@.where(a, "c").<String, IOException>call(() -> {
            throw new IOException("checked " + a.get());
        }));
        step("after call isBound", a::isBound);
        step("run(null)", () -> {
            @@SV@@.where(a, "n").run(null);
            return "ran";
        });
        step("call(null)", () -> @@SV@@.where(a, "n").<Object, RuntimeException>call(null));
        @@SV@@.where(a, null).run(() -> {
            step("null value isBound", a::isBound);
            step("null value get", a::get);
            step("null value orElse", () -> a.orElse("d"));
        });
        @@SV@@.where(a, "L1").run(() -> {
            try {
                @@SV@@.where(a, "L2").run(() -> {
                    @@SV@@.where(a, "L3").run(() -> {
                        throw new RuntimeException("deep");
                    });
                });
            } catch (RuntimeException e) {
                line("sv deep caught " + failure(e));
            }
            step("L1 restored", a::get);
        });
        step("end isBound", a::isBound);
    }
}
`;

const jdkBindings = String.raw`
    private static int powExactOf(int x, int n) { return Math.powExact(x, n); }
    private static ByteBuffer sliceOf(ByteBuffer b, int index, int length) { return b.slice(index, length); }
    private static String readAllOf(Reader r) throws IOException { return r.readAllAsString(); }
    private static boolean positiveOf(Duration d) { return d.isPositive(); }
`;
const compatBindings = String.raw`
    private static int powExactOf(int x, int n) { return BrowserJdkCompat.powExact(x, n); }
    private static ByteBuffer sliceOf(ByteBuffer b, int index, int length) { return BrowserJdkCompat.slice(b, index, length); }
    private static String readAllOf(Reader r) throws IOException { return BrowserJdkCompat.readAllAsString(r); }
    private static boolean positiveOf(Duration d) { return BrowserJdkCompat.isPositive(d); }
`;
const program = (className, imports, bindings, scopedValue) => scenario
  .replace('@@IMPORTS@@', imports)
  .replace('@@CLASS@@', className)
  .replace('@@BINDINGS@@', bindings)
  .replaceAll('@@SV@@', scopedValue);

const compileSource = String.raw`
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.teavm.backend.javascript.JSModuleType;
import org.teavm.tooling.TeaVMTool;
import org.teavm.vm.TeaVMOptimizationLevel;

public class CompileJdkCompatSmoke {
    public static void main(String[] args) throws Exception {
        TeaVMTool tool = new TeaVMTool();
        tool.setMainClass("dev.gaius.smoke.CompatTranscript");
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

// Scans the built client jar for the JdkCompatPatches263 result.
const overlaySource = String.raw`
import java.io.InputStream;
import java.util.*;
import java.util.zip.*;
import org.objectweb.asm.*;

public class OverlayJdkCompatCheck {
    static final String COMPAT = "dev/gaius/browser/BrowserJdkCompat";
    static final Map<String, String> GAPS = Map.of(
        "java/lang/Math.powExact(II)I", "powExact(II)I",
        "java/nio/ByteBuffer.slice(II)Ljava/nio/ByteBuffer;", "slice(Ljava/nio/ByteBuffer;II)Ljava/nio/ByteBuffer;",
        "java/io/Reader.readAllAsString()Ljava/lang/String;", "readAllAsString(Ljava/io/Reader;)Ljava/lang/String;",
        "java/time/Duration.isPositive()Z", "isPositive(Ljava/time/Duration;)Z");
    // The shared helpers Minecraft262BrowserPatcher redirects to (${CANONICAL_PROFILE.id} and ${RENDERPEARL_PROFILE.id}).
    static final Set<String> SHARED = Set.of("readAll(Ljava/io/Reader;)Ljava/lang/String;",
        "firstLine(Ljava/lang/String;)Ljava/lang/String;",
        "resolve(Ljava/nio/file/Path;Ljava/lang/String;[Ljava/lang/String;)Ljava/nio/file/Path;");

    public static void main(String[] args) throws Exception {
        Map<String, Integer> helperCalls = new TreeMap<>();
        List<String> problems = new ArrayList<>();
        Set<String> scopedUsers = new TreeSet<>();
        try (ZipFile zip = new ZipFile(args[0])) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                if (!entry.getName().endsWith(".class") || entry.getName().startsWith("META-INF/")) continue;
                byte[] bytes;
                try (InputStream in = zip.getInputStream(entry)) { bytes = in.readAllBytes(); }
                String text = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
                if (text.contains("java/lang/ScopedValue")) problems.add(entry.getName() + " mentions java/lang/ScopedValue");
                if (text.contains("dev/gaius/browser/BrowserScopedValue")) scopedUsers.add(entry.getName());
                if (!text.contains(COMPAT) && !text.contains("powExact") && !text.contains("slice")
                        && !text.contains("readAllAsString") && !text.contains("isPositive")) continue;
                String owner = entry.getName();
                new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override public void visitMethodInsn(int op, String o, String mn, String md, boolean itf) {
                                if (GAPS.containsKey(o + "." + mn + md)) problems.add(owner + " calls " + o + "." + mn + md);
                                if (o.equals(COMPAT)) {
                                    if (op != Opcodes.INVOKESTATIC || !(GAPS.containsValue(mn + md) || SHARED.contains(mn + md))) problems.add(owner + " bad helper call " + mn + md);
                                    helperCalls.merge(mn, 1, Integer::sum);
                                }
                            }
                            @Override public void visitInvokeDynamicInsn(String dn, String dd, Handle bsm, Object... bargs) {
                                for (Object x : bargs) if (x instanceof Handle h && GAPS.containsKey(h.getOwner() + "." + h.getName() + h.getDesc()))
                                    problems.add(owner + " handle " + h);
                            }
                        };
                    }
                }, ClassReader.SKIP_FRAMES);
            }
        }
        System.out.println("OVERLAY_HELPER_CALLS " + helperCalls);
        System.out.println("OVERLAY_SCOPED_VALUE_USERS " + scopedUsers);
        for (String problem : problems) System.out.println("OVERLAY_PROBLEM " + problem);
    }
}
`;

const expectTranscript = (output, label) => {
  const match = output.match(/JDK_COMPAT_TRANSCRIPT_END lines=(\d+)/);
  assert.ok(match, `${label} did not finish the transcript:\n${output.slice(-2000)}`);
  return output.slice(0, match.index).replace(/\r\n/g, '\n');
};
const firstDifference = (expected, actual) => {
  const a = expected.split('\n');
  const b = actual.split('\n');
  for (let index = 0; index < Math.max(a.length, b.length); index++) {
    if (a[index] !== b[index]) {
      return `line ${index + 1}:\n  JDK:    ${a[index]}\n  helper: ${b[index]}`;
    }
  }
  return null;
};

const temp = keep ? resolve(keep) : await mkdtemp(join(tmpdir(), 'gaius-jdk-compat-'));
try {
  const sources = join(temp, 'src', 'dev', 'gaius', 'smoke');
  await mkdir(sources, {recursive: true});
  const jdkFile = join(sources, 'JdkTranscript.java');
  const compatFile = join(sources, 'CompatTranscript.java');
  await writeFile(jdkFile, program('JdkTranscript', '', jdkBindings, 'ScopedValue'));
  await writeFile(compatFile, program('CompatTranscript',
    'import dev.gaius.browser.BrowserJdkCompat;\nimport dev.gaius.browser.BrowserScopedValue;',
    compatBindings, 'BrowserScopedValue'));
  const jdkClasses = join(temp, 'jdk-classes');
  const compatClasses = join(temp, 'compat-classes');
  await mkdir(jdkClasses, {recursive: true});
  await mkdir(compatClasses, {recursive: true});
  // The helpers compile like the 26.3 source set (release 21); the JDK program needs 25.
  execFileSync(tool('javac'), ['--release', '21', '-proc:none', '-encoding', 'UTF-8',
    '-d', compatClasses, ...helperSources, compatFile], {stdio: 'pipe'});
  execFileSync(tool('javac'), ['--release', '25', '-proc:none', '-encoding', 'UTF-8',
    '-d', jdkClasses, jdkFile], {stdio: 'pipe'});
  const run = (classes, main) => execFileSync(tool('java'), ['-Xmx256m', '-cp', classes, main],
    {encoding: 'utf8', timeout: 120000, maxBuffer: 64 << 20});
  const jdkTranscript = expectTranscript(run(jdkClasses, 'dev.gaius.smoke.JdkTranscript'), 'JDK run');
  const lineCount = jdkTranscript.split('\n').filter(Boolean).length;

  if (stages.has('jvm')) {
    // The 26.3 BrowserJdkCompat replaces the shared one in the 26.3 source set; the shared
    // helpers that Minecraft262BrowserPatcher calls (readAll, firstLine, resolve) must stay
    // identical to port/src/main/java.
    const sharedTail = text => text.slice(text.indexOf('    public static String readAll(Reader reader)'));
    const shared = await readFile(join(root, 'port', 'src', 'main', 'java', 'dev', 'gaius',
      'browser', 'BrowserJdkCompat.java'), 'utf8');
    const versioned = await readFile(helperSources[0], 'utf8');
    assert.ok(shared.includes('public static String readAll(Reader reader)'),
      'the shared BrowserJdkCompat no longer has readAll(Reader)');
    assert.equal(sharedTail(versioned).replace(/\r\n/g, '\n'), sharedTail(shared).replace(/\r\n/g, '\n'),
      'the ' + RENDERPEARL_PROFILE.id + ' BrowserJdkCompat copy of the shared helpers differs from port/src/main/java');
    const compatTranscript = expectTranscript(
      run(compatClasses, 'dev.gaius.smoke.CompatTranscript'), 'helper run on the JVM');
    const difference = firstDifference(jdkTranscript, compatTranscript);
    assert.equal(difference, null, `helpers differ from the JDK on the JVM at ${difference}`);
    console.log(`JVM: helpers match the JDK 25 methods on ${lineCount} transcript lines`);
  }

  if (stages.has('teavm')) {
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
    assert.ok(teavmJars.some(name => name.includes('teavm-tooling')),
      `TeaVM ${teavmVersion} tooling must be installed`);
    const patchedClasslib = join(overlayDirectory, `teavm-classlib-${teavmVersion}-gaius.jar`);
    const patchedCore = join(overlayDirectory, `teavm-core-${teavmVersion}-gaius.jar`);
    for (const jar of [patchedClasslib, patchedCore]) {
      assert.ok(existsSync(jar), `missing ${jar}; run build-overlays.sh for ${profile} first`);
    }
    const jzlib = join(repository, 'com', 'jcraft', 'jzlib', '1.1.3', 'jzlib-1.1.3.jar');
    const compileFile = join(temp, 'src', 'CompileJdkCompatSmoke.java');
    await writeFile(compileFile, compileSource);
    const driverClasses = join(temp, 'driver');
    await mkdir(driverClasses, {recursive: true});
    const driverClassPath = [patchedCore, patchedClasslib, ...teavmJars, jzlib];
    execFileSync(tool('javac'), ['--release', '21', '-proc:none', '-d', driverClasses,
      '-cp', driverClassPath.join(delimiter), compileFile], {stdio: 'pipe'});
    const teavmClassPath = [compatClasses, patchedClasslib,
      ...teavmJars.filter(jar => !jar.includes('teavm-classlib-') && !jar.includes('teavm-core-')),
      patchedCore, jzlib];
    const output = join(temp, 'out');
    const started = Date.now();
    execFileSync(tool('java'), ['-Xmx1g', '-cp', [driverClasses, ...driverClassPath].join(delimiter),
      'CompileJdkCompatSmoke', output, optimization, ...teavmClassPath],
    {stdio: ['ignore', 'pipe', 'inherit'], timeout: 600000, maxBuffer: 64 << 20});
    console.log(`TeaVM ${teavmVersion} (${optimization}) compiled the helper program in `
      + `${((Date.now() - started) / 1000).toFixed(1)} s`);
    const runner = join(output, 'run.cjs');
    await writeFile(runner, `require('./smoke.cjs').main([], function(error) {
      if (error) { console.error('TEAVM_MAIN_ERROR', error && (error.stack || error)); process.exitCode = 1; }
    });\n`);
    const nodeOutput = execFileSync(process.execPath, [runner],
      {encoding: 'utf8', timeout: 120000, maxBuffer: 64 << 20});
    const teavmTranscript = expectTranscript(nodeOutput, 'TeaVM run in Node');
    const difference = firstDifference(jdkTranscript, teavmTranscript);
    assert.equal(difference, null, `TeaVM helpers differ from the JDK at ${difference}`);
    console.log(`TeaVM in Node ${process.version}: helpers match the JDK on ${lineCount} lines`);
  }

  if (stages.has('overlay')) {
    const clientJar = join(overlayDirectory, `client-named-${profile}-gaius.jar`);
    assert.ok(existsSync(clientJar), `missing ${clientJar}; run build-overlays.sh for ${profile}`);
    const asm = join(repository, 'org', 'ow2', 'asm', 'asm', '9.8', 'asm-9.8.jar');
    const checkFile = join(temp, 'src', 'OverlayJdkCompatCheck.java');
    await writeFile(checkFile, overlaySource);
    const report = execFileSync(tool('java'), ['-cp', asm, checkFile, clientJar],
      {encoding: 'utf8', timeout: 300000, maxBuffer: 64 << 20});
    const problems = [...report.matchAll(/^OVERLAY_PROBLEM (.*)$/gm)].map(match => match[1]);
    assert.deepEqual(problems, [], `the ${profile} client jar still has TeaVM class library gaps`);
    const calls = /^OVERLAY_HELPER_CALLS \{(.*)\}$/m.exec(report)[1];
    const counts = Object.fromEntries(calls.split(', ').filter(Boolean).map(pair => {
      const [name, count] = pair.split('=');
      return [name, Number(count)];
    }));
    const users = /^OVERLAY_SCOPED_VALUE_USERS \[(.*)\]$/m.exec(report)[1];
    if (profile === RENDERPEARL_PROFILE.id) {
      // Every call site of the vanilla 26.3 jar (scan of the whole jar, not only TeaVM's paths).
      // firstLine, readAll, resolve and one slice (StagingBuffer$Cpu.copyTo) are
      // Minecraft262BrowserPatcher's redirects; the rest are JdkCompatPatches263's.
      assert.deepEqual(counts, {firstLine: 1, isPositive: 1, powExact: 2, readAll: 1,
        readAllAsString: 1, resolve: 1, slice: 4},
        'unexpected redirect counts in the ' + RENDERPEARL_PROFILE.id + ' client jar');
      assert.ok(users.includes('net/minecraft/world/level/block/state/SolidDebugger.class'),
        'SolidDebugger does not use BrowserScopedValue');
    }
    console.log(`overlay ${profile}: helper calls ${JSON.stringify(counts)}; `
      + `BrowserScopedValue users [${users}]; no gap reference left`);
  }
  console.log('browser-jdk-compat-smoke: OK');
} finally {
  if (!keep) await rm(temp, {recursive: true, force: true});
}
