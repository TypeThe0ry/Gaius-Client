package dev.gaius.tools;

import com.sun.management.ThreadMXBean;
import dev.gaius.tools.patches.worldgen.BlockIntersectionPatches;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.*;
import java.util.jar.JarFile;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Executes original jar bytecode and patched bytecode with the same real vanilla types. */
public final class BlockIntersectionTraversalTest {
    private static final String GETTER = "net/minecraft/world/level/BlockGetter";
    private static long sink;

    public static void main(String[] args) throws Exception {
        String jar = args[0];
        ClassNode original = read(jar, GETTER);
        ClassNode patched = read(jar, GETTER);
        ClassNode positions = read(jar, "net/minecraft/core/BlockPos");
        String iterator = positions.methods.stream().filter(m -> m.name.startsWith("lambda$betweenCornersInDirection$"))
                .flatMap(m -> Arrays.stream(m.instructions.toArray()))
                .filter(i -> i instanceof TypeInsnNode t && t.getOpcode() == Opcodes.NEW)
                .map(i -> ((TypeInsnNode) i).desc).findFirst().orElseThrow();
        BlockIntersectionPatches.patch(patched, positions, read(jar, iterator));
        MethodNode patchedTravel = patched.methods.stream().filter(m -> m.name.equals("addCollisionsAlongTravel"))
                .findFirst().orElseThrow();
        long mutableSites = 0;
        for (AbstractInsnNode instruction : patchedTravel.instructions) {
            if (instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
                    && type.desc.equals("net/minecraft/core/BlockPos$MutableBlockPos")) mutableSites++;
            if (instruction instanceof MethodInsnNode call) {
                require(!call.owner.equals("java/lang/Iterable") && !call.owner.equals("java/util/Iterator"),
                        "swept iterator protocol must be removed");
                require(!call.name.equals("betweenCornersInDirection"), "swept iterable creation must be removed");
            }
        }
        require(mutableSites == 1, "one fresh cursor allocation site per swept range");
        Method vanilla = fixture(original, "Vanilla");
        Method fast = fixture(patched, "Fast");
        ClassNode changed = read(jar, GETTER);
        changed.methods.stream().filter(m -> m.name.equals("addCollisionsAlongTravel")).findFirst().orElseThrow()
                .instructions.insert(new InsnNode(Opcodes.NOP));
        boolean rejected = false;
        try { BlockIntersectionPatches.patch(changed, positions, read(jar, iterator)); }
        catch (IllegalStateException expected) { rejected = true; }
        require(rejected, "changed vanilla shape must be rejected");
        Random random = new Random(0x713bc513L);
        for (int i = 0; i < 4000; i++) {
            double x = random.nextInt(300) - 150 + random.nextDouble();
            double y = random.nextInt(600) - 300 + random.nextDouble();
            double z = random.nextInt(300) - 150 + random.nextDouble();
            Vec3 from = new Vec3(x, y, z);
            Vec3 to = switch (i % 12) {
                case 0 -> from;
                case 1 -> from.add(0, -4096, 0);
                case 2 -> from.add(0, 4096, 0);
                case 3 -> from.add(1.0E-7, -1.0E-7, 0);
                case 4 -> from.add(16, 16, 16);
                default -> from.add(random.nextDouble() * 24 - 12,
                        random.nextDouble() * 100 - 50, random.nextDouble() * 24 - 12);
            };
            double sx = i % 9 == 0 ? 0 : random.nextDouble() * 3;
            double sy = i % 11 == 0 ? 0 : random.nextDouble() * 4;
            double sz = i % 7 == 0 ? 0 : random.nextDouble() * 3;
            AABB box = new AABB(to.x, to.y, to.z, to.x + sx, to.y + sy, to.z + sz);
            int stop = i % 3 == 0 ? random.nextInt(60) : Integer.MAX_VALUE;
            Capture a = new Capture(stop, i % 5 == 0);
            Capture b = new Capture(stop, i % 5 == 0);
            Object ar = vanilla.invoke(null, from, to, box, a);
            Object br = fast.invoke(null, from, to, box, b);
            require(ar.equals(br), "return " + i);
            require(a.sequence.equals(b.sequence), "visit sequence/step/type/identity " + i);
            require(a.finalValues().equals(b.finalValues()), "captured positions " + i);
        }
        // Boundary/tie cases, reversed AABB constructor inputs, integral and negative coordinates.
        for (double d : new double[] {-1, -0.0, 0, 1, 1.0E-5, -1.0E-5}) {
            Vec3 from = new Vec3(0, 97, 0), to = new Vec3(d, 55, d);
            AABB box = new AABB(1, 57, 1, -1, 55, -1);
            Capture a = new Capture(Integer.MAX_VALUE, false), b = new Capture(Integer.MAX_VALUE, false);
            require(vanilla.invoke(null, from, to, box, a).equals(fast.invoke(null, from, to, box, b)), "boundary result");
            require(a.sequence.equals(b.sequence) && a.finalValues().equals(b.finalValues()), "boundary capture");
        }
        for (Method implementation : new Method[] {vanilla, fast}) {
            Vec3 from = new Vec3(-2, 97, -2), to = new Vec3(-2, -1_000_000, -2);
            AABB box = new AABB(-2.3, -1_000_000, -2.3, -1.7, -999_998.2, -1.7);
            Capture expected = new Capture(25, false);
            Object expectedResult = vanilla.invoke(null, from, to, box, expected);
            Capture observed = new Capture(25, false);
            final boolean[] entered = {false};
            BlockGetter.BlockStepVisitor reentrant = (pos, step) -> {
                if (!entered[0]) {
                    entered[0] = true;
                    int x = pos.getX(), y = pos.getY(), z = pos.getZ();
                    try {
                        implementation.invoke(null, new Vec3(0, 0, 0), new Vec3(0, -7, 0),
                                new AABB(0, -7, 0, 1, -5, 1), new Capture(Integer.MAX_VALUE, true));
                    } catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
                    require(pos.getX() == x && pos.getY() == y && pos.getZ() == z,
                            "reentrant traversal changed outer cursor");
                }
                return observed.visit(pos, step);
            };
            require(expectedResult.equals(implementation.invoke(null, from, to, box, reentrant)), "huge span result");
            require(expected.sequence.equals(observed.sequence) && expected.finalValues().equals(observed.finalValues()),
                    "huge span/reentrant capture");
        }
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        require(bean.isThreadAllocatedMemorySupported(), "allocated-byte measurement unsupported");
        bean.setThreadAllocatedMemoryEnabled(true);
        Vec3 from = new Vec3(0, 97, 0), to = new Vec3(0, 55, 0);
        // Many cells per plane separate cursor/clip cost per plane from cost per visited cell.
        AABB broad = new AABB(-20, 55, -20, 20, 57, 20);
        Counting count = new Counting();
        Method vanillaTravel = vanilla.getDeclaringClass().getMethod("addCollisionsAlongTravel", LongSet.class,
                Vec3.class, AABB.class, BlockGetter.BlockStepVisitor.class);
        Method fastTravel = fast.getDeclaringClass().getMethod("addCollisionsAlongTravel", LongSet.class,
                Vec3.class, AABB.class, BlockGetter.BlockStepVisitor.class);
        // Reuse a fully sized set to isolate iterator allocation from vanilla hash-table growth.
        LongOpenHashSet set = new LongOpenHashSet(200_000);
        Object[] call = {set, to.subtract(from), broad, count};
        for (int i = 0; i < 150; i++) {
            set.clear(); vanillaTravel.invoke(null, call); set.clear(); fastTravel.invoke(null, call);
        }
        long va = allocated(bean, vanillaTravel, call, 50);
        count.cells = 0;
        long fa = allocated(bean, fastTravel, call, 50);
        double bytesPerCell = (double) fa / count.cells;
        require(fa < va, "patch must reduce traversal allocated bytes: " + va + " -> " + fa);
        require(bytesPerCell < 1, "per-cell allocation slope: " + bytesPerCell);
        call[2] = new AABB(-0.3, 55, -0.3, 0.3, 56.8, 0.3);
        long narrowVanilla = allocated(bean, vanillaTravel, call, 50);
        count.cells = 0;
        long narrowPatched = allocated(bean, fastTravel, call, 50);
        double narrowBytesPerCell = (double) narrowPatched / count.cells;
        require(narrowPatched < narrowVanilla, "narrow fall allocation reduction");
        System.out.println("BLOCK_INTERSECTION_TEST PASS jar=" + jar + " randomized=4000 capture=true earlyExit=true mutation=true vanillaBytes="
                + va + " patchedBytes=" + fa + " patchedBytesPerCell=" + bytesPerCell
                + " narrowVanillaBytes=" + narrowVanilla + " narrowPatchedBytes=" + narrowPatched
                + " narrowPatchedBytesPerCell=" + narrowBytesPerCell + " checksum=" + sink);
    }

