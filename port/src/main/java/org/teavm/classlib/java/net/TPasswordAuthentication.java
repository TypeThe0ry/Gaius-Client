package org.teavm.classlib.java.net;

public final class TPasswordAuthentication {
    private final String userName;
    private final char[] password;

    public TPasswordAuthentication(String userName, char[] password) {
        this.userName = userName;
        this.password = password.clone();
    }

    public String getUserName() {
        return userName;
    }

    public char[] getPassword() {
        return password.clone();
    }
}
