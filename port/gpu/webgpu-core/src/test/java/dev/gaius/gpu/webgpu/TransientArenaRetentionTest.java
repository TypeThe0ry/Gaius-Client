package dev.gaius.gpu.webgpu;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.List;

/** JVM-only checks for bounded per-submit CPU scratch retention. */
public final class TransientArenaRetentionTest {
    public static void main(String[] args) throws Exception {
        TransientArena arena = new TransientArena(null);
        for (int i = 0; i < 16; i++) {
            arena.allocateCpu(TransientArena.BLOCK_SIZE, 4, TransientArena.BLOCK_SIZE, 1);
        }
        check(cpuBlocks(arena).size() == 16, "burst allocates CPU scratch blocks on demand");

        arena.rotate();
        check(cpuBlocks(arena).size() == 8, "submit trims CPU scratch to the retained limit");

        for (int i = 0; i < 8; i++) {
            arena.allocateCpu(TransientArena.BLOCK_SIZE, 4, TransientArena.BLOCK_SIZE, 1);
        }
        check(cpuBlocks(arena).size() == 8, "the retained CPU scratch blocks are reusable");

        TransientArena largeScratchArena = new TransientArena(null);
        largeScratchArena.allocateCpu(TransientArena.BLOCK_SIZE + 4L, 4, TransientArena.BLOCK_SIZE + 4L, 1);
        check(temporaryCpuBlocks(largeScratchArena).size() == 1, "large scratch is tracked until submit");
        largeScratchArena.rotate();
        check(temporaryCpuBlocks(largeScratchArena).isEmpty(), "large scratch is released after submit");

        TransientArena gpuArena = new TransientArena(null);
        ByteBuffer data = ByteBuffer.allocateDirect(16);
        data.putInt(0x12345678).flip();
        gpuArena.stage(data, 4);
        Object uploaded = usedBlocks(gpuArena).get(0);
        check(blockCpu(uploaded) != null, "staged bytes have a CPU copy before upload");
        gpuArena.flush();
        check(blockCpu(uploaded) == null, "the CPU copy is released after upload queues its bytes");
        check(uploadCpuBlocks(gpuArena).size() == 1, "released CPU storage enters the reuse pool");

        ByteBuffer fullPage = ByteBuffer.allocateDirect(TransientArena.BLOCK_SIZE);
        for (int i = 0; i < 24; i++) {
            fullPage.clear();
            gpuArena.stage(fullPage, 4);
        }
        gpuArena.flush();
        check(uploadCpuBlocks(gpuArena).size() == 16, "upload CPU reuse pool remains bounded after a burst");
        for (Object block : usedBlocks(gpuArena)) {
            check(blockCpu(block) == null, "uploaded GPU blocks release their CPU storage");
        }

        for (int i = 0; i < 24; i++) {
            gpuArena.allocateGpu(TransientArena.BLOCK_SIZE, 4, TransientArena.BLOCK_SIZE, 1);
        }
        gpuArena.rotate();
        check(freeBlocks(gpuArena).size() == 16, "submit trims regular GPU blocks to the retained limit");
        for (Object block : freeBlocks(gpuArena)) {
            check(blockCpu(block) == null, "idle GPU blocks do not retain CPU upload copies");
        }
        System.out.println("transient arena retention passed");
    }

    @SuppressWarnings("unchecked")
    private static List<ByteBuffer> cpuBlocks(TransientArena arena) throws Exception {
        Field field = TransientArena.class.getDeclaredField("cpuBlocks");
        field.setAccessible(true);
        return (List<ByteBuffer>) field.get(arena);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> freeBlocks(TransientArena arena) throws Exception {
        Field field = TransientArena.class.getDeclaredField("freeBlocks");
        field.setAccessible(true);
        return (List<Object>) field.get(arena);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> usedBlocks(TransientArena arena) throws Exception {
        Field field = TransientArena.class.getDeclaredField("usedBlocks");
        field.setAccessible(true);
        return (List<Object>) field.get(arena);
    }

    @SuppressWarnings("unchecked")
    private static List<ByteBuffer> uploadCpuBlocks(TransientArena arena) throws Exception {
        Field field = TransientArena.class.getDeclaredField("freeUploadCpuBlocks");
        field.setAccessible(true);
        return (List<ByteBuffer>) field.get(arena);
    }

    @SuppressWarnings("unchecked")
    private static List<ByteBuffer> temporaryCpuBlocks(TransientArena arena) throws Exception {
        Field field = TransientArena.class.getDeclaredField("temporaryCpuBlocks");
        field.setAccessible(true);
        return (List<ByteBuffer>) field.get(arena);
    }

    private static ByteBuffer blockCpu(Object block) throws Exception {
        Field field = block.getClass().getDeclaredField("cpu");
        field.setAccessible(true);
        return (ByteBuffer) field.get(block);
    }

    private static void check(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