    private static long allocated(ThreadMXBean bean, Method method, Object[] args, int iterations) throws Exception {
        long id = Thread.currentThread().threadId();
        long before = bean.getThreadAllocatedBytes(id);
        for (int i = 0; i < iterations; i++) { ((LongSet) args[0]).clear(); method.invoke(null, args); }
        return bean.getThreadAllocatedBytes(id) - before;
    }

    private static final class Counting implements BlockGetter.BlockStepVisitor {
        long cells;
        public boolean visit(BlockPos pos, int step) {
            cells++;
            sink += pos.getX() + pos.getY() + pos.getZ() + step;
            return true;
        }
    }

    private static final class Capture implements BlockGetter.BlockStepVisitor {
        final int stop;
        final boolean mutate;
        final List<String> sequence = new ArrayList<>();
        final IdentityHashMap<BlockPos, Integer> ids = new IdentityHashMap<>();
        final List<BlockPos> retained = new ArrayList<>();
        Capture(int stop, boolean mutate) { this.stop = stop; this.mutate = mutate; }
        public boolean visit(BlockPos pos, int step) {
            Integer id = ids.get(pos);
            if (id == null) { id = ids.size(); ids.put(pos, id); retained.add(pos); }
            sequence.add(pos.getX() + "," + pos.getY() + "," + pos.getZ() + ":" + step + ":" + id
                    + ":" + (pos instanceof BlockPos.MutableBlockPos));
            // Vanilla writes all coordinates before each visit; callbacks can mutate a mutable cursor.
            if (mutate && pos instanceof BlockPos.MutableBlockPos mutable) mutable.set(31, -37, 41);
            return sequence.size() <= stop;
        }
        List<String> finalValues() {
            List<String> values = new ArrayList<>();
            for (BlockPos pos : retained) values.add(pos.getX() + "," + pos.getY() + "," + pos.getZ());
            return values;
        }
    }

