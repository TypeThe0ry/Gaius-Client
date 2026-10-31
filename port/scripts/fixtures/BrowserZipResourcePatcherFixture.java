package dev.gaius.browser;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PackResources;

/** JVM fixture for the private FilePackResources bytecode patch. */
public final class BrowserZipResourcePatcherFixture {
    private static final String FILE_OWNER = "net.minecraft.server.packs.FilePackResources";
    private static final String SHARED_OWNER = FILE_OWNER + "$SharedZipFileAccess";

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("jar outputRoot profile");
        Path jar = Path.of(args[0]);
        Path output = Path.of(args[1]);
        String profile = args[2];
        Files.createDirectories(output);
        invokePrivatePatch(jar, output);
        runPatchedResourceContract(jar, profile);
        System.out.println("BROWSER_ZIP_RESOURCE_PATCHER_OK profile=" + profile
            + " list-order overlay-prefix invalid-path close-clear reopen-identity");
    }

    private static void invokePrivatePatch(Path jar, Path output) throws Exception {
        Class<?> patcher = Class.forName("dev.gaius.tools.patches.resources.ResourcePatches");
        Method method = patcher.getDeclaredMethod("patchFilePackResourcesBrowserIndex", String.class, Path.class);
        method.setAccessible(true);
        try {
            method.invoke(null, jar.toString(), output);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error fatal) throw fatal;
            throw error;
        }
    }

    private static void runPatchedResourceContract(Path sourceJar, String profile) throws Exception {
        Path archive = Files.createTempFile("gaius-browser-resource-patch-", ".zip");
        try {
            writeArchive(archive);
            Class<?> sharedType = Class.forName(SHARED_OWNER);
            Constructor<?> sharedCtor = sharedType.getDeclaredConstructor(File.class);
            sharedCtor.setAccessible(true);
            Object shared = sharedCtor.newInstance(archive.toFile());

            PackLocationInfo location = new PackLocationInfo("fixture-" + profile,
                (Component) null, null, Optional.empty());
            Constructor<FilePackResources> resourceCtor = FilePackResources.class
                .getDeclaredConstructor(PackLocationInfo.class, sharedType, String.class);
            resourceCtor.setAccessible(true);
            FilePackResources resources = resourceCtor.newInstance(location, shared, "overlay");
            List<String> first = list(resources, "ns", "dir");
            List<String> expected = List.of("ns:dir/z.txt", "ns:dir/a.txt", "ns:dir/nested/c.txt");
            if (!first.equals(expected)) throw new AssertionError("patched order/prefix mismatch: " + first);
            Field indexField = sharedType.getDeclaredField("browserResourceIndex");
            indexField.setAccessible(true);
            Object firstIndex = indexField.get(shared);
            if (firstIndex == null) throw new AssertionError("index was not published");
            if (!list(resources, "ns", "dir").equals(expected)) {
                throw new AssertionError("same-index second query mismatch");
            }
            if (indexField.get(shared) != firstIndex) {
                throw new AssertionError("second query rebuilt the cached index");
            }
            Field zipField = sharedType.getDeclaredField("zipFile");
            zipField.setAccessible(true);
            Object firstZip = zipField.get(shared);
            if (firstZip == null) throw new AssertionError("zip was not opened");
            resources.close();
            if (indexField.get(shared) != null || zipField.get(shared) != null) {
                throw new AssertionError("close did not clear index and zip");
            }

            // Reopen through the same SharedZipFileAccess instance: close must
            // invalidate both the cached ZipFile and the browser index.
            FilePackResources reopenedResources = resourceCtor.newInstance(location, shared, "overlay");
            List<String> second = list(reopenedResources, "ns", "dir");
            if (!second.equals(expected)) throw new AssertionError("reopen mismatch: " + second);
            Object secondIndex = indexField.get(shared);
            Object secondZip = zipField.get(shared);
            if (secondIndex == null || secondIndex == firstIndex || secondZip == null || secondZip == firstZip) {
                throw new AssertionError("reopen reused/failed to create index or ZipFile");
            }
            reopenedResources.close();
            if (indexField.get(shared) != null || zipField.get(shared) != null) {
                throw new AssertionError("reopen close did not clear index and zip");
            }
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    private static List<String> list(FilePackResources resources, String namespace, String path) {
        ArrayList<String> ids = new ArrayList<>();
        PackResources.ResourceOutput output = (identifier, supplier) -> {
            try (var stream = supplier.get()) {
                if (stream.readAllBytes().length != 1) {
                    throw new AssertionError("lazy supplier returned unexpected content for " + identifier);
                }
            } catch (IOException error) {
                throw new AssertionError("lazy supplier failed for " + identifier, error);
            }
            ids.add(identifier.toString());
        };
        resources.listResources(PackType.CLIENT_RESOURCES, namespace, path, output);
        return ids;
    }

    private static void writeArchive(Path path) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            entry(zip, "overlay/assets/ns/dir/", null);
            entry(zip, "overlay/assets/ns/dir/z.txt", new byte[] {1});
            entry(zip, "overlay/assets/ns/dir/a.txt", new byte[] {2});
            entry(zip, "overlay/assets/ns/dir/nested/", null);
            entry(zip, "overlay/assets/ns/dir/nested/c.txt", new byte[] {3});
            entry(zip, "overlay/assets/ns/dir/bad path.txt", new byte[] {4});
            entry(zip, "overlay/assets/ns/dir2/not-selected.txt", new byte[] {5});
            entry(zip, "assets/ns/dir/unprefixed.txt", new byte[] {6});
        }
    }

    private static void entry(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        if (data != null) zip.write(data);
        zip.closeEntry();
    }
}
