package org.teavm.classlib.java.nio.file;

import java.util.regex.Pattern;

public final class TFileSystemModernSupport {
    private TFileSystemModernSupport() {
    }

    public static TPathMatcher getPathMatcher(TFileSystem fileSystem, String syntaxAndPattern) {
        int separator = syntaxAndPattern.indexOf(':');
        if (separator < 0) {
            throw new IllegalArgumentException("Missing syntax: " + syntaxAndPattern);
        }
        String syntax = syntaxAndPattern.substring(0, separator);
        String expression = syntaxAndPattern.substring(separator + 1);
        Pattern pattern;
        if (syntax.equalsIgnoreCase("regex")) {
            pattern = Pattern.compile(expression);
        } else if (syntax.equalsIgnoreCase("glob")) {
            pattern = Pattern.compile(globToRegex(expression));
        } else {
            throw new UnsupportedOperationException("Unsupported path matcher: " + syntax);
        }
        return path -> pattern.matcher(path.toString()).matches();
    }

    private static String globToRegex(String glob) {
        StringBuilder result = new StringBuilder("^");
        for (int index = 0; index < glob.length(); index++) {
            char character = glob.charAt(index);
            switch (character) {
                case '*' -> {
                    if (index + 1 < glob.length() && glob.charAt(index + 1) == '*') {
                        result.append(".*");
                        index++;
                    } else {
                        result.append("[^/]*");
                    }
                }
                case '?' -> result.append("[^/]");
                case '.', '(', ')', '+', '|', '^', '$', '@', '%' ->
                        result.append('\\').append(character);
                case '\\' -> result.append('/');
                default -> result.append(character);
            }
        }
        return result.append('$').toString();
    }
}
