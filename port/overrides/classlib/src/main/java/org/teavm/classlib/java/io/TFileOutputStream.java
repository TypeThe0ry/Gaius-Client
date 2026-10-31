/*
 *  Copyright 2017 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  Modified for Gaius: Adapted file writes to browser-backed persistence.
 */

package org.teavm.classlib.java.io;

import dev.gaius.browser.BrowserFilePersistence;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Objects;
import org.teavm.runtime.fs.VirtualFile;
import org.teavm.runtime.fs.VirtualFileAccessor;

public class TFileOutputStream extends OutputStream {
    private static final byte[] ONE_BYTE_BUFFER = new byte[1];
    private VirtualFileAccessor accessor;
    private String path;
    private boolean dirty;
    private boolean materializedRetained;

    public TFileOutputStream(TFile file) throws FileNotFoundException {
        this(file, false);
    }

    public TFileOutputStream(String path) throws FileNotFoundException {
        this(new TFile(path));
    }

    public TFileOutputStream(String path, boolean append) throws FileNotFoundException {
        this(new TFile(path), append);
    }

    public TFileOutputStream(TFile file, boolean append) throws FileNotFoundException {
        BrowserFilePersistence.mount();
        String absolutePath = file.getAbsolutePath();
        try {
            BrowserFilePersistence.materializeForOpen(absolutePath);
        } catch (IOException exception) {
            FileNotFoundException failure = new FileNotFoundException(absolutePath);
            failure.initCause(exception);
            throw failure;
        }
        if (file.getName().isEmpty()) {
            throw new FileNotFoundException("Invalid file name");
        }
        VirtualFile parentVirtualFile = file.findParentFile();
        if (parentVirtualFile != null && parentVirtualFile.isDirectory()) {
            try {
                parentVirtualFile.createFile(file.getName());
            } catch (IOException e) {
                throw new FileNotFoundException();
            }
        }

        VirtualFile virtualFile = file.findVirtualFile();
        if (virtualFile == null || !virtualFile.isFile()) {
            throw new FileNotFoundException("Could not create file");
        }
        accessor = virtualFile.createAccessor(false, true, append);
        if (accessor == null) {
            throw new FileNotFoundException();
        }
        if (!append) {
            try {
                truncateIfRequested(accessor, true);
            } catch (IOException exception) {
                try {
                    accessor.close();
                } catch (IOException ignored) {
                    // Preserve the truncation failure as the useful cause.
                }
                accessor = null;
                FileNotFoundException failure =
                        new FileNotFoundException("Could not truncate file");
                failure.initCause(exception);
                throw failure;
            }
        }
        initialize(absolutePath, accessor, !append);
    }

    public static void truncateIfRequested(VirtualFileAccessor accessor, boolean truncate) throws IOException {
        if (truncate) {
            accessor.resize(0);
            accessor.seek(0);
        }
    }

    public TFileOutputStream(VirtualFileAccessor accessor) {
        this.accessor = accessor;
    }

    public TFileOutputStream(String path, VirtualFileAccessor accessor) {
        initialize(path, accessor, false);
    }

    public TFileOutputStream(String path, VirtualFileAccessor accessor, boolean truncated)
            throws IOException {
        try {
            truncateIfRequested(accessor, truncated);
        } catch (IOException failure) {
            try {
                accessor.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
        initialize(path, accessor, truncated);
    }

    private void initialize(String path, VirtualFileAccessor opened, boolean dirty) {
        this.accessor = opened;
        this.path = path;
        this.dirty = dirty;
        try {
            materializedRetained = BrowserFilePersistence.retainMaterializedChunkFile(path);
        } catch (RuntimeException | Error failure) {
            accessor = null;
            try {
                opened.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        Objects.requireNonNull(b);
        if (off < 0 || len < 0 || off > b.length - len) {
            throw new IndexOutOfBoundsException();
        }
        ensureOpened();
        accessor.write(b, off, len);
        dirty = true;
    }

    @Override
    public void flush() throws IOException {
        ensureOpened();
        accessor.flush();
        persistIfDirty();
    }

    @Override
    public void close() throws IOException {
        VirtualFileAccessor opened = accessor;
        if (opened == null) {
            return;
        }
        IOException failure = null;
        try {
            opened.flush();
            persistIfDirty();
        } catch (IOException exception) {
            failure = exception;
        }
        try {
            opened.close();
        } catch (IOException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        } finally {
            accessor = null;
            if (materializedRetained) {
                materializedRetained = false;
                BrowserFilePersistence.releaseMaterializedChunkFile(
                        path, failure == null && !dirty);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public void write(int b) throws IOException {
        ensureOpened();
        byte[] buffer = ONE_BYTE_BUFFER;
        buffer[0] = (byte) b;
        accessor.write(buffer, 0, 1);
        dirty = true;
    }

    private void persistIfDirty() throws IOException {
        if (!dirty || path == null) {
            return;
        }
        int oldPosition = accessor.tell();
        int size = accessor.size();
        byte[] bytes = new byte[size];
        accessor.seek(0);
        int offset = 0;
        while (offset < size) {
            int read = accessor.read(bytes, offset, size - offset);
            if (read <= 0) {
                break;
            }
            offset += read;
        }
        accessor.seek(oldPosition);
        if (offset < size) {
            bytes = Arrays.copyOf(bytes, offset);
        }
        if (!BrowserFilePersistence.persist(path, bytes)) {
            throw new IOException("Could not persist browser file " + path);
        }
        dirty = false;
    }

    private void ensureOpened() throws IOException {
        if (accessor == null) {
            throw new IOException("This stream is already closed");
        }
    }
}
