package dev.gaius.tools.bridges;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** ASM description of the Gaius-authored BrowserModelDependencyScheduler bridge for the legacy model ABI. */
public final class BrowserModelDependencySchedulerLegacyAsm implements Opcodes {
    private BrowserModelDependencySchedulerLegacyAsm() {}

    public static byte[] emit() {
        ClassWriter classWriter = new ClassWriter(0);
        FieldVisitor fieldVisitor;

        classWriter.visit(V21, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, "net/minecraft/client/resources/model/BrowserModelDependencyScheduler", null, "java/lang/Object", null);

        classWriter.visitSource("BrowserModelDependencyScheduler.java", null);

        classWriter.visitNestMember("net/minecraft/client/resources/model/BrowserModelDependencyScheduler$DiscoveryTask");

        classWriter.visitInnerClass("net/minecraft/client/resources/model/BrowserModelDependencyScheduler$DiscoveryTask", "net/minecraft/client/resources/model/BrowserModelDependencyScheduler", "DiscoveryTask", ACC_PRIVATE | ACC_FINAL | ACC_STATIC);

        classWriter.visitInnerClass("net/minecraft/client/resources/model/BlockStateModelLoader$LoadedModels", "net/minecraft/client/resources/model/BlockStateModelLoader", "LoadedModels", ACC_PUBLIC | ACC_FINAL | ACC_STATIC);

        classWriter.visitInnerClass("net/minecraft/client/resources/model/ClientItemInfoLoader$LoadedClientInfos", "net/minecraft/client/resources/model/ClientItemInfoLoader", "LoadedClientInfos", ACC_PUBLIC | ACC_FINAL | ACC_STATIC);

        classWriter.visitInnerClass("java/lang/invoke/MethodHandles$Lookup", "java/lang/invoke/MethodHandles", "Lookup", ACC_PUBLIC | ACC_FINAL | ACC_STATIC);

        {
            fieldVisitor = classWriter.visitField(ACC_PRIVATE | ACC_FINAL | ACC_STATIC, "WORK_BUDGET_NANOS", "J", null, Long.valueOf(4000000L));
            fieldVisitor.visitEnd();
        }
        emitConstructor(classWriter);
        emitContinuation(classWriter);
        emitDiscoverAsync(classWriter);
        emitLambda_continuation_0(classWriter);
        classWriter.visitEnd();
        return classWriter.toByteArray();
    }

    private static void emitConstructor(ClassWriter classWriter) {
        MethodVisitor methodVisitor = classWriter.visitMethod(ACC_PRIVATE, "<init>", "()V", null, null);
        methodVisitor.visitCode();
        Label label0 = new Label();
        methodVisitor.visitLabel(label0);
        methodVisitor.visitLineNumber(23, label0);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        Label label1 = new Label();
        methodVisitor.visitLabel(label1);
        methodVisitor.visitLineNumber(24, label1);
        methodVisitor.visitInsn(RETURN);
        methodVisitor.visitMaxs(1, 1);
        methodVisitor.visitEnd();
    }

