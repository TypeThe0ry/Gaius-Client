package dev.gaius.browser;

import java.util.Arrays;
import net.minecraft.util.BitStorage;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PaletteResize;

/**
 * {@code PalettedContainer.reencodeContents} with the same result and the same palette side effects,
 * for the chunk save path ({@code pack}) and the repacking branch of {@code unpack}.
 *
 * <p>Vanilla unpacks the storage into an int array, then walks it and replaces each source palette id
 * by {@code target.idFor(source.valueFor(id))}, calling the two palette methods whenever the id differs
 * from the previous element. In a terrain section the id changes every few blocks, so one
 * identity-hash lookup per change dominates. The target id of a source id never changes during the walk
 * (the first {@code idFor} of a value adds it, later ones return the same id), so this version
 * remembers the answer per source id and calls the palettes once per distinct source id, in the same
 * order of first occurrence: the target palette receives the same values in the same order, hence the
 * same ids, hence the same saved bytes. A storage without bits (a single value) needs one lookup
 * instead of 4096.
 *
 * <p>Sources with more than {@link #MEMO_MAX_BITS} bits (global palettes) take vanilla's loop.
 */
public final class BrowserPaletteRepack {
    private static final int MEMO_MAX_BITS = 8;

    private BrowserPaletteRepack() {
    }

    public static <T> int[] reencode(BitStorage storage, Palette<T> source, Palette<T> target) {
        int size = storage.getSize();
        int[] ids = new int[size];
        PaletteResize<T> resize = PaletteResize.noResizeExpected();
        if (storage instanceof ZeroBitStorage) {
            // ZeroBitStorage.unpack fills zeros, which is what a new array holds. The loop would
            // look up id 0 once and store the answer everywhere.
            if (size > 0) {
                int id = target.idFor(source.valueFor(0), resize);
                if (id != 0) {
                    Arrays.fill(ids, id);
                }
            }
            return ids;
        }
        storage.unpack(ids);
        int bits = storage.getBits();
        if (bits < 0 || bits > MEMO_MAX_BITS) {
            return walk(ids, source, target, resize);
        }
        // answers[v] is the target id of source id v plus one; zero is "not looked up yet".
        int[] answers = new int[1 << bits];
        int last = -1;
        int lastId = -1;
        for (int index = 0; index < ids.length; index++) {
            int value = ids[index];
            if (value != last) {
                last = value;
                if (value >= 0 && value < answers.length) {
                    int answer = answers[value];
                    if (answer == 0) {
                        answer = target.idFor(source.valueFor(value), resize) + 1;
                        answers[value] = answer;
                    }
                    lastId = answer - 1;
                } else {
                    lastId = target.idFor(source.valueFor(value), resize);
                }
            }
            ids[index] = lastId;
        }
        return ids;
    }

    /** Vanilla's loop. */
    private static <T> int[] walk(int[] ids, Palette<T> source, Palette<T> target, PaletteResize<T> resize) {
        int last = -1;
        int lastId = -1;
        for (int index = 0; index < ids.length; index++) {
            int value = ids[index];
            if (value != last) {
                last = value;
                lastId = target.idFor(source.valueFor(value), resize);
            }
            ids[index] = lastId;
        }
        return ids;
    }
}
