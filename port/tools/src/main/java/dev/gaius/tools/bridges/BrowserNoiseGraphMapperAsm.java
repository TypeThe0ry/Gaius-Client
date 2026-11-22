package dev.gaius.tools.bridges;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** ASM description of the Gaius-authored BrowserNoiseGraphMapper bridge. */
public final class BrowserNoiseGraphMapperAsm implements Opcodes {
    private BrowserNoiseGraphMapperAsm() {}

    public static byte[] emit() {
        ClassWriter classWriter = new ClassWriter(0);
        FieldVisitor fieldVisitor;

        classWriter.visit(V21, ACC_FINAL | ACC_SUPER, "net/minecraft/world/level/levelgen/BrowserNoiseGraphMapper", null, "java/lang/Object", new String[] { "net/minecraft/world/level/levelgen/DensityFunction$Visitor" });

        classWriter.visitSource("BrowserNoiseGraphMapper.java", null);

        classWriter.visitInnerClass("net/minecraft/world/level/levelgen/DensityFunction$Visitor", "net/minecraft/world/level/levelgen/DensityFunction", "Visitor", ACC_PUBLIC | ACC_STATIC | ACC_ABSTRACT | ACC_INTERFACE);

        classWriter.visitInnerClass("net/minecraft/world/level/levelgen/DensityFunction$NoiseHolder", "net/minecraft/world/level/levelgen/DensityFunction", "NoiseHolder", ACC_PUBLIC | ACC_FINAL | ACC_STATIC);

        {
            fieldVisitor = classWriter.visitField(ACC_PRIVATE | ACC_FINAL, "delegate", "Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;", null, null);
            fieldVisitor.visitEnd();
        }
        {
            fieldVisitor = classWriter.visitField(ACC_PRIVATE | ACC_FINAL, "mapped", "Ljava/util/Map;", "Ljava/util/Map<Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/DensityFunction;>;", null);
            fieldVisitor.visitEnd();
        }
        emitConstructor(classWriter);
        emitMap(classWriter);
        emitMapRouter(classWriter);
        emitApply(classWriter);
        emitVisitNoise(classWriter);
        classWriter.visitEnd();
        return classWriter.toByteArray();
    }

    private static void emitConstructor(ClassWriter classWriter) {
        MethodVisitor methodVisitor = classWriter.visitMethod(ACC_PRIVATE, "<init>", "(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)V", null, null);
        methodVisitor.visitCode();
        Label label0 = new Label();
        methodVisitor.visitLabel(label0);
        methodVisitor.visitLineNumber(19, label0);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        Label label1 = new Label();
        methodVisitor.visitLabel(label1);
        methodVisitor.visitLineNumber(17, label1);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitTypeInsn(NEW, "java/util/IdentityHashMap");
        methodVisitor.visitInsn(DUP);
        methodVisitor.visitMethodInsn(INVOKESPECIAL, "java/util/IdentityHashMap", "<init>", "()V", false);
        methodVisitor.visitFieldInsn(PUTFIELD, "net/minecraft/world/level/levelgen/BrowserNoiseGraphMapper", "mapped", "Ljava/util/Map;");
        Label label2 = new Label();
        methodVisitor.visitLabel(label2);
        methodVisitor.visitLineNumber(20, label2);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitVarInsn(ALOAD, 1);
        methodVisitor.visitFieldInsn(PUTFIELD, "net/minecraft/world/level/levelgen/BrowserNoiseGraphMapper", "delegate", "Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;");
        Label label3 = new Label();
        methodVisitor.visitLabel(label3);
        methodVisitor.visitLineNumber(21, label3);
        methodVisitor.visitInsn(RETURN);
        methodVisitor.visitMaxs(3, 2);
        methodVisitor.visitEnd();
    }

