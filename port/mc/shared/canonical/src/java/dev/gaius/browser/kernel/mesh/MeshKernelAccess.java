package dev.gaius.browser.kernel.mesh;

import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCopy;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * Accessors that {@code dev.gaius.tools.patches.mesh.MeshKernelPatches} adds to vanilla
 * classes for the mesh kernel (one canonical copy for every modern profile). Each nested
 * interface is implemented by exactly one patched class; the methods only read private state,
 * except {@link Task}'s, which also keep the kernel's per-task state and requeue the task.
 */
public final class MeshKernelAccess {
    private MeshKernelAccess() {
    }

    /** SectionRenderDispatcher.RenderSection.CompileTask. */
    public interface Task {
        /** The kernel's state for this task (null until the task first ran). */
        Object gaius$meshKernelState();

        void gaius$setMeshKernelState(Object state);

        /** {@code isCancelled.get()}. */
        boolean gaius$meshKernelCancelled();

        /** Install-queue revision at task creation, before its first compile run. */
        int gaius$meshSnapshotSequence();

        /** {@code isCompleted.set(false)}, then {@code SectionRenderDispatcher.schedule(this)}. */
        void gaius$requeueForMeshKernel();

        RenderSectionRegion gaius$meshRegion();

        SectionRenderDispatcher.RenderSection gaius$meshSection();
    }

    /** SectionCompiler. */
    public interface Compiler {
        boolean gaius$ambientOcclusion();

        boolean gaius$cutoutLeaves();

        BlockStateModelSet gaius$blockModelSet();

        FluidStateModelSet gaius$fluidModelSet();

        BlockColors gaius$blockColors();
    }

    /** RenderSectionRegion. */
    public interface Region {
        SectionCopy[] gaius$sections();

        int gaius$minSectionX();

        int gaius$minSectionY();

        int gaius$minSectionZ();

        ClientLevel gaius$level();
    }

    /** SectionCopy. */
    public interface Copy {
        /** The copied block states, null for an empty or missing section. */
        PalettedContainer<BlockState> gaius$states();

        boolean gaius$debug();
    }

    /** PalettedContainer: the storage and palette of its current data. */
    public interface Paletted {
        BitStorage gaius$storage();

        Palette<?> gaius$palette();
    }
}
