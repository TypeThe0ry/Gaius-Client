/*
 * Copyright (c) LWJGL
 * SPDX-License-Identifier: BSD-3-Clause
 *
 * The encodeUTF8(CharSequence, boolean) method follows LWJGL's string encoder.
 * Modified for Gaius: write encoded bytes through browser-managed memory.
 */

package org.lwjgl.system;

import java.nio.Buffer;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import java.nio.charset.StandardCharsets;

/**
 * LWJGL 3.4.3 {@link MemoryBackend} over the browser virtual memory of
 * {@link BrowserMemory}.
 *
 * <p>LWJGL 3.4.3 routes most of {@code MemoryUtil}, and {@code MemoryStack}'s
 * buffer and string helpers, through the {@code MemoryUtil.BACKEND} field.
 * {@code LwjglMemoryPatcher} assigns {@link #INSTANCE} to that field and
 * replaces the reflective {@code MemoryUtil.createBackend()}, so this is the
 * only backend TeaVM can reach.</p>
 *
 * <p>Only the public {@link BrowserMemory} API is used; what it lacks (char
 * access, the unaligned aliases, array copies, string encoding) is
 * implemented here. Addresses are {@link BrowserMemory} virtual addresses,
 * so unaligned access needs no special handling. Pointers and C longs are
 * 8 bytes, matching the browser {@code Pointer.POINTER_SIZE}.</p>
 */
public final class BrowserMemoryBackend implements MemoryBackend {
    public static final BrowserMemoryBackend INSTANCE = new BrowserMemoryBackend();

    private static final boolean LITTLE_ENDIAN =
            ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
    private static final Class<?> BYTE_BUFFER = BrowserMemory.bufferClass(0);
    private static final Class<?> SHORT_BUFFER = BrowserMemory.bufferClass(1);
    private static final Class<?> CHAR_BUFFER = BrowserMemory.bufferClass(2);
    private static final Class<?> INT_BUFFER = BrowserMemory.bufferClass(3);
    private static final Class<?> LONG_BUFFER = BrowserMemory.bufferClass(4);
    private static final Class<?> FLOAT_BUFFER = BrowserMemory.bufferClass(5);
    private static final Class<?> DOUBLE_BUFFER = BrowserMemory.bufferClass(6);

    private BrowserMemoryBackend() {
    }

    // Scalar reads.

    @Override
    public boolean getBoolean(long address) {
        return BrowserMemory.getBoolean(address);
    }

    @Override
    public byte getByte(long address) {
        return BrowserMemory.getByte(address);
    }

    @Override
    public char getChar(long address) {
        return (char) BrowserMemory.getShort(address);
    }

    @Override
    public char getCharUnaligned(long address) {
        return (char) BrowserMemory.getShort(address);
    }

    @Override
    public short getShort(long address) {
        return BrowserMemory.getShort(address);
    }

    @Override
    public short getShortUnaligned(long address) {
        return BrowserMemory.getShort(address);
    }

    @Override
    public int getInt(long address) {
        return BrowserMemory.getInt(address);
    }

    @Override
    public int getIntUnaligned(long address) {
        return BrowserMemory.getInt(address);
    }

    @Override
    public long getLong(long address) {
        return BrowserMemory.getLong(address);
    }

    @Override
    public long getLongUnaligned(long address) {
        return BrowserMemory.getLong(address);
    }

    /** Reads a native-order long from {@code array[offset..offset + 8)}. */
    @Override
    public long getLong(byte[] array, long offset) {
        if (offset < 0L || offset > array.length - 8L) {
            throw new ArrayIndexOutOfBoundsException(
                    "Invalid long read at " + offset + " of byte[" + array.length + "]");
        }
        int index = (int) offset;
        long value = 0L;
        if (LITTLE_ENDIAN) {
            for (int shift = 7; shift >= 0; shift--) {
                value = (value << 8) | (array[index + shift] & 0xffL);
            }
        } else {
            for (int shift = 0; shift < 8; shift++) {
                value = (value << 8) | (array[index + shift] & 0xffL);
            }
        }
        return value;
    }

    @Override
    public float getFloat(long address) {
        return BrowserMemory.getFloat(address);
    }

    @Override
    public float getFloatUnaligned(long address) {
        return BrowserMemory.getFloat(address);
    }

    @Override
    public double getDouble(long address) {
        return BrowserMemory.getDouble(address);
    }

    @Override
    public double getDoubleUnaligned(long address) {
        return BrowserMemory.getDouble(address);
    }

