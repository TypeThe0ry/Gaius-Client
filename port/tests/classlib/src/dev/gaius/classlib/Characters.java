package dev.gaius.classlib;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntUnaryOperator;

/**
 * java.lang.Character properties over every code point, as count/digest per range (the BMP in
 * 4096-code-point ranges, the supplementary planes whole). Vanilla uses them in text input
 * (chat, signs, books, edit boxes), font rendering, name validation and the string reader of
 * commands. Differences come from the Unicode version of each class library (JDK 25: Unicode
 * 16.0).
 */
final class Characters {
    private Characters() {
    }

    static void run() {
        Map<String, IntUnaryOperator> properties = new LinkedHashMap<>();
        properties.put("isLetter", cp -> Character.isLetter(cp) ? 1 : 0);
        properties.put("isDigit", cp -> Character.isDigit(cp) ? 1 : 0);
        properties.put("isLetterOrDigit", cp -> Character.isLetterOrDigit(cp) ? 1 : 0);
        properties.put("isAlphabetic", cp -> Character.isAlphabetic(cp) ? 1 : 0);
        properties.put("isWhitespace", cp -> Character.isWhitespace(cp) ? 1 : 0);
        properties.put("isSpaceChar", cp -> Character.isSpaceChar(cp) ? 1 : 0);
        properties.put("isUpperCase", cp -> Character.isUpperCase(cp) ? 1 : 0);
        properties.put("isLowerCase", cp -> Character.isLowerCase(cp) ? 1 : 0);
        properties.put("isTitleCase", cp -> Character.isTitleCase(cp) ? 1 : 0);
        properties.put("isJavaIdentifierStart", cp -> Character.isJavaIdentifierStart(cp) ? 1 : 0);
        properties.put("isJavaIdentifierPart", cp -> Character.isJavaIdentifierPart(cp) ? 1 : 0);
        properties.put("isUnicodeIdentifierPart", cp -> Character.isUnicodeIdentifierPart(cp) ? 1 : 0);
        properties.put("isISOControl", cp -> Character.isISOControl(cp) ? 1 : 0);
        properties.put("isDefined", cp -> Character.isDefined(cp) ? 1 : 0);
        properties.put("getType", Character::getType);
        properties.put("toUpperCase", Character::toUpperCase);
        properties.put("toLowerCase", Character::toLowerCase);
        properties.put("toTitleCase", Character::toTitleCase);
        properties.put("digit36", cp -> Character.digit(cp, 36));
        properties.put("getNumericValue", Character::getNumericValue);
        for (Map.Entry<String, IntUnaryOperator> property : properties.entrySet()) {
            IntUnaryOperator f = property.getValue();
            for (int range = 0; range < 32; range++) {
                int start = range < 16 ? range << 12 : (range - 15) << 16;
                int end = range < 16 ? start + 0x1000 : start + 0x10000;
                String key = property.getKey() + "[" + Integer.toHexString(start) + "]";
                Out.check(key, () -> {
                    Out.Digest digest = new Out.Digest();
                    int count = 0;
                    for (int cp = start; cp < end; cp++) {
                        int value = f.applyAsInt(cp);
                        if (value != 0 && value != cp) {
                            count++;
                        }
                        digest.add(value);
                    }
                    return count + " " + digest;
                });
            }
        }
        int[] samples = {'a', 'Z', '0', '_', '$', ' ', '\t', ' ', ' ', ' ', '　', 'ß',
            'İ', 'ı', 'ǅ', 'Σ', '٠', '²', 'Ⅰ', '一', 'Ａ', '\ud800',
            0x1F600, 0x10400, 0x1D400, 0xE0001, 0x10FFFF, 0x2028, 0x200B, 0xFEFF, 0x0085, 0x1680};
        for (int cp : samples) {
            String key = Integer.toHexString(cp);
            Out.check("sample." + key, () -> Character.getType(cp) + " " + Character.isLetter(cp) + " "
                    + Character.isWhitespace(cp) + " " + Character.isSpaceChar(cp) + " "
                    + Integer.toHexString(Character.toUpperCase(cp)) + " " + Integer.toHexString(Character.toLowerCase(cp))
                    + " " + Character.getNumericValue(cp) + " " + Character.isJavaIdentifierPart(cp) + " "
                    + Character.charCount(cp) + " " + Character.isBmpCodePoint(cp));
        }
        Out.check("surrogates", () -> {
            char[] pair = Character.toChars(0x1F600);
            return Integer.toHexString(pair[0]) + " " + Integer.toHexString(pair[1]) + " "
                    + Character.isSurrogatePair(pair[0], pair[1]) + " " + Integer.toHexString(Character.toCodePoint(pair[0], pair[1]))
                    + " " + Character.isHighSurrogate(pair[0]) + " " + Character.isLowSurrogate(pair[1]);
        });
        Out.check("forDigit", () -> Character.forDigit(11, 16) + "" + Character.forDigit(35, 36) + Character.forDigit(5, 3));
        Out.check("toChars.invalid", () -> Character.toChars(0x110000).length);
    }
}
