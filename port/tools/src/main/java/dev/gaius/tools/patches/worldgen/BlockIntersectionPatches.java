package dev.gaius.tools.patches.worldgen;

import static dev.gaius.tools.patches.support.ClientPatchSupport.find;
import static dev.gaius.tools.patches.support.ClientPatchSupport.read;
import static dev.gaius.tools.patches.support.ClientPatchSupport.writeComputeFrames;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import dev.gaius.tools.PatchTargets;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * Scalar-inlines the directed iterator inside BlockGetter's swept-slice loop. Only
 * addCollisionsAlongTravel is rewritten; its DDA, clip, set and visitor body are retained.
 * Setup and computeNext arithmetic are copied from the input jar, with iterator fields
 * mapped to locals. Exact normalized bytecode hashes guard every scalarized method,
 * including the constructor whose field initialization is reproduced here.
 *
 * <p>The iterable and iterator disappear, but the cursor deliberately remains fresh per
 * slice: visitors may retain or mutate it. No allocation is introduced per candidate cell.
 */
public final class BlockIntersectionPatches implements Opcodes {
    private static final String POS = "net/minecraft/core/BlockPos";
    private static final String MUTABLE = POS + "$MutableBlockPos";
    private static final String VEC = "net/minecraft/world/phys/Vec3";
    private static final String RANGE = "(IIIIIIL" + VEC + ";)Ljava/lang/Iterable;";
    private BlockIntersectionPatches() { }

    public static void apply(String jar, Path output) throws IOException {
        ClassNode getter = read(jar, "net/minecraft/world/level/BlockGetter.class");
        ClassNode positions = read(jar, POS + ".class");
        MethodNode factory = positions.methods.stream().filter(method ->
                method.name.startsWith("lambda$betweenCornersInDirection$")
                && method.desc.equals("(Lnet/minecraft/core/Direction;Lnet/minecraft/core/Direction;Lnet/minecraft/core/Direction;IIIIII)Ljava/util/Iterator;"))
                .findFirst().orElseThrow(() -> new IllegalStateException("Missing directed iterator factory"));
        String iterator = null;
        for (AbstractInsnNode insn : factory.instructions) {
            if (insn instanceof TypeInsnNode type && type.getOpcode() == NEW) iterator = type.desc;
        }
        if (iterator == null) throw new IllegalStateException("Missing directed range iterator");
        patch(getter, positions, read(jar, iterator + ".class"));
        writeComputeFrames(getter, output);
    }

