package org.teavm.classlib.java.net;

import java.io.Serializable;
import java.util.Arrays;

public class TInetAddress implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String hostName;
    private final byte[] address;

    protected TInetAddress(String hostName, byte[] address) {
        this.hostName = hostName;
        this.address = address.clone();
    }

    public static TInetAddress getByName(String host) throws TUnknownHostException {
        if (host == null || host.isEmpty() || host.equalsIgnoreCase("localhost")) {
            return getLoopbackAddress();
        }
        if (host.endsWith(".gaius-local")) {
            return new TInet4Address(host, new byte[] {0, 0, 0, 0});
        }
        byte[] parsed = parseIpv4(host);
        if (parsed == null) {
            throw new TUnknownHostException(
                    "DNS resolution is provided by the browser transport bridge: " + host);
        }
        return new TInet4Address(host, parsed);
    }

    public static TInetAddress[] getAllByName(String host) throws TUnknownHostException {
        return new TInetAddress[] {getByName(host)};
    }

    public static TInetAddress getLoopbackAddress() {
        return new TInet4Address("localhost", new byte[] {127, 0, 0, 1});
    }

    public static TInetAddress getByAddress(byte[] address) throws TUnknownHostException {
        return getByAddress(null, address);
    }

    public static TInetAddress getByAddress(String host, byte[] address)
            throws TUnknownHostException {
        if (address == null) {
            throw new TUnknownHostException("Address is null");
        }
        if (address.length == 4) {
            return new TInet4Address(host, address);
        }
        if (address.length == 16) {
            return new TInet6Address(host, address);
        }
        throw new TUnknownHostException("Address must contain 4 or 16 bytes");
    }

    public static TInetAddress getLocalHost() {
        return getLoopbackAddress();
    }

    public String getHostName() {
        return hostName;
    }

    public String getCanonicalHostName() {
        return hostName;
    }

    public byte[] getAddress() {
        return address.clone();
    }

    public String getHostAddress() {
        // Synthetic integrated-server names are transport identities, not DNS
        // addresses.  They intentionally carry a 0.0.0.0 byte payload so the
        // TeaVM classlib can construct an Inet4Address without a resolver, but
        // exposing that payload here destroys the name when vanilla converts
        // InetSocketAddress -> InetAddress -> host address.  Keep the typed
        // name all the way to BrowserWebSocketChannel.open().
        if (hostName != null && hostName.endsWith(".gaius-local")) {
            return hostName;
        }
        if (address.length == 4) {
            return (address[0] & 255) + "." + (address[1] & 255) + "."
                    + (address[2] & 255) + "." + (address[3] & 255);
        }
        return hostName;
    }

    public boolean isAnyLocalAddress() {
        return address.length == 4 && address[0] == 0 && address[1] == 0
                && address[2] == 0 && address[3] == 0;
    }

    public boolean isLoopbackAddress() {
        return address.length == 4 && (address[0] & 255) == 127;
    }

    public boolean isMulticastAddress() {
        if (address.length == 4) {
            int first = address[0] & 255;
            return first >= 224 && first <= 239;
        }
        return address.length == 16 && (address[0] & 255) == 255;
    }

    public boolean isLinkLocalAddress() {
        if (address.length == 4) {
            return (address[0] & 255) == 169 && (address[1] & 255) == 254;
        }
        return address.length == 16
                && (address[0] & 255) == 254
                && ((address[1] & 255) & 192) == 128;
    }

    public boolean isSiteLocalAddress() {
        if (address.length == 4) {
            int first = address[0] & 255;
            int second = address[1] & 255;
            return first == 10
                    || first == 172 && second >= 16 && second <= 31
                    || first == 192 && second == 168;
        }
        return address.length == 16
                && ((address[0] & 255) & 254) == 252;
    }

    @Override
    public String toString() {
        return hostName + "/" + getHostAddress();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TInetAddress
                && Arrays.equals(address, ((TInetAddress) other).address);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(address);
    }

    private static byte[] parseIpv4(String host) {
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return null;
        }
        byte[] result = new byte[4];
        try {
            for (int index = 0; index < 4; index++) {
                int value = Integer.parseInt(parts[index]);
                if (value < 0 || value > 255) {
                    return null;
                }
                result[index] = (byte) value;
            }
            return result;
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
