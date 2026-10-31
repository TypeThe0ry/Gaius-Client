package dev.gaius.tools;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.CodeSource;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Records the outcome of every browser patch call so that no patch can be skipped silently
 * (gate G4 of the 26.3 migration plan), and gives each patch a kind, build targets and profiles
 * (patchkit step 1).
 *
 * <h2>Registration</h2>
 * A patcher {@code main} calls {@link #configureProfile} and {@link #configureOutput} and then
 * registers every patch as {@code PatchRegistry.run("<Class>.<method>", "<spec>", () -> ...)}.
 * The spec is the patch's metadata, whitespace-separated: a {@link Kind} first, then optionally
 * the TeaVM targets it applies to ({@code js}, {@code wasmgc}; both when none is named) and the
 * profiles it applies to: {@code *} (the default), a profile id ({@code 26.3}), a bound
 * ({@code >=26.2}, {@code <=26.2}) or a comma-separated union of these. Bounds compare the
 * leading dotted numbers of a profile id, so {@code >=26.3} also covers {@code 26.4-snapshot-2}.
 * Examples: {@code "REQUIRED"}, {@code "PERF js"}, {@code "REQUIRED wasmgc"},
 * {@code "FEATURE >=26.2"}, {@code "REQUIRED 1.21.11"}.
 *
 * <h2>Outcomes</h2>
 * Each registered patch ends in exactly one {@link Outcome}:
 * <ul>
 *   <li><b>APPLIED</b> &mdash; the body ran, or a patcher called {@link #applied};</li>
 *   <li><b>NOT_APPLICABLE</b> &mdash; the build target is not one of the patch's targets
 *       ({@code PATCH_TARGET_SKIPPED <id> target=<t>}), the profile is not one of its profiles
 *       ({@code PATCH_NOT_APPLICABLE <id> profile=<p>}), or {@link #dropped} proved that its
 *       original target is absent from the jar ({@code PATCH_DROPPED <id>}). A body that
 *       registers drops (under the run's own id or under sub-ids of its parts) counts as dropped,
 *       not also as applied;</li>
 *   <li><b>DISABLED</b> &mdash; the body of a patch that is not {@link Kind#REQUIRED} failed: the
 *       files it wrote under the output root are restored, {@code PATCH_DISABLED <id> kind=<k>
 *       error=<message>} is printed and the build goes on with the vanilla code;</li>
 *   <li><b>FAILED</b> &mdash; the body of a REQUIRED patch failed: {@code PATCH_FAILED} is
 *       printed and the failure propagates, which fails the build (in check mode the patch is
 *       restored and recorded instead, so every outcome of a chain can be listed);</li>
 *   <li><b>BRINGUP_SKIPPED</b> &mdash; only in bring-up mode, for ids listed in the profile's
 *       bring-up list ({@code BRINGUP_SKIP <id>}).</li>
 * </ul>
 * {@code PATCH_SUMMARY applied=N dropped=N bringupSkipped=N} ends each patcher; the counters
 * {@code targetSkipped}, {@code notApplicable}, {@code disabled} and {@code failed} follow only
 * when they are not zero, so the summary of a build without them keeps its old form.
 *
 * <h2>Inputs (set by the caller)</h2>
 * <ul>
 *   <li><b>Profile id</b>, first match wins:
 *     <ol>
 *       <li>{@link #configureProfile(String)}, called by a patcher {@code main} with its
 *           Minecraft version argument;</li>
 *       <li>system property {@code gaius.profile};</li>
 *       <li>environment variable {@code GAIUS_PROFILE};</li>
 *       <li>environment variable {@code GAIUS_MINECRAFT_VERSION} (exported for every child of
 *           {@code build-overlays.sh} by {@code version-profile.sh}).</li>
 *     </ol>
 *     A {@code configureProfile} value that disagrees with {@code gaius.profile} or
 *     {@code GAIUS_PROFILE} throws. {@code GAIUS_MINECRAFT_VERSION} is only a fallback.</li>
 *   <li><b>Build target</b>: {@link BuildTarget} ({@code gaius.teavm.target} or
 *       {@code GAIUS_TEAVM_TARGET}, js by default).</li>
 *   <li><b>Output root</b>: {@link #configureOutput(Path)}, the directory the patcher writes its
 *       classes to. Only with it can a failed patch be restored (and so disabled), and only with
 *       it does the run log name the files each patch wrote.</li>
 *   <li><b>Bring-up flag</b>: system property {@code gaius.bringup} or environment variable
 *       {@code GAIUS_BRINGUP}; {@code 1}/{@code true}/{@code yes} enable it.</li>
 *   <li><b>Bring-up list</b>: system property {@code gaius.bringup.list} or environment variable
 *       {@code GAIUS_BRINGUP_LIST}. Without either, {@code port/tools/bringup/<profile>.txt} is
 *       looked up in the working directory and its parents, then in the directories above the
 *       location this class was loaded from.</li>
 *   <li><b>Check mode</b>: {@code gaius.patch.check} / {@code GAIUS_PATCH_CHECK}. A failing
 *       REQUIRED patch is restored and recorded as FAILED, and the patcher goes on, so that
 *       {@code port/tools/patchkit/patchkit.mjs check} lists every outcome of a chain.</li>
 *   <li><b>Strict mode</b>: {@code gaius.patch.strict} / {@code GAIUS_PATCH_STRICT}. Every
 *       failure fails the build, whatever the kind (nothing is disabled).</li>
 *   <li><b>Run log</b>: {@code gaius.patch.runlog} / {@code GAIUS_PATCH_RUN_LOG}, a file to which
 *       one JSON object per outcome is appended (id, kind, targets, profiles, outcome, reason,
 *       the files the patch wrote, the targets a drop proved absent, the error). The patch
 *       manifests of {@code port/mc/profiles/<profile>/} are generated from it.</li>
 *   <li><b>Injected failures</b>: {@code gaius.patch.fail} / {@code GAIUS_PATCH_FAIL}, a
 *       comma-separated list of patch ids whose body is replaced by a failure. Only for tests of
 *       the DISABLED path and of the dependencies between patches.</li>
 * </ul>
 *
 * <p>Bring-up mode is active only when the flag is set <em>and</em> the profile is neither
 * {@code 26.2} nor {@code 1.21.11}. For those two profiles the flag is ignored (with a notice on
 * stderr), so their patch output never depends on it.
 *
 * <h2>Bring-up list format</h2>
 * One entry per line: {@code <patchId> | <owner package> | <reason>}. {@code #} starts a
 * comment that runs to the end of the line, and blank lines are ignored; a reason can therefore
 * contain neither {@code #} nor {@code |}. These are the rules of the other readers of the same
 * file ({@code version-profile.sh}, {@code check-version-profile.mjs} and
 * {@code check-build-log-skips.mjs}). {@code patchId} matches
 * {@code [A-Za-z0-9_$][A-Za-z0-9_$.:@-]*} (no {@code /}) and is the id used in logs, e.g.
 * {@code MinecraftClientPatcher.patchGlx}, {@code Minecraft262BrowserPatcher.patchVulkanBackend},
 * {@code step:LwjglSdlBrowserPatcher} or {@code step:LwjglMemoryPatcher@lwjgl} (shell steps,
 * read by build-overlays.sh). The owner is a work package {@code P1}..{@code P9}, optionally
 * with a letter such as {@code P7a}. Duplicate ids, a bad owner, a missing or extra field and an
 * empty reason throw.
 *
 * <h2>Log lines</h2>
 * {@code BRINGUP_SKIP <id>}, {@code PATCH_DROPPED <id>}, {@code PATCH_TARGET_SKIPPED},
 * {@code PATCH_NOT_APPLICABLE}, {@code PATCH_DISABLED}, {@code PATCH_FAILED} and
 * {@code PATCH_SUMMARY} go to stdout ({@code PATCH_APPLIED <id>} too with
 * {@code gaius.patch.verbose} / {@code GAIUS_PATCH_VERBOSE}). Warnings
 * ({@code BRINGUP_UNUSED <id>}, {@code BRINGUP_IGNORED ...}, {@code PATCH_UNDECLARED <id>}) and
 * the pending list printed when a patch fails outside bring-up mode
 * ({@code BRINGUP_PENDING <line>}) go to stderr.
 */
public final class PatchRegistry {
    /** A patch body; the same shape as the {@code patchX(jar, output)} methods. */
    @FunctionalInterface
    public interface IORunnable {
        void run() throws IOException;
    }

    /** One line of a bring-up list. */
    public record BringupEntry(String patchId, String owner, String reason, int line) {
    }

    /** What a failure of the patch means for the build. */
    public enum Kind {
        /** The browser client is broken without it: a failure fails the build. */
        REQUIRED,
        /** A browser feature or default; the vanilla code stays correct without it. */
        FEATURE,
        /** Speed or memory only; the vanilla code stays correct, only slower. */
        PERF,
        /** Diagnostics only. */
        TELEMETRY
    }

    /** How one registered patch ended. */
    public enum Outcome {
        APPLIED,
        NOT_APPLICABLE,
        DISABLED,
        FAILED,
        BRINGUP_SKIPPED
    }

    /**
     * The metadata of one patch: its kind, the TeaVM targets it applies to and the profiles it
     * applies to (see the class comment for the text form).
     */
    public record Spec(Kind kind, List<String> targets, String profiles, boolean notApplicable,
            boolean wholeClass) {
        public Spec(Kind kind, List<String> targets, String profiles) {
            this(kind, targets, profiles, false, false);
        }
        /** Every TeaVM target, in the canonical order. */
        public static final List<String> ALL_TARGETS = List.of(BuildTarget.JS, BuildTarget.WASM_GC);

        public Spec {
            if (kind == null) {
                throw new IllegalArgumentException("a patch spec needs a kind");
            }
            targets = List.copyOf(targets);
            if (targets.isEmpty() || !ALL_TARGETS.containsAll(targets)) {
                throw new IllegalArgumentException("invalid patch targets " + targets);
            }
            if (profiles == null || profiles.isBlank()) {
                throw new IllegalArgumentException("a patch spec needs profiles");
            }
        }

        /** Parses {@code <KIND> [js|wasmgc]... [<profiles>]...}. */
        public static Spec parse(String text) {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("empty patch spec");
            }
            String[] tokens = text.strip().split("\\s+");
            Kind kind;
            try {
                kind = Kind.valueOf(tokens[0]);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("patch spec '" + text
                        + "' must start with REQUIRED, FEATURE, PERF or TELEMETRY");
            }
            Set<String> targets = new LinkedHashSet<>();
            List<String> terms = new ArrayList<>();
            boolean notApplicable = false;
            boolean wholeClass = false;
            for (int index = 1; index < tokens.length; index++) {
                String token = tokens[index];
                if (token.equals("OVERRIDE")) {
                    if (wholeClass) {
                        throw new IllegalArgumentException("duplicate OVERRIDE in " + text);
                    }
                    wholeClass = true;
                    continue;
                }
                if (token.equals("NOT_APPLICABLE")) {
                    if (notApplicable) {
                        throw new IllegalArgumentException("duplicate NOT_APPLICABLE in " + text);
                    }
                    notApplicable = true;
                    continue;
                }
                if (ALL_TARGETS.contains(token)) {
                    if (!targets.add(token)) {
                        throw new IllegalArgumentException("patch spec '" + text
                                + "' names target " + token + " twice");
                    }
                    continue;
                }
                for (String term : token.split(",", -1)) {
                    if (!PROFILE_TERM.matcher(term).matches()) {
                        throw new IllegalArgumentException("patch spec '" + text
                                + "': '" + term + "' is neither a target (js, wasmgc) nor a profile"
                                + " term (*, <id>, >=<id>, <=<id>)");
                    }
                    if (terms.contains(term)) {
                        throw new IllegalArgumentException("patch spec '" + text
                                + "' names profile term " + term + " twice");
                    }
                    terms.add(term);
                }
            }
            if (terms.contains("*") && terms.size() > 1) {
                throw new IllegalArgumentException("patch spec '" + text
                        + "': '*' already covers every profile");
            }
            List<String> ordered = new ArrayList<>();
            for (String target : ALL_TARGETS) {
                if (targets.isEmpty() || targets.contains(target)) {
                    ordered.add(target);
                }
            }
            return new Spec(kind, ordered, terms.isEmpty() ? "*" : String.join(",", terms),
                    notApplicable, wholeClass);
        }

        public boolean hasTarget(String target) {
            return targets.contains(target);
        }

        /** Whether the patch applies to {@code profileId} (an unknown profile is covered). */
        public boolean coversProfile(String profileId) {
            if (profiles.equals("*") || profileId == null) {
                return true;
            }
            int[] version = leadingVersion(profileId);
            if (version == null) {
                return false;
            }
            for (String term : profiles.split(",")) {
                if (term.startsWith(">=")) {
                    if (compareVersions(version, leadingVersion(term.substring(2))) >= 0) {
                        return true;
                    }
                } else if (term.startsWith("<=")) {
                    if (compareVersions(version, leadingVersion(term.substring(2))) <= 0) {
                        return true;
                    }
                } else if (compareVersions(version, leadingVersion(term)) == 0) {
                    return true;
                }
            }
            return false;
        }

        /** The canonical text form: the kind, then targets and profiles when they restrict. */
        public String text() {
            StringBuilder text = new StringBuilder(kind.name());
            if (wholeClass) {
                text.append(" OVERRIDE");
            }
            if (notApplicable) {
                text.append(" NOT_APPLICABLE");
            }
            if (!targets.equals(ALL_TARGETS)) {
                text.append(' ').append(String.join(" ", targets));
            }
            if (!profiles.equals("*")) {
                text.append(' ').append(profiles);
            }
            return text.toString();
        }
    }

    public static final String PROFILE_PROPERTY = "gaius.profile";
    public static final String PROFILE_ENV = "GAIUS_PROFILE";
    public static final String BUILD_PROFILE_ENV = "GAIUS_MINECRAFT_VERSION";
    public static final String BRINGUP_PROPERTY = "gaius.bringup";
    public static final String BRINGUP_ENV = "GAIUS_BRINGUP";
    public static final String BRINGUP_LIST_PROPERTY = "gaius.bringup.list";
    public static final String BRINGUP_LIST_ENV = "GAIUS_BRINGUP_LIST";
    public static final String VERBOSE_PROPERTY = "gaius.patch.verbose";
    public static final String VERBOSE_ENV = "GAIUS_PATCH_VERBOSE";
    public static final String CHECK_PROPERTY = "gaius.patch.check";
    public static final String CHECK_ENV = "GAIUS_PATCH_CHECK";
    public static final String STRICT_PROPERTY = "gaius.patch.strict";
    public static final String STRICT_ENV = "GAIUS_PATCH_STRICT";
    public static final String RUN_LOG_PROPERTY = "gaius.patch.runlog";
    public static final String RUN_LOG_ENV = "GAIUS_PATCH_RUN_LOG";
    public static final String FAIL_PROPERTY = "gaius.patch.fail";
    public static final String FAIL_ENV = "GAIUS_PATCH_FAIL";

    /**
     * A patch id: {@code ^[A-Za-z0-9_$][A-Za-z0-9_$.:@-]*$}, the rule of every reader of a
     * bring-up list ({@code version-profile.sh}, {@code check-version-profile.mjs},
     * {@code check-build-log-skips.mjs}).
     */
    private static final Pattern PATCH_ID = Pattern.compile("[A-Za-z0-9_$][A-Za-z0-9_$.:@-]*");
    private static final Pattern OWNER = Pattern.compile("P[1-9][a-z]?");
    private static final Pattern PROFILE_TERM = Pattern.compile("\\*|(?:>=|<=)?[0-9]+(?:\\.[0-9]+)*");
    private static final Pattern LEADING_VERSION = Pattern.compile("^([0-9]+(?:\\.[0-9]+)*)");
    /** The metadata of a patch registered without a spec: the old behaviour, REQUIRED everywhere. */
    private static final Spec UNDECLARED = new Spec(Kind.REQUIRED, Spec.ALL_TARGETS, "*");

    private static String configuredProfile;
    private static boolean resolved;
    private static String profile;
    private static boolean bringupActive;
    private static Path bringupList;
    private static Map<String, BringupEntry> bringupEntries = Map.of();
    private static final Set<String> queried = new LinkedHashSet<>();
    private static final Set<String> scopes = new LinkedHashSet<>();
    private static final Map<String, Spec> specs = new HashMap<>();
    private static final Set<String> undeclaredWarned = new LinkedHashSet<>();
    private static int applied;
    private static int dropped;
    private static int bringupSkipped;
    private static int targetSkipped;
    private static int notApplicable;
    private static int disabled;
    private static int failed;
    private static boolean pendingPrinted;
    private static OutputTracker output;
    private static PatchTargets targetFingerprints;
    /** The {@link #run} bodies executing on this thread, innermost last. */
    private static final ThreadLocal<ArrayDeque<Frame>> FRAMES =
            ThreadLocal.withInitial(ArrayDeque::new);
    /** Optional failure injected by a patcher-wide preflight (for example symbol probing).
     *  It is deliberately consumed by every applicable registration so check mode records a
     *  complete manifest instead of crashing before the first patch body. */
    private static volatile String preflightFailure;

    public static synchronized void configurePreflightFailure(String reason) {
        preflightFailure = reason == null || reason.isBlank() ? null : reason;
    }

    private PatchRegistry() {
    }

    /**
     * Sets the profile from a patcher's version argument. Must be called before the first patch
     * call; a later call with a different value throws.
     */
    public static synchronized void configureProfile(String profileId) {
        if (profileId == null || profileId.isBlank()) {
            throw new IllegalArgumentException("profile id must not be empty");
        }
        if (configuredProfile != null && !configuredProfile.equals(profileId)) {
            throw new IllegalStateException("PatchRegistry profile already configured as "
                    + configuredProfile + ", refusing " + profileId);
        }
        if (resolved && !profileId.equals(profile)) {
            throw new IllegalStateException("PatchRegistry already resolved profile " + profile
                    + " before configureProfile(" + profileId + ")");
        }
        for (String[] source : new String[][] {
                {"system property " + PROFILE_PROPERTY, System.getProperty(PROFILE_PROPERTY)},
                {"environment variable " + PROFILE_ENV, System.getenv(PROFILE_ENV)}}) {
            String value = source[1];
            if (value != null && !value.isBlank() && !value.trim().equals(profileId)) {
                throw new IllegalStateException("Patcher profile " + profileId
                        + " disagrees with " + source[0] + "=" + value.trim());
            }
        }
        configuredProfile = profileId;
    }

    /**
     * Names the directory the patcher writes its classes to. Each registered patch body is then
     * tracked: a failed body's files are restored to their state before it ran, and the run log
     * names the files each patch wrote. Files written outside this directory are not tracked.
     */
    public static synchronized void configureOutput(Path root) throws IOException {
        if (root == null) {
            throw new IllegalArgumentException("output root must not be null");
        }
        Path absolute = root.toAbsolutePath().normalize();
        if (output != null) {
            if (!output.root.equals(absolute)) {
                throw new IllegalStateException("PatchRegistry output already configured as "
                        + output.root + ", refusing " + absolute);
            }
            return;
        }
        if (!FRAMES.get().isEmpty()) {
            throw new IllegalStateException("configureOutput inside a patch body");
        }
        output = new OutputTracker(absolute);
        String vanillaJar = System.getProperty("gaius.patch.vanilla");
        String inputJar = System.getProperty("gaius.patch.input");
        if (vanillaJar != null && inputJar != null) {
            targetFingerprints = new PatchTargets(hostPath(vanillaJar), hostPath(inputJar));
        }
    }

    /**
     * The profile id the run names (configureProfile, {@value #PROFILE_PROPERTY},
     * {@value #PROFILE_ENV}, {@value #BUILD_PROFILE_ENV}) without resolving the registry, or
     * {@code null}; for a patcher main that has no version argument.
     */
    public static synchronized String requestedProfile() {
        return firstNonBlank(configuredProfile, System.getProperty(PROFILE_PROPERTY),
                System.getenv(PROFILE_ENV), System.getenv(BUILD_PROFILE_ENV));
    }

    /** The resolved profile id, or {@code null} when no source provides one. */
    public static synchronized String profile() {
        resolve();
        return profile;
    }

    /** Whether bring-up skipping is active for this JVM. */
    public static synchronized boolean bringupActive() {
        resolve();
        return bringupActive;
    }

    /** The bring-up list that was loaded, or {@code null}. */
    public static synchronized Path bringupList() {
        resolve();
        return bringupList;
    }

    /** The loaded bring-up entries in file order (empty unless a list was loaded). */
    public static synchronized List<BringupEntry> bringupEntries() {
        resolve();
        return List.copyOf(bringupEntries.values());
    }

    /**
     * Returns {@code true} only in bring-up mode for ids listed in the bring-up list, and then
     * prints {@code BRINGUP_SKIP <id>} and counts the skip. The caller must not run the patch.
     */
    public static synchronized boolean bringupSkip(String patchId) {
        requirePatchId(patchId);
        resolve();
        queried.add(patchId);
        scopes.add(scopeOf(patchId));
        if (!bringupActive || !bringupEntries.containsKey(patchId)) {
            return false;
        }
        bringupSkipped++;
        System.out.println("BRINGUP_SKIP " + patchId);
        return true;
    }

    /**
     * Registers a patch as dropped for this jar. Every entry must be absent, otherwise this
     * throws. An entry is either a jar entry name ({@code com/x/Y.class}; a class name without
     * the {@code .class} suffix is accepted too) or a member of a class:
     * {@code owner#name} (no method or field with that name) or {@code owner#name(desc)ret}
     * (no method with that exact descriptor). A member is absent when its class is absent.
     */
    public static synchronized void dropped(
            String patchId, String jar, String... entriesThatMustBeAbsent) throws IOException {
        requirePatchId(patchId);
        if (entriesThatMustBeAbsent == null || entriesThatMustBeAbsent.length == 0) {
            throw new IllegalArgumentException(patchId
                    + ": a dropped patch must name at least one target that must be absent");
        }
        resolve();
        queried.add(patchId);
        scopes.add(scopeOf(patchId));
        List<String> present = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar)) {
            for (String entry : entriesThatMustBeAbsent) {
                if (entry == null || entry.isBlank()) {
                    throw new IllegalArgumentException(patchId + ": empty dropped target");
                }
                if (targetPresent(zip, entry)) {
                    present.add(entry);
                }
            }
        }
        if (!present.isEmpty()) {
            throw new IllegalStateException("Patch " + patchId
                    + " is registered as dropped, but its target is still present in " + jar
                    + ": " + String.join(", ", present));
        }
        dropped++;
        List<String> absent = List.of(entriesThatMustBeAbsent);
        Frame enclosing = FRAMES.get().peekLast();
        if (enclosing == null) {
            record(patchId, null, false, Outcome.NOT_APPLICABLE, "dropped", null, null, absent,
                    null, null);
        } else {
            enclosing.drops++;
            if (patchId.equals(enclosing.id)) {
                enclosing.absent.addAll(absent);
            } else {
                enclosing.parts.add(patchId);
                record(patchId, enclosing.spec, enclosing.undeclared, Outcome.NOT_APPLICABLE,
                        "dropped", enclosing.id, null, absent, null, null);
            }
        }
        System.out.println("PATCH_DROPPED " + patchId);
    }

    /** Records that a patch was applied (for patchers that do not use {@link #run}). */
    public static synchronized void applied(String patchId) {
        requirePatchId(patchId);
        resolve();
        queried.add(patchId);
        scopes.add(scopeOf(patchId));
        applied++;
        if (verbose()) {
            System.out.println("PATCH_APPLIED " + patchId);
        }
        Frame enclosing = FRAMES.get().peekLast();
        record(patchId, enclosing == null ? null : enclosing.spec,
                enclosing != null && enclosing.undeclared, Outcome.APPLIED, null,
                enclosing == null ? null : enclosing.id, null, null, null, null);
    }

    /**
     * Runs one patch with its metadata ({@code spec}, see the class comment). The patch is not
     * applicable for a build target or profile outside the spec, and skipped when
     * {@link #bringupSkip} says so; otherwise the body runs and the patch ends as applied,
     * unless the body registered its target (or the targets of all its parts) as
     * {@link #dropped}: then it ends as dropped only. A failing body of a REQUIRED patch fails
     * the build (when a profile has a bring-up list, its unfinished patches are printed to
     * stderr first); a failing body of another kind is restored and disabled, see the class
     * comment.
     */
    public static void run(String patchId, String spec, IORunnable body) throws IOException {
        requirePatchId(patchId);
        runPatch(patchId, specFor(spec), false, false, null, body);
    }

    public static void runPart(String patchId, String spec, IORunnable body) throws IOException {
        requirePatchId(patchId);
        runPatch(patchId, specFor(spec), false, true, null, body);
    }

    public static void runPart(String patchId, String spec, ClassNode node, IORunnable body)
            throws IOException {
        requirePatchId(patchId);
        runPatch(patchId, specFor(spec), false, true, node, body);
    }

    /**
     * {@link #run(String, String, IORunnable)} without metadata: the patch is treated as
     * REQUIRED for every target and profile, and {@code PATCH_UNDECLARED <id>} is printed to
     * stderr. Registrations of {@code port/tools} always name a spec.
     *
     * @deprecated declare the kind, targets and profiles of the patch
     */
    @Deprecated
    public static void run(String patchId, IORunnable body) throws IOException {
        requirePatchId(patchId);
        synchronized (PatchRegistry.class) {
            if (undeclaredWarned.add(patchId)) {
                System.err.println("PATCH_UNDECLARED " + patchId);
            }
        }
        runPatch(patchId, UNDECLARED, true, false, null, body);
    }

    private static void runPatch(String patchId, Spec spec, boolean undeclared, boolean part,
            ClassNode node, IORunnable body)
            throws IOException {
        if (body == null) {
            throw new IllegalArgumentException(patchId + ": null patch body");
        }
        String target = BuildTarget.name();
        if (!spec.hasTarget(target)) {
            targetSkipped(patchId, spec, undeclared, target);
            return;
        }
        String currentProfile = profile();
        if (!spec.coversProfile(currentProfile)) {
            profileNotApplicable(patchId, spec, undeclared, currentProfile);
            return;
        }
        if (spec.notApplicable()) {
            String reason = VersionProfile.forId(currentProfile).patchNotApplicable(patchId, target);
            if (reason != null) {
                synchronized (PatchRegistry.class) {
                    queried.add(patchId);
                    scopes.add(scopeOf(patchId));
                    if (!part) {
                        applied++;
                    }
                    System.out.println("PATCH_NOT_APPLICABLE " + patchId + " profile="
                            + currentProfile + " summary=" + (part ? "part" : "applied"));
                    record(patchId, spec, undeclared, Outcome.NOT_APPLICABLE, reason,
                            null, null, null, null, null);
                }
                return;
            }
        }
        if (bringupSkip(patchId)) {
            synchronized (PatchRegistry.class) {
                record(patchId, spec, undeclared, Outcome.BRINGUP_SKIPPED, "bring-up list", null,
                        null, null, null, null);
            }
            return;
        }
        Frame frame = openFrame(patchId, spec, undeclared);
        frame.part = part;
        frame.node = node;
        if (node != null) {
            frame.originalNode = new ClassNode();
            node.accept(frame.originalNode);
        }
        Throwable failure = null;
        try {
            if (injectedFailure(patchId)) {
                throw new IllegalStateException("injected failure (" + FAIL_PROPERTY + ")");
            }
            if (preflightFailure != null) {
                throw new IllegalStateException(preflightFailure);
            }
            body.run();
        } catch (IOException | RuntimeException | AssertionError caught) {
            failure = caught;
        } catch (Error fatal) {
            synchronized (PatchRegistry.class) {
                FRAMES.get().remove(frame);
            }
            throw fatal;
        }
        if (failure != null) {
            fail(frame, failure);
            return;
        }
        try {
            succeed(frame);
        } catch (IOException | RuntimeException fingerprintFailure) {
            fail(frame, fingerprintFailure);
        }
    }

    private static synchronized Spec specFor(String text) {
        Spec spec = specs.get(text);
        if (spec == null) {
            spec = Spec.parse(text);
            specs.put(text, spec);
        }
        return spec;
    }

    private static synchronized Frame openFrame(String patchId, Spec spec, boolean undeclared)
            throws IOException {
        ArrayDeque<Frame> frames = FRAMES.get();
        if (tracked(spec)) {
            output.refresh(frames);
        }
        Frame frame = new Frame(patchId, spec, undeclared);
        frames.addLast(frame);
        return frame;
    }

    /**
     * Whether the files of a patch body are tracked: always when it can be restored (not
     * REQUIRED, or check mode) or when a run log names them; a REQUIRED body of a normal build
     * is not (its failure fails the build anyway), which keeps the overhead to the patches that
     * can be disabled. The changes of an untracked body are taken in by the next refresh.
     */
    private static boolean tracked(Spec spec) {
        return output != null && (spec.kind() != Kind.REQUIRED || truthy(CHECK_PROPERTY, CHECK_ENV)
                || firstNonBlank(System.getProperty(RUN_LOG_PROPERTY), System.getenv(RUN_LOG_ENV))
                        != null);
    }

    private static synchronized void succeed(Frame frame) throws IOException {
        ArrayDeque<Frame> frames = FRAMES.get();
        boolean tracked = tracked(frame.spec);
        if (tracked) {
            output.refresh(frames);
        }
        if (targetFingerprints != null && tracked) {
            frame.methodTargets.addAll(targetFingerprints.changed(output.root, frame.before,
                    frame.spec.wholeClass()));
            if (frame.originalNode != null) {
                frame.methodTargets.addAll(targetFingerprints.changed(frame.originalNode, frame.node));
            }
        }
        frames.remove(frame);
        queried.add(frame.id);
        scopes.add(scopeOf(frame.id));
        Collection<String> classes = tracked ? frame.before.keySet() : null;
        if (frame.drops > 0) {
            record(frame.id, frame.spec, frame.undeclared, Outcome.NOT_APPLICABLE, "dropped", null,
                    classes, frame.absent, frame.parts, null, frame.methodTargets);
            return;
        }
        if (!frame.part) {
            applied++;
        }
        if (verbose()) {
            System.out.println("PATCH_APPLIED " + frame.id);
        }
        record(frame.id, frame.spec, frame.undeclared, Outcome.APPLIED, null, null, classes, null,
                null, null, frame.methodTargets);
    }

    private static void fail(Frame frame, Throwable failure) throws IOException {
        boolean disable = frame.spec.kind() != Kind.REQUIRED && !truthy(STRICT_PROPERTY, STRICT_ENV);
        boolean check = truthy(CHECK_PROPERTY, CHECK_ENV);
        String error = oneLine(failure);
        synchronized (PatchRegistry.class) {
            ArrayDeque<Frame> frames = FRAMES.get();
            if ((disable || check) && output != null) {
                output.refresh(frames);
                Collection<String> restored = new TreeSet<>(frame.before.keySet());
                output.restore(frame);
                if (frame.originalNode != null) {
                    PatchTargets.restoreNode(frame.originalNode, frame.node);
                }
                frames.remove(frame);
                queried.add(frame.id);
                scopes.add(scopeOf(frame.id));
                Outcome outcome = disable ? Outcome.DISABLED : Outcome.FAILED;
                if (!frame.part) {
                    if (disable) {
                        disabled++;
                    } else {
                        failed++;
                    }
                }
                System.out.println("PATCH_" + outcome.name() + " " + frame.id + " kind="
                        + frame.spec.kind().name() + (frame.part ? " summary=part" : "")
                        + " error=" + error);
                if (check || verbose()) {
                    failure.printStackTrace();
                }
                record(frame.id, frame.spec, frame.undeclared, outcome, "restored", null, restored,
                        frame.absent, frame.parts, error);
                if (frame.part && frame.spec.kind() == Kind.REQUIRED) {
                    throw new IOException("Required patch part failed: " + frame.id, failure);
                }
                return;
            }
            boolean tracked = tracked(frame.spec);
            if (tracked) {
                output.refresh(frames);
            }
            frames.remove(frame);
            queried.add(frame.id);
            scopes.add(scopeOf(frame.id));
            if (!frame.part) {
                failed++;
            }
            System.out.println("PATCH_FAILED " + frame.id + " kind=" + frame.spec.kind().name()
                    + (frame.part ? " summary=part" : "") + " error=" + error);
            if (disable) {
                System.err.println("Patch " + frame.id + " (" + frame.spec.kind().name()
                        + ") cannot be disabled: its patcher did not call"
                        + " PatchRegistry.configureOutput, so its files cannot be restored");
            }
            record(frame.id, frame.spec, frame.undeclared, Outcome.FAILED, null, null,
                    tracked ? frame.before.keySet() : null, frame.absent, frame.parts, error);
        }
        printPendingOnFailure(frame.id);
        if (failure instanceof IOException io) {
            throw io;
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        throw (AssertionError) failure;
    }

    private static synchronized void targetSkipped(
            String patchId, Spec spec, boolean undeclared, String target) {
        resolve();
        queried.add(patchId);
        scopes.add(scopeOf(patchId));
        targetSkipped++;
        System.out.println("PATCH_TARGET_SKIPPED " + patchId + " target=" + target);
        record(patchId, spec, undeclared, Outcome.NOT_APPLICABLE, "target", null, null, null, null,
                null);
    }

    private static synchronized void profileNotApplicable(
            String patchId, Spec spec, boolean undeclared, String currentProfile) {
        queried.add(patchId);
        scopes.add(scopeOf(patchId));
        notApplicable++;
        System.out.println("PATCH_NOT_APPLICABLE " + patchId + " profile=" + currentProfile);
        record(patchId, spec, undeclared, Outcome.NOT_APPLICABLE, "profile", null, null, null, null,
                null);
    }

    /**
     * Prints {@code PATCH_SUMMARY applied=N dropped=N bringupSkipped=N} (followed by the
     * counters of the other outcomes that are not zero) to stdout and, in bring-up mode, warns
     * on stderr about listed ids of the patchers seen in this JVM that were never reached
     * ({@code BRINGUP_UNUSED <id>}).
     */
    public static synchronized void printSummary() {
        preflightFailure = null;
        resolve();
        if (bringupActive) {
            for (String id : bringupEntries.keySet()) {
                if (!queried.contains(id) && scopes.contains(scopeOf(id))) {
                    System.err.println("BRINGUP_UNUSED " + id);
                }
            }
        }
        System.out.println("PATCH_SUMMARY applied=" + applied + " dropped=" + dropped
                + " bringupSkipped=" + bringupSkipped
                + (targetSkipped > 0 ? " targetSkipped=" + targetSkipped : "")
                + (notApplicable > 0 ? " notApplicable=" + notApplicable : "")
                + (disabled > 0 ? " disabled=" + disabled : "")
                + (failed > 0 ? " failed=" + failed : ""));
    }

    /** Parses a bring-up list. Exposed for smokes and for build scripts written in Java. */
    public static Map<String, BringupEntry> parseBringupList(Path file) throws IOException {
        Map<String, BringupEntry> entries = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            int comment = line.indexOf('#');
            if (comment >= 0) {
                line = line.substring(0, comment);
            }
            line = line.strip();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\\|", -1);
            String where = file + ":" + (index + 1);
            if (parts.length != 3) {
                throw new IllegalStateException(where
                        + ": expected '<patchId> | <owner> | <reason>', got: " + line);
            }
            String id = parts[0].strip();
            String owner = parts[1].strip();
            String reason = parts[2].strip();
            if (!PATCH_ID.matcher(id).matches()) {
                throw new IllegalStateException(where + ": invalid patch id '" + id + "'");
            }
            if (!OWNER.matcher(owner).matches()) {
                throw new IllegalStateException(where + ": invalid owner package '" + owner
                        + "' (expected P1..P9, optionally with a letter suffix such as P7a)");
            }
            if (reason.isEmpty()) {
                throw new IllegalStateException(where + ": missing reason for " + id);
            }
            BringupEntry previous = entries.putIfAbsent(
                    id, new BringupEntry(id, owner, reason, index + 1));
            if (previous != null) {
                throw new IllegalStateException(where + ": duplicate bring-up entry " + id
                        + " (first on line " + previous.line() + ")");
            }
        }
        return Collections.unmodifiableMap(entries);
    }

    /**
     * Whether the given profile id may ever use bring-up mode: its version profile allows it
     * ({@code bringupAllowed}). An unknown or missing profile never does.
     */
    public static boolean bringupEligible(String profileId) {
        if (profileId == null || profileId.isBlank()) {
            return false;
        }
        try {
            return VersionProfile.forId(profileId).bringupAllowed();
        } catch (RuntimeException unknownProfile) {
            return false;
        }
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        profile = firstNonBlank(configuredProfile, System.getProperty(PROFILE_PROPERTY),
                System.getenv(PROFILE_ENV), System.getenv(BUILD_PROFILE_ENV));
        boolean flag = truthy(BRINGUP_PROPERTY, BRINGUP_ENV);
        Path explicitList = pathOrNull(firstNonBlank(
                System.getProperty(BRINGUP_LIST_PROPERTY), System.getenv(BRINGUP_LIST_ENV)));
        if (flag && !bringupEligible(profile)) {
            System.err.println("BRINGUP_IGNORED profile=" + profile
                    + " (bring-up mode applies only to a profile whose bringupAllowed is true)");
        }
        bringupActive = flag && bringupEligible(profile);
        Path list = explicitList;
        if (list == null && bringupEligible(profile)) {
            list = locateBringupList(profile);
        }
        try {
            if (bringupActive) {
                if (list == null || !Files.isRegularFile(list)) {
                    throw new IllegalStateException("Bring-up mode for profile " + profile
                            + " needs port/tools/bringup/" + profile + ".txt (or "
                            + BRINGUP_LIST_ENV + "); not found"
                            + (list == null ? "" : ": " + list));
                }
                bringupList = list;
                bringupEntries = parseBringupList(list);
            } else if (list != null && Files.isRegularFile(list) && bringupEligible(profile)) {
                // Loaded only to explain failures; never used to skip anything.
                bringupList = list;
                bringupEntries = parseBringupList(list);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read bring-up list " + list, exception);
        }
        resolved = true;
    }

    private static void printPendingOnFailure(String failedId) {
        String currentProfile;
        Path list;
        Map<String, BringupEntry> entries;
        synchronized (PatchRegistry.class) {
            resolve();
            if (bringupActive) {
                // Listed ids never run, so a failure in bring-up mode is always unlisted.
                System.err.println("Patch " + failedId + " failed in bring-up mode for profile "
                        + profile + " and is not listed in " + bringupList);
                return;
            }
            if (pendingPrinted || bringupEntries.isEmpty()) {
                return;
            }
            pendingPrinted = true;
            currentProfile = profile;
            list = bringupList;
            entries = bringupEntries;
        }
        BringupEntry failedEntry = entries.get(failedId);
        System.err.println("Patch " + failedId + " failed for profile " + currentProfile
                + (failedEntry == null ? " and is not in the bring-up list"
                        : " (bring-up owner " + failedEntry.owner() + ": " + failedEntry.reason()
                                + ")")
                + ". Unfinished patches listed in " + list + " (set " + BRINGUP_ENV
                + "=1 to skip them during bring-up):");
        for (BringupEntry entry : entries.values()) {
            System.err.println("BRINGUP_PENDING " + entry.patchId() + " | " + entry.owner()
                    + " | " + entry.reason());
        }
    }

    private static boolean injectedFailure(String patchId) {
        String value = firstNonBlank(System.getProperty(FAIL_PROPERTY), System.getenv(FAIL_ENV));
        if (value == null) {
            return false;
        }
        for (String id : value.split(",")) {
            if (id.strip().equals(patchId)) {
                return true;
            }
        }
        return false;
    }

    /** Appends one outcome to the run log, when one is configured. */
    private static void record(String id, Spec spec, boolean undeclared, Outcome outcome,
            String reason, String parent, Collection<String> classes, Collection<String> absent,
            Collection<String> parts, String error) {
        record(id, spec, undeclared, outcome, reason, parent, classes, absent, parts, error, null);
    }

    private static void record(String id, Spec spec, boolean undeclared, Outcome outcome,
            String reason, String parent, Collection<String> classes, Collection<String> absent,
            Collection<String> parts, String error, List<Map<String, Object>> methodTargets) {
        String path = firstNonBlank(System.getProperty(RUN_LOG_PROPERTY), System.getenv(RUN_LOG_ENV));
        if (path == null) {
            return;
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("patcher", mainClass());
        fields.put("id", id);
        fields.put("kind", spec == null ? null : spec.kind().name());
        fields.put("targets", spec == null ? null : spec.targets());
        fields.put("profiles", spec == null ? null : spec.profiles());
        fields.put("notApplicableDeclared", spec != null && spec.notApplicable() ? true : null);
        fields.put("wholeClass", spec != null && spec.wholeClass() ? true : null);
        fields.put("methodTargets", methodTargets);
        fields.put("outcome", outcome.name());
        fields.put("reason", reason);
        fields.put("parent", parent);
        fields.put("classes", classes == null ? null : new TreeSet<>(classes));
        fields.put("absent", absent == null || absent.isEmpty() ? null : new ArrayList<>(absent));
        fields.put("parts", parts == null || parts.isEmpty() ? null : new ArrayList<>(parts));
        fields.put("error", error);
        fields.put("undeclared", undeclared ? Boolean.TRUE : null);
        fields.put("profile", profile);
        fields.put("target", BuildTarget.name());
        StringBuilder line = new StringBuilder("{");
        for (Map.Entry<String, Object> field : fields.entrySet()) {
            if (field.getValue() == null) {
                continue;
            }
            if (line.length() > 1) {
                line.append(',');
            }
            appendJson(line, field.getKey());
            line.append(':');
            appendJson(line, field.getValue());
        }
        line.append("}\n");
        try {
            Files.writeString(hostPath(path), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot append to the patch run log " + path, exception);
        }
    }

    private static void appendJson(StringBuilder out, Object value) {
        if (value instanceof Map<?, ?> items) {
            out.append('{');
            boolean first = true;
            for (var item : items.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                appendJson(out, item.getKey());
                out.append(':');
                appendJson(out, item.getValue());
            }
            out.append('}');
            return;
        }
        if (value instanceof Boolean flag) {
            out.append(flag.booleanValue());
            return;
        }
        if (value instanceof Collection<?> items) {
            out.append('[');
            boolean first = true;
            for (Object item : items) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                appendJson(out, item);
            }
            out.append(']');
            return;
        }
        String text = String.valueOf(value);
        out.append('"');
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        out.append('"');
    }

    private static String mainClass() {
        String command = System.getProperty("sun.java.command");
        if (command == null || command.isBlank()) {
            return null;
        }
        String main = command.strip().split("\\s+")[0];
        return main.substring(main.lastIndexOf('.') + 1);
    }

    /** The failure as one log line: class, message and the causes. */
    private static String oneLine(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (text.length() > 0) {
                text.append(" <- ");
            }
            text.append(cause.getClass().getSimpleName());
            if (cause.getMessage() != null) {
                text.append(": ").append(cause.getMessage());
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        String line = text.toString().replaceAll("\\s+", " ").strip();
        return line.length() > 400 ? line.substring(0, 400) + "..." : line;
    }

    private static int[] leadingVersion(String text) {
        Matcher matcher = LEADING_VERSION.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String[] parts = matcher.group(1).split("\\.");
        int[] version = new int[parts.length];
        for (int index = 0; index < parts.length; index++) {
            version[index] = Integer.parseInt(parts[index]);
        }
        return version;
    }

    private static int compareVersions(int[] left, int[] right) {
        for (int index = 0; index < Math.max(left.length, right.length); index++) {
            int a = index < left.length ? left[index] : 0;
            int b = index < right.length ? right[index] : 0;
            if (a != b) {
                return Integer.compare(a, b);
            }
        }
        return 0;
    }

    private static boolean targetPresent(ZipFile zip, String target) throws IOException {
        int member = target.indexOf('#');
        if (member < 0) {
            if (zip.getEntry(target) != null) {
                return true;
            }
            String last = target.substring(target.lastIndexOf('/') + 1);
            return !last.contains(".") && zip.getEntry(target + ".class") != null;
        }
        String owner = target.substring(0, member);
        String rest = target.substring(member + 1);
        if (owner.endsWith(".class")) {
            owner = owner.substring(0, owner.length() - ".class".length());
        }
        if (owner.isEmpty() || rest.isEmpty()) {
            throw new IllegalArgumentException("Invalid member target " + target);
        }
        var entry = zip.getEntry(owner + ".class");
        if (entry == null) {
            return false;
        }
        ClassNode node = new ClassNode();
        try (var input = zip.getInputStream(entry)) {
            new ClassReader(input).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG
                    | ClassReader.SKIP_FRAMES);
        }
        int paren = rest.indexOf('(');
        String name = paren < 0 ? rest : rest.substring(0, paren);
        String desc = paren < 0 ? null : rest.substring(paren);
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && (desc == null || method.desc.equals(desc))) {
                return true;
            }
        }
        if (desc == null) {
            for (FieldNode field : node.fields) {
                if (field.name.equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Path locateBringupList(String profileId) {
        String relative = "port/tools/bringup/" + profileId + ".txt";
        List<Path> starts = new ArrayList<>();
        starts.add(Path.of("").toAbsolutePath());
        try {
            CodeSource source = PatchRegistry.class.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                starts.add(Path.of(source.getLocation().toURI()).toAbsolutePath());
            }
        } catch (URISyntaxException | IllegalArgumentException | SecurityException ignored) {
            // The working directory is still searched.
        }
        for (Path start : starts) {
            for (Path directory = start; directory != null; directory = directory.getParent()) {
                Path candidate = directory.resolve(relative);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static void requirePatchId(String patchId) {
        if (patchId == null || !PATCH_ID.matcher(patchId).matches()) {
            throw new IllegalArgumentException("Invalid patch id: " + patchId);
        }
    }

    private static String scopeOf(String patchId) {
        int dot = patchId.indexOf('.');
        return dot < 0 ? patchId : patchId.substring(0, dot);
    }

    private static boolean verbose() {
        return truthy(VERBOSE_PROPERTY, VERBOSE_ENV);
    }

    private static boolean truthy(String property, String environment) {
        return truthy(System.getProperty(property)) || truthy(System.getenv(environment));
    }

    private static boolean truthy(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("1") || normalized.equals("true") || normalized.equals("yes");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static Path pathOrNull(String value) {
        return value == null ? null : hostPath(value);
    }

    /**
     * Converts a Git Bash style absolute path ({@code /d/dir/file}) to a Windows path
     * ({@code D:/dir/file}) when running on Windows; other paths are returned unchanged.
     */
    static Path hostPath(String value) {
        String path = value.trim();
        if (java.io.File.separatorChar == '\\' && path.length() >= 3 && path.charAt(0) == '/'
                && Character.isLetter(path.charAt(1)) && path.charAt(2) == '/') {
            return Path.of(Character.toUpperCase(path.charAt(1)) + ":" + path.substring(2));
        }
        return Path.of(path);
    }

    /** One {@link #run} body in progress. */
    private static final class Frame {
        final String id;
        final Spec spec;
        final boolean undeclared;
        boolean part;
        ClassNode node;
        ClassNode originalNode;
        final List<Map<String, Object>> methodTargets = new ArrayList<>();
        int drops;
        final List<String> parts = new ArrayList<>();
        final List<String> absent = new ArrayList<>();
        /**
         * The state of every file of the output root that changed while this frame was open,
         * before its first change ({@link OutputTracker#ABSENT}: it did not exist).
         */
        final Map<String, byte[]> before = new TreeMap<>();

        Frame(String id, Spec spec, boolean undeclared) {
            this.id = id;
            this.spec = spec;
            this.undeclared = undeclared;
        }
    }

    /**
     * The files of a patcher's output root as of the last {@link #refresh}, with their bytes, so
     * that the changes of a failed patch body can be undone. A change is a new, deleted or
     * rewritten file (another size or modification time).
     */
    private static final class OutputTracker {
        static final byte[] ABSENT = new byte[0];

        private record State(long size, FileTime modified, byte[] bytes) {
        }

        final Path root;
        private final Map<String, State> known = new HashMap<>();

        OutputTracker(Path root) throws IOException {
            this.root = root;
            refresh(List.of());
        }

        /** Takes in every change since the last call, journaling it in each open frame. */
        void refresh(Collection<Frame> frames) throws IOException {
            Map<String, BasicFileAttributes> now = list();
            for (Map.Entry<String, BasicFileAttributes> entry : now.entrySet()) {
                String path = entry.getKey();
                BasicFileAttributes attributes = entry.getValue();
                State old = known.get(path);
                byte[] bytes = targetFingerprints == null ? null : Files.readAllBytes(root.resolve(path));
                if (old != null && old.size() == attributes.size()
                        && old.modified().equals(attributes.lastModifiedTime())
                        && (bytes == null || java.util.Arrays.equals(bytes, old.bytes()))) {
                    continue;
                }
                for (Frame frame : frames) {
                    frame.before.putIfAbsent(path, old == null ? ABSENT : old.bytes());
                }
                known.put(path, new State(attributes.size(), attributes.lastModifiedTime(),
                        bytes == null ? Files.readAllBytes(root.resolve(path)) : bytes));
            }
            for (String path : new ArrayList<>(known.keySet())) {
                if (!now.containsKey(path)) {
                    for (Frame frame : frames) {
                        frame.before.putIfAbsent(path, known.get(path).bytes());
                    }
                    known.remove(path);
                }
            }
        }

        /** Puts every file the frame changed back to its state before the frame. */
        void restore(Frame frame) throws IOException {
            for (Map.Entry<String, byte[]> entry : frame.before.entrySet()) {
                Path file = root.resolve(entry.getKey());
                if (entry.getValue() == ABSENT) {
                    Files.deleteIfExists(file);
                    known.remove(entry.getKey());
                } else {
                    Files.createDirectories(file.getParent());
                    Files.write(file, entry.getValue());
                    BasicFileAttributes attributes =
                            Files.readAttributes(file, BasicFileAttributes.class);
                    known.put(entry.getKey(), new State(attributes.size(),
                            attributes.lastModifiedTime(), entry.getValue()));
                }
            }
        }

        private Map<String, BasicFileAttributes> list() throws IOException {
            Map<String, BasicFileAttributes> files = new HashMap<>();
            if (!Files.isDirectory(root)) {
                return files;
            }
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (attributes.isRegularFile()) {
                        files.put(root.relativize(file).toString().replace('\\', '/'), attributes);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            return files;
        }
    }
}
