package dev.gaius.browser;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.teavm.jso.JSBody;

/** Browser-safe primitives required by the Minecraft online-mode login protocol. */
public final class BrowserCrypto {
    private static final int[] SHA256_CONSTANTS = {
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5,
        0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
        0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc,
        0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7,
        0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
        0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3,
        0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
        0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
        0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    };
    private static final byte[] SHA256_DIGEST_INFO_PREFIX = {
        0x30, 0x31, 0x30, 0x0d, 0x06, 0x09, 0x60, (byte) 0x86,
        0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x01, 0x05,
        0x00, 0x04, 0x20,
    };

    private BrowserCrypto() {
    }

    public static SecretKey generateSecretKey() {
        byte[] key = new byte[16];
        fillRandom(key);
        report("network.crypto.secretKey");
        return new SecretKeySpec(key, "AES");
    }

    public static PublicKey parseRsaPublicKey(byte[] encoded) {
        BrowserRsaPublicKey key = BrowserRsaPublicKey.parse(encoded);
        report("network.crypto.rsaPublicKey");
        return key;
    }

    public static PrivateKey parseRsaPrivateKey(byte[] encoded) {
        BrowserRsaPrivateKey key = BrowserRsaPrivateKey.parse(encoded);
        report("network.crypto.rsaPrivateKey");
        return key;
    }

    public static byte[] digestData(String serverId, PublicKey publicKey, SecretKey secretKey) {
        byte[] result = sha1(
                serverId.getBytes(StandardCharsets.ISO_8859_1),
                secretKey.getEncoded(),
                publicKey.getEncoded());
        report("network.crypto.sha1");
        return result;
    }

    public static byte[] encryptUsingKey(Key key, byte[] input) {
        BrowserRsaPublicKey rsa = key instanceof BrowserRsaPublicKey browserKey
                ? browserKey
                : BrowserRsaPublicKey.parse(key.getEncoded());
        byte[] encrypted = rsaEncrypt(rsa, input, null);
        report("network.crypto.rsaEncrypt");
        return encrypted;
    }

    public static BrowserAesCfb8 createAesCfb8(int mode, Key key) {
        BrowserAesCfb8 cipher = new BrowserAesCfb8(mode, key.getEncoded());
        report(mode == BrowserAesCfb8.ENCRYPT_MODE
                ? "network.crypto.aesEncrypt"
                : "network.crypto.aesDecrypt");
        return cipher;
    }

    public static byte[] sha1(byte[]... parts) {
        long byteLength = 0;
        for (byte[] part : parts) {
            if (part != null) {
                byteLength += part.length;
            }
        }
        if (byteLength > Integer.MAX_VALUE - 72L) {
            throw new IllegalArgumentException("SHA-1 input is too large");
        }
        int messageLength = (int) byteLength;
        int paddedLength = ((messageLength + 9 + 63) / 64) * 64;
        byte[] message = new byte[paddedLength];
        int position = 0;
        for (byte[] part : parts) {
            if (part != null && part.length > 0) {
                System.arraycopy(part, 0, message, position, part.length);
                position += part.length;
            }
        }
        message[messageLength] = (byte) 0x80;
        long bitLength = byteLength * 8L;
        for (int index = 0; index < 8; index++) {
            message[paddedLength - 1 - index] = (byte) (bitLength >>> (index * 8));
        }

        int h0 = 0x67452301;
        int h1 = 0xefcdab89;
        int h2 = 0x98badcfe;
        int h3 = 0x10325476;
        int h4 = 0xc3d2e1f0;
        int[] words = new int[80];
        for (int block = 0; block < paddedLength; block += 64) {
            for (int index = 0; index < 16; index++) {
                int offset = block + index * 4;
                words[index] = ((message[offset] & 0xff) << 24)
                        | ((message[offset + 1] & 0xff) << 16)
                        | ((message[offset + 2] & 0xff) << 8)
                        | (message[offset + 3] & 0xff);
            }
            for (int index = 16; index < words.length; index++) {
                words[index] = Integer.rotateLeft(
                        words[index - 3] ^ words[index - 8]
                                ^ words[index - 14] ^ words[index - 16],
                        1);
            }
            int a = h0;
            int b = h1;
            int c = h2;
            int d = h3;
            int e = h4;
            for (int index = 0; index < words.length; index++) {
                int function;
                int constant;
                if (index < 20) {
                    function = (b & c) | (~b & d);
                    constant = 0x5a827999;
                } else if (index < 40) {
                    function = b ^ c ^ d;
                    constant = 0x6ed9eba1;
                } else if (index < 60) {
                    function = (b & c) | (b & d) | (c & d);
                    constant = 0x8f1bbcdc;
                } else {
                    function = b ^ c ^ d;
                    constant = 0xca62c1d6;
                }
                int temporary = Integer.rotateLeft(a, 5)
                        + function + e + constant + words[index];
                e = d;
                d = c;
                c = Integer.rotateLeft(b, 30);
                b = a;
                a = temporary;
            }
            h0 += a;
            h1 += b;
            h2 += c;
            h3 += d;
            h4 += e;
        }
        byte[] digest = new byte[20];
        writeInt(digest, 0, h0);
        writeInt(digest, 4, h1);
        writeInt(digest, 8, h2);
        writeInt(digest, 12, h3);
        writeInt(digest, 16, h4);
        return digest;
    }

