package dev.gaius.tools;

import dev.gaius.tools.patches.ui.UiPatches;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Runs the loading presentation patches (UiPatches.patchLoadingOverlayBrowserNoFade,
 * patchTitleScreenBrowserNoFadeIn, patchLevelLoadTrackerBrowserNoCloseDelay) on fixtures with the
 * vanilla bytecode shape and executes the result: the overlay still covers an unfinished reload,
 * and only after the reload is done (successfully or not) does the next frame remove it. With
 * patchLoadingOverlayBrowserForeground in front, a finished in-game reload clears its own overlay
 * at once, but leaves a new overlay that onFinish installed (the rollback of a failed reload).
 */
final class LoadingPresentationPatchesTest {
    private static final String OVERLAY = "net/minecraft/client/gui/screens/LoadingOverlay";
    private static final String RELOAD = "net/minecraft/server/packs/resources/ReloadInstance";
    private static final String UTIL = "net/minecraft/util/Util";
    private static final String TITLE = "net/minecraft/client/gui/screens/TitleScreen";
    private static final String TRACKER = "net/minecraft/client/multiplayer/LevelLoadTracker";
    private static final String MINECRAFT = "net/minecraft/client/Minecraft";
    private static final String LEVEL = "net/minecraft/client/multiplayer/ClientLevel";
    private static final String OVERLAY_TYPE = "net/minecraft/client/gui/screens/Overlay";
    private static final String BOOT_PROGRESS = "dev/gaius/browser/BrowserBootProgress";
    private static int checks;

    private LoadingPresentationPatchesTest() {
    }

    static int run(Path root) throws Exception {
        Path jar = root.resolve("loading-presentation.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            put(zip, UTIL + ".class", util());
            put(zip, RELOAD + ".class", reloadInstance());
            put(zip, OVERLAY + ".class", overlay(false));
            put(zip, TITLE + ".class", titleScreen());
            put(zip, TRACKER + ".class", tracker());
        }
        Path patched = root.resolve("loading-presentation-out");
        UiPatches.patchLoadingOverlayBrowserNoFade(jar.toString(), patched.resolve(OVERLAY + ".class"));
        UiPatches.patchTitleScreenBrowserNoFadeIn(jar.toString(), patched.resolve(TITLE + ".class"));
        UiPatches.patchLevelLoadTrackerBrowserNoCloseDelay(jar.toString(), patched);

        try (URLClassLoader vanilla = loader(jar);
                URLClassLoader browser = loader(patched, jar)) {
            // Vanilla still shows the overlay on the frame after completion (the fixture tells the
            // patched and unpatched code apart).
            Overlay reference = new Overlay(vanilla, false);
            reference.done = true;
            reference.tick();
            check(reference.finished.size() == 1 && !reference.render(),
                    "vanilla keeps the overlay for the fade-out after the reload is done");
            reference.advance(2000);
            check(reference.render(), "vanilla removes the overlay two fade-out seconds later");

            Overlay loading = new Overlay(browser, false);
            for (int frame = 0; frame < 5; frame++) {
                loading.tick();
                check(!loading.render(), "the overlay covers an unfinished reload (frame " + frame + ")");
                loading.advance(1000);
            }
            check(loading.finished.isEmpty() && loading.fadeOutStart() == -1L,
                    "an unfinished reload neither finishes nor starts the fade-out");
            loading.done = true;
            loading.tick();
            check(loading.finished.size() == 1 && loading.finished.get(0).isEmpty(),
                    "a finished reload reports success to onFinish");
            check(loading.fadeOutStart() == loading.now() - 2000L,
                    "the fade-out start is moved back by both fade-out phases");
            check(loading.render(), "the next frame after the reload removes the overlay");
            loading.tick();
            check(loading.finished.size() == 1, "onFinish runs once");

            Overlay failed = new Overlay(browser, false);
            failed.tick();
            check(!failed.render(), "a failing reload is covered while it runs");
            failed.done = true;
            failed.failure = new IllegalStateException("broken pack");
            failed.tick();
            check(failed.finished.size() == 1 && failed.finished.get(0).isPresent()
                            && failed.finished.get(0).get() == failed.failure,
                    "a failed reload still hands its exception to onFinish");
            check(failed.render(), "a failed reload drops the fade-out as well");

            // In-game resource reloads fade in; vanilla also holds them for one second after the
            // fade-in starts, which covers no work.
            Overlay ingame = new Overlay(browser, true);
            ingame.tick();
            check(!ingame.render(), "an in-game reload is covered while it runs");
            ingame.done = true;
            ingame.tick();
            check(ingame.finished.size() == 1 && ingame.render(),
                    "an in-game reload leaves without the minimum display and the fade-out");
            Overlay ingameVanilla = new Overlay(vanilla, true);
            ingameVanilla.done = true;
            ingameVanilla.tick();
            check(ingameVanilla.finished.isEmpty(), "vanilla holds an in-game reload for its minimum display");

            Object title = browser.loadClass(TITLE.replace('/', '.')).getConstructor(boolean.class)
                    .newInstance(true);
            check(!field(title, "fading").getBoolean(title), "the title screen does not fade in");
            Object vanillaTitle = vanilla.loadClass(TITLE.replace('/', '.')).getConstructor(boolean.class)
                    .newInstance(true);
            check(field(vanillaTitle, "fading").getBoolean(vanillaTitle), "vanilla title screen fades in");

            Object tracker = browser.loadClass(TRACKER.replace('/', '.')).getConstructor(long.class)
                    .newInstance(500L);
            check(field(tracker, "closeDelayMs").getLong(tracker) == 0L,
                    "the level loading screen closes when the level is ready");
        }
        foreground(root);
        return checks;
    }

