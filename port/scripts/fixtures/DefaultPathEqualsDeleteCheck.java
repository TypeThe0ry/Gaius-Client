import java.io.IOException;
import java.util.Arrays;
import dev.gaius.browser.BrowserFilePersistence;
import org.teavm.classlib.java.nio.file.TFileVisitResult;
import org.teavm.classlib.java.nio.file.TFiles;
import org.teavm.classlib.java.nio.file.TPath;
import org.teavm.classlib.java.nio.file.TSimpleFileVisitor;
import org.teavm.classlib.java.nio.file.attribute.TBasicFileAttributes;
import org.teavm.classlib.java.nio.file.impl.TDefaultFileSystem;
import org.teavm.runtime.fs.VirtualFile;
import org.teavm.runtime.fs.VirtualFileSystem;
import org.teavm.runtime.fs.VirtualFileSystemProvider;
import org.teavm.runtime.fs.memory.InMemoryVirtualFileSystem;

public final class DefaultPathEqualsDeleteCheck {
    public static void main(String[] args) throws Exception {
        VirtualFileSystem vfs = new InMemoryVirtualFileSystem();
        VirtualFileSystemProvider.setInstance(vfs);
        directory(vfs, "/", "gaius");
        directory(vfs, "/gaius", "saves");
        directory(vfs, "/gaius/saves", "W");
        directory(vfs, "/gaius/saves/W", "region");
        file(vfs, "/gaius/saves/W/region", "r.0.0.mca");
        file(vfs, "/gaius/saves/W", "level.dat");
        file(vfs, "/gaius/saves/W", "session.lock");

        TDefaultFileSystem fileSystem = TDefaultFileSystem.INSTANCE;
        TPath worldRoot = fileSystem.getPath("/gaius/saves/W");
        TPath lockPath = fileSystem.getPath("/gaius/saves/W/session.lock");
        deleteChildrenExceptLock(
                vfs.getFile("/gaius/saves/W"),
                "/gaius/saves/W",
                "/gaius/saves/W/session.lock");
        if (!Arrays.equals(vfs.getFile("/gaius/saves/W").listFiles(), new String[] {"session.lock"})) {
            throw new AssertionError("pre-delete VFS sweep did not leave only session.lock");
        }
        boolean[] lockClosed = {false};
        TFiles.walkFileTree(worldRoot, new TSimpleFileVisitor<TPath>() {
            @Override
            public TFileVisitResult visitFile(TPath file, TBasicFileAttributes attributes)
                    throws IOException {
                if (!file.equals(lockPath)) {
                    TFiles.deleteIfExists(file);
                }
                return TFileVisitResult.CONTINUE;
            }

            @Override
            public TFileVisitResult postVisitDirectory(TPath directory, IOException failure)
                    throws IOException {
                if (failure != null) {
                    throw failure;
                }
                if (directory.equals(worldRoot)) {
                    lockClosed[0] = true;
                    TFiles.deleteIfExists(lockPath);
                }
                TFiles.deleteIfExists(directory);
                return TFileVisitResult.CONTINUE;
            }
        });

        if (!lockClosed[0]) {
            throw new AssertionError("world-root comparison did not close and remove session.lock");
        }
        if (vfs.getFile("/gaius/saves/W").exists()) {
            throw new AssertionError("world root remains after the vanilla delete walk");
        }
        if (!BrowserFilePersistence.deletedPaths.contains("/gaius/saves/W/session.lock")) {
            throw new AssertionError("session.lock did not reach the browser per-file delete hook: "
                    + BrowserFilePersistence.deletedPaths);
        }
        if (worldRoot.equals(worldRoot)) {
            System.out.println("WORLD_DELETE_OK samePathEquals=true lockClosed=true deletedPaths="
                    + BrowserFilePersistence.deletedPaths);
        } else {
            throw new AssertionError("TeaVM Path.equals is not reflexive");
        }
    }

    private static void directory(VirtualFileSystem vfs, String parent, String name)
            throws IOException {
        if (!vfs.getFile(parent).createDirectory(name)) {
            throw new AssertionError("could not create " + parent + "/" + name);
        }
    }

    private static void file(VirtualFileSystem vfs, String parent, String name)
            throws IOException {
        if (!vfs.getFile(parent).createFile(name)) {
            throw new AssertionError("could not create " + parent + "/" + name);
        }
    }

    private static void deleteChildrenExceptLock(VirtualFile directory, String path, String lockPath)
            throws IOException {
        for (String childName : directory.listFiles()) {
            String childPath = path + "/" + childName;
            if (childPath.equals(lockPath)) {
                continue;
            }
            VirtualFile child = VirtualFileSystemProvider.getInstance().getFile(childPath);
            if (child.isDirectory()) {
                deleteChildrenExceptLock(child, childPath, lockPath);
            }
            if (!child.delete()) {
                throw new IOException("could not delete " + childPath);
            }
        }
    }
}
