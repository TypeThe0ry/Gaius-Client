package dev.gaius.tools;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

/** Fail-closed checks of the complete output, independent of patch manifest outcomes. */
public final class OverlayClassCheck {
    private OverlayClassCheck() { }
    private interface Visitor { void accept(String entry, byte[] bytes) throws Exception; }

    private static void visit(Path source, Visitor visitor) throws Exception {
        if (Files.isDirectory(source)) {
            try (var paths = Files.walk(source)) {
                for (Path file : paths.filter(p -> p.toString().endsWith(".class")).sorted().toList()) {
                    visitor.accept(source.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
                }
            }
        } else {
            try (var zip = new ZipFile(source.toFile())) {
                var entries = zip.stream().filter(e -> e.getName().endsWith(".class")).sorted(java.util.Comparator.comparing(java.util.zip.ZipEntry::getName)).toList();
                var names = new HashSet<String>();
                for (var entry : entries) {
                    if (!names.add(entry.getName())) throw new IOException("Duplicate class entry: " + entry.getName());
                    try (var stream = zip.getInputStream(entry)) { visitor.accept(entry.getName(), stream.readAllBytes()); }
                }
            }
        }
    }

    public static int checkMembers(Path source, Path report) throws Exception {
        var failures = new ArrayList<String>();
        var hashes = new TreeMap<String, String>();
        int[] members = new int[2];
        visit(source, (entry, bytes) -> {
            hashes.put(entry, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            var methods = new HashSet<String>(); var fields = new HashSet<String>();
            for (var method : node.methods) {
                members[0]++;
                if (!methods.add(method.name + method.desc)) failures.add(node.name + ": duplicate method " + method.name + method.desc);
            }
            for (var field : node.fields) {
                members[1]++;
                if (!fields.add(field.name + ":" + field.desc)) failures.add(node.name + ": duplicate field " + field.name + ":" + field.desc);
            }
        });
        write(report, "members", hashes, failures, "\"methods\":" + members[0] + ",\"fields\":" + members[1]);
        System.out.println("OVERLAY_MEMBERS " + (failures.isEmpty() ? "PASS" : "FAIL") + " classes=" + hashes.size() + " methods=" + members[0] + " fields=" + members[1] + " failures=" + failures.size());
        failures.forEach(System.err::println);
        return failures.isEmpty() && !hashes.isEmpty() ? 0 : 1;
    }

    public static int verify(Path overlay, String classpath, Path report) throws Exception {
        if (!ManagementFactory.getRuntimeMXBean().getInputArguments().contains("-Xverify:all")) {
            throw new IllegalStateException("JVM verifier gate requires -Xverify:all");
        }
        var urls = new ArrayList<URL>();
        urls.add(overlay.toUri().toURL());
        for (String value : classpath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if (value.isBlank()) continue;
            Path path = Path.of(value);
            if (!Files.exists(path)) throw new IOException("Missing verifier dependency: " + path);
            urls.add(path.toUri().toURL());
        }
        var failures = new ArrayList<String>(); var hashes = new TreeMap<String, String>();
        // Platform parent prevents tool/test classpaths from shadowing any application class.
        // No initializer, constructor or native method is executed. Reflection forces the JVM's
        // otherwise lazy method verification and resolves every declared member descriptor.
        try (var loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader()) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                URL resource = findResource(name.replace('.', '/') + ".class");
                if (resource == null) throw new ClassNotFoundException(name);
                // Patch outputs no longer have upstream signatures. Define their unchanged
                // classfile bytes directly, without inheriting stale jar signing metadata.
                try (var stream = resource.openStream()) {
                    byte[] bytes = stream.readAllBytes();
                    return defineClass(name, bytes, 0, bytes.length);
                } catch (IOException error) { throw new ClassNotFoundException(name, error); }
            }
        }) {
            visit(overlay, (entry, bytes) -> {
                String owner = new ClassReader(bytes).getClassName();
                hashes.put(entry, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
                try {
                    Class<?> type = Class.forName(owner.replace('/', '.'), false, loader);
                    type.getDeclaredConstructors(); type.getDeclaredMethods(); type.getDeclaredFields();
                } catch (LinkageError | ReflectiveOperationException | SecurityException error) {
                    failures.add(owner + ": " + error);
                }
            });
        }
        write(report, "jvm-verifier", hashes, failures, "\"initialization\":false,\"flags\":\"-Xverify:all\"");
        System.out.println("OVERLAY_JVM_VERIFY " + (failures.isEmpty() ? "PASS" : "FAIL") + " classes=" + hashes.size() + " failures=" + failures.size());
        failures.forEach(System.err::println);
        return failures.isEmpty() && !hashes.isEmpty() ? 0 : 1;
    }

