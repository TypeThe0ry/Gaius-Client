#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$ROOT/port/scripts/version-profile.sh"
gaius_load_version_profile "$ROOT"
gaius_select_java_home
overlay_directory="$(gaius_overlay_directory "$ROOT")"

MEMORY_SOURCE="$ROOT/port/overrides/libraries/lwjgl/src/main/java/org/lwjgl/system/BrowserMemory.java"
# The patched LWJGL core of the active profile: lwjgl-3.4.1-unsafe.jar for
# 26.2, the plain lwjgl-3.4.3.jar (MemoryBackend shape) for 26.3.
MEMORY_JAR="$overlay_directory/libraries/$(gaius_library_path "org.lwjgl:lwjgl" "unsafe")"
JOML_JAR="$(find "$overlay_directory/libraries/org/joml/joml" \
    -type f -name 'joml-*.jar' -print | sort | tail -1)"
TEAVM_JSO_JAR="$(find "$HOME/.m2/repository/org/teavm/teavm-jso" \
    -type f -name 'teavm-jso-*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' \
    -print | sort | tail -1)"
TEAVM_INTEROP_JAR="$(find "$HOME/.m2/repository/org/teavm/teavm-interop" \
    -type f -name 'teavm-interop-*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' \
    -print | sort | tail -1)"

die() {
    printf 'browser memory lifecycle smoke failed: %s\n' "$1" >&2
    exit 1
}

[[ -f "$MEMORY_JAR" ]] || die "missing patched LWJGL jar; run build-overlays.sh first"
[[ -f "$JOML_JAR" ]] || die "missing JOML jar; run build-overlays.sh first"
[[ -f "$TEAVM_JSO_JAR" ]] || die "missing TeaVM JSO jar"
[[ -f "$TEAVM_INTEROP_JAR" ]] || die "missing TeaVM interop jar"

TELEMETRY_BLOCK="$(sed -n \
    '/private static void publishTelemetry()/,/private static native void publishTelemetryBrowser(/p' \
    "$MEMORY_SOURCE")"
[[ -n "$TELEMETRY_BLOCK" ]] || die "missing browser memory telemetry publisher"
printf '%s\n' "$TELEMETRY_BLOCK" \
    | rg -q 'globalThis\.__gaiusMemoryTelemetry' \
    || die "telemetry is not published at globalThis.__gaiusMemoryTelemetry"
printf '%s\n' "$TELEMETRY_BLOCK" \
    | rg -q 'root\.browserMemory' \
    || die "telemetry is not published under browserMemory"

for field in \
    liveRegions liveBytes associatedBuffers \
    peakLiveRegions peakLiveBytes peakAssociatedBuffers \
    allocations frees registrations reallocations collectedAssociations addressLookupMisses \
    maxLiveBytes allocationFailures maxTemporaryBytes peakTemporaryBytes \
    temporaryAllocationFailures; do
    printf '%s\n' "$TELEMETRY_BLOCK" | rg -q "memory\\.${field} =" \
        || die "missing scalar telemetry field: $field"
done

if printf '%s\n' "$TELEMETRY_BLOCK" \
        | rg -q '\.push\(|\bhistory\b|\bsamples\b|new (Array|Map|Set)\('; then
    die "browser memory telemetry retains history or a growing collection"
fi

HOT_VERTEX_BLOCK="$(sed -n \
    '/public static void putPosition(/,/public static void set(/p' \
    "$MEMORY_SOURCE")"
if printf '%s\n' "$HOT_VERTEX_BLOCK" | rg -q 'publishTelemetry\('; then
    die "browser memory telemetry was added to the vertex write hot path"
fi

TMP="$(mktemp -d "${TMPDIR:-/tmp}/gaius-browser-memory-smoke.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/src/org/lwjgl/system" "$TMP/classes"

cat > "$TMP/src/org/lwjgl/system/BrowserMemoryLifecycleSmoke.java" <<'JAVA'
package org.lwjgl.system;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

public final class BrowserMemoryLifecycleSmoke {
    private static final int REPEAT_COUNT = 1_000;
    private static final int TRANSIENT_VIEW_COUNT = 20_000;

