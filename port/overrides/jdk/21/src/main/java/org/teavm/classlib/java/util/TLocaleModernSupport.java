package org.teavm.classlib.java.util;

public final class TLocaleModernSupport {
    private TLocaleModernSupport() {
    }

    public static TLocale forLanguageTag(String languageTag) {
        String[] parts = languageTag.replace('_', '-').split("-");
        String language = parts.length > 0 ? parts[0] : "";
        String country = "";
        String variant = "";
        for (int index = 1; index < parts.length; index++) {
            String part = parts[index];
            if (country.isEmpty() && (part.length() == 2 || part.length() == 3)) {
                country = part;
            } else if (variant.isEmpty() && part.length() >= 4) {
                variant = part;
            }
        }
        return new TLocale(language, country, variant);
    }

    public static String getScript(TLocale locale) {
        return "";
    }

    public static String getExtension(TLocale locale, char key) {
        return null;
    }

    public static TSet<Character> getExtensionKeys(TLocale locale) {
        return TCollections.emptySet();
    }

    public static TSet<String> getUnicodeLocaleAttributes(TLocale locale) {
        return TCollections.emptySet();
    }

    public static TSet<String> getUnicodeLocaleKeys(TLocale locale) {
        return TCollections.emptySet();
    }

    public static String getUnicodeLocaleType(TLocale locale, String key) {
        return null;
    }

    public static String getISO3Country(TLocale locale) {
        String country = locale.getCountry();
        return country.length() == 3 ? country : "";
    }
}
