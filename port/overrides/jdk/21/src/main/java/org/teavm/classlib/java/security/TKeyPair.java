package org.teavm.classlib.java.security;

import java.io.Serializable;

public final class TKeyPair implements Serializable {
    private final TPublicKey publicKey;
    private final TPrivateKey privateKey;

    public TKeyPair(TPublicKey publicKey, TPrivateKey privateKey) {
        this.publicKey = publicKey;
        this.privateKey = privateKey;
    }

    public TPublicKey getPublic() {
        return publicKey;
    }

    public TPrivateKey getPrivate() {
        return privateKey;
    }
}
