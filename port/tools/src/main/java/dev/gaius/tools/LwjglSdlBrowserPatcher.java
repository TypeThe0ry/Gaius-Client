package dev.gaius.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Redirects the LWJGL SDL3 bindings that the vanilla client uses to the browser shim
 * {@code org.lwjgl.sdl.BrowserSdl} (work package P2, decision D3).
 *
 * <ul>
 *   <li>{@code SDL.<clinit>} becomes {@code SDL = null}: no native library is loaded.</li>
 *   <li>The entry table is generated from the profile's usage lock
 *       ({@code port/mc/profiles/<profile>/usage.lock}, {@link UsageLock}): every public static
 *       {@code SDL_*} method of {@code org/lwjgl/sdl} that vanilla references. The <em>body</em>
 *       of each entry becomes an {@code invokestatic} of the
 *       {@code BrowserSdl} method with the same name and descriptor. Bodies rather than call
 *       sites, because {@code SDLTimer.SDL_GetTicksNS} is also reached through the
 *       {@code REF_invokeStatic} method handle of {@code RenderSystem.initBackendSystem}.</li>
 *   <li>An entry that {@code BrowserSdl} does not implement gets a FEATURE stub: its body throws
 *       the {@code UnsupportedOperationException} of {@code BrowserSdlUnsupported.entry}, which
 *       reports the entry once. Only the entries a seam of the profile requires
 *       ({@code requiredEntries} of port/mc/seams/<generation>/seam.json) are REQUIRED: the build
 *       fails when vanilla does not use one or {@code BrowserSdl} does not implement it.</li>
 * </ul>
 *
 * <p>No count is hard-coded: a new vanilla version that uses more SDL entry points gets stubs for
 * the new ones (and a stale lock fails {@code InputPatches263}'s coverage check, which compares the
 * client's references with {@link #entryKeys()}). Fail-closed otherwise: every entry must exist in
 * the jar as a public static method, and the {@code BrowserSdl} overlay (compiled into the jar by
 * build-overlays.sh before this step) must be present.
 *
 * <p>The module has no native methods and no {@code sun.misc.Unsafe} use; its callback
 * interfaces ({@code SDL_LogOutputFunctionI} and others) still need the {@code callbacks}
 * step (LwjglCallbackDescriptorPatcher) of build-overlays.sh.
 */
public final class LwjglSdlBrowserPatcher {
    public static final String PACKAGE = "org/lwjgl/sdl/";
    public static final String SDL = PACKAGE + "SDL";
    public static final String BROWSER = PACKAGE + "BrowserSdl";
    public static final String UNSUPPORTED = PACKAGE + "BrowserSdlUnsupported";
    /** The usage lock section of the SDL bindings. */
    public static final String ARTIFACT = "org.lwjgl:lwjgl-sdl";

    private LwjglSdlBrowserPatcher() {
    }

    /** An SDL entry point: owner simple name in {@code org/lwjgl/sdl}, name and descriptor. */
    record Entry(String owner, String name, String desc) {
        String key() {
            return PACKAGE + owner + "." + name + desc;
        }

        /** {@code SDLVideo.SDL_CreateWindow}: the form of seam.json's requiredEntries. */
        String ownerAndName() {
            return PACKAGE + owner + "." + name;
        }
    }

    /**
     * The SDL entry points of a usage lock: the {@code M org/lwjgl/sdl/<Owner>.SDL_*(...)} lines
     * of its lwjgl-sdl section, sorted.
     */
    static List<Entry> entries(UsageLock lock) {
        List<Entry> entries = new ArrayList<>();
        for (String line : lock.library(ARTIFACT)) {
            if (!line.startsWith("M " + PACKAGE)) {
                continue;
            }
            String body = line.substring(2);
            int paren = body.indexOf('(');
            String qualified = body.substring(0, paren);
            int dot = qualified.lastIndexOf('.');
            String owner = qualified.substring(PACKAGE.length(), dot);
            String name = qualified.substring(dot + 1);
            if (name.startsWith("SDL_") && owner.indexOf('/') < 0) {
                entries.add(new Entry(owner, name, body.substring(paren)));
            }
        }
        return entries;
    }

    /**
     * {@code org/lwjgl/sdl/<Owner>.<name><descriptor>} of every entry point of the active
     * profile's usage lock (redirected or stubbed: none keeps its native body).
     */
    public static Set<String> entryKeys() throws IOException {
        Set<String> keys = new LinkedHashSet<>();
        for (Entry entry : entries(UsageLock.active())) {
            keys.add(entry.key());
        }
        return Collections.unmodifiableSet(keys);
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2 || args.length > 3) {
            throw new IllegalArgumentException(
                    "usage: LwjglSdlBrowserPatcher INPUT_JAR OUTPUT_ROOT [USAGE_LOCK]");
        }
        String jar = args[0];
        Path root = Path.of(args[1]);
        UsageLock lock = args.length == 3 ? UsageLock.read(Path.of(args[2])) : UsageLock.active();
        List<Entry> entries = entries(lock);
        if (entries.isEmpty()) {
            throw new IllegalStateException(lock.file() + " lists no SDL entry point of " + ARTIFACT
                    + "; regenerate it (bash port/tools/extrefs/extrefs.sh usage " + lock.profile() + " --write)");
        }
        Set<String> targets = browserTargets(jar);
        List<Entry> redirected = new ArrayList<>();
        List<Entry> stubbed = new ArrayList<>();
        for (Entry entry : entries) {
            (targets.contains(entry.name() + entry.desc()) ? redirected : stubbed).add(entry);
        }
        Set<String> required = new LinkedHashSet<>();
        for (String requirement : lock.seamRequiredEntries()) {
            if (requirement.startsWith(PACKAGE)) {
                required.add(requirement);
            }
        }
        List<String> unmet = new ArrayList<>();
        for (String requirement : required) {
            boolean used = false;
            boolean implemented = false;
            for (Entry entry : entries) {
                if (entry.ownerAndName().equals(requirement)) {
                    used = true;
                    implemented |= redirected.contains(entry);
                }
            }
            if (!used) {
                unmet.add(requirement + " (vanilla does not use it)");
            } else if (!implemented) {
                unmet.add(requirement + " (" + BROWSER + " does not implement it)");
            }
        }
        if (!unmet.isEmpty()) {
            throw new IllegalStateException("REQUIRED SDL entry points of the " + lock.profile()
                    + " seams are not redirected: " + String.join(", ", unmet));
        }
        if (!stubbed.isEmpty()) {
            read(jar, UNSUPPORTED + ".class");
        }
        patchLibraryInitializer(jar, root.resolve(SDL + ".class"));
        int replaced = rewriteEntries(jar, root, redirected, stubbed);
        if (replaced != entries.size()) {
            throw new IllegalStateException("Rewrote " + replaced + " SDL entry points, the usage lock lists "
                    + entries.size());
        }
        for (Entry entry : stubbed) {
            System.out.println("SDL_FEATURE_STUB " + entry.owner() + "." + entry.name() + entry.desc());
        }
        System.out.println("LwjglSdlBrowserPatcher: redirected " + redirected.size() + " SDL entry points to "
                + BROWSER + " and stubbed " + stubbed.size() + " (FEATURE) of the " + entries.size()
                + " in " + lock.file().getFileName() + " of " + lock.profile() + " (" + required.size()
                + " REQUIRED by seams); SDL.<clinit> loads no native library");
    }

    private static Set<String> browserTargets(String jar) throws IOException {
        ClassNode browser = read(jar, BROWSER + ".class");
        Set<String> targets = new LinkedHashSet<>();
        for (MethodNode method : browser.methods) {
            if ((method.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC))
                    == (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)) {
                targets.add(method.name + method.desc);
            }
        }
        return targets;
    }

    private static void patchLibraryInitializer(String jar, Path output) throws IOException {
        ClassNode node = read(jar, SDL + ".class");
        MethodNode initializer = null;
        for (MethodNode method : node.methods) {
            if (method.name.equals("<clinit>") && method.desc.equals("()V")) {
                initializer = method;
            }
        }
        if (initializer == null) {
            throw new IllegalStateException(SDL + ".<clinit> not found");
        }
        int loads = 0;
        int stores = 0;
        for (AbstractInsnNode instruction = initializer.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("org/lwjgl/system/Library")
                    && call.name.equals("loadNative")) {
                loads++;
            } else if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTSTATIC
                    && field.owner.equals(SDL)) {
                if (!field.name.equals("SDL") || !field.desc.equals("Lorg/lwjgl/system/SharedLibrary;")) {
                    throw new IllegalStateException("Unexpected SDL.<clinit> store " + field.name);
                }
                stores++;
            }
        }
        if (loads != 1 || stores != 1) {
            throw new IllegalStateException("SDL.<clinit> shape changed: loadNative=" + loads
                    + " stores=" + stores);
        }
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new FieldInsnNode(Opcodes.PUTSTATIC, SDL, "SDL", "Lorg/lwjgl/system/SharedLibrary;"));
        code.add(new InsnNode(Opcodes.RETURN));
        replace(initializer, code, 1);
        write(node, output);
    }

    private static int rewriteEntries(String jar, Path root, List<Entry> redirected, List<Entry> stubbed)
            throws IOException {
        // Owner -> (name + descriptor -> redirect?), owners in name order for a stable log.
        Map<String, Map<String, Boolean>> byOwner = new TreeMap<>();
        for (Entry entry : redirected) {
            byOwner.computeIfAbsent(entry.owner(), owner -> new LinkedHashMap<>()).put(entry.name() + entry.desc(), true);
        }
        for (Entry entry : stubbed) {
            byOwner.computeIfAbsent(entry.owner(), owner -> new LinkedHashMap<>()).put(entry.name() + entry.desc(), false);
        }
        int replaced = 0;
        for (Map.Entry<String, Map<String, Boolean>> owner : byOwner.entrySet()) {
            String className = PACKAGE + owner.getKey();
            ClassNode node = read(jar, className + ".class");
            Map<String, Boolean> wanted = owner.getValue();
            Set<String> done = new LinkedHashSet<>();
            for (MethodNode method : node.methods) {
                String key = method.name + method.desc;
                Boolean redirect = wanted.get(key);
                if (redirect == null) {
                    continue;
                }
                if (!done.add(key)) {
                    throw new IllegalStateException("Duplicate method " + className + "." + key);
                }
                if ((method.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC))
                        != (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)
                        || (method.access & (Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) != 0) {
                    throw new IllegalStateException(className + "." + key + " is not a public static method");
                }
                if (redirect) {
                    delegate(method);
                } else {
                    stub(method, owner.getKey() + "." + key);
                }
                replaced++;
            }
            List<String> missing = new ArrayList<>();
            for (String key : wanted.keySet()) {
                if (!done.contains(key)) {
                    missing.add(className + "." + key);
                }
            }
            if (!missing.isEmpty()) {
                throw new IllegalStateException("SDL entry points not found: " + String.join(", ", missing));
            }
            write(node, root.resolve(className + ".class"));
        }
        return replaced;
    }

    private static void delegate(MethodNode method) {
        InsnList code = new InsnList();
        int local = 0;
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            code.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), local));
            local += argument.getSize();
        }
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BROWSER, method.name, method.desc, false));
        code.add(new InsnNode(Type.getReturnType(method.desc).getOpcode(Opcodes.IRETURN)));
        replace(method, code, Math.max(2, local));
    }

    /** {@code throw BrowserSdlUnsupported.entry("<Owner>.<name><descriptor>");} */
    private static void stub(MethodNode method, String entry) {
        InsnList code = new InsnList();
        code.add(new LdcInsnNode(entry));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, UNSUPPORTED, "entry",
                "(Ljava/lang/String;)Ljava/lang/UnsupportedOperationException;", false));
        code.add(new InsnNode(Opcodes.ATHROW));
        replace(method, code, 1);
    }

    private static ClassNode read(String jarPath, String entryName) throws IOException {
        try (ZipFile jar = new ZipFile(jarPath)) {
            var entry = jar.getEntry(entryName);
            if (entry == null) {
                throw new IllegalStateException(entryName + " not found in " + jarPath);
            }
            ClassNode node = new ClassNode();
            try (var stream = jar.getInputStream(entry)) {
                new ClassReader(stream.readAllBytes()).accept(node, 0);
            }
            return node;
        }
    }

    private static void replace(MethodNode method, InsnList code, int maxStack) {
        method.instructions = code;
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) {
            method.localVariables.clear();
        }
        method.visibleLocalVariableAnnotations = null;
        method.invisibleLocalVariableAnnotations = null;
        method.maxStack = maxStack;
        method.maxLocals = Math.max(method.maxLocals, Type.getArgumentsAndReturnSizes(method.desc) >> 2);
    }

    private static void write(ClassNode node, Path output) throws IOException {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }
}
