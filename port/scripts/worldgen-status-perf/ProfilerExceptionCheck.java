package dev.gaius.wgperf;

import java.lang.reflect.InvocationTargetException;

public final class ProfilerExceptionCheck {
    private static final class FixtureLoader extends ClassLoader {
        Class<?> define(byte[] bytes) {
            return defineClass(null, bytes, 0, bytes.length);
        }
    }

    public static void main(String[] arguments) throws Exception {
        byte[] original;
        try (var input = ClassLoader.getSystemResourceAsStream(
                "dev/gaius/wgperf/ProfilerExceptionFixture.class")) {
            original = java.util.Objects.requireNonNull(input).readAllBytes();
        }
        byte[] transformed = new StatusAgent().transform(ClassLoader.getSystemClassLoader(),
                "net/minecraft/world/level/chunk/status/ChunkStatusTasks", null, null, original);
        if (transformed == null) throw new AssertionError("Fixture was not instrumented");
        Class<?> fixture = new FixtureLoader().define(transformed);
        StatusProfiler.beginChunk();
        if (!fixture.getMethod("generateFeatures").invoke(null).equals(6)) {
            throw new AssertionError("Caught exception changed the result");
        }
        try {
            fixture.getMethod("generateCarvers").invoke(null);
            throw new AssertionError("Original exception disappeared");
        } catch (InvocationTargetException failure) {
            if (!(failure.getCause() instanceof IllegalStateException)
                    || !failure.getCause().getMessage().equals("original failure")) throw failure;
        }
        StatusProfiler.endChunk();
        StatusProfiler.write(arguments[0]);
        System.out.println("Profiler implicit, explicit and caught exceptions preserve balanced frames");
    }
}
