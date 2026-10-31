package joptsimple.internal;

import joptsimple.ValueConverter;

/** Browser-safe Integer converter: direct parsing, no reflection. */
final class BrowserIntegerValueConverter implements ValueConverter<Integer> {
    BrowserIntegerValueConverter() {
    }

    public Integer convert(String value) {
        return Integer.valueOf(value);
    }

    public Class<? extends Integer> valueType() {
        return Integer.class;
    }

    public String valuePattern() {
        return null;
    }
}