    /** Foreground then NoFade, in registration order, on an overlay that knows its Minecraft. */
    private static void foreground(Path root) throws Exception {
        Path jar = root.resolve("loading-foreground.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            put(zip, UTIL + ".class", util());
            put(zip, RELOAD + ".class", reloadInstance());
            put(zip, OVERLAY_TYPE + ".class", overlayType());
            put(zip, LEVEL + ".class", level());
            put(zip, MINECRAFT + ".class", minecraft());
            put(zip, OVERLAY + ".class", overlay(true));
            put(zip, BOOT_PROGRESS + ".class", bootProgress());
        }
        Path patched = root.resolve("loading-foreground-out");
        Path output = patched.resolve(OVERLAY + ".class");
        UiPatches.patchLoadingOverlayBrowserForeground(jar.toString(), output);
        UiPatches.patchLoadingOverlayBrowserNoFade(jar.toString(), output);
        try (URLClassLoader browser = loader(patched, jar)) {
            Object minecraft = browser.loadClass(MINECRAFT.replace('/', '.')).getConstructor().newInstance();
            Field current = field(minecraft, "overlay");
            Object level = browser.loadClass(LEVEL.replace('/', '.')).getConstructor().newInstance();

            Overlay menu = new Overlay(browser, false);
            menu.attach(minecraft);
            current.set(minecraft, menu.overlay);
            menu.done = true;
            menu.tick();
            check(menu.finished.size() == 1 && current.get(minecraft) == menu.overlay,
                    "without a level the overlay stays installed for its last frame");
            check(menu.render(), "without a level the next frame removes the overlay");

            field(minecraft, "level").set(minecraft, level);
            Overlay ingame = new Overlay(browser, true);
            ingame.attach(minecraft);
            current.set(minecraft, ingame.overlay);
            ingame.tick();
            check(current.get(minecraft) == ingame.overlay, "an unfinished in-game reload keeps its overlay");
            ingame.done = true;
            ingame.tick();
            check(ingame.finished.size() == 1 && current.get(minecraft) == null,
                    "a finished in-game reload clears its own overlay at once");

            Overlay failed = new Overlay(browser, true);
            Overlay rollback = new Overlay(browser, true);
            failed.attach(minecraft);
            rollback.attach(minecraft);
            current.set(minecraft, failed.overlay);
            failed.onFinish = result -> {
                try {
                    current.set(minecraft, rollback.overlay);
                } catch (IllegalAccessException exception) {
                    throw new IllegalStateException(exception);
                }
            };
            failed.tick();
            failed.done = true;
            failed.failure = new IllegalStateException("broken pack");
            failed.tick();
            check(failed.finished.size() == 1 && failed.finished.get(0).isPresent(),
                    "the failed in-game reload hands its exception to onFinish");
            check(current.get(minecraft) == rollback.overlay,
                    "the rollback overlay installed by onFinish survives the failed reload's completion");
            rollback.tick();
            check(current.get(minecraft) == rollback.overlay && !rollback.render(),
                    "the rollback reload stays covered while it runs");
            rollback.done = true;
            rollback.tick();
            check(rollback.finished.size() == 1 && current.get(minecraft) == null,
                    "the finished rollback reload clears its own overlay");
        }
    }

