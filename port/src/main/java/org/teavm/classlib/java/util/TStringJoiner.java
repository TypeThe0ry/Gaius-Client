package org.teavm.classlib.java.util;

public final class TStringJoiner {
    private final String delimiter;
    private final String prefix;
    private final String suffix;
    private final StringBuilder value = new StringBuilder();
    private String emptyValue;

    public TStringJoiner(CharSequence delimiter) {
        this(delimiter, "", "");
    }

    public TStringJoiner(CharSequence delimiter, CharSequence prefix, CharSequence suffix) {
        this.delimiter = delimiter.toString();
        this.prefix = prefix.toString();
        this.suffix = suffix.toString();
        this.emptyValue = this.prefix + this.suffix;
    }

    public TStringJoiner setEmptyValue(CharSequence emptyValue) {
        this.emptyValue = emptyValue.toString();
        return this;
    }

    public TStringJoiner add(CharSequence element) {
        if (value.length() > 0) {
            value.append(delimiter);
        }
        value.append(element);
        return this;
    }

    public TStringJoiner merge(TStringJoiner other) {
        if (other.value.length() > 0) {
            add(other.value);
        }
        return this;
    }

    public int length() {
        return value.length() == 0 ? emptyValue.length()
                : prefix.length() + value.length() + suffix.length();
    }

    @Override
    public String toString() {
        return value.length() == 0 ? emptyValue : prefix + value + suffix;
    }
}
