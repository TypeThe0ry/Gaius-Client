import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.DeflaterOutputStream;
import org.teavm.classlib.java.nio.TByteOrder;
import org.teavm.classlib.java.util.zip.TDataFormatException;
import org.teavm.classlib.java.util.zip.TInflater;
import org.teavm.classlib.java.util.zip.TInflaterInputStream;

public class InflaterBufferCheck {
    public static void main(String[] args) throws IOException {
        byte[] plain = new byte[8192];
        new Random(17).nextBytes(plain);
        var buffer = new ByteArrayOutputStream();
        try (var compressor = new DeflaterOutputStream(buffer)) {
            compressor.write(plain);
        }
        byte[] compressed = buffer.toByteArray();
        var source = new ByteArrayInputStream(compressed) {
            private boolean firstRead = true;

            @Override
            public int read(byte[] bytes, int offset, int length) {
                if (firstRead) {
                    firstRead = false;
                    return 0;
                }
                return super.read(bytes, offset, Math.min(length, 29));
            }
        };
        var stream = new TInflaterInputStream(source, new TInflater(), 61);
        try (stream) {
            check(stream.available() == 1, "availability before reading");
            check(stream.skip(2333) == 2333, "skip across refills");
            check(Arrays.equals(stream.readAllBytes(), Arrays.copyOfRange(plain, 2333, plain.length)), "suffix after skip");
            check(stream.skip(1) == 0 && stream.available() == 0, "end of stream");
        }
        try (var truncated = new TInflaterInputStream(new ByteArrayInputStream(compressed, 0, compressed.length - 8))) {
            truncated.readAllBytes();
            throw new AssertionError("truncated input was accepted");
        } catch (IOException expected) {
            check(expected.getCause() instanceof TDataFormatException, "truncated input error");
        }
        check(TByteOrder.nativeOrder() == TByteOrder.LITTLE_ENDIAN, "browser byte order");
        check("BIG_ENDIAN".equals(TByteOrder.BIG_ENDIAN.toString()), "byte order name");
    }

    private static void check(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
    }
}