    @Override
    public long getCLong(long address) {
        return BrowserMemory.getCLong(address);
    }

    @Override
    public long getCLongUnaligned(long address) {
        return BrowserMemory.getCLong(address);
    }

    @Override
    public long getAddress(long address) {
        return BrowserMemory.getLong(address);
    }

    @Override
    public long getAddressUnaligned(long address) {
        return BrowserMemory.getLong(address);
    }

    // Scalar writes.

    @Override
    public void putBoolean(long address, boolean value) {
        BrowserMemory.putByte(address, value ? (byte) 1 : (byte) 0);
    }

    @Override
    public void putByte(long address, byte value) {
        BrowserMemory.putByte(address, value);
    }

    @Override
    public void putChar(long address, char value) {
        BrowserMemory.putShort(address, (short) value);
    }

    @Override
    public void putCharUnaligned(long address, char value) {
        BrowserMemory.putShort(address, (short) value);
    }

    @Override
    public void putShort(long address, short value) {
        BrowserMemory.putShort(address, value);
    }

    @Override
    public void putShortUnaligned(long address, short value) {
        BrowserMemory.putShort(address, value);
    }

    @Override
    public void putInt(long address, int value) {
        BrowserMemory.putInt(address, value);
    }

    @Override
    public void putIntUnaligned(long address, int value) {
        BrowserMemory.putInt(address, value);
    }

    @Override
    public void putLong(long address, long value) {
        BrowserMemory.putLong(address, value);
    }

    @Override
    public void putLongUnaligned(long address, long value) {
        BrowserMemory.putLong(address, value);
    }

    @Override
    public void putFloat(long address, float value) {
        BrowserMemory.putFloat(address, value);
    }

    @Override
    public void putFloatUnaligned(long address, float value) {
        BrowserMemory.putFloat(address, value);
    }

    @Override
    public void putDouble(long address, double value) {
        BrowserMemory.putDouble(address, value);
    }

    @Override
    public void putDoubleUnaligned(long address, double value) {
        BrowserMemory.putDouble(address, value);
    }

    @Override
    public void putCLong(long address, long value) {
        BrowserMemory.putCLong(address, value);
    }

    @Override
    public void putCLongUnaligned(long address, long value) {
        BrowserMemory.putCLong(address, value);
    }

    @Override
    public void putAddress(long address, long value) {
        BrowserMemory.putLong(address, value);
    }

    @Override
    public void putAddressUnaligned(long address, long value) {
        BrowserMemory.putLong(address, value);
    }

    // Buffer addresses.

    @Override
    public long getAddress0(Buffer buffer) {
        return BrowserMemory.address0(buffer);
    }

    @Override
    public long getAddress0(ByteBuffer buffer) {
        return BrowserMemory.address0(buffer);
    }

    @Override
    public long getAddress0(CharBuffer buffer) {
        return BrowserMemory.address0(buffer);
    }

    @Override
    public long getAddress0(ShortBuffer buffer) {
        return BrowserMemory.address0(buffer);
    }

    @Override
    public long getAddress0(IntBuffer buffer) {
        return BrowserMemory.address0(buffer);
    }

    @Override
    public long getAddress0(LongBuffer buffer) {
        return BrowserMemory.address0(buffer);
    }

    @Override
    public long getAddress0(FloatBuffer buffer) {
        return BrowserMemory.address0(buffer);
    }

    @Override
    public long getAddress0(DoubleBuffer buffer) {
        return BrowserMemory.address0(buffer);
    }

    @Override
    public long getAddress(Buffer buffer) {
        return BrowserMemory.address(buffer);
    }

    @Override
    public long getAddress(Buffer buffer, int index) {
        return BrowserMemory.addressAt(buffer, index);
    }

    @Override
    public long getAddress(ByteBuffer buffer) {
        return BrowserMemory.address(buffer);
    }

    @Override
    public long getAddress(ByteBuffer buffer, int index) {
        return BrowserMemory.addressAt(buffer, index);
    }

    @Override
    public long getAddress(CharBuffer buffer) {
        return BrowserMemory.address(buffer);
    }

    @Override
    public long getAddress(CharBuffer buffer, int index) {
        return BrowserMemory.addressAt(buffer, index);
    }

    @Override
    public long getAddress(ShortBuffer buffer) {
        return BrowserMemory.address(buffer);
    }

    @Override
    public long getAddress(ShortBuffer buffer, int index) {
        return BrowserMemory.addressAt(buffer, index);
    }