    private static void emitMap(ClassWriter classWriter) {
        MethodVisitor methodVisitor = classWriter.visitMethod(ACC_STATIC, "map", "(Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)Lnet/minecraft/world/level/levelgen/DensityFunction;", null, null);
        methodVisitor.visitCode();
        Label label0 = new Label();
        methodVisitor.visitLabel(label0);
        methodVisitor.visitLineNumber(24, label0);
        methodVisitor.visitTypeInsn(NEW, "net/minecraft/world/level/levelgen/BrowserNoiseGraphMapper");
        methodVisitor.visitInsn(DUP);
        methodVisitor.visitVarInsn(ALOAD, 1);
        methodVisitor.visitMethodInsn(INVOKESPECIAL, "net/minecraft/world/level/levelgen/BrowserNoiseGraphMapper", "<init>", "(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)V", false);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/level/levelgen/BrowserNoiseGraphMapper", "apply", "(Lnet/minecraft/world/level/levelgen/DensityFunction;)Lnet/minecraft/world/level/levelgen/DensityFunction;", false);
        methodVisitor.visitInsn(ARETURN);
        methodVisitor.visitMaxs(3, 2);
        methodVisitor.visitEnd();
    }

    private static void emitMapRouter(ClassWriter classWriter) {
        MethodVisitor methodVisitor = classWriter.visitMethod(ACC_STATIC, "mapRouter", "(Lnet/minecraft/world/level/levelgen/NoiseRouter;Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)Lnet/minecraft/world/level/levelgen/NoiseRouter;", null, null);
        methodVisitor.visitCode();
        Label label0 = new Label();
        methodVisitor.visitLabel(label0);
        methodVisitor.visitLineNumber(28, label0);
        methodVisitor.visitTypeInsn(NEW, "net/minecraft/world/level/levelgen/BrowserNoiseGraphMapper");
        methodVisitor.visitInsn(DUP);
        methodVisitor.visitVarInsn(ALOAD, 1);
        methodVisitor.visitMethodInsn(INVOKESPECIAL, "net/minecraft/world/level/levelgen/BrowserNoiseGraphMapper", "<init>", "(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)V", false);
        methodVisitor.visitVarInsn(ASTORE, 2);
        Label label1 = new Label();
        methodVisitor.visitLabel(label1);
        methodVisitor.visitLineNumber(29, label1);
        methodVisitor.visitTypeInsn(NEW, "net/minecraft/world/level/levelgen/NoiseRouter");
        methodVisitor.visitInsn(DUP);
        methodVisitor.visitVarInsn(ALOAD, 2);
        methodVisitor.visitVarInsn(ALOAD, 0);
        Label label2 = new Label();
        methodVisitor.visitLabel(label2);
        methodVisitor.visitLineNumber(30, label2);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/level/levelgen/NoiseRouter", "barrierNoise", "()Lnet/minecraft/world/level/levelgen/DensityFunction;", false);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/level/levelgen/BrowserNoiseGraphMapper", "apply", "(Lnet/minecraft/world/level/levelgen/DensityFunction;)Lnet/minecraft/world/level/levelgen/DensityFunction;", false);
        methodVisitor.visitVarInsn(ALOAD, 2);
        methodVisitor.visitVarInsn(ALOAD, 0);
        Label label3 = new Label();
        methodVisitor.visitLabel(label3);
        methodVisitor.visitLineNumber(31, label3);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/level/levelgen/NoiseRouter", "fluidLevelFloodednessNoise", "()Lnet/minecraft/world/level/levelgen/DensityFunction;", false);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/level/levelgen/BrowserNoiseGraphMapper", "apply", "(Lnet/minecraft/world/level/levelgen/DensityFunction;)Lnet/minecraft/world/level/levelgen/DensityFunction;", false);
        methodVisitor.visitVarInsn(ALOAD, 2);
        methodVisitor.visitVarInsn(ALOAD, 0);
        Label label4 = new Label();
        methodVisitor.visitLabel(label4);
        methodVisitor.visitLineNumber(32, label4);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/level/levelgen/NoiseRouter", "fluidLevelSpreadNoise", "()Lnet/minecraft/world/level/levelgen/DensityFunction;", false);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/level/levelgen/BrowserNoiseGraphMapper", "apply", "(Lnet/minecraft/world/level/levelgen/DensityFunction;)Lnet/minecraft/world/level/levelgen/DensityFunction;", false);
        methodVisitor.visitVarInsn(ALOAD, 2);
        methodVisitor.visitVarInsn(ALOAD, 0);
        Label label5 = new Label();
        methodVisitor.visitLabel(label5);
        methodVisitor.visitLineNumber(33, label5);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/level/levelgen/NoiseRouter", "lavaNoise", "()Lnet/minecraft/world/level/levelgen/DensityFunction;", false);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/level/levelgen/BrowserNoiseGraphMapper", "apply", "(Lnet/minecraft/world/level/levelgen/DensityFunction;)Lnet/minecraft/world/level/levelgen/DensityFunction;", false);
        methodVisitor.visitVarInsn(ALOAD, 2);
        methodVisitor.visitVarInsn(ALOAD, 0);
        Label label6 = new Label();
        methodVisitor.visitLabel(label6);
        methodVisitor.visitLineNumber(34, label6);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/level/levelgen/NoiseRouter", "temperature", "()Lnet/minecraft/world/level/levelgen/DensityFunction;", false);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/level/levelgen/BrowserNoiseGraphMapper", "apply", "(Lnet/minecraft/world/level/levelgen/DensityFunction;)Lnet/minecraft/world/level/levelgen/DensityFunction;", false);
        methodVisitor.visitVarInsn(ALOAD, 2);
        methodVisitor.visitVarInsn(ALOAD, 0);
        Label label7 = new Label();
        methodVisitor.visitLabel(label7);
        methodVisitor.visitLineNumber(35, label7);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/level/levelgen/NoiseRouter", "vegetation", "()Lnet/minecraft/world/level/levelgen/DensityFunction;", false);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/level/levelgen/BrowserNoiseGraphMapper", "apply", "(Lnet/minecraft/world/level/levelgen/DensityFunction;)Lnet/minecraft/world/level/levelgen/DensityFunction;", false);
        methodVisitor.visitVarInsn(ALOAD, 2);
        methodVisitor.visitVarInsn(ALOAD, 0);
        Label label8 = new Label();
        methodVisitor.visitLabel(label8);
        methodVisitor.visitLineNumber(36, label8);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/level/levelgen/NoiseRouter", "continents", "()Lnet/minecraft/world/level/levelgen/DensityFunction;", false);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/level/levelgen/BrowserNoiseGraphMapper", "apply", "(Lnet/minecraft/world/level/levelgen/DensityFunction;)Lnet/minecraft/world/level/levelgen/DensityFunction;", false);
        methodVisitor.visitVarInsn(ALOAD, 2);
        methodVisitor.visitVarInsn(ALOAD, 0);
        Label label9 = new Label();
        methodVisitor.visitLabel(label9);
        methodVisitor.visitLineNumber(37, label9);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/level/levelgen/NoiseRouter", "erosion", "()Lnet/minecraft/world/level/levelgen/DensityFunction;", false);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/level/levelgen/BrowserNoiseGraphMapper", "apply", "(Lnet/minecraft/world/level/levelgen/DensityFunction;)Lnet/minecraft/world/level/levelgen/DensityFunction;", false);
        methodVisitor.visitVarInsn(ALOAD, 2);
        methodVisitor.visitVarInsn(ALOAD, 0);
        Label label10 = new Label();
        methodVisitor.visitLabel(label10);
        methodVisitor.visitLineNumber(38, label10);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/level/levelgen/NoiseRouter", "depth", "()Lnet/minecraft/world/level/levelgen/DensityFunction;", false);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/level/levelgen/BrowserNoiseGraphMapper", "apply", "(Lnet/minecraft/world/level/levelgen/DensityFunction;)Lnet/minecraft/world/level/levelgen/DensityFunction;", false);
        methodVisitor.visitVarInsn(ALOAD, 2);
        methodVisitor.visitVarInsn(ALOAD, 0);
        Label label11 = new Label();
        methodVisitor.visitLabel(label11);
        methodVisitor.visitLineNumber(39, label11);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/level/levelgen/NoiseRouter", "ridges", "()Lnet/minecraft/world/level/levelgen/DensityFunction;", false);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/level/levelgen/BrowserNoiseGraphMapper", "apply", "(Lnet/minecraft/world/level/levelgen/DensityFunction;)Lnet/minecraft/world/level/levelgen/DensityFunction;", false);
        methodVisitor.visitVarInsn(ALOAD, 2);
        methodVisitor.visitVarInsn(ALOAD, 0);
        Label label12 = new Label();
        methodVisitor.visitLabel(label12);
        methodVisitor.visitLineNumber(40, label12);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/level/levelgen/NoiseRouter", "preliminarySurfaceLevel", "()Lnet/minecraft/world/level/levelgen/DensityFunction;", false);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/level/levelgen/BrowserNoiseGraphMapper", "apply", "(Lnet/minecraft/world/level/levelgen/DensityFunction;)Lnet/minecraft/world/level/levelgen/DensityFunction;", false);
        methodVisitor.visitVarInsn(ALOAD, 2);
        methodVisitor.visitVarInsn(ALOAD, 0);
        Label label13 = new Label();
        methodVisitor.visitLabel(label13);
        methodVisitor.visitLineNumber(41, label13);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/level/levelgen/NoiseRouter", "finalDensity", "()Lnet/minecraft/world/level/levelgen/DensityFunction;", false);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/level/levelgen/BrowserNoiseGraphMapper", "apply", "(Lnet/minecraft/world/level/levelgen/DensityFunction;)Lnet/minecraft/world/level/levelgen/DensityFunction;", false);
        methodVisitor.visitVarInsn(ALOAD, 2);
        methodVisitor.visitVarInsn(ALOAD, 0);
        Label label14 = new Label();
        methodVisitor.visitLabel(label14);
        methodVisitor.visitLineNumber(42, label14);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/level/levelgen/NoiseRouter", "veinToggle", "()Lnet/minecraft/world/level/levelgen/DensityFunction;", false);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/level/levelgen/BrowserNoiseGraphMapper", "apply", "(Lnet/minecraft/world/level/levelgen/DensityFunction;)Lnet/minecraft/world/level/levelgen/DensityFunction;", false);
        methodVisitor.visitVarInsn(ALOAD, 2);
        methodVisitor.visitVarInsn(ALOAD, 0);
        Label label15 = new Label();
        methodVisitor.visitLabel(label15);
        methodVisitor.visitLineNumber(43, label15);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/level/levelgen/NoiseRouter", "veinRidged", "()Lnet/minecraft/world/level/levelgen/DensityFunction;", false);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/level/levelgen/BrowserNoiseGraphMapper", "apply", "(Lnet/minecraft/world/level/levelgen/DensityFunction;)Lnet/minecraft/world/level/levelgen/DensityFunction;", false);
        methodVisitor.visitVarInsn(ALOAD, 2);
        methodVisitor.visitVarInsn(ALOAD, 0);
        Label label16 = new Label();
        methodVisitor.visitLabel(label16);
        methodVisitor.visitLineNumber(44, label16);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/level/levelgen/NoiseRouter", "veinGap", "()Lnet/minecraft/world/level/levelgen/DensityFunction;", false);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/level/levelgen/BrowserNoiseGraphMapper", "apply", "(Lnet/minecraft/world/level/levelgen/DensityFunction;)Lnet/minecraft/world/level/levelgen/DensityFunction;", false);
        methodVisitor.visitMethodInsn(INVOKESPECIAL, "net/minecraft/world/level/levelgen/NoiseRouter", "<init>", "(Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/DensityFunction;)V", false);
        Label label17 = new Label();
        methodVisitor.visitLabel(label17);
        methodVisitor.visitLineNumber(29, label17);
        methodVisitor.visitInsn(ARETURN);
        methodVisitor.visitMaxs(18, 3);
        methodVisitor.visitEnd();
    }

    private static void emitApply(ClassWriter classWriter) {
        MethodVisitor methodVisitor = classWriter.visitMethod(ACC_PUBLIC, "apply", "(Lnet/minecraft/world/level/levelgen/DensityFunction;)Lnet/minecraft/world/level/levelgen/DensityFunction;", null, null);
        methodVisitor.visitCode();
        Label label0 = new Label();
        methodVisitor.visitLabel(label0);
        methodVisitor.visitLineNumber(49, label0);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitFieldInsn(GETFIELD, "net/minecraft/world/level/levelgen/BrowserNoiseGraphMapper", "mapped", "Ljava/util/Map;");
        methodVisitor.visitVarInsn(ALOAD, 1);
        methodVisitor.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        methodVisitor.visitTypeInsn(CHECKCAST, "net/minecraft/world/level/levelgen/DensityFunction");
        methodVisitor.visitVarInsn(ASTORE, 2);
        Label label1 = new Label();
        methodVisitor.visitLabel(label1);
        methodVisitor.visitLineNumber(50, label1);
        methodVisitor.visitVarInsn(ALOAD, 2);
        Label label2 = new Label();
        methodVisitor.visitJumpInsn(IFNULL, label2);
        Label label3 = new Label();
        methodVisitor.visitLabel(label3);
        methodVisitor.visitLineNumber(51, label3);
        methodVisitor.visitVarInsn(ALOAD, 2);
        methodVisitor.visitInsn(ARETURN);
        methodVisitor.visitLabel(label2);
        methodVisitor.visitLineNumber(53, label2);
        methodVisitor.visitFrame(Opcodes.F_APPEND,1, new Object[] {"net/minecraft/world/level/levelgen/DensityFunction"}, 0, null);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitFieldInsn(GETFIELD, "net/minecraft/world/level/levelgen/BrowserNoiseGraphMapper", "delegate", "Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;");
        methodVisitor.visitVarInsn(ALOAD, 1);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitMethodInsn(INVOKEINTERFACE, "net/minecraft/world/level/levelgen/DensityFunction", "mapChildren", "(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)Lnet/minecraft/world/level/levelgen/DensityFunction;", true);
        methodVisitor.visitMethodInsn(INVOKEINTERFACE, "net/minecraft/world/level/levelgen/DensityFunction$Visitor", "apply", "(Lnet/minecraft/world/level/levelgen/DensityFunction;)Lnet/minecraft/world/level/levelgen/DensityFunction;", true);
        methodVisitor.visitVarInsn(ASTORE, 3);
        Label label4 = new Label();
        methodVisitor.visitLabel(label4);
        methodVisitor.visitLineNumber(54, label4);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitFieldInsn(GETFIELD, "net/minecraft/world/level/levelgen/BrowserNoiseGraphMapper", "mapped", "Ljava/util/Map;");
        methodVisitor.visitVarInsn(ALOAD, 1);
        methodVisitor.visitVarInsn(ALOAD, 3);
        methodVisitor.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
        methodVisitor.visitInsn(POP);
        Label label5 = new Label();
        methodVisitor.visitLabel(label5);
        methodVisitor.visitLineNumber(55, label5);
        methodVisitor.visitVarInsn(ALOAD, 3);
        methodVisitor.visitInsn(ARETURN);
        methodVisitor.visitMaxs(3, 4);
        methodVisitor.visitEnd();
    }

    private static void emitVisitNoise(ClassWriter classWriter) {
        MethodVisitor methodVisitor = classWriter.visitMethod(ACC_PUBLIC, "visitNoise", "(Lnet/minecraft/world/level/levelgen/DensityFunction$NoiseHolder;)Lnet/minecraft/world/level/levelgen/DensityFunction$NoiseHolder;", null, null);
        methodVisitor.visitCode();
        Label label0 = new Label();
        methodVisitor.visitLabel(label0);
        methodVisitor.visitLineNumber(60, label0);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitFieldInsn(GETFIELD, "net/minecraft/world/level/levelgen/BrowserNoiseGraphMapper", "delegate", "Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;");
        methodVisitor.visitVarInsn(ALOAD, 1);
        methodVisitor.visitMethodInsn(INVOKEINTERFACE, "net/minecraft/world/level/levelgen/DensityFunction$Visitor", "visitNoise", "(Lnet/minecraft/world/level/levelgen/DensityFunction$NoiseHolder;)Lnet/minecraft/world/level/levelgen/DensityFunction$NoiseHolder;", true);
        methodVisitor.visitInsn(ARETURN);
        methodVisitor.visitMaxs(2, 2);
        methodVisitor.visitEnd();
    }
}
