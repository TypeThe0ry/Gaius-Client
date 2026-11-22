package dev.gaius.tools.bridges;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** ASM description of the Gaius-authored GaiusMeshBlockAccess bridge. */
public final class GaiusMeshBlockAccessAsm implements Opcodes {
    private GaiusMeshBlockAccessAsm() {}

    public static byte[] emit() {
        ClassWriter classWriter = new ClassWriter(0);

        classWriter.visit(V21, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, "net/minecraft/world/level/block/state/GaiusMeshBlockAccess", null, "java/lang/Object", null);

        classWriter.visitSource("GaiusMeshBlockAccess.java", null);

        emitConstructor(classWriter);
        emitMaxHorizontalOffset(classWriter);
        emitMaxVerticalOffset(classWriter);
        classWriter.visitEnd();
        return classWriter.toByteArray();
    }

    private static void emitConstructor(ClassWriter classWriter) {
        MethodVisitor methodVisitor = classWriter.visitMethod(ACC_PRIVATE, "<init>", "()V", null, null);
        methodVisitor.visitCode();
        Label label0 = new Label();
        methodVisitor.visitLabel(label0);
        methodVisitor.visitLineNumber(10, label0);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        Label label1 = new Label();
        methodVisitor.visitLabel(label1);
        methodVisitor.visitLineNumber(11, label1);
        methodVisitor.visitInsn(RETURN);
        Label label2 = new Label();
        methodVisitor.visitLabel(label2);
        methodVisitor.visitLocalVariable("this", "Lnet/minecraft/world/level/block/state/GaiusMeshBlockAccess;", null, label0, label2, 0);
        methodVisitor.visitMaxs(1, 1);
        methodVisitor.visitEnd();
    }

    private static void emitMaxHorizontalOffset(ClassWriter classWriter) {
        MethodVisitor methodVisitor = classWriter.visitMethod(ACC_PUBLIC | ACC_STATIC, "maxHorizontalOffset", "(Lnet/minecraft/world/level/block/state/BlockBehaviour;)F", null, null);
        methodVisitor.visitCode();
        Label label0 = new Label();
        methodVisitor.visitLabel(label0);
        methodVisitor.visitLineNumber(14, label0);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/level/block/state/BlockBehaviour", "getMaxHorizontalOffset", "()F", false);
        methodVisitor.visitInsn(FRETURN);
        Label label1 = new Label();
        methodVisitor.visitLabel(label1);
        methodVisitor.visitLocalVariable("block", "Lnet/minecraft/world/level/block/state/BlockBehaviour;", null, label0, label1, 0);
        methodVisitor.visitMaxs(1, 1);
        methodVisitor.visitEnd();
    }

    private static void emitMaxVerticalOffset(ClassWriter classWriter) {
        MethodVisitor methodVisitor = classWriter.visitMethod(ACC_PUBLIC | ACC_STATIC, "maxVerticalOffset", "(Lnet/minecraft/world/level/block/state/BlockBehaviour;)F", null, null);
        methodVisitor.visitCode();
        Label label0 = new Label();
        methodVisitor.visitLabel(label0);
        methodVisitor.visitLineNumber(18, label0);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/level/block/state/BlockBehaviour", "getMaxVerticalOffset", "()F", false);
        methodVisitor.visitInsn(FRETURN);
        Label label1 = new Label();
        methodVisitor.visitLabel(label1);
        methodVisitor.visitLocalVariable("block", "Lnet/minecraft/world/level/block/state/BlockBehaviour;", null, label0, label1, 0);
        methodVisitor.visitMaxs(1, 1);
        methodVisitor.visitEnd();
    }
}
