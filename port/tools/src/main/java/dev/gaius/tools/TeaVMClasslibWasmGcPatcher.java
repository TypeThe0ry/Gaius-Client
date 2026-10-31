package dev.gaius.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * WebAssembly GC target only: fixes TeaVM 0.15 class library code that only the WebAssembly GC
 * backend runs.
 *
 * <p>{@code TByteBufferNative} backs every direct {@code ByteBuffer} of that backend (LWJGL
 * memory, {@code allocateDirect}):
 * <ul>
 * <li>{@code getLong(int)} advances the position by 8 although it is an absolute get, so
 * {@code PngInfo.validateHeader} (two absolute reads) moved every PNG buffer and STB decoded
 * from the wrong offset ("not a PNG image");</li>
 * <li>{@code putLong(int, long)} checks {@code index + 3} instead of {@code index + 7};</li>
 * <li>{@code put(ByteBuffer)} from an array-backed buffer adds the source offset twice;</li>
 * <li>the absolute accessors ({@code get/put}, {@code Char}, {@code Short}, {@code Int},
 * {@code Float}, {@code Long}, {@code Double} with an index) check {@code index + n - 1 < limit},
 * which overflows near {@code Integer.MAX_VALUE} (a wasm trap instead of an exception, or an
 * access past the buffer), and the float and double ones do not check a negative index (a write
 * before the buffer start) and throw Buffer(Under|Over)flowException instead of
 * IndexOutOfBoundsException.</li>
 * </ul>
 * {@code TByteBuffer.allocateDirect} takes the memory of a direct buffer from {@code Heap.alloc},
 * which hands out a freed block as it is: it still holds the allocator's own bookkeeping (a free
 * HeapNode leaf leaves height 1 at data offset 12) or the bytes of the buffer that held it before,
 * while Java zeroes it. Vanilla relies on the zeroes: a new RegionFile keeps the header buffer it
 * allocated as the location table of the new file, so the wasm server wrote stale bytes as the
 * sector entries of chunks that were never stored ("Chunk [x, z] header is truncated", "Chunk
 * found in invalid location", sector offsets out of range). The fix zeroes the block between the
 * allocation's null check and the construction of the buffer; the JavaScript branch (a new
 * Int8Array) is zeroed already.
 *
 * Each fix replaces an exact instruction sequence and fails when the TeaVM code differs; the
 * stack shape never changes, so the original stack map frames are kept. The absolute-accessor
 * check is a new prologue whose branch targets carry frames equal to the method's entry frame.
 *
 * <p>Usage: {@code TeaVMClasslibWasmGcPatcher <teavm-classlib.jar> <patch-dir>}.
 */
public final class TeaVMClasslibWasmGcPatcher {
    static final String BYTE_BUFFER_NATIVE = "org/teavm/classlib/java/nio/TByteBufferNative";
    static final String BYTE_BUFFER = "org/teavm/classlib/java/nio/TByteBuffer";

    private TeaVMClasslibWasmGcPatcher() {
    }

    public static void main(String[] args) throws IOException {
        ClassNode node = read(args[0], BYTE_BUFFER_NATIVE);
        fixAbsoluteGetLong(node);
        fixAbsolutePutLongBound(node);
        fixPutBufferOffset(node);
        checkAbsoluteIndexes(node);
        write(Path.of(args[1]), node);
        ClassNode byteBuffer = read(args[0], BYTE_BUFFER);
        zeroAllocateDirect(byteBuffer);
        write(Path.of(args[1]), byteBuffer);
    }

