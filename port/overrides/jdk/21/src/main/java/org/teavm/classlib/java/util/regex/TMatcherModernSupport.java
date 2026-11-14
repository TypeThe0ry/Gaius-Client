package org.teavm.classlib.java.util.regex;

import java.util.Iterator;
import java.util.Objects;
import java.util.Map;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/** JDK 21 Matcher methods and group expansion for TeaVM's older class library. */
public final class TMatcherModernSupport {
    private TMatcherModernSupport() {
    }

    public static String replacementGroup(TMatcher matcher, int group) {
        String value = matcher.group(group);
        return value == null ? "" : value;
    }

    public static String expandReplacement(TMatcher matcher, String replacement) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < replacement.length(); i++) {
            char ch = replacement.charAt(i);
            if (ch == '\\') {
                if (++i == replacement.length()) throw new IndexOutOfBoundsException();
                result.append(replacement.charAt(i));
            } else if (ch == '$') {
                if (++i == replacement.length()) throw new IllegalArgumentException();
                int group;
                char next = replacement.charAt(i);
                if (next == '{') {
                    int end = replacement.indexOf('}', i + 1);
                    if (end < 0) throw new IllegalArgumentException();
                    String name = replacement.substring(i + 1, end);
                    Map<String, Integer> groups = matcher.namedGroups();
                    Integer index = groups == null ? null : groups.get(name);
                    if (index == null || name.isEmpty()) throw new IllegalArgumentException();
                    group = index;
                    i = end;
                } else if (Character.isDigit(next)) {
                    group = next - '0';
                    if (group > matcher.groupCount()) throw new IndexOutOfBoundsException();
                    while (i + 1 < replacement.length()) {
                        char digit = replacement.charAt(i + 1);
                        if (!Character.isDigit(digit)) break;
                        int candidate = group * 10 + digit - '0';
                        if (candidate > matcher.groupCount()) break;
                        group = candidate;
                        i++;
                    }
                } else {
                    throw new IllegalArgumentException();
                }
                String value = matcher.group(group);
                if (value != null) result.append(value);
            } else {
                result.append(ch);
            }
        }
        return result.toString();
    }

    public static String replaceAll(TMatcher matcher, Function<? super TMatchResult, String> replacer) {
        Objects.requireNonNull(replacer);
        matcher.reset();
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(result, replacer.apply(matcher.toMatchResult()));
        }
        return matcher.appendTail(result).toString();
    }

    public static Stream<TMatchResult> results(TMatcher matcher) {
        Iterator<TMatchResult> matches = new Iterator<>() {
            private boolean ready;
            private boolean exhausted;

            @Override
            public boolean hasNext() {
                if (!ready && !exhausted) {
                    ready = matcher.find();
                    exhausted = !ready;
                }
                return ready;
            }

            @Override
            public TMatchResult next() {
                if (!hasNext()) {
                    throw new java.util.NoSuchElementException();
                }
                ready = false;
                return matcher.toMatchResult();
            }
        };
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(matches,
                Spliterator.ORDERED | Spliterator.NONNULL), false);
    }
}
