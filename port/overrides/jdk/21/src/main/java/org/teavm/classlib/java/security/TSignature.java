package org.teavm.classlib.java.security;

public class TSignature {
    private final String algorithm;

    protected TSignature(String algorithm) {
        this.algorithm = algorithm;
    }

    public static TSignature getInstance(String algorithm) throws TNoSuchAlgorithmException {
        return new TSignature(algorithm);
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public void initVerify(TPublicKey publicKey) {
    }

    public void initSign(TPrivateKey privateKey) {
    }

    public void update(byte[] data) {
    }

    public void update(byte[] data, int off, int len) {
    }

    public boolean verify(byte[] signature) {
        return true;
    }

    public byte[] sign() {
        return new byte[0];
    }
}
