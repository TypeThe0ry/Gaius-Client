package dev.gaius.browser;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.teavm.jso.JSBody;

/** Recomputes targeting after the camera has been updated for the frame being rendered. */
public final class BrowserTargeting {
    private BrowserTargeting() {
    }

    /**
     * Consumes the vanilla per-frame pick call so the one raycast for this frame can run after
     * the render camera has been extracted. Tick-time picks are left unchanged.
     */
    public static void deferFramePick(Minecraft minecraft, float partialTick) {
        recordDeferredPick(partialTick);
    }

    /** Refreshes both block and entity targeting from the camera used by this rendered frame. */
    public static void refreshFramePick(
            Minecraft minecraft,
            Camera camera,
            float partialTick) {
        if (minecraft == null) {
            recordTargetingResult(false, partialTick, null, camera);
            return;
        }
        HitResult updated = stabilizeBlockHit(
                minecraft.hitResult,
                minecraft,
                camera,
                partialTick);
        minecraft.hitResult = updated;
        minecraft.crosshairPickEntity = updated instanceof EntityHitResult entityHit
                ? entityHit.getEntity()
                : null;
    }

    public static HitResult stabilizeBlockHit(
            HitResult current,
            Minecraft minecraft,
            Camera camera,
            float partialTick) {
        if (minecraft == null
                || minecraft.level == null
                || minecraft.player == null
                || camera == null
                || !camera.isInitialized()
                || camera.entity() == null) {
            recordTargetingResult(false, partialTick, current, camera);
            return current;
        }
        Vec3 cameraPosition = camera.position();
        HitResult updated = pickFromRenderCamera(minecraft, camera, cameraPosition);
        recordTargetingResult(true, partialTick, updated, camera);
        return updated;
    }