    private static ClassNode read(String jar, String name) throws Exception {
        try (JarFile file = new JarFile(jar, false); var stream = file.getInputStream(file.getJarEntry(name + ".class"))) {
            ClassNode node = new ClassNode(); new ClassReader(stream).accept(node, 0); return node;
        }
    }

    private static Method fixture(ClassNode source, String suffix) throws Exception {
        ClassNode fixture = new ClassNode();
        fixture.version = Opcodes.V21;
        fixture.access = Opcodes.ACC_PUBLIC;
        fixture.name = "dev/gaius/tools/Traversal" + suffix;
        fixture.superName = "java/lang/Object";
        for (MethodNode method : source.methods) {
            if (!Set.of("forEachBlockIntersectedBetween", "addCollisionsAlongTravel", "getFurthestCorner").contains(method.name)) continue;
            method.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(GETTER)) {
                    call.owner = fixture.name; call.itf = false;
                }
            }
            fixture.methods.add(method);
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        fixture.accept(writer);
        byte[] bytes = writer.toByteArray();
        Class<?> type = new ClassLoader(BlockIntersectionTraversalTest.class.getClassLoader()) {
            Class<?> define() { return defineClass(null, bytes, 0, bytes.length); }
        }.define();
        return type.getMethod("forEachBlockIntersectedBetween", Vec3.class, Vec3.class, AABB.class, BlockGetter.BlockStepVisitor.class);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
