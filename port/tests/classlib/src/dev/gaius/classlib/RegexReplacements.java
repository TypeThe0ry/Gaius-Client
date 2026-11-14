package dev.gaius.classlib;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Optional captures in language-format normalization and cached replacement parts. */
final class RegexReplacements {
    private RegexReplacements() {
    }

    static void run() {
        Pattern format = Pattern.compile("%(\\d+\\$)?[\\d.]*[df]");
        for (String value : new String[] {"%d", "%.2f", "%1$d", "%2$.3f", "%d %1$d %.2f %2$f",
                "%1$d %d %2$f %.2f", "plain %s %%", "数量 %d / %2$.2f"}) {
            Out.check("format:" + value, () -> format.matcher(value).replaceAll("%$1s"));
        }
        Out.check("optional", () -> Pattern.compile("(a)?b").matcher("b ab b").replaceAll("<$1>"));
        Out.check("matched-empty", () -> Pattern.compile("(a*)b").matcher("b aab b").replaceAll("<$1>"));
        Out.check("whole-match", () -> Pattern.compile("(a)?b").matcher("b ab").replaceAll("[$0:$1]"));
        Out.check("replace-first", () -> format.matcher("%d %1$d").replaceFirst("%$1s"));
        Out.check("append-replacement", () -> {
            Matcher matcher = Pattern.compile("(a)?b").matcher("b ab b!");
            StringBuffer result = new StringBuffer();
            while (matcher.find()) matcher.appendReplacement(result, "<$1>");
            return matcher.appendTail(result).toString();
        });
        Out.check("reset", () -> {
            Matcher matcher = format.matcher("%1$d");
            return matcher.replaceAll("%$1s") + "/" + matcher.reset("%d %2$f").replaceAll("%$1s");
        });
        Out.check("escaped", () -> Pattern.compile("(a)?b").matcher("b ab")
                .replaceAll(Matcher.quoteReplacement("$1\\literal")));
        Out.check("group-remains-null", () -> {
            Matcher matcher = Pattern.compile("(a)?b").matcher("b");
            matcher.find();
            return matcher.group(1) == null;
        });
        Out.check("unmatched-span", () -> {
            Matcher matcher = Pattern.compile("(a)?(b)").matcher("b");
            matcher.find();
            return matcher.group(1) + ":" + matcher.start(1) + ":" + matcher.end(1)
                    + "/" + matcher.start(2) + ":" + matcher.end(2);
        });
        Out.check("dollar-ten", () -> Pattern.compile("(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)")
                .matcher("abcdefghij").replaceAll("$10|$1" + "0"));
        Out.check("backslash-dollar", () -> Pattern.compile("(a)?b").matcher("b ab")
                .replaceAll("\\$1|\\\\"));
        Out.check("named-unmatched", () -> Pattern.compile("(?<optional>a)?(?<letter>b)")
                .matcher("b ab").replaceAll("${optional}:${letter}"));
        Out.check("named-invalid", () -> Out.eval(() -> Pattern.compile("(?<x>a)?b").matcher("b")
                .replaceAll("${missing}")));
        Out.check("quote-replacement", () -> Matcher.quoteReplacement("$1\\tail"));
        Out.check("cached-append-replacement", () -> {
            Matcher matcher = Pattern.compile("(a)?b").matcher("b ab b");
            StringBuffer result = new StringBuffer();
            while (matcher.find()) matcher.appendReplacement(result, "$1\\\\");
            return matcher.appendTail(result).toString();
        });
        Out.check("replace-function", () -> Pattern.compile("(?<optional>a)?b").matcher("b ab")
                .replaceAll(match -> "[" + match.group("optional") + ":" + match.group() + "]"));
        Out.check("replace-function-resets", () -> {
            Matcher matcher = Pattern.compile("(a)?b").matcher("x b");
            matcher.find();
            return matcher.replaceAll(match -> match.group(1) == null ? "empty" : match.group(1));
        });
        Out.check("replace-function-null", () -> Out.eval(() -> Pattern.compile("a").matcher("a")
                .replaceAll((java.util.function.Function<java.util.regex.MatchResult, String>) null)));
        Out.check("results", () -> {
            List<String> values = new ArrayList<>();
            Pattern.compile("(a)?b").matcher("b ab").results()
                    .forEach(match -> values.add(match.group() + ":" + match.start(1) + ":" + match.end(1)));
            return values;
        });
        Out.check("results-lazy", () -> {
            Matcher matcher = Pattern.compile("(a)?b").matcher("b ab");
            java.util.stream.Stream<java.util.regex.MatchResult> results = matcher.results();
            boolean foundBeforeTraversal = matcher.find();
            long size = results.count();
            return foundBeforeTraversal + ":" + size;
        });
        Out.check("split-zero-width", () -> Arrays.toString("abc".split("(?=b)")));
        Out.check("split-leading-empty", () -> Arrays.toString("ab".split("a")));
        Out.check("split-limits", () -> Arrays.toString(new String[] {
                Arrays.toString("a,b,,".split(",", 0)), Arrays.toString("a,b,,".split(",", -1)),
                Arrays.toString("a,b,,".split(",", 2)), Arrays.toString("".split("", -1))}));
        Out.check("game-path-sanitize", () -> "dir/name.with\"quote"
                .replaceAll("[\\\\./\\\"]", "_"));
        Out.check("game-chat-newlines", () -> "first\r\nsecond"
                .replaceAll("\\r", "\\\\r").replaceAll("\\n", "\\\\n"));
        Out.check("game-whitespace-split", () -> Arrays.toString("one  two\tthree"
                .split("(\\s+)")));
        Out.check("game-numbered-suffix", () -> Pattern.compile(".*-([0-9]+)$")
                .matcher("world-42").matches());
        Out.check("game-uri-scheme", () -> Pattern.compile("^[a-zA-Z][-a-zA-Z0-9+.]+:")
                .matcher("https:").find());
    }
}
