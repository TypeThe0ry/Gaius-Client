package dev.gaius.tools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The version profile ({@code port/versions/<id>.json}) a patcher runs for. Patchers decide by
 * its fields instead of comparing version ids, so a new profile whose fields are already known
 * needs no patcher change:
 *
 * <ul>
 *   <li>{@code patchSet}: {@code modern} (the named Java 25 client layout) or
 *       {@code legacy-12111}; build-overlays.sh picks the patcher chain from the same field;</li>
 *   <li>{@code renderSeam}: the render seam generation ({@code renderpearl-<n>}, ...) or
 *       {@code none}; a {@code renderpearl-<n>} seam means the renderpearl render API;</li>
 *   <li>{@code inputSeam}, {@code shaderFront}: read as given;</li>
 *   <li>{@code bringupAllowed}: whether the profile may build in bring-up mode (a new profile
 *       does; a shipped profile whose patches are all finished does not).</li>
 * </ul>
 *
 * <p>The file is the first of: the system property {@code gaius.profile.json}; the environment
 * variable {@code GAIUS_VERSION_PROFILE} (exported by version-profile.sh) when that file exists
 * and has the requested id, else {@code <id>.json} next to it; {@code port/versions/<id>.json} in
 * the working directory or the tool classes directory or one of their parents. The file's
 * {@code id} must be the requested id.
 */
public final class VersionProfile {
    public static final String PATH_PROPERTY = "gaius.profile.json";
    public static final String PATH_ENV = "GAIUS_VERSION_PROFILE";
    public static final String MODERN_PATCH_SET = "modern";
    public static final String LEGACY_PATCH_SET = "legacy-12111";
    private static final Pattern RENDERPEARL_SEAM = Pattern.compile("renderpearl-\\d+");
    /** kernelSemantics.densityGraph of the profiles whose density graph runs as a router of records. */
    public static final String ROUTER_DENSITY_GRAPH = "router-f64";
    /** kernelSemantics.densityGraph of the profiles whose density graph is compiled into a sampler. */
    public static final String SAMPLER_DENSITY_GRAPH = "sampler-f32";
    private static final Map<String, VersionProfile> CACHE = new ConcurrentHashMap<>();

    private final Path path;
    private final String id;
    private final String patchSet;
    private final String renderSeam;
    private final String inputSeam;
    private final String shaderFront;
    private final String densityGraph;
    private final boolean bringupAllowed;
    private final Map<String, NotApplicable> patchNotApplicable = new LinkedHashMap<>();

    /**
     * A {@code patchkitNotApplicable} declaration: its reason and the TeaVM targets it covers. The
     * value is the reason (every target) or {@code {"targets": [...], "reason": "..."}}, for a
     * patch that this profile's generation applies on some targets only.
     */
    private record NotApplicable(String reason, List<String> targets) {
    }

    private VersionProfile(Path path, Map<?, ?> json) {
        this.path = path;
        this.id = string(json, "id");
        this.patchSet = string(json, "patchSet");
        this.renderSeam = string(json, "renderSeam");
        this.inputSeam = string(json, "inputSeam");
        this.shaderFront = string(json, "shaderFront");
        if (!(json.get("kernelSemantics") instanceof Map<?, ?> semantics)) {
            throw new IllegalStateException(path + ": kernelSemantics must be an object");
        }
        this.densityGraph = string(semantics, "densityGraph");
        if (!(json.get("bringupAllowed") instanceof Boolean bringup)) {
            throw new IllegalStateException(path + ": bringupAllowed must be true or false");
        }
        this.bringupAllowed = bringup;
        if (json.get("patchkitNotApplicable") instanceof Map<?, ?> declarations) {
            for (var declaration : declarations.entrySet()) {
                if (!(declaration.getKey() instanceof String patchId)) {
                    throw new IllegalStateException(path + ": invalid patchkitNotApplicable entry");
                }
                patchNotApplicable.put(patchId, notApplicable(path, patchId, declaration.getValue()));
            }
        } else if (json.containsKey("patchkitNotApplicable")) {
            throw new IllegalStateException(path + ": patchkitNotApplicable must be an object");
        }
        if (!ROUTER_DENSITY_GRAPH.equals(densityGraph) && !SAMPLER_DENSITY_GRAPH.equals(densityGraph)) {
            throw new IllegalStateException(path + ": unknown kernelSemantics.densityGraph " + densityGraph);
        }
        if (!MODERN_PATCH_SET.equals(patchSet) && !LEGACY_PATCH_SET.equals(patchSet)) {
            throw new IllegalStateException(path + ": unknown patchSet " + patchSet);
        }
    }

