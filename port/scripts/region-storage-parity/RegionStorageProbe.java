package dev.gaius.storage;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

public final class RegionStorageProbe {
    private static final int REGION_COUNT = 8;
    private static final int PAYLOAD_BYTES = 9000;
    private static final int[] SLOTS = {0, 31, 992, 1023};

    private RegionStorageProbe() {
    }

    public static void main(String[] args) throws Exception {
        boolean write = args[0].equals("write");
        Path directory = Path.of(args[1]);
        Files.createDirectories(directory);
        long fileBytes = 0;
        for (int region = 0; region < REGION_COUNT; region++) {
            Path path = directory.resolve("r." + region + ".0.mca");
            if (write) {
                for (int allocation = 0; allocation < 24; allocation++) {
                    ByteBuffer poison = ByteBuffer.allocateDirect(8192);
                    for (int index = 0; index < poison.capacity(); index++) {
                        poison.put(index, (byte) 0x7F);
                    }
                }
                System.gc();
                Thread.sleep(10);
                try (RegionFile file = open(path, directory)) {
                    for (int slot : SLOTS) {
                        try (DataOutputStream stream = file.getChunkDataOutputStream(position(region, slot))) {
                            stream.writeInt(region);
                            stream.writeInt(slot);
                            for (int index = 0; index < PAYLOAD_BYTES; index++) {
                                stream.writeByte(payload(region, slot, index));
                            }
                        }
                        file.flush();
                    }
                }
            }
            byte[] bytes = Files.readAllBytes(path);
            require(bytes.length >= 8192 && bytes.length % 4096 == 0, "sector padding " + region);
            ByteBuffer header = ByteBuffer.wrap(bytes);
            for (int slot = 0; slot < 1024; slot++) {
                int location = header.getInt(slot * 4);
                int timestamp = header.getInt(4096 + slot * 4);
                if (present(slot)) {
                    require((location >>> 8) >= 2 && (location & 255) >= 3, "location " + region + ":" + slot);
                    require(((long) (location >>> 8) + (location & 255)) * 4096 <= bytes.length,
                            "truncated " + region + ":" + slot);
                } else {
                    require(location == 0 && timestamp == 0, "phantom chunk " + region + ":" + slot);
                }
            }
            try (RegionFile file = open(path, directory)) {
                for (int slot = 0; slot < 1024; slot++) {
                    require(file.hasChunk(position(region, slot)) == present(slot), "presence " + region + ":" + slot);
                }
                for (int slot : SLOTS) {
                    try (DataInputStream stream = file.getChunkDataInputStream(position(region, slot))) {
                        require(stream != null && stream.readInt() == region && stream.readInt() == slot,
                                "chunk prefix " + region + ":" + slot);
                        for (int index = 0; index < PAYLOAD_BYTES; index++) {
                            require(stream.readUnsignedByte() == payload(region, slot, index), "payload " + index);
                        }
                        require(stream.read() == -1, "chunk tail " + region + ":" + slot);
                    }
                }
            }
            fileBytes += bytes.length;
            if (write) {
                System.out.println("REGION " + path.getFileName() + " " + Base64.getEncoder().encodeToString(bytes));
            }
        }
        System.out.println("REGION_STORAGE_PASS regions=" + REGION_COUNT + " chunks=" + REGION_COUNT * SLOTS.length
                + " absent=" + REGION_COUNT * (1024 - SLOTS.length) + " payloadBytes="
                + REGION_COUNT * SLOTS.length * PAYLOAD_BYTES + " fileBytes=" + fileBytes);
    }

    private static RegionFile open(Path path, Path directory) throws Exception {
        return new RegionFile(new RegionStorageInfo("storage-parity", null, "chunk"), path, directory,
                RegionFileVersion.VERSION_DEFLATE, false);
    }

    private static ChunkPos position(int region, int slot) {
        return new ChunkPos(region * 32 + (slot & 31), slot >>> 5);
    }

    private static boolean present(int slot) {
        for (int expected : SLOTS) {
            if (slot == expected) {
                return true;
            }
        }
        return false;
    }

    private static int payload(int region, int slot, int index) {
        int value = (index + region * PAYLOAD_BYTES + slot * 17) * 0x45D9F3B;
        return ((value ^ (value >>> 16)) * 0x45D9F3B) >>> 24;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
