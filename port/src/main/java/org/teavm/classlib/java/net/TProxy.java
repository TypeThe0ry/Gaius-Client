package org.teavm.classlib.java.net;

import java.util.Objects;

public class TProxy {
    public enum Type {
        DIRECT,
        HTTP,
        SOCKS
    }

    public static final TProxy NO_PROXY = new TProxy();

    private final Type type;
    private final TSocketAddress address;

    private TProxy() {
        type = Type.DIRECT;
        address = null;
    }

    public TProxy(Type type, TSocketAddress address) {
        this.type = Objects.requireNonNull(type);
        if (type == Type.DIRECT || address == null) {
            throw new IllegalArgumentException("type DIRECT is not compatible with an address");
        }
        this.address = address;
    }

    public Type type() {
        return type;
    }

    public TSocketAddress address() {
        return address;
    }

    @Override
    public String toString() {
        return type == Type.DIRECT ? "DIRECT" : type + " @ " + address;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof TProxy)) {
            return false;
        }
        TProxy proxy = (TProxy) other;
        return type == proxy.type && Objects.equals(address, proxy.address);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, address);
    }
}
