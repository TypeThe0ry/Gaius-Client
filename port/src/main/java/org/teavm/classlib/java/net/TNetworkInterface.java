package org.teavm.classlib.java.net;

import java.util.Collections;
import java.util.Enumeration;

public final class TNetworkInterface {
    public static Enumeration<TNetworkInterface> getNetworkInterfaces() {
        return Collections.emptyEnumeration();
    }

    public static TNetworkInterface getByInetAddress(TInetAddress address) {
        return null;
    }

    public static TNetworkInterface getByName(String name) {
        return null;
    }

    public String getName() {
        return "browser";
    }

    public String getDisplayName() {
        return "Browser";
    }

    public boolean isLoopback() {
        return true;
    }

    public boolean isVirtual() {
        return false;
    }

    public Enumeration<TInetAddress> getInetAddresses() {
        return Collections.enumeration(Collections.singleton(TInetAddress.getLoopbackAddress()));
    }

    public byte[] getHardwareAddress() {
        return null;
    }
}