    public static byte[] sha256(byte[] input) {
        if (input == null) {
            throw new IllegalArgumentException("SHA-256 input is missing");
        }
        if (input.length > Integer.MAX_VALUE - 72) {
            throw new IllegalArgumentException("SHA-256 input is too large");
        }
        int paddedLength = ((input.length + 9 + 63) / 64) * 64;
        byte[] message = Arrays.copyOf(input, paddedLength);
        message[input.length] = (byte) 0x80;
        long bitLength = (long) input.length * 8L;
        for (int index = 0; index < 8; index++) {
            message[paddedLength - 1 - index] = (byte) (bitLength >>> (index * 8));
        }

        int[] hash = {
            0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
            0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
        };
        int[] words = new int[64];
        for (int block = 0; block < paddedLength; block += 64) {
            for (int index = 0; index < 16; index++) {
                int offset = block + index * 4;
                words[index] = ((message[offset] & 0xff) << 24)
                        | ((message[offset + 1] & 0xff) << 16)
                        | ((message[offset + 2] & 0xff) << 8)
                        | (message[offset + 3] & 0xff);
            }
            for (int index = 16; index < words.length; index++) {
                int before15 = words[index - 15];
                int before2 = words[index - 2];
                int sigma0 = Integer.rotateRight(before15, 7)
                        ^ Integer.rotateRight(before15, 18) ^ (before15 >>> 3);
                int sigma1 = Integer.rotateRight(before2, 17)
                        ^ Integer.rotateRight(before2, 19) ^ (before2 >>> 10);
                words[index] = words[index - 16] + sigma0
                        + words[index - 7] + sigma1;
            }
            int a = hash[0];
            int b = hash[1];
            int c = hash[2];
            int d = hash[3];
            int e = hash[4];
            int f = hash[5];
            int g = hash[6];
            int h = hash[7];
            for (int index = 0; index < words.length; index++) {
                int sum1 = Integer.rotateRight(e, 6)
                        ^ Integer.rotateRight(e, 11) ^ Integer.rotateRight(e, 25);
                int choice = (e & f) ^ (~e & g);
                int temporary1 = h + sum1 + choice + SHA256_CONSTANTS[index] + words[index];
                int sum0 = Integer.rotateRight(a, 2)
                        ^ Integer.rotateRight(a, 13) ^ Integer.rotateRight(a, 22);
                int majority = (a & b) ^ (a & c) ^ (b & c);
                int temporary2 = sum0 + majority;
                h = g;
                g = f;
                f = e;
                e = d + temporary1;
                d = c;
                c = b;
                b = a;
                a = temporary1 + temporary2;
            }
            hash[0] += a;
            hash[1] += b;
            hash[2] += c;
            hash[3] += d;
            hash[4] += e;
            hash[5] += f;
            hash[6] += g;
            hash[7] += h;
        }
        byte[] digest = new byte[32];
        for (int index = 0; index < hash.length; index++) {
            writeInt(digest, index * 4, hash[index]);
        }
        return digest;
    }

