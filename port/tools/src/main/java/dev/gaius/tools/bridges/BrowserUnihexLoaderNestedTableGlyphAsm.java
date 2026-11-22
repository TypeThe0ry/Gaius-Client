package dev.gaius.tools.bridges;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** ASM description of the Gaius-authored BrowserUnihexLoader$TableGlyph bridge. */
public final class BrowserUnihexLoaderNestedTableGlyphAsm implements Opcodes {
    private BrowserUnihexLoaderNestedTableGlyphAsm() {}

    public static byte[] emit() {
        ClassWriter classWriter = new ClassWriter(0);
        FieldVisitor fieldVisitor;

        classWriter.visit(V21, ACC_FINAL | ACC_SUPER, "net/minecraft/client/gui/font/providers/BrowserUnihexLoader$TableGlyph", null, "java/lang/Object", new String[] { "com/mojang/blaze3d/font/UnbakedGlyph", "net/minecraft/client/gui/font/providers/UnihexProvider$LineData" });

        classWriter.visitSource("BrowserUnihexLoader.java", null);

        classWriter.visitNestHost("net/minecraft/client/gui/font/providers/BrowserUnihexLoader");

        classWriter.visitInnerClass("net/minecraft/client/gui/font/providers/BrowserUnihexLoader$TableGlyph", "net/minecraft/client/gui/font/providers/BrowserUnihexLoader", "TableGlyph", ACC_PRIVATE | ACC_FINAL | ACC_STATIC);

        classWriter.visitInnerClass("dev/gaius/browser/UnihexGlyphTable$Zip", "dev/gaius/browser/UnihexGlyphTable", "Zip", ACC_PUBLIC | ACC_FINAL | ACC_STATIC);

        classWriter.visitInnerClass("com/mojang/blaze3d/font/UnbakedGlyph$Stitcher", "com/mojang/blaze3d/font/UnbakedGlyph", "Stitcher", ACC_PUBLIC | ACC_STATIC | ACC_ABSTRACT | ACC_INTERFACE);

        classWriter.visitInnerClass("net/minecraft/client/gui/font/providers/UnihexProvider$LineData", "net/minecraft/client/gui/font/providers/UnihexProvider", "LineData", ACC_PUBLIC | ACC_STATIC | ACC_ABSTRACT | ACC_INTERFACE);

        {
            fieldVisitor = classWriter.visitField(ACC_PRIVATE | ACC_FINAL, "zip", "Ldev/gaius/browser/UnihexGlyphTable$Zip;", null, null);
            fieldVisitor.visitEnd();
        }
        {
            fieldVisitor = classWriter.visitField(ACC_PRIVATE | ACC_FINAL, "glyph", "I", null, null);
            fieldVisitor.visitEnd();
        }
        {
            fieldVisitor = classWriter.visitField(ACC_PRIVATE | ACC_FINAL, "left", "I", null, null);
            fieldVisitor.visitEnd();
        }
        {
            fieldVisitor = classWriter.visitField(ACC_PRIVATE | ACC_FINAL, "right", "I", null, null);
            fieldVisitor.visitEnd();
        }
        emitConstructor(classWriter);
        emitLine(classWriter);
        emitBitWidth(classWriter);
        emitInfo(classWriter);
        emitBake(classWriter);
        classWriter.visitEnd();
        return classWriter.toByteArray();
    }

