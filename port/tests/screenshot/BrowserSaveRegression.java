package dev.gaius.browser.test;

import dev.gaius.browser.BrowserFilePersistence;
import dev.gaius.browser.BrowserPngWriter;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import org.teavm.jso.JSBody;

public final class BrowserSaveRegression {
    private BrowserSaveRegression() {
    }

    public static void main(String[] args) throws IOException {
        BrowserFilePersistence.mount();
        File directory = new File("/gaius/screenshots");
        directory.mkdirs();
        String mode = args.length > 0 ? args[0] : "save";
        boolean large = mode.equals("large") || mode.equals("restore-large");
        Path path = new File(directory, large ? "large.png" : "regression.png").toPath();
        if (mode.equals("restore") || mode.equals("restore-large")) {
            byte[] png = Files.readAllBytes(path);
            if (png.length < 8 || png[0] != (byte) 137 || png[1] != 80) {
                throw new AssertionError("PNG startup restore failed");
            }
            System.out.println("BROWSER_SAVE_RESTORE PASS bytes=" + png.length);
            return;
        }
        if (mode.equals("quota")) {
            Path rejected = new File(directory, "rejected.png").toPath();
            boolean failed = false;
            try (WritableByteChannel channel = Files.newByteChannel(rejected,
                    Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
                channel.write(ByteBuffer.wrap(new byte[] {1}));
            } catch (IOException expected) {
                failed = true;
            }
            if (!failed || isPersisted(rejected.toString())) throw new AssertionError("persistence failure was hidden");
            System.out.println("BROWSER_SAVE_QUOTA PASS");
            return;
        }
        int width = large ? 1000 : 2;
        int height = large ? 790 : 1;
        byte[] pixels = new byte[] {(byte) 255, 0, 0, (byte) 255, 0, (byte) 255, 0, (byte) 128};
        if (large) {
            pixels = new byte[width * height * 4];
            int state = 123456789;
            for (int index = 0; index < pixels.length; index++) {
                state = state * 1664525 + 1013904223;
                pixels[index] = (byte) (state >>> 24);
            }
        }
        long started = System.nanoTime();
        try (WritableByteChannel channel = Files.newByteChannel(path,
                Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
            BrowserPngWriter.write(channel, ByteBuffer.wrap(pixels), width, height, 4);
            if (isPersisted(path.toString())) throw new AssertionError("PNG persisted before closing its channel");
        }
        if (!isPersisted(path.toString())) throw new AssertionError("PNG absent from browser game-directory persistence");
        if (!BrowserFilePersistence.persist("/gaius/cache/transient.png", pixels) || isTransientPersisted()) {
            throw new AssertionError("non-durable file policy changed");
        }
        System.out.println("BROWSER_SAVE_REGRESSION PASS inputBytes=" + pixels.length
                + " saveMs=" + (System.nanoTime() - started) / 1_000_000.0);
    }

    @JSBody(params = "path", script = "return !!globalThis.__gaiusPersistentFiles[path];")
    private static native boolean isPersisted(String path);

    @JSBody(script = "return !!globalThis.__gaiusPersistentFiles['/gaius/cache/transient.png'];")
    private static native boolean isTransientPersisted();
}
