/*
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  Based on TeaVM 0.15's TInputStreamReader (Copyright Alexey Andreev, Apache License 2.0).
 *  Modified for Gaius: the byte buffer of a reader over a plain ByteArrayInputStream is sized
 *  from the bytes the stream holds (at least 64, at most the original 8192) instead of always
 *  8192 bytes. Every other line is TeaVM's.
 */

package org.teavm.classlib.java.io;

import java.io.IOException;
import org.teavm.classlib.java.nio.TByteBuffer;
import org.teavm.classlib.java.nio.TCharBuffer;
import org.teavm.classlib.java.nio.charset.TCharset;
import org.teavm.classlib.java.nio.charset.TCharsetDecoder;
import org.teavm.classlib.java.nio.charset.TCoderResult;
import org.teavm.classlib.java.nio.charset.TCodingErrorAction;
import org.teavm.classlib.java.nio.charset.TUnsupportedCharsetException;
import org.teavm.classlib.java.nio.charset.impl.TUTF8Charset;

/**
 * TeaVM's InputStreamReader with a smaller byte buffer for in-memory input.
 *
 * <p>The resource reload opens thousands of small JSON files as {@code new InputStreamReader(new
 * ByteArrayInputStream(bytes), UTF_8)}, and each reader zeroed an 8 KiB byte array. For a stream
 * whose class is exactly {@link TByteArrayInputStream}, {@code available()} is the exact number of
 * bytes left and {@code read(b, off, len)} returns {@code min(len, available())}; the buffer then
 * gets {@code max(64, available())} bytes, capped at 8192. Whenever that is below 8192 the first
 * read takes every byte of the stream, exactly as the 8192-byte buffer did, and every later read
 * returns -1, so the decoder sees the same bytes in the same calls and the reader returns the same
 * characters, counts, {@code ready()} answers and end of stream as before. Any other stream
 * (subclasses included, whose {@code available()} or {@code read} may differ) keeps 8192 bytes.
 */
public class TInputStreamReader extends TReader {
    private static final int DEFAULT_IN_DATA = 8192;
    private static final int MIN_IN_DATA = 64;
    private TInputStream stream;
    private TCharsetDecoder decoder;
    private byte[] inData;
    private TByteBuffer inBuffer;
    private char[] outData;
    private TCharBuffer outBuffer;
    private boolean streamEof;
    private boolean eof;

    public TInputStreamReader(TInputStream in, String charsetName) throws TUnsupportedEncodingException {
        this(in, getCharset(charsetName));
    }

    public TInputStreamReader(TInputStream in, TCharset charset) {
        this(in, charset.newDecoder()
                .onMalformedInput(TCodingErrorAction.REPLACE)
                .onUnmappableCharacter(TCodingErrorAction.REPLACE));
    }

    public TInputStreamReader(TInputStream in) {
        this(in, TUTF8Charset.INSTANCE);
    }

    public TInputStreamReader(TInputStream in, TCharsetDecoder decoder) {
        inData = new byte[inDataSize(in)];
        inBuffer = TByteBuffer.wrap(inData);
        outData = new char[1024];
        outBuffer = TCharBuffer.wrap(outData);
        this.stream = in;
        this.decoder = decoder;
        outBuffer.position(outBuffer.limit());
        inBuffer.position(inBuffer.limit());
    }

    private static int inDataSize(TInputStream in) {
        if (in == null || in.getClass() != TByteArrayInputStream.class) {
            return DEFAULT_IN_DATA;
        }
        int available = ((TByteArrayInputStream) in).available();
        return available >= DEFAULT_IN_DATA ? DEFAULT_IN_DATA : Math.max(MIN_IN_DATA, available);
    }

    private static TCharset getCharset(String charsetName) throws TUnsupportedEncodingException {
        try {
            return TCharset.forName(charsetName.toString());
        } catch (TUnsupportedCharsetException e) {
            throw new TUnsupportedEncodingException(charsetName);
        }
    }

    public String getEncoding() {
        return decoder.charset().name();
    }

    @Override
    public void close() throws IOException {
        stream.close();
    }

    @Override
    public int read() throws IOException {
        if (eof && !outBuffer.hasRemaining()) {
            return -1;
        }
        ensureBufferHasData(true);
        return outBuffer.hasRemaining() ? outBuffer.get() : -1;
    }

    @Override
    public int read(char[] cbuf, int off, int len) throws IOException {
        if (!outBuffer.hasRemaining()) {
            if (eof) {
                return -1;
            }
            if (len == 0) {
                return 0;
            }
            ensureBufferHasData(true);
        } else if (len == 0) {
            return 0;
        }
        int bytesRead = 0;
        do {
            int sz = Math.min(len, outBuffer.remaining());
            outBuffer.get(cbuf, off + bytesRead, sz);
            len -= sz;
            bytesRead += sz;
        } while (len > 0 && ensureBufferHasData(false));
        return bytesRead;
    }

    private boolean ensureBufferHasData(boolean blocking) throws IOException {
        if (outBuffer.hasRemaining()) {
            return true;
        }
        return fillBuffer(blocking);
    }

    private boolean fillBuffer(boolean blocking) throws IOException {
        if (eof) {
            return false;
        }
        outBuffer.compact();
        boolean hasData = !blocking;
        boolean result = true;
        boolean decoded = false;
        while (true) {
            if (inBuffer.hasRemaining()) {
                int position = outBuffer.position();
                TCoderResult coderResult = decoder.decode(inBuffer, outBuffer, streamEof);
                decoded = true;
                hasData |= outBuffer.position() > position;
                if (coderResult.isOverflow()) {
                    break;
                } else if (!coderResult.isUnderflow()) {
                    continue;
                }
            }
            if (stream.available() <= 0 && hasData) {
                result = false;
                break;
            }
            if (!fillReadBuffer()) {
                decoder.decode(inBuffer, outBuffer, true);
                break;
            }
        }
        if (!inBuffer.hasRemaining() && streamEof) {
            if (!decoded || decoder.flush(outBuffer).isUnderflow()) {
                eof = true;
            }
        }
        outBuffer.flip();
        return result;
    }

    private boolean fillReadBuffer() throws IOException {
        if (streamEof) {
            return false;
        }
        inBuffer.compact();
        while (inBuffer.hasRemaining()) {
            int bytesRead = stream.read(inBuffer.array(), inBuffer.position(), inBuffer.remaining());
            if (bytesRead == -1) {
                streamEof = true;
                break;
            } else if (bytesRead > 0) {
                inBuffer.position(inBuffer.position() + bytesRead);
                break;
            }
        }
        inBuffer.flip();
        return true;
    }

    @Override
    public boolean ready() throws IOException {
        return outBuffer.hasRemaining() || inBuffer.hasRemaining() || stream.available() > 0;
    }
}
