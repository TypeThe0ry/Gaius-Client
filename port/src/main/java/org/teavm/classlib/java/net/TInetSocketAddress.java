package org.teavm.classlib.java.net;

public class TInetSocketAddress extends TSocketAddress {
    private static final long serialVersionUID = 1L;

    private final String host;
    private final int port;
    private final TInetAddress address;

    public TInetSocketAddress(String host, int port) {
        this.host = host;
        this.port = validatePort(port);
        TInetAddress resolved;
        try {
            resolved = TInetAddress.getByName(host);
        } catch (TUnknownHostException exception) {
            resolved = null;
        }
        this.address = resolved;
    }

    public TInetSocketAddress(TInetAddress address, int port) {
        this.address = address;
        this.host = address == null ? "0.0.0.0" : address.getHostName();
        this.port = validatePort(port);
    }

    public static TInetSocketAddress createUnresolved(String host, int port) {
        return new TInetSocketAddress(host, port);
    }

    public int getPort() {
        return port;
    }

    public TInetAddress getAddress() {
        return address;
    }

    public String getHostName() {
        return host;
    }

    public String getHostString() {
        return host;
    }

    public boolean isUnresolved() {
        return address == null;
    }

    @Override
    public String toString() {
        return host + ":" + port;
    }

    private static int validatePort(int port) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        return port;
    }
}
