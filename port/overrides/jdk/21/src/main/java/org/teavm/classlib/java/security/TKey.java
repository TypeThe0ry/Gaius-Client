package org.teavm.classlib.java.security;

import java.io.Serializable;

public interface TKey extends Serializable {
    String getAlgorithm();

    default String getFormat() {
        return "RAW";
    }

    default byte[] getEncoded() {
        return new byte[0];
    }
}
