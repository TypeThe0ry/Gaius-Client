package dev.gaius.browser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The browser resource list ({@code dev/gaius/browser/minecraft-resources.txt}) as the vanilla
 * pack overrides read it, without TeaVM's regular-expression engine.
 *
 * <p>{@link #parse} gives the entries that {@code text.split("\\r?\\n")} followed by
 * {@code trim()} and dropping empty pieces gave: the pieces start and end at the same {@code '\n'}
 * positions; a piece of the old split lacked the {@code '\r'} of a {@code "\r\n"} pair, which
 * {@code trim()} (everything up to {@code ' '}) removes from the end of the {@code '\n'} piece as
 * well; a lone {@code '\r'} splits neither; trailing empty pieces, which split drops, are empty
 * here and dropped by the same emptiness check.
 *
 * <p>{@link #sortedCopy} returns the array that {@code Arrays.sort} of a copy returns. The list is
 * written sorted at build time, so the sort is skipped when every neighbour pair is already in
 * {@code String.compareTo} order: TeaVM's object sort is a stable merge sort (it keeps the left
 * element unless {@code compare > 0}), which leaves such an array unchanged, element for element.
 */
public final class BrowserResourceList {
    private BrowserResourceList() {
    }

    public static String[] parse(String text) {
        List<String> result = new ArrayList<>();
        int length = text.length();
        int start = 0;
        while (start <= length) {
            int end = text.indexOf('\n', start);
            if (end < 0) {
                end = length;
            }
            int first = start;
            int last = end;
            while (first < last && text.charAt(first) <= ' ') {
                first++;
            }
            while (last > first && text.charAt(last - 1) <= ' ') {
                last--;
            }
            if (first < last) {
                result.add(text.substring(first, last));
            }
            start = end + 1;
        }
        return result.toArray(new String[0]);
    }

    public static String[] sortedCopy(String[] values) {
        String[] copy = Arrays.copyOf(values, values.length);
        for (int index = 1; index < copy.length; index++) {
            if (copy[index - 1].compareTo(copy[index]) > 0) {
                Arrays.sort(copy);
                return copy;
            }
        }
        return copy;
    }
}