    @Override
    public long getAddress(IntBuffer buffer) {
        return BrowserMemory.address(buffer);
    }

    @Override
    public long getAddress(IntBuffer buffer, int index) {
        return BrowserMemory.addressAt(buffer, index);
    }

    @Override
    public long getAddress(LongBuffer buffer) {
        return BrowserMemory.address(buffer);
    }

    @Override
    public long getAddress(LongBuffer buffer, int index) {
        return BrowserMemory.addressAt(buffer, index);
    }

    @Override
    public long getAddress(FloatBuffer buffer) {
        return BrowserMemory.address(buffer);
    }

    @Override
    public long getAddress(FloatBuffer buffer, int index) {
        return BrowserMemory.addressAt(buffer, index);
    }

    @Override
    public long getAddress(DoubleBuffer buffer) {
        return BrowserMemory.address(buffer);
    }

    @Override
    public long getAddress(DoubleBuffer buffer, int index) {
        return BrowserMemory.addressAt(buffer, index);
    }

    // Buffer views over virtual memory.

    @Override
    public ByteBuffer wrapBufferByte(long address, int capacity) {
        return (ByteBuffer) BrowserMemory.wrap(BYTE_BUFFER, address, capacity);
    }

    @Override
    public CharBuffer wrapBufferChar(long address, int capacity) {
        return (CharBuffer) BrowserMemory.wrap(CHAR_BUFFER, address, capacity);
    }

    @Override
    public ShortBuffer wrapBufferShort(long address, int capacity) {
        return (ShortBuffer) BrowserMemory.wrap(SHORT_BUFFER, address, capacity);
    }

    @Override
    public IntBuffer wrapBufferInt(long address, int capacity) {
        return (IntBuffer) BrowserMemory.wrap(INT_BUFFER, address, capacity);
    }

    @Override
    public LongBuffer wrapBufferLong(long address, int capacity) {
        return (LongBuffer) BrowserMemory.wrap(LONG_BUFFER, address, capacity);
    }

    @Override
    public FloatBuffer wrapBufferFloat(long address, int capacity) {
        return (FloatBuffer) BrowserMemory.wrap(FLOAT_BUFFER, address, capacity);
    }

    @Override
    public DoubleBuffer wrapBufferDouble(long address, int capacity) {
        return (DoubleBuffer) BrowserMemory.wrap(DOUBLE_BUFFER, address, capacity);
    }

    @Override
    public ByteBuffer duplicate(ByteBuffer buffer) {
        return BrowserMemory.duplicate(buffer);
    }

    @Override
    public CharBuffer duplicate(CharBuffer buffer) {
        return BrowserMemory.duplicate(buffer);
    }

    @Override
    public ShortBuffer duplicate(ShortBuffer buffer) {
        return BrowserMemory.duplicate(buffer);
    }

    @Override
    public IntBuffer duplicate(IntBuffer buffer) {
        return BrowserMemory.duplicate(buffer);
    }

    @Override
    public LongBuffer duplicate(LongBuffer buffer) {
        return BrowserMemory.duplicate(buffer);
    }

    @Override
    public FloatBuffer duplicate(FloatBuffer buffer) {
        return BrowserMemory.duplicate(buffer);
    }

    @Override
    public DoubleBuffer duplicate(DoubleBuffer buffer) {
        return BrowserMemory.duplicate(buffer);
    }

    @Override
    public ByteBuffer slice(ByteBuffer buffer) {
        return BrowserMemory.slice(buffer);
    }

    @Override
    public ByteBuffer slice(ByteBuffer buffer, int offset, int capacity) {
        return BrowserMemory.slice(buffer, offset, capacity);
    }

    @Override
    public CharBuffer slice(CharBuffer buffer) {
        return BrowserMemory.slice(buffer);
    }

    @Override
    public CharBuffer slice(CharBuffer buffer, int offset, int capacity) {
        return BrowserMemory.slice(buffer, offset, capacity);
    }

    @Override
    public ShortBuffer slice(ShortBuffer buffer) {
        return BrowserMemory.slice(buffer);
    }

    @Override
    public ShortBuffer slice(ShortBuffer buffer, int offset, int capacity) {
        return BrowserMemory.slice(buffer, offset, capacity);
    }

    @Override
    public IntBuffer slice(IntBuffer buffer) {
        return BrowserMemory.slice(buffer);
    }

    @Override
    public IntBuffer slice(IntBuffer buffer, int offset, int capacity) {
        return BrowserMemory.slice(buffer, offset, capacity);
    }

