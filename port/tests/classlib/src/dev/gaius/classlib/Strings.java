package dev.gaius.classlib;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** String casing, encoding, comparison, splitting and regular expressions. */
final class Strings {
    private Strings() {
    }

    static void run() {
        String[] casing = {"straße", "İstanbul", "Iı", "ΟΔΟΣ", "Ǆǅǆ",
            "ﬀﬁ", "ẞ", "Gaius", "ŉ", "ΐ", "𐐀𐐨", "µ"};
        for (int i = 0; i < casing.length; i++) {
            String s = casing[i];
            Out.check("upper.root." + i, () -> s.toUpperCase(Locale.ROOT));
            Out.check("lower.root." + i, () -> s.toLowerCase(Locale.ROOT));
            Out.check("upper.default." + i, () -> s.toUpperCase());
            Out.check("lower.default." + i, () -> s.toLowerCase());
            Out.check("upper.tr." + i, () -> s.toUpperCase(Locale.forLanguageTag("tr")));
            Out.check("equalsIgnoreCase." + i, () -> s.equalsIgnoreCase(s.toUpperCase(Locale.ROOT)));
        }
        String[] texts = {"", "abc", "é", "😀", "\ud800", "a\udc00b", "\u0000", "世界"};
        for (int i = 0; i < texts.length; i++) {
            String s = texts[i];
            Out.check("utf8." + i, () -> Arrays.toString(s.getBytes(StandardCharsets.UTF_8)));
            Out.check("latin1." + i, () -> Arrays.toString(s.getBytes(StandardCharsets.ISO_8859_1)));
            Out.check("utf16." + i, () -> Arrays.toString(s.getBytes(StandardCharsets.UTF_16BE)));
            Out.check("codePoints." + i, () -> s.codePointCount(0, s.length()) + " " + Arrays.toString(s.codePoints().toArray()));
            Out.check("reverse." + i, () -> Arrays.toString(new StringBuilder(s).reverse().toString().chars().toArray()));
        }
        byte[][] encoded = {{(byte) 0xC3, (byte) 0xA9}, {(byte) 0xC3}, {(byte) 0xFF, 0x41}, {(byte) 0xED, (byte) 0xA0, (byte) 0x80},
            {(byte) 0xF0, (byte) 0x9F, (byte) 0x98, (byte) 0x80}, {(byte) 0xF0, (byte) 0x9F, 0x41}, {(byte) 0xC0, (byte) 0x80},
            {(byte) 0xE2, (byte) 0x82}, {0x41, (byte) 0x80, 0x42}};
        for (int i = 0; i < encoded.length; i++) {
            byte[] bytes = encoded[i];
            Out.check("decodeUtf8." + i, () -> Arrays.toString(new String(bytes, StandardCharsets.UTF_8).chars().toArray()));
            Out.check("decodeLatin1." + i, () -> Arrays.toString(new String(bytes, StandardCharsets.ISO_8859_1).chars().toArray()));
        }
        Out.check("compareTo", () -> "a".compareTo("b") + " " + "b".compareTo("a") + " " + "abc".compareTo("ab") + " "
                + "é".compareTo("f") + " " + "😀".compareTo("￿") + " " + "A".compareToIgnoreCase("a"));
        String equalLeft = new String(new char[] {'m', 'i', 'n', 'e', 'c', 'r', 'a', 'f', 't', ':', 's', 't', 'o', 'n', 'e'});
        String equalRight = new String(new char[] {'m', 'i', 'n', 'e', 'c', 'r', 'a', 'f', 't', ':', 's', 't', 'o', 'n', 'e'});
        String prefix = new String(new char[] {'m', 'i', 'n', 'e', 'c', 'r', 'a', 'f', 't', ':', 's', 't', 'o', 'n'});
        Out.check("equals.distinct", () -> equalLeft.equals(equalRight) + " " + (equalLeft == equalRight));
        Out.check("equals.prefix", () -> equalLeft.equals(prefix) + " " + prefix.equals(equalLeft));
        Out.check("equals.hashCollision", () -> "Aa".equals("BB") + " " + ("Aa".hashCode() == "BB".hashCode()));
        Out.check("hash.repeated", () -> equalLeft.hashCode() + " " + equalLeft.hashCode());
        Out.check("split.1", () -> Arrays.toString("a,b,,c,,".split(",")));
        Out.check("split.2", () -> Arrays.toString("a,b,,c,,".split(",", -1)));
        Out.check("split.3", () -> Arrays.toString(" a  b ".split(" ")));
        Out.check("split.4", () -> Arrays.toString("a1b22c".split("\\d+")));
        Out.check("split.5", () -> Arrays.toString("abc".split("")));
        Out.check("split.6", () -> Arrays.toString("minecraft:stone".split(":", 2)));
        Out.check("split.7", () -> Arrays.toString("a.b.c".split("\\.")));
        Out.check("split.8", () -> Arrays.toString("".split(",")));
        Out.check("trim", () -> "[" + " \t x  ".trim() + "][" + " \t x  ".strip() + "][" + "\u0000x\u0000".trim() + "]");
        Out.check("isBlank", () -> "  ".isBlank() + " " + "  ".isBlank() + " " + "\u0000".isBlank());
        Out.check("repeat", () -> "ab".repeat(3) + "|" + "".repeat(5) + "|" + "x".repeat(0));
        Out.check("join", () -> String.join("/", "a", "", "b") + " " + String.join(",", java.util.List.of()));
        Out.check("replace", () -> "aaa".replace("aa", "b") + " " + "a.b.c".replaceAll(".", "x") + " "
                + "a.b.c".replace(".", "$") + " " + "abc".replaceFirst("[bc]", "\\$"));
        Out.check("indexOf", () -> "hello".indexOf('l') + " " + "hello".lastIndexOf("l") + " " + "hello".indexOf("", 9)
                + " " + "a😀b".indexOf(0x1F600) + " " + "hello".indexOf('l', -5));
        Out.check("regionMatches", () -> "Minecraft".regionMatches(true, 0, "MINE", 0, 4) + " "
                + "abc".startsWith("bc", 1) + " " + "abc".endsWith(""));
        Out.check("chars", () -> Arrays.toString("abé".chars().toArray()));
        Out.check("intern", () -> (new String("x").intern() == "x"));
        Out.check("valueOf", () -> String.valueOf((Object) null) + " " + String.valueOf(new char[] {'h', 'i'}) + " "
                + String.valueOf(true) + " " + String.valueOf('c') + " " + String.valueOf(1L << 40));
        Out.check("concat.null", () -> "a" + null + 1 + 2 + 'c' + 1.5f + 2.5 + true);
        Out.check("stringBuilder", () -> new StringBuilder("abcdef").insert(2, 3.5).delete(0, 1).deleteCharAt(0)
                .replace(1, 3, "XY").append((Object) null).appendCodePoint(0x1F600).toString());
        Out.check("stringBuilder.setLength", () -> {
            StringBuilder sb = new StringBuilder("abc");
            sb.setLength(5);
            return Arrays.toString(sb.toString().chars().toArray());
        });
        String[][] regex = {{"[a-z0-9_.-]+", "minecraft:stone"}, {"[a-z0-9_.-]+", "stone_1.a-b"},
            {"^#?[0-9a-fA-F]{6}$", "#FF00aa"}, {"(\\d+)x(\\d+)", "1920x1080"}, {"\\p{L}+", "été"},
            {"\\w+", "été"}, {"(?i)STONE", "stone"}, {"a*+a", "aaa"}, {"\\s+", " "},
            {"[^\\x00-\\x7F]", "aé"}, {"(?<name>[a-z]+)=(\\d)", "key=5"}, {"\\bword\\b", "a word."}};
        for (int i = 0; i < regex.length; i++) {
            String pattern = regex[i][0];
            String input = regex[i][1];
            Out.check("regex.matches." + i, () -> input.matches(pattern));
            Out.check("regex.find." + i, () -> {
                Matcher m = Pattern.compile(pattern).matcher(input);
                StringBuilder out = new StringBuilder();
                while (m.find()) {
                    out.append(m.start()).append('-').append(m.end()).append(':').append(m.group());
                    for (int g = 1; g <= m.groupCount(); g++) {
                        out.append('/').append(m.group(g));
                    }
                    out.append(';');
                }
                return out;
            });
        }
        Out.check("regex.replaceAll", () -> Pattern.compile("(\\w)(\\d)").matcher("a1 b2 c").replaceAll("$2$1"));
        Out.check("regex.quote", () -> "a.b".replaceAll(Pattern.quote("."), "!") + " " + Matcher.quoteReplacement("$1\\"));
    }
}
