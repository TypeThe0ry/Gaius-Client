/*
 * Gaius Patcher: builds a personal copy of the Gaius browser client on the
 * user's own machine from the user's own game files.
 *
 * This file is the whole command-line front end. It has no dependencies
 * outside the JDK, so it runs straight from source:
 *
 *   java patcher/GaiusPatcher.java doctor
 *   java patcher/GaiusPatcher.java fetch 26.3
 *
 * Nothing this program writes leaves the user's machine, and it never
 * contacts a Gaius server. Official game files come only from the user's own
 * launcher installation: the user must have installed and started each
 * version in the official launcher. The one download is the 1.21.11
 * obfuscation mappings, which the launcher does not keep; they come straight
 * from the Mojang URL in the user's own version metadata. Every file is
 * checked against the SHA-1 that the version profile (port/versions/<id>.json)
 * pins.
 *
 * SPDX-License-Identifier: MIT
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class GaiusPatcher {
    static final String TOOL_VERSION = "0.1.0-dev";

    public static void main(String[] args) {
        Args parsed = Args.parse(args);
        try {
            int status = switch (parsed.command) {
                case "doctor" -> Doctor.run(parsed);
                case "fetch" -> Fetch.run(parsed);
                case "terms" -> Terms.show(parsed);
                case "selftest" -> SelfTest.run();
                case "version", "--version" -> {
                    System.out.println("gaius-patcher " + TOOL_VERSION);
                    yield 0;
                }
                case "", "help", "--help", "-h" -> {
                    printHelp();
                    yield 0;
                }
                default -> {
                    System.err.println("Unknown command: " + parsed.command);
                    printHelp();
                    yield 2;
                }
            };
            System.exit(status);
        } catch (PatcherException error) {
            System.err.println("error: " + error.getMessage());
            System.exit(1);
        }
    }

    static void printHelp() {
        System.out.println("""
                gaius-patcher %s

                Builds a personal Gaius client from your own installed copy of the game.
                Install and start each version once in the official launcher first.
                The result contains official game code and assets: it is for your own
                use only. Do not share it or host it on a public website.

                Not an official Minecraft product. Not approved by or associated with
                Mojang or Microsoft. You must own Minecraft: Java Edition and accept the
                Minecraft EULA (https://www.minecraft.net/eula).

                Commands:
                  doctor                 Check Java, memory, disk space and your game installation
                  fetch <version>        Collect and verify the official files for one version
                  terms                  Show the terms of use and whether you accepted them
                  selftest               Run the built-in offline checks
                  version                Print the patcher version

                Options:
                  --minecraft-dir <dir>  Your launcher's game directory (default: the OS location)
                  --profiles <dir>       Directory holding the version profiles (<id>.json)
                  --work <dir>           Where fetched files are written (default: per version)
                  --mappings <file>      Use this client_mappings file for 1.21.11 instead of
                                         downloading it from Mojang
                  --offline              Never use the network
                  --accept-terms         Accept the terms of use (patcher/TERMS.md) without
                                         the interactive prompt
                """.formatted(TOOL_VERSION));
    }

    // ---------------------------------------------------------------- doctor

    static final class Doctor {
        static int run(Args args) {
            int problems = 0;
            int java = Runtime.version().feature();
            System.out.println("Java:      " + Runtime.version() + " (" + System.getProperty("java.vendor") + ")");

            long totalMemory = totalPhysicalMemory();
            System.out.println("Memory:    " + (totalMemory > 0 ? gib(totalMemory) : "unknown"));
            if (totalMemory > 0 && totalMemory < 16L << 30) {
                System.out.println("           warning: building one version currently needs about 16 GB for the compiler;"
                        + " 32 GB is recommended");
            }

            Path minecraftDir = Locations.minecraftDir(args);
            System.out.println("Game dir:  " + minecraftDir + (Files.isDirectory(minecraftDir) ? "" : " (not found)"));

            Path profiles;
            try {
                profiles = Locations.profilesDir(args);
            } catch (PatcherException error) {
                System.out.println("Profiles:  " + error.getMessage());
                return 1;
            }
            System.out.println("Profiles:  " + profiles);

            Path workRoot = Locations.workRoot(args, profiles);
            try {
                Path probe = workRoot;
                while (probe != null && !Files.exists(probe)) {
                    probe = probe.getParent();
                }
                if (probe != null) {
                    FileStore store = Files.getFileStore(probe);
                    long free = store.getUsableSpace();
                    System.out.println("Work dir:  " + workRoot + " (" + gib(free) + " free)");
                    if (free < 20L << 30) {
                        System.out.println("           warning: a full build of one version needs about 20 GB of free space");
                    }
                }
            } catch (IOException error) {
                System.out.println("Work dir:  " + workRoot + " (free space unknown: " + error.getMessage() + ")");
            }

            System.out.println();
            for (VersionProfile profile : VersionProfile.loadAll(profiles)) {
                String status;
                Path jar = minecraftDir.resolve("versions").resolve(profile.id).resolve(profile.id + ".jar");
                if (!Files.isRegularFile(jar)) {
                    status = "not installed: start " + profile.id + " once in the official launcher";
                } else if (profile.clientSha1.equals(Hashes.sha1(jar))) {
                    status = "installed, client jar verified";
                } else {
                    status = "installed, but the client jar does not match the pinned SHA-1";
                    problems++;
                }
                String javaNote = java >= profile.javaVersion ? "" : " [needs Java " + profile.javaVersion + "+]";
                if (java < profile.javaVersion) {
                    problems++;
                }
                System.out.printf(Locale.ROOT, "  %-8s %s%s%n", profile.id, status, javaNote);
            }
            return problems == 0 ? 0 : 1;
        }

        static long totalPhysicalMemory() {
            if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
                return os.getTotalMemorySize();
            }
            return -1;
        }

        static String gib(long bytes) {
            return String.format(Locale.ROOT, "%.1f GB", bytes / (double) (1L << 30));
        }
    }

    // ----------------------------------------------------------------- fetch

    /**
     * Collects the official files one profile needs into the work directory,
     * in the layout the build scripts read (client-original.jar, libraries/,
     * assets/, classpath.txt, version.json, client-version.json).
     */
    static final class Fetch {
        static int run(Args args) {
            if (args.positional.isEmpty()) {
                throw new PatcherException("usage: fetch <version>");
            }
            Path profiles = Locations.profilesDir(args);
            VersionProfile profile = VersionProfile.load(profiles, args.positional.get(0));
            Terms.require(args, profiles);
            Path minecraftDir = Locations.minecraftDir(args);
            Path work = Locations.workRoot(args, profiles).resolve(profile.id);
            if (args.options.containsKey("work")) {
                work = Paths.get(args.options.get("work")).toAbsolutePath().normalize();
            }
            Sources sources = new Sources(minecraftDir, profile.id, args.flags.contains("offline"));
            createDirectories(work);

            System.out.println("Fetching " + profile.id + " into " + work);
            System.out.println("Local installation: " + minecraftDir
                    + (Files.isDirectory(minecraftDir) ? "" : " (not found)"));

            Map<String, Object> metadata = sources.versionMetadata(profile);
            writeString(work.resolve("version.json"), Json.write(metadata));
            Map<String, Object> downloads = Json.object(metadata, "downloads");

            Map<String, Object> client = Json.object(downloads, "client");
            requireEqual("client jar SHA-1 in the version metadata", profile.clientSha1, Json.string(client, "sha1"));
            Path clientJar = work.resolve("client-original.jar");
            sources.obtain(Job.local("the client jar",
                    minecraftDir.resolve("versions").resolve(profile.id).resolve(profile.id + ".jar"),
                    profile.clientSha1, clientJar));

            if (profile.needsMappings()) {
                Map<String, Object> mappings = Json.object(downloads, "client_mappings");
                String sha1 = Json.string(mappings, "sha1");
                if (profile.clientMappingsSha1 != null) {
                    requireEqual("client mappings SHA-1", profile.clientMappingsSha1, sha1);
                }
                List<Path> candidates = args.options.containsKey("mappings")
                        ? List.of(Paths.get(args.options.get("mappings")).toAbsolutePath())
                        : List.of();
                sources.obtain(new Job("the client mappings", candidates, Json.string(mappings, "url"), sha1,
                        work.resolve("client-mappings.txt")));
            }

            List<Path> libraryJars = fetchLibraries(sources, metadata, minecraftDir, work.resolve("libraries"));
            writeString(work.resolve("classpath.txt"), Classpath.join(libraryJars) + "\n");
            extractClientVersion(clientJar, work.resolve("client-version.json"));

            Path assets = work.resolve("assets");
            Map<String, Object> indexInfo = Json.object(metadata, "assetIndex");
            String indexId = Json.string(indexInfo, "id");
            String indexSha1 = Json.string(indexInfo, "sha1");
            if (!indexId.equals(profile.assetIndexId)) {
                throw new PatcherException("asset index " + indexId + " is not the profile's " + profile.assetIndexId
                        + "; this patcher does not support this game build");
            }
            if (!profile.acceptsAssetIndex(indexSha1) && !args.flags.contains("allow-new-asset-index")) {
                throw new PatcherException("Mojang published a new asset index (" + indexSha1 + ") that this patcher"
                        + " has not been checked against. Update the patcher, or pass --allow-new-asset-index.");
            }
            Path index = assets.resolve("indexes").resolve(indexId + ".json");
            sources.obtain(Job.local("the asset index " + indexId,
                    minecraftDir.resolve("assets").resolve("indexes").resolve(indexId + ".json"), indexSha1, index));

            AssetSelection selection = AssetSelection.load(profiles);
            Map<String, Object> indexJson = Json.asObject(Json.parse(readString(index)), index.toString());
            Map<String, String> wanted = selection.select(Json.object(indexJson, "objects"));
            fetchAssetObjects(sources, wanted, minecraftDir, assets.resolve("objects"));

            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("tool", "gaius-patcher " + TOOL_VERSION);
            manifest.put("profile", profile.id);
            manifest.put("fetchedAt", Instant.now().toString());
            manifest.put("clientSha1", profile.clientSha1);
            manifest.put("assetIndex", indexId);
            manifest.put("assetIndexSha1", indexSha1);
            manifest.put("libraries", libraryJars.size());
            manifest.put("assetObjects", wanted.size());
            manifest.put("fromLocalInstallation", sources.localCount.get());
            manifest.put("downloaded", sources.downloadCount.get());
            writeString(work.resolve("fetch-manifest.json"), Json.write(manifest));

            System.out.println();
            System.out.println("Verified " + profile.id + ": client jar, " + libraryJars.size() + " libraries, "
                    + wanted.size() + " asset objects (" + sources.localCount.get() + " from the local installation, "
                    + sources.downloadCount.get() + " downloaded from Mojang).");
            return 0;
        }

        static List<Path> fetchLibraries(Sources sources, Map<String, Object> metadata, Path minecraftDir, Path out) {
            List<Object> libraries = Json.array(metadata, "libraries");
            List<Job> jobs = new ArrayList<>();
            List<Job> otherPlatforms = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (Object entry : libraries) {
                Map<String, Object> library = Json.asObject(entry, "library");
                Object downloads = library.get("downloads");
                if (!(downloads instanceof Map<?, ?>)) {
                    continue;
                }
                Object artifactValue = Json.asObject(downloads, "downloads").get("artifact");
                if (!(artifactValue instanceof Map<?, ?>)) {
                    continue;
                }
                Map<String, Object> artifact = Json.asObject(artifactValue, "artifact");
                String path = Json.string(artifact, "path");
                if (!seen.add(path)) {
                    continue;
                }
                Path relative = Safe.relative(path);
                Job job = Job.local("library " + path, minecraftDir.resolve("libraries").resolve(relative),
                        Json.string(artifact, "sha1"), out.resolve(relative));
                if (LibraryRules.appliesHere(library)) {
                    jobs.add(job);
                } else {
                    otherPlatforms.add(job);
                }
            }
            sources.obtainAll(jobs);
            // The launcher only installs the libraries of the platform it runs on. Libraries for
            // other platforms are native-only jars; copy them when present so the classpath matches
            // a build on that platform, and skip them otherwise.
            for (Job job : otherPlatforms) {
                if (sources.tryLocal(job)) {
                    jobs.add(job);
                }
            }
            List<Path> jars = new ArrayList<>();
            for (Job job : jobs) {
                if (job.target.getFileName().toString().endsWith(".jar")) {
                    jars.add(job.target);
                }
            }
            Collections.sort(jars);
            return jars;
        }

        static void fetchAssetObjects(Sources sources, Map<String, String> wanted, Path minecraftDir, Path out) {
            List<Job> jobs = new ArrayList<>();
            Set<String> hashes = new LinkedHashSet<>(wanted.values());
            for (String hash : hashes) {
                if (!hash.matches("[0-9a-f]{40}")) {
                    throw new PatcherException("asset index has a malformed hash: " + hash);
                }
                String relative = hash.substring(0, 2) + "/" + hash;
                jobs.add(Job.local("asset object " + hash,
                        minecraftDir.resolve("assets").resolve("objects").resolve(relative), hash, out.resolve(relative)));
            }
            sources.obtainAll(jobs);
        }

        static void extractClientVersion(Path clientJar, Path out) {
            try (ZipFile zip = new ZipFile(clientJar.toFile())) {
                ZipEntry entry = zip.getEntry("version.json");
                if (entry == null) {
                    throw new PatcherException("client jar has no version.json");
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.write(out, in.readAllBytes());
                }
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }
    }

    // -------------------------------------------------------------- sources

    /**
     * One official file. Game files (jar, libraries, asset index and objects)
     * come only from the user's own launcher installation; a job with a URL is
     * the one exception, the 1.21.11 mappings, which the launcher never stores.
     */
    record Job(String label, List<Path> localCandidates, String url, String sha1, Path target) {
        static Job local(String label, Path candidate, String sha1, Path target) {
            return new Job(label, List.of(candidate), null, sha1, target);
        }
    }

    /**
     * Resolves each official file from the work directory (already verified)
     * or the user's launcher installation, accepting a file only when its
     * SHA-1 matches. Nothing is downloaded except the obfuscation mappings,
     * which come straight from Mojang's server named in the local metadata.
     */
    static final class Sources {
        final Path minecraftDir;
        final String versionId;
        final boolean offline;
        final AtomicInteger localCount = new AtomicInteger();
        final AtomicInteger downloadCount = new AtomicInteger();
        private HttpClient http;

        Sources(Path minecraftDir, String versionId, boolean offline) {
            this.minecraftDir = minecraftDir;
            this.versionId = versionId;
            this.offline = offline;
        }

        Map<String, Object> versionMetadata(VersionProfile profile) {
            Path local = minecraftDir.resolve("versions").resolve(profile.id).resolve(profile.id + ".json");
            if (!Files.isRegularFile(local)) {
                throw notInstalled("the version metadata " + local);
            }
            Map<String, Object> metadata = Json.asObject(Json.parse(readString(local)), profile.id + ".json");
            if (metadata.get("downloads") instanceof Map<?, ?> downloads
                    && downloads.get("client") instanceof Map<?, ?> client
                    && profile.clientSha1.equals(client.get("sha1"))) {
                return metadata;
            }
            throw new PatcherException(local + " does not describe the official " + profile.id
                    + " client this patcher supports. Reinstall " + profile.id + " in the official launcher.");
        }

        void obtain(Job job) {
            obtainAll(List.of(job));
        }

        void obtainAll(List<Job> jobs) {
            List<Job> missing = new ArrayList<>();
            for (Job job : jobs) {
                if (!resolveLocally(job)) {
                    missing.add(job);
                }
            }
            List<Job> notInstalled = new ArrayList<>();
            for (Job job : missing) {
                if (job.url == null) {
                    notInstalled.add(job);
                }
            }
            if (!notInstalled.isEmpty()) {
                String first = notInstalled.get(0).label;
                throw notInstalled(notInstalled.size() == 1 ? first
                        : notInstalled.size() + " files, starting with " + first);
            }
            for (Job job : missing) {
                if (offline) {
                    throw new PatcherException(job.label + " is not in the work directory and --offline is set."
                            + " Pass --mappings <file>, or drop --offline to download it from Mojang.");
                }
                System.out.println("  downloading " + job.label + " from " + URI.create(job.url).getHost());
                download(job);
            }
        }

        private PatcherException notInstalled(String what) {
            return new PatcherException("Missing or modified in your launcher installation (" + minecraftDir + "): "
                    + what + ".\nStart " + versionId + " once in the official launcher so all of its files are"
                    + " present and verified, then run fetch again (or pass --minecraft-dir).");
        }

        boolean tryLocal(Job job) {
            return resolveLocally(job);
        }

        private boolean resolveLocally(Job job) {
            if (Files.isRegularFile(job.target) && job.sha1.equals(Hashes.sha1(job.target))) {
                return true;
            }
            for (Path candidate : job.localCandidates) {
                if (Files.isRegularFile(candidate) && job.sha1.equals(Hashes.sha1(candidate))) {
                    createDirectories(job.target.getParent());
                    try {
                        Files.copy(candidate, job.target, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException error) {
                        throw new UncheckedIOException(error);
                    }
                    localCount.incrementAndGet();
                    return true;
                }
            }
            return false;
        }

        private void download(Job job) {
            Path partial = job.target.resolveSibling(job.target.getFileName() + ".part");
            createDirectories(job.target.getParent());
            IOException last = null;
            for (int attempt = 1; attempt <= 5; attempt++) {
                try {
                    HttpResponse<Path> response = client().send(
                            HttpRequest.newBuilder(URI.create(job.url)).timeout(Duration.ofMinutes(5)).GET().build(),
                            HttpResponse.BodyHandlers.ofFile(partial));
                    if (response.statusCode() != 200) {
                        throw new IOException("HTTP " + response.statusCode() + " for " + job.url);
                    }
                    String actual = Hashes.sha1(partial);
                    if (!actual.equals(job.sha1)) {
                        Files.deleteIfExists(partial);
                        throw new PatcherException("SHA-1 mismatch for " + job.label + " from " + job.url
                                + ": expected " + job.sha1 + ", got " + actual);
                    }
                    Files.move(partial, job.target, StandardCopyOption.REPLACE_EXISTING);
                    downloadCount.incrementAndGet();
                    return;
                } catch (IOException error) {
                    last = error;
                    sleep(1000L << (attempt - 1));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new PatcherException("interrupted");
                }
            }
            throw new PatcherException("could not download " + job.label + ": " + last);
        }

        private HttpClient client() {
            if (http == null) {
                http = HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .proxy(ProxySelector.getDefault())
                        .connectTimeout(Duration.ofSeconds(20))
                        .build();
            }
            return http;
        }
    }

    // -------------------------------------------------------------- profiles

    static final class VersionProfile {
        final String id;
        final int javaVersion;
        final String clientDistribution;
        final String clientSha1;
        final String clientMappingsSha1;
        final String assetIndexId;
        final Set<String> assetIndexSha1s;

        VersionProfile(Map<String, Object> json) {
            id = Json.string(json, "id");
            javaVersion = (int) Json.number(json, "javaVersion");
            clientDistribution = Json.string(json, "clientDistribution");
            Map<String, Object> official = Json.object(json, "official");
            clientSha1 = Json.string(official, "clientSha1");
            Object mappings = official.get("clientMappingsSha1");
            clientMappingsSha1 = mappings instanceof String s ? s : null;
            assetIndexId = Json.string(official, "assetIndexId");
            assetIndexSha1s = new LinkedHashSet<>();
            assetIndexSha1s.add(Json.string(official, "assetIndexSha1"));
            if (official.get("assetIndexSha1Alternates") instanceof List<?> alternates) {
                for (Object alternate : alternates) {
                    assetIndexSha1s.add(String.valueOf(alternate));
                }
            }
            if (!clientSha1.matches("[0-9a-f]{40}")) {
                throw new PatcherException("profile " + id + " has a malformed clientSha1");
            }
            if (!id.matches("[0-9A-Za-z._-]+")) {
                throw new PatcherException("profile id is not a plain version id: " + id);
            }
        }

        boolean needsMappings() {
            return "obfuscated-with-mappings".equals(clientDistribution);
        }

        boolean acceptsAssetIndex(String sha1) {
            return assetIndexSha1s.contains(sha1);
        }

        static VersionProfile load(Path profiles, String id) {
            if (!id.matches("[0-9A-Za-z._-]+")) {
                throw new PatcherException("not a version id: " + id);
            }
            Path file = profiles.resolve(id + ".json");
            if (!Files.isRegularFile(file)) {
                throw new PatcherException("no profile for " + id + " in " + profiles
                        + " (available: " + String.join(", ", ids(profiles)) + ")");
            }
            return new VersionProfile(Json.asObject(Json.parse(readString(file)), file.toString()));
        }

        static List<VersionProfile> loadAll(Path profiles) {
            List<VersionProfile> all = new ArrayList<>();
            for (String id : ids(profiles)) {
                all.add(load(profiles, id));
            }
            return all;
        }

        static List<String> ids(Path profiles) {
            List<String> ids = new ArrayList<>();
            try (var stream = Files.list(profiles)) {
                stream.map(path -> path.getFileName().toString())
                        .filter(name -> name.endsWith(".json"))
                        .map(name -> name.substring(0, name.length() - ".json".length()))
                        .sorted()
                        .forEach(ids::add);
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
            return ids;
        }
    }

    /**
     * Which asset objects the browser build packs. The rules live in
     * patcher/asset-selection.json so the list is data, not code.
     */
    static final class AssetSelection {
        final Set<String> exact = new LinkedHashSet<>();
        final List<String[]> prefixSuffix = new ArrayList<>();

        static AssetSelection load(Path profiles) {
            Path file = Locations.patcherDir(profiles).resolve("asset-selection.json");
            AssetSelection selection = new AssetSelection();
            Map<String, Object> json = Json.asObject(Json.parse(readString(file)), file.toString());
            for (Object value : Json.array(json, "exact")) {
                selection.exact.add((String) value);
            }
            for (Object value : Json.array(json, "prefixes")) {
                Map<String, Object> rule = Json.asObject(value, "prefix rule");
                Object suffix = rule.get("suffix");
                selection.prefixSuffix.add(new String[] {
                        Json.string(rule, "prefix"), suffix instanceof String s ? s : ""});
            }
            return selection;
        }

        /** Returns logical path to hash for every selected object, sorted. */
        Map<String, String> select(Map<String, Object> objects) {
            Map<String, String> selected = new TreeMap<>();
            for (String path : exact) {
                Object object = objects.get(path);
                if (object == null) {
                    throw new PatcherException("asset index has no " + path);
                }
                selected.put(path, Json.string(Json.asObject(object, path), "hash"));
            }
            for (Map.Entry<String, Object> entry : objects.entrySet()) {
                for (String[] rule : prefixSuffix) {
                    if (entry.getKey().startsWith(rule[0]) && entry.getKey().endsWith(rule[1])) {
                        selected.put(entry.getKey(), Json.string(Json.asObject(entry.getValue(), entry.getKey()), "hash"));
                        break;
                    }
                }
            }
            return selected;
        }
    }

    /** The launcher's library "rules": which operating systems a library is installed on. */
    static final class LibraryRules {
        static boolean appliesHere(Map<String, Object> library) {
            return applies(library, currentOs());
        }

        static boolean applies(Map<String, Object> library, String os) {
            if (!(library.get("rules") instanceof List<?> rules)) {
                return true;
            }
            boolean allowed = false;
            for (Object value : rules) {
                Map<String, Object> rule = Json.asObject(value, "library rule");
                boolean matches = !(rule.get("os") instanceof Map<?, ?> osRule) || os.equals(osRule.get("name"));
                if (matches) {
                    allowed = "allow".equals(rule.get("action"));
                }
            }
            return allowed;
        }

        static String currentOs() {
            String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (name.contains("win")) {
                return "windows";
            }
            return name.contains("mac") ? "osx" : "linux";
        }
    }

    // ------------------------------------------------------------- locations

    static final class Locations {
        static Path minecraftDir(Args args) {
            String explicit = args.options.get("minecraft-dir");
            if (explicit != null) {
                return Paths.get(explicit).toAbsolutePath().normalize();
            }
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            Path home = Paths.get(System.getProperty("user.home"));
            if (os.contains("win")) {
                String appData = System.getenv("APPDATA");
                return (appData != null ? Paths.get(appData) : home.resolve("AppData").resolve("Roaming"))
                        .resolve(".minecraft");
            }
            if (os.contains("mac")) {
                return home.resolve("Library").resolve("Application Support").resolve("minecraft");
            }
            return home.resolve(".minecraft");
        }

        /** Finds the version profiles: --profiles, ./profiles, or port/versions in this or a parent directory. */
        static Path profilesDir(Args args) {
            String explicit = args.options.get("profiles");
            if (explicit != null) {
                Path path = Paths.get(explicit).toAbsolutePath().normalize();
                if (!Files.isDirectory(path)) {
                    throw new PatcherException("--profiles is not a directory: " + path);
                }
                return path;
            }
            Path cwd = Paths.get("").toAbsolutePath();
            Path bundled = cwd.resolve("profiles");
            if (Files.isDirectory(bundled)) {
                return bundled;
            }
            for (Path dir = cwd; dir != null; dir = dir.getParent()) {
                Path candidate = dir.resolve("port").resolve("versions");
                if (Files.isDirectory(candidate)) {
                    return candidate;
                }
            }
            throw new PatcherException("no version profiles found; pass --profiles <dir>");
        }

        /** The patcher's own data directory: next to the profiles in a release, patcher/ in the source tree. */
        static Path patcherDir(Path profiles) {
            Path parent = profiles.getParent();
            if (parent != null && parent.getFileName() != null && parent.getFileName().toString().equals("port")) {
                return parent.resolveSibling("patcher");
            }
            return parent == null ? Paths.get("") : parent;
        }

        static Path workRoot(Args args, Path profiles) {
            String explicit = args.options.get("work-root");
            if (explicit != null) {
                return Paths.get(explicit).toAbsolutePath().normalize();
            }
            Path parent = profiles.getParent();
            if (parent != null && parent.getFileName() != null && parent.getFileName().toString().equals("port")) {
                return parent.resolve("work");
            }
            return Paths.get("").toAbsolutePath().resolve("work");
        }
    }

    // ----------------------------------------------------------------- terms

    /**
     * The terms of use in patcher/TERMS.md. Commands that read game files run
     * only after the user accepts them, interactively or with --accept-terms.
     * The acceptance is recorded in the work root together with the SHA-256
     * of the text, so a changed text is asked for again.
     */
    static final class Terms {
        static final String RECORD = "terms-accepted.json";
        static final String ANSWER = "I AGREE";

        static int show(Args args) {
            Path profiles = Locations.profilesDir(args);
            System.out.println(text(profiles));
            System.out.println(accepted(args, profiles)
                    ? "You accepted these terms."
                    : "You have not accepted these terms yet.");
            return 0;
        }

        static void require(Args args, Path profiles) {
            if (accepted(args, profiles)) {
                return;
            }
            String text = text(profiles);
            if (!args.flags.contains("accept-terms")) {
                java.io.Console console = System.console();
                if (console == null || !isTerminal(console)) {
                    throw new PatcherException("You have not accepted the terms of use yet. Read them with"
                            + " `terms` (or patcher/TERMS.md) and run again with --accept-terms to accept.");
                }
                console.printf("%s%n", text);
                String answer = console.readLine("Type %s to accept these terms: ", ANSWER);
                if (answer == null || !answer.trim().equalsIgnoreCase(ANSWER)) {
                    throw new PatcherException("terms not accepted; nothing was read or written");
                }
            }
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("termsSha256", Hashes.sha256(text.getBytes(StandardCharsets.UTF_8)));
            record.put("acceptedAt", Instant.now().toString());
            record.put("patcherVersion", TOOL_VERSION);
            writeString(Locations.workRoot(args, profiles).resolve(RECORD), Json.write(record) + "\n");
            System.out.println("Terms of use accepted (" + RECORD + ").");
        }

        static boolean accepted(Args args, Path profiles) {
            Path record = Locations.workRoot(args, profiles).resolve(RECORD);
            if (!Files.isRegularFile(record)) {
                return false;
            }
            Object stored = Json.asObject(Json.parse(readString(record)), record.toString()).get("termsSha256");
            return Hashes.sha256(text(profiles).getBytes(StandardCharsets.UTF_8)).equals(stored);
        }

        static String text(Path profiles) {
            Path file = Locations.patcherDir(profiles).resolve("TERMS.md");
            if (!Files.isRegularFile(file)) {
                throw new PatcherException("the terms of use are missing (" + file + "); reinstall the patcher");
            }
            return readString(file).replace("\r\n", "\n");
        }

        /** Console.isTerminal() exists from Java 22; before that a console is always a terminal. */
        static boolean isTerminal(java.io.Console console) {
            try {
                return (Boolean) java.io.Console.class.getMethod("isTerminal").invoke(console);
            } catch (ReflectiveOperationException error) {
                return true;
            }
        }
    }

    static final class Classpath {
        /**
         * Joins jars the way the bash build scripts read classpath.txt:
         * ':'-separated, with Windows drive paths in Git Bash form (/d/...).
         */
        static String join(List<Path> jars) {
            List<String> parts = new ArrayList<>();
            for (Path jar : jars) {
                parts.add(bashPath(jar.toAbsolutePath().toString()));
            }
            return String.join(":", parts);
        }

        static String bashPath(String path) {
            String slashed = path.replace('\\', '/');
            if (slashed.length() >= 2 && slashed.charAt(1) == ':' && Character.isLetter(slashed.charAt(0))) {
                return "/" + Character.toLowerCase(slashed.charAt(0)) + slashed.substring(2);
            }
            return slashed;
        }
    }

    static final class Safe {
        /** A relative path from a Mojang manifest, refused if it could escape its directory. */
        static Path relative(String path) {
            if (path.isEmpty() || path.startsWith("/") || path.startsWith("\\") || path.contains("..")
                    || path.contains(":") || path.contains("\\")) {
                throw new PatcherException("refusing unsafe path from metadata: " + path);
            }
            return Paths.get(path);
        }
    }

    // ---------------------------------------------------------------- hashes

    static final class Hashes {
        static String sha1(Path file) {
            try (InputStream in = Files.newInputStream(file)) {
                MessageDigest digest = MessageDigest.getInstance("SHA-1");
                byte[] buffer = new byte[1 << 16];
                for (int read; (read = in.read(buffer)) > 0; ) {
                    digest.update(buffer, 0, read);
                }
                return HexFormat.of().formatHex(digest.digest());
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            } catch (NoSuchAlgorithmException error) {
                throw new IllegalStateException(error);
            }
        }

        static String sha1(byte[] bytes) {
            return digest("SHA-1", bytes);
        }

        static String sha256(byte[] bytes) {
            return digest("SHA-256", bytes);
        }

        static String digest(String algorithm, byte[] bytes) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
            } catch (NoSuchAlgorithmException error) {
                throw new IllegalStateException(error);
            }
        }
    }

    // ------------------------------------------------------------------ json

    /** A small JSON reader and writer; the patcher deliberately has no dependencies. */
    static final class Json {
        private final String text;
        private int at;

        private Json(String text) {
            this.text = text;
        }

        static Object parse(String text) {
            Json json = new Json(text);
            json.skipSpace();
            Object value = json.value();
            json.skipSpace();
            if (json.at != text.length()) {
                throw json.error("trailing data");
            }
            return value;
        }

        private Object value() {
            if (at >= text.length()) {
                throw error("unexpected end");
            }
            char c = text.charAt(at);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<>();
            at++;
            skipSpace();
            if (peek() == '}') {
                at++;
                return map;
            }
            while (true) {
                skipSpace();
                if (peek() != '"') {
                    throw error("expected a key");
                }
                String key = string();
                skipSpace();
                expect(':');
                skipSpace();
                map.put(key, value());
                skipSpace();
                char c = next();
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw error("expected ',' or '}'");
                }
            }
        }

        private List<Object> array() {
            List<Object> list = new ArrayList<>();
            at++;
            skipSpace();
            if (peek() == ']') {
                at++;
                return list;
            }
            while (true) {
                skipSpace();
                list.add(value());
                skipSpace();
                char c = next();
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw error("expected ',' or ']'");
                }
            }
        }

        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return out.toString();
                }
                if (c == '\\') {
                    char e = next();
                    switch (e) {
                        case '"', '\\', '/' -> out.append(e);
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case 'u' -> {
                            if (at + 4 > text.length()) {
                                throw error("bad unicode escape");
                            }
                            out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                            at += 4;
                        }
                        default -> throw error("bad escape");
                    }
                } else if (c < 0x20) {
                    throw error("control character in string");
                } else {
                    out.append(c);
                }
            }
        }

        private Object number() {
            int start = at;
            while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) {
                at++;
            }
            String token = text.substring(start, at);
            if (token.isEmpty()) {
                throw error("unexpected character");
            }
            try {
                if (token.matches("-?\\d+")) {
                    return Long.parseLong(token);
                }
                return Double.parseDouble(token);
            } catch (NumberFormatException error) {
                throw error("bad number " + token);
            }
        }

        private Object literal(String word, Object value) {
            if (!text.startsWith(word, at)) {
                throw error("unexpected token");
            }
            at += word.length();
            return value;
        }

        private void skipSpace() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
        }

        private char peek() {
            if (at >= text.length()) {
                throw error("unexpected end");
            }
            return text.charAt(at);
        }

        private char next() {
            char c = peek();
            at++;
            return c;
        }

        private void expect(char c) {
            if (next() != c) {
                throw error("expected '" + c + "'");
            }
        }

        private PatcherException error(String message) {
            return new PatcherException("JSON " + message + " at offset " + at);
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> asObject(Object value, String what) {
            if (value instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            throw new PatcherException(what + " is not a JSON object");
        }

        static Map<String, Object> object(Map<String, Object> parent, String key) {
            return asObject(parent.get(key), "'" + key + "'");
        }

        @SuppressWarnings("unchecked")
        static List<Object> array(Map<String, Object> parent, String key) {
            if (parent.get(key) instanceof List<?> list) {
                return (List<Object>) list;
            }
            throw new PatcherException("'" + key + "' is not a JSON array");
        }

        static String string(Map<String, Object> parent, String key) {
            if (parent.get(key) instanceof String value) {
                return value;
            }
            throw new PatcherException("'" + key + "' is not a JSON string");
        }

        static double number(Map<String, Object> parent, String key) {
            if (parent.get(key) instanceof Number value) {
                return value.doubleValue();
            }
            throw new PatcherException("'" + key + "' is not a JSON number");
        }

        static String write(Object value) {
            StringBuilder out = new StringBuilder();
            write(value, out, 0);
            return out.append('\n').toString();
        }

        private static void write(Object value, StringBuilder out, int indent) {
            if (value == null) {
                out.append("null");
            } else if (value instanceof String s) {
                out.append('"');
                for (char c : s.toCharArray()) {
                    switch (c) {
                        case '"' -> out.append("\\\"");
                        case '\\' -> out.append("\\\\");
                        case '\n' -> out.append("\\n");
                        case '\r' -> out.append("\\r");
                        case '\t' -> out.append("\\t");
                        default -> {
                            if (c < 0x20) {
                                out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                            } else {
                                out.append(c);
                            }
                        }
                    }
                }
                out.append('"');
            } else if (value instanceof Map<?, ?> map) {
                if (map.isEmpty()) {
                    out.append("{}");
                    return;
                }
                out.append("{\n");
                int i = 0;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    out.append("  ".repeat(indent + 1));
                    write(String.valueOf(entry.getKey()), out, indent + 1);
                    out.append(": ");
                    write(entry.getValue(), out, indent + 1);
                    out.append(++i < map.size() ? ",\n" : "\n");
                }
                out.append("  ".repeat(indent)).append('}');
            } else if (value instanceof List<?> list) {
                if (list.isEmpty()) {
                    out.append("[]");
                    return;
                }
                out.append("[\n");
                for (int i = 0; i < list.size(); i++) {
                    out.append("  ".repeat(indent + 1));
                    write(list.get(i), out, indent + 1);
                    out.append(i + 1 < list.size() ? ",\n" : "\n");
                }
                out.append("  ".repeat(indent)).append(']');
            } else {
                out.append(value);
            }
        }
    }

    // ------------------------------------------------------------- selftest

    static final class SelfTest {
        static int failures;

        static int run() {
            Map<String, Object> parsed = Json.asObject(
                    Json.parse("{\"a\": [1, 2.5, \"x\\u0041\\n\"], \"b\": {\"c\": true, \"d\": null}}"), "test");
            check("json array", Json.array(parsed, "a").size() == 3);
            check("json long", Long.valueOf(1).equals(Json.array(parsed, "a").get(0)));
            check("json escape", "xA\n".equals(Json.array(parsed, "a").get(2)));
            check("json nested", Boolean.TRUE.equals(Json.object(parsed, "b").get("c")));
            check("json round trip", Json.parse(Json.write(parsed)).equals(parsed));
            check("json rejects trailing data", throwsPatcher(() -> Json.parse("{} x")));

            check("sha1 of empty", "da39a3ee5e6b4b0d3255bfef95601890afd80709".equals(Hashes.sha1(new byte[0])));
            check("sha1 of abc", "a9993e364706816aba3e25717850c26c9cd0d89d"
                    .equals(Hashes.sha1("abc".getBytes(StandardCharsets.US_ASCII))));
            check("sha256 of abc", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
                    .equals(Hashes.sha256("abc".getBytes(StandardCharsets.US_ASCII))));

            check("bash path drive", "/d/gaius/a.jar".equals(Classpath.bashPath("D:\\gaius\\a.jar")));
            check("bash path posix", "/home/u/a.jar".equals(Classpath.bashPath("/home/u/a.jar")));

            check("safe path accepts maven path", Safe.relative("org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar") != null);
            check("safe path rejects parent", throwsPatcher(() -> Safe.relative("../x.jar")));
            check("safe path rejects absolute", throwsPatcher(() -> Safe.relative("/etc/passwd")));
            check("safe path rejects drive", throwsPatcher(() -> Safe.relative("C:/x.jar")));

            AssetSelection selection = new AssetSelection();
            selection.exact.add("minecraft/sounds.json");
            selection.prefixSuffix.add(new String[] {"minecraft/sounds/block/", ".ogg"});
            Map<String, Object> objects = Json.asObject(Json.parse("""
                    {"minecraft/sounds.json": {"hash": "1111111111111111111111111111111111111111"},
                     "minecraft/sounds/block/a.ogg": {"hash": "2222222222222222222222222222222222222222"},
                     "minecraft/sounds/block/a.txt": {"hash": "3333333333333333333333333333333333333333"},
                     "minecraft/sounds/music/b.ogg": {"hash": "4444444444444444444444444444444444444444"}}
                    """), "objects");
            Map<String, String> selected = selection.select(objects);
            check("asset selection size", selected.size() == 2);
            check("asset selection prefix+suffix", selected.containsKey("minecraft/sounds/block/a.ogg"));

            Args args = Args.parse(new String[] {"fetch", "26.3", "--offline", "--minecraft-dir", "/x"});
            check("args command", "fetch".equals(args.command));
            check("args positional", List.of("26.3").equals(args.positional));
            check("args flag", args.flags.contains("offline"));

            Map<String, Object> windowsOnly = Json.asObject(Json.parse(
                    "{\"rules\": [{\"action\": \"allow\", \"os\": {\"name\": \"windows\"}}]}"), "lib");
            Map<String, Object> notMac = Json.asObject(Json.parse(
                    "{\"rules\": [{\"action\": \"allow\"}, {\"action\": \"disallow\", \"os\": {\"name\": \"osx\"}}]}"),
                    "lib");
            check("rules: no rules applies", LibraryRules.applies(new LinkedHashMap<>(), "linux"));
            check("rules: windows only", LibraryRules.applies(windowsOnly, "windows")
                    && !LibraryRules.applies(windowsOnly, "linux"));
            check("rules: all but mac", LibraryRules.applies(notMac, "linux") && !LibraryRules.applies(notMac, "osx"));
            check("args option", "/x".equals(args.options.get("minecraft-dir")));

            System.out.println(failures == 0 ? "selftest: all checks passed" : "selftest: " + failures + " failed");
            return failures == 0 ? 0 : 1;
        }

        static void check(String name, boolean ok) {
            if (!ok) {
                failures++;
                System.out.println("FAIL " + name);
            }
        }

        static boolean throwsPatcher(Runnable action) {
            try {
                action.run();
                return false;
            } catch (PatcherException expected) {
                return true;
            }
        }
    }

    // ------------------------------------------------------------------ misc

    static final class Args {
        String command = "";
        final List<String> positional = new ArrayList<>();
        final Map<String, String> options = new LinkedHashMap<>();
        final Set<String> flags = new LinkedHashSet<>();
        static final Set<String> VALUE_OPTIONS =
                Set.of("minecraft-dir", "profiles", "work", "work-root", "mappings");

        static Args parse(String[] argv) {
            Args args = new Args();
            for (int i = 0; i < argv.length; i++) {
                String arg = argv[i];
                if (arg.startsWith("--") && arg.length() > 2) {
                    String name = arg.substring(2);
                    int equals = name.indexOf('=');
                    if (equals > 0) {
                        args.options.put(name.substring(0, equals), name.substring(equals + 1));
                    } else if (VALUE_OPTIONS.contains(name)) {
                        if (i + 1 >= argv.length) {
                            throw new PatcherException("--" + name + " needs a value");
                        }
                        args.options.put(name, argv[++i]);
                    } else {
                        args.flags.add(name);
                    }
                } else if (args.command.isEmpty()) {
                    args.command = arg;
                } else {
                    args.positional.add(arg);
                }
            }
            return args;
        }

        int intOption(String name, int fallback) {
            String value = options.get(name);
            if (value == null) {
                return fallback;
            }
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException error) {
                throw new PatcherException("--" + name + " is not a number: " + value);
            }
        }
    }

    static final class PatcherException extends RuntimeException {
        PatcherException(String message) {
            super(message);
        }
    }

    static void requireEqual(String what, String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new PatcherException(what + " is " + actual + ", expected " + expected
                    + ". This patcher does not support this game build; update the patcher.");
        }
    }

    static String readString(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    static void writeString(Path file, String text) {
        try {
            createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    static void createDirectories(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new PatcherException("interrupted");
        }
    }
}
