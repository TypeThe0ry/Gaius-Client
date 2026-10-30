package dev.gaius.wgperf;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.Label;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MethodInsnNode;

public final class StatusAgent implements ClassFileTransformer {
    private static final String PROFILER = "dev/gaius/wgperf/StatusProfiler";
    private static final java.util.Map<String, ClassReader> TYPES = new java.util.concurrent.ConcurrentHashMap<>();

    private static ClassReader type(String name) {
        return TYPES.computeIfAbsent(name, key -> {
            try (var source = ClassLoader.getSystemResourceAsStream(key + ".class")) {
                if (source == null) throw new IllegalStateException("Missing frame type " + key);
                return new ClassReader(source);
            } catch (java.io.IOException error) { throw new IllegalStateException(error); }
        });
    }

    private static boolean assignable(String target, String source) {
        if (target.equals(source) || target.equals("java/lang/Object")) return true;
        if (source.startsWith("[")) {
            if (target.equals("java/lang/Cloneable") || target.equals("java/io/Serializable")) return true;
            if (!target.startsWith("[")) return false;
            String targetElement = element(target);
            String sourceElement = element(source);
            return targetElement != null && sourceElement != null && assignable(targetElement, sourceElement);
        }
        if (target.startsWith("[")) return false;
        ClassReader info = type(source);
        if (info.getSuperName() != null && assignable(target, info.getSuperName())) return true;
        for (String parent : info.getInterfaces()) if (assignable(target, parent)) return true;
        return false;
    }

    private static String element(String array) {
        String element = array.substring(1);
        if (element.startsWith("[")) return element;
        return element.startsWith("L") ? element.substring(1, element.length() - 1) : null;
    }

    private static String common(String left, String right) {
        if (assignable(left, right)) return left;
        if (assignable(right, left)) return right;
        if (left.startsWith("[") && right.startsWith("[")) {
            String leftElement = element(left);
            String rightElement = element(right);
            if (leftElement != null && rightElement != null) {
                String merged = common(leftElement, rightElement);
                return "[" + (merged.startsWith("[") ? merged : "L" + merged + ";");
            }
        }
        if (left.startsWith("[") || right.startsWith("[")
                || (type(left).getAccess() & Opcodes.ACC_INTERFACE) != 0
                || (type(right).getAccess() & Opcodes.ACC_INTERFACE) != 0) return "java/lang/Object";
        do { left = type(left).getSuperName(); } while (!assignable(left, right));
        return left;
    }

