package org.teavm.classlib.java.net;

public final class TInet6Address extends TInetAddress {
    TInet6Address(String hostName, byte[] address) {
        super(hostName, address);
    }

    public static TInet6Address getByAddress(String host, byte[] address, int scopeId)
            throws TUnknownHostException {
        if (address == null || address.length != 16) {
            throw new TUnknownHostException("IPv6 address must contain 16 bytes");
        }
        return new TInet6Address(host, address);
    }

    public static TInet6Address getByAddress(
            String host, byte[] address, TNetworkInterface networkInterface)
            throws TUnknownHostException {
        return getByAddress(host, address, 0);
    }

    public TNetworkInterface getScopedInterface() {
        return null;
    }

    public int getScopeId() {
        return 0;
    }
}
