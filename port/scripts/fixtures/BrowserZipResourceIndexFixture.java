package dev.gaius.browser;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipFile;

public final class BrowserZipResourceIndexFixture {
    private record Spec(String name, byte[] data, boolean directory) {}

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("gaius-zip-index-");
        try {
            Path archive = root.resolve("overlay.zip");
            writeZip(archive, List.of(
                new Spec("assets/", new byte[0], true),
                new Spec("assets/a.txt", new byte[] {1}, false),
                new Spec("assets/a.txt", new byte[] {2}, false),
                new Spec("assets/a2.txt", new byte[] {3}, false),
                new Spec("assets/nested/", new byte[0], true),
                new Spec("assets/nested/b.txt", new byte[] {4}, false),
                new Spec("assets2/not-match.txt", new byte[] {5}, false),
                new Spec("invalid/../resource.txt", new byte[] {6}, false)));
            try (ZipFile zip = new ZipFile(archive.toFile())) {
                BrowserZipResourceIndex index = new BrowserZipResourceIndex(zip);
                if (BrowserZipResourceIndex.forZip(index, zip) != index) {
                    throw new AssertionError("same ZipFile object did not reuse index");
                }
                checkSameAsVanilla(zip, index, "assets/");
                checkSameAsVanilla(zip, index, "assets/nested/");
                checkSameAsVanilla(zip, index, "");
                checkSameAsVanilla(zip, index, "missing/");
                if (index.lastCandidateCountForTest() > 1) {
                    throw new AssertionError("unknown prefix scanned too many candidates: "
                        + index.lastCandidateCountForTest());
                }
                checkNames(index.entries("assets/"), List.of(
                    "assets/a.txt", "assets/a.txt", "assets/a2.txt", "assets/nested/b.txt"));
            }

            Path second = root.resolve("second.zip");
            writeZip(second, List.of(new Spec("assets/only-second.txt", new byte[] {7}, false)));
            BrowserZipResourceIndex closedIndex;
            try (ZipFile closedZip = new ZipFile(archive.toFile())) {
                closedIndex = new BrowserZipResourceIndex(closedZip);
            }
            try (ZipFile reopenedZip = new ZipFile(archive.toFile())) {
                if (BrowserZipResourceIndex.forZip(closedIndex, reopenedZip) == closedIndex) {
                    throw new AssertionError("reopened ZipFile incorrectly reused closed index");
                }
            }
            try (ZipFile firstZip = new ZipFile(archive.toFile()); ZipFile secondZip = new ZipFile(second.toFile())) {
                BrowserZipResourceIndex first = new BrowserZipResourceIndex(firstZip);
                BrowserZipResourceIndex secondIndex = new BrowserZipResourceIndex(secondZip);
                if (BrowserZipResourceIndex.forZip(first, secondZip) == first) {
                    throw new AssertionError("different archive object reused index");
                }
                checkSameAsVanilla(firstZip, first, "assets2/");
                checkSameAsVanilla(secondZip, secondIndex, "assets/");
                if (first.indexedEntryCountForTest() == secondIndex.indexedEntryCountForTest()) {
                    throw new AssertionError("archives unexpectedly share index state");
                }
            }

            Path large = root.resolve("large.zip");
            ArrayList<Spec> specs = new ArrayList<>();
            for (int i = 0; i < 2000; i++) {
                specs.add(new Spec(String.format("other/%04d.bin", i), new byte[] {(byte) i}, false));
            }
            for (int i = 0; i < 5; i++) {
                specs.add(new Spec(String.format("target/%04d.bin", i), new byte[] {(byte) i}, false));
            }
            writeZip(large, specs);
            try (ZipFile zip = new ZipFile(large.toFile())) {
                BrowserZipResourceIndex index = new BrowserZipResourceIndex(zip);
                checkSameAsVanilla(zip, index, "target/");
                checkNames(index.entries("target/"), List.of(
                    "target/0000.bin", "target/0001.bin", "target/0002.bin", "target/0003.bin", "target/0004.bin"));
                if (index.lastCandidateCountForTest() > 5 || index.lastCandidateCountForTest() >= index.indexedEntryCountForTest() / 100) {
                    throw new AssertionError("target query was not bounded: candidates="
                        + index.lastCandidateCountForTest() + " total=" + index.indexedEntryCountForTest());
                }
            }
            System.out.println("BROWSER_ZIP_RESOURCE_INDEX_OK duplicate-order prefix-filter archive-isolation bounded-range");
        } finally {
            deleteTree(root);
        }
    }

    private static void checkNames(List<java.util.zip.ZipEntry> entries, List<String> expected) {
        List<String> actual = entries.stream().map(java.util.zip.ZipEntry::getName).toList();
        if (!actual.equals(expected)) {
            throw new AssertionError("names mismatch expected=" + expected + " actual=" + actual);
        }
    }

    private static void checkSameAsVanilla(ZipFile zip, BrowserZipResourceIndex index, String prefix) {
        ArrayList<String> expected = new ArrayList<>();
        Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            java.util.zip.ZipEntry entry = entries.nextElement();
            if (!entry.isDirectory() && entry.getName().startsWith(prefix)) expected.add(entry.getName());
        }
        checkNames(index.entries(prefix), expected);
    }

    private static void writeZip(Path path, List<Spec> specs) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(output);
        ArrayList<Long> offsets = new ArrayList<>();
        for (Spec spec : specs) {
            byte[] name = spec.name().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] data = spec.data();
            CRC32 crc = new CRC32();
            crc.update(data);
            offsets.add((long) output.size());
            le32(out, 0x04034b50); le16(out, 20); le16(out, 0); le16(out, 0); le16(out, 0); le16(out, 0);
            le32(out, (int) crc.getValue()); le32(out, data.length); le32(out, data.length);
            le16(out, name.length); le16(out, 0); out.write(name); out.write(data);
        }
        int centralOffset = output.size();
        for (int i = 0; i < specs.size(); i++) {
            Spec spec = specs.get(i); byte[] name = spec.name().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] data = spec.data(); CRC32 crc = new CRC32(); crc.update(data);
            le32(out, 0x02014b50); le16(out, 20); le16(out, 20); le16(out, 0); le16(out, 0); le16(out, 0); le16(out, 0);
            le32(out, (int) crc.getValue()); le32(out, data.length); le32(out, data.length);
            le16(out, name.length); le16(out, 0); le16(out, 0); le16(out, 0); le16(out, 0);
            le32(out, spec.directory() ? 0x10 : 0); le32(out, offsets.get(i).intValue()); out.write(name);
        }
        int centralSize = output.size() - centralOffset;
        le32(out, 0x06054b50); le16(out, 0); le16(out, 0); le16(out, specs.size()); le16(out, specs.size());
        le32(out, centralSize); le32(out, centralOffset); le16(out, 0);
        Files.write(path, output.toByteArray());
    }

    private static void le16(DataOutputStream out, int value) throws IOException {
        out.writeByte(value); out.writeByte(value >>> 8);
    }

    private static void le32(DataOutputStream out, int value) throws IOException {
        out.writeByte(value); out.writeByte(value >>> 8); out.writeByte(value >>> 16); out.writeByte(value >>> 24);
    }

    private static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException error) { throw new RuntimeException(error); }
            });
        }
    }
}
