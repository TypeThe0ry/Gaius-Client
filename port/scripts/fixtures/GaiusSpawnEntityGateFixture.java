import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Runs the patched PrepareSpawnTask$Preparing.gaius$spawnEntitiesLoaded on the JVM.
 *
 * <p>The classpath holds only the patched Preparing and ServerLevel classes in front of the
 * vanilla client jar, plus a stub dev.gaius.browser.BrowserIntegratedServerMain whose
 * isWorkerServer() is the static field {@code worker}. No level ever ticks here, which is the
 * state of a vanilla IntegratedServer during the host's configuration: it runs only
 * tickConnection while its player list is empty.</p>
 */
public final class GaiusSpawnEntityGateFixture {
    private static Object unsafe;
    private static Method allocateInstance;

    private GaiusSpawnEntityGateFixture() {
    }

    public static void main(String[] args) throws Throwable {
        ClassLoader loader = GaiusSpawnEntityGateFixture.class.getClassLoader();
        Class.forName("net.minecraft.SharedConstants").getMethod("tryDetectVersion").invoke(null);
        Class.forName("net.minecraft.server.Bootstrap").getMethod("bootStrap").invoke(null);

        Class<?> worker = Class.forName("dev.gaius.browser.BrowserIntegratedServerMain");
        Class<?> levelClass =
                Class.forName("net.minecraft.server.level.ServerLevel", false, loader);
        Class<?> vec3 = Class.forName("net.minecraft.world.phys.Vec3");
        Class<?> chunkPosClass = Class.forName("net.minecraft.world.level.ChunkPos");
        Method gate = Class.forName(
                        "net.minecraft.server.network.config.PrepareSpawnTask$Preparing")
                .getDeclaredMethod("gaius$spawnEntitiesLoaded", levelClass, vec3);
        gate.setAccessible(true);
        // (40.5, 70, -7.5) lies in chunk (2, -1).
        Object spawn = vec3.getConstructor(double.class, double.class, double.class)
                .newInstance(40.5D, 70.0D, -7.5D);
        Object spawnChunk = chunkPosClass.getConstructor(int.class, int.class).newInstance(2, -1);
        Method packMethod;
        try {
            packMethod = chunkPosClass.getMethod("pack");
        } catch (NoSuchMethodException legacy) {
            packMethod = chunkPosClass.getMethod("toLong");
        }
        long spawnKey = (long) packMethod.invoke(spawnChunk);

        // Any server but the Worker passes at once, without touching the level, so Preparing
        // turns Ready and Ready.spawn's waitForEntities keeps the entity wait.
        worker.getField("worker").setBoolean(null, false);
        check(Boolean.TRUE.equals(gate.invoke(null, null, spawn)),
                "a non-Worker server must pass the spawn entity gate at once");
        check("".equals(worker.getField("loaded").get(null))
                        && "".equals(worker.getField("waiting").get(null)),
                "a non-Worker server must not report spawn entity telemetry");
        System.out.println("SPAWN_ENTITY_GATE_NON_WORKER_PASSES");

        Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        unsafe = unsafeField.get(null);
        allocateInstance = unsafe.getClass().getMethod("allocateInstance", Class.class);

        Class<?> managerClass =
                Class.forName("net.minecraft.world.level.entity.PersistentEntitySectionManager");
        Object manager = allocate(managerClass);
        Queue<Object> inbox = new ConcurrentLinkedQueue<>();
        set(managerClass, manager, "loadingInbox", inbox);
        set(managerClass, manager, "chunkLoadStatuses",
                Class.forName("it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap")
                        .getConstructor().newInstance());
        Class<?> chunkCacheClass = Class.forName("net.minecraft.server.level.ServerChunkCache");
        Object chunkCache = allocate(chunkCacheClass);
        Class<?> ticketStorageClass = Class.forName("net.minecraft.world.level.TicketStorage");
        Object tickets = ticketStorageClass.getConstructor().newInstance();
        set(chunkCacheClass, chunkCache, "ticketStorage", tickets);
        Object level = allocate(levelClass);
        set(levelClass, level, "entityManager", manager);
        set(levelClass, level, "chunkSource", chunkCache);

        // Worker, entity read still in flight: wait and keep the radius-1 spawn ticket alive.
        worker.getField("worker").setBoolean(null, true);
        check(Boolean.FALSE.equals(gate.invoke(null, level, spawn)),
                "the Worker gate must wait while the spawn chunk's entities are unread");
        List<?> spawnTickets = (List<?>) ticketStorageClass.getMethod("getTickets", long.class)
                .invoke(tickets, spawnKey);
        Object playerSpawn = Class.forName("net.minecraft.server.level.TicketType")
                .getField("PLAYER_SPAWN").get(null);
        check(spawnTickets.size() == 1
                        && spawnTickets.get(0).getClass().getMethod("getType")
                                .invoke(spawnTickets.get(0)) == playerSpawn
                        // ChunkLevel FULL (33) minus radius 1, as Ready.keepAlive adds it.
                        && (int) spawnTickets.get(0).getClass().getMethod("getTicketLevel")
                                .invoke(spawnTickets.get(0)) == 32,
                "a waiting gate must refresh the radius-1 PLAYER_SPAWN ticket: " + spawnTickets);
        check("2,-1;".equals(worker.getField("waiting").get(null))
                        && "".equals(worker.getField("loaded").get(null)),
                "a waiting gate must count the wait for the spawn chunk only");
        System.out.println("SPAWN_ENTITY_GATE_WAITS_WITH_TICKET");

        // The read completes and lands in loadingInbox. No level tick drains it, so the gate
        // itself must, as vanilla waitForEntities' predicate does.
        inbox.add(Class.forName("net.minecraft.world.level.entity.ChunkEntities")
                .getConstructor(chunkPosClass, List.class)
                .newInstance(spawnChunk, List.of()));
        check(Boolean.TRUE.equals(gate.invoke(null, level, spawn)),
                "the Worker gate must drain pending entity loads without a level tick");
        check(inbox.isEmpty(), "the Worker gate must process the pending entity load");
        check("2,-1;".equals(worker.getField("loaded").get(null)),
                "a passing gate must report the loaded spawn chunk once");
        System.out.println("SPAWN_ENTITY_GATE_DRAINS_PENDING_LOADS");

        checkSpawnFixup(spawn);
    }

