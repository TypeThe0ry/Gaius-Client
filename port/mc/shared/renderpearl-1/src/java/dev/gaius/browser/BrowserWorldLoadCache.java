package dev.gaius.browser;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.commands.Commands;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.flag.FeatureFlagSet;

/**
 * The data pack stages of {@code WorldLoader.load}, computed once ahead of the click.
 *
 * <p>ServerWorkerPatches' world loader patch (WorldLoaderCachePatches) routes the four calls
 * of {@code WorldLoader.load} that depend only on the selected data packs and the load's own
 * parameters (the static registries' tag lists, the two {@code RegistryDataLoader.load} calls
 * and {@code ReloadableServerResources.loadResources}) through this class. In a normal start
 * every call is vanilla's. After {@code BrowserWorldLoadOps.prewarm} recorded their results for
 * the default data pack, the first load whose resource manager holds exactly the same packs is
 * answered from the record: the tag list, then the world and dimension registry groups, then the
 * reloadable resources when the feature flags, command selection and function permissions are
 * the recorded ones and the load carries the recorded registries and tags. They are replayed
 * together, so the registries, the pending tags and the resources that reference each other
 * stay one consistent set. The record stops short of {@code updateComponentsAndStaticRegistryTags},
 * which vanilla still runs once on the replayed resources. A record serves one load; every other
 * load, and any pack list or parameter that differs (a world data pack, an experimental feature
 * pack), runs vanilla's code.
 */
public final class BrowserWorldLoadCache {
    private BrowserWorldLoadCache() {
    }

    private static final class Prepared {
        final List<String> packs;
        final List<?> pendingTags;
        Object worldRegistries;
        Object dimensionRegistries;
        ReloadableServerResources resources;
        FeatureFlagSet features;
        Commands.CommandSelection selection;
        PermissionSet permissions;

        Prepared(List<String> packs, List<?> pendingTags) {
            this.packs = packs;
            this.pendingTags = pendingTags;
        }

        boolean complete() {
            return worldRegistries != null && dimensionRegistries != null;
        }

        Prepared withPacks(List<String> other) {
            Prepared copy = new Prepared(other, pendingTags);
            copy.worldRegistries = worldRegistries;
            copy.dimensionRegistries = dimensionRegistries;
            copy.resources = resources;
            copy.features = features;
            copy.selection = selection;
            copy.permissions = permissions;
            return copy;
        }
    }

    private static final Object LOCK = new Object();
    private static boolean recording;
    private static Prepared recorded;
    private static CompletableFuture<Void> recordingDone;
    private static Prepared ready;
    private static Prepared served;

    /** Starts a recording; the result completes when the resources were recorded (or failed). */
    static CompletableFuture<Void> beginRecording() {
        synchronized (LOCK) {
            recording = true;
            recorded = null;
            ready = null;
            served = null;
            recordingDone = new CompletableFuture<>();
            return recordingDone;
        }
    }

    /** Ends the recording; true when the registry stages were recorded and a load can use them. */
    static boolean endRecording(boolean forceMiss) {
        synchronized (LOCK) {
            recording = false;
            Prepared record = recorded;
            recorded = null;
            recordingDone = null;
            ready = record != null && record.complete()
                    ? (forceMiss ? record.withPacks(List.of("gaius-diagnostic-miss")) : record) : null;
            return ready != null;
        }
    }

    /** Whether the ended recording also holds the reloadable resources. */
    static boolean hasResources() {
        synchronized (LOCK) {
            return ready != null && ready.resources != null;
        }
    }

    static void discard() {
        synchronized (LOCK) {
            recording = false;
            recorded = null;
            recordingDone = null;
            ready = null;
            served = null;
        }
    }

    private static List<String> packs(ResourceManager manager) {
        return manager.listPacks().map(PackResources::packId).toList();
    }

    /** Replaces {@code TagLoader.loadTagsForExistingRegistries} in WorldLoader. */
    public static List loadTags(ResourceManager manager, RegistryAccess access) {
        boolean record;
        Prepared offered = null;
        synchronized (LOCK) {
            record = recording;
            if (!record) {
                offered = ready;
                ready = null;
                served = null;
            }
        }
        if (record) {
            List pending = TagLoader.loadTagsForExistingRegistries(manager, access);
            synchronized (LOCK) {
                recorded = new Prepared(packs(manager), pending);
            }
            return pending;
        }
        if (offered != null) {
            List<String> actual = packs(manager);
            if (actual.equals(offered.packs)) {
                synchronized (LOCK) {
                    served = offered;
                }
                BrowserIntegratedServerMain.reportRuntimeEvent("world-load-cache", "hit " + actual);
                return offered.pendingTags;
            }
            BrowserIntegratedServerMain.reportRuntimeEvent("world-load-cache",
                    "miss packs=" + actual + " prewarmed=" + offered.packs);
        }
        return TagLoader.loadTagsForExistingRegistries(manager, access);
    }

