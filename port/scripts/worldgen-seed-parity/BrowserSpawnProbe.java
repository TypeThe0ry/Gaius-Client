package dev.gaius.parity;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.LevelLoadListener;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.ServerLevelData;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Runs the browser's replacement of {@code MinecraftServer.setInitialSpawn} (the "fast initial
 * spawn" that MinecraftClientPatcher writes) on the harness level, so the seed-parity harness
 * checks the spawn the browser Worker really writes instead of the vanilla spawn search.
 *
 * <p>The patched method is static and only uses its arguments, so it is copied out of the
 * patched {@code MinecraftServer.class} (path in the {@code gaius.parity.patchedServerClass}
 * system property) into a class of its own; its {@code BrowserOpenGL.reportMinecraftEvent}
 * telemetry call is redirected to {@link #reportMinecraftEvent}. The level data and the load
 * listener are recording proxies, so the harness world itself is not changed. Compiled and
 * put on the class path only for {@code --patched} runs (it needs ASM).
 */
public final class BrowserSpawnProbe {
    static final String SET_INITIAL_SPAWN_DESC = "(Lnet/minecraft/server/level/ServerLevel;"
            + "Lnet/minecraft/world/level/storage/ServerLevelData;"
            + "ZZLnet/minecraft/server/level/progress/LevelLoadListener;)V";
    private static final String COPY = "dev/gaius/parity/PatchedInitialSpawn";
    private static final List<String> EVENTS = new ArrayList<>();

    private BrowserSpawnProbe() {
    }

    /** Stands in for BrowserOpenGL.reportMinecraftEvent in the copied method. */
    public static void reportMinecraftEvent(String event) {
        EVENTS.add(event);
    }

    /** Runs the patched setInitialSpawn and returns the spawn it wrote as a JSON object. */
    public static String run(ServerLevel level) throws Throwable {
        String file = System.getProperty("gaius.parity.patchedServerClass");
        if (file == null) {
            throw new IllegalStateException("gaius.parity.patchedServerClass is not set");
        }
        ClassNode server = new ClassNode();
        new ClassReader(Files.readAllBytes(Path.of(file))).accept(server, 0);
        MethodNode method = server.methods.stream()
                .filter(candidate -> candidate.name.equals("setInitialSpawn")
                        && candidate.desc.equals(SET_INITIAL_SPAWN_DESC))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "patched MinecraftServer has no setInitialSpawn" + SET_INITIAL_SPAWN_DESC));
        if ((method.access & Opcodes.ACC_STATIC) == 0) {
            throw new IllegalStateException("MinecraftServer.setInitialSpawn is no longer static");
        }
        boolean browserBody = false;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof LdcInsnNode ldc && "server.browserFastInitialSpawn".equals(ldc.cst)) {
                browserBody = true;
            }
            String owner = instruction instanceof MethodInsnNode call ? call.owner
                    : instruction instanceof FieldInsnNode field ? field.owner : null;
            if (server.name.equals(owner)) {
                throw new IllegalStateException("the browser setInitialSpawn uses MinecraftServer member "
                        + (instruction instanceof MethodInsnNode call ? call.name : ((FieldInsnNode) instruction).name)
                        + "; the probe cannot run it outside the server class");
            }
        }
        if (!browserBody) {
            throw new IllegalStateException("setInitialSpawn in the patched jar is not the browser fast initial"
                    + " spawn (no server.browserFastInitialSpawn event)");
        }
        method.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
        ClassWriter writer = new ClassWriter(0);
        ClassRemapper copy = new ClassRemapper(writer, new SimpleRemapper(Map.of(
                "org/lwjgl/opengl/BrowserOpenGL", "dev/gaius/parity/BrowserSpawnProbe")));
        copy.visit(server.version, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, COPY, null,
                "java/lang/Object", null);
        method.accept(copy);
        copy.visitEnd();
        Class<?> patched = MethodHandles.lookup().defineClass(writer.toByteArray());

        LevelData.RespawnData[] written = new LevelData.RespawnData[1];
        ServerLevelData data = recorder(ServerLevelData.class, (proxy, called, args) -> {
            if (called.getName().equals("setSpawn") && args != null && args.length == 1
                    && args[0] instanceof LevelData.RespawnData respawn) {
                if (written[0] != null) {
                    throw new IllegalStateException("setSpawn called twice");
                }
                written[0] = respawn;
                return null;
            }
            throw new UnsupportedOperationException("the browser setInitialSpawn called ServerLevelData."
                    + called.getName());
        });
        List<String> listener = new ArrayList<>();
        LevelLoadListener load = recorder(LevelLoadListener.class, (proxy, called, args) -> {
            StringBuilder entry = new StringBuilder(called.getName());
            if (args != null) {
                for (Object arg : args) {
                    entry.append(' ').append(arg);
                }
            }
            listener.add(entry.toString());
            return defaultValue(called.getReturnType());
        });
        EVENTS.clear();
        try {
            patched.getMethod("setInitialSpawn", ServerLevel.class, ServerLevelData.class, boolean.class,
                    boolean.class, LevelLoadListener.class).invoke(null, level, data, false, false, load);
        } catch (java.lang.reflect.InvocationTargetException exception) {
            throw exception.getCause();
        }
        if (written[0] == null) {
            throw new IllegalStateException("the browser setInitialSpawn did not set a world spawn");
        }
        if (!written[0].dimension().equals(level.dimension())) {
            throw new IllegalStateException("the browser spawn is in " + written[0].dimension()
                    + ", not in " + level.dimension());
        }
        BlockPos pos = written[0].pos();
        StringBuilder json = new StringBuilder();
        json.append(String.format(Locale.ROOT,
                "{\"x\": %d, \"y\": %d, \"z\": %d, \"chunkX\": %d, \"chunkZ\": %d, \"levelMaxY\": %d, ",
                pos.getX(), pos.getY(), pos.getZ(), pos.getX() >> 4, pos.getZ() >> 4, level.getMaxY()));
        json.append("\"events\": ").append(quoteAll(EVENTS)).append(", ");
        json.append("\"listener\": ").append(quoteAll(listener)).append('}');
        return json.toString();
    }

    @SuppressWarnings("unchecked")
    private static <T> T recorder(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, called, args) -> {
            if (called.getDeclaringClass() == Object.class) {
                return switch (called.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> type.getSimpleName() + " recorder";
                };
            }
            return handler.invoke(proxy, called, args);
        });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0f;
        }
        if (type == double.class) {
            return 0.0;
        }
        if (type == char.class) {
            return (char) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        return 0;
    }

    private static String quoteAll(List<String> values) {
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                json.append(", ");
            }
            json.append('"').append(values.get(index).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return json.append(']').toString();
    }
}
