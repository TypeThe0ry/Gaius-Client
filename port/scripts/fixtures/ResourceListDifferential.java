import dev.gaius.browser.BrowserResourceList;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

/**
 * Differential test of BrowserResourceList against the code it replaced in the vanilla pack
 * overrides (split("\\r?\\n") + trim + drop empty; Arrays.copyOf + Arrays.sort).
 *
 *   java ResourceListDifferential <list file>...
 *
 * For every list file: the bytes as read (UTF-8), the same text with CRLF line ends, with blank
 * and padded lines, without a final line end, shuffled (sort runs), shuffled with duplicates, and
 * the sorted list with duplicates; plus random texts over an alphabet of separators, whitespace,
 * control characters, non-ASCII and surrogates. parse must give equal arrays; sortedCopy must give
 * equal arrays whose elements are the same objects as after the old sort (identity, so a skipped
 * sort is indistinguishable), and must never return its argument.
 */
public final class ResourceListDifferential {
    private static long checks;
    private static long sortSkipped;
    private static long sortRun;

    public static void main(String[] args) throws Exception {
        for (String file : args) {
            byte[] bytes = Files.readAllBytes(Path.of(file));
            String text = new String(bytes, StandardCharsets.UTF_8);
            String[] parsed = check(text, file);
            Random random = new Random(0x5eedL ^ file.hashCode());
            check(text.replace("\n", "\r\n"), file + " crlf");
            check("\n\n  " + text.replace("\n", " \t\n\n") + "\r\n\r", file + " padded");
            check(text.endsWith("\n") ? text.substring(0, text.length() - 1) : text + "\n", file + " last line end");
            List<String> shuffled = new ArrayList<>(Arrays.asList(parsed));
            Collections.shuffle(shuffled, random);
            check(String.join("\n", shuffled) + "\n", file + " shuffled");
            List<String> duplicated = new ArrayList<>(shuffled);
            for (int index = 0; index < Math.min(500, parsed.length); index++) {
                duplicated.add(parsed[random.nextInt(parsed.length)]);
            }
            Collections.shuffle(duplicated, random);
            check(String.join("\n", duplicated), file + " duplicates");
            List<String> sortedDuplicates = new ArrayList<>(duplicated);
            Collections.sort(sortedDuplicates);
            check(String.join("\n", sortedDuplicates), file + " sorted duplicates");
            String[] sorted = BrowserResourceList.sortedCopy(parsed);
            System.out.println("LIST " + file + " bytes=" + bytes.length + " entries=" + parsed.length
                    + " sorted=" + Arrays.equals(sorted, parsed) + " sha256=" + sha256(bytes));
        }
        Random random = new Random(20261113L);
        char[] alphabet = {'a', 'b', 'Z', '/', '.', ' ', '\t', '\r', '\n', '\u000b', '\f', '\u0000', '\u001f',
            '\u007f', ' ', 'é', ' ', '　', '\ud83d', '\ude00', '￿'};
        for (int round = 0; round < 200_000; round++) {
            int length = random.nextInt(round < 1000 ? 6 : 40);
            StringBuilder builder = new StringBuilder(length);
            for (int index = 0; index < length; index++) {
                builder.append(alphabet[random.nextInt(alphabet.length)]);
            }
            check(builder.toString(), null);
        }
        for (String edge : new String[] {"", "\n", "\r", "\r\n", "\n\r", "\r\r\n", " \n ", "a", "a\n", "\na",
            "a\rb", "a\r\rb\n", "b\na\n", "a\na", "a\r\n\r\nb", "\u0000a\u0000"}) {
            check(edge, null);
        }
        System.out.println("RESOURCE_LIST_DIFFERENTIAL_OK checks=" + checks + " sortSkipped=" + sortSkipped
                + " sortRun=" + sortRun);
    }

    private static String[] check(String text, String what) {
        String[] expected = oldParse(text);
        String[] actual = BrowserResourceList.parse(text);
        if (!Arrays.equals(expected, actual)) {
            throw new IllegalStateException("parse differs for " + describe(text, what));
        }
        String[] oldSorted = oldSortedCopy(actual);
        String[] newSorted = BrowserResourceList.sortedCopy(actual);
        if (newSorted == actual || newSorted.length != oldSorted.length) {
            throw new IllegalStateException("sortedCopy shape for " + describe(text, what));
        }
        for (int index = 0; index < oldSorted.length; index++) {
            if (oldSorted[index] != newSorted[index]) {
                throw new IllegalStateException("sortedCopy element " + index + " for " + describe(text, what));
            }
        }
        boolean already = true;
        for (int index = 1; index < actual.length; index++) {
            already &= actual[index - 1].compareTo(actual[index]) <= 0;
        }
        if (already) {
            sortSkipped++;
        } else {
            sortRun++;
        }
        checks++;
        return actual;
    }

    /** The replaced loadResourceList body (all three overrides had this one). */
    private static String[] oldParse(String text) {
        String[] lines = text.split("\\r?\\n");
        List<String> result = new ArrayList<>(lines.length);
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result.toArray(String[]::new);
    }

    /** The replaced sortedResourceCopy body. */
    private static String[] oldSortedCopy(String[] values) {
        String[] copy = Arrays.copyOf(values, values.length);
        Arrays.sort(copy);
        return copy;
    }

    private static String describe(String text, String what) {
        if (what != null) {
            return what;
        }
        StringBuilder builder = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            builder.append(c >= 0x20 && c < 0x7f ? String.valueOf(c) : String.format("\\u%04x", (int) c));
        }
        return builder.append('"').toString();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
