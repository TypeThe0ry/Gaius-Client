import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class JarProbe {
    public static void main(String[] arguments) throws Exception {
        var classes = new ArrayList<String>();
        var hashes = new TreeMap<String, byte[]>();
        int maximum = 0;
        try (var jar = new ZipFile(arguments[0])) {
            ZipEntry version = jar.getEntry("version.json");
            if (version == null) throw new IllegalArgumentException("Jar has no version.json");
            Files.writeString(Path.of(arguments[1]), new String(jar.getInputStream(version).readAllBytes(),
                    StandardCharsets.UTF_8));
            for (Enumeration<? extends ZipEntry> entries = jar.entries(); entries.hasMoreElements(); ) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) continue;
                classes.add(entry.getName().substring(0, entry.getName().length() - 6));
                try (var input = jar.getInputStream(entry)) {
                    hashes.put(entry.getName(), MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
                }
                try (var input = new DataInputStream(jar.getInputStream(entry))) {
                    if (input.readInt() != 0xcafebabe) throw new IllegalArgumentException(entry.getName());
                    input.readUnsignedShort();
                    maximum = Math.max(maximum, input.readUnsignedShort());
                }
            }
        }
        classes.sort(String::compareTo);
        var digest = MessageDigest.getInstance("SHA-256");
        for (var entry : hashes.entrySet()) {
            digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(entry.getValue());
        }
        Files.write(Path.of(arguments[2]), classes, StandardCharsets.UTF_8);
        System.out.println("{\"classCount\":" + classes.size() + ",\"maxMajor\":" + maximum
                + ",\"javaRelease\":" + (maximum - 44) + ",\"classContentSha256\":\""
                + HexFormat.of().formatHex(digest.digest()) + "\"}");
    }
}