    /**
     * Preparing.tick once the spawn chunk and its entities are in: a returning player's saved
     * position reaches Ready unchanged, as in vanilla; only a spawn that came from the
     * (shortened) PlayerSpawnFinder search goes through PlayerSpawnFinder.gaius$fixupLoadedSpawn.
     * Runs as a non-Worker server, whose entity gate passes at once.
     */
    private static void checkSpawnFixup(Object spawn) throws Throwable {
        Class<?> worker = Class.forName("dev.gaius.browser.BrowserIntegratedServerMain");
        worker.getField("worker").setBoolean(null, false);
        Class<?> taskClass = Class.forName("net.minecraft.server.network.config.PrepareSpawnTask");
        Class<?> preparingClass =
                Class.forName("net.minecraft.server.network.config.PrepareSpawnTask$Preparing");
        Class<?> readyClass =
                Class.forName("net.minecraft.server.network.config.PrepareSpawnTask$Ready");
        Class<?> listenerClass =
                Class.forName("net.minecraft.server.level.progress.LevelLoadListener");
        Object listener = java.lang.reflect.Proxy.newProxyInstance(
                listenerClass.getClassLoader(), new Class<?>[] {listenerClass}, (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == arguments[0];
                            default -> "LevelLoadListener";
                        };
                    }
                    return null;
                });
        Object task = allocate(taskClass);
        set(taskClass, task, "loadListener", listener);
        Method tick = preparingClass.getDeclaredMethod("tick");
        tick.setAccessible(true);
        for (boolean searched : new boolean[] {false, true}) {
            Object preparing = allocate(preparingClass);
            set(preparingClass, preparing, "this$0", task);
            set(preparingClass, preparing, "spawnPosition",
                    java.util.concurrent.CompletableFuture.completedFuture(spawn));
            set(preparingClass, preparing, "chunkLoadFuture",
                    java.util.concurrent.CompletableFuture.completedFuture(null));
            set(preparingClass, preparing, "chunkLoadCounter",
                    Class.forName("net.minecraft.server.level.ChunkLoadCounter").getConstructor().newInstance());
            set(preparingClass, preparing, "spawnAngle", Class.forName("net.minecraft.world.phys.Vec2")
                    .getConstructor(float.class, float.class).newInstance(0.0F, 0.0F));
            set(preparingClass, preparing, "gaius$spawnSearched", searched);
            Object ready;
            try {
                ready = tick.invoke(preparing);
            } catch (InvocationTargetException failure) {
                // No level here: a fixup that runs fails inside it, which shows it ran.
                boolean inFixup = false;
                for (StackTraceElement frame : failure.getCause().getStackTrace()) {
                    inFixup |= frame.getMethodName().equals("gaius$fixupLoadedSpawn");
                }
                check(searched && inFixup,
                        "only a searched spawn may reach gaius$fixupLoadedSpawn: " + failure.getCause());
                continue;
            }
            check(!searched, "a searched spawn must go through gaius$fixupLoadedSpawn");
            check(ready != null && readyClass.isInstance(ready),
                    "Preparing must turn Ready once the spawn chunk is in: " + ready);
            Field position = readyClass.getDeclaredField("spawnPosition");
            position.setAccessible(true);
            check(position.get(ready) == spawn,
                    "a saved position must reach Ready unchanged: " + position.get(ready));
        }
        System.out.println("SPAWN_FIXUP_ONLY_FOR_SEARCHED_SPAWN");
    }

    private static Object allocate(Class<?> type) throws ReflectiveOperationException {
        try {
            return allocateInstance.invoke(unsafe, type);
        } catch (InvocationTargetException failure) {
            throw new IllegalStateException(
                    "cannot allocate " + type.getName(), failure.getCause());
        }
    }

    private static void set(Class<?> owner, Object target, String name, Object value)
            throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
