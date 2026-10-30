package dev.gaius.browser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Int32Array;
import org.teavm.jso.typedarrays.Int8Array;
import org.teavm.runtime.fs.VirtualFile;
import org.teavm.runtime.fs.VirtualFileAccessor;
import org.teavm.runtime.fs.VirtualFileSystem;
import org.teavm.runtime.fs.VirtualFileSystemProvider;

/** Browser persistence mirror for TeaVM's in-memory filesystem.
 *
 * <p>The Java side must stay synchronous because it sits behind {@code java.io}
 * and {@code java.nio}. The page preloads IndexedDB into
 * {@code globalThis.__gaiusPersistentFiles} before invoking Minecraft, then these
 * methods synchronously read/write that mirror and enqueue durable IndexedDB
 * writes from JavaScript.</p>
 */
public final class BrowserFilePersistence {
    private static final String OPTIONS_PATH = "/gaius/options.txt";
    private static final String SCREENSHOT_ROOT = "/gaius/screenshots/";
    private static final String USER_RESOURCE_PACK_ROOT = "/gaius/resourcepacks/";
    private static final String USER_RESOURCE_PACK_IMPORT_ROOT = "/gaius/resourcepack-imports/";
    // 4671 is retained solely to recognize the legacy options payload. It is
    // never used to select, read, or delete browser storage for a runtime.
    private static final int LEGACY_DATA_VERSION = 4671;
    /**
     * Options seeded into a new browser profile before the first start. The graphics part
     * depends on the GPU tier the page's capability layer measured
     * ({@code port/web/runtime/quality/gpu-caps.js}, {@link #runtimeGraphicsTier}): the Fast
     * preset on low-tier and mobile GPUs, Fancy from the mid tier up, a render distance that
     * grows with the tier and the vanilla 0.75 s chunk fade-in from the mid tier up. These values
     * are live: {@code GraphicsPresetStartupPatcher} stops {@code Minecraft.<init>} from
     * re-applying the named preset over them on every start. Existing profiles keep their saved
     * options; only files still equal to an earlier seed are rewritten, and a profile whose
     * seeded video options the player never changed follows a new tier
     * ({@link #reconcileAutomaticPreset}).
     */
    private static final String BROWSER_OPTION_COMMON_DEFAULTS = String.join("\n",
            "autoJump:false",
            "operatorItemsTab:true",
            // Vanilla's own frame settings (Options.framerateLimit 120, enableVsync true, the
            // same in 1.21.11, 26.2 and 26.3). The setting "Unlimited" is 260 and stays a player
            // choice. Existing browser profiles keep their saved value; this only affects new ones.
            "maxFps:120",
            "vignette:true",
            "prioritizeChunkUpdates:0",
            "enableVsync:true",
            "bobView:false",
            "panoramaSpeed:1.0",
            "screenEffectScale:0.0",
            "fovEffectScale:0.0",
            "darknessEffectScale:0.0",
            // pauseOnLostFocus keeps vanilla's default (true): a page that loses focus or is
            // hidden while in a world opens the pause menu like the desktop game.
            "darkMojangStudiosBackground:false",
            "hideSplashTexts:true",
            "showAutosaveIndicator:false",
            "skipMultiplayerWarning:true",
            "onboardAccessibility:false") + "\n";
    /** Low tier and mobile: the vanilla Fast preset, minus the menu blur, at 6/4 distance. */
    private static final String LOW_TIER_GRAPHICS_DEFAULTS = String.join("\n",
            "graphicsPreset:\"fast\"",
            "renderDistance:6",
            "simulationDistance:4",
            "entityDistanceScaling:0.5",
            "renderClouds:\"fast\"",
            "cloudRange:32",
            "ao:false",
            "cutoutLeaves:false",
            "improvedTransparency:false",
            "weatherRadius:3",
            "chunkSectionFadeInTime:0.0",
            "mipmapLevels:2",
            "maxAnisotropyBit:1",
            "textureFiltering:0",
            "biomeBlendRadius:0",
            "particles:1",
            "entityShadows:false",
            "menuBackgroundBlurriness:0") + "\n";
    /** Mid tier (integrated desktop GPUs): Fancy at the browser 8/6 distance contract. */
    private static final String MID_TIER_GRAPHICS_DEFAULTS = String.join("\n",
            "graphicsPreset:\"fancy\"",
            "renderDistance:8",
            "simulationDistance:6",
            "entityDistanceScaling:0.75",
            "renderClouds:\"true\"",
            "cloudRange:64",
            "ao:true",
            "cutoutLeaves:true",
            "improvedTransparency:false",
            "weatherRadius:5",
            "chunkSectionFadeInTime:0.75",
            "mipmapLevels:4",
            "maxAnisotropyBit:1",
            "textureFiltering:0",
            "biomeBlendRadius:1",
            "particles:0",
            "entityShadows:true",
            "menuBackgroundBlurriness:2") + "\n";
    /**
     * High tier (discrete GPUs): Fancy with full biome blend and weather; improved transparency
     * is requested but only runs where the context renders and blends float targets
     * ({@code BrowserQualityCaps}). 26.3 only: the other profiles seed it off and cap the
     * render distance at 8 ({@link #defaultBrowserOptions}).
     */
    private static final String HIGH_TIER_GRAPHICS_DEFAULTS = String.join("\n",
            "graphicsPreset:\"fancy\"",
            "renderDistance:10",
            "simulationDistance:6",
            "entityDistanceScaling:1.0",
            "renderClouds:\"true\"",
            "cloudRange:64",
            "ao:true",
            "cutoutLeaves:true",
            "improvedTransparency:true",
            "weatherRadius:10",
            "chunkSectionFadeInTime:0.75",
            "mipmapLevels:4",
            "maxAnisotropyBit:1",
            "textureFiltering:0",
            "biomeBlendRadius:2",
            "particles:0",
            "entityShadows:true",
            "menuBackgroundBlurriness:3") + "\n";
    /** Ultra tier (fast discrete GPUs): High plus a longer render and cloud distance. */
    private static final String ULTRA_TIER_GRAPHICS_DEFAULTS = String.join("\n",
            "graphicsPreset:\"fancy\"",
            "renderDistance:12",
            "simulationDistance:6",
            "entityDistanceScaling:1.0",
            "renderClouds:\"true\"",
            "cloudRange:96",
            "ao:true",
            "cutoutLeaves:true",
            "improvedTransparency:true",
            "weatherRadius:10",
            "chunkSectionFadeInTime:0.75",
            "mipmapLevels:4",
            "maxAnisotropyBit:1",
            "textureFiltering:0",
            "biomeBlendRadius:2",
            "particles:0",
            "entityShadows:true",
            "menuBackgroundBlurriness:5") + "\n";
    /**
     * The single seed of v0.2.2 to v0.3.x. Kept only to recognise options files the game never
     * rewrote (and, through {@link #LEGACY_BROWSER_OPTION_DEFAULTS}, the older one); most of its
     * graphics values never applied because the Fast preset was re-applied on every start.
     */
    private static final String PREVIOUS_BROWSER_OPTION_DEFAULTS = String.join("\n",
            "autoJump:false",
            "operatorItemsTab:true",
            "renderDistance:8",
            "simulationDistance:6",
            "entityDistanceScaling:0.5",
            "maxFps:260",
            "graphicsPreset:\"fast\"",
            "renderClouds:\"true\"",
            "cloudRange:32",
            "ao:true",
            "cutoutLeaves:true",
            "vignette:true",
            "improvedTransparency:true",
            "weatherRadius:3",
            "chunkSectionFadeInTime:0.0",
            "prioritizeChunkUpdates:0",
            "mipmapLevels:4",
            "maxAnisotropyBit:1",
            "textureFiltering:0",
            "biomeBlendRadius:0",
            "particles:2",
            "enableVsync:false",
            "entityShadows:false",
            "bobView:false",
            "menuBackgroundBlurriness:0",
            "panoramaSpeed:1.0",
            "screenEffectScale:0.0",
            "fovEffectScale:0.0",
            "darknessEffectScale:0.0",
            "pauseOnLostFocus:false",
            "darkMojangStudiosBackground:false",
            "hideSplashTexts:true",
            "showAutosaveIndicator:false",
            "skipMultiplayerWarning:true",
            "onboardAccessibility:false") + "\n";
    private static final String LEGACY_BROWSER_OPTION_DEFAULTS = PREVIOUS_BROWSER_OPTION_DEFAULTS
            .replace("graphicsPreset:\"fast\"\n", "graphicsPreset:\"fancy\"\n")
            .replace("weatherRadius:3\n", "weatherRadius:0\n")
            .replace("onboardAccessibility:false\n", "");
    private static final Map<String, Integer> OPEN_MATERIALIZED_CHUNK_FILES = new HashMap<>();
    private static final Set<String> INTERNAL_VIRTUAL_FILE_PATHS = new HashSet<>();
    private static final Set<String> LAZY_CHUNK_FILE_PLACEHOLDERS = new HashSet<>();
    private static boolean mounted;
    private static String mountedStorageSignature;
    private static String mountedStoragePrefix;
    private static int mountedWorldVersion;

