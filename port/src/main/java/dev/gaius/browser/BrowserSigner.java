package dev.gaius.browser;

import java.io.ByteArrayOutputStream;
import java.security.PrivateKey;
import java.security.SignatureException;
import net.minecraft.util.SignatureUpdater;
import net.minecraft.util.Signer;

/** SHA256withRSA signer for secure-profile and signed-chat packets in the browser. */
public final class BrowserSigner implements Signer {
    private final PrivateKey privateKey;

    private BrowserSigner(PrivateKey privateKey) {
        this.privateKey = privateKey;
    }

    public static Signer create(PrivateKey privateKey, String algorithm) {
        if (privateKey == null) {
            throw new IllegalArgumentException("RSA signing key is missing");
        }
        if (!"SHA256withRSA".equalsIgnoreCase(algorithm)) {
            throw new IllegalArgumentException("Unsupported browser signing algorithm: " + algorithm);
        }
        return new BrowserSigner(privateKey);
    }

    @Override
    public byte[] sign(SignatureUpdater updater) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(512);
        try {
            updater.update(bytes -> output.write(bytes, 0, bytes.length));
        } catch (SignatureException exception) {
            throw new IllegalStateException("Failed to collect signed message bytes", exception);
        }
        return BrowserCrypto.signUsingKey(privateKey, output.toByteArray());
    }
}
