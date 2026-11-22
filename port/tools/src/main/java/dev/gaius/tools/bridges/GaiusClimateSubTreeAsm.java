package dev.gaius.tools.bridges;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** ASM description of the Gaius-authored GaiusClimateSubTree bridge. */
public final class GaiusClimateSubTreeAsm implements Opcodes {
    private GaiusClimateSubTreeAsm() {}

    public static byte[] emit() {
        ClassWriter classWriter = new ClassWriter(0);

        classWriter.visit(V21, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, "net/minecraft/world/level/biome/GaiusClimateSubTree", null, "java/lang/Object", null);

        classWriter.visitSource("GaiusClimateSubTree.java", null);

        classWriter.visitInnerClass("net/minecraft/world/level/biome/Climate$RTree", "net/minecraft/world/level/biome/Climate", "RTree", ACC_PROTECTED | ACC_FINAL | ACC_STATIC);

        classWriter.visitInnerClass("net/minecraft/world/level/biome/Climate$RTree$Node", "net/minecraft/world/level/biome/Climate$RTree", "Node", ACC_STATIC | ACC_ABSTRACT);

        emitGaius_children(classWriter);
        classWriter.visitEnd();
        return classWriter.toByteArray();
    }

    private static void emitGaius_children(ClassWriter classWriter) {
        MethodVisitor methodVisitor = classWriter.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "gaius$children", "()[Lnet/minecraft/world/level/biome/Climate$RTree$Node;", "()[Lnet/minecraft/world/level/biome/Climate$RTree$Node<*>;", null);
        methodVisitor.visitEnd();
    }
}