    private BrowserFilePersistence() {
    }

    public static void mount() {
        String storageSignature = storageConfigurationSignature();
        if (mounted) {
            if (!storageSignature.equals(mountedStorageSignature)) {
                throw new IllegalStateException("Browser storage profile changed after mount");
            }
            return;
        }
        String prefix = storagePrefix();
        // Validate both pieces of profile configuration before marking the
        // filesystem mounted. seedDefaultOptions reports ordinary I/O errors,
        // so leaving this check inside that method would turn a missing runtime
        // profile into a partially mounted client with an unversioned options file.
        int worldVersion = currentDataVersion();
        mountedStoragePrefix = prefix;
        mountedWorldVersion = worldVersion;
        mounted = true;
        mountedStorageSignature = storageSignature;
        String[] paths = storedPaths(prefix);
        int restored = 0;
        for (String path : paths) {
            if (!shouldRestoreAtStartup(path)) {
                continue;
            }
            try {
                if (restore(path)) {
                    restored++;
                }
            } catch (Throwable exception) {
                report("storage-restore-crashed", normalize(path) + ": " + describe(exception));
            }
        }
        seedDefaultOptions();
        report("storage-mounted", restored + " files");
        report("storage-backend", backendName());
    }

    public static boolean persist(String path, byte[] bytes) {
        if (path == null || bytes == null) {
            return false;
        }
        // FileChannel/FileOutputStream already wrote non-durable paths to TeaVM's
        // in-memory filesystem. Skipping the browser mirror is success, not an I/O
        // failure; only paths selected by shouldPersist require a durable write.
        if (!shouldPersist(path)) {
            return true;
        }
        String normalized = normalize(path);
        if (isBinaryChunkStoragePath(normalized) && setBytes(normalized, bytes)) {
            return true;
        }
        if (isDownloadedPackFile(normalized) && setBytes(normalized, bytes)) {
            markDownloadedPackPersisted(normalized, bytes.length);
            return true;
        }
        if (isUserResourcePackFile(normalized) && setBytes(normalized, bytes)) {
            return true;
        }
        if (isScreenshotPath(normalized)) {
            // The page keeps screenshots in IndexedDB as bytes, within a count and byte
            // budget and without a memory copy. -1: a page without that store.
            int screenshot = storeScreenshot(normalized, bytes);
            if (screenshot >= 0) {
                if (screenshot == 0) {
                    report("storage-screenshot-not-stored", normalized + " bytes=" + bytes.length);
                }
                return screenshot > 0;
            }
        }
        String encoded = Base64.getEncoder().encodeToString(bytes);
        boolean stored = setItem(storagePrefix() + normalized, encoded);
        if (!stored) {
            report("storage-quota-or-error", path + " bytes=" + bytes.length);
        }
        return stored;
    }

    /** Installs one browser-selected resource-pack file into the TeaVM filesystem. */
    public static boolean installUserResourcePack(String relativePath, byte[] bytes) {
        if (relativePath == null || bytes == null || bytes.length == 0) {
            return false;
        }
        String relative = normalizeUserResourcePackPath(relativePath);
        if (relative == null || relative.length() > 240) {
            return false;
        }
        String path = USER_RESOURCE_PACK_IMPORT_ROOT + relative;
        try {
            if (!mounted) {
                mount();
            }
            writeVirtualFile(path, bytes);
            if (!persist(path, bytes)) {
                report("resource-pack-persist-failed", path);
                return false;
            }
            return true;
        } catch (Throwable exception) {
            report("resource-pack-install-failed", path + ": " + describe(exception));
            return false;
        }
    }

    /** Returns true only when the active Worker can durably append region range records. */
    public static boolean supportsRangePersistence(String path) {
        if (path == null) {
            return false;
        }
        String normalized = normalize(path);
        return isChunkStoragePath(normalized) && canPatchBytes(normalized);
    }

    /** Persists one atomic logical-size update and its bounded set of changed byte ranges. */
    public static boolean persistRanges(
            String path, int logicalSize, int[] offsets, int[] lengths, byte[] payload) {
        if (path == null || logicalSize < 0 || offsets == null || lengths == null
                || payload == null || offsets.length != lengths.length) {
            return false;
        }
        if (!shouldPersist(path)) {
            return true;
        }
        String normalized = normalize(path);
        if (!isChunkStoragePath(normalized) || !canPatchBytes(normalized)) {
            return false;
        }
        boolean stored = patchBytes(normalized, logicalSize, offsets, lengths, payload);
        if (!stored) {
            report("storage-quota-or-error", path + " patchBytes=" + payload.length);
        }
        return stored;
    }

    /** Makes durable chunk storage visible to metadata checks without reading its payload. */
    public static boolean restoreOnDemand(String path) {
        if (path == null) {
            return false;
        }
        String normalized = normalize(path);
        if (!isOnDemandChunkStoragePath(normalized)
                || !INTERNAL_VIRTUAL_FILE_PATHS.add(normalized)) {
            return false;
        }
        try {
            if (!mounted) {
                mount();
            }
            if (!hasStoredPath(normalized)) {
                return false;
            }
            VirtualFile existing = VirtualFileSystemProvider.getInstance().getFile(normalized);
            if (existing != null && existing.isFile()) {
                return true;
            }
            writeVirtualFile(normalized, new byte[0]);
            LAZY_CHUNK_FILE_PLACEHOLDERS.add(normalized);
            reportRegion("storage-region-placeholder", normalized);
            return true;
        } catch (Throwable exception) {
            reportRegion("storage-region-placeholder-failed", normalized + ": " + describe(exception));
            return false;
        } finally {
            INTERNAL_VIRTUAL_FILE_PATHS.remove(normalized);
        }
    }

