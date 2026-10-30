package org.teavm.classlib.java.lang.invoke;

import java.nio.ByteBuffer;

/**
 * Browser stub for {@code java.lang.invoke.VarHandle}. Netty keeps these access
 * modes in its reachable graph even though {@code hasVarHandle} is false, so the
 * class must exist with the exact descriptors the call sites reference. Every
 * operation fails fast because the browser build never enables VarHandle paths.
 */
public class TVarHandle {
    public TVarHandle() {
    }

    public static void storeStoreFence() {
        throw new UnsupportedOperationException("VarHandle is not available in the browser");
    }

    public Object get(Object... args) {
        throw unsupported();
    }

    public Object getVolatile(Object... args) {
        throw unsupported();
    }

    public Object getAcquire(Object... args) {
        throw unsupported();
    }

    public void set(Object... args) {
        throw unsupported();
    }

    public void setVolatile(Object... args) {
        throw unsupported();
    }

    public void setRelease(Object... args) {
        throw unsupported();
    }

    public Object getAndAdd(Object... args) {
        throw unsupported();
    }

    public Object getAndSet(Object... args) {
        throw unsupported();
    }

    public boolean compareAndSet(Object... args) {
        throw unsupported();
    }

    // --- netty-buffer access modes: (ByteBuffer, int) ---
    public short get(ByteBuffer buffer, int index) {
        throw unsupported();
    }

    public int getInt(ByteBuffer buffer, int index) {
        throw unsupported();
    }

    public long getLong(ByteBuffer buffer, int index) {
        throw unsupported();
    }

    public void set(ByteBuffer buffer, int index, short value) {
        throw unsupported();
    }

    public void set(ByteBuffer buffer, int index, int value) {
        throw unsupported();
    }

    public void set(ByteBuffer buffer, int index, long value) {
        throw unsupported();
    }

    // --- netty-buffer access modes: (byte[], int) ---
    public short get(byte[] array, int index) {
        throw unsupported();
    }

    public int getInt(byte[] array, int index) {
        throw unsupported();
    }

    public long getLong(byte[] array, int index) {
        throw unsupported();
    }

    public void set(byte[] array, int index, short value) {
        throw unsupported();
    }

    public void set(byte[] array, int index, int value) {
        throw unsupported();
    }

    public void set(byte[] array, int index, long value) {
        throw unsupported();
    }

    // --- netty RefCnt / ReferenceCounted access modes ---
    public int get(Object receiver) {
        throw unsupported();
    }

    public int getAcquire(Object receiver) {
        throw unsupported();
    }

    public void set(Object receiver, int value) {
        throw unsupported();
    }

    public void setRelease(Object receiver, int value) {
        throw unsupported();
    }

    public int getAndAdd(Object receiver, int delta) {
        throw unsupported();
    }

    public boolean compareAndSet(Object receiver, int expected, int update) {
        throw unsupported();
    }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("VarHandle is not available in the browser");
    }
}
