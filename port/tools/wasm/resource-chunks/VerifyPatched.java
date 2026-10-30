package dev.gaius.reslimit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;

/** Check ASM data flow, then resolve every patched class in a fresh -Xverify:all JVM. */
public final class VerifyPatched {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        int count = 0;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".class")).toList()) {
                StringWriter diagnostics = new StringWriter();
                CheckClassAdapter.verify(new ClassReader(Files.readAllBytes(path)),
                        VerifyPatched.class.getClassLoader(), false, new PrintWriter(diagnostics));
                if (!diagnostics.toString().isEmpty()) {
                    throw new AssertionError(path + "\n" + diagnostics);
                }
                String name = root.relativize(path).toString().replace('\\', '.').replace('/', '.');
                name = name.substring(0, name.length() - 6);
                Class<?> cls = Class.forName(name, false, VerifyPatched.class.getClassLoader());
                cls.getDeclaredMethods();
                cls.getDeclaredConstructors();
                count++;
            }
        }
        System.out.println("VERIFY_PATCHED_OK classes=" + count);
    }
}
