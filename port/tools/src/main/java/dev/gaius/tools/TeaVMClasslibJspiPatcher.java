package dev.gaius.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Routes TeaVM 0.15's thread and monitor primitives to TJspiThreadSupport when the build has
 * the gaius-jspi platform tag (WebAssembly GC JSPI mode, gaius.teavm.jspi).
 *
 * <p>Each patched entry point (the monitor primitives, wait/notify, {@code Thread.start},
 * {@code yield}, {@code sleep} and {@code join(long, int)}) starts with
 * {@code if (JspiHost.enabled()) { delegate; return; }}. TeaVM's PlatformMarkerSupport folds
 * the call to a constant before dependency analysis, so a JSPI build never reaches the stock
 * {@code @Async} primitives (monitorEnterWait, waitImpl, switchContext, the sleep callback)
 * and every other build never reaches TJspiThreadSupport. {@code Thread.sleep(long)} is an {@code @Async} native method, so the
 * stock pair moves to {@code gaiusSleepAsync} and {@code sleep} becomes a plain method that
 * picks one of the two.
 *
 * <p>TJspiThreadSupport is compiled against the patched classes: this patcher also gives
 * TThread a {@code gaiusJspiSlot} field (handle plus one), gives TObject a
 * {@code gaiusJspiMonitor} getter and opens
 * {@code TThread.runThread} and {@code TObject.createMonitor} to the package, and the TThread
 * fields that {@code runThread}'s finally block updates ({@code alive}, {@code finishedLock},
 * {@code activeCount}), which TJspiThreadSupport.abandon updates for a thread that trapped.
 *
 * <p>TeaVM 0.15's TThread has no {@code onSpinWait()} (Java 9), which vanilla 26.2 and later
 * call in the retry loops of SectionRenderDispatcher's compile and resort tasks once the
 * JavaScript-only patch that turns them into scheduler yields is not applied (WebAssembly GC
 * target). On the JVM it is a hint inside a loop that waits for another thread; a JSPI thread
 * only gives up the agent at a park, a yield or its end, so the patch adds it as
 * {@code Thread.yield()} (yieldNow), which lets the thread the loop waits for run.
 *
 * <p>The other {@code @Async} methods of the class library that a game build reaches
 * ({@link #ASYNC_METHODS}) keep their callback implementation: in a JSPI build the method
 * passes a TJspiThreadSupport result callback to that implementation and parks until it
 * completes ({@link #patchAsyncMethod}).
 */
public final class TeaVMClasslibJspiPatcher {
    static final String OBJECT = "org/teavm/classlib/java/lang/TObject";
    static final String THREAD = "org/teavm/classlib/java/lang/TThread";
    static final String SUPPORT = "org/teavm/classlib/java/lang/TJspiThreadSupport";
    static final String HOST = "dev/gaius/browser/JspiHost";
    static final String ASYNC = "Lorg/teavm/interop/Async;";
    static final String SLOT_FIELD = "gaiusJspiSlot";
    static final String SLEEP_ASYNC = "gaiusSleepAsync";
    static final String MONITOR_GETTER = "gaiusJspiMonitor";
    static final String ASYNC_CALLBACK = "org/teavm/interop/AsyncCallback";
    static final String ASYNC_PREFIX = "gaiusAsync$";
    /** {@code @Async} methods outside TObject/TThread: class, name, descriptor. */
    static final String[][] ASYNC_METHODS = {
        {"org/teavm/classlib/java/net/impl/TXHRURLConnection", "performRequest",
            "()Ljava/lang/Boolean;"},
    };

    private TeaVMClasslibJspiPatcher() {
    }

    public static void main(String[] args) throws IOException {
        String jar = args[0];
        Path root = Path.of(args[1]);
        write(root, patchObject(read(jar, OBJECT)));
        write(root, patchThread(read(jar, THREAD)));
        for (String[] async : ASYNC_METHODS) {
            write(root, patchAsyncMethod(read(jar, async[0]), async[1], async[2]));
        }
    }

    /**
     * Turns {@code @Async native R m(A...)} with its implementation
     * {@code void m(A..., AsyncCallback)} into a plain method:
     * {@code if (JspiHost.enabled()) { cb = TJspiThreadSupport.newResult(); m(args, cb);
     * return (R) TJspiThreadSupport.awaitResult(cb); } return gaiusAsync$m(args);}. The stock
     * pair moves to {@code gaiusAsync$m}, so coroutine builds keep it unchanged and JSPI builds
     * never reach an {@code @Async} method. R must be void or a reference type.
     */
    static ClassNode patchAsyncMethod(ClassNode node, String name, String desc) {
        MethodNode stub = method(node, name, desc);
        Type returnType = Type.getReturnType(desc);
        Type[] arguments = Type.getArgumentTypes(desc);
        Type[] implArguments = new Type[arguments.length + 1];
        System.arraycopy(arguments, 0, implArguments, 0, arguments.length);
        implArguments[arguments.length] = Type.getObjectType(ASYNC_CALLBACK);
        String implDesc = Type.getMethodDescriptor(Type.VOID_TYPE, implArguments);
        MethodNode impl = method(node, name, implDesc);
        if ((stub.access & Opcodes.ACC_NATIVE) == 0 || !hasAnnotation(stub, ASYNC)) {
            throw new IllegalStateException(node.name + "." + name + desc + " is not an @Async native");
        }
        int sort = returnType.getSort();
        if (sort != Type.VOID && sort != Type.OBJECT && sort != Type.ARRAY) {
            throw new IllegalStateException(node.name + "." + name + desc + " returns a primitive");
        }
        boolean isStatic = (stub.access & Opcodes.ACC_STATIC) != 0;
        int access = stub.access & ~Opcodes.ACC_NATIVE;
        stub.name = ASYNC_PREFIX + name;
        impl.name = ASYNC_PREFIX + name;
        MethodNode wrapper = new MethodNode(access, name, desc, null,
                stub.exceptions.toArray(new String[0]));
        InsnList code = wrapper.instructions;
        int resultLocal = isStatic ? 0 : 1;
        for (Type argument : arguments) {
            resultLocal += argument.getSize();
        }
        LabelNode stock = new LabelNode();
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOST, "enabled", "()Z", false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, stock));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SUPPORT, "newResult",
                "()L" + ASYNC_CALLBACK + ";", false));
        code.add(new VarInsnNode(Opcodes.ASTORE, resultLocal));
        loadArguments(wrapper, code);
        code.add(new VarInsnNode(Opcodes.ALOAD, resultLocal));
        code.add(invoke(node, impl, isStatic));
        code.add(new VarInsnNode(Opcodes.ALOAD, resultLocal));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SUPPORT, "awaitResult",
                "(L" + ASYNC_CALLBACK + ";)Ljava/lang/Object;", false));
        if (sort == Type.VOID) {
            code.add(new InsnNode(Opcodes.POP));
            code.add(new InsnNode(Opcodes.RETURN));
        } else {
            code.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.CHECKCAST,
                    returnType.getInternalName()));
            code.add(new InsnNode(Opcodes.ARETURN));
        }
        code.add(stock);
        loadArguments(wrapper, code);
        code.add(invoke(node, stub, isStatic));
        code.add(new InsnNode(returnType.getOpcode(Opcodes.IRETURN)));
        node.methods.add(wrapper);
        return node;
    }

    private static MethodInsnNode invoke(ClassNode owner, MethodNode method, boolean isStatic) {
        int opcode;
        if (isStatic) {
            opcode = Opcodes.INVOKESTATIC;
        } else if ((method.access & Opcodes.ACC_PRIVATE) != 0) {
            opcode = Opcodes.INVOKESPECIAL;
        } else {
            opcode = Opcodes.INVOKEVIRTUAL;
        }
        return new MethodInsnNode(opcode, owner.name, method.name, method.desc, false);
    }

    static ClassNode patchObject(ClassNode node) {
        String o = "(L" + OBJECT + ";";
        open(node, "createMonitor", o + ")V");
        addMonitorGetter(node);
        delegate(node, "monitorEnterSync", o + ")V", "monitorEnter", o + "I)V", 1);
        delegate(node, "monitorEnter", o + ")V", "monitorEnter", o + "I)V", 1);
        delegate(node, "monitorEnter", o + "I)V", "monitorEnter", o + "I)V", -1);
        delegate(node, "monitorExitSync", o + ")V", "monitorExit", o + "I)V", 1);
        delegate(node, "monitorExit", o + ")V", "monitorExit", o + "I)V", 1);
        delegate(node, "monitorExit", o + "I)V", "monitorExit", o + "I)V", -1);
        delegate(node, "wait0", "(JI)V", "waitOn", o + "JI)V", -1);
        delegateFlag(node, "notify0", "()V", "notify", o + "Z)V", false);
        delegateFlag(node, "notifyAll0", "()V", "notify", o + "Z)V", true);
        return node;
    }

    static ClassNode patchThread(ClassNode node) {
        if (node.fields.stream().anyMatch(field -> field.name.equals(SLOT_FIELD))) {
            throw new IllegalStateException(THREAD + " already has " + SLOT_FIELD);
        }
        node.fields.add(new FieldNode(0, SLOT_FIELD, "I", null, null));
        open(node, "runThread", "()V");
        openField(node, "alive", "Z");
        openField(node, "finishedLock", "Ljava/lang/Object;");
        openField(node, "activeCount", "I");
        String t = "(L" + THREAD + ";";
        delegate(node, "start", "()V", "start", t + ")V", -1);
        delegate(node, "yield", "()V", "yieldNow", "()V", -1);
        addOnSpinWait(node);
        // join(J) and join() call join(JI): the JVM's loop on isAlive (TJspiThreadSupport.join).
        delegate(node, "join", "(JI)V", "join", t + "JI)V", -1);

        // sleep(J) is "@Async native" with the callback implementation sleep(JAsyncCallback).
        MethodNode sleep = method(node, "sleep", "(J)V");
        MethodNode callback = method(node, "sleep", "(JLorg/teavm/interop/AsyncCallback;)V");
        if ((sleep.access & Opcodes.ACC_NATIVE) == 0 || !hasAnnotation(sleep, ASYNC)) {
            throw new IllegalStateException(THREAD + ".sleep(J) is no longer an @Async native");
        }
        sleep.name = SLEEP_ASYNC;
        sleep.access = (sleep.access & ~Opcodes.ACC_PUBLIC) | Opcodes.ACC_PRIVATE;
        callback.name = SLEEP_ASYNC;
        MethodNode wrapper = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "sleep", "(J)V",
                null, sleep.exceptions.toArray(new String[0]));
        LabelNode stock = new LabelNode();
        InsnList code = wrapper.instructions;
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOST, "enabled", "()Z", false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, stock));
        code.add(new VarInsnNode(Opcodes.LLOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SUPPORT, "sleep", "(J)V", false));
        code.add(new InsnNode(Opcodes.RETURN));
        code.add(stock);
        code.add(new VarInsnNode(Opcodes.LLOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, THREAD, SLEEP_ASYNC, "(J)V", false));
        code.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(wrapper);
        return node;
    }

    /** Adds {@code public static void onSpinWait() { yield(); }} (see the class comment). */
    private static void addOnSpinWait(ClassNode node) {
        if (node.methods.stream().anyMatch(m -> m.name.equals("onSpinWait"))) {
            throw new IllegalStateException(THREAD + " already has onSpinWait");
        }
        MethodNode spin = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "onSpinWait", "()V",
                null, null);
        spin.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, THREAD, "yield", "()V", false));
        spin.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(spin);
    }

    /**
     * Adds {@code static Monitor gaiusJspiMonitor(TObject)}. TeaVM's WebAssembly GC backend
     * (BaseClassesTransformation) only rewrites accesses to Object.monitor that are inside
     * java.lang.Object; a field access from another class compiles to {@code unreachable}.
     */
    private static void addMonitorGetter(ClassNode node) {
        String monitor = "L" + OBJECT + "$Monitor;";
        if (node.fields.stream().noneMatch(f -> f.name.equals("monitor") && f.desc.equals(monitor))) {
            throw new IllegalStateException(OBJECT + ".monitor not found");
        }
        MethodNode getter = new MethodNode(Opcodes.ACC_STATIC, MONITOR_GETTER,
                "(L" + OBJECT + ";)" + monitor, null, null);
        getter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getter.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETFIELD, OBJECT,
                "monitor", monitor));
        getter.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(getter);
    }

    /** Removes {@code private} so the support class in the same package can call it. */
    private static void open(ClassNode node, String name, String desc) {
        MethodNode method = method(node, name, desc);
        if ((method.access & Opcodes.ACC_PRIVATE) == 0) {
            throw new IllegalStateException(node.name + "." + name + desc + " is not private");
        }
        method.access &= ~Opcodes.ACC_PRIVATE;
    }

    /** Removes {@code private} from a field so the support class in the same package can use it. */
    private static void openField(ClassNode node, String name, String desc) {
        FieldNode field = node.fields.stream()
                .filter(candidate -> candidate.name.equals(name) && candidate.desc.equals(desc))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(node.name + "." + name + " not found"));
        if ((field.access & Opcodes.ACC_PRIVATE) == 0) {
            throw new IllegalStateException(node.name + "." + name + " is not private");
        }
        field.access &= ~Opcodes.ACC_PRIVATE;
    }

    /**
     * Prefixes the method with {@code if (JspiHost.enabled()) { support(args[, constant]);
     * return; }}. A constant of -1 or more is appended as an int argument.
     */
    private static void delegate(ClassNode node, String name, String desc, String target,
            String targetDesc, int constant) {
        MethodNode method = method(node, name, desc);
        InsnList prefix = new InsnList();
        LabelNode stock = new LabelNode();
        prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOST, "enabled", "()Z", false));
        prefix.add(new JumpInsnNode(Opcodes.IFEQ, stock));
        loadArguments(method, prefix);
        if (constant >= 0) {
            prefix.add(new InsnNode(Opcodes.ICONST_0 + constant));
        }
        prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SUPPORT, target, targetDesc, false));
        prefix.add(new InsnNode(Opcodes.RETURN));
        prefix.add(stock);
        insertPrefix(method, prefix);
    }

    private static void delegateFlag(ClassNode node, String name, String desc, String target,
            String targetDesc, boolean flag) {
        MethodNode method = method(node, name, desc);
        InsnList prefix = new InsnList();
        LabelNode stock = new LabelNode();
        prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOST, "enabled", "()Z", false));
        prefix.add(new JumpInsnNode(Opcodes.IFEQ, stock));
        loadArguments(method, prefix);
        prefix.add(new InsnNode(flag ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
        prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SUPPORT, target, targetDesc, false));
        prefix.add(new InsnNode(Opcodes.RETURN));
        prefix.add(stock);
        insertPrefix(method, prefix);
    }

    private static void insertPrefix(MethodNode method, InsnList prefix) {
        if ((method.access & (Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) != 0) {
            throw new IllegalStateException(method.name + method.desc + " has no body");
        }
        method.instructions.insert(prefix);
    }

    private static void loadArguments(MethodNode method, InsnList code) {
        int local = 0;
        if ((method.access & Opcodes.ACC_STATIC) == 0) {
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            local = 1;
        }
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            code.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), local));
            local += argument.getSize();
        }
    }

    private static boolean hasAnnotation(MethodNode method, String desc) {
        List<AnnotationNode> all = new ArrayList<>();
        if (method.visibleAnnotations != null) {
            all.addAll(method.visibleAnnotations);
        }
        if (method.invisibleAnnotations != null) {
            all.addAll(method.invisibleAnnotations);
        }
        return all.stream().anyMatch(annotation -> annotation.desc.equals(desc));
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        return node.methods.stream()
                .filter(candidate -> candidate.name.equals(name) && candidate.desc.equals(desc))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(node.name + "." + name + desc
                        + " not found"));
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
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return "java/lang/Object";
            }
        };
        node.accept(writer);
        Path output = root.resolve(node.name + ".class");
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }
}