    @Override
    public LongBuffer slice(LongBuffer buffer) {
        return BrowserMemory.slice(buffer);
    }

    @Override
    public LongBuffer slice(LongBuffer buffer, int offset, int capacity) {
        return BrowserMemory.slice(buffer, offset, capacity);
    }

    @Override
    public FloatBuffer slice(FloatBuffer buffer) {
        return BrowserMemory.slice(buffer);
    }

    @Override
    public FloatBuffer slice(FloatBuffer buffer, int offset, int capacity) {
        return BrowserMemory.slice(buffer, offset, capacity);
    }

    @Override
    public DoubleBuffer slice(DoubleBuffer buffer) {
        return BrowserMemory.slice(buffer);
    }

    @Override
    public DoubleBuffer slice(DoubleBuffer buffer, int offset, int capacity) {
        return BrowserMemory.slice(buffer, offset, capacity);
    }

    // Bulk memory.

    @Override
    public void memset(long address, int value, long bytes) {
        BrowserMemory.set(address, value, bytes);
    }

    /** Source first, like LWJGL's backends (libc memcpy is destination first). */
    @Override
    public void memcpy(long source, long destination, long bytes) {
        BrowserMemory.copy(source, destination, bytes);
    }

    // Array to memory: memcpy(array, destination, first element, element count).

    @Override
    public void memcpy(byte[] source, long destination, int index, int length) {
        checkArrayRange(source.length, index, length);
        writeBytes(destination, source, index, length);
    }

    @Override
    public void memcpy(char[] source, long destination, int index, int length) {
        checkArrayRange(source.length, index, length);
        checkMemoryRange(destination, length, 1);
        for (int element = 0; element < length; element++) {
            BrowserMemory.putShort(destination + ((long) element << 1),
                    (short) source[index + element]);
        }
    }

    @Override
    public void memcpy(short[] source, long destination, int index, int length) {
        checkArrayRange(source.length, index, length);
        checkMemoryRange(destination, length, 1);
        for (int element = 0; element < length; element++) {
            BrowserMemory.putShort(destination + ((long) element << 1), source[index + element]);
        }
    }

    @Override
    public void memcpy(int[] source, long destination, int index, int length) {
        checkArrayRange(source.length, index, length);
        checkMemoryRange(destination, length, 2);
        for (int element = 0; element < length; element++) {
            BrowserMemory.putInt(destination + ((long) element << 2), source[index + element]);
        }
    }

    @Override
    public void memcpy(long[] source, long destination, int index, int length) {
        checkArrayRange(source.length, index, length);
        checkMemoryRange(destination, length, 3);
        for (int element = 0; element < length; element++) {
            BrowserMemory.putLong(destination + ((long) element << 3), source[index + element]);
        }
    }

    @Override
    public void memcpy(float[] source, long destination, int index, int length) {
        checkArrayRange(source.length, index, length);
        checkMemoryRange(destination, length, 2);
        for (int element = 0; element < length; element++) {
            BrowserMemory.putFloat(destination + ((long) element << 2), source[index + element]);
        }
    }

    @Override
    public void memcpy(double[] source, long destination, int index, int length) {
        checkArrayRange(source.length, index, length);
        checkMemoryRange(destination, length, 3);
        for (int element = 0; element < length; element++) {
            BrowserMemory.putDouble(destination + ((long) element << 3), source[index + element]);
        }
    }

    // Memory to array: memcpy(source, array, first element, element count).

    @Override
    public void memcpy(long source, byte[] destination, int index, int length) {
        checkArrayRange(destination.length, index, length);
        readBytes(source, destination, index, length);
    }

    @Override
    public void memcpy(long source, char[] destination, int index, int length) {
        checkArrayRange(destination.length, index, length);
        checkMemoryRange(source, length, 1);
        for (int element = 0; element < length; element++) {
            destination[index + element] =
                    (char) BrowserMemory.getShort(source + ((long) element << 1));
        }
    }

    @Override
    public void memcpy(long source, short[] destination, int index, int length) {
        checkArrayRange(destination.length, index, length);
        checkMemoryRange(source, length, 1);
        for (int element = 0; element < length; element++) {
            destination[index + element] = BrowserMemory.getShort(source + ((long) element << 1));
        }
    }

    @Override
    public void memcpy(long source, int[] destination, int index, int length) {
        checkArrayRange(destination.length, index, length);
        checkMemoryRange(source, length, 2);
        for (int element = 0; element < length; element++) {
            destination[index + element] = BrowserMemory.getInt(source + ((long) element << 2));
        }
    }

