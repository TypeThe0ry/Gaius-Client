package dev.gaius.tools;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** Verifies ASM-patched class methods before TeaVM parses them. */
public final class PatchedBytecodeVerifier {
    private PatchedBytecodeVerifier() {}

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        List<String> classes = new ArrayList<>();
        if (options.changedRoot != null) {
            try (var stream = Files.walk(options.changedRoot)) {
                stream.filter(path -> path.toString().endsWith(".class"))
                        .map(path -> options.changedRoot.relativize(path).toString()
                                .replace('\\', '/').replaceAll("\\.class$", ""))
                        .sorted().forEach(classes::add);
            }
        } else if (options.className != null) {
            classes.add(options.className);
        } else if (options.classesFile != null) {
            for (String line : Files.readAllLines(options.classesFile)) {
                if (!line.isBlank()) classes.add(line.trim().replace('.', '/'));
            }
        } else {
            throw new IllegalArgumentException("one of --changed-root, --classes-file or --class is required");
        }
        int failures = 0;
        for (String className : classes) {
            byte[] bytes = readClass(options, className);
            if (bytes == null) {
                System.err.println("MISSING " + className);
                failures++;
                continue;
            }
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG);
            for (MethodNode method : node.methods) {
                if (method.instructions == null || method.instructions.size() == 0) continue;
                try {
                    new Analyzer<BasicValue>(new BasicVerifier()).analyze(node.name, method);
                } catch (AnalyzerException | RuntimeException error) {
                    failures++;
                    String detail = error.getMessage();
                    if (detail == null || detail.isBlank()) detail = error.toString();
                    System.out.println("FAIL class=" + node.name + " method=" + method.name
                            + method.desc + " detail=" + detail.replace('\n', ' '));
                }
            }
        }
        System.out.println("SUMMARY classes=" + classes.size() + " failures=" + failures);
        if (failures != 0) System.exit(1);
    }

    private static byte[] readClass(Options options, String className) throws IOException {
        String entry = className + ".class";
        if (options.changedRoot != null) {
            Path path = options.changedRoot.resolve(entry);
            return Files.exists(path) ? Files.readAllBytes(path) : null;
        }
        try (JarFile jar = new JarFile(options.jar.toFile())) {
            var item = jar.getJarEntry(entry);
            if (item == null) return null;
            try (InputStream input = jar.getInputStream(item)) { return input.readAllBytes(); }
        }
    }

    private record Options(Path jar, Path changedRoot, Path classesFile, String className) {
        static Options parse(String[] args) {
            Path jar = null, root = null, classesFile = null; String className = null;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--jar" -> jar = Path.of(args[++i]);
                    case "--changed-root" -> root = Path.of(args[++i]);
                    case "--classes-file" -> classesFile = Path.of(args[++i]);
                    case "--class" -> className = args[++i];
                    default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
                }
            }
            if (jar == null && root == null) throw new IllegalArgumentException("--jar or --changed-root required");
            if (jar != null && root != null) throw new IllegalArgumentException("--jar and --changed-root are exclusive");
            if (root != null && classesFile != null) throw new IllegalArgumentException("--changed-root and --classes-file are exclusive");
            return new Options(jar, root, classesFile, className);
        }
    }
}
