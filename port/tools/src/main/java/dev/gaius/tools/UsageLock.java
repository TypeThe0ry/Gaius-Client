package dev.gaius.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A profile's external API usage, {@code port/mc/profiles/<profile>/usage.lock} (written by
 * {@code port/tools/extrefs}): what vanilla references in the JDK and
 * in each native library, by section ({@code jdk}, {@code library <group>:<artifact>:<version>}).
 * Patchers generate their entry tables from it instead of keeping hand-written tables with exact
 * counts.
 *
 * <p>Located, in order, by the system property {@value #LOCK_PROPERTY} or the environment
 * variable {@value #LOCK_ENV}; else {@code port/mc/profiles/<profile>/usage.lock} of the
 * repository that holds {@code GAIUS_VERSION_PROFILE} (exported by build-overlays.sh) or that
 * contains the working directory, for the profile of {@link PatchRegistry#profile()}.
 */
public final class UsageLock {
    public static final String LOCK_PROPERTY = "gaius.usage.lock";
    public static final String LOCK_ENV = "GAIUS_USAGE_LOCK";

    private final Path file;
    private final String profile;
    private final Map<String, Set<String>> sections;

    private UsageLock(Path file, String profile, Map<String, Set<String>> sections) {
        this.file = file;
        this.profile = profile;
        this.sections = sections;
    }

    /** The usage lock of the active profile (see the class comment). */
    public static UsageLock active() throws IOException {
        String explicit = firstNonBlank(System.getProperty(LOCK_PROPERTY), System.getenv(LOCK_ENV));
        if (explicit != null) {
            return read(Path.of(nativePath(explicit)));
        }
        String profileId = PatchRegistry.profile();
        if (profileId == null) {
            throw new IllegalStateException("No profile for the usage lock: set -D" + PatchRegistry.PROFILE_PROPERTY
                    + ", GAIUS_MINECRAFT_VERSION or -D" + LOCK_PROPERTY);
        }
        Path port = portDirectory();
        if (port == null) {
            throw new IllegalStateException("Cannot find the repository's port directory for the usage lock of "
                    + profileId + "; run from the repository or set -D" + LOCK_PROPERTY);
        }
        return read(port.resolve("mc/profiles/" + profileId + "/usage.lock"));
    }

    public static UsageLock read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("Missing usage lock " + file
                    + " (bash port/tools/extrefs/extrefs.sh usage <profile> --write)");
        }
        Map<String, Set<String>> sections = new TreeMap<>();
        String profileId = null;
        Set<String> current = null;
        for (String raw : Files.readAllLines(file)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[")) {
                int end = line.indexOf(']');
                current = sections.computeIfAbsent(line.substring(1, end), key -> new TreeSet<>());
            } else if (current != null) {
                current.add(line);
            } else if (line.startsWith("profile ")) {
                profileId = line.substring("profile ".length()).strip();
            }
        }
        if (profileId == null) {
            throw new IllegalStateException(file + " has no profile line");
        }
        return new UsageLock(file, profileId, sections);
    }

    public Path file() {
        return file;
    }

    public String profile() {
        return profile;
    }

    /**
     * The entries of the library {@code group:artifact} ({@code C}, {@code M owner.name(desc)},
     * {@code F owner.name:desc} lines), or an empty set when the profile does not use it.
     */
    public Set<String> library(String groupAndArtifact) {
        String prefix = "library " + groupAndArtifact + ":";
        List<String> matches = new ArrayList<>();
        for (String section : sections.keySet()) {
            if (section.startsWith(prefix)) {
                matches.add(section);
            }
        }
        if (matches.size() > 1) {
            throw new IllegalStateException(file + " has several sections for " + groupAndArtifact + ": " + matches);
        }
        return matches.isEmpty() ? Set.of() : Collections.unmodifiableSet(sections.get(matches.get(0)));
    }

    /**
     * The {@code requiredEntries} of the seams the lock's profile selects (port/versions/<id>.json
     * {@code renderSeam}; port/mc/seams/<generation>/seam.json), as {@code owner.name} strings.
     */
    public Set<String> seamRequiredEntries() throws IOException {
        Path port = portOf(file);
        Path versionProfile = port.resolve("versions/" + profile + ".json");
        if (!Files.isRegularFile(versionProfile)) {
            throw new IllegalStateException("No version profile " + versionProfile + " for " + file);
        }
        Set<String> required = new LinkedHashSet<>();
        Matcher seam = Pattern.compile("\"renderSeam\"\\s*:\\s*\"([^\"]*)\"").matcher(Files.readString(versionProfile));
        if (seam.find() && !seam.group(1).equals("none")) {
            Path manifest = port.resolve("mc/seams/" + seam.group(1) + "/seam.json");
            Matcher list = Pattern.compile("\"requiredEntries\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(Files.readString(manifest));
            if (list.find()) {
                Matcher entry = Pattern.compile("\"([^\"]+)\"").matcher(list.group(1));
                while (entry.find()) {
                    required.add(entry.group(1));
                }
            }
        }
        return required;
    }

    /** The port directory of a lock at port/mc/profiles/<id>/usage.lock. */
    private static Path portOf(Path lock) {
        Path port = lock.toAbsolutePath().normalize().getParent().getParent().getParent().getParent();
        if (port == null || !Files.isDirectory(port.resolve("versions"))) {
            throw new IllegalStateException(lock + " is not at port/mc/profiles/<profile>/usage.lock");
        }
        return port;
    }

    private static Path portDirectory() {
        String profileFile = System.getenv("GAIUS_VERSION_PROFILE");
        if (profileFile != null && !profileFile.isBlank()) {
            Path versions = Path.of(nativePath(profileFile.strip())).toAbsolutePath().getParent();
            if (versions != null && versions.getParent() != null
                    && Files.isDirectory(versions.getParent().resolve("mc"))) {
                return versions.getParent();
            }
        }
        for (Path directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
            if (Files.isDirectory(directory.resolve("port/versions")) && Files.isDirectory(directory.resolve("port/mc"))) {
                return directory.resolve("port");
            }
        }
        return null;
    }

    /** Git Bash may hand over {@code /d/...} paths. */
    private static String nativePath(String value) {
        if (System.getProperty("os.name", "").startsWith("Windows") && value.matches("^/[A-Za-z](/.*)?$")) {
            return Character.toUpperCase(value.charAt(1)) + ":" + (value.length() > 2 ? value.substring(2) : "/");
        }
        return value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return null;
    }
}
