package dev.gaius.tools.runtime;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Role-level TeaVM compiler options for the Gaius build, read inside the patched TeaVM compiler.
 *
 * <p>The patched TeaVM classes (see {@code TeaVMCoreBrowserPatcher}) call into this class, and
 * the patcher copies it into the patched teavm-core jar, so it runs in the TeaVM Maven plugin.
 * It must only depend on the JDK: the patchers are compiled against ASM alone, and every TeaVM
 * model object is handled reflectively.
 *
 * <p>The options come from the {@code <properties>} block of the TeaVM plugin configuration that
 * {@code port/scripts/generate-pom.sh} writes from the role table (client or
 * singleplayer-worker). The patched {@code TeaVM.getPlatformTags} (read when plugins are
 * installed) and {@code Renderer.setProperties} pass them here before dependency analysis,
 * the async analysis and name allocation run. JVM system properties are not
 * read: a leftover {@code -Dgaius.teavm.*} toggle fails the build so that a stale MAVEN_OPTS
 * cannot silently change the output. Without a configuration every option is off, which is
 * the vanilla TeaVM behaviour.
 */
public final class GaiusTeaVMOptions {
    public static final String PREFIX = "gaius.teavm.";
    public static final String ROLE = PREFIX + "role";
    /**
     * Callers of an async class initializer are not made async (see TeaVMCoreBrowserPatcher).
     * Cooperative yields (TModernRuntimeSupport.yieldToEventLoop, TLockSupport) are skipped
     * while a class initializer runs, so on the client the Blocks and Items initializers no
     * longer repaint at their BrowserStartupScheduler checkpoints: the startup progress events
     * are still recorded, but the page stays unpainted for those seconds. A real suspension
     * inside an initializer is counted in globalThis.__gaiusClinitSuspensions (patched
     * thread.js). The barrier and syncMonitors stay consistent with syncClinits off as well,
     * because yields and parks are also skipped inside guarded methods
     * (globalThis.__gaiusNoSuspendDepth), so a role can turn this off on its own.
     */
    public static final String SYNC_CLINITS = PREFIX + "syncClinits";
    /**
     * java.lang.Object monitor primitives are compiled synchronously, with a suspension guard.
     * Synchronized methods that only became async through the monitor primitives (the
     * exception construction in monitorExitSync) are then compiled synchronously too, and
     * TeaVM renders their monitor entry as monitorEnterSync. When such a monitor is contended,
     * monitorEnterSync throws IllegalStateException("Can't enter monitor from another thread
     * synchronously") without reaching TeaVMThread.suspend: it is not counted in
     * globalThis.__gaiusNoSuspendViolations, and the acceptance scripts match the message
     * text instead.
     */
    public static final String SYNC_MONITORS = PREFIX + "syncMonitors";
    /** Comma-separated barrier groups (see {@link #BARRIER_GROUPS}), or "none". */
    public static final String ASYNC_BARRIER = PREFIX + "asyncBarrier";
    /** TeaVM runtime functions ($rt_*, Long_*) always get a top-level binding. */
    public static final String PIN_RUNTIME_NAMES = PREFIX + "pinRuntimeNames";
    /** "false" removes the {@link #TELEMETRY_TAG} platform tag (release builds). */
    public static final String TELEMETRY = PREFIX + "telemetry";
    /** Optional file that receives the methods the async options compiled synchronously. */
    public static final String CUT_REPORT = PREFIX + "cutReport";
    /**
     * Optional file that receives the async method census (M0 spike S2): every method the
     * async analysis made a coroutine, the root that made it async (an {@code @Async}
     * primitive or a monitor), the virtual families that turn call sites async, and the
     * async methods reachable from the client loop and the server tick. A summary goes to
     * {@code <file>.summary.txt}. Reporting only: it never changes the output.
     */
    public static final String ASYNC_DUMP = PREFIX + "asyncDump";
    /**
     * "true" stops the compilation right after {@link #ASYNC_DUMP} is written, for census
     * runs that do not need the generated code.
     */
    public static final String ASYNC_DUMP_STOP = PREFIX + "asyncDumpStop";
    /**
     * WebAssembly GC JSPI mode: the async analysis yields an empty set, so no
     * method is compiled as a coroutine. Blocking primitives (monitors, wait/notify, sleep,
     * park, thread start) suspend the whole wasm stack through WebAssembly.Suspending imports
     * instead (dev.gaius.browser.JspiHost), and every Java thread runs as one
     * WebAssembly.promising activation. The class library selects those primitives through
     * the {@link #JSPI_TAG} platform tag. A reachable {@code @Async} method fails the build,
     * because nothing would suspend for it. Only for the WEBASSEMBLY_GC target, which requires
     * it ({@link #platformTags} fails a configured WebAssembly GC compile without it); exclusive
     * with the coroutine cutting options. Class initializers get the JVM's initialization
     * semantics in this mode ({@link #wasmWrapMethodBody}).
     */
    public static final String JSPI = PREFIX + "jspi";

    /**
     * Platform tag present unless the role strips telemetry. Code marks a static boolean
     * method with {@code @PlatformMarker("gaius-telemetry")}; TeaVM's PlatformMarkerSupport
     * replaces every call with this compile-time constant, so a stripped release drops the
     * guarded telemetry branches before optimization.
     */
    public static final String TELEMETRY_TAG = "gaius-telemetry";
    /** Platform tag prefix for the role, for example {@code gaius-role-client}. */
    public static final String ROLE_TAG_PREFIX = "gaius-role-";
    /** Platform tag of the JSPI mode ({@link #JSPI}), read by JspiHost.enabled(). */
    public static final String JSPI_TAG = "gaius-jspi";
    /** Platform tag of TeaVM's WEBASSEMBLY_GC target ({@code WasmGCTarget.getPlatformTags}). */
    static final String WASM_GC_TAG = "webassembly-gc";
    /**
     * The class (TeaVM name of TJspiThreadSupport) whose clinitEnter/clinitExit the class
     * initialization guards of the JSPI mode call ({@link #wasmWrapMethodBody}).
     */
    static final String JSPI_SUPPORT_CLASS = "java.lang.JspiThreadSupport";

    /** JS counter of active frames that must not suspend (cut methods). */
    public static final String NO_SUSPEND_COUNTER = "globalThis.__gaiusNoSuspendDepth";
    static final String GUARD_OPEN =
            NO_SUSPEND_COUNTER + "=(" + NO_SUSPEND_COUNTER + "|0)+1;try{";
    static final String GUARD_CLOSE = "}finally{" + NO_SUSPEND_COUNTER + "--;}";

    private static final Set<String> KNOWN_KEYS = Set.of(
            ROLE, SYNC_CLINITS, SYNC_MONITORS, ASYNC_BARRIER, PIN_RUNTIME_NAMES, TELEMETRY,
            CUT_REPORT, ASYNC_DUMP, ASYNC_DUMP_STOP, JSPI);
    private static final Set<String> ROLES = Set.of("client", "singleplayer-worker");

