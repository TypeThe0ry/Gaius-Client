package dev.gaius.browser.kernel.light;

import net.minecraft.world.level.ChunkPos;

/**
 * Added to {@code ThreadedLevelLightEngine} by
 * {@code dev.gaius.tools.patches.light.LightKernelPatches}: the private pieces of the vanilla
 * engine that the light kernel path needs. Cast the engine through {@code Object} to reach them.
 */
public interface BrowserLightEngineHooks {
    /** {@code ThreadedLevelLightEngine$TaskType} ordinals for {@link #gaius$addLightTask}. */
    int PRE_UPDATE = 0;
    int POST_UPDATE = 1;

    /** {@code addTask(x, z, TaskType.values()[taskType], task)}. */
    void gaius$addLightTask(int chunkX, int chunkZ, int taskType, Runnable task);

    /** {@code super.propagateLightSources(pos)}: the vanilla first light of a chunk. */
    void gaius$propagateLightSourcesVanilla(ChunkPos pos);
}
