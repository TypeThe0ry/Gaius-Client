package dev.gaius.reslimit;

import java.io.InputStream;

public final class ResourceMain {
    public static void main(String[] args) throws Exception {
        // Check every entry, including both sides of every chunk boundary.
        for (int i = 0; i < 12001; i++) {
            try (InputStream input = ResourceMain.class.getResourceAsStream("/dummy/" + i)) {
                if (input == null || input.read() != (i % 251) || input.read() != -1) {
                    throw new AssertionError("Resource mismatch at " + i);
                }
            }
        }
        if (ResourceMain.class.getResourceAsStream("/dummy/missing") != null) {
            throw new AssertionError("Missing resource exists");
        }
        System.out.println("RESOURCE_CHUNKS_OK resources=12001 boundaries=4096,8192");
    }
}
