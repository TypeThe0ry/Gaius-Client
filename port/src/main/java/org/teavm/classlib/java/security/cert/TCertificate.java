package org.teavm.classlib.java.security.cert;

import java.io.Serializable;

public abstract class TCertificate implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String type;

    protected TCertificate(String type) {
        this.type = type;
    }

    public final String getType() {
        return type;
    }
}