    /** Restores bytes before an accessor is created; metadata placeholders are never opened. */
    public static void materializeForOpen(String path) throws IOException {
        if (path == null) {
            return;
        }
        String normalized = normalize(path);
        if (!isOnDemandChunkStoragePath(normalized)) {
            return;
        }
        if (!INTERNAL_VIRTUAL_FILE_PATHS.add(normalized)) {
            throw new IOException("Recursive browser chunk materialization for " + normalized);
        }
        try {
            if (!mounted) {
                mount();
            }
            if (!hasStoredPath(normalized)) {
                return;
            }
            VirtualFile existing = VirtualFileSystemProvider.getInstance().getFile(normalized);
            if (existing != null && existing.isFile()
                    && !LAZY_CHUNK_FILE_PLACEHOLDERS.contains(normalized)) {
                return;
            }
            if (storedByteLength(normalized) < 0 && HostLoop.jspi()) {
                HostLoop.awaitHost(resume -> requestStoredRegion(normalized, resume::run));
                if (!storedRegionRequestSucceeded(normalized)) {
                    throw new IOException("Could not read browser chunk file " + normalized
                            + ": " + storedRegionRequestDetail(normalized));
                }
            }
            if (!restore(normalized)) {
                throw new IOException("Could not restore browser chunk file " + normalized);
            }
            releaseStoredRegionBridge(normalized);
            LAZY_CHUNK_FILE_PLACEHOLDERS.remove(normalized);
            reportRegion("storage-region-hydrated", normalized);
        } catch (IOException exception) {
            throw exception;
        } catch (Throwable exception) {
            IOException failure = new IOException("Could not restore browser chunk file " + normalized);
            failure.initCause(exception);
            throw failure;
        } finally {
            INTERNAL_VIRTUAL_FILE_PATHS.remove(normalized);
        }
    }

    /** Pins a materialized .mca or .mcc file while a synchronous accessor can use it. */
    public static boolean retainMaterializedChunkFile(String path) {
        String normalized = normalize(path);
        if (!isOnDemandChunkStoragePath(normalized)) {
            return false;
        }
        OPEN_MATERIALIZED_CHUNK_FILES.put(
                normalized, OPEN_MATERIALIZED_CHUNK_FILES.getOrDefault(normalized, 0) + 1);
        return true;
    }

    /**
     * Releases the TeaVM copy after the last channel closes. Durable storage is
     * deliberately left untouched so the next getFile call can hydrate it again.
     */
    public static void releaseMaterializedChunkFile(String path, boolean durable) {
        String normalized = normalize(path);
        if (!isOnDemandChunkStoragePath(normalized)) {
            return;
        }
        int references = OPEN_MATERIALIZED_CHUNK_FILES.getOrDefault(normalized, 0);
        if (references > 1) {
            OPEN_MATERIALIZED_CHUNK_FILES.put(normalized, references - 1);
            return;
        }
        OPEN_MATERIALIZED_CHUNK_FILES.remove(normalized);
        if (references == 0 || !durable || !hasStoredPath(normalized)) {
            return;
        }
        reclaimMaterializedChunkFileIfUnreferenced(normalized);
    }

    private static void reclaimMaterializedChunkFileIfUnreferenced(String normalized) {
        if (!isOnDemandChunkStoragePath(normalized)
                || OPEN_MATERIALIZED_CHUNK_FILES.getOrDefault(normalized, 0) != 0
                || !hasStoredPath(normalized)
                || !INTERNAL_VIRTUAL_FILE_PATHS.add(normalized)) {
            return;
        }
        try {
            VirtualFile file = VirtualFileSystemProvider.getInstance().getFile(normalized);
            LAZY_CHUNK_FILE_PLACEHOLDERS.remove(normalized);
            if (file != null && file.isFile() && file.delete()) {
                reportRegion("storage-region-reclaimed", normalized);
            }
        } catch (Throwable exception) {
            reportRegion("storage-region-reclaim-failed", normalized + ": " + describe(exception));
        } finally {
            INTERNAL_VIRTUAL_FILE_PATHS.remove(normalized);
        }
    }

    public static boolean delete(String path) {
        if (path == null || !shouldPersist(path)) {
            return false;
        }
        String normalized = normalize(path);
        LAZY_CHUNK_FILE_PLACEHOLDERS.remove(normalized);
        removeItem(storagePrefix() + normalized);
        return true;
    }

    public static void syncDelete(String path) {
        if (delete(path)) {
            report("storage-delete", normalize(path));
        }
    }

    /** Removes every browser record and the OPFS region container for a world. */
    public static void deleteWorld(String worldId) throws IOException {
        if (worldId == null || worldId.isEmpty()) {
            throw new IOException("Cannot delete a world without an id");
        }
        // The classlib overlay cannot see the client-side BrowserI18n helper; the screen that
        // reports this IOException shows the message as-is (English, matching gaius.world.*).
        deleteWorldStorage(worldId,
                "World deletion is unavailable in this browser",
                "This world is open in another Gaius tab. Close it before deleting the world.");
    }

    /** Clears stale client-side VFS entries before vanilla performs its locked tree walk. */
    public static void prepareWorldDirectoryForVanillaDelete(String worldId) throws IOException {
        if (worldId == null || worldId.isEmpty() || worldId.contains("/") || worldId.contains("\\")
                || worldId.equals(".") || worldId.equals("..")) {
            throw new IOException("Cannot delete a world with an invalid id");
        }
        String worldPath = "/gaius/saves/" + worldId;
        VirtualFile worldDirectory = VirtualFileSystemProvider.getInstance().getFile(worldPath);
        if (worldDirectory == null || !worldDirectory.isDirectory()) {
            return;
        }
        deleteWorldDirectoryChildren(worldDirectory, worldPath, worldPath + "/session.lock");
    }

    private static void deleteWorldDirectoryChildren(
            VirtualFile directory, String directoryPath, String preservedLockPath) throws IOException {
        String[] children = directory.listFiles();
        if (children == null) {
            throw new IOException("Could not list world directory " + directoryPath);
        }
        for (String childName : children) {
            String childPath = directoryPath + "/" + childName;
            if (childPath.equals(preservedLockPath)) {
                continue;
            }
            VirtualFile child = VirtualFileSystemProvider.getInstance().getFile(childPath);
            if (child == null) {
                throw new IOException("World directory changed while deleting " + childPath);
            }
            if (child.isDirectory()) {
                deleteWorldDirectoryChildren(child, childPath, preservedLockPath);
            }
            if (!child.delete()) {
                throw new IOException("Could not remove world file " + childPath);
            }
        }
    }