    /** Final production pass: changes only maxima/frames, never game instructions. */
    public static int frames(Path overlay, Path input, String classpath, Path report) throws Exception {
        System.setProperty("gaius.patch.input", input.toString());
        System.setProperty("gaius.patch.classpath", classpath);
        var hashes = new TreeMap<String, String>();
        var failures = new ArrayList<String>();
        visit(overlay, (entry, bytes) -> {
            try {
                ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
                Path file = ArchivePaths.resolveEntry(overlay, entry);
                try (var writer = HierarchyClassWriter.create(node, file)) {
                    node.accept(writer);
                    byte[] result = writer.toByteArray();
                    ClassNode verified = PatchTargets.read(result);
                    if (node.methods.size() != verified.methods.size()) throw new IllegalStateException("Frame writer changed members: " + node.name);
                    for (int i = 0; i < node.methods.size(); i++) {
                        if (!PatchTargets.methodHash(node.methods.get(i)).equals(PatchTargets.methodHash(verified.methods.get(i)))) {
                            throw new IllegalStateException("Frame writer changed instructions: " + node.name + "." + node.methods.get(i).name);
                        }
                    }
                    Files.write(file, result);
                    hashes.put(entry, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(result)));
                }
            } catch (RuntimeException | IOException error) {
                failures.add(entry + ": " + error);
            }
        });
        write(report, "production-frames", hashes, failures, "\"instructionHashesVerified\":true,\"instructionsRewritten\":false");
        System.out.println("OVERLAY_FRAMES " + (failures.isEmpty() ? "PASS" : "FAIL") + " classes=" + hashes.size() + " failures=" + failures.size());
        failures.forEach(System.err::println);
        return failures.isEmpty() && !hashes.isEmpty() ? 0 : 1;
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) switch (c) {
            case '"' -> out.append("\\\""); case '\\' -> out.append("\\\\");
            case '\n' -> out.append("\\n"); case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t");
            default -> { if (c < 32) out.append(String.format("\\u%04x", (int)c)); else out.append(c); }
        }
        return out.append('"').toString();
    }

    private static void write(Path report, String mode, TreeMap<String, String> hashes, List<String> failures, String details) throws IOException {
        Files.createDirectories(report.toAbsolutePath().getParent());
        StringBuilder json = new StringBuilder("{\"schema\":1,\"mode\":").append(quote(mode))
                .append(",\"valid\":").append(failures.isEmpty() && !hashes.isEmpty())
                .append(",\"classes\":").append(hashes.size()).append(',').append(details)
                .append(",\"failures\":[");
        for (int i = 0; i < failures.size(); i++) { if (i > 0) json.append(','); json.append(quote(failures.get(i))); }
        json.append("],\"classSha256\":{"); int i = 0;
        for (var entry : hashes.entrySet()) { if (i++ > 0) json.append(','); json.append(quote(entry.getKey())).append(':').append(quote(entry.getValue())); }
        Files.writeString(report, json.append("}}\n").toString());
    }

    public static void main(String[] args) throws Exception {
        int status = switch (args[0]) {
            case "members" -> checkMembers(Path.of(args[1]), Path.of(args[2]));
            case "verify" -> verify(Path.of(args[1]), args[2], Path.of(args[3]));
            case "frames" -> frames(Path.of(args[1]), Path.of(args[2]), args[3], Path.of(args[4]));
            default -> throw new IllegalArgumentException("members <jar-or-directory> <report> | verify <overlay> <classpath> <report>");
        };
        if (status != 0) System.exit(status);
    }
}
