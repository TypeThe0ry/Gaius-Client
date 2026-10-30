package org.teavm.classlib.java.net;

public final class TIDN {
    public static final int ALLOW_UNASSIGNED = 1;
    public static final int USE_STD3_ASCII_RULES = 2;

    private TIDN() {
    }

    public static String toASCII(String input) {
        return toASCII(input, 0);
    }

    public static String toASCII(String input, int flags) {
        // Server host names are handed to the browser transport bridge. Preserve
        // Unicode here; the browser URL implementation performs IDNA conversion.
        return input;
    }

    public static String toUnicode(String input) {
        return toUnicode(input, 0);
    }

    public static String toUnicode(String input, int flags) {
        return input;
    }
}