    private static void emitConstructor(ClassWriter classWriter) {
        MethodVisitor methodVisitor = classWriter.visitMethod(ACC_PRIVATE, "<init>", "(Ldev/gaius/browser/UnihexGlyphTable$Zip;III)V", null, null);
        methodVisitor.visitCode();
        Label label0 = new Label();
        methodVisitor.visitLabel(label0);
        methodVisitor.visitLineNumber(292, label0);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        Label label1 = new Label();
        methodVisitor.visitLabel(label1);
        methodVisitor.visitLineNumber(293, label1);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitVarInsn(ALOAD, 1);
        methodVisitor.visitFieldInsn(PUTFIELD, "net/minecraft/client/gui/font/providers/BrowserUnihexLoader$TableGlyph", "zip", "Ldev/gaius/browser/UnihexGlyphTable$Zip;");
        Label label2 = new Label();
        methodVisitor.visitLabel(label2);
        methodVisitor.visitLineNumber(294, label2);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitVarInsn(ILOAD, 2);
        methodVisitor.visitFieldInsn(PUTFIELD, "net/minecraft/client/gui/font/providers/BrowserUnihexLoader$TableGlyph", "glyph", "I");
        Label label3 = new Label();
        methodVisitor.visitLabel(label3);
        methodVisitor.visitLineNumber(295, label3);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitVarInsn(ILOAD, 3);
        methodVisitor.visitFieldInsn(PUTFIELD, "net/minecraft/client/gui/font/providers/BrowserUnihexLoader$TableGlyph", "left", "I");
        Label label4 = new Label();
        methodVisitor.visitLabel(label4);
        methodVisitor.visitLineNumber(296, label4);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitVarInsn(ILOAD, 4);
        methodVisitor.visitFieldInsn(PUTFIELD, "net/minecraft/client/gui/font/providers/BrowserUnihexLoader$TableGlyph", "right", "I");
        Label label5 = new Label();
        methodVisitor.visitLabel(label5);
        methodVisitor.visitLineNumber(297, label5);
        methodVisitor.visitInsn(RETURN);
        Label label6 = new Label();
        methodVisitor.visitLabel(label6);
        methodVisitor.visitLocalVariable("this", "Lnet/minecraft/client/gui/font/providers/BrowserUnihexLoader$TableGlyph;", null, label0, label6, 0);
        methodVisitor.visitLocalVariable("zip", "Ldev/gaius/browser/UnihexGlyphTable$Zip;", null, label0, label6, 1);
        methodVisitor.visitLocalVariable("glyph", "I", null, label0, label6, 2);
        methodVisitor.visitLocalVariable("left", "I", null, label0, label6, 3);
        methodVisitor.visitLocalVariable("right", "I", null, label0, label6, 4);
        methodVisitor.visitMaxs(2, 5);
        methodVisitor.visitEnd();
    }