    /** Replaces both {@code RegistryDataLoader.load} calls of WorldLoader. */
    public static CompletableFuture loadRegistries(
            ResourceManager manager, List lookups, List registries, Executor executor) {
        boolean world = registries == RegistryDataLoader.WORLD_REGISTRIES;
        boolean dimension = registries == RegistryDataLoader.DIMENSION_REGISTRIES;
        Prepared record;
        boolean recordNow;
        synchronized (LOCK) {
            recordNow = recording && recorded != null;
            record = recordNow ? recorded : served;
        }
        if (record != null && (world || dimension)) {
            if (!recordNow) {
                Object cached;
                synchronized (LOCK) {
                    cached = world ? record.worldRegistries : record.dimensionRegistries;
                }
                if (cached != null) {
                    return CompletableFuture.completedFuture(cached);
                }
            } else {
                CompletableFuture<?> loaded = RegistryDataLoader.load(manager, lookups, registries, executor);
                return loaded.thenApply(result -> {
                    synchronized (LOCK) {
                        if (world) {
                            record.worldRegistries = result;
                        } else {
                            record.dimensionRegistries = result;
                        }
                    }
                    return result;
                });
            }
        }
        return RegistryDataLoader.load(manager, lookups, registries, executor);
    }

    /** Replaces {@code ReloadableServerResources.loadResources} in WorldLoader. */
    public static CompletableFuture loadResources(ResourceManager manager,
            LayeredRegistryAccess layers, List pending, FeatureFlagSet features,
            Commands.CommandSelection selection, PermissionSet permissions,
            Executor first, Executor second) {
        Prepared record;
        boolean recordNow;
        CompletableFuture<Void> done;
        synchronized (LOCK) {
            recordNow = recording && recorded != null;
            record = recordNow ? recorded : served;
            done = recordingDone;
            if (!recordNow) {
                served = null;
            }
        }
        if (recordNow) {
            // Record the resources and stop: the returned future never completes, so vanilla's
            // next step (binding the tags and components into the static registries) does not
            // run on the recording's own resources; the recorder waits on `done` instead.
            CompletableFuture<ReloadableServerResources> loaded = ReloadableServerResources.loadResources(
                    manager, layers, pending, features, selection, permissions, first, second);
            loaded.whenComplete((resources, failure) -> {
                if (failure != null) {
                    done.completeExceptionally(failure);
                    return;
                }
                synchronized (LOCK) {
                    record.resources = resources;
                    record.features = features;
                    record.selection = selection;
                    record.permissions = permissions;
                }
                done.complete(null);
            });
            return new CompletableFuture<ReloadableServerResources>();
        }
        if (record != null && record.resources != null) {
            String reason = replayable(record, layers, pending, features, selection, permissions);
            if (reason == null) {
                BrowserIntegratedServerMain.reportRuntimeEvent("world-load-cache", "resources hit");
                return CompletableFuture.completedFuture(record.resources);
            }
            BrowserIntegratedServerMain.reportRuntimeEvent("world-load-cache", "resources miss " + reason);
        }
        return ReloadableServerResources.loadResources(
                manager, layers, pending, features, selection, permissions, first, second);
    }

    /** Why the recorded resources cannot serve this load, or null. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String replayable(Prepared record, LayeredRegistryAccess layers, List pending,
            FeatureFlagSet features, Commands.CommandSelection selection, PermissionSet permissions) {
        if (pending != record.pendingTags) {
            return "tags";
        }
        if (!features.equals(record.features)) {
            return "features";
        }
        if (selection != record.selection) {
            return "selection";
        }
        if (!permissions.equals(record.permissions)) {
            return "permissions";
        }
        // The load must carry the registries the resources were recorded against.
        RegistryAccess composite = layers.compositeAccess();
        RegistryAccess recordedWorld = (RegistryAccess) record.worldRegistries;
        for (Object entry : RegistryDataLoader.WORLD_REGISTRIES) {
            var key = ((RegistryDataLoader.RegistryData<?>) entry).key();
            Registry<?> actual = composite.lookup(key).orElse(null);
            Registry<?> recordedRegistry = recordedWorld.lookup(key).orElse(null);
            if (actual != recordedRegistry) {
                return "registries";
            }
        }
        return null;
    }
}
