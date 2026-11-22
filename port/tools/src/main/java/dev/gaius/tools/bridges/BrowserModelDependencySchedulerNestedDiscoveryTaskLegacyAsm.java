package dev.gaius.tools.bridges;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** ASM description of the Gaius-authored BrowserModelDependencyScheduler$DiscoveryTask bridge for the legacy model ABI. */
public final class BrowserModelDependencySchedulerNestedDiscoveryTaskLegacyAsm implements Opcodes {
    private BrowserModelDependencySchedulerNestedDiscoveryTaskLegacyAsm() {}

    public static byte[] emit() {
        ClassWriter classWriter = new ClassWriter(0);
        FieldVisitor fieldVisitor;

        classWriter.visit(V21, ACC_FINAL | ACC_SUPER, "net/minecraft/client/resources/model/BrowserModelDependencyScheduler$DiscoveryTask", null, "java/lang/Object", new String[] { "java/lang/Runnable" });

        classWriter.visitSource("BrowserModelDependencyScheduler.java", null);

        classWriter.visitNestHost("net/minecraft/client/resources/model/BrowserModelDependencyScheduler");

        classWriter.visitInnerClass("net/minecraft/client/resources/model/BlockStateModelLoader$LoadedModels", "net/minecraft/client/resources/model/BlockStateModelLoader", "LoadedModels", ACC_PUBLIC | ACC_FINAL | ACC_STATIC);

        classWriter.visitInnerClass("net/minecraft/client/resources/model/BrowserModelDependencyScheduler$DiscoveryTask", "net/minecraft/client/resources/model/BrowserModelDependencyScheduler", "DiscoveryTask", ACC_PRIVATE | ACC_FINAL | ACC_STATIC);

        classWriter.visitInnerClass("net/minecraft/client/resources/model/ClientItemInfoLoader$LoadedClientInfos", "net/minecraft/client/resources/model/ClientItemInfoLoader", "LoadedClientInfos", ACC_PUBLIC | ACC_FINAL | ACC_STATIC);

        classWriter.visitInnerClass("net/minecraft/client/renderer/item/ItemModel$Unbaked", "net/minecraft/client/renderer/item/ItemModel", "Unbaked", ACC_PUBLIC | ACC_STATIC | ACC_ABSTRACT | ACC_INTERFACE);

        classWriter.visitInnerClass("net/minecraft/client/renderer/block/model/BlockStateModel$UnbakedRoot", "net/minecraft/client/renderer/block/model/BlockStateModel", "UnbakedRoot", ACC_PUBLIC | ACC_STATIC | ACC_ABSTRACT | ACC_INTERFACE);

        {
            fieldVisitor = classWriter.visitField(ACC_PRIVATE | ACC_FINAL, "blockRoots", "Ljava/util/Iterator;", "Ljava/util/Iterator<Lnet/minecraft/client/renderer/block/model/BlockStateModel$UnbakedRoot;>;", null);
            fieldVisitor.visitEnd();
        }
        {
            fieldVisitor = classWriter.visitField(ACC_PRIVATE | ACC_FINAL, "itemRoots", "Ljava/util/Iterator;", "Ljava/util/Iterator<Lnet/minecraft/client/renderer/item/ClientItem;>;", null);
            fieldVisitor.visitEnd();
        }
        {
            fieldVisitor = classWriter.visitField(ACC_PRIVATE | ACC_FINAL, "result", "Ljava/util/concurrent/CompletableFuture;", "Ljava/util/concurrent/CompletableFuture<Lnet/minecraft/client/resources/model/ModelDiscovery;>;", null);
            fieldVisitor.visitEnd();
        }
        {
            fieldVisitor = classWriter.visitField(ACC_PRIVATE | ACC_FINAL, "executor", "Ljava/util/concurrent/Executor;", null, null);
            fieldVisitor.visitEnd();
        }
        {
            fieldVisitor = classWriter.visitField(ACC_PRIVATE | ACC_FINAL, "discovery", "Lnet/minecraft/client/resources/model/ModelDiscovery;", null, null);
            fieldVisitor.visitEnd();
        }
        emitConstructor(classWriter);
        emitSchedule(classWriter);
        emitRun(classWriter);
        classWriter.visitEnd();
        return classWriter.toByteArray();
    }

