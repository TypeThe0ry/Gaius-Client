package joptsimple.internal;

import java.io.File;
import joptsimple.ValueConverter;

/** Browser-safe File converter: direct construction, no reflection. */
final class BrowserFileValueConverter implements ValueConverter<File> {
    BrowserFileValueConverter() {
    }

    public File convert(String value) {
        return new File(value);
    }

    public Class<? extends File> valueType() {
        return File.class;
    }

    public String valuePattern() {
        return null;
    }
}