    @Override
    public void memcpy(long source, long[] destination, int index, int length) {
        checkArrayRange(destination.length, index, length);
        checkMemoryRange(source, length, 3);
        for (int element = 0; element < length; element++) {
            destination[index + element] = BrowserMemory.getLong(source + ((long) element << 3));
        }
    }

    @Override
    public void memcpy(long source, float[] destination, int index, int length) {
        checkArrayRange(destination.length, index, length);
        checkMemoryRange(source, length, 2);
        for (int element = 0; element < length; element++) {
            destination[index + element] = BrowserMemory.getFloat(source + ((long) element << 2));
        }
    }

    @Override
    public void memcpy(long source, double[] destination, int index, int length) {
        checkArrayRange(destination.length, index, length);
        checkMemoryRange(source, length, 3);
        for (int element = 0; element < length; element++) {
            destination[index + element] = BrowserMemory.getDouble(source + ((long) element << 3));
        }
    }

    // Strings.  The length-less getString* defaults measure the string with
    // MemoryUtil.strlenNT1Checked/strlenNT2Checked, which LwjglMemoryPatcher
    // delegates to BrowserMemory's byte-wise scan.

    @Override
    public String getStringASCII(long address, int length) {
        return length == 0 ? "" : BrowserMemory.decodeAscii(address, length);
    }

    @Override
    public String getStringUTF8(long address, int length) {
        return BrowserMemory.decodeUtf8(address, length);
    }

    /** {@code length} counts UTF-16 code units. */
    @Override
    public String getStringUTF16(long address, int length) {
        if (length == 0) {
            return "";
        }
        checkMemoryRange(address, length, 1);
        char[] chars = new char[length];
        for (int index = 0; index < length; index++) {
            chars[index] = (char) BrowserMemory.getShort(address + ((long) index << 1));
        }
        return new String(chars, 0, length);
    }

    @Override
    public void putStringASCII(String text, boolean nullTerminated, long target) {
        writeBytes(target, encodeASCII(text, nullTerminated));
    }

    @Override
    public void putStringASCII(CharSequence text, boolean nullTerminated, long target) {
        writeBytes(target, encodeASCII(text, nullTerminated));
    }

    /**
     * Encodes into memory taken from {@code allocator}, which for
     * {@code MemoryStack.UTF8} is the stack itself: the string then lives in
     * the current stack frame and is released by {@code pop()}.
     */
    @Override
    public ByteBuffer allocateUTF8(
            String text, boolean nullTerminated, MemoryUtil.MemoryAllocator allocator) {
        byte[] encoded = text.getBytes(StandardCharsets.UTF_8);
        int length = terminatedLength(encoded.length, nullTerminated);
        long address = allocator.malloc(length);
        if (address == 0L) {
            throw new OutOfMemoryError(
                    "Browser UTF-8 allocation of " + length + " bytes failed");
        }
        writeBytes(address, encoded, 0, encoded.length);
        if (nullTerminated) {
            BrowserMemory.putByte(address + encoded.length, (byte) 0);
        }
        return wrapBufferByte(address, length);
    }

    /** Writes the same bytes that {@code MemoryUtil.memLengthUTF8} counts. */
    @Override
    public void putStringUTF8(CharSequence text, boolean nullTerminated, long target) {
        writeBytes(target, encodeUTF8(text, nullTerminated));
    }

    @Override
    public int putStringUTF8(String text, boolean nullTerminated, long target, int maxLength) {
        byte[] encoded = text.getBytes(StandardCharsets.UTF_8);
        int length = terminatedLength(encoded.length, nullTerminated);
        if (maxLength < length) {
            throw new BufferOverflowException();
        }
        writeBytes(target, encoded, 0, encoded.length);
        if (nullTerminated) {
            BrowserMemory.putByte(target + encoded.length, (byte) 0);
        }
        return length;
    }

    @Override
    public void putStringUTF16(String text, boolean nullTerminated, long target) {
        putUTF16(text, nullTerminated, target);
    }

    @Override
    public void putStringUTF16(CharSequence text, boolean nullTerminated, long target) {
        putUTF16(text, nullTerminated, target);
    }

    private static void putUTF16(CharSequence text, boolean nullTerminated, long target) {
        int length = text.length();
        checkMemoryRange(target, terminatedLength(length, nullTerminated), 1);
        for (int index = 0; index < length; index++) {
            BrowserMemory.putShort(target + ((long) index << 1), (short) text.charAt(index));
        }
        if (nullTerminated) {
            BrowserMemory.putShort(target + ((long) length << 1), (short) 0);
        }
    }

