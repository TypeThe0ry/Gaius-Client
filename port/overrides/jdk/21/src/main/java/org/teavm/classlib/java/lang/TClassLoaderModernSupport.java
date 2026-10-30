package org.teavm.classlib.java.lang;

import org.teavm.classlib.java.net.TURL;
import org.teavm.classlib.java.util.TEnumeration;

public final class TClassLoaderModernSupport {
    private static final TEnumeration<TURL> EMPTY_URLS = new TEnumeration<>() {
        @Override
        public boolean hasMoreElements() {
            return false;
        }

        @Override
        public TURL nextElement() {
            throw new java.util.NoSuchElementException();
        }
    };

    private TClassLoaderModernSupport() {
    }

    public static TClass<?> loadClass(TClassLoader loader, String name)
            throws TClassNotFoundException {
        return TClass.forName((TString) (Object) name);
    }

    public static TEnumeration<TURL> getResources(TClassLoader loader, String name) {
        return EMPTY_URLS;
    }
}