    /**
     * Barrier groups: virtual families whose implementations never suspend in practice but
     * make every caller of the family async as soon as one implementation is async (most often
     * through exception construction, Class.getName or StringBuilder). Entries are
     * {root class, method name, descriptor, kind}; kind "family" matches every non-static
     * override declared in a subtype of the root, kind "exact" matches only the root class.
     * A method on this list is compiled synchronously and its callers are not made async;
     * its body is wrapped in a guard that throws if a suspension is reached inside it.
     */
    private static final Map<String, String[][]> BARRIER_GROUPS = barrierGroups();

    private static final Set<String> MONITOR_PRIMITIVES = Set.of(
            "monitorEnter", "monitorExit", "monitorEnterSync", "monitorExitSync");

    private static boolean configured;
    private static String role = "unconfigured";
    private static boolean syncClinits;
    private static boolean syncMonitors;
    private static boolean pinRuntimeNames;
    private static boolean telemetry = true;
    private static boolean jspi;
    private static String cutReport;
    private static String asyncDump;
    private static boolean asyncDumpStop;
    private static final Map<Object, Object> asyncCauses = new HashMap<>();
    private static List<String[]> barrierEntries = List.of();
    private static Set<String> barrierNames = Set.of();
    private static Set<String> barrierGroupNames = Set.of();

    private static final Set<Object> cutMethods = new HashSet<>();
    private static final Map<String, Integer> cutCounts = new TreeMap<>();
    private static final List<String> cutDescriptions = new ArrayList<>();
    private static final Map<String, Boolean> subtypeCache = new HashMap<>();
    private static final Map<String, Method> reflectionCache = new HashMap<>();

    private GaiusTeaVMOptions() {
    }

    private static Map<String, String[][]> barrierGroups() {
        Map<String, String[][]> groups = new HashMap<>();
        groups.put("object", new String[][] {
            {"java.lang.Object", "toString", "()Ljava/lang/String;", "family"},
            {"java.lang.Object", "hashCode", "()I", "family"},
            {"java.lang.Object", "equals", "(Ljava/lang/Object;)Z", "family"},
        });
        groups.put("throwable", new String[][] {
            {"java.lang.Throwable", "fillInStackTrace", "()Ljava/lang/Throwable;", "family"},
            {"java.lang.Throwable", "getMessage", "()Ljava/lang/String;", "family"},
            {"java.lang.Throwable", "getLocalizedMessage", "()Ljava/lang/String;", "family"},
        });
        groups.put("map", new String[][] {
            {"java.util.Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", "family"},
            {"java.util.Map", "containsKey", "(Ljava/lang/Object;)Z", "family"},
            {"java.util.Map", "size", "()I", "family"},
            {"java.util.Map", "isEmpty", "()Z", "family"},
        });
        groups.put("collection", new String[][] {
            {"java.util.Collection", "size", "()I", "family"},
            {"java.util.Collection", "isEmpty", "()Z", "family"},
            {"java.util.Collection", "contains", "(Ljava/lang/Object;)Z", "family"},
            {"java.util.Collection", "iterator", "()Ljava/util/Iterator;", "family"},
            {"java.lang.Iterable", "iterator", "()Ljava/util/Iterator;", "family"},
        });
        groups.put("iterator", new String[][] {
            {"java.util.Iterator", "hasNext", "()Z", "family"},
            {"java.util.Iterator", "next", "()Ljava/lang/Object;", "family"},
        });
        groups.put("stringbuilder", new String[][] {
            {"java.lang.StringBuilder", "append", "(Ljava/lang/Object;)Ljava/lang/StringBuilder;",
                "exact"},
            {"java.lang.AbstractStringBuilder", "append",
                "(Ljava/lang/Object;)Ljava/lang/AbstractStringBuilder;", "exact"},
            {"java.lang.String", "valueOf", "(Ljava/lang/Object;)Ljava/lang/String;", "exact"},
        });
        return Collections.unmodifiableMap(groups);
    }

    /** The barrier group names, for the build scripts' validation and documentation. */
    public static Set<String> barrierGroupNames() {
        return BARRIER_GROUPS.keySet();
    }

    /**
     * Reads the role configuration. Called from the patched TeaVM with the compiler properties;
     * a later call with the same properties is a no-op, a call with different Gaius options
     * fails because part of the compilation already used the previous ones.
     */
    public static synchronized void configure(Properties properties) {
        rejectSystemPropertyToggles();
        if (properties == null) {
            return;
        }
        Map<String, String> gaius = new TreeMap<>();
        for (String name : properties.stringPropertyNames()) {
            if (name.startsWith(PREFIX)) {
                if (!KNOWN_KEYS.contains(name)) {
                    throw new IllegalStateException("Unknown Gaius TeaVM option " + name
                            + " (known: " + new TreeSet<>(KNOWN_KEYS) + ")");
                }
                gaius.put(name, properties.getProperty(name).trim());
            }
        }
        if (gaius.isEmpty()) {
            if (configured) {
                throw new IllegalStateException(
                        "The Gaius TeaVM options disappeared from the compiler properties");
            }
            return;
        }
        String newRole = gaius.getOrDefault(ROLE, "");
        if (!ROLES.contains(newRole)) {
            throw new IllegalStateException("Invalid " + ROLE + " '" + newRole
                    + "' (expected one of " + ROLES + ")");
        }
        boolean newSyncClinits = parseBoolean(gaius, SYNC_CLINITS, false);
        boolean newSyncMonitors = parseBoolean(gaius, SYNC_MONITORS, false);
        boolean newPin = parseBoolean(gaius, PIN_RUNTIME_NAMES, false);
        boolean newTelemetry = parseBoolean(gaius, TELEMETRY, true);
        boolean newJspi = parseBoolean(gaius, JSPI, false);
        String newReport = gaius.get(CUT_REPORT);
        if (newReport != null && newReport.isEmpty()) {
            newReport = null;
        }
        String newDump = gaius.get(ASYNC_DUMP);
        if (newDump != null && newDump.isEmpty()) {
            newDump = null;
        }
        boolean newDumpStop = parseBoolean(gaius, ASYNC_DUMP_STOP, false);
        Set<String> groups = new LinkedHashSet<>();
        String barrier = gaius.getOrDefault(ASYNC_BARRIER, "none");
        if (!barrier.isEmpty() && !barrier.equals("none")) {
            for (String group : barrier.split(",")) {
                String trimmed = group.trim();
                if (!BARRIER_GROUPS.containsKey(trimmed)) {
                    throw new IllegalStateException("Unknown " + ASYNC_BARRIER + " group '"
                            + trimmed + "' (known: " + new TreeMap<>(BARRIER_GROUPS).keySet()
                            + ")");
                }
                groups.add(trimmed);
            }
        }
        if (newJspi && (newSyncClinits || newSyncMonitors || !groups.isEmpty())) {
            throw new IllegalStateException(JSPI + " compiles no method as a coroutine; remove "
                    + SYNC_CLINITS + ", " + SYNC_MONITORS + " and " + ASYNC_BARRIER
                    + " from this role");
        }
        if (configured) {
            if (!newRole.equals(role) || newSyncClinits != syncClinits
                    || newSyncMonitors != syncMonitors || newPin != pinRuntimeNames
                    || newTelemetry != telemetry || newJspi != jspi
                    || !groups.equals(barrierGroupNames)) {
                throw new IllegalStateException(
                        "The Gaius TeaVM options changed during one compilation");
            }
            return;
        }
        List<String[]> entries = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (String group : groups) {
            for (String[] entry : BARRIER_GROUPS.get(group)) {
                entries.add(entry);
                names.add(entry[1]);
            }
        }
        role = newRole;
        syncClinits = newSyncClinits;
        syncMonitors = newSyncMonitors;
        pinRuntimeNames = newPin;
        telemetry = newTelemetry;
        jspi = newJspi;
        cutReport = newReport;
        asyncDump = newDump;
        asyncDumpStop = newDumpStop;
        barrierEntries = List.copyOf(entries);
        barrierNames = Set.copyOf(names);
        barrierGroupNames = Collections.unmodifiableSet(groups);
        configured = true;
        System.out.println("[gaius-teavm] role=" + role + " syncClinits=" + syncClinits
                + " syncMonitors=" + syncMonitors + " asyncBarrier="
                + (groups.isEmpty() ? "none" : String.join(",", groups))
                + " pinRuntimeNames=" + pinRuntimeNames + " telemetry=" + telemetry
                + " jspi=" + jspi);
    }

