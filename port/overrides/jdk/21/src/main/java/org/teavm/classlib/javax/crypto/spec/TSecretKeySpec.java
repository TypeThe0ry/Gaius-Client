package org.teavm.classlib.javax.crypto.spec;

import org.teavm.classlib.javax.crypto.TSecretKey;

public class TSecretKeySpec implements TSecretKey {
    private final byte[] key;
    private final String algorithm;

    public TSecretKeySpec(byte[] key, String algorithm) {
        this.key = key == null ? new byte[0] : key.clone();
        this.algorithm = algorithm;
    }

    public TSecretKeySpec(byte[] key, int offset, int len, String algorithm) {
        this.key = new byte[Math.max(0, len)];
        if (key != null && len > 0) {
            System.arraycopy(key, offset, this.key, 0, len);
        }
        this.algorithm = algorithm;
    }

    @Override
    public String getAlgorithm() {
        return algorithm;
    }

    @Override
    public String getFormat() {
        return "RAW";
    }

    @Override
    public byte[] getEncoded() {
        return key.clone();
    }
}