    /** The profile with the given id; fails when no profile file for it can be found. */
    public static VersionProfile forId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("A version profile id is required");
        }
        return CACHE.computeIfAbsent(id.trim(), VersionProfile::load);
    }

    public Path path() {
        return path;
    }

    public String id() {
        return id;
    }

    public String patchSet() {
        return patchSet;
    }

    public String renderSeam() {
        return renderSeam;
    }

    public String inputSeam() {
        return inputSeam;
    }

    public String shaderFront() {
        return shaderFront;
    }

    /**
     * kernelSemantics.densityGraph: {@value #ROUTER_DENSITY_GRAPH} (vanilla evaluates its density
     * functions as records) or {@value #SAMPLER_DENSITY_GRAPH} (vanilla compiles each density graph
     * once per RandomState).
     */
    public String densityGraph() {
        return densityGraph;
    }

    /** Whether the profile may build in bring-up mode (GAIUS_BRINGUP=1, PatchRegistry). */
    public boolean bringupAllowed() {
        return bringupAllowed;
    }

    /** Whether the profile takes the modern patch set. */
    public boolean modernPatchSet() {
        return MODERN_PATCH_SET.equals(patchSet);
    }

    /**
     * The reason this profile declares {@code patchId} not applicable on the TeaVM {@code target},
     * or null when it applies there ({@code patchkitNotApplicable}).
     */
    public String patchNotApplicable(String patchId, String target) {
        NotApplicable declaration = patchNotApplicable.get(patchId);
        return declaration != null && declaration.targets().contains(target) ? declaration.reason() : null;
    }

    private static NotApplicable notApplicable(Path path, String patchId, Object value) {
        if (value instanceof String reason && !reason.isBlank()) {
            return new NotApplicable(reason, PatchRegistry.Spec.ALL_TARGETS);
        }
        if (value instanceof Map<?, ?> fields && fields.size() == 2
                && fields.get("reason") instanceof String reason && !reason.isBlank()
                && fields.get("targets") instanceof List<?> targets && !targets.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Object target : targets) {
                if (!(target instanceof String name) || !PatchRegistry.Spec.ALL_TARGETS.contains(name)
                        || names.contains(name)) {
                    throw new IllegalStateException(path + ": patchkitNotApplicable " + patchId
                            + " names an unknown or repeated target " + target);
                }
                names.add(name);
            }
            return new NotApplicable(reason, List.copyOf(names));
        }
        throw new IllegalStateException(path + ": patchkitNotApplicable " + patchId
                + " must be a reason or {\"targets\": [...], \"reason\": \"...\"}");
    }

    /** Whether the profile takes the legacy (1.21-era, obfuscated-with-mappings) patch set. */
    public boolean legacyPatchSet() {
        return LEGACY_PATCH_SET.equals(patchSet);
    }

    /** Whether the profile's render seam is a renderpearl generation (the renderpearl render API). */
    public boolean renderpearl() {
        return RENDERPEARL_SEAM.matcher(renderSeam).matches();
    }

    private static VersionProfile load(String id) {
        String explicit = System.getProperty(PATH_PROPERTY);
        if (explicit != null && !explicit.isBlank()) {
            return require(id, read(Path.of(explicit.trim())));
        }
        String exported = System.getenv(PATH_ENV);
        if (exported != null && !exported.isBlank() && Files.isRegularFile(Path.of(exported.trim()))) {
            Path active = Path.of(exported.trim());
            VersionProfile profile = read(active);
            if (profile.id.equals(id)) {
                return profile;
            }
            Path sibling = active.resolveSibling(id + ".json");
            if (Files.isRegularFile(sibling)) {
                return require(id, read(sibling));
            }
        }
        String relative = "port/versions/" + id + ".json";
        Path candidate = searchCheckout(relative, true);
        if (candidate != null) {
            return require(id, read(candidate));
        }
        throw new IllegalStateException("No version profile for " + id + ": set -D" + PATH_PROPERTY
                + " or " + PATH_ENV + ", or run from a checkout that has " + relative);
    }

    /**
     * The id of the one profile of port/versions that takes the given patch set (for a patcher run
     * without a profile argument or build environment). The directory is the one of the profile
     * named by {@code gaius.profile.json} or {@code GAIUS_VERSION_PROFILE}, else port/versions of
     * the checkout around the working directory or the tool classes. Fails unless exactly one
     * profile takes the patch set.
     */
    public static String only(String patchSet) {
        Path directory = null;
        for (String named : new String[] {System.getProperty(PATH_PROPERTY), System.getenv(PATH_ENV)}) {
            if (named != null && !named.isBlank() && Files.isRegularFile(Path.of(named.trim()))) {
                directory = Path.of(named.trim()).toAbsolutePath().getParent();
                break;
            }
        }
        if (directory == null) {
            directory = searchCheckout("port/versions", false);
        }
        if (directory == null) {
            throw new IllegalStateException("No port/versions directory for patch set " + patchSet);
        }
        List<String> ids = new ArrayList<>();
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted().toList()) {
                VersionProfile profile = read(file);
                if (patchSet.equals(profile.patchSet)) {
                    ids.add(profile.id);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list " + directory, e);
        }
        if (ids.size() != 1) {
            throw new IllegalStateException("Expected one profile with patch set " + patchSet + " in "
                    + directory + ", found " + ids);
        }
        return ids.get(0);
    }

    /** The first file (or directory) at relative under the working directory, the tool classes
     * directory or one of their parents, or null. */
    private static Path searchCheckout(String relative, boolean file) {
        List<Path> starts = new ArrayList<>();
        starts.add(Path.of("").toAbsolutePath());
        try {
            CodeSource source = VersionProfile.class.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                starts.add(Path.of(source.getLocation().toURI()).toAbsolutePath());
            }
        } catch (URISyntaxException | IllegalArgumentException | SecurityException ignored) {
            // The working directory is still searched.
        }
        for (Path start : starts) {
            for (Path directory = start; directory != null; directory = directory.getParent()) {
                Path candidate = directory.resolve(relative);
                if (file ? Files.isRegularFile(candidate) : Files.isDirectory(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /**
     * A JSON file read with the same small reader as the profiles (objects as {@link Map}, arrays
     * as {@link List}, numbers as {@link Double}); for the other JSON the patchers read, such as
     * {@code port/mc/seams/<generation>/seam.json}.
     */
    public static Object readJson(Path path) {
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + path, e);
        }
        return new JsonReader(text, path).document();
    }

    private static VersionProfile require(String id, VersionProfile profile) {
        if (!profile.id.equals(id)) {
            throw new IllegalStateException(profile.path + " is profile " + profile.id + ", not " + id);
        }
        return profile;
    }

    private static VersionProfile read(Path path) {
        Object json = readJson(path);
        if (!(json instanceof Map<?, ?> map)) {
            throw new IllegalStateException(path + ": a version profile is a JSON object");
        }
        return new VersionProfile(path.toAbsolutePath(), map);
    }

    private static String string(Map<?, ?> json, String key) {
        if (!(json.get(key) instanceof String value) || value.isEmpty()) {
            throw new IllegalStateException("Version profile field " + key + " must be a non-empty string");
        }
        return value;
    }

    /** A small JSON reader: objects, arrays, strings, numbers (as Double), booleans and null. */
    private static final class JsonReader {
        private final String text;
        private final Path path;
        private int at;

        JsonReader(String text, Path path) {
            this.text = text;
            this.path = path;
        }

        Object document() {
            Object value = value();
            skipSpace();
            if (at != text.length()) {
                throw error("trailing text");
            }
            return value;
        }

        private Object value() {
            skipSpace();
            if (at >= text.length()) {
                throw error("unexpected end");
            }
            char c = text.charAt(at);
            switch (c) {
                case '{':
                    return object();
                case '[':
                    return array();
                case '"':
                    return string();
                case 't':
                    return literal("true", Boolean.TRUE);
                case 'f':
                    return literal("false", Boolean.FALSE);
                case 'n':
                    return literal("null", null);
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return number();
                    }
                    throw error("unexpected character '" + c + "'");
            }
        }

        private Map<String, Object> object() {
            Map<String, Object> result = new LinkedHashMap<>();
            at++;
            skipSpace();
            if (peek('}')) {
                at++;
                return result;
            }
            while (true) {
                skipSpace();
                if (!peek('"')) {
                    throw error("expected a key");
                }
                String key = string();
                skipSpace();
                expect(':');
                if (result.put(key, value()) != null) {
                    throw error("duplicate key " + key);
                }
                skipSpace();
                if (peek(',')) {
                    at++;
                } else {
                    expect('}');
                    return result;
                }
            }
        }

        private List<Object> array() {
            List<Object> result = new ArrayList<>();
            at++;
            skipSpace();
            if (peek(']')) {
                at++;
                return result;
            }
            while (true) {
                result.add(value());
                skipSpace();
                if (peek(',')) {
                    at++;
                } else {
                    expect(']');
                    return result;
                }
            }
        }

        private String string() {
            StringBuilder result = new StringBuilder();
            at++;
            while (true) {
                if (at >= text.length()) {
                    throw error("unterminated string");
                }
                char c = text.charAt(at++);
                if (c == '"') {
                    return result.toString();
                }
                if (c != '\\') {
                    result.append(c);
                    continue;
                }
                if (at >= text.length()) {
                    throw error("unterminated escape");
                }
                char e = text.charAt(at++);
                switch (e) {
                    case '"', '\\', '/' -> result.append(e);
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> {
                        if (at + 4 > text.length()) {
                            throw error("short unicode escape");
                        }
                        result.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                        at += 4;
                    }
                    default -> throw error("bad escape \\" + e);
                }
            }
        }

        private Double number() {
            int start = at;
            while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) {
                at++;
            }
            try {
                return Double.valueOf(text.substring(start, at));
            } catch (NumberFormatException e) {
                throw error("bad number");
            }
        }

        private Object literal(String word, Object value) {
            if (!text.startsWith(word, at)) {
                throw error("expected " + word);
            }
            at += word.length();
            return value;
        }

        private boolean peek(char c) {
            return at < text.length() && text.charAt(at) == c;
        }

        private void expect(char c) {
            if (!peek(c)) {
                throw error("expected '" + c + "'");
            }
            at++;
        }

        private void skipSpace() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
        }

        private IllegalStateException error(String message) {
            return new IllegalStateException(path + ": invalid JSON at offset " + at + ": " + message);
        }
    }
}
