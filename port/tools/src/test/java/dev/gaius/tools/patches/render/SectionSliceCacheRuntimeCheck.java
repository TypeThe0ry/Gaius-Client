package dev.gaius.tools.patches.render;

import com.mojang.blaze3d.vertex.TlsfAllocator;
import com.mojang.blaze3d.vertex.UberGpuBuffer;
import dev.gaius.browser.render.BrowserSectionSliceCache;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SplittableRandom;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;

/**
 * Runs inside a loader whose UberGpuBuffer, CompiledSectionMesh and LevelRenderer are the patched
 * ones: random allocation changes (the patched removeAllocation and close, and map puts followed by
 * the same invalidate() the patched upload path calls) interleaved with lookups, each memoized
 * lookup compared with the vanilla getRenderSectionSlice. A final unhooked put proves the
 * comparison sees a stale memo.
 */
public final class SectionSliceCacheRuntimeCheck {
    private static final sun.misc.Unsafe UNSAFE = unsafe();

    private SectionSliceCacheRuntimeCheck() {
    }

    public static void main(String[] args) throws Throwable {
        ChunkSectionLayer[] layers = ChunkSectionLayer.values();
        Method lookup = CompiledSectionMesh.class.getMethod("gaius$renderSectionSlice",
                SectionRenderDispatcher.class, SectionMesh.class, ChunkSectionLayer.class);
        Field memo = CompiledSectionMesh.class.getDeclaredField("gaius$sliceCache");

        List<UberGpuBuffer<SectionMesh>> ubers = new ArrayList<>();
        SectionRenderDispatcher first = dispatcher(layers, ubers);
        SectionRenderDispatcher second = dispatcher(layers, ubers);
        SectionRenderDispatcher[] dispatchers = {first, second};

        List<SectionMesh> meshes = new ArrayList<>();
        for (int index = 0; index < 24; index++) {
            CompiledSectionMesh mesh = (CompiledSectionMesh) UNSAFE.allocateInstance(CompiledSectionMesh.class);
            put(mesh, memo, new BrowserSectionSliceCache.Entry());
            meshes.add(mesh);
        }
        meshes.add(CompiledSectionMesh.UNCOMPILED);
        meshes.add(CompiledSectionMesh.EMPTY);

        Object[] heaps = new Object[4];
        for (int index = 0; index < heaps.length; index++) {
            heaps[index] = heap();
        }
        Constructor<TlsfAllocator.Allocation> allocationConstructor =
                TlsfAllocator.Allocation.class.getDeclaredConstructor(
                        Class.forName("com.mojang.blaze3d.vertex.TlsfAllocator$Block"), long.class);
        allocationConstructor.setAccessible(true);
        Class<?> blockClass = Class.forName("com.mojang.blaze3d.vertex.TlsfAllocator$Block");

        SplittableRandom random = new SplittableRandom(0x5EC7105L);
        long queries = 0;
        long nonNull = 0;
        for (int step = 0; step < 400_000; step++) {
            int operation = random.nextInt(100);
            UberGpuBuffer<SectionMesh> uber = ubers.get(random.nextInt(ubers.size()));
            SectionMesh mesh = meshes.get(random.nextInt(meshes.size()));
            if (operation < 8) {
                Object block = UNSAFE.allocateInstance(blockClass);
                put(block, blockClass.getDeclaredField("heap"), heaps[random.nextInt(heaps.length)]);
                TlsfAllocator.Allocation allocation =
                        allocationConstructor.newInstance(block, 32L * random.nextInt(1 << 16));
                allocationMap(uber).put(mesh, allocation);
                BrowserSectionSliceCache.invalidate(); // what the patched upload path runs after its put
            } else if (operation < 14) {
                uber.removeAllocation(mesh);
            } else if (operation == 14 && random.nextInt(50) == 0) {
                uber.close();
            } else {
                SectionRenderDispatcher dispatcher = dispatchers[random.nextInt(dispatchers.length)];
                ChunkSectionLayer layer = layers[random.nextInt(layers.length)];
                SectionRenderDispatcher.RenderSectionBufferSlice cached =
                        (SectionRenderDispatcher.RenderSectionBufferSlice) lookup.invoke(null, dispatcher, mesh, layer);
                SectionRenderDispatcher.RenderSectionBufferSlice direct = dispatcher.getRenderSectionSlice(mesh, layer);
                if (!Objects.equals(cached, direct)) {
                    throw new AssertionError("step " + step + ": memoized " + cached + " != vanilla " + direct);
                }
                queries++;
                if (direct != null) {
                    nonNull++;
                }
            }
        }
        if (nonNull < queries / 10) {
            throw new AssertionError("too few non-null lookups to be meaningful: " + nonNull + "/" + queries);
        }

        // Sensitivity: a mutation that bypasses invalidate() must be visible to the comparison.
        CompiledSectionMesh probe = (CompiledSectionMesh) meshes.get(0);
        UberGpuBuffer<SectionMesh> vertex = ubers.get(0);
        Object block = UNSAFE.allocateInstance(blockClass);
        put(block, blockClass.getDeclaredField("heap"), heaps[0]);
        allocationMap(vertex).put(probe, allocationConstructor.newInstance(block, 64L));
        BrowserSectionSliceCache.invalidate();
        Object memoized = lookup.invoke(null, first, probe, layers[0]);
        Object fresh = first.getRenderSectionSlice(probe, layers[0]);
        if (!Objects.equals(memoized, fresh) || fresh == null) {
            throw new AssertionError("probe lookup disagrees before the unhooked change");
        }
        allocationMap(vertex).put(probe, allocationConstructor.newInstance(block, 96L)); // no invalidate()
        if (Objects.equals(lookup.invoke(null, first, probe, layers[0]), first.getRenderSectionSlice(probe, layers[0]))) {
            throw new AssertionError("the comparison cannot see a stale memo");
        }
        System.out.println("SECTION_SLICE_CACHE_RUNTIME_PASS queries=" + queries + " nonNull=" + nonNull
                + " layers=" + layers.length);
    }