    private static boolean parseBoolean(Map<String, String> values, String key, boolean fallback) {
        String value = values.get(key);
        if (value == null) {
            return fallback;
        }
        if (value.equals("true")) {
            return true;
        }
        if (value.equals("false")) {
            return false;
        }
        throw new IllegalStateException("Invalid " + key + " '" + value
                + "' (expected true or false)");
    }

    private static void rejectSystemPropertyToggles() {
        for (String name : System.getProperties().stringPropertyNames()) {
            if (name.startsWith(PREFIX)) {
                throw new IllegalStateException("JVM system property " + name
                        + " is no longer read: TeaVM options come from the role configuration"
                        + " in port/scripts/generate-pom.sh (POM <properties>). Remove it from"
                        + " MAVEN_OPTS.");
            }
        }
    }

    /**
     * Patched {@code TeaVM.getPlatformTags}: configures the options from the compiler
     * properties and appends the role and telemetry tags. The class library plugin reads the
     * tags when TeaVM installs plugins, after TeaVMTool has set the properties; the TeaVM
     * constructor reads the target's tags directly, before that, and is not affected.
     */
    public static String[] platformTags(String[] base, Properties properties) {
        configure(properties);
        if (!configured) {
            return base;
        }
        if (!jspi && Arrays.asList(base).contains(WASM_GC_TAG)) {
            // The coroutine (Fiber) mode of the WebAssembly GC target was removed (design D3,
            // M0 S2: its output does not validate); GAIUS_TEAVM_JSPI=false no longer works.
            throw new IllegalStateException("The WebAssembly GC target only runs in JSPI mode:"
                    + " role " + role + " must set " + JSPI + "=true (design D3)");
        }
        List<String> tags = new ArrayList<>(Arrays.asList(base));
        tags.add(ROLE_TAG_PREFIX + role);
        if (telemetry) {
            tags.add(TELEMETRY_TAG);
        }
        if (jspi) {
            tags.add(JSPI_TAG);
        }
        return tags.toArray(new String[0]);
    }

    public static boolean syncClinits() {
        return syncClinits;
    }

    public static boolean syncMonitors() {
        return syncMonitors;
    }

    public static boolean jspi() {
        return jspi;
    }

    /**
     * Patched {@code AsyncMethodFinder.find} start: true in JSPI mode, where the analysis
     * stops with an empty async set. Fails the build when an {@code @Async} method is still
     * reachable: its callers would run it synchronously and its callback would never resume
     * them. The class library replaces every {@code @Async} primitive with a JspiHost import
     * when the gaius-jspi tag is present (TeaVMClasslibJspiPatcher).
     */
    public static synchronized boolean jspiSkipAsyncAnalysis(Object classSource, Object dependency) {
        if (!jspi) {
            return false;
        }
        Object reachable = invoke(dependency, "getReachableMethods");
        List<String> violations = new ArrayList<>();
        for (Object name : (Collection<?>) invoke(classSource, "getClassNames")) {
            Object cls = invoke(classSource, "get", name);
            if (cls == null) {
                continue;
            }
            for (Object method : (Collection<?>) invoke(cls, "getMethods")) {
                if (hasAnnotation(method, "org.teavm.interop.Async")
                        && (Boolean) invoke(reachable, "contains", invoke(method, "getReference"))) {
                    violations.add(String.valueOf(invoke(method, "getReference")));
                }
            }
        }
        if (!violations.isEmpty()) {
            Collections.sort(violations);
            throw new IllegalStateException(JSPI + ": " + violations.size()
                    + " @Async methods are reachable but nothing suspends for them in JSPI mode"
                    + " (route them through JspiHost): " + violations);
        }
        System.out.println("[gaius-teavm] jspi: async analysis skipped, 0 coroutine methods");
        return true;
    }

    public static boolean pinRuntimeNames() {
        return pinRuntimeNames;
    }

    /**
     * Whether a TeaVM runtime function keeps a top-level binding even after the top-level name
     * budget (maxTopLevelNames) is spent. Runtime functions are few (a few hundred) and every
     * coroutine check goes through some of them ($rt_suspending, $rt_resuming,
     * $rt_nativeThread and the current-thread slot), so a dictionary-mode property load there
     * costs on every async call.
     */
    public static boolean isPinnedRuntimeName(String name) {
        return pinRuntimeNames && name != null
                && (name.startsWith("$rt_") || name.startsWith("Long_"));
    }

    /** Patched {@code AsyncMethodFinder.find} start. */
    public static synchronized void beginAsyncAnalysis() {
        asyncCauses.clear();
        cutMethods.clear();
        cutCounts.clear();
        cutDescriptions.clear();
        subtypeCache.clear();
    }

