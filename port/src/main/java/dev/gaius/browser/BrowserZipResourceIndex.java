package dev.gaius.browser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A per-archive index for the prefix queries used by browser pack resources.
 *
 * <p>The sorted view makes a query inspect only the contiguous name range for
 * its prefix.  Results are then restored to the archive enumeration order so
 * duplicate names and overlay precedence retain vanilla ordering.  The index
 * does not own or close the supplied ZipFile.</p>
 */
public final class BrowserZipResourceIndex {
    private static final Comparator<Record> BY_NAME = (left, right) -> {
        int nameOrder = left.name.compareTo(right.name);
        return nameOrder != 0 ? nameOrder : Integer.compare(left.ordinal, right.ordinal);
    };
    private static final Comparator<Record> BY_ORDINAL = Comparator.comparingInt(record -> record.ordinal);

    private static final class Record {
        private final ZipEntry entry;
        private final String name;
        private final int ordinal;

        private Record(ZipEntry entry, int ordinal) {
            this.entry = entry;
            this.name = entry.getName();
            this.ordinal = ordinal;
        }
    }

    private final Record[] byName;
    private final ZipFile source;
    private int lastCandidateCount;

    /** Builds one immutable index from one ZipFile's enumeration. */
    public BrowserZipResourceIndex(ZipFile zipFile) {
        source = Objects.requireNonNull(zipFile, "zipFile");
        ArrayList<Record> records = new ArrayList<>();
        Enumeration<? extends ZipEntry> entries = source.entries();
        int ordinal = 0;
        while (entries.hasMoreElements()) {
            records.add(new Record(entries.nextElement(), ordinal++));
        }
        records.sort(BY_NAME);
        byName = records.toArray(new Record[0]);
    }

    /**
     * Reuses an index only when the caller still owns the exact same ZipFile
     * object. A close-and-reopen of the same path therefore creates a fresh
     * index and cannot retain entries from the old archive handle.
     */
    public static BrowserZipResourceIndex forZip(BrowserZipResourceIndex previous, ZipFile zipFile) {
        Objects.requireNonNull(zipFile, "zipFile");
        return previous != null && previous.source == zipFile ? previous : new BrowserZipResourceIndex(zipFile);
    }

    /**
     * Returns non-directory entries whose names start with {@code prefix}, in
     * the same order as ZipFile.entries().  A null prefix is rejected just as
     * a direct startsWith call would be; an empty prefix matches all entries.
     */
    public List<ZipEntry> entries(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        int start = lowerBound(prefix);
        int end = start;
        while (end < byName.length && byName[end].name.startsWith(prefix)) {
            end++;
        }
        lastCandidateCount = end - start;

        ArrayList<Record> matches = new ArrayList<>(end - start);
        for (int index = start; index < end; index++) {
            Record record = byName[index];
            if (!record.entry.isDirectory()) {
                matches.add(record);
            }
        }
        matches.sort(BY_ORDINAL);
        ArrayList<ZipEntry> result = new ArrayList<>(matches.size());
        for (Record match : matches) {
            result.add(match.entry);
        }
        return result;
    }

    private int lowerBound(String prefix) {
        int low = 0;
        int high = byName.length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (byName[middle].name.compareTo(prefix) < 0) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    /** Number of entries indexed; useful for bounded diagnostics and tests. */
    int indexedEntryCountForTest() {
        return byName.length;
    }

    /** Number of sorted candidates inspected by the most recent query. */
    int lastCandidateCountForTest() {
        return lastCandidateCount;
    }
}