    @SuppressWarnings("unchecked")
    private static SectionRenderDispatcher dispatcher(ChunkSectionLayer[] layers, List<UberGpuBuffer<SectionMesh>> all)
            throws Throwable {
        SectionRenderDispatcher dispatcher = (SectionRenderDispatcher) UNSAFE.allocateInstance(SectionRenderDispatcher.class);
        Class<?> buffers = Class.forName("net.minecraft.client.renderer.chunk.SectionRenderDispatcher$SectionUberBuffers");
        Constructor<?> constructor = buffers.getDeclaredConstructor(UberGpuBuffer.class, UberGpuBuffer.class);
        constructor.setAccessible(true);
        Map<ChunkSectionLayer, Object> map = new EnumMap<>(ChunkSectionLayer.class);
        for (ChunkSectionLayer layer : layers) {
            UberGpuBuffer<SectionMesh> vertex = uber();
            UberGpuBuffer<SectionMesh> index = uber();
            all.add(vertex);
            all.add(index);
            map.put(layer, constructor.newInstance(vertex, index));
        }
        put(dispatcher, SectionRenderDispatcher.class.getDeclaredField("chunkUberBuffers"), map);
        return dispatcher;
    }

    @SuppressWarnings("unchecked")
    private static UberGpuBuffer<SectionMesh> uber() throws Throwable {
        UberGpuBuffer<SectionMesh> uber = (UberGpuBuffer<SectionMesh>) UNSAFE.allocateInstance(UberGpuBuffer.class);
        put(uber, UberGpuBuffer.class.getDeclaredField("allocationMap"), new HashMap<>());
        put(uber, UberGpuBuffer.class.getDeclaredField("nodes"), new ArrayList<>());
        put(uber, UberGpuBuffer.class.getDeclaredField("stagedAllocations"), new Object2ObjectOpenHashMap<>());
        put(uber, UberGpuBuffer.class.getDeclaredField("skippedStagedAllocations"), new ObjectOpenHashSet<>());
        return uber;
    }

    private static Object heap() throws Throwable {
        Class<?> heapClass = Class.forName("com.mojang.blaze3d.vertex.UberGpuBuffer$UberGpuBufferHeap");
        Object heap = UNSAFE.allocateInstance(heapClass);
        Field field = heapClass.getDeclaredField("gpuBuffer");
        Object buffer = field.getType().isInterface() ? Proxy.newProxyInstance(field.getType().getClassLoader(), new Class<?>[]{field.getType()},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "equals" -> proxy == arguments[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "GpuBuffer@" + Integer.toHexString(System.identityHashCode(proxy));
                    default -> throw new UnsupportedOperationException(method.getName());
                })
                : UNSAFE.allocateInstance(Class.forName("dev.gaius.tools.patches.render.FakeGpuBuffer"));
        put(heap, field, buffer);
        return heap;
    }

    @SuppressWarnings("unchecked")
    private static Map<SectionMesh, TlsfAllocator.Allocation> allocationMap(UberGpuBuffer<SectionMesh> uber)
            throws Throwable {
        Field field = UberGpuBuffer.class.getDeclaredField("allocationMap");
        return (Map<SectionMesh, TlsfAllocator.Allocation>) UNSAFE.getObject(uber, UNSAFE.objectFieldOffset(field));
    }

    @SuppressWarnings("deprecation")
    private static void put(Object target, Field field, Object value) {
        UNSAFE.putObject(target, UNSAFE.objectFieldOffset(field), value);
    }

    private static sun.misc.Unsafe unsafe() {
        try {
            Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (sun.misc.Unsafe) field.get(null);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
