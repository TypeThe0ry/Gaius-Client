import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

public final class GaiusTextureCaptureFixture implements Opcodes {
    private static final String STATE = "net/minecraft/client/renderer/texture/SpriteContents$AnimationState";
    private static final String INFO = "net/minecraft/client/renderer/texture/SpriteContents$AnimatedTexture";
    private static final String FRAME = "net/minecraft/client/renderer/texture/SpriteContents$FrameInfo";
    public static int captureTick = -1;

    public static int captureAnimationTick() {
        return captureTick;
    }

    public static final class Frame {
        public int index;
        public int time;

        public Frame(int index, int time) {
            this.index = index;
            this.time = time;
        }
    }

    public static final class Info {
        public List<Frame> frames = List.of(new Frame(0, 2), new Frame(1, 3),
                new Frame(1, 1), new Frame(2, 4));
    }

    private static String mapped(String name) {
        return name.replace(STATE, "CaptureState").replace(INFO, "GaiusTextureCaptureFixture$Info")
                .replace(FRAME, "GaiusTextureCaptureFixture$Frame")
                .replace("org/lwjgl/opengl/BrowserOpenGL", "GaiusTextureCaptureFixture");
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static int value(Object state, String name) throws Exception {
        return state.getClass().getField(name).getInt(state);
    }

    private static void equalState(Object first, Object second, boolean dirty) throws Exception {
        if (value(first, "frame") != value(second, "frame")
                || value(first, "subFrame") != value(second, "subFrame")
                || dirty && first.getClass().getField("isDirty").getBoolean(first)
                    != second.getClass().getField("isDirty").getBoolean(second)) {
            throw new AssertionError("vanilla animation state mismatch");
        }
    }

    public static void main(String[] args) throws Exception {
        ClassNode original;
        try (ZipFile jar = new ZipFile(args[0])) {
            original = read(jar.getInputStream(jar.getEntry(STATE + ".class")).readAllBytes());
        }
        ClassNode patched = read(Files.readAllBytes(Path.of(args[1], STATE + ".class")));
        ClassNode fixture = new ClassNode();
        fixture.version = V17; fixture.access = ACC_PUBLIC; fixture.name = "CaptureState";
        fixture.superName = "java/lang/Object";
        for (FieldNode field : original.fields) {
            if (List.of("frame", "subFrame", "isDirty", "animationInfo").contains(field.name)) {
                fixture.fields.add(new FieldNode(ACC_PUBLIC, field.name, mapped(field.desc), null, null));
            }
        }
        MethodNode constructor = new MethodNode(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(RETURN); fixture.methods.add(constructor);
        MethodNode baseline = original.methods.stream().filter(method -> method.name.equals("tick") && method.desc.equals("()V")).findFirst().orElseThrow();
        baseline.name = "baseline"; fixture.methods.add(baseline);
        fixture.methods.addAll(patched.methods.stream().filter(method -> method.name.equals("tick") || method.name.equals("gaius$tickUnpinned")).toList());
        for (MethodNode method : fixture.methods) {
            method.access = ACC_PUBLIC;
            for (var instruction : method.instructions) {
                if (instruction instanceof FieldInsnNode field) { field.owner = mapped(field.owner); field.desc = mapped(field.desc); }
                if (instruction instanceof MethodInsnNode call) { call.owner = mapped(call.owner); call.desc = mapped(call.desc); }
                if (instruction instanceof TypeInsnNode type) type.desc = mapped(type.desc);
            }
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        fixture.accept(writer);
        Class<?> stateType = new ClassLoader(GaiusTextureCaptureFixture.class.getClassLoader()) {
            Class<?> create() { return defineClass("CaptureState", writer.toByteArray(), 0, writer.toByteArray().length); }
        }.create();
        Object actual = stateType.getConstructor().newInstance();
        Object expected = stateType.getConstructor().newInstance();
        for (Object state : List.of(actual, expected)) stateType.getField("animationInfo").set(state, new Info());
        Method tick = stateType.getMethod("tick"), vanilla = stateType.getMethod("baseline");
        for (int count = 0; count < 100; count++) { tick.invoke(actual); vanilla.invoke(expected); equalState(actual, expected, true); }
        for (int target : new int[] {0, 1, 2, 4, 9, 19, 1200}) {
            stateType.getField("frame").setInt(expected, 0); stateType.getField("subFrame").setInt(expected, 0);
            for (int count = 0; count < target; count++) vanilla.invoke(expected);
            captureTick = target;
            for (int repeat = 0; repeat < 3; repeat++) {
                boolean changed = value(actual, "frame") != value(expected, "frame")
                        || value(actual, "subFrame") != value(expected, "subFrame");
                tick.invoke(actual); equalState(actual, expected, false);
                if (stateType.getField("isDirty").getBoolean(actual) != changed) {
                    throw new AssertionError("pinned atlas must upload only a changed phase");
                }
            }
            captureTick = -1; tick.invoke(actual); vanilla.invoke(expected); equalState(actual, expected, true);
        }
        System.out.println("TEXTURE_CAPTURE_OK " + args[2] + " vanilla=100 pinned=21 resume=7");
    }
}
