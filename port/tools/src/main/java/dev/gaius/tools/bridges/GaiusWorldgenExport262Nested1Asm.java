package dev.gaius.tools.bridges;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** ASM description of the Gaius-authored GaiusWorldgenExport262$1 bridge. */
public final class GaiusWorldgenExport262Nested1Asm implements Opcodes {
    private GaiusWorldgenExport262Nested1Asm() {}

    public static byte[] emit() {
        ClassWriter classWriter = new ClassWriter(0);
        FieldVisitor fieldVisitor;

        classWriter.visit(V21, ACC_SUPER, "net/minecraft/world/level/levelgen/GaiusWorldgenExport262$1", null, "java/lang/Object", new String[] { "net/minecraft/world/level/levelgen/DensityFunction$Visitor" });

        classWriter.visitSource("GaiusWorldgenExport262.java", null);

        classWriter.visitNestHost("net/minecraft/world/level/levelgen/GaiusWorldgenExport262");

        classWriter.visitOuterClass("net/minecraft/world/level/levelgen/GaiusWorldgenExport262", "children", "(Lnet/minecraft/world/level/levelgen/DensityFunction;)Ljava/util/List;");

        classWriter.visitInnerClass("net/minecraft/world/level/levelgen/GaiusWorldgenExport262$1", null, null, 0);

        classWriter.visitInnerClass("net/minecraft/world/level/levelgen/DensityFunction$Visitor", "net/minecraft/world/level/levelgen/DensityFunction", "Visitor", ACC_PUBLIC | ACC_STATIC | ACC_ABSTRACT | ACC_INTERFACE);

        {
            fieldVisitor = classWriter.visitField(ACC_FINAL | ACC_SYNTHETIC, "val$children", "Ljava/util/List;", null, null);
            fieldVisitor.visitEnd();
        }
        emitConstructor(classWriter);
        emitApply(classWriter);
        classWriter.visitEnd();
        return classWriter.toByteArray();
    }

    private static void emitConstructor(ClassWriter classWriter) {
        MethodVisitor methodVisitor = classWriter.visitMethod(0, "<init>", "(Ljava/util/List;)V", "()V", null);
        methodVisitor.visitCode();
        Label label0 = new Label();
        methodVisitor.visitLabel(label0);
        methodVisitor.visitLineNumber(288, label0);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitVarInsn(ALOAD, 1);
        methodVisitor.visitFieldInsn(PUTFIELD, "net/minecraft/world/level/levelgen/GaiusWorldgenExport262$1", "val$children", "Ljava/util/List;");
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        methodVisitor.visitInsn(RETURN);
        Label label1 = new Label();
        methodVisitor.visitLabel(label1);
        methodVisitor.visitLocalVariable("this", "Lnet/minecraft/world/level/levelgen/GaiusWorldgenExport262$1;", null, label0, label1, 0);
        methodVisitor.visitMaxs(2, 2);
        methodVisitor.visitEnd();
    }

    private static void emitApply(ClassWriter classWriter) {
        MethodVisitor methodVisitor = classWriter.visitMethod(ACC_PUBLIC, "apply", "(Lnet/minecraft/world/level/levelgen/DensityFunction;)Lnet/minecraft/world/level/levelgen/DensityFunction;", null, null);
        methodVisitor.visitCode();
        Label label0 = new Label();
        methodVisitor.visitLabel(label0);
        methodVisitor.visitLineNumber(291, label0);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitFieldInsn(GETFIELD, "net/minecraft/world/level/levelgen/GaiusWorldgenExport262$1", "val$children", "Ljava/util/List;");
        methodVisitor.visitVarInsn(ALOAD, 1);
        methodVisitor.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
        methodVisitor.visitInsn(POP);
        Label label1 = new Label();
        methodVisitor.visitLabel(label1);
        methodVisitor.visitLineNumber(292, label1);
        methodVisitor.visitVarInsn(ALOAD, 1);
        methodVisitor.visitInsn(ARETURN);
        Label label2 = new Label();
        methodVisitor.visitLabel(label2);
        methodVisitor.visitLocalVariable("this", "Lnet/minecraft/world/level/levelgen/GaiusWorldgenExport262$1;", null, label0, label2, 0);
        methodVisitor.visitLocalVariable("child", "Lnet/minecraft/world/level/levelgen/DensityFunction;", null, label0, label2, 1);
        methodVisitor.visitMaxs(2, 2);
        methodVisitor.visitEnd();
    }
}
