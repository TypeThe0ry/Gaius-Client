package org.teavm.classlib.java.io;

import org.teavm.classlib.java.nio.file.TPath;
import org.teavm.classlib.java.nio.file.TPaths;

public final class TFileModernSupport {
    private TFileModernSupport() {
    }

    public static TPath toPath(TFile file) {
        return TPaths.get(file.getPath());
    }
}
