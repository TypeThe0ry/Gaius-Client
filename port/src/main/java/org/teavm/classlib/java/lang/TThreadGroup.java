package org.teavm.classlib.java.lang;

public class TThreadGroup {
    private final String name;

    public TThreadGroup(String name) {
        this.name = name;
    }

    public TThreadGroup(TThreadGroup parent, String name) {
        this(name);
    }

    public String getName() {
        return name;
    }

    public TThreadGroup getParent() {
        return null;
    }
}