    /**
     * Patched {@code AsyncMethodFinder.add} start: true when the method must not become async
     * and must not make its callers async. The method is then rendered synchronously with the
     * {@link #guardOpen} wrapper.
     */
    public static synchronized boolean cutAsync(Object classSource, Object methodRef) {
        if (!syncMonitors && barrierEntries.isEmpty()) {
            return false;
        }
        if (cutMethods.contains(methodRef)) {
            return true;
        }
        String name = (String) invoke(methodRef, "getName");
        String className = (String) invoke(methodRef, "getClassName");
        String reason = null;
        if (syncMonitors && className.equals("java.lang.Object")
                && MONITOR_PRIMITIVES.contains(name)) {
            reason = "syncMonitors";
        } else if (barrierNames.contains(name)) {
            String descriptor = String.valueOf(invoke(methodRef, "getDescriptor"));
            String signature = descriptor.substring(name.length());
            for (String[] entry : barrierEntries) {
                if (!entry[1].equals(name) || !entry[2].equals(signature)) {
                    continue;
                }
                if (entry[3].equals("exact") ? className.equals(entry[0])
                        : isFamilyMember(classSource, className, entry[0], methodRef)) {
                    reason = "asyncBarrier:" + entry[0] + "." + entry[1];
                    break;
                }
            }
        }
        if (reason == null) {
            return false;
        }
        Object method = methodReader(classSource, className, methodRef);
        if (method != null && hasAnnotation(method, "org.teavm.interop.Async")) {
            throw new IllegalStateException("Gaius TeaVM option " + reason + " would compile "
                    + methodRef + " synchronously, but it is an @Async method; remove it"
                    + " from the barrier list");
        }
        cutMethods.add(methodRef);
        cutCounts.merge(reason, 1, Integer::sum);
        cutDescriptions.add(reason + "\t" + methodRef);
        return true;
    }

    private static boolean isFamilyMember(
            Object classSource, String className, String root, Object methodRef) {
        Object method = methodReader(classSource, className, methodRef);
        if (method == null || hasModifier(method, "STATIC")) {
            return false;
        }
        Object level = invoke(method, "getLevel");
        if (level instanceof Enum<?> access && access.name().equals("PRIVATE")) {
            return false;
        }
        if (root.equals("java.lang.Object")) {
            return true;
        }
        return isSubtype(classSource, className, root);
    }

    private static boolean isSubtype(Object classSource, String className, String root) {
        String key = className + "|" + root;
        Boolean cached = subtypeCache.get(key);
        if (cached != null) {
            return cached;
        }
        boolean result = false;
        Set<String> visited = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(className);
        while (!pending.isEmpty()) {
            String current = pending.poll();
            if (!visited.add(current)) {
                continue;
            }
            if (current.equals(root)) {
                result = true;
                break;
            }
            Object cls = invoke(classSource, "get", current);
            if (cls == null) {
                continue;
            }
            Object parent = invoke(cls, "getParent");
            if (parent != null) {
                pending.add((String) parent);
            }
            Object interfaces = invoke(cls, "getInterfaces");
            if (interfaces instanceof Collection<?> collection) {
                for (Object item : collection) {
                    pending.add((String) item);
                }
            }
        }
        subtypeCache.put(key, result);
        return result;
    }

    private static Object methodReader(Object classSource, String className, Object methodRef) {
        Object cls = invoke(classSource, "get", className);
        if (cls == null) {
            return null;
        }
        return invoke(cls, "getMethod", invoke(methodRef, "getDescriptor"));
    }