    /** Drives one LoadingOverlay fixture instance through tick and render. */
    private static final class Overlay {
        final List<Optional<Throwable>> finished = new ArrayList<>();
        Consumer<Optional<Throwable>> onFinish = result -> { };
        boolean done;
        RuntimeException failure;
        final Object overlay;
        private final Class<?> overlayClass;
        private final Field clock;

        Overlay(ClassLoader loader, boolean fadeIn) throws Exception {
            Class<?> reloadClass = loader.loadClass(RELOAD.replace('/', '.'));
            Object reload = Proxy.newProxyInstance(loader, new Class<?>[] {reloadClass}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "isDone":
                        return done;
                    case "getActualProgress":
                        return done ? 1.0f : 0.5f;
                    case "checkExceptions":
                        if (failure != null) {
                            throw failure;
                        }
                        return null;
                    default:
                        throw new UnsupportedOperationException(method.getName());
                }
            });
            Consumer<Optional<Throwable>> report = result -> {
                finished.add(result);
                onFinish.accept(result);
            };
            overlayClass = loader.loadClass(OVERLAY.replace('/', '.'));
            overlay = overlayClass.getConstructor(reloadClass, Consumer.class, boolean.class)
                    .newInstance(reload, report, fadeIn);
            clock = loader.loadClass(UTIL.replace('/', '.')).getField("now");
            clock.setLong(null, 100_000L);
        }

        void attach(Object minecraft) throws Exception {
            field(overlay, "minecraft").set(overlay, minecraft);
        }

        void tick() throws Exception {
            overlayClass.getMethod("tick").invoke(overlay);
        }

        /** Renders one frame and returns whether it removed the overlay. */
        boolean render() throws Exception {
            overlayClass.getMethod("extractRenderState", Object.class, int.class, int.class, float.class)
                    .invoke(overlay, null, 0, 0, 0.0f);
            return field(overlay, "removed").getBoolean(overlay);
        }

        void advance(long millis) throws Exception {
            clock.setLong(null, clock.getLong(null) + millis);
        }

        long now() throws Exception {
            return clock.getLong(null);
        }

        long fadeOutStart() throws Exception {
            return field(overlay, "fadeOutStart").getLong(overlay);
        }
    }

    private static Field field(Object instance, String name) throws NoSuchFieldException {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static URLClassLoader loader(Path... roots) throws IOException {
        URL[] urls = new URL[roots.length];
        for (int index = 0; index < roots.length; index++) {
            urls[index] = roots[index].toUri().toURL();
        }
        return new URLClassLoader(urls, LoadingPresentationPatchesTest.class.getClassLoader());
    }

    private static byte[] util() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, UTIL, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "now", "J", null, null).visitEnd();
        MethodVisitor millis = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getMillis", "()J", null, null);
        millis.visitCode();
        millis.visitFieldInsn(Opcodes.GETSTATIC, UTIL, "now", "J");
        millis.visitInsn(Opcodes.LRETURN);
        millis.visitMaxs(0, 0);
        millis.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] reloadInstance() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, RELOAD, null,
                "java/lang/Object", null);
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "isDone", "()Z", null, null).visitEnd();
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "checkExceptions", "()V", null, null).visitEnd();
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "getActualProgress", "()F", null, null)
                .visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** Stand-in for the browser runtime's boot progress sink the foreground patch reports to. */
    private static byte[] bootProgress() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, BOOT_PROGRESS, null, "java/lang/Object", null);
        MethodVisitor report = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "reloadProgress", "(F)V",
                null, null);
        report.visitCode();
        report.visitInsn(Opcodes.RETURN);
        report.visitMaxs(0, 0);
        report.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] overlayType() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, OVERLAY_TYPE,
                null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] level() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, LEVEL, null, "java/lang/Object", null);
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** Minecraft with the level field and the overlay accessors PlatformPatches adds. */
    private static byte[] minecraft() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, MINECRAFT, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, "level", "L" + LEVEL + ";", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE, "overlay", "L" + OVERLAY_TYPE + ";", null, null).visitEnd();
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor get = writer.visitMethod(Opcodes.ACC_PUBLIC, "gaius$getOverlay", "()L" + OVERLAY_TYPE + ";",
                null, null);
        get.visitCode();
        get.visitVarInsn(Opcodes.ALOAD, 0);
        get.visitFieldInsn(Opcodes.GETFIELD, MINECRAFT, "overlay", "L" + OVERLAY_TYPE + ";");
        get.visitInsn(Opcodes.ARETURN);
        get.visitMaxs(0, 0);
        get.visitEnd();
        MethodVisitor set = writer.visitMethod(Opcodes.ACC_PUBLIC, "gaius$setOverlay", "(L" + OVERLAY_TYPE + ";)V",
                null, null);
        set.visitCode();
        set.visitVarInsn(Opcodes.ALOAD, 0);
        set.visitVarInsn(Opcodes.ALOAD, 1);
        set.visitFieldInsn(Opcodes.PUTFIELD, MINECRAFT, "overlay", "L" + OVERLAY_TYPE + ";");
        set.visitInsn(Opcodes.RETURN);
        set.visitMaxs(0, 0);
        set.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /**
     * LoadingOverlay with the vanilla tick, isReadyToFadeOut and fade-out arithmetic of the frame;
     * the foreground variant also implements Overlay and carries the minecraft field.
     */
    private static byte[] overlay(boolean foreground) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, OVERLAY, null, "java/lang/Object",
                foreground ? new String[] {OVERLAY_TYPE} : null);
        if (foreground) {
            writer.visitField(Opcodes.ACC_PRIVATE, "minecraft", "L" + MINECRAFT + ";", null, null).visitEnd();
        }
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "reload", "L" + RELOAD + ";", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "onFinish", "Ljava/util/function/Consumer;", null, null)
                .visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "fadeIn", "Z", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE, "fadeOutStart", "J", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE, "fadeInStart", "J", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE, "removed", "Z", null, null).visitEnd();

        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
                "(L" + RELOAD + ";Ljava/util/function/Consumer;Z)V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        for (String name : new String[] {"fadeOutStart", "fadeInStart"}) {
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitLdcInsn(-1L);
            init.visitFieldInsn(Opcodes.PUTFIELD, OVERLAY, name, "J");
        }
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ALOAD, 1);
        init.visitFieldInsn(Opcodes.PUTFIELD, OVERLAY, "reload", "L" + RELOAD + ";");
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ALOAD, 2);
        init.visitFieldInsn(Opcodes.PUTFIELD, OVERLAY, "onFinish", "Ljava/util/function/Consumer;");
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ILOAD, 3);
        init.visitFieldInsn(Opcodes.PUTFIELD, OVERLAY, "fadeIn", "Z");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        // public void extractRenderState(..., int, int, float): fadeIn start, fadeOut seconds, removal.
        MethodVisitor render = writer.visitMethod(Opcodes.ACC_PUBLIC, "extractRenderState",
                "(Ljava/lang/Object;IIF)V", null, null);
        render.visitCode();
        render.visitMethodInsn(Opcodes.INVOKESTATIC, UTIL, "getMillis", "()J", false);
        render.visitVarInsn(Opcodes.LSTORE, 5);
        Label fadeInSet = new Label();
        render.visitVarInsn(Opcodes.ALOAD, 0);
        render.visitFieldInsn(Opcodes.GETFIELD, OVERLAY, "fadeIn", "Z");
        render.visitJumpInsn(Opcodes.IFEQ, fadeInSet);
        render.visitVarInsn(Opcodes.ALOAD, 0);
        render.visitFieldInsn(Opcodes.GETFIELD, OVERLAY, "fadeInStart", "J");
        render.visitLdcInsn(-1L);
        render.visitInsn(Opcodes.LCMP);
        render.visitJumpInsn(Opcodes.IFNE, fadeInSet);
        render.visitVarInsn(Opcodes.ALOAD, 0);
        render.visitVarInsn(Opcodes.LLOAD, 5);
        render.visitFieldInsn(Opcodes.PUTFIELD, OVERLAY, "fadeInStart", "J");
        render.visitLabel(fadeInSet);
        Label notFading = new Label();
        Label fadeKnown = new Label();
        render.visitVarInsn(Opcodes.ALOAD, 0);
        render.visitFieldInsn(Opcodes.GETFIELD, OVERLAY, "fadeOutStart", "J");
        render.visitLdcInsn(-1L);
        render.visitInsn(Opcodes.LCMP);
        render.visitJumpInsn(Opcodes.IFLE, notFading);
        render.visitVarInsn(Opcodes.LLOAD, 5);
        render.visitVarInsn(Opcodes.ALOAD, 0);
        render.visitFieldInsn(Opcodes.GETFIELD, OVERLAY, "fadeOutStart", "J");
        render.visitInsn(Opcodes.LSUB);
        render.visitInsn(Opcodes.L2F);
        render.visitLdcInsn(1000.0f);
        render.visitInsn(Opcodes.FDIV);
        render.visitJumpInsn(Opcodes.GOTO, fadeKnown);
        render.visitLabel(notFading);
        render.visitLdcInsn(-1.0f);
        render.visitLabel(fadeKnown);
        render.visitVarInsn(Opcodes.FSTORE, 7);
        Label keep = new Label();
        render.visitVarInsn(Opcodes.FLOAD, 7);
        render.visitInsn(Opcodes.FCONST_2);
        render.visitInsn(Opcodes.FCMPL);
        render.visitJumpInsn(Opcodes.IFLT, keep);
        render.visitVarInsn(Opcodes.ALOAD, 0);
        render.visitInsn(Opcodes.ICONST_1);
        render.visitFieldInsn(Opcodes.PUTFIELD, OVERLAY, "removed", "Z");
        render.visitLabel(keep);
        render.visitInsn(Opcodes.RETURN);
        render.visitMaxs(0, 0);
        render.visitEnd();

        // public void tick(): the vanilla completion block.
        MethodVisitor tick = writer.visitMethod(Opcodes.ACC_PUBLIC, "tick", "()V", null, null);
        tick.visitCode();
        Label end = new Label();
        Label tryStart = new Label();
        Label tryEnd = new Label();
        Label handler = new Label();
        Label after = new Label();
        tick.visitTryCatchBlock(tryStart, tryEnd, handler, "java/lang/Throwable");
        tick.visitVarInsn(Opcodes.ALOAD, 0);
        tick.visitFieldInsn(Opcodes.GETFIELD, OVERLAY, "fadeOutStart", "J");
        tick.visitLdcInsn(-1L);
        tick.visitInsn(Opcodes.LCMP);
        tick.visitJumpInsn(Opcodes.IFNE, end);
        tick.visitVarInsn(Opcodes.ALOAD, 0);
        tick.visitFieldInsn(Opcodes.GETFIELD, OVERLAY, "reload", "L" + RELOAD + ";");
        tick.visitMethodInsn(Opcodes.INVOKEINTERFACE, RELOAD, "isDone", "()Z", true);
        tick.visitJumpInsn(Opcodes.IFEQ, end);
        tick.visitVarInsn(Opcodes.ALOAD, 0);
        tick.visitMethodInsn(Opcodes.INVOKEVIRTUAL, OVERLAY, "isReadyToFadeOut", "()Z", false);
        tick.visitJumpInsn(Opcodes.IFEQ, end);
        tick.visitLabel(tryStart);
        tick.visitVarInsn(Opcodes.ALOAD, 0);
        tick.visitFieldInsn(Opcodes.GETFIELD, OVERLAY, "reload", "L" + RELOAD + ";");
        tick.visitMethodInsn(Opcodes.INVOKEINTERFACE, RELOAD, "checkExceptions", "()V", true);
        tick.visitVarInsn(Opcodes.ALOAD, 0);
        tick.visitFieldInsn(Opcodes.GETFIELD, OVERLAY, "onFinish", "Ljava/util/function/Consumer;");
        tick.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Optional", "empty", "()Ljava/util/Optional;", false);
        tick.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Consumer", "accept",
                "(Ljava/lang/Object;)V", true);
        tick.visitLabel(tryEnd);
        tick.visitJumpInsn(Opcodes.GOTO, after);
        tick.visitLabel(handler);
        tick.visitVarInsn(Opcodes.ASTORE, 1);
        tick.visitVarInsn(Opcodes.ALOAD, 0);
        tick.visitFieldInsn(Opcodes.GETFIELD, OVERLAY, "onFinish", "Ljava/util/function/Consumer;");
        tick.visitVarInsn(Opcodes.ALOAD, 1);
        tick.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Optional", "of",
                "(Ljava/lang/Object;)Ljava/util/Optional;", false);
        tick.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Consumer", "accept",
                "(Ljava/lang/Object;)V", true);
        tick.visitLabel(after);
        tick.visitVarInsn(Opcodes.ALOAD, 0);
        tick.visitMethodInsn(Opcodes.INVOKESTATIC, UTIL, "getMillis", "()J", false);
        tick.visitFieldInsn(Opcodes.PUTFIELD, OVERLAY, "fadeOutStart", "J");
        tick.visitLabel(end);
        tick.visitInsn(Opcodes.RETURN);
        tick.visitMaxs(0, 0);
        tick.visitEnd();

        // private boolean isReadyToFadeOut(): !fadeIn || fadeInStart > -1 && now - fadeInStart >= 1000.
        MethodVisitor ready = writer.visitMethod(Opcodes.ACC_PRIVATE, "isReadyToFadeOut", "()Z", null, null);
        ready.visitCode();
        Label yes = new Label();
        Label no = new Label();
        ready.visitVarInsn(Opcodes.ALOAD, 0);
        ready.visitFieldInsn(Opcodes.GETFIELD, OVERLAY, "fadeIn", "Z");
        ready.visitJumpInsn(Opcodes.IFEQ, yes);
        ready.visitVarInsn(Opcodes.ALOAD, 0);
        ready.visitFieldInsn(Opcodes.GETFIELD, OVERLAY, "fadeInStart", "J");
        ready.visitLdcInsn(-1L);
        ready.visitInsn(Opcodes.LCMP);
        ready.visitJumpInsn(Opcodes.IFLE, no);
        ready.visitMethodInsn(Opcodes.INVOKESTATIC, UTIL, "getMillis", "()J", false);
        ready.visitVarInsn(Opcodes.ALOAD, 0);
        ready.visitFieldInsn(Opcodes.GETFIELD, OVERLAY, "fadeInStart", "J");
        ready.visitInsn(Opcodes.LSUB);
        ready.visitLdcInsn(1000L);
        ready.visitInsn(Opcodes.LCMP);
        ready.visitJumpInsn(Opcodes.IFLT, no);
        ready.visitLabel(yes);
        ready.visitInsn(Opcodes.ICONST_1);
        ready.visitInsn(Opcodes.IRETURN);
        ready.visitLabel(no);
        ready.visitInsn(Opcodes.ICONST_0);
        ready.visitInsn(Opcodes.IRETURN);
        ready.visitMaxs(0, 0);
        ready.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] titleScreen() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, TITLE, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "fading", "Z", null, null).visitEnd();
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Z)V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ILOAD, 1);
        init.visitFieldInsn(Opcodes.PUTFIELD, TITLE, "fading", "Z");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] tracker() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, TRACKER, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "closeDelayMs", "J", null, null).visitEnd();
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(J)V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.LLOAD, 1);
        init.visitFieldInsn(Opcodes.PUTFIELD, TRACKER, "closeDelayMs", "J");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
        checks++;
    }
}
