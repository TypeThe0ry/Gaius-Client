package org.teavm.classlib.java.text;

public final class TStringCharacterIterator implements TCharacterIterator {
    private String text;
    private int begin;
    private int end;
    private int position;

    public TStringCharacterIterator(String text) {
        this(text, 0);
    }

    public TStringCharacterIterator(String text, int position) {
        this(text, 0, text.length(), position);
    }

    public TStringCharacterIterator(String text, int begin, int end, int position) {
        if (text == null) {
            throw new NullPointerException();
        }
        if (begin < 0 || end < begin || end > text.length()
                || position < begin || position > end) {
            throw new IllegalArgumentException();
        }
        this.text = text;
        this.begin = begin;
        this.end = end;
        this.position = position;
    }

    public void setText(String value) {
        if (value == null) {
            throw new NullPointerException();
        }
        text = value;
        begin = 0;
        end = value.length();
        position = 0;
    }

    @Override public char first() { position = begin; return current(); }
    @Override public char last() { position = begin == end ? end : end - 1; return current(); }

    @Override
    public char setIndex(int value) {
        if (value < begin || value > end) {
            throw new IllegalArgumentException();
        }
        position = value;
        return current();
    }

    @Override public char current() {
        return position < end ? text.charAt(position) : DONE;
    }
    @Override public char next() {
        if (position < end) position++;
        return current();
    }
    @Override public char previous() {
        if (position <= begin) return DONE;
        position--;
        return current();
    }
    @Override public int getBeginIndex() { return begin; }
    @Override public int getEndIndex() { return end; }
    @Override public int getIndex() { return position; }
    @Override public Object clone() {
        return new TStringCharacterIterator(text, begin, end, position);
    }

    @Override
    public boolean equals(Object object) {
        if (!(object instanceof TStringCharacterIterator)) return false;
        TStringCharacterIterator other = (TStringCharacterIterator) object;
        return text.equals(other.text) && begin == other.begin
                && end == other.end && position == other.position;
    }

    @Override public int hashCode() { return text.hashCode() ^ begin ^ end ^ position; }
}
