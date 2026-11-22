package dev.gaius.tools.bridges;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** ASM description of the Gaius-authored GaiusClimateIndex$Cursor bridge. */
public final class GaiusClimateIndexNestedCursorAsm implements Opcodes {
    private GaiusClimateIndexNestedCursorAsm() {}

    public static byte[] emit() {
        ClassWriter classWriter = new ClassWriter(0);
        FieldVisitor fieldVisitor;

        classWriter.visit(V21, ACC_FINAL | ACC_SUPER, "net/minecraft/world/level/biome/GaiusClimateIndex$Cursor", null, "java/lang/Object", null);

        classWriter.visitSource("GaiusClimateIndex.java", null);

        classWriter.visitNestHost("net/minecraft/world/level/biome/GaiusClimateIndex");

        classWriter.visitInnerClass("net/minecraft/world/level/biome/GaiusClimateIndex$Cursor", "net/minecraft/world/level/biome/GaiusClimateIndex", "Cursor", ACC_PRIVATE | ACC_FINAL | ACC_STATIC);

        {
            fieldVisitor = classWriter.visitField(0, "min", "J", null, null);
            fieldVisitor.visitEnd();
        }
        {
            fieldVisitor = classWriter.visitField(0, "best", "I", null, null);
            fieldVisitor.visitEnd();
        }
        {
            fieldVisitor = classWriter.visitField(ACC_FINAL, "partial", "Z", null, null);
            fieldVisitor.visitEnd();
        }
        emitConstructor(classWriter);
        classWriter.visitEnd();
        return classWriter.toByteArray();
    }

    private static void emitConstructor(ClassWriter classWriter) {
        MethodVisitor methodVisitor = classWriter.visitMethod(0, "<init>", "(JZ)V", null, null);
        methodVisitor.visitCode();
        Label label0 = new Label();
        methodVisitor.visitLabel(label0);
        methodVisitor.visitLineNumber(277, label0);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        Label label1 = new Label();
        methodVisitor.visitLabel(label1);
        methodVisitor.visitLineNumber(273, label1);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitInsn(ICONST_M1);
        methodVisitor.visitFieldInsn(PUTFIELD, "net/minecraft/world/level/biome/GaiusClimateIndex$Cursor", "best", "I");
        Label label2 = new Label();
        methodVisitor.visitLabel(label2);
        methodVisitor.visitLineNumber(278, label2);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitVarInsn(LLOAD, 1);
        methodVisitor.visitFieldInsn(PUTFIELD, "net/minecraft/world/level/biome/GaiusClimateIndex$Cursor", "min", "J");
        Label label3 = new Label();
        methodVisitor.visitLabel(label3);
        methodVisitor.visitLineNumber(279, label3);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitVarInsn(ILOAD, 3);
        methodVisitor.visitFieldInsn(PUTFIELD, "net/minecraft/world/level/biome/GaiusClimateIndex$Cursor", "partial", "Z");
        Label label4 = new Label();
        methodVisitor.visitLabel(label4);
        methodVisitor.visitLineNumber(280, label4);
        methodVisitor.visitInsn(RETURN);
        Label label5 = new Label();
        methodVisitor.visitLabel(label5);
        methodVisitor.visitLocalVariable("this", "Lnet/minecraft/world/level/biome/GaiusClimateIndex$Cursor;", null, label0, label5, 0);
        methodVisitor.visitLocalVariable("min", "J", null, label0, label5, 1);
        methodVisitor.visitLocalVariable("partial", "Z", null, label0, label5, 3);
        methodVisitor.visitMaxs(3, 4);
        methodVisitor.visitEnd();
    }
}