    @org.teavm.jso.JSBody(params = {"worldId", "unavailable", "inUse"}, script = """
            var remove = globalThis.__gaiusDeleteWorld;
            if (typeof remove !== 'function') {
              throw new Error(unavailable);
            }
            var result = remove(String(worldId || ''));
            if (result === false) {
              throw new Error(inUse);
            }
            """)
    private static native void deleteWorldStorage(String worldId, String unavailable, String inUse);

    /**
     * Loads the startup files of a world the page stored after mount (a world imported by
     * port/web/launcher/world-archive.js): level.dat, its backup, the icon and the world-gen
     * settings, exactly the files {@link #mount} restores for every world, so vanilla's world
     * list finds it. Returns the number of files restored.
     */
    public static int restoreWorldMetadata(String worldId) {
        if (worldId == null || worldId.isEmpty() || worldId.contains("/") || worldId.contains("\\")
                || worldId.equals(".") || worldId.equals("..")) {
            return 0;
        }
        if (!mounted) {
            mount();
        }
        String worldPrefix = "/gaius/saves/" + worldId + "/";
        int restored = 0;
        for (String path : storedPaths(storagePrefix())) {
            String normalized = normalize(path);
            if (!normalized.startsWith(worldPrefix) || !shouldRestoreAtStartup(normalized)) {
                continue;
            }
            try {
                if (restore(normalized)) {
                    restored++;
                }
            } catch (Throwable exception) {
                report("storage-restore-crashed", normalized + ": " + describe(exception));
            }
        }
        report("storage-world-imported", worldId + " files=" + restored);
        return restored;
    }

    public static void syncMove(String source, String target) throws IOException {
        boolean stored = syncFile(target);
        if (stored) {
            delete(source);
            report("storage-move", normalize(source) + " -> " + normalize(target));
            reclaimMaterializedChunkFileIfUnreferenced(normalize(target));
        } else {
            report("storage-move-failed", normalize(source) + " -> " + normalize(target));
            throw new IOException("Could not persist moved browser file " + normalize(target));
        }
    }

    public static boolean syncFile(String path) {
        if (path == null || !shouldPersist(path)) {
            return false;
        }
        String normalized = normalize(path);
        try {
            VirtualFileSystem fileSystem = VirtualFileSystemProvider.getInstance();
            VirtualFile file = fileSystem.getFile(normalized);
            if (file == null || !file.isFile()) {
                delete(normalized);
                return false;
            }
            VirtualFileAccessor accessor = file.createAccessor(true, false, false);
            if (accessor == null) {
                report("storage-sync-failed", normalized + ": open failed");
                return false;
            }
            try {
                int size = accessor.size();
                byte[] bytes = new byte[size];
                accessor.seek(0);
                int offset = 0;
                while (offset < size) {
                    int read = accessor.read(bytes, offset, size - offset);
                    if (read <= 0) {
                        break;
                    }
                    offset += read;
                }
                if (offset < size) {
                    bytes = Arrays.copyOf(bytes, offset);
                }
                boolean stored = persist(normalized, bytes);
                if (stored) {
                    report("storage-sync-file", normalized + " bytes=" + bytes.length);
                }
                return stored;
            } finally {
                accessor.close();
            }
        } catch (IOException exception) {
            report("storage-sync-failed", normalized + ": " + exception.getMessage());
            return false;
        }
    }

    private static boolean restore(String path) {
        String normalized = normalize(path);
        byte[] bytes;
        int storedLength = storedByteLength(normalized);
        if (storedLength >= 0) {
            bytes = new byte[storedLength];
            if (copyStoredBytes(normalized, bytes) != storedLength) {
                report("storage-decode-failed", normalized);
                return false;
            }
        } else {
            String encoded = getItem(storagePrefix() + normalized);
            if (encoded == null || encoded.isEmpty()) {
                return false;
            }
            try {
                bytes = Base64.getDecoder().decode(encoded.getBytes(StandardCharsets.ISO_8859_1));
            } catch (IllegalArgumentException exception) {
                report("storage-decode-failed", normalized);
                return false;
            }
        }
        try {
            writeVirtualFile(normalized, bytes);
            if (normalized.endsWith("/level.dat")) {
                ensureBrowserSessionLock(parent(normalized));
            }
            return true;
        } catch (IOException exception) {
            report("storage-restore-failed", normalized + ": " + exception.getMessage());
            return false;
        }
    }

    private static void ensureBrowserSessionLock(String worldDirectory) throws IOException {
        String lockPath = normalize(worldDirectory) + "/session.lock";
        VirtualFile lock = VirtualFileSystemProvider.getInstance().getFile(lockPath);
        if (lock == null || !lock.isFile()) {
            // DirectoryLock.isLocked opens this file without CREATE. TeaVM's
            // missing-file exception is broader than the JDK type vanilla catches.
            writeVirtualFile(lockPath, new byte[0]);
        }
    }

    private static boolean shouldPersist(String path) {
        String normalized = normalize(path);
        return normalized.contains("/saves/")
                || normalized.startsWith(SCREENSHOT_ROOT)
                || normalized.startsWith(USER_RESOURCE_PACK_ROOT)
                || normalized.startsWith(USER_RESOURCE_PACK_IMPORT_ROOT)
                || normalized.startsWith("/gaius/downloads/")
                || normalized.endsWith("/options.txt")
                || normalized.endsWith("/servers.dat")
                || normalized.endsWith("/servers.dat_old")
                || normalized.endsWith("/optionsof.txt")
                || normalized.endsWith("/optionsshaders.txt");
    }

    private static boolean isScreenshotPath(String normalized) {
        return normalized.startsWith(SCREENSHOT_ROOT);
    }

    private static boolean isDownloadedPackFile(String normalized) {
        return normalized.startsWith("/gaius/downloads/")
                && !normalized.endsWith("/log.json");
    }

    private static boolean isUserResourcePackFile(String normalized) {
        return normalized.startsWith(USER_RESOURCE_PACK_ROOT)
                || normalized.startsWith(USER_RESOURCE_PACK_IMPORT_ROOT);
    }

    /**
     * The title client only needs world summaries, while a server Worker only
     * needs the world it was launched for. Restoring every region file here
     * turns a large saved world into a long synchronous browser task.
     */
    private static boolean shouldRestoreAtStartup(String path) {
        String normalized = normalize(path);
        if (normalized.contains("/.gaius-backups/") ||
                normalized.contains("/gaius/storage/recovery-journal/")) {
            return false;
        }
        if (isScreenshotPath(normalized)) {
            // Written by the game, never read back by it: restoring them only filled memory.
            return false;
        }
        String activeWorld = activeServerWorldId();
        if (activeWorld != null && !activeWorld.isEmpty()) {
            return normalized.startsWith("/gaius/saves/" + activeWorld + "/")
                    && !isOnDemandChunkStoragePath(normalized);
        }
        if (!normalized.startsWith("/gaius/saves/")) {
            return true;
        }
        int worldEnd = normalized.indexOf('/', "/gaius/saves/".length());
        if (worldEnd < 0 || worldEnd + 1 >= normalized.length()) {
            return false;
        }
        String relative = normalized.substring(worldEnd + 1);
        return relative.equals("level.dat")
                || relative.equals("level.dat_old")
                || relative.equals("icon.png")
                // WorldOpenFlows reads this small saved-data record on the
                // client before handing the world to the integrated Worker.
                // Keep large region files on their OPFS/IDB backend instead.
                || relative.equals("data/minecraft/world_gen_settings.dat");
    }