    private static byte[] encodeASCII(CharSequence text, boolean nullTerminated) {
        int length = text.length();
        byte[] encoded = new byte[terminatedLength(length, nullTerminated)];
        for (int index = 0; index < length; index++) {
            encoded[index] = (byte) text.charAt(index);
        }
        return encoded;
    }

    /** LWJGL's CharSequence encoder: a high surrogate always takes the next char. */
    private static byte[] encodeUTF8(CharSequence text, boolean nullTerminated) {
        byte[] encoded = new byte[MemoryUtil.memLengthUTF8(text, nullTerminated)];
        int position = 0;
        int index = 0;
        int length = text.length();
        while (index < length) {
            char c = text.charAt(index++);
            if (c < 0x80) {
                encoded[position++] = (byte) c;
                continue;
            }
            int codePoint = c;
            if (c < 0x800) {
                encoded[position++] = (byte) (0xc0 | (codePoint >> 6));
            } else {
                if (!Character.isHighSurrogate(c)) {
                    encoded[position++] = (byte) (0xe0 | (codePoint >> 12));
                } else {
                    codePoint = Character.toCodePoint(c, text.charAt(index++));
                    encoded[position++] = (byte) (0xf0 | (codePoint >> 18));
                    encoded[position++] = (byte) (0x80 | ((codePoint >> 12) & 0x3f));
                }
                encoded[position++] = (byte) (0x80 | ((codePoint >> 6) & 0x3f));
            }
            encoded[position++] = (byte) (0x80 | (codePoint & 0x3f));
        }
        if (nullTerminated) {
            encoded[position++] = 0;
        }
        if (position != encoded.length) {
            throw new IllegalStateException(
                    "UTF-8 length mismatch: wrote " + position + " of " + encoded.length);
        }
        return encoded;
    }

    /** Code units including the optional terminator. */
    private static int terminatedLength(int length, boolean nullTerminated) {
        int units = nullTerminated ? length + 1 : length;
        if (units < 0) {
            throw new BufferOverflowException();
        }
        return units;
    }

    private static void writeBytes(long target, byte[] source) {
        writeBytes(target, source, 0, source.length);
    }

    /**
     * Copies into virtual memory, straight into the region's backing array
     * when it has one (every region BrowserMemory allocates itself does).
     */
    private static void writeBytes(long target, byte[] source, int index, int length) {
        if (length == 0) {
            return;
        }
        checkMemoryRange(target, length, 0);
        byte[] data = backingArray(target);
        if (data != null) {
            System.arraycopy(source, index, data, BrowserMemory.dataOffset(target), length);
            return;
        }
        for (int offset = 0; offset < length; offset++) {
            BrowserMemory.putByte(target + offset, source[index + offset]);
        }
    }

    private static void readBytes(long source, byte[] target, int index, int length) {
        if (length == 0) {
            return;
        }
        checkMemoryRange(source, length, 0);
        byte[] data = backingArray(source);
        if (data != null) {
            System.arraycopy(data, BrowserMemory.dataOffset(source), target, index, length);
            return;
        }
        for (int offset = 0; offset < length; offset++) {
            target[index + offset] = BrowserMemory.getByte(source + offset);
        }
    }

    private static byte[] backingArray(long address) {
        try {
            return BrowserMemory.data(address);
        } catch (IllegalStateException notArrayBacked) {
            return null;
        }
    }

    private static void checkArrayRange(int arrayLength, int index, int length) {
        if (index < 0 || length < 0 || index > arrayLength - length) {
            throw new ArrayIndexOutOfBoundsException(
                    "Invalid array range " + index + "+" + length + " of " + arrayLength);
        }
    }

    /**
     * Fails before any byte is written when {@code count << shift} bytes at
     * {@code address} leave the virtual region: reading the last byte
     * resolves the region and checks its bounds.
     */
    private static void checkMemoryRange(long address, int count, int shift) {
        if (count <= 0) {
            return;
        }
        long bytes = (long) count << shift;
        // Keep the last byte in the same region id (the low 32 address bits
        // are the offset, and regions never exceed Integer.MAX_VALUE bytes).
        if ((address & 0xffff_ffffL) + bytes - 1L > Integer.MAX_VALUE) {
            throw new IndexOutOfBoundsException("Invalid virtual memory range");
        }
        BrowserMemory.getByte(address);
        BrowserMemory.getByte(address + bytes - 1L);
    }
}