    private static HitResult pickFromRenderCamera(
            Minecraft minecraft,
            Camera camera,
            Vec3 cameraPosition) {
        double blockRange = minecraft.player.blockInteractionRange();
        double entityRange = minecraft.player.entityInteractionRange();
        double pickRange = Math.max(blockRange, entityRange);
        Vec3 forward = new Vec3(camera.forwardVector()).normalize();
        Vec3 end = cameraPosition.add(forward.scale(pickRange));
        BlockHitResult block = minecraft.level.clip(new ClipContext(
                cameraPosition,
                end,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                camera.entity()));
        double blockDistanceSquared = block.getType() == HitResult.Type.MISS
                ? pickRange * pickRange
                : cameraPosition.distanceToSqr(block.getLocation());
        AABB searchBounds = new AABB(cameraPosition, end).inflate(1.0D);
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
                camera.entity(),
                cameraPosition,
                end,
                searchBounds,
                EntitySelector.CAN_BE_PICKED,
                blockDistanceSquared);
        if (entityHit != null
                && cameraPosition.distanceToSqr(entityHit.getLocation()) < blockDistanceSquared
                && cameraPosition.distanceToSqr(entityHit.getLocation()) < entityRange * entityRange) {
            return entityHit;
        }
        if (block.getType() != HitResult.Type.MISS
                && blockDistanceSquared < blockRange * blockRange) {
            return block;
        }
        return BlockHitResult.miss(
                end,
                Direction.getApproximateNearest(forward.x, forward.y, forward.z),
                BlockPos.containing(end));
    }

    /** Records the current-version vanilla pick without performing another raycast. */
    public static void observeVanillaPick(
            HitResult current,
            Camera camera,
            float partialTick) {
        recordTargetingResult(true, partialTick, current, camera);
    }

    private static void recordTargetingResult(
            boolean updated,
            float partialTick,
            HitResult result,
            Camera camera) {
        if (!targetingTelemetryEnabled()) {
            return;
        }
        int type = result == null ? -1 : result.getType().ordinal();
        int blockX = Integer.MIN_VALUE;
        int blockY = Integer.MIN_VALUE;
        int blockZ = Integer.MIN_VALUE;
        if (result instanceof BlockHitResult blockHit) {
            BlockPos block = blockHit.getBlockPos();
            blockX = block.getX();
            blockY = block.getY();
            blockZ = block.getZ();
        }
        Vec3 cameraPosition = camera != null && camera.isInitialized()
                ? camera.position()
                : Vec3.ZERO;
        double cameraOriginError = 0.0;
        double cameraDirectionError = 0.0;
        if (camera != null && camera.isInitialized() && camera.entity() != null) {
            cameraOriginError = Math.sqrt(cameraPosition.distanceToSqr(
                    camera.entity().getEyePosition(partialTick)));
            if (result != null) {
                Vec3 cameraForward = new Vec3(camera.forwardVector()).normalize();
                Vec3 hitDirection = result.getLocation().subtract(cameraPosition).normalize();
                double dot = Math.max(-1.0D, Math.min(1.0D, cameraForward.dot(hitDirection)));
                cameraDirectionError = Math.acos(dot);
            }
        }
        recordTargetingFrame(
                updated,
                partialTick,
                type,
                blockX,
                blockY,
                blockZ,
                cameraPosition.x,
                cameraPosition.y,
                cameraPosition.z,
                cameraOriginError,
                cameraDirectionError);
    }

    @JSBody(script = "return !!globalThis.__gaiusBenchmarkEnabled;")
    private static native boolean targetingTelemetryEnabled();

    @JSBody(params = "partialTick", script = """
            if (!globalThis.__gaiusBenchmarkEnabled) return;
            const state=globalThis.__gaiusTargetingTelemetry ||
              (globalThis.__gaiusTargetingTelemetry={updates:0,skips:0});
            state.deferredPicks=(Number(state.deferredPicks)||0)+1;
            state.deferredPartialTick=Number(partialTick);
            """)
    private static native void recordDeferredPick(float partialTick);

    @JSBody(params = {
            "updated",
            "partialTick",
            "type",
            "blockX",
            "blockY",
            "blockZ",
            "cameraX",
            "cameraY",
            "cameraZ",
            "cameraOriginError",
            "cameraDirectionError"
    }, script = """
            if (!globalThis.__gaiusBenchmarkEnabled) return;
            const state=globalThis.__gaiusTargetingTelemetry ||
              (globalThis.__gaiusTargetingTelemetry={updates:0,skips:0});
            if (updated) state.updates=(state.updates||0)+1;
            else state.skips=(state.skips||0)+1;
            const frameTelemetry=globalThis.__gaiusFrameTelemetry || {};
            const renderedFrameValue=Number.isFinite(Number(frameTelemetry.visibleFrameCount))
              ? Number(frameTelemetry.visibleFrameCount)
              : Number(frameTelemetry.frameCount);
            const renderedFrame=Math.max(0,Math.floor(
              Number(renderedFrameValue) || 0));
            const previousFrame=Number(state.lastObservationFrame);
            if (Number.isFinite(previousFrame)) {
              state.maxObservationLagFrames=Math.max(
                Number(state.maxObservationLagFrames)||0,
                Math.max(0,renderedFrame-previousFrame));
            } else {
              state.maxObservationLagFrames=0;
            }
            if (Number(state.observationFrame)===renderedFrame) {
              state.observationsThisFrame=(Number(state.observationsThisFrame)||0)+1;
            } else {
              state.observationFrame=renderedFrame;
              state.observationsThisFrame=1;
            }
            state.maxObservationsPerRenderedFrame=Math.max(
              Number(state.maxObservationsPerRenderedFrame)||0,
              state.observationsThisFrame);
            state.lastObservationFrame=renderedFrame;
            state.partialTick=Number(partialTick);
            state.type=Number(type);
            state.blockX=Number(blockX);
            state.blockY=Number(blockY);
            state.blockZ=Number(blockZ);
            state.cameraX=Number(cameraX);
            state.cameraY=Number(cameraY);
            state.cameraZ=Number(cameraZ);
            state.cameraOriginError=Math.max(0,Number(cameraOriginError)||0);
            state.maxCameraOriginError=Math.max(
              Number(state.maxCameraOriginError)||0,
              state.cameraOriginError);
            state.cameraDirectionError=Math.max(0,Number(cameraDirectionError)||0);
            state.maxCameraDirectionError=Math.max(
              Number(state.maxCameraDirectionError)||0,
              state.cameraDirectionError);
            let ring=state.ring;
            if (!ring || !(ring.blockX instanceof Int32Array)) {
              ring={
                blockX:new Int32Array(256),
                blockY:new Int32Array(256),
                blockZ:new Int32Array(256),
                type:new Int8Array(256),
                partialTick:new Float32Array(256),
                cameraX:new Float64Array(256),
                cameraY:new Float64Array(256),
                cameraZ:new Float64Array(256),
                cameraOriginError:new Float32Array(256),
                cameraDirectionError:new Float32Array(256),
                writeIndex:0,
                count:0
              };
              state.ring=ring;
            }
            const index=(Number(ring.writeIndex)||0)&255;
            ring.blockX[index]=Number(blockX);
            ring.blockY[index]=Number(blockY);
            ring.blockZ[index]=Number(blockZ);
            ring.type[index]=Number(type);
            ring.partialTick[index]=Number(partialTick);
            ring.cameraX[index]=Number(cameraX);
            ring.cameraY[index]=Number(cameraY);
            ring.cameraZ[index]=Number(cameraZ);
            ring.cameraOriginError[index]=state.cameraOriginError;
            ring.cameraDirectionError[index]=state.cameraDirectionError;
            ring.writeIndex=(index+1)&255;
            ring.count=Math.min(256,(Number(ring.count)||0)+1);
            state.lastAt=Date.now();
            """)
    private static native void recordTargetingFrame(
            boolean updated,
            float partialTick,
            int type,
            int blockX,
            int blockY,
            int blockZ,
            double cameraX,
            double cameraY,
            double cameraZ,
            double cameraOriginError,
            double cameraDirectionError);
}