    private static String normalizeUserResourcePackPath(String path) {
        String normalized = String.valueOf(path).replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        String[] parts = normalized.split("/");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)
                    || part.indexOf('\0') >= 0) {
                return null;
            }
            if (result.length() > 0) {
                result.append('/');
            }
            result.append(part);
        }
        return result.length() == 0 ? null : result.toString();
    }

    private static boolean isOnDemandChunkStoragePath(String normalized) {
        if (!isChunkStoragePath(normalized)) {
            return false;
        }
        String activeWorld = activeServerWorldId();
        return activeWorld != null
                && !activeWorld.isEmpty()
                && normalized.startsWith("/gaius/saves/" + activeWorld + "/");
    }

    private static boolean isBinaryChunkStoragePath(String normalized) {
        if (isChunkStoragePath(normalized)) {
            return true;
        }
        String activeWorld = activeServerWorldId();
        if (activeWorld == null || activeWorld.isEmpty()
                || !normalized.startsWith("/gaius/saves/" + activeWorld + "/")) {
            return false;
        }
        String fileName = name(normalized);
        return fileName.startsWith("tmp") && fileName.endsWith(".tmp");
    }

    private static boolean isChunkStoragePath(String normalized) {
        return normalized.endsWith(".mca") || normalized.endsWith(".mcc");
    }

    private static void seedDefaultOptions() {
        // A singleplayer server Worker restores only its world, so options.txt is always missing
        // there; seeding it would write defaults over the player's saved options in the shared
        // store. The dedicated server never reads options.txt.
        String activeWorld = activeServerWorldId();
        if (activeWorld != null && !activeWorld.isEmpty()) {
            return;
        }
        try {
            VirtualFileSystem fileSystem = VirtualFileSystemProvider.getInstance();
            VirtualFile existing = fileSystem.getFile(OPTIONS_PATH);
            if (existing != null && existing.isFile()) {
                if (!migrateLegacyDefaultOptions(existing)) {
                    reconcileAutomaticPreset(existing);
                }
                return;
            }
            writeDefaultOptions("browser defaults");
        } catch (Throwable exception) {
            report("storage-default-options-failed", describe(exception));
        }
    }

    /** True when an untouched earlier seed was replaced with the tiered defaults. */
    private static boolean migrateLegacyDefaultOptions(VirtualFile existing) throws IOException {
        byte[] bytes = readVirtualFile(existing);
        String stored = new String(bytes, StandardCharsets.UTF_8);
        // The v0.3 seed, never rewritten by the game: replace it with the tiered defaults.
        if (withoutVersionLine(stored).equals(PREVIOUS_BROWSER_OPTION_DEFAULTS)) {
            writeDefaultOptions("browser defaults graphics tiers", true);
            return true;
        }
        // The launcher moves the old Fancy default to Fast once before this runs;
        // compare against the legacy payload as it was written.
        String options = stored
                .replace("graphicsPreset:\"fast\"\n", "graphicsPreset:\"fancy\"\n");
        String legacyVersionedOptions = "version:" + LEGACY_DATA_VERSION + "\n"
                + LEGACY_BROWSER_OPTION_DEFAULTS;
        if (!options.equals(LEGACY_BROWSER_OPTION_DEFAULTS)
                && !options.equals(legacyVersionedOptions)) {
            return false;
        }
        writeDefaultOptions("browser defaults data version", true);
        return true;
    }

    /**
     * The automatic video preset of a profile that already has options: while the player has not
     * changed any of the video options the tier seeded, a different tier (a new classification
     * of the machine, a {@code ?gaiusTier} override) re-applies that tier's values; once the
     * player changed one, the options are never adjusted again. The bookkeeping lives in the
     * page's quality layer ({@code GaiusQuality.autoPreset}); without it nothing changes.
     */
    private static void reconcileAutomaticPreset(VirtualFile existing) throws IOException {
        String stored = new String(readVirtualFile(existing), StandardCharsets.UTF_8);
        String tier = graphicsTier();
        String updated = syncAutomaticPreset(storageConfigurationSignature(), stored,
                defaultBrowserOptions(tier), tier, false);
        if (updated == null || updated.isEmpty() || updated.equals(stored)) {
            return;
        }
        byte[] bytes = updated.getBytes(StandardCharsets.UTF_8);
        writeVirtualFile(OPTIONS_PATH, bytes);
        persist(OPTIONS_PATH, bytes);
        report("storage-default-options", "automatic preset tier=" + tier);
    }

    private static byte[] readVirtualFile(VirtualFile file) throws IOException {
        VirtualFileAccessor accessor = file.createAccessor(true, false, false);
        if (accessor == null) {
            throw new IOException("Could not open " + OPTIONS_PATH);
        }
        try {
            int size = accessor.size();
            byte[] bytes = new byte[size];
            accessor.seek(0);
            int offset = 0;
            while (offset < size) {
                int read = accessor.read(bytes, offset, size - offset);
                if (read <= 0) {
                    break;
                }
                offset += read;
            }
            return offset == size ? bytes : Arrays.copyOf(bytes, offset);
        } finally {
            accessor.close();
        }
    }

    private static void writeDefaultOptions(String detail) throws IOException {
        writeDefaultOptions(detail, false);
    }

    /**
     * @param keepEarlierFrameSettings true when an untouched earlier seed is being replaced: those
     *        profiles keep that seed's frame settings (Unlimited, VSync off) so the rewrite
     *        changes their graphics tier but not how they pace frames. Only new profiles get
     *        vanilla's frame settings.
     */
    private static void writeDefaultOptions(String detail, boolean keepEarlierFrameSettings)
            throws IOException {
        String tier = graphicsTier();
        String options = defaultBrowserOptions(tier);
        if (keepEarlierFrameSettings) {
            options = options
                    .replace("maxFps:120\n", "maxFps:260\n")
                    .replace("enableVsync:true\n", "enableVsync:false\n");
        }
        byte[] bytes = options.getBytes(StandardCharsets.UTF_8);
        writeVirtualFile(OPTIONS_PATH, bytes);
        persist(OPTIONS_PATH, bytes);
        report("storage-default-options", detail + " tier=" + tier);
        syncAutomaticPreset(storageConfigurationSignature(), "", options, tier, true);
    }

    private static String defaultBrowserOptions(String tier) {
        String graphics = tierGraphicsDefaults(tier);
        // Hardware anisotropic filtering instead of the 8-tap RGSS shader path, where the
        // context exposes EXT_texture_filter_anisotropic: 4x on high, 8x on ultra.
        if (("high".equals(tier) || "ultra".equals(tier)) && runtimeAnisotropicFiltering()) {
            graphics = graphics
                    .replace("maxAnisotropyBit:1\n",
                            "ultra".equals(tier) ? "maxAnisotropyBit:3\n" : "maxAnisotropyBit:2\n")
                    .replace("textureFiltering:0\n", "textureFiltering:2\n");
        }
        // Improved transparency is capability-gated only on 26.3 (BrowserQualityCaps); the
        // 26.2 and 1.21.11 transparency post chain has no gate, so their seeds keep it off.
        // Their Worker world generation also stays at the 8/6 distance their graphics presets
        // are pinned to.
        if (!storageConfigurationSignature().startsWith("26.3|")) {
            graphics = graphics
                    .replace("improvedTransparency:true\n", "improvedTransparency:false\n")
                    .replace("renderDistance:10\n", "renderDistance:8\n")
                    .replace("renderDistance:12\n", "renderDistance:8\n");
        }
        return "version:" + currentDataVersion() + "\n" + BROWSER_OPTION_COMMON_DEFAULTS
                + graphics;
    }

    private static String tierGraphicsDefaults(String tier) {
        switch (tier) {
            case "low":
                return LOW_TIER_GRAPHICS_DEFAULTS;
            case "high":
                return HIGH_TIER_GRAPHICS_DEFAULTS;
            case "ultra":
                return ULTRA_TIER_GRAPHICS_DEFAULTS;
            default:
                return MID_TIER_GRAPHICS_DEFAULTS;
        }
    }

    /** low, mid, high or ultra; mid when the page has no capability layer (or in a Worker). */
    private static String graphicsTier() {
        String tier;
        try {
            tier = runtimeGraphicsTier();
        } catch (Throwable exception) {
            tier = null;
        }
        if ("low".equals(tier) || "high".equals(tier) || "ultra".equals(tier)) {
            return tier;
        }
        return "mid";
    }

    private static String withoutVersionLine(String options) {
        if (options.startsWith("version:")) {
            int newline = options.indexOf('\n');
            return newline < 0 ? "" : options.substring(newline + 1);
        }
        return options;
    }

    private static int currentDataVersion() {
        if (mounted && mountedWorldVersion > 0) {
            return mountedWorldVersion;
        }
        storageConfigurationSignature();
        int value = runtimeWorldVersion();
        if (value <= 0) {
            throw new IllegalStateException("Browser storage world version is not configured");
        }
        return value;
    }

    private static String storagePrefix() {
        if (mounted && mountedStoragePrefix != null) {
            return mountedStoragePrefix;
        }
        storageConfigurationSignature();
        String value = runtimeStoragePrefix();
        if (value == null || value.trim().isEmpty()
                || "gaius.fs.v1:".equals(value)) {
            throw new IllegalStateException("Browser storage prefix is not configured");
        }
        return value;
    }

    private static String storageConfigurationSignature() {
        String value = runtimeStorageConfigurationSignature();
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("Browser storage profile is not configured safely");
        }
        if (mounted && !value.equals(mountedStorageSignature)) {
            throw new IllegalStateException("Browser storage profile changed after mount");
        }
        return value;
    }

    private static String describe(Throwable exception) {
        if (exception == null) {
            return "unknown";
        }
        String message = exception.getMessage();
        String name = exception.getClass().getName();
        return message == null || message.isEmpty() ? name : name + ": " + message;
    }

    private static void writeVirtualFile(String path, byte[] bytes) throws IOException {
        VirtualFileSystem fileSystem = VirtualFileSystemProvider.getInstance();
        ensureParentDirectories(fileSystem, path);
        String parentPath = parent(path);
        String name = name(path);
        VirtualFile parent = fileSystem.getFile(parentPath);
        if (parent == null || !parent.isDirectory()) {
            throw new IOException("Could not open parent directory " + parentPath);
        }
        VirtualFile file = fileSystem.getFile(path);
        if ((file == null || !file.isFile()) && !parent.createFile(name)) {
            file = fileSystem.getFile(path);
            if (file == null || !file.isFile()) {
                throw new IOException("Could not create " + path);
            }
        }
        file = fileSystem.getFile(path);
        if (file == null || !file.isFile()) {
            throw new IOException("Could not open " + path);
        }
        VirtualFileAccessor accessor = file.createAccessor(false, true, false);
        if (accessor == null) {
            throw new IOException("Could not open " + path);
        }
        accessor.resize(0);
        accessor.write(bytes, 0, bytes.length);
        accessor.flush();
        accessor.close();
    }

    private static void ensureParentDirectories(VirtualFileSystem fileSystem, String path) {
        String parentPath = parent(path);
        if (parentPath.isEmpty() || parentPath.equals("/")) {
            return;
        }
        ensureParentDirectories(fileSystem, parentPath);
        VirtualFile parent = fileSystem.getFile(parent(parentPath));
        VirtualFile directory = fileSystem.getFile(parentPath);
        if ((directory == null || !directory.isDirectory()) && parent != null && parent.isDirectory()) {
            parent.createDirectory(name(parentPath));
        }
    }

    private static String normalize(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        String normalized = path.replace('\\', '/');
        return normalized.startsWith("/") ? normalized : "/" + normalized;
    }

    private static String parent(String path) {
        String normalized = normalize(path);
        int slash = normalized.lastIndexOf('/');
        return slash <= 0 ? "/" : normalized.substring(0, slash);
    }

    private static String name(String path) {
        String normalized = normalize(path);
        int slash = normalized.lastIndexOf('/');
        return slash < 0 ? normalized : normalized.substring(slash + 1);
    }

    @JSBody(params = {"prefix"}, script = """
            try {
              var files=globalThis.__gaiusPersistentFiles;
              if (files) return Object.keys(files);
              var result=[];
              var storage=globalThis.localStorage;
              if (!storage) return result;
              for (var i=0;i<storage.length;i++) {
                var key=storage.key(i);
                if (key && key.indexOf(prefix)===0) result.push(key.substring(prefix.length));
              }
              return result;
            } catch (e) {
              return [];
            }
            """)
    private static native String[] storedPaths(String prefix);

    @JSBody(params = {"key"}, script = """
            try {
              var prefix=String(globalThis.__gaiusStoragePrefix || '');
              if (!prefix || !key || key.indexOf(prefix)!==0) return null;
              var files=globalThis.__gaiusPersistentFiles;
              if (files) {
                var path=key.substring(prefix.length);
                return Object.prototype.hasOwnProperty.call(files,path) ? files[path] : null;
              }
              return globalThis.localStorage ? globalThis.localStorage.getItem(key) : null;
            } catch (e) {
              return null;
            }
            """)
    private static native String getItem(String key);

    @JSBody(params = {"key", "value"}, script = """
            try {
              var prefix=String(globalThis.__gaiusStoragePrefix || '');
              if (!prefix || !key || key.indexOf(prefix)!==0) return false;
              if (globalThis.__gaiusFsPut) {
                return !!globalThis.__gaiusFsPut(key.substring(prefix.length), value);
              }
              if (!globalThis.localStorage) return false;
              globalThis.localStorage.setItem(key,value);
              return true;
            } catch (e) {
              return false;
            }
            """)
    private static native boolean setItem(String key, String value);

    @JSBody(params = {"path", "bytes"}, script = """
            try {
              if (!globalThis.__gaiusFsPutBytes) return false;
              return !!globalThis.__gaiusFsPutBytes(String(path || '/'), bytes);
            } catch (e) {
              return false;
            }
            """)
    private static native boolean setBytesJs(String path, Int8Array bytes);

    private static boolean setBytes(String path, byte[] bytes) {
        return setBytesJs(path, JsArrays.int8(bytes));
    }

    @JSBody(params = {"path", "bytes"}, script = """
            try {
              if (typeof globalThis.__gaiusFsPutScreenshot !== 'function') return -1;
              return globalThis.__gaiusFsPutScreenshot(String(path || '/'), bytes) ? 1 : 0;
            } catch (e) {
              return 0;
            }
            """)
    private static native int storeScreenshotJs(String path, Int8Array bytes);

    /** 1 stored, 0 refused (no IndexedDB, over the size limit), -1 the page has no screenshot store. */
    private static int storeScreenshot(String path, byte[] bytes) {
        return storeScreenshotJs(path, JsArrays.int8(bytes));
    }

    @JSBody(params = {"path"}, script = """
            try {
              return typeof globalThis.__gaiusFsCanPatchBytes === 'function'
                && !!globalThis.__gaiusFsCanPatchBytes(String(path || '/'));
            } catch (e) {
              return false;
            }
            """)
    private static native boolean canPatchBytes(String path);

    @JSBody(params = {"path", "logicalSize", "offsets", "lengths", "payload"}, script = """
            try {
              if (typeof globalThis.__gaiusFsPatchBytes !== 'function') return false;
              return !!globalThis.__gaiusFsPatchBytes(
                String(path || '/'), logicalSize, offsets, lengths, payload);
            } catch (e) {
              return false;
            }
            """)
    private static native boolean patchBytesJs(
            String path,
            int logicalSize,
            Int32Array offsets,
            Int32Array lengths,
            Int8Array payload);

    private static boolean patchBytes(
            String path, int logicalSize, int[] offsets, int[] lengths, byte[] payload) {
        return patchBytesJs(path, logicalSize, JsArrays.int32(offsets), JsArrays.int32(lengths),
                JsArrays.int8(payload));
    }

    @JSBody(params = {"path", "byteLength"}, script = """
            try {
              var state=globalThis.__gaiusMultiplayerRecovery ||
                (globalThis.__gaiusMultiplayerRecovery={});
              state.packCachedAt=Date.now();
              state.packPath=String(path || '');
              state.packBytes=byteLength|0;
              state.packWrites=(state.packWrites|0)+1;
              state.packAddress=String(state.activeAddress || '');
              state.packAttempt=state.activeAttempt|0;
            } catch (e) {}
            """)
    private static native void markDownloadedPackPersisted(String path, int byteLength);

    @JSBody(params = {"path"}, script = """
            try {
              path=String(path || '/');
              var prefix=String(globalThis.__gaiusStoragePrefix || '');
              if (!prefix) return -1;
              var files=globalThis.__gaiusPersistentFiles;
              var value=files && Object.prototype.hasOwnProperty.call(files,path)
                ? files[path]
                : (globalThis.localStorage
                  ? globalThis.localStorage.getItem(prefix + path) : null);
              if (typeof value === 'string') {
                if (!value.length) return 0;
                var padding=value.endsWith('==') ? 2 : (value.endsWith('=') ? 1 : 0);
                return Math.floor(value.length * 3 / 4) - padding;
              }
              if (value instanceof ArrayBuffer) return value.byteLength;
              if (ArrayBuffer.isView(value)) return value.byteLength;
              return -1;
            } catch (e) {
              return -1;
            }
            """)
    private static native int storedByteLength(String path);

    @JSBody(params = {"path", "output"}, script = """
            try {
              path=String(path || '/');
              var prefix=String(globalThis.__gaiusStoragePrefix || '');
              if (!prefix) return -1;
              var files=globalThis.__gaiusPersistentFiles;
              var value=files && Object.prototype.hasOwnProperty.call(files,path)
                ? files[path]
                : (globalThis.localStorage
                  ? globalThis.localStorage.getItem(prefix + path) : null);
              var bytes;
              if (typeof value === 'string') {
                if (typeof Uint8Array.fromBase64 === 'function') {
                  bytes=Uint8Array.fromBase64(value);
                } else {
                  var binary=globalThis.atob(value);
                  bytes=new Uint8Array(binary.length);
                  for (var i=0;i<binary.length;i++) bytes[i]=binary.charCodeAt(i);
                }
              } else if (value instanceof ArrayBuffer) {
                bytes=new Uint8Array(value);
              } else if (ArrayBuffer.isView(value)) {
                bytes=new Uint8Array(value.buffer,value.byteOffset,value.byteLength);
              } else {
                return -1;
              }
              if (!output || output.length !== bytes.length) return -1;
              if (typeof output.set === 'function') output.set(bytes);
              else for (var j=0;j<bytes.length;j++) output[j]=bytes[j];
              return bytes.length;
            } catch (e) {
              return -1;
            }
            """)
    private static native int copyStoredBytesJs(String path, Int8Array output);

    private static int copyStoredBytes(String path, byte[] output) {
        Int8Array view = JsArrays.int8(output);
        int copied = copyStoredBytesJs(path, view);
        if (copied > 0) {
            JsArrays.readBack(view, output);
        }
        return copied;
    }

    @JSBody(params = {"path", "resume"}, script = """
            try {
              var results = globalThis.__gaiusFsRequestResults ||
                (globalThis.__gaiusFsRequestResults = Object.create(null));
              var key = String(path || '/');
              results[key] = {success: false, detail: 'request did not complete'};
              var request = globalThis.__gaiusFsRequestRegion;
              if (typeof request !== 'function') {
                results[key] = {success: false, detail: 'IndexedDB region request is unavailable'};
                resume();
                return;
              }
              request(key, function(success, detail) {
                results[key] = {success: !!success, detail: String(detail || '')};
                resume();
              });
            } catch (error) {
              var fallback = globalThis.__gaiusFsRequestResults ||
                (globalThis.__gaiusFsRequestResults = Object.create(null));
              fallback[String(path || '/')] = {
                success: false, detail: String(error && (error.message || error) || error)};
              resume();
            }
            """)
    private static native void requestStoredRegion(String path, StorageResume resume);

    @JSFunctor
    private interface StorageResume extends JSObject {
        void run();
    }

    @JSBody(params = {"path"}, script = """
            var release = globalThis.__gaiusFsReleaseRegion;
            if (typeof release === 'function') release(String(path || '/'));
            """)
    private static native void releaseStoredRegionBridge(String path);

    @JSBody(params = {"path"}, script = """
            var results = globalThis.__gaiusFsRequestResults || {};
            var result = results[String(path || '/')];
            return !!(result && result.success);
            """)
    private static native boolean storedRegionRequestSucceeded(String path);

    @JSBody(params = {"path"}, script = """
            var results = globalThis.__gaiusFsRequestResults || {};
            var result = results[String(path || '/')];
            return result ? String(result.detail || '') : 'request did not complete';
            """)
    private static native String storedRegionRequestDetail(String path);

    @JSBody(params = {"path"}, script = """
            try {
              path=String(path || '/');
              var prefix=String(globalThis.__gaiusStoragePrefix || '');
              if (!prefix) return false;
              var files=globalThis.__gaiusPersistentFiles;
              if (files && Object.prototype.hasOwnProperty.call(files,path)) return true;
              return !!(globalThis.localStorage &&
                globalThis.localStorage.getItem(prefix + path) !== null);
            } catch (e) {
              return false;
            }
            """)
    private static native boolean hasStoredPath(String path);

    @JSBody(params = {"key"}, script = """
            try {
              var prefix=String(globalThis.__gaiusStoragePrefix || '');
              if (!prefix || !key || key.indexOf(prefix)!==0) return;
              if (globalThis.__gaiusFsDelete) {
                globalThis.__gaiusFsDelete(key.substring(prefix.length));
                return;
              }
              if (globalThis.localStorage) globalThis.localStorage.removeItem(key);
            } catch (e) {}
            """)
    private static native void removeItem(String key);

    @JSBody(script = """
            if (globalThis.__gaiusFsBackend) return String(globalThis.__gaiusFsBackend);
            if (globalThis.__gaiusPersistentFiles) return 'memory';
            try {
              return globalThis.localStorage ? 'localStorage' : 'none';
            } catch (e) {
              return 'none';
            }
            """)
    private static native String backendName();

    /**
     * The GPU tier of the graphics quality layer. Asks it to classify now when it has not yet
     * (a synchronous probe on a private canvas, remembered in localStorage), then falls back to
     * the published or remembered tier, then to the user agent (mobile: low). Empty when unknown.
     */
    @JSBody(script = """
            try {
              var root = globalThis;
              var quality = root.GaiusQuality;
              if (quality && quality.profile && typeof quality.profile.resolve === 'function') {
                var settings = quality.profile.resolve();
                if (settings && settings.memoryTier && settings.memoryTier.lowMemory) return 'low';
              }
              if (quality && quality.caps && typeof quality.caps.ensureTier === 'function') {
                var ensured = quality.caps.ensureTier();
                if (ensured && ensured.tier) return String(ensured.tier);
              }
              var caps = root.__gaiusGpuCaps;
              if (caps && caps.tier) return String(caps.tier);
              var storage = null;
              try {
                storage = root.localStorage || null;
              } catch (ignored) {
                storage = null;
              }
              if (storage) {
                var stored = storage.getItem('gaius.quality.gpuTier.v1');
                if (stored) {
                  var parsed = JSON.parse(stored);
                  if (parsed && parsed.tier) return String(parsed.tier);
                }
              }
              var nav = root.navigator;
              if (nav && ((nav.userAgentData && nav.userAgentData.mobile)
                  || /Android|iPhone|iPad|iPod|Mobile/i.test(String(nav.userAgent || '')))) {
                return 'low';
              }
              return '';
            } catch (e) {
              return '';
            }
            """)
    private static native String runtimeGraphicsTier();

    /**
     * {@code GaiusQuality.autoPreset.sync}: records the preset just seeded ({@code seeded}) or
     * decides whether {@code current} still follows the recorded one. Returns the options to
     * write, or an empty string to keep {@code current}.
     */
    @JSBody(params = {"profile", "current", "seed", "tier", "seeded"}, script = """
            try {
              var quality = globalThis.GaiusQuality;
              if (quality && quality.autoPreset && typeof quality.autoPreset.sync === 'function') {
                var result = quality.autoPreset.sync(profile, seeded ? null : current, seed, tier);
                return typeof result === 'string' ? result : '';
              }
            } catch (e) {
              // The options stay as they are.
            }
            return '';
            """)
    private static native String syncAutomaticPreset(String profile, String current, String seed,
            String tier, boolean seeded);

    /** Whether the capability layer saw EXT_texture_filter_anisotropic (false when unknown). */
    @JSBody(script = """
            try {
              var caps = globalThis.__gaiusGpuCaps;
              return !!(caps && caps.extensions && caps.extensions.anisotropic);
            } catch (e) {
              return false;
            }
            """)
    private static native boolean runtimeAnisotropicFiltering();

    @JSBody(script = """
            try {
              return Number(globalThis.__gaiusWorldVersion || 0) | 0;
            } catch (e) {
              return 0;
            }
            """)
    private static native int runtimeWorldVersion();

    @JSBody(script = """
            try {
              var value=globalThis.__gaiusStoragePrefix;
              return value == null ? null : String(value);
            } catch (e) {
              return null;
            }
            """)
    private static native String runtimeStoragePrefix();

    @JSBody(script = """
            try {
              var profile=String(globalThis.__gaiusProfileId || '');
              var world=Number(globalThis.__gaiusWorldVersion);
              var schema=Number(globalThis.__gaiusStorageSchema);
              var database=String(globalThis.__gaiusStorageDatabaseName || '');
              var prefix=String(globalThis.__gaiusStoragePrefix || '');
              var opfs=String(globalThis.__gaiusStorageOpfsDirectory || '');
              if (profile==='1.21.11' && world===4671 && schema===2
                  && database==='gaius-fs-v2-1.21.11'
                  && prefix==='gaius.fs.v2:1.21.11:'
                  && opfs==='regions-v2-1.21.11') {
                return profile+'|'+world+'|'+schema+'|'+database+'|'+prefix+'|'+opfs;
              }
              if (profile==='26.2' && world===4903 && schema===2
                  && database==='gaius-fs-v2-26.2'
                  && prefix==='gaius.fs.v2:26.2:'
                  && opfs==='regions-v2-26.2') {
                return profile+'|'+world+'|'+schema+'|'+database+'|'+prefix+'|'+opfs;
              }
              if (profile==='26.3' && world===5023 && schema===2
                  && database==='gaius-fs-v2-26.3'
                  && prefix==='gaius.fs.v2:26.3:'
                  && opfs==='regions-v2-26.3') {
                return profile+'|'+world+'|'+schema+'|'+database+'|'+prefix+'|'+opfs;
              }
              return '';
            } catch (e) {
              return '';
            }
            """)
    private static native String runtimeStorageConfigurationSignature();

    @JSBody(script = """
            try {
              return globalThis.__gaiusServerWorldId == null
                ? null
                : String(globalThis.__gaiusServerWorldId);
            } catch (e) {
              return null;
            }
            """)
    private static native String activeServerWorldId();

    @JSBody(params = {"event", "detail"}, script = """
            try {
              var counters=globalThis.__gaiusMinecraftCounters || (globalThis.__gaiusMinecraftCounters={});
              var key='storage:'+event;
              counters[key]=(counters[key]||0)+1;
              var events=globalThis.__gaiusMinecraftEvents || (globalThis.__gaiusMinecraftEvents=[]);
              events.push({event:'storage:'+event,detail:detail,count:counters[key],at:Date.now()});
              if (events.length>500) events.splice(0,events.length-500);
            } catch (e) {}
            """)
    private static native void reportRegion(String event, String detail);

    @JSBody(params = {"event", "detail"}, script = """
            try {
              var counters=globalThis.__gaiusMinecraftCounters || (globalThis.__gaiusMinecraftCounters={});
              var key='storage:'+event+':'+detail;
              counters[key]=(counters[key]||0)+1;
              var events=globalThis.__gaiusMinecraftEvents || (globalThis.__gaiusMinecraftEvents=[]);
              events.push({event:'storage:'+event,detail:detail,count:counters[key],at:Date.now()});
              if (events.length>500) events.splice(0,events.length-500);
            } catch (e) {}
            """)
    private static native void report(String event, String detail);
}