    private static boolean hasModifier(Object element, String modifier) {
        Object modifiers = invoke(element, "readModifiers");
        if (modifiers instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item instanceof Enum<?> value && value.name().equals(modifier)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasAnnotation(Object element, String annotation) {
        Object annotations = invoke(element, "getAnnotations");
        return annotations != null && invoke(annotations, "get", annotation) != null;
    }

    /** Patched {@code AsyncMethodFinder.find} end: logs and optionally writes the cut list. */
    public static synchronized void reportCuts() {
        if (!syncMonitors && barrierEntries.isEmpty()) {
            return;
        }
        System.out.println("[gaius-teavm] methods compiled synchronously by role options: "
                + cutMethods.size() + " " + cutCounts);
        if (syncMonitors && cutCounts.getOrDefault("syncMonitors", 0) == 0) {
            System.out.println("[gaius-teavm] WARNING: syncMonitors is on but no monitor"
                    + " primitive reached the async analysis");
        }
        if (cutReport != null) {
            List<String> lines = new ArrayList<>(cutDescriptions);
            Collections.sort(lines);
            try {
                Path path = Path.of(cutReport);
                if (path.getParent() != null) {
                    Files.createDirectories(path.getParent());
                }
                Files.write(path, lines, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("Could not write " + cutReport, e);
            }
        }
    }

    /** Patched {@code Renderer.renderRegularBody}: JS written after the method prologue. */
    public static synchronized String guardOpen(Object methodRef) {
        return cutMethods.contains(methodRef) ? GUARD_OPEN : null;
    }

    /** Patched {@code Renderer.renderRegularBody}: JS written after the method body. */
    public static synchronized String guardClose(Object methodRef) {
        return cutMethods.contains(methodRef) ? GUARD_CLOSE : null;
    }

    /**
     * Patched {@code AsyncMethodFinder.add}, right after a method joined the async set: keeps
     * the finder's call stack (the chain from this method down to the root that made it
     * async) for {@link #dumpAsync}. A no-op without {@link #ASYNC_DUMP}.
     */
    public static synchronized void recordAsync(Object methodRef, Object callStack) {
        if (asyncDump != null) {
            asyncCauses.put(methodRef, callStack);
        }
    }

    /** Methods whose forward call closure the census reports, as class name and method name. */
    private static final String[][] CENSUS_ENTRIES = {
        {"net.minecraft.client.Minecraft", "run"},
        {"net.minecraft.client.Minecraft", "renderFrame"},
        {"net.minecraft.client.Minecraft", "tick"},
        {"net.minecraft.client.renderer.GameRenderer", "render"},
        {"net.minecraft.server.MinecraftServer", "runServer"},
        {"net.minecraft.server.MinecraftServer", "tickServer"},
        {"net.minecraft.server.MinecraftServer", "tickChildren"},
    };

    /**
     * Patched {@code AsyncMethodFinder.find} end: writes the async census to
     * {@link #ASYNC_DUMP} (no-op without it). Reads the finder's fields reflectively.
     */
    public static synchronized void dumpAsync(Object finder) {
        if (asyncDump == null) {
            return;
        }
        try {
            writeAsyncCensus(finder);
        } catch (RuntimeException | Error failure) {
            if (asyncDumpStop) {
                throw failure;
            }
            // Reporting only: a census failure must not fail the compilation.
            System.out.println("[gaius-teavm] async census failed: " + failure);
            failure.printStackTrace(System.out);
        }
        if (asyncDumpStop) {
            throw new IllegalStateException("Gaius async census written to " + asyncDump
                    + "; stopping the compilation as " + ASYNC_DUMP_STOP + " requests");
        }
    }

    private static void writeAsyncCensus(Object finder) {
        long started = System.nanoTime();
        Set<?> asyncMethods = (Set<?>) field(finder, "asyncMethods");
        Map<?, ?> families = (Map<?, ?>) field(finder, "asyncFamilyMethods");
        Object callGraph = field(finder, "callGraph");
        Object classSource = field(finder, "classSource");
        Set<String> async = new TreeSet<>();
        Map<String, String> rootOf = new HashMap<>();
        Map<String, String> viaOf = new HashMap<>();
        Map<String, Integer> rootCounts = new TreeMap<>();
        Map<String, Integer> rootKinds = new TreeMap<>();
        Map<String, String> kindCache = new HashMap<>();
        for (Object method : asyncMethods) {
            String name = String.valueOf(method);
            async.add(name);
            Object stack = asyncCauses.get(method);
            String root = name;
            String via = "";
            if (stack != null) {
                Object next = field(stack, "next");
                if (next != null) {
                    via = String.valueOf(field(next, "method"));
                }
                Object last = stack;
                for (Object cursor = stack; cursor != null; cursor = field(cursor, "next")) {
                    last = cursor;
                }
                root = String.valueOf(field(last, "method"));
            }
            rootOf.put(name, root);
            viaOf.put(name, via);
            rootCounts.merge(root, 1, Integer::sum);
        }
        for (String root : rootCounts.keySet()) {
            rootKinds.merge(rootKind(classSource, root, kindCache), rootCounts.get(root),
                    Integer::sum);
        }
        Set<String> familyMembers = new TreeSet<>();
        for (Map.Entry<?, ?> entry : families.entrySet()) {
            if (Boolean.TRUE.equals(entry.getValue())) {
                familyMembers.add(String.valueOf(entry.getKey()));
            }
        }
        List<String> summary = new ArrayList<>();
        summary.add("role=" + role + " syncClinits=" + syncClinits + " syncMonitors=" + syncMonitors
                + " asyncBarrier=" + (barrierGroupNames.isEmpty() ? "none"
                        : String.join(",", barrierGroupNames)));
        summary.add("asyncMethods=" + async.size() + " asyncFamilyMethods=" + familyMembers.size()
                + " cut=" + cutMethods.size());
        summary.add("rootKinds=" + rootKinds);
        List<Map.Entry<String, Integer>> topRoots = new ArrayList<>(rootCounts.entrySet());
        topRoots.sort((left, right) -> Integer.compare(right.getValue(), left.getValue()));
        summary.add("topRoots:");
        for (int index = 0; index < Math.min(40, topRoots.size()); index++) {
            Map.Entry<String, Integer> root = topRoots.get(index);
            summary.add("  " + root.getValue() + "  "
                    + rootKind(classSource, root.getKey(), kindCache) + "  " + root.getKey());
        }
        List<String> lines = new ArrayList<>();
        for (String[] entry : CENSUS_ENTRIES) {
            for (Object start : entryMethods(classSource, entry[0], entry[1])) {
                Object node = invoke(callGraph, "getNode", start);
                if (node == null) {
                    summary.add("entry " + start + ": not in the call graph");
                    continue;
                }
                Set<String> reachable = new HashSet<>();
                Set<String> reachableAsync = new TreeSet<>();
                ArrayDeque<Object> pending = new ArrayDeque<>();
                pending.add(node);
                reachable.add(String.valueOf(start));
                while (!pending.isEmpty()) {
                    Object current = pending.poll();
                    String currentName = String.valueOf(invoke(current, "getMethod"));
                    if (async.contains(currentName)) {
                        reachableAsync.add(currentName);
                    }
                    for (Object site : (Collection<?>) invoke(current, "getCallSites")) {
                        for (Object callee : (Collection<?>) invoke(site, "getCalledMethods")) {
                            if (reachable.add(String.valueOf(invoke(callee, "getMethod")))) {
                                pending.add(callee);
                            }
                        }
                    }
                }
                Map<String, Integer> kinds = new TreeMap<>();
                for (String method : reachableAsync) {
                    kinds.merge(rootKind(classSource, rootOf.get(method), kindCache), 1,
                            Integer::sum);
                }
                summary.add("entry " + start + ": reachable=" + reachable.size()
                        + " reachableAsync=" + reachableAsync.size() + " byRootKind=" + kinds
                        + " entryAsync=" + async.contains(String.valueOf(start)));
                lines.add("# entry " + start + " reachableAsync=" + reachableAsync.size());
                for (String method : reachableAsync) {
                    lines.add("R\t" + start + "\t" + method);
                }
            }
        }
        for (String method : async) {
            lines.add("A\t" + method + "\t" + rootKind(classSource, rootOf.get(method), kindCache)
                    + "\t" + rootOf.get(method) + "\t" + viaOf.get(method));
        }
        for (String method : familyMembers) {
            lines.add("F\t" + method);
        }
        summary.add("census time " + (System.nanoTime() - started) / 1_000_000 + " ms");
        try {
            Path path = Path.of(asyncDump);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.write(path, lines, StandardCharsets.UTF_8);
            Files.write(Path.of(asyncDump + ".summary.txt"), summary, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not write " + asyncDump, e);
        }
        for (String line : summary) {
            System.out.println("[gaius-teavm] async census: " + line);
        }
    }

    /** {@code async} for an {@code @Async} root, {@code monitor...} for any other root. */
    private static String rootKind(Object classSource, String root, Map<String, String> cache) {
        if (root == null) {
            return "unknown";
        }
        String cached = cache.get(root);
        if (cached != null) {
            return cached;
        }
        String kind = "unknown";
        int paren = root.indexOf('(');
        int dot = paren < 0 ? -1 : root.lastIndexOf('.', paren);
        if (dot >= 0) {
            String className = root.substring(0, dot);
            Object cls = invoke(classSource, "get", className);
            if (cls != null) {
                for (Object method : (Iterable<?>) invoke(cls, "getMethods")) {
                    if (String.valueOf(invoke(method, "getReference")).equals(root)) {
                        if (hasAnnotation(method, "org.teavm.interop.Async")) {
                            kind = "async";
                        } else {
                            kind = hasModifier(method, "SYNCHRONIZED")
                                    ? "monitor-synchronized" : "monitor";
                        }
                        break;
                    }
                }
            }
        }
        cache.put(root, kind);
        return kind;
    }

    private static List<Object> entryMethods(Object classSource, String className, String name) {
        List<Object> result = new ArrayList<>();
        Object cls = invoke(classSource, "get", className);
        if (cls == null) {
            return result;
        }
        for (Object method : (Iterable<?>) invoke(cls, "getMethods")) {
            if (name.equals(invoke(method, "getName"))) {
                result.add(invoke(method, "getReference"));
            }
        }
        return result;
    }

    private static Object field(Object target, String name) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot read " + name + " of "
                        + target.getClass(), e);
            }
        }
        throw new IllegalStateException("TeaVM API changed: no field " + name + " in "
                + target.getClass().getName());
    }