    public static void patch(ClassNode getter, ClassNode positions, ClassNode iterator) {
        MethodNode travel = find(getter, "addCollisionsAlongTravel",
                "(Lit/unimi/dsi/fastutil/longs/LongSet;L" + VEC
                + ";Lnet/minecraft/world/phys/AABB;Lnet/minecraft/world/level/BlockGetter$BlockStepVisitor;)I");
        MethodNode setup = find(positions, "betweenCornersInDirection", RANGE);
        MethodNode next = find(iterator, "computeNext", "()L" + POS + ";");
        verifyShapes(travel, setup, next, iterator);
        MethodInsnNode range = null;
        for (AbstractInsnNode insn : travel.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(POS)
                    && call.name.equals("betweenCornersInDirection") && call.desc.equals(RANGE)) {
                if (range != null) throw new IllegalStateException("Multiple swept range sites");
                range = call;
            }
        }
        if (range == null) throw new IllegalStateException("Missing swept range site");
        // Match the javac enhanced-for protocol; preserve its callback and duplicate suppression body.
        AbstractInsnNode[] loop = new AbstractInsnNode[10];
        AbstractInsnNode cursor = range;
        for (int i = 0; i < loop.length; i++) { loop[i] = cursor; cursor = realNext(cursor); }
        requireCall(loop[1], "java/lang/Iterable", "iterator");
        if (!(loop[2] instanceof VarInsnNode store) || store.getOpcode() != ASTORE
                || !(loop[3] instanceof VarInsnNode load) || load.getOpcode() != ALOAD || load.var != store.var
                || !(loop[5] instanceof JumpInsnNode exit) || exit.getOpcode() != IFEQ
                || !(loop[6] instanceof VarInsnNode loadNext) || loadNext.var != store.var
                || !(loop[8] instanceof TypeInsnNode cast) || cast.getOpcode() != CHECKCAST || !cast.desc.equals(POS)
                || !(loop[9] instanceof VarInsnNode position) || position.getOpcode() != ASTORE) {
            throw new IllegalStateException("Swept enhanced-for shape changed");
        }
        requireCall(loop[4], "java/util/Iterator", "hasNext");
        requireCall(loop[7], "java/util/Iterator", "next");
        // The back edge must target the old hasNext load; replace that entire header with our loop.
        LabelNode oldHead = null;
        for (AbstractInsnNode n = loop[3].getPrevious(); n != null && n.getOpcode() < 0; n = n.getPrevious()) {
            if (n instanceof LabelNode label) oldHead = label;
        }
        if (oldHead == null) throw new IllegalStateException("Missing slice loop label");
        int base = travel.maxLocals;
        int captures = base + setup.maxLocals;
        Map<String, Integer> fields = new LinkedHashMap<>();
        for (FieldNode field : iterator.fields) fields.put(field.name, captures + 9 + fields.size());
        InsnList code = new InsnList();
        // Range arguments already on the operand stack; scalar-inline vanilla's exact setup math.
        for (int i = 6; i >= 0; i--) code.add(new VarInsnNode(i == 6 ? ASTORE : ISTORE, base + i));
        Map<LabelNode, LabelNode> labels = labels(setup);
        for (AbstractInsnNode insn : setup.instructions) {
            if (insn instanceof InvokeDynamicInsnNode) break;
            if (insn instanceof FrameNode || insn instanceof LineNumberNode) continue;
            AbstractInsnNode copy = insn.clone(labels);
            if (copy instanceof VarInsnNode var) var.var += base;
            code.add(copy);
        }
        // Lambda captures: first/second/third directions, start XYZ, third/second/first maxima.
        for (int i = 8; i >= 0; i--) code.add(new VarInsnNode(i < 3 ? ASTORE : ISTORE, captures + i));
        String[] names = {"val$firstVisitDir", "val$secondVisitDir", "val$thirdVisitDir",
                "val$startCornerX", "val$startCornerY", "val$startCornerZ", "val$thirdMax", "val$secondMax", "val$firstMax"};
        for (int i = 0; i < names.length; i++) {
            code.add(new VarInsnNode(i < 3 ? ALOAD : ILOAD, captures + i));
            code.add(new VarInsnNode(i < 3 ? ASTORE : ISTORE, field(fields, names[i])));
        }
        code.add(new TypeInsnNode(NEW, MUTABLE));
        code.add(new InsnNode(DUP));
        code.add(new MethodInsnNode(INVOKESPECIAL, MUTABLE, "<init>", "()V", false));
        code.add(new VarInsnNode(ASTORE, field(fields, "cursor")));
        for (String name : new String[]{"firstIndex", "secondIndex", "thirdIndex", "end"}) {
            code.add(new InsnNode(ICONST_0));
            code.add(new VarInsnNode(ISTORE, field(fields, name)));
        }
        String[] axes = {"X", "Y", "Z"};
        String[] ranks = {"first", "second", "third"};
        for (int rank = 0; rank < 3; rank++) for (String axis : axes) {
            code.add(new VarInsnNode(ALOAD, captures + rank));
            code.add(new MethodInsnNode(INVOKEVIRTUAL, "net/minecraft/core/Direction", "getStep" + axis, "()I", false));
            code.add(new VarInsnNode(ISTORE, field(fields, ranks[rank] + "Dir" + axis)));
        }
        LabelNode head = new LabelNode();
        code.add(head);
        code.add(new VarInsnNode(ILOAD, field(fields, "end")));
        code.add(new JumpInsnNode(IFNE, exit.label));
        // Remove only AbstractIterator's endOfData preamble and return. Preserve vanilla coordinate
        // arithmetic and advance-before-callback timing, including mutations by callbacks.
        labels = labels(next);
        LabelNode firstBody = ((JumpInsnNode) firstBranch(next)).label;
        for (AbstractInsnNode insn = firstBody; insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == ARETURN) break;
            if (insn instanceof FrameNode || insn instanceof LineNumberNode) continue;
            if (insn instanceof VarInsnNode var && var.getOpcode() == ALOAD && var.var == 0) continue;
            if (insn.getOpcode() == DUP) continue;
            if (insn instanceof FieldInsnNode access && access.owner.equals(iterator.name)) {
                boolean reference = access.desc.startsWith("L");
                int opcode = access.getOpcode() == GETFIELD ? (reference ? ALOAD : ILOAD) : (reference ? ASTORE : ISTORE);
                code.add(new VarInsnNode(opcode, field(fields, access.name)));
            } else {
                code.add(insn.clone(labels));
            }
        }
        code.add(new VarInsnNode(ASTORE, position.var));
        travel.instructions.insertBefore(range, code);
        for (int i = 0; i <= 9; i++) travel.instructions.remove(loop[i]);
        for (AbstractInsnNode insn : travel.instructions) {
            if (insn instanceof JumpInsnNode branch && branch.label == oldHead) branch.label = head;
        }
        travel.maxLocals = captures + 9 + fields.size();
        travel.localVariables = null;
        for (AbstractInsnNode insn : travel.instructions.toArray()) if (insn instanceof FrameNode) travel.instructions.remove(insn);
    }

    private static AbstractInsnNode firstBranch(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) if (insn instanceof JumpInsnNode) return insn;
        throw new IllegalStateException("No iterator end check");
    }
    private static int field(Map<String, Integer> fields, String name) {
        Integer value = fields.get(name);
        if (value == null) throw new IllegalStateException("Missing iterator field " + name);
        return value;
    }
    private static Map<LabelNode, LabelNode> labels(MethodNode method) {
        Map<LabelNode, LabelNode> map = new HashMap<>();
        for (AbstractInsnNode insn : method.instructions) if (insn instanceof LabelNode label) map.put(label, new LabelNode());
        return map;
    }
    private static AbstractInsnNode realNext(AbstractInsnNode insn) {
        do { insn = insn.getNext(); } while (insn != null && insn.getOpcode() < 0);
        if (insn == null) throw new IllegalStateException("Truncated slice loop");
        return insn;
    }
    private static void requireCall(AbstractInsnNode insn, String owner, String name) {
        if (!(insn instanceof MethodInsnNode call) || !call.owner.equals(owner) || !call.name.equals(name)) {
            throw new IllegalStateException("Expected " + owner + "." + name);
        }
    }
    private static void verifyShapes(MethodNode travel, MethodNode setup, MethodNode next, ClassNode iterator) {
        if (!iterator.superName.equals("com/google/common/collect/AbstractIterator") || iterator.fields.size() != 23) {
            throw new IllegalStateException("Directed iterator fields changed");
        }
        requireHash(travel, "defd3e2eae9eaf30b0dfd880d6c04c024edb09ada6ba0a61f7533ed64677cb26");
        requireHash(setup, "f375b39a4ba79c02991fc7ec3bb8914ec9e289c23d2c36c2c3b0ce53264df553",
                "0871bdf515e3d950e6afdf4b331521c9349c0a77b514feb529146f85d4d4b7d0");
        requireHash(next, "a6a9844aa623e7278c45174b95bc1489af46000defb35da1c0b2cf624678456d",
                "c53fadc57f4d6ec1ddca7b6b90667d976665c5be4d8f14894e500561af8a39c7");
        MethodNode constructor = iterator.methods.stream().filter(method -> method.name.equals("<init>"))
                .findFirst().orElseThrow();
        requireHash(constructor, "13d1834470c751f20e8a45632cf78eacdee1d1c19e2bb68918c427c5f68ac95f",
                "85f4e0071b92af3cc4148c856809680f3b8f2e1f936f0558946308cb01a2239b");
    }
    private static void requireHash(MethodNode method, String... accepted) {
        String actual = PatchTargets.methodHash(method);
        if (!Set.of(accepted).contains(actual)) {
            throw new IllegalStateException("Swept traversal shape changed: " + method.name + method.desc + " hash=" + actual);
        }
    }
}
