package org.teavm.classlib.java.net;

public abstract class TAuthenticator {
    public enum RequestorType {
        PROXY,
        SERVER
    }

    private static TAuthenticator defaultAuthenticator;

    public TAuthenticator() {
    }

    public static void setDefault(TAuthenticator authenticator) {
        defaultAuthenticator = authenticator;
    }

    public static TAuthenticator getDefault() {
        return defaultAuthenticator;
    }

    protected TPasswordAuthentication getPasswordAuthentication() {
        return null;
    }
}