    /** Exception classes whose catch clauses {@link #wasmCatchBranch} dropped (logged once). */
    private static final Set<String> droppedWasmCatchTypes = new TreeSet<>();

    /**
     * Patched {@code WasmGCInstructionGenerationVisitor.visit(TryCatchStatement)} (WebAssembly GC
     * only): the {@code br_on_cast} of one catch clause. TeaVM 0.15 gives a class that is not
     * in the reachable class source (named by a catch clause but missing from the class path,
     * so never instantiated) a struct type without its supertype chain, and the cast from
     * Throwable to it fails validation ("invalid types for br_on_cast"). No thrown object can
     * have such a class, so the clause is dropped: its handler stays in the code but nothing
     * branches to it. The
     * struct (which has the fields of java.lang.Object) is declared a subtype of the Object
     * struct, so the handler, which stores the exception in a local, still validates.
     *
     * <p>This is the fallback behind {@link #wasmDropUnreachableCatches}, which already removes
     * those clauses from the program (the 26.3 client and server builds never reach this path).
     * It is fail-closed: a clause is only dropped when {@code exceptionType} is absent from the
     * reachable class source (the same view as {@link #wasmDropUnreachableCatches}). A reachable
     * class whose struct lost its supertype chain would be a different TeaVM defect, and
     * dropping its clause would let the exception escape the handler that catches it on the JVM,
     * so the build fails instead.
     *
     * @param visitor the WasmGCInstructionGenerationVisitor (its generation context holds the
     *     class hierarchy)
     * @param exceptionType the Java class name of the clause (java.lang.Throwable for a
     *     catch-all clause)
     */
    public static synchronized Object wasmCatchBranch(Object builder, Object condition, Object from,
            Object to, Object target, Object visitor, String exceptionType) {
        Object fromType = compositeOf(from);
        Object toType = compositeOf(to);
        if (fromType != null && toType != null && !isWasmSupertype(fromType, toType)) {
            Object classSource = invoke(invoke(fieldValue(visitor, "context"), "hierarchy"),
                    "getClassSource");
            if (exceptionType == null || invoke(classSource, "get", exceptionType) != null) {
                throw new IllegalStateException("Gaius: the WebAssembly GC struct of the reachable "
                        + "exception class " + exceptionType + " (" + invoke(toType, "getName")
                        + ") is not a subtype of " + invoke(fromType, "getName") + ", so its catch "
                        + "clause cannot be compiled; dropping it would change which handler "
                        + "catches the exception. TeaVM lost the supertype chain of a reachable "
                        + "class (GaiusTeaVMOptions.wasmCatchBranch only drops clauses of "
                        + "unreachable classes).");
            }
            Object root = fromType;
            for (Object parent = invoke(root, "getSupertype"); parent != null;
                    parent = invoke(root, "getSupertype")) {
                root = parent;
            }
            if (invoke(toType, "getSupertype") == null && toType != root) {
                invoke(toType, "setSupertype", root);
            }
            String name = String.valueOf(invoke(toType, "getName"));
            if (droppedWasmCatchTypes.add(name)) {
                System.out.println("[gaius-teavm] wasm: dropped the catch clause of unreachable class "
                        + name);
            }
            return builder;
        }
        return invoke(builder, "castBranch", condition, from, to, target);
    }

    /** Methods whose catch clauses of unreachable classes {@link #wasmDropUnreachableCatches} removed. */
    private static int droppedWasmCatchMethods;

    /**
     * Patched {@code WasmGCMethodGenerator.generateRegularMethodBody} (WebAssembly GC only),
     * before the method is decompiled: removes the try/catch edges whose exception class the
     * dependency analysis never reached and deletes the handlers that become unreachable. No
     * thrown object can have such a class, so this is exact; it matters because TeaVM 0.15 maps
     * the handler's exception variable to java.lang.Object while the handler code still passes
     * it where a Throwable is expected, which does not validate.
     *
     * <p>{@code hierarchy.getClassSource()} is the class set after the dependency analysis, not
     * the class path (TeaVM 0.15): {@code TeaVM.build} hands that set to
     * {@code WasmGCTarget.emit}, {@code WasmGCDeclarationsGenerator} wraps it in the
     * ClassHierarchy it passes to WasmGCMethodGenerator, and both pipelines restrict it to the
     * reached classes that exist ({@code TeaVM.link} copies only those;
     * {@code TeaVM$PostProcessingClassHolderSource.get} returns null for any other name).
     * TeaVM's DependencyGraphBuilder links the exception type of every catch clause of a reached
     * method, so a class that exists on the class path is always in that set when a generated
     * method catches it: the null case is a class missing from the class path (the 26.3 builds
     * drop UnsatisfiedLinkError, InvalidKeyException and other classes absent from the TeaVM
     * class library), and no object of it can exist. Subclasses do not change this:
     * {@code DependencyAnalyzer.linkClass} links the superclass and the interfaces of every class
     * it reaches and reports a missing one as a compile error. GaiusTeaVMOptionsWasmTest
     * (port/tools/src/test) pins these points through a TeaVM compile: the hierarchy of the code
     * generation reads null for a class on the class path that nothing references, the catch of
     * a class missing from the class path is dropped, and the catch of a class that only the
     * catch clause names is kept.
     */
    public static synchronized void wasmDropUnreachableCatches(Object generator, Object method) {
        Object program = invoke(method, "getProgram");
        if (program == null) {
            return;
        }
        Object classSource = invoke(fieldValue(generator, "hierarchy"), "getClassSource");
        boolean changed = false;
        for (Object block : (Iterable<?>) invoke(program, "getBasicBlocks")) {
            if (block == null) {
                continue;
            }
            List<?> tryCatches = (List<?>) invoke(block, "getTryCatchBlocks");
            for (int i = tryCatches.size() - 1; i >= 0; i--) {
                Object tryCatch = tryCatches.get(i);
                Object type = invoke(tryCatch, "getExceptionType");
                if (type == null || invoke(classSource, "get", type) != null) {
                    continue;
                }
                Object handler = invoke(tryCatch, "getHandler");
                tryCatches.remove(i);
                boolean stillLinked = false;
                for (Object other : tryCatches) {
                    if (invoke(other, "getHandler") == handler) {
                        stillLinked = true;
                    }
                }
                if (!stillLinked) {
                    invoke(handler, "removeIncomingsFrom", block);
                }
                changed = true;
            }
        }
        if (!changed) {
            return;
        }
        droppedWasmCatchMethods++;
        try {
            Class<?> eliminator = Class.forName("org.teavm.model.optimization.UnreachableBasicBlockEliminator",
                    true, program.getClass().getClassLoader());
            invoke(eliminator.getConstructor().newInstance(), "optimize", program);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("TeaVM API changed: UnreachableBasicBlockEliminator", e);
        }
        if (droppedWasmCatchMethods <= 20) {
            System.out.println("[gaius-teavm] wasm: dropped catch clauses of unreachable classes in "
                    + invoke(method, "getReference"));
        }
    }

