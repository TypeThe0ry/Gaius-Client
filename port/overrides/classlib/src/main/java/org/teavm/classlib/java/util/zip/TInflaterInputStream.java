package org.teavm.classlib.java.util.zip;

import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Inflater stream with a large refill buffer for MCA chunk streams. */
public class TInflaterInputStream extends FilterInputStream {
    protected TInflater inf;
    protected byte[] buf;
    protected int len;
    private boolean closed;
    private boolean eof;
    private static final int BUF_SIZE = 512;
    private byte[] skipBuffer;

    public TInflaterInputStream(InputStream in) { this(in, new TInflater(), BUF_SIZE); }
    public TInflaterInputStream(InputStream in, TInflater inf) { this(in, inf, BUF_SIZE); }
    public TInflaterInputStream(InputStream in, TInflater inf, int size) {
        super(in);
        if (in == null || inf == null) throw new NullPointerException();
        if (size <= 0) throw new IllegalArgumentException();
        this.inf = inf; this.buf = new byte[size];
    }
    @Override public int read() throws IOException { byte[] b=new byte[1]; return read(b,0,1)<0?-1:b[0]&255; }
    @Override public int read(byte[] out,int off,int count) throws IOException {
        if (closed) throw new IOException("Stream is closed");
        if (out == null) throw new NullPointerException();
        if (off < 0 || count < 0 || off > out.length-count) throw new IndexOutOfBoundsException();
        if (count == 0) return 0;
        while (true) {
            if (eof) return -1;
            if (inf.needsInput()) fill();
            try {
                int n=inf.inflate(out,off,count);
                eof=inf.finished();
                if (n>0) return n;
                if (eof) return -1;
                if (inf.needsDictionary()) { eof=true; return -1; }
                if (len == -1) { eof=true; throw new EOFException(); }
            } catch (TDataFormatException e) {
                eof=true; throw new IOException(e);
            }
        }
    }
    protected void fill() throws IOException {
        if (closed) throw new IOException();
        do { len=in.read(buf); } while (len == 0);
        if (len>0) inf.setInput(buf,0,len);
    }
    @Override public long skip(long n) throws IOException {
        if (n < 0) throw new IllegalArgumentException();
        if (skipBuffer == null) skipBuffer = new byte[BUF_SIZE];
        long skipped = 0;
        while (skipped < n) {
            int count = (int) Math.min(n - skipped, skipBuffer.length);
            int read = read(skipBuffer, 0, count);
            if (read < 0) break;
            skipped += read;
        }
        return skipped;
    }
    @Override public int available() throws IOException { return eof?0:1; }
    @Override public void close() throws IOException { if(!closed){ in.close(); inf.end(); closed=true; buf=null; } }
    @Override public void mark(int limit) {}
    @Override public void reset() throws IOException { throw new IOException("mark/reset not supported"); }
    @Override public boolean markSupported(){return false;}
}