    private BrowserMemoryLifecycleSmoke() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("budget")) {
            verifyConfiguredBudget();
            return;
        }
        if (args.length > 0 && args[0].equals("hard-cap")) {
            verifyHardCaps();
            return;
        }
        if (args.length > 0 && args[0].equals("jni-env")) {
            verifyJniEnvironmentReplacement();
            return;
        }
        if (args.length > 0 && args[0].equals("jni-init-failure")) {
            verifyJniEnvironmentInitializationRollback();
            return;
        }
        check(MemoryUtil.memAddressSafe((ByteBuffer) null) == 0L,
                "patched memAddressSafe(null) did not preserve LWJGL null semantics");
        int baselineRegions = BrowserMemory.liveRegions();
        long baselineBytes = BrowserMemory.liveBytes();
        int baselineAssociations = BrowserMemory.associatedBuffers();
        long baselineAllocations = BrowserMemory.allocations();
        long baselineFrees = BrowserMemory.frees();
        long baselineCollected = BrowserMemory.collectedAssociations();

        verifyRepeatedAllocateDeriveFree(
                baselineRegions, baselineBytes, baselineAssociations);
        verifyByteBufferWrap(baselineRegions, baselineBytes, baselineAssociations);
        verifyReallocate(baselineRegions, baselineBytes, baselineAssociations);
        verifyRegister(baselineRegions, baselineBytes, baselineAssociations);
        verifyAutomaticBufferRegionRecovery(
                baselineRegions, baselineBytes, baselineAssociations);
        verifyExplicitRegionSurvivesBufferCollection(
                baselineRegions, baselineBytes, baselineAssociations);
        verifyInvalidReleaseProtection(
                baselineRegions, baselineBytes, baselineAssociations);
        verifyWeakAssociationRecovery(
                baselineRegions, baselineBytes, baselineAssociations, baselineCollected);

        check(BrowserMemory.liveRegions() == baselineRegions, "live region count did not recover");
        check(BrowserMemory.liveBytes() == baselineBytes, "live byte count did not recover");
        check(BrowserMemory.associatedBuffers() == baselineAssociations,
                "associated buffer count did not recover");
        check(BrowserMemory.allocations() >= baselineAllocations + REPEAT_COUNT + 4L,
                "allocation telemetry did not advance");
        check(BrowserMemory.frees() >= baselineFrees + REPEAT_COUNT + 4L,
                "free telemetry did not advance");
        check(BrowserMemory.reallocations() >= 1L, "reallocation telemetry did not advance");
        check(BrowserMemory.registrations() >= 1L, "registration telemetry did not advance");
        check(BrowserMemory.peakLiveRegions() >= baselineRegions + 1,
                "peak region telemetry is too small");
        check(BrowserMemory.peakLiveBytes() >= baselineBytes + 128L,
                "peak byte telemetry is too small");
        check(BrowserMemory.peakAssociatedBuffers() >= baselineAssociations + 3,
                "peak association telemetry is too small");

        System.out.printf(
                "browser memory lifecycle smoke passed: allocations=%d frees=%d "
                        + "liveRegions=%d liveBytes=%d associated=%d collected=%d peakAssociated=%d%n",
                BrowserMemory.allocations() - baselineAllocations,
                BrowserMemory.frees() - baselineFrees,
                BrowserMemory.liveRegions(),
                BrowserMemory.liveBytes(),
                BrowserMemory.associatedBuffers(),
                BrowserMemory.collectedAssociations() - baselineCollected,
                BrowserMemory.peakAssociatedBuffers());
    }

    private static void verifyByteBufferWrap(
            int baselineRegions, long baselineBytes, int baselineAssociations) {
        long address = BrowserMemory.allocate(64);
        BrowserMemory.putInt(address + 12L, 0x2468_ace0);
        ByteBuffer bytes = (ByteBuffer) BrowserMemory.wrap(
                BrowserMemory.bufferClass(0), address + 8L, 16);
        check(BrowserMemory.address0(bytes) == address + 8L,
                "byte-buffer wrap address mismatch");
        check(bytes.getInt(4) == 0x2468_ace0, "byte-buffer wrap lost aliasing");
        check(BrowserMemory.associatedBuffers() == baselineAssociations + 1,
                "byte-buffer wrap registered the same slice more than once");
        BrowserMemory.free(address);
        check(BrowserMemory.liveRegions() == baselineRegions,
                "byte-buffer wrap region was not released");
        check(BrowserMemory.liveBytes() == baselineBytes,
                "byte-buffer wrap bytes were not released");
        check(BrowserMemory.associatedBuffers() == baselineAssociations,
                "byte-buffer wrap association was not released");

        address = BrowserMemory.allocate(64);
        IntBuffer ints = (IntBuffer) BrowserMemory.wrap(
                BrowserMemory.bufferClass(3), address, 8);
        check(BrowserMemory.associatedBuffers() == baselineAssociations + 1,
                "typed wrap retained an invisible intermediate ByteBuffer association");
        BrowserMemory.free((Buffer) ints);
        check(BrowserMemory.liveRegions() == baselineRegions,
                "typed wrap region was not released");
        check(BrowserMemory.liveBytes() == baselineBytes,
                "typed wrap bytes were not released");
        check(BrowserMemory.associatedBuffers() == baselineAssociations,
                "typed wrap association was not released");
    }

    private static void verifyJniEnvironmentReplacement() {
        int baselineRegions = BrowserMemory.liveRegions();
        long baselineBytes = BrowserMemory.liveBytes();
        long lastTable = 0L;
        int finalFunctionCount = 0;
        for (int iteration = 0; iteration < REPEAT_COUNT; iteration++) {
            finalFunctionCount = 64 + (iteration & 31);
            lastTable = BrowserMemory.setupThreadEnv(finalFunctionCount);
        }
        long environment = BrowserMemory.threadJniEnv();
        long expectedTableBytes = (long) (finalFunctionCount + 4) * Long.BYTES;
        check(BrowserMemory.getLong(environment) == lastTable,
                "JNI environment did not point at the latest function table");
        check(BrowserMemory.liveRegions() == baselineRegions + 2,
                "repeated JNI setup retained obsolete function-table regions");
        check(BrowserMemory.liveBytes() == baselineBytes + Long.BYTES + expectedTableBytes,
                "repeated JNI setup retained obsolete function-table bytes");
        System.out.println("browser JNI environment replacement smoke passed");
    }

    private static void verifyJniEnvironmentInitializationRollback() {
        long failuresBefore = BrowserMemory.allocationFailures();
        try {
            BrowserMemory.threadJniEnv();
            throw new AssertionError("JNI environment initialization escaped the memory budget");
        } catch (OutOfMemoryError expected) {
            check(expected.getMessage().contains("Browser native memory budget exceeded"),
                    "JNI environment initialization reported the wrong failure");
        }
        check(BrowserMemory.liveRegions() == 0,
                "failed JNI environment initialization retained a native region");
        check(BrowserMemory.liveBytes() == 0L,
                "failed JNI environment initialization retained native bytes");
        check(BrowserMemory.allocationFailures() == failuresBefore + 1L,
                "failed JNI environment initialization telemetry did not advance");
        System.out.println("browser JNI environment initialization rollback smoke passed");
    }

    private static void verifyConfiguredBudget() {
        check(BrowserMemory.maxLiveBytes() == 1024L, "configured memory budget was ignored");
        check(BrowserMemory.maxTemporaryBytes() == 128,
                "configured temporary memory budget was ignored");
        long failuresBefore = BrowserMemory.allocationFailures();
        long temporaryFailuresBefore = BrowserMemory.temporaryAllocationFailures();
        long address = BrowserMemory.allocate(768L);
        try {
            BrowserMemory.allocate(300L);
            throw new AssertionError("native memory budget accepted an over-limit allocation");
        } catch (OutOfMemoryError expected) {
            check(expected.getMessage().contains("Browser native memory budget exceeded"),
                    "native memory budget reported the wrong failure");
        }
        check(BrowserMemory.liveBytes() == 768L,
                "failed allocation changed live native bytes");
        check(BrowserMemory.allocationFailures() == failuresBefore + 1L,
                "failed allocation telemetry did not advance");
        try {
            BrowserMemory.decodeAscii(0L, 129);
            throw new AssertionError("temporary decode budget accepted an over-limit allocation");
        } catch (OutOfMemoryError expected) {
            check(expected.getMessage().contains("Browser temporary decode budget exceeded"),
                    "temporary decode budget reported the wrong failure");
        }
        check(BrowserMemory.allocationFailures() == failuresBefore + 2L,
                "temporary allocation failure did not advance total failure telemetry");
        check(BrowserMemory.temporaryAllocationFailures() == temporaryFailuresBefore + 1L,
                "temporary allocation failure telemetry did not advance");
        BrowserMemory.free(address);
        check(BrowserMemory.liveBytes() == 0L, "budget test did not release native bytes");
        System.out.println("browser memory hard-budget smoke passed");
    }

    private static void verifyHardCaps() {
        check(BrowserMemory.maxLiveBytes() == 2L * 1024L * 1024L * 1024L,
                "configured native memory budget escaped its 2 GiB hard cap");
        check(BrowserMemory.maxTemporaryBytes() == 16 * 1024 * 1024,
                "configured temporary budget escaped its 16 MiB hard cap");
        System.out.println("browser memory configured hard-cap smoke passed");
    }

    private static void verifyRepeatedAllocateDeriveFree(
            int baselineRegions, long baselineBytes, int baselineAssociations) {
        for (int iteration = 0; iteration < REPEAT_COUNT; iteration++) {
            long address = BrowserMemory.allocate(128);
            ByteBuffer root = BrowserMemory.byteBuffer(address, 128);
            root.putInt(24, 0x1020_3040 ^ iteration);

            root.position(16);
            ByteBuffer slice = BrowserMemory.slice(root, 8, 32);
            ByteBuffer duplicate = BrowserMemory.duplicate(slice);
            IntBuffer ints = (IntBuffer) BrowserMemory.wrap(
                    BrowserMemory.bufferClass(3), address + 24L, 4);

            check(BrowserMemory.address0(root) == address, "root address mismatch");
            check(BrowserMemory.address0(slice) == address + 24L, "slice address mismatch");
            check(BrowserMemory.address0(duplicate) == address + 24L,
                    "duplicate address mismatch");
            check(BrowserMemory.address0(ints) == address + 24L,
                    "typed view address mismatch");
            check(ints.get(0) == (0x1020_3040 ^ iteration), "typed view lost aliasing");

            BrowserMemory.free((Buffer) root);
            check(BrowserMemory.liveRegions() == baselineRegions,
                    "Buffer free did not release its complete region");
            check(BrowserMemory.liveBytes() == baselineBytes,
                    "Buffer free did not recover live bytes");
            check(BrowserMemory.associatedBuffers() == baselineAssociations,
                    "Buffer free left derived associations behind");
        }
    }

    private static void verifyReallocate(
            int baselineRegions, long baselineBytes, int baselineAssociations) {
        long original = BrowserMemory.allocate(32);
        BrowserMemory.putLong(original + 8L, 0x1122_3344_5566_7788L);
        ByteBuffer staleView = BrowserMemory.byteBuffer(original, 32);
        long replacement = BrowserMemory.reallocate(original, 256);

        check(BrowserMemory.getLong(replacement + 8L) == 0x1122_3344_5566_7788L,
                "reallocate did not preserve bytes");
        check(BrowserMemory.liveRegions() == baselineRegions + 1,
                "reallocate changed live region cardinality");
        check(BrowserMemory.liveBytes() == baselineBytes + 256L,
                "reallocate live-byte telemetry mismatch");
        check(BrowserMemory.associatedBuffers() == baselineAssociations,
                "reallocate retained an association to the old region");
        expectUnknown(original);
        check(staleView.capacity() == 32, "JVM unexpectedly invalidated the local Buffer object");

        BrowserMemory.free(replacement);
        check(BrowserMemory.liveRegions() == baselineRegions, "reallocate region was not freed");
        check(BrowserMemory.liveBytes() == baselineBytes, "reallocate bytes were not recovered");
    }

    private static void verifyRegister(
            int baselineRegions, long baselineBytes, int baselineAssociations) {
        ByteBuffer external = ByteBuffer.allocate(64).order(ByteOrder.nativeOrder());
        long address = BrowserMemory.register(external);
        check(BrowserMemory.register(external) == address, "register was not idempotent");
        BrowserMemory.putInt(address + 12L, 0x5566_7788);
        check(external.getInt(12) == 0x5566_7788, "registered memory lost backing aliasing");
        check(BrowserMemory.liveRegions() == baselineRegions + 1,
                "register did not create one region");
        check(BrowserMemory.liveBytes() == baselineBytes + 64L,
                "register live-byte telemetry mismatch");
        check(BrowserMemory.associatedBuffers() == baselineAssociations + 1,
                "register association telemetry mismatch");

        BrowserMemory.free(external);
        check(BrowserMemory.liveRegions() == baselineRegions, "registered region was not freed");
        check(BrowserMemory.liveBytes() == baselineBytes, "registered bytes were not recovered");
        check(BrowserMemory.associatedBuffers() == baselineAssociations,
                "registered Buffer association was not removed");
    }

    private static void verifyAutomaticBufferRegionRecovery(
            int baselineRegions,
            long baselineBytes,
            int baselineAssociations) throws Exception {
        ByteBuffer root = ByteBuffer.allocate(96).order(ByteOrder.nativeOrder());
        root.putInt(20, 0x1357_9bdf);
        int lookupMissesBefore = BrowserMemory.addressLookupMisses();
        long address = BrowserMemory.address0(root);

        check(BrowserMemory.address0(root) == address,
                "repeated address created a different automatic region");
        for (int lookup = 0; lookup < 10_000; lookup++) {
            check(BrowserMemory.address0(root) == address, "hot address cache changed address");
        }
        check(BrowserMemory.addressLookupMisses() <= lookupMissesBefore + 1,
                "hot address lookup fell back to allocating identity-map keys");
        verifyAlternatingAddressCache();
        check(BrowserMemory.liveRegions() == baselineRegions + 1,
                "automatic address did not create exactly one region");
        check(BrowserMemory.liveBytes() == baselineBytes + 96L,
                "automatic region live-byte telemetry mismatch");
        check(BrowserMemory.associatedBuffers() == baselineAssociations + 1,
                "repeated address duplicated the Buffer association");
        check(BrowserMemory.getInt(address + 20L) == 0x1357_9bdf,
                "automatic region did not copy source bytes");

        root.position(16);
        ByteBuffer retainedSlice = BrowserMemory.slice(root, 4, 32);
        check(BrowserMemory.address0(retainedSlice) == address + 20L,
                "automatic region slice address mismatch");
        root = null;
        awaitAssociationCount(baselineAssociations + 1);
        check(BrowserMemory.liveRegions() == baselineRegions + 1,
                "automatic region was released while a derived view was live");
        check(BrowserMemory.getInt(address + 20L) == 0x1357_9bdf,
                "automatic region became unreadable while a derived view was live");

        retainedSlice = null;
        awaitMemoryState(baselineRegions, baselineBytes, baselineAssociations);
        expectUnknown(address);
    }

    private static void verifyAlternatingAddressCache() {
        ByteBuffer[] buffers = new ByteBuffer[4];
        long[] addresses = new long[4];
        boolean[] slots = new boolean[16];
        int found = 0;
        while (found < buffers.length) {
            ByteBuffer candidate = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder());
            int slot = System.identityHashCode(candidate) & 15;
            if (slots[slot]) {
                continue;
            }
            slots[slot] = true;
            buffers[found] = candidate;
            addresses[found] = BrowserMemory.address0(candidate);
            found++;
        }
        int missesAfterWarmup = BrowserMemory.addressLookupMisses();
        for (int iteration = 0; iteration < 10_000; iteration++) {
            int index = iteration & 3;
            check(BrowserMemory.address0(buffers[index]) == addresses[index],
                    "alternating address cache changed address");
        }
        check(BrowserMemory.addressLookupMisses() == missesAfterWarmup,
                "alternating address cache fell back to identity-map probes");
        for (ByteBuffer buffer : buffers) {
            BrowserMemory.free(buffer);
        }
    }

    private static void verifyExplicitRegionSurvivesBufferCollection(
            int baselineRegions, long baselineBytes, int baselineAssociations) throws Exception {
        long address = BrowserMemory.allocate(80);
        ByteBuffer view = BrowserMemory.byteBuffer(address, 80);
        view.putInt(0, 0x2468_ace0);
        view = null;

        awaitAssociationCount(baselineAssociations);
        check(BrowserMemory.liveRegions() == baselineRegions + 1,
                "explicit region was incorrectly released by Buffer GC");
        check(BrowserMemory.liveBytes() == baselineBytes + 80L,
                "explicit region bytes were incorrectly released by Buffer GC");
        check(BrowserMemory.getInt(address) == 0x2468_ace0,
                "explicit region became unreadable after Buffer GC");

        BrowserMemory.free(address);
        check(BrowserMemory.liveRegions() == baselineRegions,
                "explicit region was not released by free");
        check(BrowserMemory.liveBytes() == baselineBytes,
                "explicit region bytes were not released by free");
    }

    private static void verifyInvalidReleaseProtection(
            int baselineRegions, long baselineBytes, int baselineAssociations) {
        long address = BrowserMemory.allocate(64);
        ByteBuffer root = BrowserMemory.byteBuffer(address, 64);
        root.position(8);
        ByteBuffer slice = BrowserMemory.slice(root, 4, 16);
        long freesBefore = BrowserMemory.frees();

        expectInvalidRelease(() -> BrowserMemory.free(address + 12L));
        expectInvalidRelease(() -> BrowserMemory.free((Buffer) slice));
        check(BrowserMemory.liveRegions() == baselineRegions + 1,
                "interior release removed a shared region");
        check(BrowserMemory.liveBytes() == baselineBytes + 64L,
                "interior release changed live bytes");
        check(BrowserMemory.associatedBuffers() == baselineAssociations + 2,
                "interior release changed Buffer associations");
        check(BrowserMemory.frees() == freesBefore,
                "interior release changed free telemetry");

        BrowserMemory.free(address);
        long freesAfter = BrowserMemory.frees();
        BrowserMemory.free(address);
        check(BrowserMemory.frees() == freesAfter,
                "double-free changed free telemetry");
        check(BrowserMemory.liveRegions() == baselineRegions,
                "base release did not remove the region");
        check(BrowserMemory.liveBytes() == baselineBytes,
                "base release did not recover live bytes");
        check(BrowserMemory.associatedBuffers() == baselineAssociations,
                "base release left shared associations behind");
    }

    private static void verifyWeakAssociationRecovery(
            int baselineRegions,
            long baselineBytes,
            int baselineAssociations,
            long baselineCollected) throws Exception {
        long address = BrowserMemory.allocate(128);
        ByteBuffer retained = BrowserMemory.byteBuffer(address, 128);
        createTransientViews(retained);
        check(BrowserMemory.associatedBuffers() > baselineAssociations + 1_000,
                "transient view setup did not create enough associations");

        awaitAssociationCount(baselineAssociations + 1);
        check(BrowserMemory.collectedAssociations() > baselineCollected,
                "ReferenceQueue did not report collected associations");
        check(BrowserMemory.address0(retained) == address,
                "retained Buffer lost its address while weak peers were collected");

        BrowserMemory.free(retained);
        check(BrowserMemory.liveRegions() == baselineRegions,
                "weak-association region was not freed");
        check(BrowserMemory.liveBytes() == baselineBytes,
                "weak-association bytes were not recovered");
        check(BrowserMemory.associatedBuffers() == baselineAssociations,
                "weak-association count did not return to baseline");
    }

    private static void createTransientViews(ByteBuffer retained) {
        for (int index = 0; index < TRANSIENT_VIEW_COUNT; index++) {
            retained.position(index & 31);
            ByteBuffer slice = BrowserMemory.slice(retained);
            BrowserMemory.duplicate(slice);
        }
        retained.clear();
    }

    private static void awaitAssociationCount(int expected) throws Exception {
        for (int attempt = 0; attempt < 80; attempt++) {
            System.gc();
            byte[][] pressure = new byte[8][];
            for (int index = 0; index < pressure.length; index++) {
                pressure[index] = new byte[256 * 1024];
            }
            if (BrowserMemory.associatedBuffers() <= expected) {
                return;
            }
            Thread.sleep(10L);
        }
        throw new AssertionError(
                "weak associations did not recover; live=" + BrowserMemory.associatedBuffers()
                        + " expected<=" + expected);
    }

    private static void awaitMemoryState(
            int expectedRegions, long expectedBytes, int expectedAssociations) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            System.gc();
            byte[][] pressure = new byte[8][];
            for (int index = 0; index < pressure.length; index++) {
                pressure[index] = new byte[256 * 1024];
            }
            if (BrowserMemory.liveRegions() == expectedRegions
                    && BrowserMemory.liveBytes() == expectedBytes
                    && BrowserMemory.associatedBuffers() == expectedAssociations) {
                return;
            }
            Thread.sleep(10L);
        }
        throw new AssertionError(
                "memory state did not recover; regions=" + BrowserMemory.liveRegions()
                        + " bytes=" + BrowserMemory.liveBytes()
                        + " associations=" + BrowserMemory.associatedBuffers());
    }

    private static void expectInvalidRelease(Runnable release) {
        try {
            release.run();
            throw new AssertionError("interior virtual address was accepted for release");
        } catch (IllegalArgumentException expected) {
            // Expected: only a region base address may release the complete region.
        }
    }

    private static void expectUnknown(long address) {
        try {
            BrowserMemory.getByte(address);
            throw new AssertionError("freed virtual address remained readable");
        } catch (IllegalStateException expected) {
            // Expected: reallocate releases the complete original region.
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
JAVA

javac \
    -encoding UTF-8 \
    -classpath "$MEMORY_JAR:$JOML_JAR:$TEAVM_JSO_JAR:$TEAVM_INTEROP_JAR" \
    -d "$TMP/classes" \
    "$MEMORY_SOURCE" \
    "$TMP/src/org/lwjgl/system/BrowserMemoryLifecycleSmoke.java"

java \
    -Xms32m \
    -Xmx96m \
    -classpath "$TMP/classes:$MEMORY_JAR:$JOML_JAR" \
    org.lwjgl.system.BrowserMemoryLifecycleSmoke

java \
    -Xms32m \
    -Xmx96m \
    -Dgaius.browser.memory.maxBytes=1024 \
    -Dgaius.browser.memory.maxTemporaryBytes=128 \
    -classpath "$TMP/classes:$MEMORY_JAR:$JOML_JAR" \
    org.lwjgl.system.BrowserMemoryLifecycleSmoke budget

java \
    -Xms32m \
    -Xmx96m \
    -Dgaius.browser.memory.maxBytes=9223372036854775807 \
    -Dgaius.browser.memory.maxTemporaryBytes=9223372036854775807 \
    -classpath "$TMP/classes:$MEMORY_JAR:$JOML_JAR" \
    org.lwjgl.system.BrowserMemoryLifecycleSmoke hard-cap

java \
    -Xms32m \
    -Xmx96m \
    -classpath "$TMP/classes:$MEMORY_JAR:$JOML_JAR" \
    org.lwjgl.system.BrowserMemoryLifecycleSmoke jni-env

java \
    -Xms32m \
    -Xmx96m \
    -Dgaius.browser.memory.maxBytes=32768 \
    -classpath "$TMP/classes:$MEMORY_JAR:$JOML_JAR" \
    org.lwjgl.system.BrowserMemoryLifecycleSmoke jni-init-failure