    private static Object fieldValue(Object target, String name) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException e) {
                // Declared further up.
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("TeaVM API changed: " + type.getName() + "." + name, e);
            }
        }
        throw new IllegalStateException("TeaVM API changed: no field " + name + " on " + target.getClass());
    }

    /**
     * Patched {@code WasmGCMethodGenerator.generateRegularMethodBody} (WebAssembly GC only),
     * after the body is generated: when every path of a value-returning method returns or
     * throws inside a block, TeaVM 0.15 ends the body with that block, but a wasm validator
     * treats the end of a block, loop, if or try_table as reachable and finds no return value
     * ("expected 1 elements on the stack for fallthru, found 0"). An {@code unreachable} after
     * a block without a result is never executed and makes the body valid.
     *
     * <p>Only that shape is touched: a void function, an empty body, a body whose last
     * instruction is anything but a block, if or try (a {@code return}, a value expression)
     * and a structured instruction that has a result are left as they are. TeaVM generates
     * statements with an empty operand stack between them, so nothing below the final block
     * could have been the return value either: every body this changes was invalid before.
     */
    public static void wasmSealFunctionBody(Object function) {
        Object type = invoke(function, "getType");
        if (invoke(type, "getSingleReturnType") == null) {
            return;
        }
        Object body = invoke(function, "getBody");
        Object last = invoke(body, "getLast");
        if (last == null) {
            return;
        }
        String kind = last.getClass().getSimpleName();
        if (!kind.equals("WasmBlock") && !kind.equals("WasmConditional") && !kind.equals("WasmTry")) {
            return;
        }
        // A structured instruction without a result cannot provide the return value, so the
        // Java method never reaches its end (javac guarantees a return or throw on every path).
        if (invoke(last, "getType") != null) {
            return;
        }
        try {
            Class<?> unreachable = Class.forName("org.teavm.backend.wasm.model.instruction.WasmUnreachable",
                    true, last.getClass().getClassLoader());
            invoke(body, "add", unreachable.getConstructor().newInstance());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("TeaVM API changed: WasmUnreachable", e);
        }
    }

    /** Ids of the class initializers that {@link #wasmWrapMethodBody} guarded, by class name. */
    private static final Map<String, Integer> wasmClinitIds = new HashMap<>();
    private static final Map<String, Method> privateMethods = new HashMap<>();

    /**
     * Patched {@code WasmGCMethodGenerator.generateRegularMethodBody} (WebAssembly GC only):
     * takes the place of its {@code wrapSynchronizedMethod(method, visitor, function, node)}
     * call, which every other method still goes through unchanged.
     *
     * <p>In JSPI mode a class initializer that TeaVM compiles with an initializer pointer (a
     * dynamic initializer: call sites call it through a global, and its body starts with the
     * "erase" that points the global at an empty function, added by
     * {@code addInitializerErase}) gets the JVM's class initialization semantics (design D3).
     * The stock erase at the start lets every other thread that requests the class while the
     * initializer is suspended see it half-initialized. The guard keeps the pointer on the
     * initializer until the body ends and asks TJspiThreadSupport who may run it:
     * <pre>
     *   if (!JspiThreadSupport.clinitEnter(id)) return;   // recursive, or waited for another thread
     *   block $done
     *     block $exn (result exnref)
     *       try_table (catch_all_ref $exn)  body (its returns break to $done)  br $done  end
     *       unreachable
     *     end
     *     erase; JspiThreadSupport.clinitExit(id); throw_ref
     *   end
     *   erase; JspiThreadSupport.clinitExit(id)
     * </pre>
     * {@code id} numbers the guarded classes of the compile. The two erase copies are the stock
     * one, moved, and a second one from addInitializerErase; on an exception the class stays
     * erased, as in the stock code, instead of running its initializer again. JspiThreadSupport's
     * own initializer is left as it is: the guard's callees are exported, and TeaVM starts an
     * exported method with the initialization of its class, so a guarded JspiThreadSupport
     * initializer would call itself (it runs before any thread exists).
     */
    public static synchronized void wasmWrapMethodBody(Object generator, Object method,
            Object visitor, Object function, Object node) {
        if (jspi && "<clinit>".equals(invoke(method, "getName"))
                && !JSPI_SUPPORT_CLASS.equals(invoke(method, "getOwnerName"))
                && Boolean.TRUE.equals(invoke(fieldValue(generator, "classInitInfo"),
                        "isDynamicInitializer", invoke(method, "getOwnerName")))) {
            wasmClinitGuard(generator, method, visitor, function, node);
            return;
        }
        invokePrivate(generator, "wrapSynchronizedMethod", method, visitor, function, node);
    }

    private static void wasmClinitGuard(Object generator, Object method, Object visitor,
            Object function, Object node) {
        String owner = (String) invoke(method, "getOwnerName");
        Integer known = wasmClinitIds.get(owner);
        int id = known != null ? known : wasmClinitIds.size();
        wasmClinitIds.put(owner, id);
        if (wasmClinitIds.size() == 1 && known == null) {
            System.out.println("[gaius-teavm] jspi: class initializers wait for their initializing"
                    + " thread (JVM semantics)");
        }
        ClassLoader loader = function.getClass().getClassLoader();
        String instruction = "org.teavm.backend.wasm.model.instruction.";
        Object body = invoke(function, "getBody");
        if (invoke(body, "isEmpty").equals(Boolean.TRUE)) {
            throw new IllegalStateException("TeaVM API changed: the initializer erase of " + owner
                    + " is not at the start of its body");
        }
        Object normalErase = construct(loader, instruction + "WasmInstructionList");
        invoke(normalErase, "transferFrom", body);
        invokePrivate(generator, "addInitializerErase", method, function);
        Object exceptionalErase = construct(loader, instruction + "WasmInstructionList");
        invoke(exceptionalErase, "transferFrom", body);
        Object enter = invoke(generator, "forStaticMethod",
                supportMethod(loader, "clinitEnter", "BOOLEAN"));
        Object exit = invoke(generator, "forStaticMethod", supportMethod(loader, "clinitExit", "VOID"));

        Object builder = invoke(body, "builder");
        i32Const(builder, id);
        invoke(builder, "call", enter);
        invoke(builder, "intUnary", enumConstant(loader, instruction + "WasmIntType", "INT32"),
                enumConstant(loader, instruction + "WasmIntUnaryOperation", "EQZ"));
        Object skip = invoke(builder, "conditional");
        invoke(invoke(skip, "getThenBlock"), "add", construct(loader, instruction + "WasmReturn"));

        Object done = invoke(builder, "block");
        Object exn = invoke(done, "block", staticValue(loader, "org.teavm.backend.wasm.model.WasmType", "EXN"));
        Object tryTable = invoke(exn, "try_");
        Object doneList = publicField(done, "list");
        Object exnList = publicField(exn, "list");
        try {
            Class<?> clause = Class.forName(instruction + "WasmCatchClause", true, loader);
            @SuppressWarnings("unchecked")
            List<Object> catches = (List<Object>) invoke(tryTable, "getCatches");
            catches.add(clause.getConstructor(Class.forName("org.teavm.backend.wasm.model.WasmTag",
                    true, loader), boolean.class, exnList.getClass()).newInstance(null, true, exnList));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("TeaVM API changed: WasmCatchClause", e);
        }
        invoke(visitor, "setReturnBlock", doneList);
        Object tryBody = invoke(tryTable, "getBody");
        invoke(visitor, "generate", invoke(node, "getBody"), tryBody);
        try {
            invoke(tryBody, "add", Class.forName(instruction + "WasmBreak", true, loader)
                    .getConstructor(doneList.getClass()).newInstance(doneList));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("TeaVM API changed: WasmBreak", e);
        }
        invoke(exn, "unreachable");
        invoke(done, "transferFrom", exceptionalErase);
        i32Const(done, id);
        invoke(done, "call", exit);
        invoke(done, "throw_", (Object) null);
        invoke(builder, "transferFrom", normalErase);
        i32Const(builder, id);
        invoke(builder, "call", exit);
    }

    /** {@code JspiThreadSupport.<name>(int)} returning {@code returnType} (a ValueType constant). */
    private static Object supportMethod(ClassLoader loader, String name, String returnType) {
        try {
            Class<?> valueType = Class.forName("org.teavm.model.ValueType", true, loader);
            Object signature = java.lang.reflect.Array.newInstance(valueType, 2);
            java.lang.reflect.Array.set(signature, 0, valueType.getField("INTEGER").get(null));
            java.lang.reflect.Array.set(signature, 1, valueType.getField(returnType).get(null));
            return Class.forName("org.teavm.model.MethodReference", true, loader)
                    .getConstructor(String.class, String.class, signature.getClass())
                    .newInstance(JSPI_SUPPORT_CLASS, name, signature);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("TeaVM API changed: MethodReference", e);
        }
    }

    private static void i32Const(Object builder, int value) {
        try {
            builder.getClass().getMethod("i32Const", int.class).invoke(builder, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("TeaVM API changed: WasmInstructionBuilder.i32Const", e);
        }
    }

    private static Object construct(ClassLoader loader, String className) {
        try {
            return Class.forName(className, true, loader).getConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("TeaVM API changed: " + className, e);
        }
    }

    private static Object staticValue(ClassLoader loader, String className, String field) {
        try {
            return Class.forName(className, true, loader).getField(field).get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("TeaVM API changed: " + className + "." + field, e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConstant(ClassLoader loader, String className, String name) {
        try {
            return Enum.valueOf((Class<? extends Enum>) Class.forName(className, true, loader), name);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("TeaVM API changed: " + className, e);
        }
    }

    private static Object publicField(Object target, String name) {
        try {
            return target.getClass().getField(name).get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("TeaVM API changed: " + target.getClass().getName()
                    + "." + name, e);
        }
    }

    /** Calls a private method of a TeaVM class, found by name and parameter count. */
    private static Object invokePrivate(Object target, String name, Object... arguments) {
        String key = target.getClass().getName() + "#" + name + "/" + arguments.length;
        Method method = privateMethods.get(key);
        if (method == null) {
            for (Method candidate : target.getClass().getDeclaredMethods()) {
                if (candidate.getName().equals(name) && candidate.getParameterCount() == arguments.length) {
                    if (method != null) {
                        throw new IllegalStateException("TeaVM API changed: " + key + " is overloaded");
                    }
                    method = candidate;
                }
            }
            if (method == null) {
                throw new IllegalStateException("TeaVM API changed: no method " + key);
            }
            method.setAccessible(true);
            privateMethods.put(key, method);
        }
        try {
            return method.invoke(target, arguments);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot call " + key, e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(key + " failed", cause);
        }
    }

    private static Object compositeOf(Object reference) {
        try {
            return reference.getClass().getField("composite").get(reference);
        } catch (NoSuchFieldException e) {
            return null;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("TeaVM API changed: WasmType.CompositeReference", e);
        }
    }

    private static boolean isWasmSupertype(Object supertype, Object type) {
        if (!type.getClass().getSimpleName().equals("WasmStructure")) {
            return true;
        }
        for (Object current = type; current != null; current = invoke(current, "getSupertype")) {
            if (current == supertype) {
                return true;
            }
        }
        return false;
    }

    private static Object invoke(Object target, String name, Object... arguments) {
        Method method = findMethod(target.getClass(), name, arguments);
        try {
            return method.invoke(target, arguments);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot call " + name + " on " + target.getClass(), e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(name + " failed on " + target.getClass(), cause);
        }
    }

    /**
     * Finds a public method through the public interfaces and superclasses of a TeaVM model
     * class (implementation classes are often package-private, so their own Method objects
     * cannot be invoked from here).
     */
    private static Method findMethod(Class<?> type, String name, Object[] arguments) {
        StringBuilder keyBuilder = new StringBuilder(type.getName()).append('#').append(name);
        for (Object argument : arguments) {
            keyBuilder.append('/').append(argument == null ? "null" : argument.getClass().getName());
        }
        String key = keyBuilder.toString();
        Method cached = reflectionCache.get(key);
        if (cached != null) {
            return cached;
        }
        ArrayDeque<Class<?>> pending = new ArrayDeque<>();
        Set<Class<?>> visited = new HashSet<>();
        pending.add(type);
        Method found = null;
        while (!pending.isEmpty() && found == null) {
            Class<?> current = pending.poll();
            if (!visited.add(current)) {
                continue;
            }
            if (Modifier.isPublic(current.getModifiers())) {
                for (Method candidate : current.getMethods()) {
                    if (candidate.getName().equals(name)
                            && Modifier.isPublic(candidate.getDeclaringClass().getModifiers())
                            && accepts(candidate, arguments)) {
                        found = candidate;
                        break;
                    }
                }
            }
            if (current.getSuperclass() != null) {
                pending.add(current.getSuperclass());
            }
            pending.addAll(Arrays.asList(current.getInterfaces()));
        }
        if (found == null) {
            throw new IllegalStateException("TeaVM API changed: no public " + name + " accepting "
                    + key + " on " + type.getName());
        }
        reflectionCache.put(key, found);
        return found;
    }

    private static boolean accepts(Method method, Object[] arguments) {
        Class<?>[] parameters = method.getParameterTypes();
        if (parameters.length != arguments.length) {
            return false;
        }
        for (int index = 0; index < parameters.length; index++) {
            if (parameters[index].isPrimitive()
                    || (arguments[index] != null && !parameters[index].isInstance(arguments[index]))) {
                return false;
            }
        }
        return true;
    }
}