    private static void emitContinuation(ClassWriter classWriter) {
        MethodVisitor methodVisitor = classWriter.visitMethod(ACC_PUBLIC | ACC_STATIC, "continuation", "(Ljava/util/concurrent/CompletableFuture;Ljava/util/concurrent/CompletableFuture;Ljava/util/concurrent/CompletableFuture;Ljava/util/concurrent/Executor;)Ljava/util/function/Function;", "(Ljava/util/concurrent/CompletableFuture<Ljava/util/Map<Lnet/minecraft/resources/Identifier;Lnet/minecraft/client/resources/model/UnbakedModel;>;>;Ljava/util/concurrent/CompletableFuture<Lnet/minecraft/client/resources/model/BlockStateModelLoader$LoadedModels;>;Ljava/util/concurrent/CompletableFuture<Lnet/minecraft/client/resources/model/ClientItemInfoLoader$LoadedClientInfos;>;Ljava/util/concurrent/Executor;)Ljava/util/function/Function<Ljava/lang/Void;Ljava/util/concurrent/CompletableFuture<Lnet/minecraft/client/resources/model/ModelDiscovery;>;>;", null);
        methodVisitor.visitCode();
        Label label0 = new Label();
        methodVisitor.visitLabel(label0);
        methodVisitor.visitLineNumber(31, label0);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitVarInsn(ALOAD, 1);
        methodVisitor.visitVarInsn(ALOAD, 2);
        methodVisitor.visitVarInsn(ALOAD, 3);
        methodVisitor.visitInvokeDynamicInsn("apply", "(Ljava/util/concurrent/CompletableFuture;Ljava/util/concurrent/CompletableFuture;Ljava/util/concurrent/CompletableFuture;Ljava/util/concurrent/Executor;)Ljava/util/function/Function;", new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory", "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false), new Object[]{Type.getType("(Ljava/lang/Object;)Ljava/lang/Object;"), new Handle(Opcodes.H_INVOKESTATIC, "net/minecraft/client/resources/model/BrowserModelDependencyScheduler", "lambda$continuation$0", "(Ljava/util/concurrent/CompletableFuture;Ljava/util/concurrent/CompletableFuture;Ljava/util/concurrent/CompletableFuture;Ljava/util/concurrent/Executor;Ljava/lang/Void;)Ljava/util/concurrent/CompletableFuture;", false), Type.getType("(Ljava/lang/Void;)Ljava/util/concurrent/CompletableFuture;")});
        methodVisitor.visitInsn(ARETURN);
        methodVisitor.visitMaxs(4, 4);
        methodVisitor.visitEnd();
    }

    private static void emitDiscoverAsync(ClassWriter classWriter) {
        MethodVisitor methodVisitor = classWriter.visitMethod(ACC_PRIVATE | ACC_STATIC, "discoverAsync", "(Ljava/util/Map;Lnet/minecraft/client/resources/model/BlockStateModelLoader$LoadedModels;Lnet/minecraft/client/resources/model/ClientItemInfoLoader$LoadedClientInfos;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;", "(Ljava/util/Map<Lnet/minecraft/resources/Identifier;Lnet/minecraft/client/resources/model/UnbakedModel;>;Lnet/minecraft/client/resources/model/BlockStateModelLoader$LoadedModels;Lnet/minecraft/client/resources/model/ClientItemInfoLoader$LoadedClientInfos;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture<Lnet/minecraft/client/resources/model/ModelDiscovery;>;", null);
        methodVisitor.visitCode();
        Label label0 = new Label();
        methodVisitor.visitLabel(label0);
        methodVisitor.visitLineNumber(39, label0);
        methodVisitor.visitTypeInsn(NEW, "java/util/concurrent/CompletableFuture");
        methodVisitor.visitInsn(DUP);
        methodVisitor.visitMethodInsn(INVOKESPECIAL, "java/util/concurrent/CompletableFuture", "<init>", "()V", false);
        methodVisitor.visitVarInsn(ASTORE, 4);
        Label label1 = new Label();
        methodVisitor.visitLabel(label1);
        methodVisitor.visitLineNumber(40, label1);
        methodVisitor.visitTypeInsn(NEW, "net/minecraft/client/resources/model/BrowserModelDependencyScheduler$DiscoveryTask");
        methodVisitor.visitInsn(DUP);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitVarInsn(ALOAD, 1);
        methodVisitor.visitVarInsn(ALOAD, 2);
        methodVisitor.visitVarInsn(ALOAD, 4);
        methodVisitor.visitVarInsn(ALOAD, 3);
        methodVisitor.visitMethodInsn(INVOKESPECIAL, "net/minecraft/client/resources/model/BrowserModelDependencyScheduler$DiscoveryTask", "<init>", "(Ljava/util/Map;Lnet/minecraft/client/resources/model/BlockStateModelLoader$LoadedModels;Lnet/minecraft/client/resources/model/ClientItemInfoLoader$LoadedClientInfos;Ljava/util/concurrent/CompletableFuture;Ljava/util/concurrent/Executor;)V", false);
        methodVisitor.visitVarInsn(ASTORE, 5);
        Label label2 = new Label();
        methodVisitor.visitLabel(label2);
        methodVisitor.visitLineNumber(41, label2);
        methodVisitor.visitVarInsn(ALOAD, 5);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/client/resources/model/BrowserModelDependencyScheduler$DiscoveryTask", "schedule", "()V", false);
        Label label3 = new Label();
        methodVisitor.visitLabel(label3);
        methodVisitor.visitLineNumber(42, label3);
        methodVisitor.visitVarInsn(ALOAD, 4);
        methodVisitor.visitInsn(ARETURN);
        methodVisitor.visitMaxs(7, 6);
        methodVisitor.visitEnd();
    }

    private static void emitLambda_continuation_0(ClassWriter classWriter) {
        MethodVisitor methodVisitor = classWriter.visitMethod(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, "lambda$continuation$0", "(Ljava/util/concurrent/CompletableFuture;Ljava/util/concurrent/CompletableFuture;Ljava/util/concurrent/CompletableFuture;Ljava/util/concurrent/Executor;Ljava/lang/Void;)Ljava/util/concurrent/CompletableFuture;", null, null);
        methodVisitor.visitCode();
        Label label0 = new Label();
        methodVisitor.visitLabel(label0);
        methodVisitor.visitLineNumber(31, label0);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/util/concurrent/CompletableFuture", "join", "()Ljava/lang/Object;", false);
        methodVisitor.visitTypeInsn(CHECKCAST, "java/util/Map");
        methodVisitor.visitVarInsn(ALOAD, 1);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/util/concurrent/CompletableFuture", "join", "()Ljava/lang/Object;", false);
        methodVisitor.visitTypeInsn(CHECKCAST, "net/minecraft/client/resources/model/BlockStateModelLoader$LoadedModels");
        methodVisitor.visitVarInsn(ALOAD, 2);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/util/concurrent/CompletableFuture", "join", "()Ljava/lang/Object;", false);
        methodVisitor.visitTypeInsn(CHECKCAST, "net/minecraft/client/resources/model/ClientItemInfoLoader$LoadedClientInfos");
        methodVisitor.visitVarInsn(ALOAD, 3);
        methodVisitor.visitMethodInsn(INVOKESTATIC, "net/minecraft/client/resources/model/BrowserModelDependencyScheduler", "discoverAsync", "(Ljava/util/Map;Lnet/minecraft/client/resources/model/BlockStateModelLoader$LoadedModels;Lnet/minecraft/client/resources/model/ClientItemInfoLoader$LoadedClientInfos;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;", false);
        methodVisitor.visitInsn(ARETURN);
        methodVisitor.visitMaxs(4, 5);
        methodVisitor.visitEnd();
    }
}
