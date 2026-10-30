package dev.gaius.classlib.repro;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code Files.createDirectories} of a directory that exists returns quietly, also for a path of
 * one name (TeaVMClasslibPatcher patchFilesCreateDirectories: TeaVM 0.15 called
 * createDirectory for it and threw FileAlreadyExistsException, so vanilla's second chunk IO error
 * report, which creates "debug", failed). An existing file still throws. The case works in a
 * directory of the current one and removes what it created.
 */
public final class FilesDirs {
    private FilesDirs() {
    }

    interface Step {
        void run() throws Exception;
    }

    static void step(String name, Step step) {
        String result;
        try {
            step.run();
            result = "ok";
        } catch (Exception e) {
            result = "throws " + e.getClass().getSimpleName();
        }
        System.out.println(name + " = " + result);
    }

    public static void main(String[] args) {
        Path base = Path.of("gaius-classlib-dirs");
        Path nested = base.resolve("a").resolve("b");
        Path file = base.resolve("f");
        step("createDirectories one name, new", () -> Files.createDirectories(base));
        step("createDirectories one name, existing", () -> Files.createDirectories(base));
        step("createDirectories nested, new", () -> Files.createDirectories(nested));
        step("createDirectories nested, existing", () -> Files.createDirectories(nested));
        step("write a file", () -> Files.write(file, new byte[] {1}));
        step("createDirectories on a file", () -> Files.createDirectories(file));
        step("createDirectory existing", () -> Files.createDirectory(base));
        step("delete", () -> {
            Files.delete(file);
            Files.delete(nested);
            Files.delete(nested.getParent());
            Files.delete(base);
        });
    }
}