    public static byte[] signUsingKey(PrivateKey key, byte[] input) {
        BrowserRsaPrivateKey rsa = key instanceof BrowserRsaPrivateKey browserKey
                ? browserKey
                : BrowserRsaPrivateKey.parse(key.getEncoded());
        byte[] digest = sha256(input);
        int encodedLength = (rsa.modulus().bitLength() + 7) / 8;
        int paddingLength = encodedLength - SHA256_DIGEST_INFO_PREFIX.length - digest.length - 3;
        if (paddingLength < 8) {
            throw new IllegalArgumentException("RSA key is too small for SHA-256 signing");
        }
        byte[] encoded = new byte[encodedLength];
        encoded[0] = 0;
        encoded[1] = 1;
        Arrays.fill(encoded, 2, 2 + paddingLength, (byte) 0xff);
        int digestInfoOffset = 3 + paddingLength;
        System.arraycopy(
                SHA256_DIGEST_INFO_PREFIX,
                0,
                encoded,
                digestInfoOffset,
                SHA256_DIGEST_INFO_PREFIX.length);
        System.arraycopy(
                digest,
                0,
                encoded,
                digestInfoOffset + SHA256_DIGEST_INFO_PREFIX.length,
                digest.length);
        BigInteger signature = rsa.applyPrivate(new BigInteger(1, encoded));
        byte[] raw = signature.toByteArray();
        byte[] result = new byte[encodedLength];
        int copyLength = Math.min(raw.length, result.length);
        System.arraycopy(raw, raw.length - copyLength, result, result.length - copyLength, copyLength);
        report("network.crypto.rsaSign");
        return result;
    }

    public static byte[] rsaEncryptForTesting(PublicKey key, byte[] input, byte[] padding) {
        BrowserRsaPublicKey rsa = key instanceof BrowserRsaPublicKey browserKey
                ? browserKey
                : BrowserRsaPublicKey.parse(key.getEncoded());
        return rsaEncrypt(rsa, input, padding);
    }

    private static byte[] rsaEncrypt(BrowserRsaPublicKey key, byte[] input, byte[] padding) {
        int encodedLength = (key.modulus().bitLength() + 7) / 8;
        int paddingLength = encodedLength - input.length - 3;
        if (paddingLength < 8) {
            throw new IllegalArgumentException("RSA input is too long");
        }
        byte[] nonZeroPadding;
        if (padding == null) {
            nonZeroPadding = new byte[paddingLength];
            fillNonZeroRandom(nonZeroPadding);
        } else {
            if (padding.length != paddingLength) {
                throw new IllegalArgumentException("RSA test padding has the wrong length");
            }
            nonZeroPadding = padding.clone();
            for (byte value : nonZeroPadding) {
                if (value == 0) {
                    throw new IllegalArgumentException("RSA PKCS#1 padding must be non-zero");
                }
            }
        }
        byte[] encoded = new byte[encodedLength];
        encoded[0] = 0;
        encoded[1] = 2;
        System.arraycopy(nonZeroPadding, 0, encoded, 2, nonZeroPadding.length);
        encoded[2 + nonZeroPadding.length] = 0;
        System.arraycopy(input, 0, encoded, 3 + nonZeroPadding.length, input.length);
        BigInteger message = new BigInteger(1, encoded);
        BigInteger encrypted = message.modPow(key.exponent(), key.modulus());
        byte[] raw = encrypted.toByteArray();
        byte[] result = new byte[encodedLength];
        int copyLength = Math.min(raw.length, result.length);
        System.arraycopy(raw, raw.length - copyLength, result, result.length - copyLength, copyLength);
        return result;
    }

    private static void fillNonZeroRandom(byte[] target) {
        fillRandom(target);
        byte[] retry = new byte[target.length];
        boolean hasZero = true;
        while (hasZero) {
            hasZero = false;
            fillRandom(retry);
            for (int index = 0; index < target.length; index++) {
                if (target[index] == 0) {
                    target[index] = retry[index];
                    hasZero |= target[index] == 0;
                }
            }
        }
    }