    private static void emitLine(ClassWriter classWriter) {
        MethodVisitor methodVisitor = classWriter.visitMethod(ACC_PUBLIC, "line", "(I)I", null, null);
        methodVisitor.visitCode();
        Label label0 = new Label();
        methodVisitor.visitLabel(label0);
        methodVisitor.visitLineNumber(301, label0);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitFieldInsn(GETFIELD, "net/minecraft/client/gui/font/providers/BrowserUnihexLoader$TableGlyph", "zip", "Ldev/gaius/browser/UnihexGlyphTable$Zip;");
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitFieldInsn(GETFIELD, "net/minecraft/client/gui/font/providers/BrowserUnihexLoader$TableGlyph", "glyph", "I");
        methodVisitor.visitVarInsn(ILOAD, 1);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "dev/gaius/browser/UnihexGlyphTable$Zip", "line", "(II)I", false);
        methodVisitor.visitInsn(IRETURN);
        Label label1 = new Label();
        methodVisitor.visitLabel(label1);
        methodVisitor.visitLocalVariable("this", "Lnet/minecraft/client/gui/font/providers/BrowserUnihexLoader$TableGlyph;", null, label0, label1, 0);
        methodVisitor.visitLocalVariable("row", "I", null, label0, label1, 1);
        methodVisitor.visitMaxs(3, 2);
        methodVisitor.visitEnd();
    }

    private static void emitBitWidth(ClassWriter classWriter) {
        MethodVisitor methodVisitor = classWriter.visitMethod(ACC_PUBLIC, "bitWidth", "()I", null, null);
        methodVisitor.visitCode();
        Label label0 = new Label();
        methodVisitor.visitLabel(label0);
        methodVisitor.visitLineNumber(306, label0);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitFieldInsn(GETFIELD, "net/minecraft/client/gui/font/providers/BrowserUnihexLoader$TableGlyph", "zip", "Ldev/gaius/browser/UnihexGlyphTable$Zip;");
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitFieldInsn(GETFIELD, "net/minecraft/client/gui/font/providers/BrowserUnihexLoader$TableGlyph", "glyph", "I");
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "dev/gaius/browser/UnihexGlyphTable$Zip", "bitWidth", "(I)I", false);
        methodVisitor.visitInsn(IRETURN);
        Label label1 = new Label();
        methodVisitor.visitLabel(label1);
        methodVisitor.visitLocalVariable("this", "Lnet/minecraft/client/gui/font/providers/BrowserUnihexLoader$TableGlyph;", null, label0, label1, 0);
        methodVisitor.visitMaxs(2, 1);
        methodVisitor.visitEnd();
    }

    private static void emitInfo(ClassWriter classWriter) {
        MethodVisitor methodVisitor = classWriter.visitMethod(ACC_PUBLIC, "info", "()Lcom/mojang/blaze3d/font/GlyphInfo;", null, null);
        methodVisitor.visitCode();
        Label label0 = new Label();
        methodVisitor.visitLabel(label0);
        methodVisitor.visitLineNumber(311, label0);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitFieldInsn(GETFIELD, "net/minecraft/client/gui/font/providers/BrowserUnihexLoader$TableGlyph", "left", "I");
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitFieldInsn(GETFIELD, "net/minecraft/client/gui/font/providers/BrowserUnihexLoader$TableGlyph", "right", "I");
        methodVisitor.visitMethodInsn(INVOKESTATIC, "net/minecraft/client/gui/font/providers/BrowserUnihexLoader", "glyphInfo", "(II)Lcom/mojang/blaze3d/font/GlyphInfo;", false);
        methodVisitor.visitInsn(ARETURN);
        Label label1 = new Label();
        methodVisitor.visitLabel(label1);
        methodVisitor.visitLocalVariable("this", "Lnet/minecraft/client/gui/font/providers/BrowserUnihexLoader$TableGlyph;", null, label0, label1, 0);
        methodVisitor.visitMaxs(2, 1);
        methodVisitor.visitEnd();
    }

    private static void emitBake(ClassWriter classWriter) {
        MethodVisitor methodVisitor = classWriter.visitMethod(ACC_PUBLIC, "bake", "(Lcom/mojang/blaze3d/font/UnbakedGlyph$Stitcher;)Lnet/minecraft/client/gui/font/glyphs/BakedGlyph;", null, null);
        methodVisitor.visitCode();
        Label label0 = new Label();
        methodVisitor.visitLabel(label0);
        methodVisitor.visitLineNumber(316, label0);
        methodVisitor.visitVarInsn(ALOAD, 1);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitFieldInsn(GETFIELD, "net/minecraft/client/gui/font/providers/BrowserUnihexLoader$TableGlyph", "left", "I");
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitFieldInsn(GETFIELD, "net/minecraft/client/gui/font/providers/BrowserUnihexLoader$TableGlyph", "right", "I");
        methodVisitor.visitMethodInsn(INVOKESTATIC, "net/minecraft/client/gui/font/providers/BrowserUnihexLoader", "bakeGlyph", "(Lcom/mojang/blaze3d/font/UnbakedGlyph$Stitcher;Lnet/minecraft/client/gui/font/providers/UnihexProvider$LineData;II)Lnet/minecraft/client/gui/font/glyphs/BakedGlyph;", false);
        methodVisitor.visitInsn(ARETURN);
        Label label1 = new Label();
        methodVisitor.visitLabel(label1);
        methodVisitor.visitLocalVariable("this", "Lnet/minecraft/client/gui/font/providers/BrowserUnihexLoader$TableGlyph;", null, label0, label1, 0);
        methodVisitor.visitLocalVariable("stitcher", "Lcom/mojang/blaze3d/font/UnbakedGlyph$Stitcher;", null, label0, label1, 1);
        methodVisitor.visitMaxs(4, 2);
        methodVisitor.visitEnd();
    }
}