    private static void emitConstructor(ClassWriter classWriter) {
        MethodVisitor methodVisitor = classWriter.visitMethod(ACC_PRIVATE, "<init>", "(Ljava/util/Map;Lnet/minecraft/client/resources/model/BlockStateModelLoader$LoadedModels;Lnet/minecraft/client/resources/model/ClientItemInfoLoader$LoadedClientInfos;Ljava/util/concurrent/CompletableFuture;Ljava/util/concurrent/Executor;)V", "(Ljava/util/Map<Lnet/minecraft/resources/Identifier;Lnet/minecraft/client/resources/model/UnbakedModel;>;Lnet/minecraft/client/resources/model/BlockStateModelLoader$LoadedModels;Lnet/minecraft/client/resources/model/ClientItemInfoLoader$LoadedClientInfos;Ljava/util/concurrent/CompletableFuture<Lnet/minecraft/client/resources/model/ModelDiscovery;>;Ljava/util/concurrent/Executor;)V", null);
        methodVisitor.visitCode();
        Label label0 = new Label();
        methodVisitor.visitLabel(label0);
        methodVisitor.visitLineNumber(57, label0);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        Label label1 = new Label();
        methodVisitor.visitLabel(label1);
        methodVisitor.visitLineNumber(58, label1);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitVarInsn(ALOAD, 2);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/client/resources/model/BlockStateModelLoader$LoadedModels", "models", "()Ljava/util/Map;", false);
        methodVisitor.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "values", "()Ljava/util/Collection;", true);
        methodVisitor.visitMethodInsn(INVOKEINTERFACE, "java/util/Collection", "iterator", "()Ljava/util/Iterator;", true);
        methodVisitor.visitFieldInsn(PUTFIELD, "net/minecraft/client/resources/model/BrowserModelDependencyScheduler$DiscoveryTask", "blockRoots", "Ljava/util/Iterator;");
        Label label2 = new Label();
        methodVisitor.visitLabel(label2);
        methodVisitor.visitLineNumber(59, label2);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitVarInsn(ALOAD, 3);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/client/resources/model/ClientItemInfoLoader$LoadedClientInfos", "contents", "()Ljava/util/Map;", false);
        methodVisitor.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "values", "()Ljava/util/Collection;", true);
        methodVisitor.visitMethodInsn(INVOKEINTERFACE, "java/util/Collection", "iterator", "()Ljava/util/Iterator;", true);
        methodVisitor.visitFieldInsn(PUTFIELD, "net/minecraft/client/resources/model/BrowserModelDependencyScheduler$DiscoveryTask", "itemRoots", "Ljava/util/Iterator;");
        Label label3 = new Label();
        methodVisitor.visitLabel(label3);
        methodVisitor.visitLineNumber(60, label3);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitVarInsn(ALOAD, 4);
        methodVisitor.visitFieldInsn(PUTFIELD, "net/minecraft/client/resources/model/BrowserModelDependencyScheduler$DiscoveryTask", "result", "Ljava/util/concurrent/CompletableFuture;");
        Label label4 = new Label();
        methodVisitor.visitLabel(label4);
        methodVisitor.visitLineNumber(61, label4);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitVarInsn(ALOAD, 5);
        methodVisitor.visitFieldInsn(PUTFIELD, "net/minecraft/client/resources/model/BrowserModelDependencyScheduler$DiscoveryTask", "executor", "Ljava/util/concurrent/Executor;");
        Label label5 = new Label();
        methodVisitor.visitLabel(label5);
        methodVisitor.visitLineNumber(62, label5);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitTypeInsn(NEW, "net/minecraft/client/resources/model/ModelDiscovery");
        methodVisitor.visitInsn(DUP);
        methodVisitor.visitVarInsn(ALOAD, 1);
        methodVisitor.visitMethodInsn(INVOKESTATIC, "net/minecraft/client/resources/model/MissingBlockModel", "missingModel", "()Lnet/minecraft/client/resources/model/UnbakedModel;", false);
        methodVisitor.visitMethodInsn(INVOKESPECIAL, "net/minecraft/client/resources/model/ModelDiscovery", "<init>", "(Ljava/util/Map;Lnet/minecraft/client/resources/model/UnbakedModel;)V", false);
        methodVisitor.visitFieldInsn(PUTFIELD, "net/minecraft/client/resources/model/BrowserModelDependencyScheduler$DiscoveryTask", "discovery", "Lnet/minecraft/client/resources/model/ModelDiscovery;");
        Label label6 = new Label();
        methodVisitor.visitLabel(label6);
        methodVisitor.visitLineNumber(63, label6);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitFieldInsn(GETFIELD, "net/minecraft/client/resources/model/BrowserModelDependencyScheduler$DiscoveryTask", "discovery", "Lnet/minecraft/client/resources/model/ModelDiscovery;");
        methodVisitor.visitFieldInsn(GETSTATIC, "net/minecraft/client/renderer/block/model/ItemModelGenerator", "GENERATED_ITEM_MODEL_ID", "Lnet/minecraft/resources/Identifier;");
        methodVisitor.visitTypeInsn(NEW, "net/minecraft/client/renderer/block/model/ItemModelGenerator");
        methodVisitor.visitInsn(DUP);
        methodVisitor.visitMethodInsn(INVOKESPECIAL, "net/minecraft/client/renderer/block/model/ItemModelGenerator", "<init>", "()V", false);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/client/resources/model/ModelDiscovery", "addSpecialModel", "(Lnet/minecraft/resources/Identifier;Lnet/minecraft/client/resources/model/UnbakedModel;)V", false);
        Label label7 = new Label();
        methodVisitor.visitLabel(label7);
        methodVisitor.visitLineNumber(66, label7);
        methodVisitor.visitInsn(RETURN);
        methodVisitor.visitMaxs(5, 6);
        methodVisitor.visitEnd();
    }

    private static void emitSchedule(ClassWriter classWriter) {
        MethodVisitor methodVisitor = classWriter.visitMethod(ACC_PRIVATE, "schedule", "()V", null, null);
        methodVisitor.visitCode();
        Label label0 = new Label();
        methodVisitor.visitLabel(label0);
        methodVisitor.visitLineNumber(69, label0);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitFieldInsn(GETFIELD, "net/minecraft/client/resources/model/BrowserModelDependencyScheduler$DiscoveryTask", "executor", "Ljava/util/concurrent/Executor;");
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitMethodInsn(INVOKEINTERFACE, "java/util/concurrent/Executor", "execute", "(Ljava/lang/Runnable;)V", true);
        Label label1 = new Label();
        methodVisitor.visitLabel(label1);
        methodVisitor.visitLineNumber(70, label1);
        methodVisitor.visitInsn(RETURN);
        methodVisitor.visitMaxs(2, 1);
        methodVisitor.visitEnd();
    }

    private static void emitRun(ClassWriter classWriter) {
        MethodVisitor methodVisitor = classWriter.visitMethod(ACC_PUBLIC, "run", "()V", null, null);
        methodVisitor.visitCode();
        Label label0 = new Label();
        Label label1 = new Label();
        Label label2 = new Label();
        methodVisitor.visitTryCatchBlock(label0, label1, label2, "java/lang/Throwable");
        Label label3 = new Label();
        Label label4 = new Label();
        methodVisitor.visitTryCatchBlock(label3, label4, label2, "java/lang/Throwable");
        Label label5 = new Label();
        Label label6 = new Label();
        methodVisitor.visitTryCatchBlock(label5, label6, label2, "java/lang/Throwable");
        Label label7 = new Label();
        methodVisitor.visitLabel(label7);
        methodVisitor.visitLineNumber(74, label7);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitFieldInsn(GETFIELD, "net/minecraft/client/resources/model/BrowserModelDependencyScheduler$DiscoveryTask", "result", "Ljava/util/concurrent/CompletableFuture;");
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/util/concurrent/CompletableFuture", "isDone", "()Z", false);
        Label label8 = new Label();
        methodVisitor.visitJumpInsn(IFEQ, label8);
        Label label9 = new Label();
        methodVisitor.visitLabel(label9);
        methodVisitor.visitLineNumber(75, label9);
        methodVisitor.visitInsn(RETURN);
        methodVisitor.visitLabel(label8);
        methodVisitor.visitLineNumber(77, label8);
        methodVisitor.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        methodVisitor.visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
        methodVisitor.visitVarInsn(LSTORE, 1);
        methodVisitor.visitLabel(label0);
        methodVisitor.visitLineNumber(79, label0);
        methodVisitor.visitFrame(Opcodes.F_APPEND,1, new Object[] {Opcodes.LONG}, 0, null);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitFieldInsn(GETFIELD, "net/minecraft/client/resources/model/BrowserModelDependencyScheduler$DiscoveryTask", "blockRoots", "Ljava/util/Iterator;");
        methodVisitor.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
        methodVisitor.visitJumpInsn(IFEQ, label3);
        Label label10 = new Label();
        methodVisitor.visitLabel(label10);
        methodVisitor.visitLineNumber(80, label10);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitFieldInsn(GETFIELD, "net/minecraft/client/resources/model/BrowserModelDependencyScheduler$DiscoveryTask", "discovery", "Lnet/minecraft/client/resources/model/ModelDiscovery;");
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitFieldInsn(GETFIELD, "net/minecraft/client/resources/model/BrowserModelDependencyScheduler$DiscoveryTask", "blockRoots", "Ljava/util/Iterator;");
        methodVisitor.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
        methodVisitor.visitTypeInsn(CHECKCAST, "net/minecraft/client/resources/model/ResolvableModel");
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/client/resources/model/ModelDiscovery", "addRoot", "(Lnet/minecraft/client/resources/model/ResolvableModel;)V", false);
        Label label11 = new Label();
        methodVisitor.visitLabel(label11);
        methodVisitor.visitLineNumber(81, label11);
        methodVisitor.visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
        methodVisitor.visitVarInsn(LLOAD, 1);
        methodVisitor.visitInsn(LSUB);
        methodVisitor.visitLdcInsn(Long.valueOf(4000000L));
        methodVisitor.visitInsn(LCMP);
        methodVisitor.visitJumpInsn(IFLT, label0);
        Label label12 = new Label();
        methodVisitor.visitLabel(label12);
        methodVisitor.visitLineNumber(82, label12);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/client/resources/model/BrowserModelDependencyScheduler$DiscoveryTask", "schedule", "()V", false);
        methodVisitor.visitLabel(label1);
        methodVisitor.visitLineNumber(83, label1);
        methodVisitor.visitInsn(RETURN);
        methodVisitor.visitLabel(label3);
        methodVisitor.visitLineNumber(86, label3);
        methodVisitor.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitFieldInsn(GETFIELD, "net/minecraft/client/resources/model/BrowserModelDependencyScheduler$DiscoveryTask", "itemRoots", "Ljava/util/Iterator;");
        methodVisitor.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
        methodVisitor.visitJumpInsn(IFEQ, label5);
        Label label13 = new Label();
        methodVisitor.visitLabel(label13);
        methodVisitor.visitLineNumber(87, label13);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitFieldInsn(GETFIELD, "net/minecraft/client/resources/model/BrowserModelDependencyScheduler$DiscoveryTask", "discovery", "Lnet/minecraft/client/resources/model/ModelDiscovery;");
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitFieldInsn(GETFIELD, "net/minecraft/client/resources/model/BrowserModelDependencyScheduler$DiscoveryTask", "itemRoots", "Ljava/util/Iterator;");
        methodVisitor.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
        methodVisitor.visitTypeInsn(CHECKCAST, "net/minecraft/client/renderer/item/ClientItem");
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/client/renderer/item/ClientItem", "model", "()Lnet/minecraft/client/renderer/item/ItemModel$Unbaked;", false);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/client/resources/model/ModelDiscovery", "addRoot", "(Lnet/minecraft/client/resources/model/ResolvableModel;)V", false);
        Label label14 = new Label();
        methodVisitor.visitLabel(label14);
        methodVisitor.visitLineNumber(88, label14);
        methodVisitor.visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
        methodVisitor.visitVarInsn(LLOAD, 1);
        methodVisitor.visitInsn(LSUB);
        methodVisitor.visitLdcInsn(Long.valueOf(4000000L));
        methodVisitor.visitInsn(LCMP);
        methodVisitor.visitJumpInsn(IFLT, label3);
        Label label15 = new Label();
        methodVisitor.visitLabel(label15);
        methodVisitor.visitLineNumber(89, label15);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/client/resources/model/BrowserModelDependencyScheduler$DiscoveryTask", "schedule", "()V", false);
        methodVisitor.visitLabel(label4);
        methodVisitor.visitLineNumber(90, label4);
        methodVisitor.visitInsn(RETURN);
        methodVisitor.visitLabel(label5);
        methodVisitor.visitLineNumber(93, label5);
        methodVisitor.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitFieldInsn(GETFIELD, "net/minecraft/client/resources/model/BrowserModelDependencyScheduler$DiscoveryTask", "result", "Ljava/util/concurrent/CompletableFuture;");
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitFieldInsn(GETFIELD, "net/minecraft/client/resources/model/BrowserModelDependencyScheduler$DiscoveryTask", "discovery", "Lnet/minecraft/client/resources/model/ModelDiscovery;");
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/util/concurrent/CompletableFuture", "complete", "(Ljava/lang/Object;)Z", false);
        methodVisitor.visitInsn(POP);
        methodVisitor.visitLabel(label6);
        methodVisitor.visitLineNumber(96, label6);
        Label label16 = new Label();
        methodVisitor.visitJumpInsn(GOTO, label16);
        methodVisitor.visitLabel(label2);
        methodVisitor.visitLineNumber(94, label2);
        methodVisitor.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] {"java/lang/Throwable"});
        methodVisitor.visitVarInsn(ASTORE, 3);
        Label label17 = new Label();
        methodVisitor.visitLabel(label17);
        methodVisitor.visitLineNumber(95, label17);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitFieldInsn(GETFIELD, "net/minecraft/client/resources/model/BrowserModelDependencyScheduler$DiscoveryTask", "result", "Ljava/util/concurrent/CompletableFuture;");
        methodVisitor.visitVarInsn(ALOAD, 3);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/util/concurrent/CompletableFuture", "completeExceptionally", "(Ljava/lang/Throwable;)Z", false);
        methodVisitor.visitInsn(POP);
        methodVisitor.visitLabel(label16);
        methodVisitor.visitLineNumber(97, label16);
        methodVisitor.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        methodVisitor.visitInsn(RETURN);
        methodVisitor.visitMaxs(4, 4);
        methodVisitor.visitEnd();
    }
}
