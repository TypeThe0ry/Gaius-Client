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
 * version in the official launcher. The one game download is the 1.21.11
 * obfuscation mappings, which the launcher does not keep; they come straight
 * from the Mojang URL in the user's own version metadata. Every file is
 * checked against the SHA-1 that the version profile (port/versions/<id>.json)
 * pins. Third-party build tools and dependencies are checked against the
 * SHA-256 values pinned in patcher/toolchain.lock.
 *
 * SPDX-License-Identifier: MIT
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class GaiusPatcher {
    /**
     * The patcher is versioned with the Gaius release it reproduces: patcher
     * 0.4.0-pre.6 builds the same client as Gaius release v0.4.0-pre.6, and
     * that client reports GAIUS_VERSION (the release's VERSION file) in its
     * About screen, exactly like the official build.
     */
    static final String GAIUS_RELEASE = "0.4.0-pre.6";
    static final String GAIUS_VERSION = "0.4.0";
    static final String TOOL_VERSION = GAIUS_RELEASE;

    public static void main(String[] args) {
        Args parsed;
        try {
            parsed = Args.parse(args);
        } catch (PatcherException error) {
            if (Arrays.asList(args).contains("--json-progress")) {
                Build.errorProgress(error.getMessage());
            } else {
                System.err.println("error: " + error.getMessage());
            }
            System.exit(1);
            return;
        }
        try {
            int status = switch (parsed.command) {
                case "doctor" -> Doctor.run(parsed);
                case "fetch" -> Fetch.run(parsed);
                case "prepare" -> Prepare.run(parsed);
                case "decompile" -> Decompile.run(parsed);
                case "build" -> Build.run(parsed);
                case "manifest" -> ReproManifest.generate(parsed);
                case "verify" -> ReproManifest.verify(parsed);
                case "terms" -> Terms.show(parsed);
                case "selftest" -> SelfTest.run();
                case "version", "--version" -> {
                    System.out.println("gaius-patcher " + TOOL_VERSION);
                    System.out.println("builds Gaius release v" + GAIUS_RELEASE + " (client version " + GAIUS_VERSION + ")");
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
            if (parsed.flags.contains("json-progress")) {
                Build.errorProgress(error.getMessage());
            } else {
                System.err.println("error: " + error.getMessage());
            }
            System.exit(1);
        } catch (RuntimeException | IOException error) {
            if (parsed.flags.contains("json-progress")) {
                Build.errorProgress(error.toString());
            } else {
                System.err.println("error: " + error);
            }
            System.exit(1);
        }
    }

    static void printHelp() {
        System.out.println("""
                gaius-patcher %s (builds Gaius release v%s, client version %s)

                Builds a personal Gaius client from your own installed copy of the game.
                Install and start each version once in the official launcher first.
                The result contains official game code and assets: it is for your own
                use only. Do not share it or host it on a public website.

                Not an official Minecraft product. Not approved by or associated with
                Mojang or Microsoft. You must own Minecraft: Java Edition and accept the
                Minecraft EULA (https://www.minecraft.net/eula).

                Commands:
                  doctor                 Check Java, memory, disk space and your game installation
                  fetch <version>        Collect local game files and provision locked build tools
                  prepare <version>      Decompile the few official classes Gaius changes from the
                                         fetched files and apply Gaius's source patches to them
                  build <version>        Fetch, patch and compile one offline browser build
                  manifest <build-output-dir>  Write a reproduction manifest for the current OS
                  verify <build-output-dir>    Verify the site and portable HTML against a manifest
                  terms                  Show the terms of use and whether you accepted them
                  selftest               Run the built-in offline checks
                  version                Print the patcher version

                Options:
                  --minecraft-dir <dir>  Your launcher's game directory (default: the OS location)
                  --profiles <dir>       Directory holding the version profiles (<id>.json)
                  --work <dir>           Where fetched files are written (default: per version)
                  --mappings <file>      Use this client_mappings file for 1.21.11 instead of
                                         downloading it from Mojang
                  --shader-toolchain <dir> Use a verified prebuilt shader toolchain
                  --toolchain-dir <dir>   Use this local cache for verified build tools
                  --quality <tier>       First-run quality tier: low, mid, high, ultra
                  --render-distance <n>  First-run render distance in chunks (2 through 32)
                  --backend <choice>      Graphics backend preference: auto, webgpu, webgl2
                  --resource-pack <file>  Copy a local resource pack into the site (repeatable)
                  --decompiler <jar>     Use this copy of the pinned decompiler instead of
                                         downloading it from Maven Central
                  --offline              Never use the network
                  --json-progress       Print one JSON progress object per line to stdout
                  --manifest <file>     Reference manifest to use with verify
                  --out <dir>           Output directory for build or manifest
                  --accept-terms         Accept the terms of use (patcher/TERMS.md) without
                                         the interactive prompt
                """.formatted(TOOL_VERSION, GAIUS_RELEASE, GAIUS_VERSION));
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
            return run(args, true);
        }

        static int run(Args args, boolean provisionTools) {
            if (args.positional.isEmpty()) {
                throw new PatcherException("usage: fetch <version>");
            }
            Path profiles = Locations.profilesDir(args);
            VersionProfile profile = VersionProfile.load(profiles, args.positional.get(0));
            Terms.require(args, profiles);
            Path lockFile = Locations.patcherDir(profiles).resolve("toolchain.lock");
            if (provisionTools && Files.isRegularFile(lockFile)) {
                ToolchainManager manager = new ToolchainManager(lockFile, args, Locations.workRoot(args, profiles));
                if (manager.lock.containsKey("mavenRepository")) {
                    manager.prepare(profiles, profile.id, args);
                }
            }
            Path minecraftDir = Locations.minecraftDir(args);
            Path work = Locations.workDir(args, profiles, profile);
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
            manifest.put("gaiusRelease", GAIUS_RELEASE);
            manifest.put("gaiusVersion", GAIUS_VERSION);
            manifest.put("profile", profile.id);
            manifest.put("fetchedAt", fetchedAt(args));
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

        static String fetchedAt(Args args) {
            return args.flags.contains("reproducible") ? Instant.EPOCH.toString() : Instant.now().toString();
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

        /** The fetched files of one version: --work, else <work root>/<id>. */
        static Path workDir(Args args, Path profiles, VersionProfile profile) {
            String explicit = args.options.get("work");
            if (explicit != null) {
                return Paths.get(explicit).toAbsolutePath().normalize();
            }
            return workRoot(args, profiles).resolve(profile.id);
        }

        /** The source tree the patch targets are written into: the parent of patcher/. */
        static Path sourceRoot(Path profiles) {
            Path parent = patcherDir(profiles).toAbsolutePath().getParent();
            if (parent == null) {
                throw new PatcherException("cannot tell where the source tree is; pass --into <dir>");
            }
            return parent;
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

    // ------------------------------------------------------------- toolchain

    static final class ToolchainManager {
        record Artifact(String name, String file, String url, String sha256, long bytes) {}
        record MavenArtifact(Path path, Artifact artifact) {}
        record HostArchive(Path file, Path root, List<String> members) {}
        record HostWasm(HostArchive binaryen, HostArchive llvm, List<Path> linuxRuntime,
                        String binaryenVersion, String llvmVersion, String suffix) {
            void stage(Path target) {
                createDirectories(target);
                for (HostArchive archive : List.of(binaryen, llvm)) {
                    List<String> command = new ArrayList<>(List.of("tar", "-xf", archive.file().toString(),
                            "-C", target.toString(), "--"));
                    command.addAll(archive.members());
                    runExtraction(command, archive.file());
                }
                Path runtime = target.resolve("runtime");
                for (Path packageFile : linuxRuntime) {
                    runExtraction(List.of("dpkg-deb", "-x", packageFile.toString(), runtime.toString()), packageFile);
                }
            }

            void configure(Path target, Map<String, String> environment) {
                environment.put("GAIUS_CLANG", executable(target, llvm, "clang"));
                environment.put("GAIUS_WASM_LD", executable(target, llvm, "wasm-ld"));
                environment.put("GAIUS_WASM_OPT", executable(target, binaryen, "wasm-opt"));
                environment.put("GAIUS_LLVM_VERSION", llvmVersion);
                environment.put("GAIUS_WASM_OPT_VERSION", binaryenVersion);
                environment.put("GAIUS_LLVM_LIBRARY_DIR", linuxRuntime.isEmpty() ? ""
                        : target.resolve("runtime/usr/lib/x86_64-linux-gnu").toString());
            }

            private String executable(Path target, HostArchive archive, String name) {
                Path path = target.resolve(archive.root()).resolve("bin").resolve(name + suffix);
                if (!Files.isRegularFile(path) || !Files.isExecutable(path)) {
                    throw new PatcherException("missing executable " + name + " in pinned " + archive.file());
                }
                return path.toString().replace('\\', '/');
            }
        }
        record Prepared(Path mavenHome, Path vineflower, Path mavenRepository, Path shaderToolchain,
                        Path jdkHome, boolean stagedJdk, String mavenVersion,
                        List<MavenArtifact> mavenArtifacts, Path remapper, HostWasm hostWasm) {}

        final Path lockFile;
        final Map<String, Object> lock;
        final Path workRoot;
        final Path cache;
        final Path externalCache;
        final boolean offline;
        private HttpClient downloadClient;

        ToolchainManager(Path lockFile, Args args, Path workRoot) {
            this.lockFile = lockFile;
            if (!Files.isRegularFile(lockFile)) {
                throw new PatcherException("the toolchain lock is missing: " + lockFile);
            }
            this.lock = Json.asObject(Json.parse(readString(lockFile)), lockFile.toString());
            if (Json.integer(lock, "schema") != 1) {
                throw new PatcherException(lockFile + ": unsupported toolchain lock schema");
            }
            this.workRoot = workRoot.toAbsolutePath().normalize();
            this.cache = this.workRoot.resolve("tools");
            String external = args.options.get("toolchain-dir");
            this.externalCache = external == null ? null : Paths.get(external).toAbsolutePath().normalize();
            this.offline = args.flags.contains("offline");
        }

        Prepared prepare(Path profiles, String version, Args args) {
            Path sourceRoot = Locations.sourceRoot(profiles);
            Map<String, Object> config = Json.asObject(Json.parse(readString(sourceRoot.resolve("port/config.json"))),
                    "port/config.json");
            String mavenVersion = Json.string(lock, "maven", "version");
            String configuredMaven = Json.string(config, "mavenVersion");
            String teavmVersion = Json.string(lock, "teavm", "version");
            String configuredTeaVM = Json.string(config, "teaVMVersion");
            if (!mavenVersion.equals(configuredMaven)) {
                throw new PatcherException("toolchain.lock Maven " + mavenVersion
                        + " does not match port/config.json " + configuredMaven);
            }
            if (!teavmVersion.equals(configuredTeaVM)) {
                throw new PatcherException("toolchain.lock TeaVM " + teavmVersion
                        + " does not match port/config.json " + configuredTeaVM);
            }

            Map<String, Object> patchConfig = Json.asObject(Json.parse(readString(
                    Locations.patcherDir(profiles).resolve("source-patches.json"))), "source-patches.json");
            String patchDecompilerHash = Json.string(Json.object(patchConfig, "decompiler"), "sha256");
            Map<String, Object> vineflowerPin = Json.object(lock, "vineflower");
            String vineflowerHash = Json.string(vineflowerPin, "sha256");
            if (!vineflowerHash.equals(patchDecompilerHash)) {
                throw new PatcherException("toolchain.lock Vineflower SHA-256 does not match source-patches.json");
            }

            verifyKernelPins(sourceRoot);
            Path jdkHome = verifyRuntimeJdk(args, version);
            boolean stagedJdk = !jdkHome.equals(Paths.get(System.getProperty("java.home")).toAbsolutePath().normalize());

            Path shader = version.equals("26.3") ? resolveShaderToolchain(args) : null;
            if (version.equals("26.3")) {
                verifyShaderToolchain(shader);
            }

            Artifact maven = artifact("Maven", Json.object(lock, "maven"));
            Artifact vineflower = artifact("Vineflower", vineflowerPin);
            Path mavenArchive = ensure(maven);
            Path mavenHome = ensureMavenHome(maven, mavenArchive);
            Path vineflowerJar = args.options.containsKey("decompiler")
                    ? Paths.get(args.options.get("decompiler")).toAbsolutePath().normalize()
                    : ensure(vineflower);
            verifyFile("Vineflower", vineflowerJar, vineflower.sha256());

            List<MavenArtifact> mavenArtifacts = lockedMavenArtifacts(version);
            Path repository = mavenArtifacts.isEmpty()
                    ? findMavenRepository(Json.array(Json.object(lock, "teavm"), "artifacts"))
                    : ensureMavenRepository(mavenArtifacts);
            Path remapper = null;
            if (!mavenArtifacts.isEmpty() && VersionProfile.load(profiles, version).needsMappings()) {
                String remapperFile = "AutoRenamingTool-" + Json.string(config, "autoRenamingToolVersion") + "-all.jar";
                remapper = mavenArtifacts.stream()
                        .filter(entry -> entry.artifact().file().equals(remapperFile))
                        .map(entry -> repository.resolve(entry.path()))
                        .findFirst().orElseThrow(() -> new PatcherException(lockFile + ": missing " + remapperFile));
            }
            return new Prepared(mavenHome, vineflowerJar, repository, shader, jdkHome, stagedJdk, mavenVersion,
                    mavenArtifacts, remapper, prepareHostWasm());
        }

        HostWasm prepareHostWasm() {
            String platform = hostPlatformKey();
            Map<String, Object> host = Json.object(Json.object(lock, "wasm"), "hostTools");
            Map<String, Object> binaryen = Json.object(host, "binaryen");
            Map<String, Object> llvm = Json.object(host, "llvm");
            String binaryenVersion = Json.string(binaryen, "version");
            String llvmVersion = Json.string(llvm, "version");
            HostArchive optimizer = hostArchive("Binaryen " + binaryenVersion, binaryen, platform);
            HostArchive compiler = hostArchive("LLVM " + llvmVersion, llvm, platform);
            List<Path> runtime = new ArrayList<>();
            if (platform.equals("linux-x64")) {
                for (Object value : Json.array(host, "linuxRuntime")) {
                    Map<String, Object> pin = Json.asObject(value, "LLVM Linux runtime package");
                    runtime.add(ensure(artifact("LLVM Linux runtime " + Json.string(pin, "file"), pin)));
                }
            }
            return new HostWasm(optimizer, compiler, List.copyOf(runtime), binaryenVersion, llvmVersion,
                    platform.equals("windows-x64") ? ".exe" : "");
        }

        private HostArchive hostArchive(String name, Map<String, Object> tool, String platform) {
            Map<String, Object> pin = Json.object(Json.object(tool, "distributions"), platform);
            Path root = Safe.relative(Json.string(pin, "root"));
            List<String> members = new ArrayList<>();
            for (Object member : Json.array(pin, "members")) {
                if (!(member instanceof String relative)) {
                    throw new PatcherException(lockFile + ": invalid archive member for " + name);
                }
                members.add(root.resolve(Safe.relative(relative)).toString().replace('\\', '/'));
            }
            if (members.isEmpty()) throw new PatcherException(lockFile + ": no archive members for " + name);
            return new HostArchive(ensure(artifact(name, pin)), root, List.copyOf(members));
        }

        void preflight(String version, Args args) {
            if (!version.equals("26.3")) return;
            verifyShaderToolchain(resolveShaderToolchain(args));
        }

        private Path verifyRuntimeJdk(Args args, String profileVersion) {
            VersionProfile profile = VersionProfile.load(Locations.profilesDir(args), profileVersion);
            int required = profile.javaVersion;
            int current;
            try {
                current = Integer.parseInt(System.getProperty("java.specification.version", "0"));
            } catch (NumberFormatException error) {
                current = 0;
            }
            Path currentJavaHome = Paths.get(System.getProperty("java.home")).toAbsolutePath().normalize();
            if (current >= required && Files.isRegularFile(jdkExecutable(currentJavaHome, "javac"))) {
                return currentJavaHome;
            }
            Map<String, Object> jdk = Json.object(lock, "jdk");
            int lockedFeature = Json.integer(jdk, "feature");
            String key = hostPlatformKey();
            Map<String, Object> distribution = Json.object(Json.object(jdk, "distributions"), key);
            Artifact artifact = artifact("JDK " + Json.string(jdk, "version"), distribution);
            Path archive = ensure(artifact);
            Path install = cache.resolve("jdk-" + lockedFeature + "-unpack");
            Path marker = install.resolve(".gaius-toolchain-sha256");
            if (Files.exists(install)) {
                if (!Files.isRegularFile(marker)
                        || !readString(marker).trim().equals(artifact.sha256())) {
                    throw new PatcherException("JDK tool directory is unverified or modified: " + install
                            + "; expected SHA-256 " + artifact.sha256());
                }
            } else {
                extractArchive(archive, install, artifact.file().endsWith(".zip"));
                writeString(marker, artifact.sha256() + "\n");
            }
            try (var entries = Files.list(install)) {
                Path home = entries.filter(path -> Files.isRegularFile(path.resolve("release"))
                                && Files.isRegularFile(jdkExecutable(path, "javac")))
                        .findFirst().orElseThrow(() -> new PatcherException("JDK archive has no JDK home: " + install));
                return home.toAbsolutePath().normalize();
            } catch (IOException error) {
                throw new PatcherException("could not inspect staged JDK at " + install + ": " + error.getMessage());
            }
        }

        private String hostPlatformKey() {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
            if (arch.equals("amd64") || arch.equals("x86_64")) {
                if (os.contains("linux")) return "linux-x64";
                if (os.contains("win")) return "windows-x64";
            }
            throw new PatcherException("no pinned host toolchain for " + os + " / " + arch
                    + "; use Linux x86-64 or Windows x86-64");
        }

        private static Path jdkExecutable(Path home, String name) {
            Path executable = home.resolve("bin").resolve(name);
            if (Files.isRegularFile(executable)) return executable;
            return home.resolve("bin").resolve(name + ".exe");
        }

        private void verifyKernelPins(Path sourceRoot) {
            Map<String, Object> kernel = Json.object(Json.object(lock, "wasm"), "kernel");
            Map<String, Object> shader = Json.object(Json.object(lock, "wasm"), "shader");
            verifyFile("Rust kernel Cargo.lock", sourceRoot.resolve("port/native/Cargo.lock"),
                    Json.string(kernel, "cargoLockSha256"));
            verifyFile("kernel mirror lock", sourceRoot.resolve("port/native/kernel-mirror.toml"),
                    Json.string(kernel, "kernelMirrorSha256"));
            verifyFile("shader source pins", sourceRoot.resolve("port/wasm/shader-toolchain/pins.env"),
                    Json.string(shader, "pinsSha256"));
        }

        private Artifact artifact(String name, Map<String, Object> pin) {
            String file = Json.string(pin, "file");
            if (!file.matches("[A-Za-z0-9_.+-]+") || file.equals(".") || file.equals("..")) {
                throw new PatcherException(lockFile + ": unsafe artifact filename for " + name);
            }
            long bytes = ((Number) pin.get("bytes")).longValue();
            String hash = Json.string(pin, "sha256");
            if (!hash.matches("[0-9a-f]{64}")) {
                throw new PatcherException(lockFile + ": invalid SHA-256 for " + name);
            }
            return new Artifact(name, file, pin.containsKey("url") ? Json.string(pin, "url") : null, hash, bytes);
        }

        Path ensure(Artifact artifact) {
            return ensure(artifact, () -> missingTool(artifact.name(), artifact.sha256(),
                    cache.resolve(artifact.file())));
        }

        private Path ensure(Artifact artifact, Supplier<PatcherException> missing) {
            Path target = cache.resolve(artifact.file());
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                verifyArtifact(artifact, target);
                return target;
            }
            Path source = findArtifactSource(artifact.file());
            if (source != null) {
                installArtifact(artifact, source, target);
            } else if (offline || artifact.url() == null || artifact.url().isBlank()) {
                throw missing.get();
            } else {
                installArtifact(artifact, null, target);
            }
            return target;
        }

        private Path findArtifactSource(String file) {
            List<Path> candidates = new ArrayList<>();
            if (externalCache != null) {
                candidates.add(externalCache.resolve(file));
                candidates.add(externalCache.resolve("files").resolve(file));
                candidates.add(externalCache.resolve("artifacts").resolve(file));
            }
            candidates.add(cache.resolve(file));
            candidates.add(cache.resolve("cache").resolve(file));
            return candidates.stream().filter(Files::isRegularFile).findFirst().orElse(null);
        }

        List<MavenArtifact> lockedMavenArtifacts(String profile) {
            if (!lock.containsKey("mavenRepository")) return List.of();
            Map<String, Object> repository = Json.object(lock, "mavenRepository");
            if (!Json.array(repository, "profiles").contains(profile)) {
                throw new PatcherException(lockFile + ": Maven dependencies are not locked for profile " + profile);
            }
            Map<Path, MavenArtifact> entries = new LinkedHashMap<>();
            for (Object value : Json.array(repository, "artifacts")) {
                Map<String, Object> pin = Json.asObject(value, "Maven artifact");
                String relative = Json.string(pin, "path");
                if (!relative.matches("[A-Za-z0-9_.+-]+(?:/[A-Za-z0-9_.+-]+)+\\.(jar|pom)")
                        || Arrays.asList(relative.split("/")).contains(".")) {
                    throw new PatcherException(lockFile + ": invalid Maven artifact path: " + relative);
                }
                Path path = Safe.relative(relative);
                String hash = Json.string(pin, "sha256");
                Object size = pin.get("bytes");
                if (!hash.matches("[0-9a-f]{64}") || !(size instanceof Long bytes) || bytes <= 0) {
                    throw new PatcherException(lockFile + ": invalid Maven artifact hash or size: " + relative);
                }
                Artifact artifact = new Artifact("Maven artifact " + relative, path.getFileName().toString(),
                        Json.string(pin, "url"), hash, bytes);
                if (entries.putIfAbsent(path, new MavenArtifact(path, artifact)) != null) {
                    throw new PatcherException(lockFile + ": duplicate Maven artifact: " + relative);
                }
            }
            if (entries.isEmpty()) throw new PatcherException(lockFile + ": empty Maven dependency closure");
            for (Object value : Json.array(Json.object(lock, "teavm"), "artifacts")) {
                Map<String, Object> pin = Json.asObject(value, "TeaVM artifact");
                String relative = Json.string(pin, "path");
                MavenArtifact entry = entries.get(Safe.relative(relative));
                if (entry == null || !entry.artifact().sha256().equals(Json.string(pin, "sha256"))) {
                    throw new PatcherException(lockFile + ": inconsistent TeaVM pin for " + relative);
                }
            }
            return List.copyOf(entries.values());
        }

        Path ensureMavenRepository(List<MavenArtifact> entries) {
            Path repository = workRoot.resolve("maven-repository");
            for (MavenArtifact entry : entries) {
                Artifact artifact = entry.artifact();
                Path target = repository.resolve(entry.path());
                // The writable repository contains plain files. Use --toolchain-dir for a shared cache.
                for (Path path = target; !path.equals(workRoot); path = path.getParent()) {
                    if (Files.isSymbolicLink(path)) {
                        throw new PatcherException("symbolic link in writable Maven repository: " + path);
                    }
                }
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    verifyArtifact(artifact, target);
                    continue;
                }
                Path source = null;
                if (externalCache != null) {
                    for (String name : List.of("maven-repository", "repository")) {
                        Path candidate = externalCache.resolve(name).resolve(entry.path());
                        if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                            source = candidate;
                            break;
                        }
                    }
                }
                if (source == null && offline) {
                    throw missingTool(artifact.name(), artifact.sha256(), target);
                }
                installArtifact(artifact, source, target);
            }
            return repository;
        }

        static void stageMavenRepository(Path repository, Path stage, List<MavenArtifact> entries) {
            for (MavenArtifact entry : entries) {
                Path target = stage.resolve(entry.path());
                Build.copyFile(repository.resolve(entry.path()), target);
                verifyArtifact(entry.artifact(), target);
            }
        }

        private static void verifyArtifact(Artifact artifact, Path path) {
            verifyFile(artifact.name(), path, artifact.sha256());
            try {
                long size = Files.size(path);
                if (artifact.bytes() > 0 && size != artifact.bytes()) {
                    throw new PatcherException("size mismatch for " + artifact.name() + " at " + path
                            + ": expected " + artifact.bytes() + " bytes, got " + size);
                }
            } catch (IOException error) {
                throw new PatcherException("could not check size of " + artifact.name() + ": " + error.getMessage());
            }
        }

        private Path findMavenRepository(List<Object> artifacts) {
            List<Path> candidates = new ArrayList<>();
            if (externalCache != null) {
                candidates.add(externalCache.resolve("maven-repository"));
                candidates.add(externalCache.resolve("repository"));
            }
            candidates.add(workRoot.resolve("maven-repository"));
            candidates.add(Paths.get(System.getProperty("user.home"), ".m2", "repository"));
            Path seed = null;
            for (Path candidate : candidates) {
                if (!Files.isDirectory(candidate)) continue;
                boolean complete = true;
                for (Object value : artifacts) {
                    Map<String, Object> item = Json.asObject(value, "TeaVM artifact");
                    Path file = candidate.resolve(Safe.relative(Json.string(item, "path")));
                    if (!Files.isRegularFile(file)) {
                        complete = false;
                        continue;
                    }
                    verifyFile("TeaVM " + file.getFileName(), file, Json.string(item, "sha256"));
                }
                if (complete) return candidate;
                if (seed == null) seed = candidate;
            }
            Path repository = workRoot.resolve("maven-repository");
            if (offline) {
                Path hintRoot = mavenRepositoryHintRoot();
                Map<String, Object> missing = firstMissingArtifact(hintRoot, artifacts);
                String path = Json.string(missing, "path");
                throw missingTool("TeaVM " + path, Json.string(missing, "sha256"),
                        hintRoot.resolve(Safe.relative(path)));
            }

            if (!repository.equals(seed)) {
                createDirectories(repository);
                if (seed != null) Build.copyTree(seed, repository, path -> false);
            }
            String base = Json.string(Json.object(lock, "teavm"), "repository");
            for (Object value : artifacts) {
                Map<String, Object> item = Json.asObject(value, "TeaVM artifact");
                String path = Json.string(item, "path");
                Path target = repository.resolve(Safe.relative(path));
                if (Files.isRegularFile(target)) {
                    verifyFile("TeaVM " + target.getFileName(), target, Json.string(item, "sha256"));
                    continue;
                }
                installArtifact(new Artifact("TeaVM " + path, target.getFileName().toString(), base + path,
                        Json.string(item, "sha256"), -1), null, target);
            }
            return repository;
        }

        private Map<String, Object> firstMissingArtifact(Path repository, List<Object> artifacts) {
            for (Object value : artifacts) {
                Map<String, Object> item = Json.asObject(value, "TeaVM artifact");
                if (!Files.isRegularFile(repository.resolve(Safe.relative(Json.string(item, "path"))))) {
                    return item;
                }
            }
            return Json.asObject(artifacts.get(0), "TeaVM artifact");
        }

        private Path mavenRepositoryHintRoot() {
            if (externalCache != null) {
                Path mavenRepository = externalCache.resolve("maven-repository");
                if (Files.isDirectory(mavenRepository)) return mavenRepository;
                Path repository = externalCache.resolve("repository");
                if (Files.isDirectory(repository)) return repository;
                return mavenRepository;
            }
            Path repository = workRoot.resolve("maven-repository");
            if (Files.isDirectory(repository)) return repository;
            return Paths.get(System.getProperty("user.home"), ".m2", "repository");
        }

        private void installArtifact(Artifact artifact, Path source, Path target) {
            createDirectories(target.getParent());
            Path partial = null;
            try {
                partial = Files.createTempFile(target.getParent(), ".gaius-artifact-", ".part");
                if (source == null) {
                    URI uri = URI.create(artifact.url());
                    if ("file".equals(uri.getScheme())) {
                        Files.copy(Paths.get(uri), partial, StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        if (!"https".equals(uri.getScheme())) {
                            throw new PatcherException("unsupported URL for " + artifact.name() + ": " + uri);
                        }
                        System.err.println("  downloading " + artifact.name() + " from " + uri);
                        if (downloadClient == null) {
                            downloadClient = HttpClient.newBuilder()
                                    .followRedirects(HttpClient.Redirect.NORMAL)
                                    .proxy(ProxySelector.getDefault())
                                    .connectTimeout(Duration.ofSeconds(20))
                                    .build();
                        }
                        HttpResponse<Path> response = downloadClient.send(HttpRequest.newBuilder(uri)
                                .timeout(Duration.ofMinutes(10)).GET().build(),
                                HttpResponse.BodyHandlers.ofFile(partial));
                        if (response.statusCode() != 200) {
                            throw new PatcherException("could not download " + artifact.name() + ": HTTP "
                                    + response.statusCode() + " from " + uri + ". Place it at " + target + ".");
                        }
                    }
                } else {
                    Files.copy(source, partial, StandardCopyOption.REPLACE_EXISTING);
                }
                verifyArtifact(artifact, partial);
                Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException error) {
                throw new PatcherException("could not install " + artifact.name() + " at " + target
                        + ": " + error.getMessage());
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new PatcherException("interrupted while downloading " + artifact.name());
            } finally {
                if (partial != null) {
                    try {
                        Files.deleteIfExists(partial);
                    } catch (IOException ignored) {
                    }
                }
            }
        }

        private Path ensureMavenHome(Artifact artifact, Path archive) {
            Path home = cache.resolve("apache-maven-" + artifact.file()
                    .replace("apache-maven-", "").replace("-bin.tar.gz", ""));
            Path marker = home.resolve(".gaius-toolchain-sha256");
            if (Files.isExecutable(home.resolve("bin/mvn"))) {
                if (!Files.isRegularFile(marker) || !readString(marker).trim().equals(artifact.sha256())) {
                    throw new PatcherException("Maven tool directory is unverified or modified: " + home
                            + "; expected archive SHA-256 " + artifact.sha256());
                }
                return home;
            }
            if (Files.exists(home)) {
                throw new PatcherException("Maven tool directory is incomplete or modified: " + home
                        + "; expected archive SHA-256 " + artifact.sha256());
            }
            Path unpack = cache.resolve(".maven-unpack-" + Long.toUnsignedString(System.nanoTime()));
            extractArchive(archive, unpack, false);
            try (var entries = Files.list(unpack)) {
                Path extracted = entries.filter(path -> Files.isExecutable(path.resolve("bin/mvn")))
                        .findFirst().orElseThrow(() -> new PatcherException("Maven archive has no bin/mvn: " + archive));
                Files.move(extracted, home, StandardCopyOption.ATOMIC_MOVE);
                writeString(home.resolve(".gaius-toolchain-sha256"), artifact.sha256() + "\n");
                Build.deleteTree(unpack);
                return home;
            } catch (IOException error) {
                Build.deleteTree(unpack);
                throw new PatcherException("could not stage Maven " + artifact.name() + ": " + error.getMessage());
            }
        }

        Path resolveShaderToolchain(Args args) {
            String explicit = args.options.get("shader-toolchain");
            if (explicit == null || explicit.isBlank()) explicit = System.getenv("GAIUS_SHADER_TOOLCHAIN_PREBUILT");
            if (explicit != null && !explicit.isBlank()) {
                return Paths.get(explicit).toAbsolutePath().normalize();
            }
            if (externalCache != null && Files.isDirectory(externalCache.resolve("shader-toolchain"))) {
                return externalCache.resolve("shader-toolchain");
            }
            Path cached = cache.resolve("shader-toolchain");
            if (Files.exists(cached, LinkOption.NOFOLLOW_LINKS)) return cached;
            Map<String, Object> shader = Json.object(Json.object(lock, "wasm"), "shader");
            if (!shader.containsKey("prebuiltArchive")) return null;
            Artifact pin = artifact("26.3 prebuilt shader toolchain archive", Json.object(shader, "prebuiltArchive"));
            Path archive = ensure(pin, () -> new PatcherException("missing " + pin.name() + " " + pin.file()
                    + "; expected SHA-256 " + pin.sha256() + ". "
                    + (offline ? "Offline mode cannot download it. " : "No download URL is configured. ")
                    + "Place the ZIP at " + cache.resolve(pin.file())
                    + " or put it in the directory passed to --toolchain-dir <dir>. "
                    + "For an unpacked verified copy, pass --shader-toolchain <dir>."));
            Set<String> expected = new LinkedHashSet<>(Json.object(shader, "files").keySet());
            expected.add(Json.string(shader, "manifest"));
            Path unpack = null;
            try {
                unpack = Files.createTempDirectory(cache, ".shader-toolchain-");
                try (ZipFile zip = new ZipFile(archive.toFile())) {
                    var entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        String name = entry.getName();
                        if (entry.isDirectory() || !expected.remove(name) || !Safe.relative(name).getFileName()
                                .toString().equals(name)) {
                            throw new PatcherException("unexpected shader archive entry: " + name);
                        }
                        try (InputStream input = zip.getInputStream(entry)) {
                            Files.copy(input, unpack.resolve(name));
                        }
                    }
                }
                verifyShaderToolchain(unpack);
                Files.move(unpack, cached, StandardCopyOption.ATOMIC_MOVE);
                return cached;
            } catch (IOException error) {
                throw new PatcherException("could not install shader toolchain archive " + archive
                        + ": " + error.getMessage());
            } finally {
                if (unpack != null) Build.deleteTree(unpack);
            }
        }

        private void verifyShaderToolchain(Path directory) {
            Map<String, Object> shader = Json.object(Json.object(lock, "wasm"), "shader");
            Path root = directory == null ? cache.resolve("shader-toolchain") : directory;
            verifyShaderFile("prebuilt shader toolchain manifest", root.resolve(Json.string(shader, "manifest")),
                    Json.string(shader, "manifestSha256"));
            for (Map.Entry<String, Object> item : Json.object(shader, "files").entrySet()) {
                verifyShaderFile("Wasm shader toolchain " + item.getKey(), root.resolve(item.getKey()),
                        String.valueOf(item.getValue()));
            }
        }

        private static void verifyShaderFile(String name, Path path, String expected) {
            if (!Files.isRegularFile(path)) {
                throw new PatcherException("missing " + name + " at " + path + "; expected SHA-256 " + expected
                        + ". Restore the missing file or pass --shader-toolchain <dir> with all verified files.");
            }
            verifyFile(name, path, expected);
        }

        static void verifyFile(String name, Path path, String expected) {
            if (!Files.isRegularFile(path)) {
                throw new PatcherException("missing " + name + ": " + path + " (expected SHA-256 " + expected + ")");
            }
            String actual = Hashes.sha256(path);
            if (!expected.equals(actual)) {
                throw new PatcherException("SHA-256 mismatch for " + name + " at " + path
                        + ": expected " + expected + ", got " + actual);
            }
        }

        static PatcherException missingTool(String name, String expected, Path target) {
            return new PatcherException("missing tool " + name + "; expected SHA-256 " + expected
                    + ". Place it at " + target + " or pass --toolchain-dir <dir>.");
        }

        static void extractArchive(Path archive, Path destination, boolean zip) {
            if (Files.exists(destination)) {
                throw new PatcherException("refusing to overwrite tool directory: " + destination);
            }
            createDirectories(destination);
            try {
                if (zip) {
                    Path normalizedDestination = destination.toAbsolutePath().normalize();
                    try (ZipFile bundle = new ZipFile(archive.toFile())) {
                        var entries = bundle.entries();
                        while (entries.hasMoreElements()) {
                            ZipEntry entry = entries.nextElement();
                            Path target = normalizedDestination.resolve(entry.getName()).normalize();
                            if (!target.startsWith(normalizedDestination)) {
                                throw new PatcherException("unsafe path in tool archive: " + entry.getName());
                            }
                            if (entry.isDirectory()) {
                                createDirectories(target);
                            } else {
                                createDirectories(target.getParent());
                                try (InputStream input = bundle.getInputStream(entry)) {
                                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                                }
                            }
                        }
                    }
                    return;
                }
                runExtraction(List.of("tar", "-xzf", archive.toString(), "-C", destination.toString()), archive);
            } catch (IOException | PatcherException error) {
                Build.deleteTree(destination);
                throw new PatcherException("could not extract tool archive " + archive + ": " + error.getMessage());
            }
        }

        static void runExtraction(List<String> command, Path archive) {
            try {
                Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
                String output;
                try (InputStream stream = process.getInputStream()) {
                    output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                }
                int status = process.waitFor();
                if (status != 0) {
                    throw new PatcherException("could not extract tool archive " + archive + ": " + output.trim());
                }
            } catch (IOException error) {
                throw new PatcherException("could not extract " + archive + " with " + command.get(0)
                        + ": " + error.getMessage());
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new PatcherException("interrupted while extracting " + archive);
            }
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

    // -------------------------------------------------------- source patches

    /**
     * A few Gaius sources change official classes. They are published only as
     * edit scripts (patcher/patches/<version>/<id>.gpatch) that hold the lines
     * Gaius wrote and the line ranges they replace, never official code.
     * `prepare` decompiles each class from the user's own fetched game files
     * with a decompiler pinned by SHA-256, checks the decompiled text against
     * the SHA-256 the script records, applies the script and checks the result
     * the same way, so every user gets the file Gaius builds with.
     */
    static final class Prepare {
        static int run(Args args) {
            Setup setup = Setup.of(args, "prepare");
            List<SourcePatches.Entry> entries = setup.patches.forVersion(setup.profile.id);
            if (entries.isEmpty()) {
                System.out.println("No source patches for " + setup.profile.id + ".");
                return 0;
            }
            Path into = args.options.containsKey("into")
                    ? Paths.get(args.options.get("into")).toAbsolutePath().normalize()
                    : Locations.sourceRoot(setup.profiles);
            Map<SourcePatches.Entry, String> bases = setup.decompile(entries);
            int written = 0;
            int unchanged = 0;
            for (SourcePatches.Entry entry : entries) {
                Path scriptFile = setup.patches.scriptFile(setup.profile.id, entry);
                if (!Files.isRegularFile(scriptFile)) {
                    throw new PatcherException("missing source patch " + scriptFile + "; reinstall the patcher");
                }
                EditScript script = EditScript.parse(readString(scriptFile), scriptFile.toString());
                byte[] result = script.apply(bases.get(entry), entry.id() + " for " + setup.profile.id)
                        .getBytes(StandardCharsets.UTF_8);
                Path target = into.resolve(Safe.relative(entry.target()));
                if (Files.isRegularFile(target)) {
                    if (Hashes.sha256(target).equals(Hashes.sha256(result))) {
                        unchanged++;
                        continue;
                    }
                    throw new PatcherException(target + " exists and differs from the patched " + entry.id()
                            + ". Delete it (or restore it from the repository) and run prepare again.");
                }
                createDirectories(target.getParent());
                try {
                    Files.write(target, result);
                } catch (IOException error) {
                    throw new UncheckedIOException(error);
                }
                written++;
            }
            System.out.println("Prepared " + entries.size() + " patched sources for " + setup.profile.id + " ("
                    + written + " written, " + unchanged + " already up to date) in " + into);
            return 0;
        }
    }

    /** Maintainer helper: writes the decompiled bases of one version for make_source_patches.py. */
    static final class Decompile {
        static int run(Args args) {
            Setup setup = Setup.of(args, "decompile");
            String out = args.options.get("out");
            if (out == null) {
                throw new PatcherException("usage: decompile <version> --out <dir>");
            }
            Path dir = Paths.get(out).toAbsolutePath().normalize();
            Map<SourcePatches.Entry, String> bases = setup.decompile(setup.patches.forVersion(setup.profile.id));
            for (Map.Entry<SourcePatches.Entry, String> base : bases.entrySet()) {
                writeString(dir.resolve(base.getKey().id() + ".java"), base.getValue());
            }
            System.out.println("Decompiled " + bases.size() + " classes of " + setup.profile.id + " into " + dir);
            return 0;
        }
    }

    // ------------------------------------------------------------------- build

    /**
     * Builds in a disposable copy of the public source tree.  The user's
     * fetched game files are copied into that tree, source patches are applied
     * there, and the existing release scripts do the bytecode, TeaVM, site and
     * portable-HTML work.  Keeping the script root disposable is important:
     * release scripts write generated resources under port/work and port/target.
     */
    static final class Build {
        static final String JSON_SCHEMA = "gaius-patcher-progress-v1";
        static final String SHADER_TOOLCHAIN_MANIFEST = "gaius-shader-toolchain.json";
        static final String ARTIFACT_CONTRACT_SMOKE =
                "apps/bridge/browser-full-path-artifact-contract-smoke.mjs";

        static int run(Args args) {
            if (args.positional.isEmpty()) {
                throw new PatcherException("usage: build <version>");
            }
            String version = args.positional.get(0);
            BuildSettings settings = BuildSettings.fromArgs(args);
            Path profiles = Locations.profilesDir(args);
            VersionProfile profile = VersionProfile.load(profiles, version);
            Path output = args.options.containsKey("out")
                    ? Paths.get(args.options.get("out")).toAbsolutePath().normalize()
                    : Locations.workRoot(args, profiles).resolve("build").resolve(version).toAbsolutePath().normalize();
            output = canonicalPath(output);
            Path sourceRoot = canonicalPath(Locations.sourceRoot(profiles));
            if (output.equals(sourceRoot) || output.startsWith(sourceRoot) || sourceRoot.startsWith(output)) {
                throw new PatcherException("--out must be outside the source tree: " + output);
            }
            if (!Files.isRegularFile(sourceRoot.resolve(ARTIFACT_CONTRACT_SMOKE))) {
                throw new PatcherException("missing build input: " + sourceRoot.resolve(ARTIFACT_CONTRACT_SMOKE));
            }
            Path workRoot = Locations.workRoot(args, profiles);
            ToolchainManager toolchain = new ToolchainManager(
                    sourceRoot.resolve("patcher/toolchain.lock"), args, workRoot);
            toolchain.preflight(version, args);
            runQuietly(() -> {
                Terms.require(args, profiles);
                return 0;
            });
            ToolchainManager.Prepared prepared = toolchain.prepare(profiles, version, args);
            // Build is intentionally offline. Fetch reads the launcher's local
            // metadata and files every time so a stale work directory cannot
            // silently become the build input.
            progress("fetch", "local-files", 8, "Validating local launcher files");
            Args fetch = copyArgs(args, "fetch", version);
            fetch.flags.add("offline");
            fetch.flags.add("reproducible");
            runQuietly(() -> Fetch.run(fetch, false));
            Path work = Locations.workDir(args, profiles, profile);

            deleteTree(output.resolve("tool"));
            createDirectories(output);
            Path toolRoot = output.resolve("tool");
            Path buildRoot = output.resolve("build");
            Path dist = output.resolve("dist");
            Path stageWork = toolRoot.resolve("port/work").resolve(version);

            progress("stage", "source-tree", 18, "Staging the isolated build tree");
            stageSourceTree(sourceRoot, toolRoot, work, workRoot.resolve("tools"), version, prepared);
            writeBuildClasspath(toolRoot, stageWork.resolve("libraries"), stageWork.resolve("classpath.txt"));
            if (!profile.needsMappings()) {
                copyFile(stageWork.resolve("client-original.jar"), stageWork.resolve("client-named.jar"));
            }
            if (profile.needsMappings() && !Files.isRegularFile(stageWork.resolve("client-named.jar"))) {
                progress("prepare", "remap", 27, "Remapping the local client");
                runTool(toolRoot, List.of("port/scripts/remap-client.sh"), output.resolve("remap.log"),
                        Map.of("GAIUS_VERSION_PROFILE_PATH", "versions/" + version + ".json",
                                "GAIUS_MAVEN_OFFLINE", "true"));
            }

            progress("prepare", "source-patches", 38, "Applying source patches and preserving user-decompiled code");
            Args stagedArgs = copyArgs(args, "prepare", version);
            stagedArgs.options.put("profiles", toolRoot.resolve("port/versions").toString());
            stagedArgs.options.put("work", stageWork.toString());
            stagedArgs.options.put("work-root", toolRoot.resolve("port/work").toString());
            stagedArgs.options.remove("decompiler");
            VersionProfile stagedProfile = VersionProfile.load(toolRoot.resolve("port/versions"), version);
            SourcePatches patches = SourcePatches.load(stagedArgs, toolRoot.resolve("port/versions"));
            Setup setup = new Setup(stagedArgs, toolRoot.resolve("port/versions"), stagedProfile, stageWork, patches);
            runQuietly(() -> {
                applySourcePatches(setup, toolRoot);
                return 0;
            });

            progress("compile", "overlays", 48, "Building isolated overlays for TeaVM");
            Map<String, String> overlayEnvironment = new LinkedHashMap<>();
            overlayEnvironment.put("GAIUS_VERSION_PROFILE_PATH", "versions/" + version + ".json");
            overlayEnvironment.put("GAIUS_BUILD_ROOT", buildRoot.toString());
            overlayEnvironment.put("GAIUS_OVERLAY_DIRECTORY", buildRoot.resolve("overlays").toString());
            overlayEnvironment.put("GAIUS_MAVEN_REPOSITORY",
                    toolRoot.resolve("port/work/tools/maven-repository").toString());
            overlayEnvironment.put("GAIUS_MAVEN_OFFLINE", "true");
            runTool(toolRoot, List.of("port/scripts/build-overlays.sh"), output.resolve("overlays.log"), overlayEnvironment);
            // The repository's fixture script has a historical default path
            // under port/work. Keep that compatibility input inside the
            // disposable tree even though the release output uses buildRoot.
            Path fixtureOverlay = toolRoot.resolve("port/work/overlays").resolve(version)
                    .resolve("teavm-core-0.15.0-gaius.jar");
            copyFile(buildRoot.resolve("overlays/teavm-core-0.15.0-gaius.jar"), fixtureOverlay);

            progress("compile", "teavm", 55, "Running the existing TeaVM release pipeline");
            Map<String, String> environment = new LinkedHashMap<>();
            environment.put("GAIUS_BUILD_ROOT", buildRoot.toString());
            environment.put("GAIUS_OVERLAY_DIRECTORY", buildRoot.resolve("overlays").toString());
            environment.put("GAIUS_DIST_DIRECTORY", dist.toString());
            environment.put("GAIUS_MAVEN_OFFLINE", "true");
            environment.put("GAIUS_ASSETS_OFFLINE", "true");
            environment.put("MAVEN_ARGS", "--offline");
            environment.put("CARGO_NET_OFFLINE", "true");
            environment.put("GAIUS_BUILD_OFFLINE", "true");
            environment.put("GAIUS_SKIP_OVERLAY_BUILD", "true");
            String stagedMavenRepository = toolRoot.resolve("port/work/tools/maven-repository").toString();
            environment.put("GAIUS_MAVEN_REPOSITORY", stagedMavenRepository);
            environment.put("MAVEN_REPOSITORY", stagedMavenRepository);
            environment.put("GAIUS_JSBODY_FIXTURE_CORE",
                    buildRoot.resolve("overlays/teavm-core-0.15.0-gaius.jar").toString());
            Path stagedShaderToolchain = toolRoot.resolve("port/work/tools/shader-toolchain");
            Path shaderToolchain = Files.isDirectory(stagedShaderToolchain) ? stagedShaderToolchain : null;
            if (shaderToolchain != null) {
                if (!Files.isRegularFile(shaderToolchain.resolve(SHADER_TOOLCHAIN_MANIFEST))) {
                    throw new PatcherException("shader toolchain manifest is missing: " + shaderToolchain);
                }
                environment.put("GAIUS_SHADER_TOOLCHAIN_PREBUILT", shaderToolchain.toString());
            }
            if (prepared.jdkHome() != null && prepared.stagedJdk()) {
                environment.put("GAIUS_JAVA_HOME", toolRoot.resolve("port/work/tools/jdk-runtime").toString());
            }
            prepared.hostWasm().configure(toolRoot.resolve("port/work/tools/host-wasm"), environment);
            environment.put("GAIUS_WASM_HOST_TOOLS_REPORT", output.resolve("wasm-host-tools.json").toString());
            runTool(toolRoot, List.of("patcher/build-release-locked.sh", version), output.resolve("build.log"), environment);

            Path pages = buildRoot.resolve("pages-site");
            Path portable = dist.resolve("Gaius.html");
            if (!Files.isDirectory(pages) || !Files.isRegularFile(portable)) {
                throw new PatcherException("build completed without site and portable HTML (site=" + pages
                        + ", html=" + portable + ")");
            }
            Path site = output.resolve("site");
            deleteTree(site);
            movePath(pages, site);
            Path html = output.resolve("Gaius-" + version + ".html");
            copyFile(portable, html);
            if (settings.present()) {
                publishBuildSettings(settings, site, html);
            }
            writeHashes(output, version, site, html);
            progress("publish", "artifacts", 100, "Published the site, single-file HTML, and hash manifest");
            return 0;
        }

        record BuildSettings(String quality, Integer renderDistance, String backend, List<Path> resourcePacks) {
            static BuildSettings fromArgs(Args args) {
                String quality = args.options.get("quality");
                if (quality != null) {
                    quality = quality.toLowerCase(Locale.ROOT);
                    if (quality.equals("medium")) quality = "mid";
                    if (!Set.of("low", "mid", "high", "ultra").contains(quality)) {
                        throw new PatcherException("invalid --quality value '" + quality
                                + "' (expected low, mid, high or ultra)");
                    }
                }
                String backend = args.options.get("backend");
                if (backend != null) {
                    backend = backend.toLowerCase(Locale.ROOT);
                    if (!Set.of("auto", "webgpu", "webgl2").contains(backend)) {
                        throw new PatcherException("invalid --backend value '" + backend
                                + "' (expected auto, webgpu or webgl2)");
                    }
                }
                Integer renderDistance = null;
                if (args.options.containsKey("render-distance")) {
                    try {
                        renderDistance = Integer.parseInt(args.options.get("render-distance"));
                    } catch (NumberFormatException error) {
                        throw new PatcherException("--render-distance is not a number: "
                                + args.options.get("render-distance"));
                    }
                    if (renderDistance < 2 || renderDistance > 32) {
                        throw new PatcherException("--render-distance must be between 2 and 32");
                    }
                }

                List<Path> resourcePacks = new ArrayList<>();
                Set<String> names = new LinkedHashSet<>();
                for (String value : args.repeatedOptions.getOrDefault("resource-pack", List.of())) {
                    Path pack = Paths.get(value).toAbsolutePath().normalize();
                    if (!Files.isRegularFile(pack)) {
                        throw new PatcherException("--resource-pack must name a local file: " + pack);
                    }
                    try {
                        pack = pack.toRealPath();
                        long size = Files.size(pack);
                        if (size == 0 || size > (256L << 20)) {
                            throw new PatcherException("--resource-pack file size must be between 1 byte and 256 MiB: "
                                    + pack);
                        }
                    } catch (IOException error) {
                        throw new PatcherException("could not read --resource-pack file " + pack + ": "
                                + error.getMessage());
                    }
                    String name = pack.getFileName().toString();
                    if (name.equals(".") || name.equals("..") || name.length() > 120
                            || name.chars().anyMatch(Character::isISOControl)) {
                        throw new PatcherException("--resource-pack has an unsupported file name: " + name);
                    }
                    if (!names.add(name.toLowerCase(Locale.ROOT))) {
                        throw new PatcherException("--resource-pack file names must be unique: " + name);
                    }
                    resourcePacks.add(pack);
                }
                return new BuildSettings(quality, renderDistance, backend, List.copyOf(resourcePacks));
            }

            boolean present() {
                return quality != null || renderDistance != null || backend != null || !resourcePacks.isEmpty();
            }

            Map<String, Object> toJson() {
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("schema", "gaius-build-defaults-v1");
                if (quality != null) values.put("quality", quality);
                if (renderDistance != null) values.put("renderDistanceChunks", renderDistance);
                if (backend != null) values.put("backend", backend);
                values.put("resourcePacks", resourcePacks.stream().map(path -> path.getFileName().toString()).toList());
                return values;
            }
        }

        static void publishBuildSettings(BuildSettings settings, Path site, Path html) {
            Path packDirectory = site.resolve("resourcepacks");
            for (Path source : settings.resourcePacks()) {
                Path target = packDirectory.resolve(source.getFileName());
                try {
                    createDirectories(packDirectory);
                    Files.copy(source, target);
                } catch (IOException error) {
                    throw new PatcherException("could not copy local resource pack " + source + " to " + target
                            + ": " + error.getMessage());
                }
            }
            String json = Json.write(settings.toJson());
            writeString(site.resolve("gaius-build-defaults.json"), json);
            if (settings.quality() != null || settings.renderDistance() != null || settings.backend() != null) {
                injectBuildDefaults(site.resolve("index.html"), json);
                injectBuildDefaults(html, json);
            }
        }

        static void injectBuildDefaults(Path html, String json) {
            String source = readString(html);
            String marker = "<head>";
            int at = source.indexOf(marker);
            if (at < 0 || source.indexOf(marker, at + marker.length()) >= 0) {
                throw new PatcherException("could not find one <head> insertion point in " + html);
            }
            String encodedJson = java.util.Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
            String script = """
                    <script data-gaius-build-defaults="v1">
                    (() => {
                      const encoded = atob('%s');
                      const bytes = Uint8Array.from(encoded, value => value.charCodeAt(0));
                      const defaults = JSON.parse(new TextDecoder().decode(bytes));
                      window.__gaiusBuildDefaults = defaults;
                      try {
                        const url = new URL(location.href);
                        if (!url.searchParams.has('gpu') && (defaults.backend === 'webgpu' || defaults.backend === 'webgl2')) {
                          url.searchParams.set('gpu', defaults.backend);
                        }
                        if (!url.searchParams.has('gaiusTier') && defaults.quality) {
                          const key = 'gaius.quality.tierOverride';
                          try {
                            const stored = localStorage.getItem(key);
                            if (!stored || !['low', 'mid', 'high', 'ultra'].includes(stored.toLowerCase())) {
                              localStorage.setItem(key, defaults.quality);
                            }
                          } catch (error) {
                            url.searchParams.set('gaiusTier', defaults.quality);
                          }
                        }
                        if (url.href !== location.href) {
                          location.replace(url.href);
                          return;
                        }
                      } catch (error) {
                        console.warn('Could not apply Gaius build URL defaults', error);
                      }
                      if (Number.isInteger(defaults.renderDistanceChunks)) {
                        const previous = window.__gaiusBeforeMain;
                        window.__gaiusBeforeMain = async () => {
                          if (typeof previous === 'function') await previous();
                          const path = '/gaius/options.txt';
                          const files = window.__gaiusPersistentFiles || {};
                          if (Object.prototype.hasOwnProperty.call(files, path)
                              || Object.prototype.hasOwnProperty.call(files, path.slice(1))) return;
                          if (typeof window.__gaiusFsPut !== 'function') throw new Error('Gaius browser storage is not ready');
                          const text = 'renderDistance:' + defaults.renderDistanceChunks + '\\n';
                          if (!window.__gaiusFsPut(path, btoa(text))) throw new Error('Could not save first-run render distance');
                          if (typeof window.__gaiusFsFlush === 'function') await window.__gaiusFsFlush();
                        };
                      }
                    })();
                    </script>
                    """.formatted(encodedJson);
            int insert = at + marker.length();
            writeString(html, source.substring(0, insert) + script + source.substring(insert));
        }

        static Path resolveShaderToolchain(Args args, Path profiles, Path stagedToolchain) {
            String configured = args.options.get("shader-toolchain");
            if (configured == null || configured.isBlank()) {
                configured = System.getenv("GAIUS_SHADER_TOOLCHAIN_PREBUILT");
            }
            if (configured != null && !configured.isBlank()) {
                return Paths.get(configured).toAbsolutePath().normalize();
            }
            String toolchainDir = args.options.get("toolchain-dir");
            if (toolchainDir != null && !toolchainDir.isBlank()) {
                Path candidate = Paths.get(toolchainDir).toAbsolutePath().normalize().resolve("shader-toolchain");
                if (Files.isDirectory(candidate)) return candidate;
            }
            if (stagedToolchain != null && Files.isRegularFile(stagedToolchain.resolve(SHADER_TOOLCHAIN_MANIFEST))) {
                return stagedToolchain;
            }
            Path workToolchain = Locations.workRoot(args, profiles).resolve("tools/shader-toolchain");
            return Files.isRegularFile(workToolchain.resolve(SHADER_TOOLCHAIN_MANIFEST)) ? workToolchain : null;
        }

        static Args copyArgs(Args source, String command, String version) {
            Args copy = new Args();
            copy.command = command;
            copy.positional.add(version);
            copy.options.putAll(source.options);
            source.repeatedOptions.forEach((name, values) -> copy.repeatedOptions.put(name, new ArrayList<>(values)));
            copy.flags.addAll(source.flags);
            copy.flags.remove("json-progress");
            return copy;
        }

        static void applySourcePatches(Setup setup, Path stageRoot) {
            List<SourcePatches.Entry> entries = setup.patches.forVersion(setup.profile.id);
            if (entries.isEmpty()) {
                return;
            }
            Map<SourcePatches.Entry, String> bases = setup.decompile(entries);
            for (SourcePatches.Entry entry : entries) {
                Path scriptFile = setup.patches.scriptFile(setup.profile.id, entry);
                if (!Files.isRegularFile(scriptFile)) {
                    throw new PatcherException("missing source patch " + scriptFile);
                }
                EditScript script = EditScript.parse(readString(scriptFile), scriptFile.toString());
                byte[] result = script.apply(bases.get(entry), entry.id() + " for " + setup.profile.id)
                        .getBytes(StandardCharsets.UTF_8);
                Path target = stageRoot.resolve(Safe.relative(entry.target()));
                createDirectories(target.getParent());
                writeBytes(target, result);
            }
        }

        static void stageSourceTree(Path sourceRoot, Path stageRoot, Path fetchedWork, Path toolCache, String version,
                                    ToolchainManager.Prepared prepared) {
            deleteTree(stageRoot);
            createDirectories(stageRoot);
            copyTree(sourceRoot.resolve("port"), stageRoot.resolve("port"), path -> {
                Path relative = sourceRoot.resolve("port").relativize(path);
                String value = relative.toString().replace('\\', '/');
                return value.equals("work") || value.startsWith("work/")
                        || value.equals("target") || value.startsWith("target/")
                        || value.equals("web/dist") || value.startsWith("web/dist/");
            });
            copyTree(sourceRoot.resolve("patcher"), stageRoot.resolve("patcher"), path -> false);
            Path artifactContract = sourceRoot.resolve(ARTIFACT_CONTRACT_SMOKE);
            if (!Files.isRegularFile(artifactContract)) {
                throw new PatcherException("missing build input: " + artifactContract);
            }
            copyFile(artifactContract, stageRoot.resolve(ARTIFACT_CONTRACT_SMOKE));
            for (String file : List.of("VERSION", "relay-nodes.json", "NOTICE", "LICENSE")) {
                Path source = sourceRoot.resolve(file);
                if (Files.isRegularFile(source)) {
                    createDirectories(stageRoot.resolve(file).getParent());
                    copyFile(source, stageRoot.resolve(file));
                }
            }
            Path licenses = sourceRoot.resolve("LICENSES");
            if (Files.isDirectory(licenses)) {
                copyTree(licenses, stageRoot.resolve("LICENSES"), path -> false);
            }
            Path stageWorkRoot = stageRoot.resolve("port/work");
            createDirectories(stageWorkRoot);
            copyTree(fetchedWork, stageWorkRoot.resolve(version), path -> false);
            if (Files.isDirectory(toolCache)) {
                Path stagedTools = stageWorkRoot.resolve("tools");
                try (var stream = Files.list(toolCache)) {
                    for (Path tool : stream.sorted().toList()) {
                        String name = tool.getFileName().toString();
                        boolean legacyTools = prepared == null || prepared.mavenArtifacts().isEmpty();
                        if (name.startsWith("apache-maven-") || name.startsWith("vineflower")
                                || name.startsWith("jdk-") || (legacyTools && (name.startsWith("AutoRenamingTool-")
                                || name.equals("maven-repository") || name.equals("shader-toolchain")))) {
                            // Tool caches are often symlinked into a shared installation. The
                            // disposable build must contain the target bytes, not a link that
                            // points back outside the staged tree.
                            Path target = stagedTools.resolve(name);
                            if (Files.isDirectory(tool)) {
                                copyTree(tool.toRealPath(), target, path -> false);
                            } else {
                                copyFile(tool.toRealPath(), target);
                            }
                        }
                    }
                } catch (IOException error) {
                    throw new UncheckedIOException(error);
                }
            }
            if (prepared != null && prepared.shaderToolchain() != null
                    && Files.isDirectory(prepared.shaderToolchain())) {
                copyTree(prepared.shaderToolchain(), stageWorkRoot.resolve("tools/shader-toolchain"), path -> false);
            }
            if (prepared != null && prepared.mavenRepository() != null
                    && Files.isDirectory(prepared.mavenRepository())) {
                Path target = stageWorkRoot.resolve("tools/maven-repository");
                if (prepared.mavenArtifacts().isEmpty()) {
                    copyTree(prepared.mavenRepository(), target, path -> false);
                } else {
                    ToolchainManager.stageMavenRepository(prepared.mavenRepository(), target,
                            prepared.mavenArtifacts());
                    if (prepared.remapper() != null) {
                        Path remapper = target.resolve(prepared.mavenRepository().relativize(prepared.remapper()));
                        copyFile(remapper, stageWorkRoot.resolve("tools").resolve(remapper.getFileName()));
                    }
                }
            }
            if (prepared != null) {
                prepared.hostWasm().stage(stageWorkRoot.resolve("tools/host-wasm"));
                copyFile(prepared.mavenHome().getParent().resolve("apache-maven-"
                                + prepared.mavenVersion() + "-bin.tar.gz"),
                        stageWorkRoot.resolve("tools/apache-maven-" + prepared.mavenVersion() + "-bin.tar.gz"));
                copyFile(prepared.vineflower(), stageWorkRoot.resolve("tools")
                        .resolve(prepared.vineflower().getFileName().toString()));
                if (prepared.stagedJdk() && prepared.jdkHome() != null) {
                    copyTree(prepared.jdkHome(), stageWorkRoot.resolve("tools/jdk-runtime"), path -> false);
                }
            }

            if (prepared != null && Files.isRegularFile(stageWorkRoot.resolve("tools/apache-maven-"
                    + prepared.mavenVersion() + "/bin/mvn")) == false) {
                Path archive = stageWorkRoot.resolve("tools/apache-maven-" + prepared.mavenVersion() + "-bin.tar.gz");
                Path unpack = stageWorkRoot.resolve("tools/.maven-unpack");
                ToolchainManager.extractArchive(archive, unpack, false);
                Path extracted = unpack.resolve("apache-maven-" + prepared.mavenVersion());
                movePath(extracted, stageWorkRoot.resolve("tools/apache-maven-" + prepared.mavenVersion()));
                deleteTree(unpack);
            }
        }

        static void writeBuildClasspath(Path toolRoot, Path libraries, Path output) {
            List<String> jars;
            try (var stream = Files.walk(libraries)) {
                jars = stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".jar"))
                        .map(path -> toolRoot.relativize(path).toString().replace('\\', '/'))
                        .sorted()
                        .toList();
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
            writeString(output, String.join(":", jars) + "\n");
        }

        static void applyDeterministicEnvironment(Map<String, String> environment) {
            environment.put("LC_ALL", "C.UTF-8");
            environment.put("LANG", "C.UTF-8");
            environment.put("TZ", "UTC");
            environment.put("SOURCE_DATE_EPOCH", "0");
            environment.put("CARGO_BUILD_JOBS", "4");
            environment.put("RAYON_NUM_THREADS", "4");
            environment.put("CARGO_INCREMENTAL", "0");

            String fixedJvmOptions = "-XX:ActiveProcessorCount=4 -Djava.util.concurrent.ForkJoinPool.common.parallelism=1"
                    + " -Dfile.encoding=UTF-8 -Duser.language=en -Duser.country=US -Duser.timezone=UTC";
            appendEnvironmentOptions(environment, "JAVA_TOOL_OPTIONS", fixedJvmOptions);
            appendEnvironmentOptions(environment, "MAVEN_OPTS", fixedJvmOptions);
        }

        private static void appendEnvironmentOptions(Map<String, String> environment, String key, String options) {
            String existing = environment.getOrDefault(key, "").trim();
            environment.put(key, existing.isEmpty() ? options : existing + " " + options);
        }


        interface PathFilter { boolean skip(Path path); }

        static void copyTree(Path source, Path target, PathFilter filter) {
            if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                throw new PatcherException("missing build input: " + source);
            }
            try {
                Files.walkFileTree(source, new SimpleFileVisitor<>() {
                    @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (!dir.equals(source) && filter.skip(dir)) return FileVisitResult.SKIP_SUBTREE;
                        createDirectories(target.resolve(source.relativize(dir)));
                        return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (!filter.skip(file)) copyFile(file, target.resolve(source.relativize(file)));
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }

        static void deleteTree(Path dir) {
            if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return;
            try (var stream = Files.walk(dir)) {
                for (Path path : stream.sorted(Collections.reverseOrder()).toList()) Files.delete(path);
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }

        static void copyFile(Path source, Path target) {
            try {
                createDirectories(target.getParent());
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }

        static void movePath(Path source, Path target) {
            try {
                createDirectories(target.getParent());
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }

        static Path canonicalPath(Path path) {
            Path normalized = path.toAbsolutePath().normalize();
            List<Path> missing = new ArrayList<>();
            Path existing = normalized;
            while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                missing.add(existing.getFileName());
                existing = existing.getParent();
            }
            if (existing == null) {
                throw new PatcherException("cannot resolve output path: " + path);
            }
            try {
                Path resolved = existing.toRealPath();
                for (int i = missing.size() - 1; i >= 0; i--) {
                    resolved = resolved.resolve(missing.get(i));
                }
                return resolved.normalize();
            } catch (IOException error) {
                throw new PatcherException("cannot resolve output path " + path + ": " + error.getMessage());
            }
        }

        static void writeBytes(Path path, byte[] bytes) {
            try {
                createDirectories(path.getParent());
                Files.write(path, bytes);
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }

        static void runTool(Path toolRoot, List<String> relativeCommand, Path log, Map<String, String> extraEnv) {
            List<String> command = new ArrayList<>();
            String first = relativeCommand.get(0);
            if (first.endsWith(".sh")) {
                command.add("bash");
            }
            command.add(toolRoot.resolve(relativeCommand.get(0)).toString());
            for (int i = 1; i < relativeCommand.size(); i++) command.add(relativeCommand.get(i));
            try {
                createDirectories(log.getParent());
                ProcessBuilder builder = new ProcessBuilder(command).directory(toolRoot.toFile())
                        .redirectErrorStream(true).redirectOutput(log.toFile());
                builder.environment().put("GAIUS_MAVEN_OFFLINE", "true");
                builder.environment().put("GAIUS_ASSETS_OFFLINE", "true");
                builder.environment().put("GAIUS_BUILD_OFFLINE", "true");
                builder.environment().put("MAVEN_ARGS", "--offline");
                builder.environment().put("CARGO_NET_OFFLINE", "true");
                builder.environment().putAll(extraEnv);
                applyDeterministicEnvironment(builder.environment());
                Process process = builder.start();
                int status = process.waitFor();
                if (status != 0) {
                    throw new PatcherException("build command failed (exit " + status + "); see " + log);
                }
            } catch (IOException error) {
                throw new PatcherException("could not start build command: " + error);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new PatcherException("build interrupted");
            }
        }

        static void runQuietly(java.util.function.IntSupplier action) {
            if (!Args.currentJsonProgress) {
                action.getAsInt();
                return;
            }
            PrintStream original = System.out;
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            try (PrintStream replacement = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
                System.setOut(replacement);
                action.getAsInt();
            } finally {
                System.setOut(original);
            }
            String text = captured.toString(StandardCharsets.UTF_8);
            if (!text.isBlank()) System.err.print(text);
        }

        static void progress(String phase, String step, int pct, String message) {
            if (Args.currentJsonProgress) {
                System.out.println(Json.compact(progressEvent(phase, step, pct, message, null)));
            } else {
                System.out.println("[" + pct + "%] " + message);
            }
        }

        static void errorProgress(String message) {
            System.out.println(Json.compact(progressEvent("error", "failed", 100, message, message)));
        }

        static Map<String, Object> progressEvent(String phase, String step, int pct, String message, String error) {
            Map<String, Object> object = new LinkedHashMap<>();
            object.put("schema", JSON_SCHEMA);
            object.put("phase", phase);
            object.put("step", step);
            object.put("pct", pct);
            object.put("message", message);
            object.put("error", error);
            return object;
        }

        static void writeHashes(Path output, String version, Path site, Path html) {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("schema", ReproManifest.BUILD_SCHEMA);
            root.put("version", version);
            Map<String, Object> portable = new LinkedHashMap<>();
            portable.put("path", html.getFileName().toString());
            portable.put("sha256", Hashes.sha256(html));
            root.put("html", portable);
            List<Object> files = new ArrayList<>();
            try {
                Files.walk(site).filter(Files::isRegularFile).sorted().forEach(path -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("path", site.relativize(path).toString().replace('\\', '/'));
                    entry.put("sha256", Hashes.sha256(path));
                    files.add(entry);
                });
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
            root.put("site", files);
            writeString(output.resolve(ReproManifest.BUILD_HASHES), Json.write(root));
        }
    }

    // --------------------------------------------------- reproduction hashes

    static final class ReproManifest {
        static final String SCHEMA = "gaius-patcher-repro-v1";
        static final String BUILD_SCHEMA = "gaius-patcher-hashes-v1";
        static final String BUILD_HASHES = "expected-hashes.json";
        static final Set<String> FIELDS = Set.of("schema", "patcherVersion", "gameVersion", "os",
                "toolchainLockSha256", "files");
        static final Set<String> WORK_DIRS = Set.of("build", "dist", "tool", "work", "work-root");
        static final Set<String> BUILD_LOGS = Set.of("build.log", "overlays.log", "run.log");

        static int generate(Args args) throws IOException {
            Path output = outputDir(args);
            Path profiles = Locations.profilesDir(args);
            Path patcher = Locations.patcherDir(profiles);
            Map<String, Object> build = readBuildHashes(output);
            String game = gameVersion(profiles, build, "version");
            String portable = portablePath(game);
            String os = currentOs();
            String name = filename(os);
            String toolchainHash = lockHash(patcher);
            checkStagedLock(output, toolchainHash);
            Path target = Paths.get(args.options.getOrDefault("out", output.toString()))
                    .toAbsolutePath().normalize().resolve(name);

            Map<String, String> recorded = new TreeMap<>();
            Map<String, Object> html = Json.object(build, "html");
            if (!portable.equals(Json.string(html, "path"))) {
                throw new PatcherException("unexpected portable HTML in " + BUILD_HASHES + ": expected " + portable);
            }
            recorded.put(portable, hashValue(html.get("sha256"), portable));
            for (Object item : Json.array(build, "site")) {
                Map<String, Object> entry = Json.asObject(item, "site entry");
                String path = "site/" + Json.string(entry, "path");
                checkArtifactPath(path, portable);
                if (recorded.putIfAbsent(path, hashValue(entry.get("sha256"), path)) != null) {
                    throw new PatcherException("duplicate build artifact: " + path);
                }
            }
            checkInventory(recorded, portable);
            Map<String, Path> actual = inventory(output, name, target);
            if (!actual.keySet().equals(recorded.keySet())) {
                throw new PatcherException("build inventory differs from " + BUILD_HASHES
                        + "; generate a reference from an unmodified build");
            }
            Map<String, String> files = new TreeMap<>();
            for (var entry : actual.entrySet()) {
                String path = entry.getKey();
                Path file = entry.getValue();
                String raw = Hashes.sha256(file);
                if (!recorded.get(path).equals(raw)) {
                    throw new PatcherException("build artifact differs from " + BUILD_HASHES + ": " + path);
                }
                files.put(path, isText(path) ? Hashes.sha256Lf(file) : raw);
            }

            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("schema", SCHEMA);
            manifest.put("patcherVersion", TOOL_VERSION);
            manifest.put("gameVersion", game);
            manifest.put("os", os);
            manifest.put("toolchainLockSha256", toolchainHash);
            manifest.put("files", files);
            String json = Json.write(manifest);
            createDirectories(target.getParent());
            var writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            try (writer) {
                writer.write(json);
            } catch (IOException error) {
                try {
                    Files.deleteIfExists(target);
                } catch (IOException cleanup) {
                    error.addSuppressed(cleanup);
                }
                throw error;
            }
            Build.progress("manifest", "written", 100, "Wrote " + name);
            return 0;
        }

        static int verify(Args args) throws IOException {
            Path output = outputDir(args);
            Path profiles = Locations.profilesDir(args);
            Path patcher = Locations.patcherDir(profiles);
            String os = currentOs();
            String name = filename(os);
            String explicit = args.options.get("manifest");
            Path reference = explicit == null ? output.resolve(name) : Paths.get(explicit).toAbsolutePath().normalize();
            if (explicit == null && Files.notExists(reference, LinkOption.NOFOLLOW_LINKS)) {
                reference = patcher.resolve(name);
            }
            Map<String, Object> manifest = readObject(reference, "reproduction manifest");
            if (!SCHEMA.equals(manifest.get("schema"))) {
                throw new PatcherException("unsupported reproduction manifest schema; expected " + SCHEMA);
            }
            if (!manifest.keySet().equals(FIELDS)) {
                throw new PatcherException("invalid fields in reproduction manifest");
            }
            checkMetadata("patcher version", Json.string(manifest, "patcherVersion"), TOOL_VERSION);
            checkMetadata("OS", Json.string(manifest, "os"), os);
            String toolchainHash = hashValue(manifest.get("toolchainLockSha256"), "toolchain.lock");
            checkMetadata("toolchain.lock SHA-256", toolchainHash, lockHash(patcher));
            checkStagedLock(output, toolchainHash);
            String game = gameVersion(profiles, manifest, "gameVersion");
            if (!Files.notExists(output.resolve(BUILD_HASHES), LinkOption.NOFOLLOW_LINKS)) {
                checkMetadata("game version", game, gameVersion(profiles, readBuildHashes(output), "version"));
            }
            String portable = portablePath(game);
            Map<String, String> expected = new TreeMap<>();
            for (var item : Json.object(manifest, "files").entrySet()) {
                checkArtifactPath(item.getKey(), portable);
                expected.put(item.getKey(), hashValue(item.getValue(), item.getKey()));
            }
            checkInventory(expected, portable);
            Map<String, Path> actual = inventory(output, name, reference);
            boolean hasMod = actual.keySet().stream().anyMatch(path -> !expected.containsKey(path) && isModPath(path));
            Set<String> paths = new TreeSet<>(expected.keySet());
            paths.addAll(actual.keySet());
            List<Object> differences = new ArrayList<>();
            boolean mismatch = false;
            for (String path : paths) {
                String want = expected.get(path);
                Path file = actual.get(path);
                String got = file == null ? null : isText(path) ? Hashes.sha256Lf(file) : Hashes.sha256(file);
                if (Objects.equals(want, got)) continue;
                String kind = want == null ? "extra" : got == null ? "missing" : "changed";
                boolean modded = want == null ? isModPath(path)
                        : got != null && hasMod && (path.equals("site/index.html") || path.equals(portable));
                mismatch |= !modded;
                Map<String, Object> difference = new LinkedHashMap<>();
                difference.put("path", path);
                difference.put("kind", kind);
                difference.put("expectedSha256", want);
                difference.put("actualSha256", got);
                difference.put("modded", modded);
                differences.add(difference);
            }
            String status = mismatch ? "MISMATCH" : differences.isEmpty() ? "MATCH" : "MODDED";
            if (Args.currentJsonProgress) {
                Map<String, Object> event = Build.progressEvent("verify", "artifacts", 100, status, null);
                event.put("status", status);
                event.put("differences", differences);
                System.out.println(Json.compact(event));
            } else {
                System.out.println(status);
                for (Object difference : differences) {
                    System.out.println(Json.compact(difference));
                }
            }
            return mismatch ? 2 : differences.isEmpty() ? 0 : 3;
        }

        static Path outputDir(Args args) {
            if (args.positional.size() != 1) {
                throw new PatcherException("usage: " + args.command + " <build-output-dir>"
                        + (args.command.equals("verify") ? " [--manifest <file>]" : " [--out <dir>]"));
            }
            Path output = Paths.get(args.positional.get(0)).toAbsolutePath().normalize();
            if (!Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS)) {
                throw new PatcherException("build output is not a directory: " + output);
            }
            return output;
        }

        static Map<String, Object> readObject(Path file, String what) {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new PatcherException(what + " is missing or not a regular file: " + file);
            }
            return Json.asObject(Json.parseUnique(readString(file)), what);
        }

        static Map<String, Object> readBuildHashes(Path output) {
            Map<String, Object> hashes = readObject(output.resolve(BUILD_HASHES), BUILD_HASHES);
            if (!BUILD_SCHEMA.equals(hashes.get("schema"))) {
                throw new PatcherException("unsupported schema in " + BUILD_HASHES + "; expected " + BUILD_SCHEMA);
            }
            return hashes;
        }

        static String gameVersion(Path profiles, Map<String, Object> metadata, String key) {
            String game = Json.string(metadata, key);
            if (!VersionProfile.ids(profiles).contains(game)) {
                throw new PatcherException("unsupported game version: " + game);
            }
            return game;
        }

        static void checkMetadata(String label, String expected, String actual) {
            if (!expected.equals(actual)) {
                throw new PatcherException("manifest " + label + " is " + expected + "; expected " + actual);
            }
        }

        static String portablePath(String game) {
            return "Gaius-" + game + ".html";
        }

        static String currentOs() {
            String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (name.startsWith("linux")) return "linux";
            if (name.startsWith("windows")) return "windows";
            if (name.startsWith("mac") || name.equals("darwin")) return "macos";
            throw new PatcherException("no reproduction manifest OS identifier for " + name);
        }

        static String filename(String os) {
            return "expected-" + TOOL_VERSION + "-" + os + ".json";
        }

        static String lockHash(Path patcher) throws IOException {
            return Hashes.sha256Lf(patcher.resolve("toolchain.lock"));
        }

        static void checkStagedLock(Path output, String expected) throws IOException {
            Path tool = output.resolve("tool");
            Path patcher = tool.resolve("patcher");
            if (!Files.isDirectory(tool, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isDirectory(patcher, LinkOption.NOFOLLOW_LINKS)) return;
            Path lock = patcher.resolve("toolchain.lock");
            if (Files.notExists(lock, LinkOption.NOFOLLOW_LINKS)) return;
            if (!Files.isRegularFile(lock, LinkOption.NOFOLLOW_LINKS) || !expected.equals(lockHash(patcher))) {
                throw new PatcherException("build toolchain.lock differs from the current patcher toolchain.lock");
            }
        }

        static String hashValue(Object value, String path) {
            if (!(value instanceof String hash) || !hash.matches("[0-9a-f]{64}")) {
                throw new PatcherException("invalid SHA-256 for " + path);
            }
            return hash;
        }

        static void checkArtifactPath(String path, String portable) {
            if (path.equals(portable)) return;
            if (!path.startsWith("site/") || !path.matches("(?:[a-zA-Z0-9_][a-zA-Z0-9_.-]*/)*[a-zA-Z0-9_][a-zA-Z0-9_.-]*")
                    || isModPath(path) || path.toLowerCase(Locale.ROOT).startsWith("site/resourcepacks/")) {
                throw new PatcherException("invalid reference artifact path: " + path);
            }
        }

        static void checkInventory(Map<String, String> files, String portable) {
            if (!files.containsKey(portable) || !files.containsKey("site/index.html")) {
                throw new PatcherException("reference inventory must include " + portable + " and site/index.html");
            }
            for (String path : files.keySet()) {
                for (int slash = path.lastIndexOf('/'); slash >= 0; slash = path.lastIndexOf('/', slash - 1)) {
                    if (files.containsKey(path.substring(0, slash))) {
                        throw new PatcherException("reference path is also a directory: " + path.substring(0, slash));
                    }
                }
            }
        }

        static Map<String, Path> inventory(Path output, String name, Path reference) throws IOException {
            Map<String, Path> files = new TreeMap<>();
            Files.walkFileTree(output, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return output.equals(dir.getParent()) && WORK_DIRS.contains(dir.getFileName().toString())
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    Path relative = output.relativize(file);
                    String path = relative.toString().replace(output.getFileSystem().getSeparator(), "/");
                    if (relative.getNameCount() == 1 && (BUILD_LOGS.contains(path) || path.equals(BUILD_HASHES)
                            || path.equals(name) || file.equals(reference))) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (!attrs.isRegularFile()) {
                        throw new PatcherException("build output entry is not a regular file: " + path);
                    }
                    files.put(path, file);
                    return FileVisitResult.CONTINUE;
                }
            });
            return files;
        }

        static boolean isModPath(String path) {
            String relative = path.toLowerCase(Locale.ROOT);
            if (relative.startsWith("site/")) relative = relative.substring("site/".length());
            return relative.startsWith("mods/")
                    || relative.matches("(?:loader|loaders|mod-loader|modloader)(?:/.*|[.-][^/]+)");
        }

        static boolean isText(String path) {
            String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
            return name.matches("(?:license|notice|_headers|.*\\.(?:html|js|mjs|css|json|txt|svg|map|xml|webmanifest))");
        }
    }

    /** What prepare and decompile share: the profile, the fetched files and the decompiler. */
    record Setup(Args args, Path profiles, VersionProfile profile, Path work, SourcePatches patches) {
        static Setup of(Args args, String command) {
            if (args.positional.isEmpty()) {
                throw new PatcherException("usage: " + command + " <version>");
            }
            Path profiles = Locations.profilesDir(args);
            VersionProfile profile = VersionProfile.load(profiles, args.positional.get(0));
            Terms.require(args, profiles);
            Path work = Locations.workDir(args, profiles, profile);
            if (!Files.isRegularFile(work.resolve("client-original.jar"))) {
                throw new PatcherException("no fetched files in " + work + "; run `fetch " + profile.id + "` first");
            }
            return new Setup(args, profiles, profile, work, SourcePatches.load(args, profiles));
        }

        Map<SourcePatches.Entry, String> decompile(List<SourcePatches.Entry> entries) {
            Path decompiler = patches.decompilerJar(args, Locations.workRoot(args, profiles));
            Path clientJar = clientJar();
            List<Path> libraries = libraryJars();
            Map<SourcePatches.Entry, String> bases = new LinkedHashMap<>();
            for (SourcePatches.Entry entry : entries) {
                Path jar = entry.jar().equals("client") ? clientJar : libraryJar(entry.jar());
                List<Path> context = new ArrayList<>();
                if (!jar.equals(clientJar)) {
                    context.add(clientJar);
                }
                for (Path library : libraries) {
                    if (!library.equals(jar)) {
                        context.add(library);
                    }
                }
                System.out.println("  decompiling " + entry.className().replace('/', '.'));
                bases.put(entry, patches.decompile(decompiler, jar, entry.className(), context,
                        work.resolve("decompiled").resolve(entry.id())));
            }
            return bases;
        }

        /** The named client: the official jar, or for an obfuscated release the remapped one. */
        Path clientJar() {
            if (!profile.needsMappings()) {
                Path jar = work.resolve("client-original.jar");
                requireEqual("SHA-1 of " + jar, profile.clientSha1, Hashes.sha1(jar));
                return jar;
            }
            Path named = work.resolve("client-named.jar");
            if (!Files.isRegularFile(named)) {
                throw new PatcherException(profile.id + " ships obfuscated; remap it first so " + named
                        + " exists (port/scripts/remap-client.sh)");
            }
            return named;
        }

        List<Path> libraryJars() {
            Path root = work.resolve("libraries");
            if (!Files.isDirectory(root)) {
                return List.of();
            }
            try (var stream = Files.walk(root)) {
                return stream.filter(path -> path.getFileName().toString().endsWith(".jar"))
                        .sorted()
                        .toList();
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }

        /** "library:com/mojang/logging" is the one jar under libraries/com/mojang/logging/. */
        Path libraryJar(String spec) {
            String prefix = spec.substring("library:".length());
            Path dir = work.resolve("libraries").resolve(Safe.relative(prefix));
            List<Path> jars = new ArrayList<>();
            for (Path jar : libraryJars()) {
                if (jar.startsWith(dir)) {
                    jars.add(jar);
                }
            }
            if (jars.size() != 1) {
                throw new PatcherException("expected one library jar under " + dir + ", found " + jars.size()
                        + "; run `fetch " + profile.id + "` again");
            }
            return jars.get(0);
        }
    }

    /** patcher/source-patches.json: the pinned decompiler and the patched classes. */
    static final class SourcePatches {
        record Entry(String id, String target, String className, String jar, List<String> versions) {}

        final Path config;
        final String decompilerUrl;
        final String decompilerSha256;
        final List<String> decompilerOptions = new ArrayList<>();
        final List<Entry> entries = new ArrayList<>();

        SourcePatches(Path config, Map<String, Object> json) {
            this.config = config;
            Map<String, Object> decompiler = Json.object(json, "decompiler");
            decompilerUrl = Json.string(decompiler, "url");
            decompilerSha256 = decompiler.get("sha256") instanceof String s ? s : null;
            for (Object option : Json.array(decompiler, "options")) {
                decompilerOptions.add(String.valueOf(option));
            }
            Set<String> ids = new LinkedHashSet<>();
            for (Object value : Json.array(json, "patches")) {
                Map<String, Object> patch = Json.asObject(value, "patch");
                List<String> versions = new ArrayList<>();
                for (Object version : Json.array(patch, "versions")) {
                    versions.add(String.valueOf(version));
                }
                Entry entry = new Entry(Json.string(patch, "id"), Json.string(patch, "target"),
                        Json.string(patch, "class"), Json.string(patch, "jar"), List.copyOf(versions));
                if (!entry.id().matches("[A-Za-z0-9._-]+") || !ids.add(entry.id())) {
                    throw new PatcherException(config + ": bad or repeated patch id " + entry.id());
                }
                if (!entry.className().matches("[A-Za-z0-9_/$]+")) {
                    throw new PatcherException(config + ": bad class name " + entry.className());
                }
                if (!entry.jar().equals("client") && !entry.jar().startsWith("library:")) {
                    throw new PatcherException(config + ": bad jar " + entry.jar());
                }
                Safe.relative(entry.target());
                entries.add(entry);
            }
        }

        static SourcePatches load(Args args, Path profiles) {
            Path config = args.options.containsKey("source-patches")
                    ? Paths.get(args.options.get("source-patches")).toAbsolutePath().normalize()
                    : Locations.patcherDir(profiles).resolve("source-patches.json");
            if (!Files.isRegularFile(config)) {
                throw new PatcherException("the source patch list is missing (" + config + "); reinstall the patcher");
            }
            return new SourcePatches(config, Json.asObject(Json.parse(readString(config)), config.toString()));
        }

        List<Entry> forVersion(String version) {
            List<Entry> matching = new ArrayList<>();
            for (Entry entry : entries) {
                if (entry.versions().contains(version)) {
                    matching.add(entry);
                }
            }
            return matching;
        }

        Path scriptFile(String version, Entry entry) {
            return config.resolveSibling("patches").resolve(version).resolve(entry.id() + ".gpatch");
        }

        /** --decompiler, else a verified copy in the work root, else Maven Central; always checked by SHA-256. */
        Path decompilerJar(Args args, Path workRoot) {
            if (decompilerSha256 == null || !decompilerSha256.matches("[0-9a-f]{64}")) {
                throw new PatcherException("the decompiler is not pinned yet in " + config
                        + " (decompiler.sha256); this patcher cannot prepare sources");
            }
            String explicit = args.options.get("decompiler");
            if (explicit != null) {
                Path jar = Paths.get(explicit).toAbsolutePath().normalize();
                requireEqual("SHA-256 of " + jar, decompilerSha256, Hashes.sha256(jar));
                return jar;
            }
            String name = decompilerUrl.substring(decompilerUrl.lastIndexOf('/') + 1);
            String toolchain = args.options.get("toolchain-dir");
            Path cached = toolchain == null
                    ? workRoot.resolve("tools").resolve(Safe.relative(name))
                    : Paths.get(toolchain).toAbsolutePath().normalize().resolve(Safe.relative(name));
            if (Files.isRegularFile(cached)) {
                String actual = Hashes.sha256(cached);
                if (decompilerSha256.equals(actual)) return cached;
                throw new PatcherException("SHA-256 mismatch for Vineflower at " + cached
                        + ": expected " + decompilerSha256 + ", got " + actual);
            }
            if (args.flags.contains("offline")) {
                throw new PatcherException("the decompiler is not in " + cached.getParent()
                        + " and --offline is set; expected SHA-256 " + decompilerSha256
                        + "; pass --decompiler <jar> or --toolchain-dir <dir>");
            }
            System.out.println("  downloading the decompiler from " + URI.create(decompilerUrl).getHost());
            createDirectories(cached.getParent());
            Path partial = cached.resolveSibling(name + ".part");
            try {
                HttpClient client = HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .proxy(ProxySelector.getDefault())
                        .connectTimeout(Duration.ofSeconds(20))
                        .build();
                HttpResponse<Path> response = client.send(HttpRequest.newBuilder(URI.create(decompilerUrl))
                        .timeout(Duration.ofMinutes(5)).GET().build(), HttpResponse.BodyHandlers.ofFile(partial));
                if (response.statusCode() != 200) {
                    throw new PatcherException("could not download the decompiler: HTTP " + response.statusCode());
                }
                String actual = Hashes.sha256(partial);
                if (!decompilerSha256.equals(actual)) {
                    Files.deleteIfExists(partial);
                    throw new PatcherException("SHA-256 mismatch for the decompiler from " + decompilerUrl
                            + ": expected " + decompilerSha256 + ", got " + actual);
                }
                Files.move(partial, cached, StandardCopyOption.REPLACE_EXISTING);
                return cached;
            } catch (IOException error) {
                throw new PatcherException("could not download the decompiler: " + error);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new PatcherException("interrupted");
            }
        }

        /**
         * Decompiles one class (with its nested classes) of {@code jar}. The
         * class files are copied into scratch/in, the other jars are passed as
         * libraries, and the decompiler writes scratch/out.
         */
        String decompile(Path decompiler, Path jar, String className, List<Path> context, Path scratch) {
            deleteTree(scratch);
            Path in = scratch.resolve("in");
            Path out = scratch.resolve("out");
            createDirectories(out);
            int copied = 0;
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                for (ZipEntry entry : Collections.list(zip.entries())) {
                    String name = entry.getName();
                    if (name.equals(className + ".class")
                            || (name.startsWith(className + "$") && name.endsWith(".class"))) {
                        Path file = in.resolve(Safe.relative(name));
                        createDirectories(file.getParent());
                        try (InputStream stream = zip.getInputStream(entry)) {
                            Files.copy(stream, file);
                        }
                        if (name.equals(className + ".class")) {
                            copied++;
                        }
                    }
                }
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
            if (copied != 1) {
                throw new PatcherException(className + " is not in " + jar);
            }
            List<String> command = new ArrayList<>();
            command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
            command.add("-jar");
            command.add(decompiler.toString());
            command.addAll(decompilerOptions);
            for (Path library : context) {
                command.add("-e=" + library);
            }
            command.add(in.toString());
            command.add(out.toString());
            Path log = scratch.resolve("decompiler.log");
            try {
                ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true)
                        .redirectOutput(log.toFile());
                Build.applyDeterministicEnvironment(builder.environment());
                Process process = builder.start();
                int status = process.waitFor();
                if (status != 0) {
                    throw new PatcherException("the decompiler failed on " + className + " (exit " + status
                            + "); see " + log);
                }
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new PatcherException("interrupted");
            }
            Path source = out.resolve(Safe.relative(className + ".java"));
            if (!Files.isRegularFile(source)) {
                throw new PatcherException("the decompiler wrote no " + className + ".java; see " + log);
            }
            return readString(source);
        }

        static void deleteTree(Path dir) {
            if (!Files.exists(dir)) {
                return;
            }
            try (var stream = Files.walk(dir)) {
                for (Path path : stream.sorted(Collections.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }
    }

    /**
     * A .gpatch file. It turns one decompiled class into one Gaius source
     * without carrying any line of the original:
     *
     * <pre>
     * gaius-source-patch 2
     * base &lt;SHA-256 of the decompiled text&gt;
     * result &lt;SHA-256 of the patched text&gt;
     * @@ &lt;first base line, from 0&gt; &lt;base lines replaced&gt;
     * +&lt;a line Gaius wrote&gt;
     * =&lt;base line&gt; &lt;count&gt;     copy lines of the user's own decompiled text (moved code)
     * ~&lt;base line&gt; &lt;indent&gt;    copy one such line with a new indent: 8 spaces, or t2 for two tabs
     * </pre>
     *
     * Copies name lines by number, so a patch never holds their text. Texts
     * are split on '\n' only, so the result is byte-exact. Hunks are in base
     * order and do not overlap. Version 1 patches hold only + lines.
     */
    static final class EditScript {
        static final String MAGIC = "gaius-source-patch 2";
        static final Set<String> READABLE = Set.of("gaius-source-patch 1", MAGIC);

        /** One output piece: literal text, or base lines [from, from + count) re-indented when indent is set. */
        record Piece(String text, int from, int count, String indent) {}

        record Hunk(int start, int count, List<Piece> pieces) {}

        final String baseSha256;
        final String resultSha256;
        final List<Hunk> hunks;

        EditScript(String baseSha256, String resultSha256, List<Hunk> hunks) {
            this.baseSha256 = baseSha256;
            this.resultSha256 = resultSha256;
            this.hunks = hunks;
        }

        static EditScript parse(String text, String name) {
            String[] lines = text.split("\n", -1);
            if (lines.length < 4 || !READABLE.contains(lines[0]) || !lines[lines.length - 1].isEmpty()) {
                throw new PatcherException(name + " is not a gaius source patch");
            }
            boolean copies = lines[0].equals(MAGIC);
            String base = header(lines[1], "base ", name);
            String result = header(lines[2], "result ", name);
            List<Hunk> hunks = new ArrayList<>();
            Hunk current = null;
            int end = 0;
            for (int i = 3; i < lines.length - 1; i++) {
                String line = lines[i];
                if (line.startsWith("+") && current != null) {
                    current.pieces().add(new Piece(line.substring(1), 0, 0, null));
                } else if (copies && current != null && line.matches("=\\d{1,7} \\d{1,7}")) {
                    String[] parts = line.substring(1).split(" ");
                    current.pieces().add(new Piece(null, Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), null));
                } else if (copies && current != null && line.matches("~\\d{1,7} t?\\d{1,3}")) {
                    String[] parts = line.substring(1).split(" ");
                    String indent = parts[1].startsWith("t")
                            ? "\t".repeat(Integer.parseInt(parts[1].substring(1)))
                            : " ".repeat(Integer.parseInt(parts[1]));
                    current.pieces().add(new Piece(null, Integer.parseInt(parts[0]), 1, indent));
                } else if (line.matches("@@ \\d{1,7} \\d{1,7}")) {
                    String[] parts = line.split(" ");
                    current = new Hunk(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), new ArrayList<>());
                    if (current.start() < end) {
                        throw new PatcherException(name + ": hunks overlap or are out of order at line " + (i + 1));
                    }
                    end = current.start() + current.count();
                    hunks.add(current);
                } else {
                    throw new PatcherException(name + ": unexpected line " + (i + 1)
                            + " (a source patch holds only @@ headers, + lines and line copies)");
                }
            }
            return new EditScript(base, result, hunks);
        }

        private static String header(String line, String prefix, String name) {
            if (!line.startsWith(prefix) || !line.substring(prefix.length()).matches("[0-9a-f]{64}")) {
                throw new PatcherException(name + ": expected '" + prefix + "<sha256>'");
            }
            return line.substring(prefix.length());
        }

        String apply(String base, String what) {
            String actual = Hashes.sha256(base.getBytes(StandardCharsets.UTF_8));
            if (!actual.equals(baseSha256)) {
                throw new PatcherException("the decompiled " + what + " is not the text the patch was made for"
                        + " (SHA-256 " + actual + ", expected " + baseSha256 + "). Use the pinned decompiler on"
                        + " unmodified official files.");
            }
            String[] lines = base.split("\n", -1);
            List<String> out = new ArrayList<>();
            int next = 0;
            for (Hunk hunk : hunks) {
                if (hunk.start() + hunk.count() > lines.length) {
                    throw new PatcherException("the patch for " + what + " reaches past the end of the base");
                }
                out.addAll(Arrays.asList(lines).subList(next, hunk.start()));
                for (Piece piece : hunk.pieces()) {
                    if (piece.text() != null) {
                        out.add(piece.text());
                        continue;
                    }
                    if (piece.from() + piece.count() > lines.length) {
                        throw new PatcherException("the patch for " + what + " copies past the end of the base");
                    }
                    for (int i = piece.from(); i < piece.from() + piece.count(); i++) {
                        out.add(piece.indent() == null ? lines[i] : piece.indent() + lines[i].replaceFirst("^[ \t]+", ""));
                    }
                }
                next = hunk.start() + hunk.count();
            }
            out.addAll(Arrays.asList(lines).subList(next, lines.length));
            String result = String.join("\n", out);
            String resultActual = Hashes.sha256(result.getBytes(StandardCharsets.UTF_8));
            if (!resultActual.equals(resultSha256)) {
                throw new PatcherException("patching " + what + " gave SHA-256 " + resultActual + ", expected "
                        + resultSha256 + "; the patch is damaged");
            }
            return result;
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
            return digest("SHA-1", file);
        }

        static String sha256(Path file) {
            return digest("SHA-256", file);
        }

        /** Hash text bytes with CRLF mapped to LF, including across read boundaries. */
        static String sha256Lf(Path file) throws IOException {
            try (InputStream in = Files.newInputStream(file)) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[1 << 16];
                boolean cr = false;
                for (int read; (read = in.read(buffer)) > 0; ) {
                    if (cr && buffer[0] != '\n') digest.update((byte) '\r');
                    cr = buffer[read - 1] == '\r';
                    int end = cr ? read - 1 : read;
                    int count = 0;
                    for (int i = 0; i < end; i++) {
                        if (buffer[i] != '\r' || buffer[i + 1] != '\n') buffer[count++] = buffer[i];
                    }
                    digest.update(buffer, 0, count);
                }
                if (cr) digest.update((byte) '\r');
                return HexFormat.of().formatHex(digest.digest());
            } catch (NoSuchAlgorithmException error) {
                throw new IllegalStateException(error);
            }
        }

        static String digest(String algorithm, Path file) {
            try (InputStream in = Files.newInputStream(file)) {
                MessageDigest digest = MessageDigest.getInstance(algorithm);
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
        private final boolean rejectDuplicateKeys;
        private int at;

        private Json(String text, boolean rejectDuplicateKeys) {
            this.text = text;
            this.rejectDuplicateKeys = rejectDuplicateKeys;
        }

        static Object parse(String text) {
            return parse(text, false);
        }

        static Object parseUnique(String text) {
            return parse(text, true);
        }

        private static Object parse(String text, boolean rejectDuplicateKeys) {
            Json json = new Json(text, rejectDuplicateKeys);
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
                if (rejectDuplicateKeys && map.containsKey(key)) {
                    throw error("duplicate key " + key);
                }
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

        static String string(Map<String, Object> parent, String first, String second) {
            return string(object(parent, first), second);
        }

        static int integer(Map<String, Object> parent, String key) {
            if (parent.get(key) instanceof Number value) {
                return value.intValue();
            }
            throw new PatcherException("'" + key + "' is not a JSON number");
        }

        static double number(Map<String, Object> parent, String key) {
            if (parent.get(key) instanceof Number value) {
                return value.doubleValue();
            }
            throw new PatcherException("'" + key + "' is not a JSON number");
        }

        static String write(Object value) {
            StringBuilder out = new StringBuilder();
            write(value, out, 0, true);
            return out.append('\n').toString();
        }

        static String compact(Object value) {
            StringBuilder out = new StringBuilder();
            write(value, out, 0, false);
            return out.toString();
        }

        private static void write(Object value, StringBuilder out, int indent, boolean pretty) {
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
                if (pretty) out.append("{\n"); else out.append('{');
                int i = 0;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (pretty) out.append("  ".repeat(indent + 1));
                    write(String.valueOf(entry.getKey()), out, indent + 1, pretty);
                    out.append(pretty ? ": " : ":");
                    write(entry.getValue(), out, indent + 1, pretty);
                    if (++i < map.size()) {
                        if (pretty) out.append(",\n"); else out.append(',');
                    }
                }
                if (pretty) out.append('\n').append("  ".repeat(indent));
                out.append('}');
            } else if (value instanceof List<?> list) {
                if (list.isEmpty()) {
                    out.append("[]");
                    return;
                }
                if (pretty) out.append("[\n"); else out.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (pretty) out.append("  ".repeat(indent + 1));
                    write(list.get(i), out, indent + 1, pretty);
                    if (i + 1 < list.size()) {
                        if (pretty) out.append(",\n"); else out.append(',');
                    }
                }
                if (pretty) out.append('\n').append("  ".repeat(indent));
                out.append(']');
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
            check("patcher version is the Gaius release", TOOL_VERSION.equals(GAIUS_RELEASE)
                    && (GAIUS_RELEASE.equals(GAIUS_VERSION) || GAIUS_RELEASE.startsWith(GAIUS_VERSION + "-")));
            check("sha256 of abc", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
                    .equals(Hashes.sha256("abc".getBytes(StandardCharsets.US_ASCII))));

            String base = "a\nb\nc\n";
            String patched = "z\na\nx\ny\nc\n";
            String text = "gaius-source-patch 1\nbase " + Hashes.sha256(base.getBytes(StandardCharsets.UTF_8))
                    + "\nresult " + Hashes.sha256(patched.getBytes(StandardCharsets.UTF_8))
                    + "\n@@ 0 0\n+z\n@@ 1 1\n+x\n+y\n";
            EditScript script = EditScript.parse(text, "test");
            check("source patch applies", patched.equals(script.apply(base, "test")));
            check("source patch checks its base", throwsPatcher(() -> script.apply("a\nb\n", "test")));
            check("source patch refuses removed lines",
                    throwsPatcher(() -> EditScript.parse(text + "-b\n", "test")));
            check("source patch refuses context lines",
                    throwsPatcher(() -> EditScript.parse(text + " c\n", "test")));
            check("source patch refuses overlapping hunks",
                    throwsPatcher(() -> EditScript.parse(text + "@@ 1 0\n", "test")));

            String moved = "    b\n  a\nnew\nc\n";
            String copyText = EditScript.MAGIC + "\nbase " + Hashes.sha256(base.getBytes(StandardCharsets.UTF_8))
                    + "\nresult " + Hashes.sha256(moved.getBytes(StandardCharsets.UTF_8))
                    + "\n@@ 0 2\n~1 4\n~0 2\n+new\n";
            check("source patch copies and re-indents base lines",
                    moved.equals(EditScript.parse(copyText, "test").apply(base, "test")));
            check("source patch copies stay inside the base", throwsPatcher(() -> EditScript.parse(
                    copyText.replace("~1 4", "=9 1"), "test").apply(base, "test")));
            check("version 1 patches have no copies",
                    throwsPatcher(() -> EditScript.parse(text + "=0 1\n", "test")));

            check("bash path drive", "/d/gaius/a.jar".equals(Classpath.bashPath("D:\\gaius\\a.jar")));
            check("bash path posix", "/home/u/a.jar".equals(Classpath.bashPath("/home/u/a.jar")));

            Map<String, String> deterministicEnvironment = new LinkedHashMap<>();
            deterministicEnvironment.put("MAVEN_OPTS", "-Xmx6g");
            deterministicEnvironment.put("JAVA_TOOL_OPTIONS", "-Xmx5g");
            Build.applyDeterministicEnvironment(deterministicEnvironment);
            check("build environment pins locale, timezone, and source epoch",
                    "C.UTF-8".equals(deterministicEnvironment.get("LC_ALL"))
                            && "UTC".equals(deterministicEnvironment.get("TZ"))
                            && "0".equals(deterministicEnvironment.get("SOURCE_DATE_EPOCH")));
            check("build environment fixes compiler concurrency",
                    "4".equals(deterministicEnvironment.get("CARGO_BUILD_JOBS"))
                            && "4".equals(deterministicEnvironment.get("RAYON_NUM_THREADS"))
                            && deterministicEnvironment.get("JAVA_TOOL_OPTIONS").contains("-XX:ActiveProcessorCount=4")
                            && deterministicEnvironment.get("JAVA_TOOL_OPTIONS").contains(
                                    "-Djava.util.concurrent.ForkJoinPool.common.parallelism=1")
                            && deterministicEnvironment.get("MAVEN_OPTS").endsWith("-Duser.timezone=UTC")
                            && deterministicEnvironment.get("JAVA_TOOL_OPTIONS").endsWith("-Duser.timezone=UTC"));
            Args reproducibleFetch = Args.parse(new String[] {"fetch", "26.3", "--offline"});
            reproducibleFetch.flags.add("reproducible");
            check("build fetch manifest uses a fixed timestamp",
                    Instant.EPOCH.toString().equals(Fetch.fetchedAt(reproducibleFetch)));

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
            Args repeated = Args.parse(new String[] {"build", "26.3", "--resource-pack", "one.zip",
                    "--resource-pack=two.zip"});
            check("args repeated resource packs", List.of("one.zip", "two.zip")
                    .equals(repeated.repeatedOptions.get("resource-pack")));

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

            Path stageFixture = null;
            try {
                stageFixture = Files.createTempDirectory("gaius-patcher-selftest-");
                Path source = stageFixture.resolve("source");
                Path staged = stageFixture.resolve("staged");
                Path fetched = stageFixture.resolve("fetched");
                Files.createDirectories(source.resolve("port"));
                Files.createDirectories(source.resolve("patcher"));
                Files.createDirectories(source.resolve("apps/bridge"));
                Files.createDirectories(source.resolve("LICENSES"));
                Files.createDirectories(fetched);
                Path firstTree = stageFixture.resolve("first-tree");
                Path secondTree = stageFixture.resolve("second-tree");
                Path firstLibraries = firstTree.resolve("port/work/test/libraries");
                Path secondLibraries = secondTree.resolve("port/work/test/libraries");
                Files.createDirectories(firstLibraries.resolve("org/example/z"));
                Files.createDirectories(firstLibraries.resolve("org/example/a"));
                Files.createDirectories(secondLibraries.resolve("org/example/z"));
                Files.createDirectories(secondLibraries.resolve("org/example/a"));
                Files.writeString(firstLibraries.resolve("org/example/z/z.jar"), "synthetic z\n",
                        StandardCharsets.UTF_8);
                Files.writeString(firstLibraries.resolve("org/example/a/a.jar"), "synthetic a\n",
                        StandardCharsets.UTF_8);
                Files.writeString(secondLibraries.resolve("org/example/z/z.jar"), "synthetic z\n",
                        StandardCharsets.UTF_8);
                Files.writeString(secondLibraries.resolve("org/example/a/a.jar"), "synthetic a\n",
                        StandardCharsets.UTF_8);
                Path firstClasspath = firstTree.resolve("port/work/test/classpath.txt");
                Path secondClasspath = secondTree.resolve("port/work/test/classpath.txt");
                Build.writeBuildClasspath(firstTree, firstLibraries, firstClasspath);
                Build.writeBuildClasspath(secondTree, secondLibraries, secondClasspath);
                check("staged classpath is sorted, relative, and independent of its root",
                        Files.readString(firstClasspath).equals(Files.readString(secondClasspath))
                                && Files.readString(firstClasspath).equals(
                                        "port/work/test/libraries/org/example/a/a.jar:"
                                                + "port/work/test/libraries/org/example/z/z.jar\n"));
                Path syntheticSite = stageFixture.resolve("site");
                Files.createDirectories(syntheticSite);
                Files.writeString(syntheticSite.resolve("index.html"), "synthetic site\n",
                        StandardCharsets.UTF_8);
                Path syntheticHtml = stageFixture.resolve("Gaius-test.html");
                Files.writeString(syntheticHtml, "synthetic portable\n", StandardCharsets.UTF_8);
                Build.writeHashes(stageFixture, "test", syntheticSite, syntheticHtml);
                String hashManifest = Files.readString(stageFixture.resolve("expected-hashes.json"));
                int htmlEntry = hashManifest.indexOf("\"html\"");
                check("build hash manifest has stable portable field order and no absolute paths",
                        hashManifest.indexOf("\"path\"", htmlEntry)
                                < hashManifest.indexOf("\"sha256\"", htmlEntry)
                                && !hashManifest.contains(stageFixture.toString()));
                Files.writeString(source.resolve(Build.ARTIFACT_CONTRACT_SMOKE), "synthetic contract input\n",
                        StandardCharsets.UTF_8);
                Files.writeString(source.resolve("NOTICE"), "synthetic notice\n", StandardCharsets.UTF_8);
                Files.writeString(source.resolve("LICENSE"), "synthetic license\n", StandardCharsets.UTF_8);
                Files.writeString(source.resolve("LICENSES/MIT.txt"), "synthetic third-party license\n",
                        StandardCharsets.UTF_8);
                Build.BuildSettings noSettings = Build.BuildSettings.fromArgs(
                        Args.parse(new String[] {"build", "26.3"}));
                check("unset build settings preserve the normal output path", !noSettings.present());

                Path localPack = stageFixture.resolve("local-pack.zip");
                Path secondPack = stageFixture.resolve("second-pack.zip");
                Files.write(localPack, new byte[] {1, 2, 3});
                Files.write(secondPack, new byte[] {4, 5, 6});
                Args configuredArgs = Args.parse(new String[] {"build", "26.3", "--quality", "medium",
                        "--render-distance", "32", "--backend", "webgpu", "--resource-pack",
                        localPack.toString(), "--resource-pack", secondPack.toString()});
                Build.BuildSettings settings = Build.BuildSettings.fromArgs(configuredArgs);
                check("medium quality uses the client mid tier", "mid".equals(settings.quality()));
                check("render distance accepts the client maximum", Integer.valueOf(32).equals(settings.renderDistance()));
                check("backend accepts the WebGPU preference", "webgpu".equals(settings.backend()));
                check("repeatable local packs are retained", settings.resourcePacks().size() == 2);
                check("invalid quality is rejected", throwsPatcher(() -> Build.BuildSettings.fromArgs(
                        Args.parse(new String[] {"build", "26.3", "--quality", "balanced"}))));
                check("render distance below the client range is rejected", throwsPatcher(
                        () -> Build.BuildSettings.fromArgs(Args.parse(new String[] {"build", "26.3",
                                "--render-distance", "1"}))));
                check("invalid backend is rejected", throwsPatcher(() -> Build.BuildSettings.fromArgs(
                        Args.parse(new String[] {"build", "26.3", "--backend", "canvas"}))));

                Path settingsSite = stageFixture.resolve("settings-site");
                Path settingsHtml = stageFixture.resolve("settings.html");
                Files.createDirectories(settingsSite);
                writeString(settingsSite.resolve("index.html"), "<!doctype html><html><head>"
                        + "<script data-gaius-boot=\"v1\"></script></head><body></body></html>");
                writeString(settingsHtml, "<!doctype html><html><head>"
                        + "<script data-gaius-boot=\"v1\"></script></head><body></body></html>");
                Build.publishBuildSettings(settings, settingsSite, settingsHtml);
                Map<String, Object> defaults = Json.asObject(
                        Json.parse(readString(settingsSite.resolve("gaius-build-defaults.json"))), "build defaults");
                check("settings sidecar records tier, distance and backend", "mid".equals(defaults.get("quality"))
                        && Long.valueOf(32).equals(defaults.get("renderDistanceChunks"))
                        && "webgpu".equals(defaults.get("backend")));
                check("settings site copies local packs", Files.isRegularFile(settingsSite.resolve("resourcepacks/local-pack.zip"))
                        && Files.isRegularFile(settingsSite.resolve("resourcepacks/second-pack.zip")));
                String injected = readString(settingsSite.resolve("index.html"));
                check("settings are injected before the browser boot hook", injected.indexOf("data-gaius-build-defaults")
                        < injected.indexOf("data-gaius-boot") && injected.contains("__gaiusBeforeMain")
                        && injected.contains("gaiusTier") && injected.contains("gaius.quality.tierOverride")
                        && injected.contains("localStorage.setItem(key, defaults.quality)") && injected.contains("gpu")
                        && injected.contains("defaults.renderDistanceChunks + '\\n';"));
                check("portable HTML receives the same first-run settings", readString(settingsHtml)
                        .contains("renderDistanceChunks"));
                Build.stageSourceTree(source, staged, fetched, stageFixture.resolve("missing-tools"), "test", null);
                check("build stage copies license inputs",
                        Files.readString(staged.resolve("NOTICE")).equals("synthetic notice\n")
                        && Files.readString(staged.resolve("LICENSE")).equals("synthetic license\n")
                        && Files.readString(staged.resolve("LICENSES/MIT.txt"))
                                .equals("synthetic third-party license\n"));
                check("build stage copies artifact contract input",
                        Files.readString(staged.resolve(Build.ARTIFACT_CONTRACT_SMOKE))
                                .equals("synthetic contract input\n"));

                Path toolCache = stageFixture.resolve("cache");
                Path toolSource = toolCache.resolve("vineflower-1.11.2.jar");
                Path toolStaged = staged.resolve("port/work/tools/vineflower-1.11.2.jar");
                byte[] toolBytes = "synthetic tool bytes\n".getBytes(StandardCharsets.UTF_8);
                String toolHash = Hashes.sha256(toolBytes);
                Files.createDirectories(toolSource.getParent());
                Files.write(toolSource, toolBytes);
                Path lockFile = Locations.patcherDir(Locations.profilesDir(new Args())).resolve("toolchain.lock");
                Args offlineArgs = Args.parse(new String[] {"build", "26.3", "--offline"});
                ToolchainManager manager = new ToolchainManager(lockFile, offlineArgs, stageFixture.resolve("work"));
                ToolchainManager.Artifact synthetic = new ToolchainManager.Artifact("synthetic tool",
                        toolSource.getFileName().toString(), "https://example.invalid/synthetic-tool.jar", toolHash,
                        toolBytes.length);
                Path cachedTool = manager.cache.resolve(synthetic.file());
                Files.createDirectories(cachedTool.getParent());
                Files.write(cachedTool, toolBytes);
                check("toolchain accepts a matching SHA-256", manager.ensure(synthetic).equals(cachedTool));
                Files.writeString(cachedTool, "tampered tool bytes\n", StandardCharsets.UTF_8);
                boolean tamperRejected = false;
                try {
                    manager.ensure(synthetic);
                } catch (PatcherException error) {
                    tamperRejected = error.getMessage().contains("expected " + toolHash);
                }
                check("toolchain rejects a tampered file", tamperRejected);
                Map<String, Object> missingTeaVM = Map.of("path", "synthetic/teavm.jar", "sha256", "1".repeat(64));
                String hint;
                try {
                    manager.findMavenRepository(List.of(missingTeaVM));
                    hint = "";
                } catch (PatcherException error) {
                    hint = error.getMessage();
                }
                check("offline toolchain hint names tool, hash, path and toolchain directory",
                        hint.contains("TeaVM synthetic/teavm.jar") && hint.contains("1".repeat(64))
                                && hint.contains("synthetic/teavm.jar")
                                && hint.contains("--toolchain-dir"));
                Build.stageSourceTree(source, staged, fetched, toolCache, "test", null);
                ToolchainManager.verifyFile("staged synthetic tool", toolStaged, toolHash);
                check("toolchain stages a verified tool into the isolated build tree",
                        Files.readAllBytes(toolStaged).length == toolBytes.length);
                testMavenClosure(stageFixture.resolve("closure"));
                testShaderArchive(stageFixture.resolve("shader"));
                testHostWasmArchives(stageFixture.resolve("host-wasm"));
            } catch (IOException | RuntimeException error) {
                check("build stage copies license inputs", false);
            } finally {
                if (stageFixture != null) Build.deleteTree(stageFixture);
            }

            System.out.println(failures == 0 ? "selftest: all checks passed" : "selftest: " + failures + " failed");
            return failures == 0 ? 0 : 1;
        }

        static void testMavenClosure(Path root) throws IOException {
            List<Object> pins = new ArrayList<>();
            for (String path : List.of("test/project/1/project-1.pom", "test/project/1/project-1.jar",
                    "test/plugin/1/plugin-1.pom", "test/plugin/1/plugin-1.jar")) {
                Path file = root.resolve("mirror").resolve(path);
                createDirectories(file.getParent());
                Files.writeString(file, "synthetic " + path + "\n");
                pins.add(Map.of("path", path, "url", file.toUri().toString(),
                        "bytes", Files.size(file), "sha256", Hashes.sha256(file)));
            }
            Map<String, Object> closure = Map.of("profiles", List.of("fixture"), "artifacts", pins);
            Path lock = root.resolve("toolchain.lock");
            Map<String, Object> data = Map.of("schema", 1, "mavenRepository", closure,
                    "teavm", Map.of("artifacts", List.of(pins.get(1))));
            writeString(lock, Json.write(data));
            Path work = root.resolve("work");
            Args offline = new Args();
            offline.flags.add("offline");
            ToolchainManager online = new ToolchainManager(lock, new Args(), work);
            ToolchainManager local = new ToolchainManager(lock, offline, work);
            List<ToolchainManager.MavenArtifact> artifacts = online.lockedMavenArtifacts("fixture");
            Path first = work.resolve("maven-repository").resolve(artifacts.get(0).path());
            try {
                local.ensureMavenRepository(artifacts);
                check("missing Maven POM fails offline", false);
            } catch (PatcherException error) {
                check("offline hint names the locked coordinate, SHA-256 and installation path",
                        error.getMessage().contains("Maven artifact test/project/1/project-1.pom")
                                && error.getMessage().contains(first.toString())
                                && error.getMessage().contains(artifacts.get(0).artifact().sha256()));
            }
            Path repo = online.ensureMavenRepository(artifacts);
            check("lock-driven fetch installs the entire synthetic closure", regularFileCount(repo) == pins.size());
            for (ToolchainManager.MavenArtifact item : artifacts) {
                check("download matches lock " + item.path(),
                        Hashes.sha256(repo.resolve(item.path())).equals(item.artifact().sha256()));
            }
            Files.writeString(repo.resolve("unlocked.jar"), "unlocked synthetic file");
            check("completed closure works offline", local.ensureMavenRepository(artifacts).equals(repo));
            Path stage = root.resolve("isolated/port/work/tools/maven-repository");
            ToolchainManager.stageMavenRepository(repo, stage, artifacts);
            check("staged repository contains each locked file and excludes unlocked files",
                    regularFileCount(stage) == pins.size() && !Files.exists(stage.resolve("unlocked.jar"))
                            && artifacts.stream().allMatch(item -> Hashes.sha256(stage.resolve(item.path()))
                                    .equals(item.artifact().sha256())));
            Files.writeString(first, "tampered POM");
            check("existing tampered Maven entry is rejected even online",
                    throwsPatcher(() -> online.ensureMavenRepository(artifacts)));
            check("staging rechecks artifact bytes", throwsPatcher(() -> ToolchainManager.stageMavenRepository(
                    repo, root.resolve("tampered-stage"), artifacts)));
            Files.delete(first);
            Files.writeString(Paths.get(URI.create(artifacts.get(0).artifact().url())), "tampered mirror");
            check("tampered mirror entry never reaches the repository",
                    throwsPatcher(() -> online.ensureMavenRepository(artifacts)) && !Files.exists(first)
                            && regularFileCount(repo) == pins.size());
            pins.add(pins.get(0));
            writeString(lock, Json.write(data));
            check("duplicate lock paths are rejected", throwsPatcher(() -> new ToolchainManager(lock, offline, work)
                    .lockedMavenArtifacts("fixture")));
        }

        static long regularFileCount(Path directory) throws IOException {
            try (var paths = Files.walk(directory)) {
                return paths.filter(Files::isRegularFile).count();
            }
        }

        static void testShaderArchive(Path root) throws IOException {
            Files.createDirectories(root);
            Path archive = root.resolve("shader.zip");
            Map<String, String> contents = Map.of("manifest.json", "{\"schema\":1}",
                    "module.wasm", "synthetic module");
            try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(archive))) {
                for (var item : contents.entrySet()) {
                    zip.putNextEntry(new ZipEntry(item.getKey()));
                    zip.write(item.getValue().getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            String manifestHash = Hashes.sha256(contents.get("manifest.json").getBytes(StandardCharsets.UTF_8));
            String moduleHash = Hashes.sha256(contents.get("module.wasm").getBytes(StandardCharsets.UTF_8));
            Path lock = root.resolve("toolchain.lock");
            writeString(lock, Json.write(Map.of("schema", 1, "wasm", Map.of("shader", Map.of(
                    "manifest", "manifest.json", "manifestSha256", manifestHash,
                    "files", Map.of("module.wasm", moduleHash), "prebuiltArchive", Map.of(
                            "file", "shader.zip", "url", archive.toUri().toString(), "bytes", Files.size(archive),
                            "sha256", Hashes.sha256(archive)))))));
            Args args = new Args();
            ToolchainManager manager = new ToolchainManager(lock, args, root.resolve("work"));
            Path installed = manager.resolveShaderToolchain(args);
            check("shader archive is verified and unpacked into its offline layout",
                    Files.readString(installed.resolve("module.wasm")).equals(contents.get("module.wasm"))
                            && regularFileCount(installed) == contents.size());
            Files.delete(installed.resolve("module.wasm"));
            try {
                manager.verifyShaderToolchain(installed);
                check("incomplete shader directory fails verification", false);
            } catch (PatcherException error) {
                check("incomplete shader directory names the missing file and unpacked alternative",
                        error.getMessage().contains(installed.resolve("module.wasm").toString())
                                && error.getMessage().contains(moduleHash)
                                && error.getMessage().contains("--shader-toolchain <dir>"));
            }
            Files.writeString(archive, "tampered ZIP");
            ToolchainManager tampered = new ToolchainManager(lock, args, root.resolve("new-work"));
            check("tampered shader archive is rejected", throwsPatcher(() -> tampered.resolveShaderToolchain(args)));
        }

        static void testHostWasmArchives(Path root) throws IOException {
            if (!System.getProperty("os.name").equals("Linux")
                    || !List.of("amd64", "x86_64").contains(System.getProperty("os.arch"))) return;
            Path cache = root.resolve("gui-cache/files");
            createDirectories(cache);
            Map<String, Object> host = new LinkedHashMap<>();
            for (var tool : Map.of("binaryen", List.of("wasm-opt"), "llvm", List.of("clang", "wasm-ld")).entrySet()) {
                String name = tool.getKey();
                Path archive = cache.resolve(name + ".tar.gz");
                Path bin = root.resolve(name).resolve("bin");
                createDirectories(bin);
                List<String> selected = new ArrayList<>();
                for (String executable : tool.getValue()) {
                    Path file = bin.resolve(executable);
                    Files.writeString(file, "synthetic " + executable);
                    check("synthetic " + executable + " is executable", file.toFile().setExecutable(true));
                    selected.add("bin/" + executable);
                }
                Files.writeString(bin.resolve("unused"), "unused synthetic file");
                ToolchainManager.runExtraction(List.of("tar", "-czf", archive.toString(), "-C", root.toString(),
                        name), archive);
                Map<String, Object> pin = Map.of("file", archive.getFileName().toString(),
                        "bytes", Files.size(archive), "sha256", Hashes.sha256(archive), "root", name,
                        "members", selected);
                host.put(name, Map.of("version", "1", "distributions", Map.of("linux-x64", pin)));
            }
            host.put("linuxRuntime", List.of());
            Path lock = root.resolve("toolchain.lock");
            writeString(lock, Json.write(Map.of("schema", 1, "wasm", Map.of("hostTools", host))));
            Args args = Args.parse(new String[] {"build", "fixture", "--offline", "--toolchain-dir",
                    cache.getParent().toString()});
            var manager = new ToolchainManager(lock, args, root.resolve("work"));
            var wasm = manager.prepareHostWasm();
            Path staged = root.resolve("staged");
            wasm.stage(staged);
            Map<String, String> environment = new LinkedHashMap<>();
            wasm.configure(staged, environment);
            check("only verified Wasm host tools are staged with explicit paths",
                    regularFileCount(staged) == 3
                            && Files.readString(Path.of(environment.get("GAIUS_WASM_OPT"))).equals("synthetic wasm-opt")
                            && Path.of(environment.get("GAIUS_WASM_LD")).equals(staged.resolve("llvm/bin/wasm-ld")));
            Path cachedOptimizer = manager.cache.resolve("binaryen.tar.gz");
            String hash = Hashes.sha256(cachedOptimizer);
            Files.writeString(cachedOptimizer, "tampered archive");
            check("tampered Wasm host tool archive is rejected", throwsPatcher(manager::prepareHostWasm));
            Files.delete(cachedOptimizer);
            Files.delete(cache.resolve("binaryen.tar.gz"));
            try {
                manager.prepareHostWasm();
                check("missing Wasm host tool archive is rejected offline", false);
            } catch (PatcherException error) {
                String message = error.getMessage();
                check("offline Wasm host tool hint includes tool, hash and cache destination",
                        message.contains("Binaryen") && message.contains(hash) && message.contains("--toolchain-dir")
                                && message.contains(cachedOptimizer.toString()));
            }
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
        static boolean currentJsonProgress;
        String command = "";
        final List<String> positional = new ArrayList<>();
        final Map<String, String> options = new LinkedHashMap<>();
        final Map<String, List<String>> repeatedOptions = new LinkedHashMap<>();
        final Set<String> flags = new LinkedHashSet<>();
        static final Set<String> VALUE_OPTIONS =
                Set.of("minecraft-dir", "profiles", "work", "work-root", "mappings", "decompiler",
                        "source-patches", "into", "out", "shader-toolchain", "toolchain-dir", "quality",
                        "render-distance", "backend", "resource-pack", "manifest");

        static Args parse(String[] argv) {
            Args args = new Args();
            for (int i = 0; i < argv.length; i++) {
                String arg = argv[i];
                if (arg.startsWith("--") && arg.length() > 2) {
                    String name = arg.substring(2);
                    int equals = name.indexOf('=');
                    if (equals > 0) {
                        args.putOption(name.substring(0, equals), name.substring(equals + 1));
                    } else if (VALUE_OPTIONS.contains(name)) {
                        if (i + 1 >= argv.length) {
                            throw new PatcherException("--" + name + " needs a value");
                        }
                        args.putOption(name, argv[++i]);
                    } else {
                        args.flags.add(name);
                    }
                } else if (args.command.isEmpty()) {
                    args.command = arg;
                } else {
                    args.positional.add(arg);
                }
            }
            currentJsonProgress = args.flags.contains("json-progress");
            return args;
        }

        private void putOption(String name, String value) {
            options.put(name, value);
            if (name.equals("resource-pack")) {
                repeatedOptions.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
            }
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
