package dev.gaius.classlib.repro;

import dev.gaius.classlib.Out;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

public final class RegionHeader {
    private RegionHeader() {
    }

    public static void run() {
        int dirtyLocations = 0;
        int dirtyTimestamps = 0;
        int corruptWrites = 0;
        for (int iteration = 0; iteration < 64; iteration++) {
            ByteBuffer reused = ByteBuffer.allocateDirect(8192);
            for (int index = 0; index < reused.capacity(); index++) {
                reused.put(index, (byte) 0x7F);
            }
            reused = null;
            System.gc();
            ByteBuffer header = ByteBuffer.allocateDirect(8192);
            IntBuffer locations = header.asIntBuffer();
            header.position(4096);
            IntBuffer timestamps = header.asIntBuffer();
            int slot = (iteration * 31) & 1023;
            locations.put(slot, (2 << 8) | 3);
            timestamps.put(slot, 123456789);
            header.position(0);
            byte[] persisted = new byte[8192];
            header.get(persisted);
            ByteBuffer reopened = ByteBuffer.wrap(persisted);
            for (int index = 0; index < 1024; index++) {
                int location = reopened.getInt(index * 4);
                int timestamp = reopened.getInt(4096 + index * 4);
                if (index == slot) {
                    if (location != ((2 << 8) | 3) || timestamp != 123456789) {
                        corruptWrites++;
                    }
                } else {
                    if (location != 0) {
                        dirtyLocations++;
                    }
                    if (timestamp != 0) {
                        dirtyTimestamps++;
                    }
                }
            }
        }
        Out.put("dirtyLocations", dirtyLocations);
        Out.put("dirtyTimestamps", dirtyTimestamps);
        Out.put("corruptWrites", corruptWrites);
    }
}