    private static void writeInt(byte[] output, int offset, int value) {
        output[offset] = (byte) (value >>> 24);
        output[offset + 1] = (byte) (value >>> 16);
        output[offset + 2] = (byte) (value >>> 8);
        output[offset + 3] = (byte) value;
    }

    private static void fillRandom(byte[] bytes) {
        for (int offset = 0; offset < bytes.length; offset += Integer.BYTES) {
            int random = secureRandomInt();
            int count = Math.min(Integer.BYTES, bytes.length - offset);
            for (int index = 0; index < count; index++) {
                bytes[offset + index] = (byte) (random >>> (index * Byte.SIZE));
            }
        }
    }

    @JSBody(script = """
            const random = new Uint32Array(1);
            globalThis.crypto.getRandomValues(random);
            return random[0] | 0;
            """)
    private static native int secureRandomInt();

    private static void report(String event) {
        try {
            reportBrowser(event);
        } catch (UnsatisfiedLinkError ignored) {
            // JVM-side algorithm verification does not install TeaVM JS bodies.
        }
    }

    @JSBody(params = {"event"}, script = """
            const counters = globalThis.__gaiusMinecraftCounters || (globalThis.__gaiusMinecraftCounters = {});
            counters[event] = (counters[event] || 0) + 1;
            const events = globalThis.__gaiusMinecraftEvents || (globalThis.__gaiusMinecraftEvents = []);
            events.push({ event: event, detail: null, count: counters[event], at: Date.now() });
            if (events.length > 500) events.splice(0, events.length - 500);
            """)
    private static native void reportBrowser(String event);

    public static final class BrowserRsaPublicKey implements PublicKey {
        private static final long serialVersionUID = 1L;

        private final byte[] encoded;
        private final BigInteger modulus;
        private final BigInteger exponent;

        private BrowserRsaPublicKey(byte[] encoded, BigInteger modulus, BigInteger exponent) {
            this.encoded = encoded.clone();
            this.modulus = modulus;
            this.exponent = exponent;
        }

        static BrowserRsaPublicKey parse(byte[] encoded) {
            if (encoded == null || encoded.length == 0) {
                throw new IllegalArgumentException("RSA public key is empty");
            }
            DerReader outer = new DerReader(encoded).readSequence();
            outer.readElement(0x30);
            byte[] bitString = outer.readElement(0x03);
            if (bitString.length < 2 || bitString[0] != 0) {
                throw new IllegalArgumentException("RSA public key bit string is invalid");
            }
            byte[] rsaBytes = Arrays.copyOfRange(bitString, 1, bitString.length);
            DerReader rsa = new DerReader(rsaBytes).readSequence();
            BigInteger modulus = new BigInteger(1, rsa.readElement(0x02));
            BigInteger exponent = new BigInteger(1, rsa.readElement(0x02));
            if (modulus.signum() <= 0 || exponent.signum() <= 0) {
                throw new IllegalArgumentException("RSA public key values are invalid");
            }
            return new BrowserRsaPublicKey(encoded, modulus, exponent);
        }

        BigInteger modulus() {
            return modulus;
        }

        BigInteger exponent() {
            return exponent;
        }

        @Override
        public String getAlgorithm() {
            return "RSA";
        }

        @Override
        public String getFormat() {
            return "X.509";
        }

        @Override
        public byte[] getEncoded() {
            return encoded.clone();
        }
    }

    public static final class BrowserRsaPrivateKey implements PrivateKey {
        private static final long serialVersionUID = 1L;

        private final byte[] encoded;
        private final BigInteger modulus;
        private final BigInteger privateExponent;
        private final BigInteger primeP;
        private final BigInteger primeQ;
        private final BigInteger primeExponentP;
        private final BigInteger primeExponentQ;
        private final BigInteger crtCoefficient;

