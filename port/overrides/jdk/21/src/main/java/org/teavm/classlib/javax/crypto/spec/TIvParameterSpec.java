package org.teavm.classlib.javax.crypto.spec;

import org.teavm.classlib.java.security.spec.TAlgorithmParameterSpec;

public class TIvParameterSpec implements TAlgorithmParameterSpec {
    private final byte[] iv;

    public TIvParameterSpec(byte[] iv) {
        this.iv = iv == null ? new byte[0] : iv.clone();
    }

    public TIvParameterSpec(byte[] iv, int offset, int len) {
        this.iv = new byte[Math.max(0, len)];
        if (iv != null && len > 0) {
            System.arraycopy(iv, offset, this.iv, 0, len);
        }
    }

    public byte[] getIV() {
        return iv.clone();
    }
}