    private static byte[] ore(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        MethodNode original = node.methods.stream().filter(method -> method.name.equals("doPlace"))
                .findFirst().orElseThrow();
        String descriptor = original.desc;
        original.name = "gaius$oreVanilla";
        original.access = (original.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
        for (var instruction : original.instructions) {
            if (!(instruction instanceof MethodInsnNode method)) continue;
            if (method.owner.equals("java/util/BitSet") && method.name.equals("set") && method.desc.equals("(I)V")) {
                method.setOpcode(Opcodes.INVOKESTATIC);
                method.owner = "dev/gaius/wgperf/OreShadow";
                method.name = "visit";
                method.desc = "(Ljava/util/BitSet;I)V";
                method.itf = false;
            } else if (method.owner.equals("net/minecraft/world/level/chunk/LevelChunkSection")
                    && method.name.equals("setBlockState") && method.desc.startsWith("(IIIL")) {
                method.setOpcode(Opcodes.INVOKESTATIC);
                method.owner = "dev/gaius/wgperf/OreShadow";
                method.name = "write";
                method.desc = "(Lnet/minecraft/world/level/chunk/LevelChunkSection;" + method.desc.substring(1);
                method.itf = false;
            }
        }
        MethodNode wrapper = new MethodNode(Opcodes.ACC_PROTECTED, "doPlace", descriptor, null, null);
        GeneratorAdapter code = new GeneratorAdapter(wrapper, wrapper.access, wrapper.name, wrapper.desc);
        code.loadThis();
        code.loadArgArray();
        code.invokeStatic(Type.getObjectType("dev/gaius/wgperf/OreShadow"),
                new org.objectweb.asm.commons.Method("dispatch", "(Ljava/lang/Object;[Ljava/lang/Object;)Z"));
        code.returnValue();
        code.endMethod();
        node.methods.add(wrapper);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    public static void premain(String options, Instrumentation instrumentation) {
        instrumentation.addTransformer(new StatusAgent());
    }

    private static String category(String owner, String name) {
        if (owner.startsWith("dev/gaius/browser/kernel/worldgen/")) {
            if (name.equals("commit") || name.equals("buildSections") || name.equals("read")
                    || name.equals("applySurface") || name.equals("surfaceStates")) return "result_install";
            if (name.equals("carve")) return "carvers";
            if (name.equals("buildTerrain") || name.equals("fillFromNoise")) return "noise";
            if (name.equals("generateSurface")) return "surface";
            if (name.equals("createBiomes")) return "biomes";
        }
        if (owner.startsWith("net/minecraft/world/level/levelgen/feature/")
                && owner.endsWith("Feature") && name.equals("place")) return "feature";
        if (owner.endsWith("/placement/PlacedFeature")
                && (name.equals("place") || name.equals("placeWithBiomeCheck"))) return "placed";
        if (owner.endsWith("/status/ChunkStatusTasks")) {
            return switch (name) {
                case "generateStructureStarts" -> "structure_starts";
                case "generateStructureReferences" -> "structure_references";
                case "generateBiomes" -> "biomes";
                case "generateNoise", "buildTerrain" -> "noise";
                case "generateSurface" -> "surface";
                case "generateCarvers" -> "carvers";
                case "generateFeatures" -> "features_other";
                case "initializeLight" -> "initialize_light";
                case "light" -> "light";
                case "generateSpawn" -> "spawn";
                case "full" -> "full";
                default -> null;
            };
        }
        if (owner.equals("net/minecraft/world/level/chunk/ChunkGenerator")) {
            if (name.equals("createStructures")) return "structure_starts";
            if (name.equals("createReferences")) return "structure_references";
            if (name.equals("applyBiomeDecoration")) return "features_other";
            if (name.equals("createBiomes") || name.startsWith("lambda$createBiomes$")) return "biomes";
        }
        if (owner.endsWith("/levelgen/NoiseBasedChunkGenerator")) {
            if (name.equals("buildSurface")) return "surface";
            if (name.equals("applyCarvers") || name.equals("generateCarvers")) return "carvers";
            if (name.equals("spawnOriginalMobs")) return "spawn";
            if (name.equals("createBiomes") || name.startsWith("lambda$createBiomes$")) return "biomes";
            if (name.equals("doFill") || name.equals("createNoiseChunk")
                    || name.startsWith("lambda$fillFromNoise$") || name.startsWith("lambda$buildTerrain$")) {
                return "noise";
            }
        }
        if (owner.endsWith("/chunk/ChunkAccess") && name.equals("fillBiomesFromNoise")) return "biomes";
        if ((owner.endsWith("/levelgen/SurfaceSystem") || owner.endsWith("/material/MaterialSystem"))
                && name.equals("buildSurface")) return "surface";
        if (owner.endsWith("/lighting/LevelLightEngine") && name.equals("runLightUpdates")) return "light";
        if (owner.endsWith("/level/ThreadedLevelLightEngine")
                && (name.equals("runUpdate") || name.startsWith("lambda$lightChunk$"))) return "light";
        return null;
    }

    @Override
    public byte[] transform(ClassLoader loader, String owner, Class<?> redefining,
            ProtectionDomain domain, byte[] bytes) {
        if (owner == null || !(owner.startsWith("net/minecraft/")
                || owner.startsWith("dev/gaius/browser/kernel/worldgen/"))) return null;
        try {
            boolean oreChanged = owner.endsWith("/feature/OreFeature")
                    && !System.getProperty("gaius.perf.ore", "off").equals("off");
            if (oreChanged) bytes = ore(bytes);
            if (!Boolean.parseBoolean(System.getProperty("gaius.perf.instrument", "true"))) {
                return oreChanged ? bytes : null;
            }
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
                @Override
                protected String getCommonSuperClass(String left, String right) { return common(left, right); }
            };
            boolean[] changed = {oreChanged};
            reader.accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions) {
                    MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                    String key = category(owner, name);
                    if (key == null || (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return visitor;
                    changed[0] = true;
                    StatusProfiler.instrumented(owner + "." + name + descriptor, key);
                    return new AdviceAdapter(Opcodes.ASM9, visitor, access, name, descriptor) {
                        private int frame;
                        private final Label start = new Label();
                        private final Label end = new Label();
                        private final Label handler = new Label();

                        @Override
                        protected void onMethodEnter() {
                            visitLdcInsn(key);
                            if (key.equals("placed") || key.equals("feature")) loadThis();
                            else visitInsn(Opcodes.ACONST_NULL);
                            visitMethodInsn(Opcodes.INVOKESTATIC, PROFILER, "enter",
                                    "(Ljava/lang/String;Ljava/lang/Object;)Ldev/gaius/wgperf/StatusProfiler$Frame;", false);
                            frame = newLocal(Type.getObjectType(PROFILER + "$Frame"));
                            storeLocal(frame);
                            mark(start);
                        }

                        @Override
                        protected void onMethodExit(int opcode) {
                            if (opcode == Opcodes.ATHROW) return;
                            loadLocal(frame);
                            visitMethodInsn(Opcodes.INVOKESTATIC, PROFILER, "exit",
                                    "(Ldev/gaius/wgperf/StatusProfiler$Frame;)V", false);
                        }

                        @Override
                        public void visitMaxs(int stack, int locals) {
                            mark(end);
                            visitTryCatchBlock(start, end, handler, null);
                            mark(handler);
                            loadLocal(frame);
                            visitMethodInsn(Opcodes.INVOKESTATIC, PROFILER, "exit",
                                    "(Ldev/gaius/wgperf/StatusProfiler$Frame;)V", false);
                            throwException();
                            super.visitMaxs(stack, locals);
                        }
                    };
                }
            }, ClassReader.EXPAND_FRAMES);
            return changed[0] ? writer.toByteArray() : null;
        } catch (Throwable error) {
            StatusProfiler.failed(owner, error);
            return null;
        }
    }
}