        private BrowserRsaPrivateKey(
                byte[] encoded,
                BigInteger modulus,
                BigInteger privateExponent,
                BigInteger primeP,
                BigInteger primeQ,
                BigInteger primeExponentP,
                BigInteger primeExponentQ,
                BigInteger crtCoefficient) {
            this.encoded = encoded.clone();
            this.modulus = modulus;
            this.privateExponent = privateExponent;
            this.primeP = primeP;
            this.primeQ = primeQ;
            this.primeExponentP = primeExponentP;
            this.primeExponentQ = primeExponentQ;
            this.crtCoefficient = crtCoefficient;
        }

        static BrowserRsaPrivateKey parse(byte[] encoded) {
            if (encoded == null || encoded.length == 0) {
                throw new IllegalArgumentException("RSA private key is empty");
            }
            DerReader privateKeyInfo = new DerReader(encoded).readSequence();
            privateKeyInfo.readElement(0x02);
            privateKeyInfo.readElement(0x30);
            byte[] privateKeyBytes = privateKeyInfo.readElement(0x04);
            DerReader rsa = new DerReader(privateKeyBytes).readSequence();
            rsa.readElement(0x02);
            BigInteger modulus = positiveInteger(rsa.readElement(0x02));
            rsa.readElement(0x02);
            BigInteger privateExponent = positiveInteger(rsa.readElement(0x02));
            BigInteger primeP = positiveInteger(rsa.readElement(0x02));
            BigInteger primeQ = positiveInteger(rsa.readElement(0x02));
            BigInteger primeExponentP = positiveInteger(rsa.readElement(0x02));
            BigInteger primeExponentQ = positiveInteger(rsa.readElement(0x02));
            BigInteger crtCoefficient = positiveInteger(rsa.readElement(0x02));
            if (modulus.signum() <= 0 || privateExponent.signum() <= 0
                    || primeP.signum() <= 0 || primeQ.signum() <= 0) {
                throw new IllegalArgumentException("RSA private key values are invalid");
            }
            return new BrowserRsaPrivateKey(
                    encoded,
                    modulus,
                    privateExponent,
                    primeP,
                    primeQ,
                    primeExponentP,
                    primeExponentQ,
                    crtCoefficient);
        }

        private static BigInteger positiveInteger(byte[] value) {
            return new BigInteger(1, value);
        }

        BigInteger modulus() {
            return modulus;
        }

        BigInteger applyPrivate(BigInteger message) {
            if (primeP.signum() > 0 && primeQ.signum() > 0
                    && primeExponentP.signum() > 0 && primeExponentQ.signum() > 0
                    && crtCoefficient.signum() > 0) {
                BigInteger first = message.modPow(primeExponentP, primeP);
                BigInteger second = message.modPow(primeExponentQ, primeQ);
                BigInteger factor = first.subtract(second).multiply(crtCoefficient).mod(primeP);
                return second.add(primeQ.multiply(factor)).mod(modulus);
            }
            return message.modPow(privateExponent, modulus);
        }

        @Override
        public String getAlgorithm() {
            return "RSA";
        }

        @Override
        public String getFormat() {
            return "PKCS#8";
        }

        @Override
        public byte[] getEncoded() {
            return encoded.clone();
        }
    }

    private static final class DerReader {
        private final byte[] data;
        private int position;

        private DerReader(byte[] data) {
            this.data = data;
        }

        private DerReader readSequence() {
            return new DerReader(readElement(0x30));
        }

        private byte[] readElement(int expectedTag) {
            if (position >= data.length || (data[position++] & 0xff) != expectedTag) {
                throw new IllegalArgumentException("Unexpected DER tag");
            }
            int length = readLength();
            if (length < 0 || position + length > data.length) {
                throw new IllegalArgumentException("Invalid DER length");
            }
            byte[] value = Arrays.copyOfRange(data, position, position + length);
            position += length;
            return value;
        }

        private int readLength() {
            if (position >= data.length) {
                throw new IllegalArgumentException("Missing DER length");
            }
            int first = data[position++] & 0xff;
            if ((first & 0x80) == 0) {
                return first;
            }
            int count = first & 0x7f;
            if (count == 0 || count > 4 || position + count > data.length) {
                throw new IllegalArgumentException("Unsupported DER length");
            }
            int length = 0;
            for (int index = 0; index < count; index++) {
                length = (length << 8) | (data[position++] & 0xff);
            }
            return length;
        }
    }
}