    /**
     * {@code allocateDirect}, WebAssembly GC branch: {@code Heap.alloc(capacity); astore a; aload
     * a; ifnonnull L; <OutOfMemoryError>; L: new TByteBufferWasmGC} gets {@code
     * Address.fillZero(a, capacity)} at L. The stack is empty there and the locals stay, so the
     * frame at L still holds.
     */
    static void zeroAllocateDirect(ClassNode node) {
        MethodNode method = method(node, "allocateDirect", "(I)L" + BYTE_BUFFER + ";");
        int patched = 0;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC
                    || !call.owner.equals("org/teavm/runtime/heap/Heap") || !call.name.equals("alloc")
                    || !call.desc.equals("(I)Lorg/teavm/interop/Address;")) {
                continue;
            }
            AbstractInsnNode store = next(call);
            AbstractInsnNode load = next(store);
            AbstractInsnNode check = next(load);
            if (!(store instanceof VarInsnNode address) || address.getOpcode() != Opcodes.ASTORE
                    || !(load instanceof VarInsnNode loaded) || loaded.getOpcode() != Opcodes.ALOAD
                    || loaded.var != address.var
                    || !(check instanceof JumpInsnNode nonNull) || nonNull.getOpcode() != Opcodes.IFNONNULL) {
                throw new IllegalStateException(node.name + ".allocateDirect: Heap.alloc is no longer followed "
                        + "by its null check");
            }
            AbstractInsnNode create = next(nonNull.label);
            if (!(create instanceof TypeInsnNode type) || type.getOpcode() != Opcodes.NEW
                    || !type.desc.equals("org/teavm/classlib/java/nio/TByteBufferWasmGC")) {
                throw new IllegalStateException(node.name + ".allocateDirect: the non-null branch of Heap.alloc "
                        + "no longer builds a TByteBufferWasmGC");
            }
            InsnList zero = new InsnList();
            zero.add(new VarInsnNode(Opcodes.ALOAD, address.var));
            zero.add(new VarInsnNode(Opcodes.ILOAD, 0));
            zero.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/teavm/interop/Address", "fillZero",
                    "(Lorg/teavm/interop/Address;I)V", false));
            method.instructions.insertBefore(create, zero);
            patched++;
        }
        if (patched != 1) {
            throw new IllegalStateException(node.name + ".allocateDirect: expected one Heap.alloc, found "
                    + patched);
        }
    }

    /** The next instruction after {@code insn}, skipping labels, line numbers and frames. */
    private static AbstractInsnNode next(AbstractInsnNode insn) {
        AbstractInsnNode next = insn == null ? null : insn.getNext();
        while (next != null && next.getOpcode() < 0) {
            next = next.getNext();
        }
        return next;
    }

    /** The absolute accessors of TByteBufferNative: name, descriptor, access width in bytes. */
    static final Object[][] ABSOLUTE_ACCESSORS = {
        {"get", "(I)B", 1},
        {"put", "(IB)L" + BYTE_BUFFER + ";", 1},
        {"getChar", "(I)C", 2},
        {"putChar", "(IC)L" + BYTE_BUFFER + ";", 2},
        {"getShort", "(I)S", 2},
        {"putShort", "(IS)L" + BYTE_BUFFER + ";", 2},
        {"getInt", "(I)I", 4},
        {"putInt", "(II)L" + BYTE_BUFFER + ";", 4},
        {"getFloat", "(I)F", 4},
        {"putFloat", "(IF)L" + BYTE_BUFFER + ";", 4},
        {"getLong", "(I)J", 8},
        {"putLong", "(IJ)L" + BYTE_BUFFER + ";", 8},
        {"getDouble", "(I)D", 8},
        {"putDouble", "(ID)L" + BYTE_BUFFER + ";", 8},
    };

    /**
     * Prefixes every absolute accessor with the JDK's check, {@code if (index < 0 || index >
     * limit - n) throw new IndexOutOfBoundsException()} ({@code limit - n} cannot overflow). A
     * put on a read-only buffer skips it, so the original code still throws
     * ReadOnlyBufferException first, as the JDK does. The original checks stay and never fire.
     */
    static void checkAbsoluteIndexes(ClassNode node) {
        for (Object[] accessor : ABSOLUTE_ACCESSORS) {
            MethodNode method = method(node, (String) accessor[0], (String) accessor[1]);
            int width = (Integer) accessor[2];
            FieldInsnNode limit = firstField(method, Opcodes.GETFIELD, "limit", "I");
            boolean put = ((String) accessor[0]).startsWith("put");
            AbstractInsnNode first = method.instructions.getFirst();
            while (first != null && first.getOpcode() < 0) {
                if (first instanceof FrameNode) {
                    throw new IllegalStateException(node.name + "." + accessor[0] + accessor[1]
                            + " starts at a branch target");
                }
                first = first.getNext();
            }
            InsnList check = new InsnList();
            LabelNode fail = new LabelNode();
            LabelNode pass = new LabelNode();
            if (put) {
                FieldInsnNode readOnly = firstField(method, Opcodes.GETFIELD, "readOnly", "Z");
                check.add(new VarInsnNode(Opcodes.ALOAD, 0));
                check.add(new FieldInsnNode(Opcodes.GETFIELD, readOnly.owner, readOnly.name, readOnly.desc));
                check.add(new JumpInsnNode(Opcodes.IFNE, pass));
            }
            check.add(new VarInsnNode(Opcodes.ILOAD, 1));
            check.add(new JumpInsnNode(Opcodes.IFLT, fail));
            check.add(new VarInsnNode(Opcodes.ILOAD, 1));
            check.add(new VarInsnNode(Opcodes.ALOAD, 0));
            check.add(new FieldInsnNode(Opcodes.GETFIELD, limit.owner, limit.name, limit.desc));
            check.add(new IntInsnNode(Opcodes.BIPUSH, width));
            check.add(new InsnNode(Opcodes.ISUB));
            check.add(new JumpInsnNode(Opcodes.IF_ICMPLE, pass));
            check.add(fail);
            check.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            check.add(new TypeInsnNode(Opcodes.NEW, "java/lang/IndexOutOfBoundsException"));
            check.add(new InsnNode(Opcodes.DUP));
            check.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/IndexOutOfBoundsException",
                    "<init>", "()V", false));
            check.add(new InsnNode(Opcodes.ATHROW));
            check.add(pass);
            check.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            method.instructions.insert(check);
        }
    }

    private static FieldInsnNode firstField(MethodNode method, int opcode, String name, String desc) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof FieldInsnNode field && field.getOpcode() == opcode
                    && field.name.equals(name) && field.desc.equals(desc)) {
                return field;
            }
        }
        throw new IllegalStateException(method.name + method.desc + " reads no " + name + " field");
    }

    /** Removes {@code this.position += 8} from {@code getLong(int)}. */
    static void fixAbsoluteGetLong(ClassNode node) {
        MethodNode method = method(node, "getLong", "(I)J");
        AbstractInsnNode start = null;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
                    && field.name.equals("position")) {
                AbstractInsnNode add = field.getPrevious();
                AbstractInsnNode eight = add.getPrevious();
                AbstractInsnNode get = eight.getPrevious();
                AbstractInsnNode dup = get.getPrevious();
                AbstractInsnNode self = dup.getPrevious();
                if (add.getOpcode() == Opcodes.IADD && eight instanceof IntInsnNode constant
                        && constant.operand == 8 && get instanceof FieldInsnNode getField
                        && getField.name.equals("position") && dup.getOpcode() == Opcodes.DUP
                        && self instanceof VarInsnNode load && load.var == 0) {
                    if (start != null) {
                        throw new IllegalStateException("getLong(int) updates the position twice");
                    }
                    start = self;
                }
            }
        }
        if (start == null) {
            throw new IllegalStateException("TByteBufferNative.getLong(int) no longer moves the position");
        }
        for (int i = 0; i < 6; i++) {
            AbstractInsnNode next = start.getNext();
            method.instructions.remove(start);
            start = next;
        }
    }

    /** {@code index + 3 < limit} becomes {@code index + 7 < limit} in {@code putLong(int, long)}. */
    static void fixAbsolutePutLongBound(ClassNode node) {
        MethodNode method = method(node, "putLong", "(IJ)L" + BYTE_BUFFER + ";");
        for (AbstractInsnNode insn : method.instructions) {
            if (insn.getOpcode() == Opcodes.ICONST_3 && insn.getPrevious() instanceof VarInsnNode load
                    && load.var == 1 && insn.getNext().getOpcode() == Opcodes.IADD) {
                method.instructions.set(insn, new IntInsnNode(Opcodes.BIPUSH, 7));
                return;
            }
        }
        throw new IllegalStateException("TByteBufferNative.putLong(int, long) bound check changed");
    }

    /** {@code offset + src.arrayOffset() + offset} becomes {@code src.arrayOffset() + offset}. */
    static void fixPutBufferOffset(ClassNode node) {
        MethodNode method = method(node, "putImpl", "(IL" + BYTE_BUFFER + ";II)V");
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.name.equals("arrayOffset")) {
                AbstractInsnNode src = call.getPrevious();
                AbstractInsnNode firstOffset = src.getPrevious();
                AbstractInsnNode add = call.getNext();
                AbstractInsnNode secondOffset = add.getNext();
                if (src instanceof VarInsnNode srcLoad && srcLoad.var == 2
                        && firstOffset instanceof VarInsnNode first && first.var == 3
                        && first.getOpcode() == Opcodes.ILOAD && add.getOpcode() == Opcodes.IADD
                        && secondOffset instanceof VarInsnNode second && second.var == 3
                        && second.getOpcode() == Opcodes.ILOAD
                        && secondOffset.getNext().getOpcode() == Opcodes.IADD) {
                    method.instructions.remove(firstOffset);
                    method.instructions.remove(add);
                    return;
                }
            }
        }
        throw new IllegalStateException("TByteBufferNative.putImpl(int, ByteBuffer, int, int) changed");
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        return node.methods.stream()
                .filter(candidate -> candidate.name.equals(name) && candidate.desc.equals(desc))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(node.name + "." + name + desc + " not found"));
    }

    private static ClassNode read(String jarPath, String className) throws IOException {
        try (ZipFile jar = new ZipFile(jarPath)) {
            var entry = jar.getEntry(className + ".class");
            if (entry == null) {
                throw new IllegalStateException(className + " not found in " + jarPath);
            }
            try (var stream = jar.getInputStream(entry)) {
                ClassNode node = new ClassNode();
                new ClassReader(stream.readAllBytes()).accept(node, 0);
                return node;
            }
        }
    }

    private static void write(Path root, ClassNode node) throws IOException {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        Path output = root.resolve(node.name + ".class");
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }
}
