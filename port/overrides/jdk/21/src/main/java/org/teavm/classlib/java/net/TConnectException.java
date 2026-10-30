package org.teavm.classlib.java.net;

import org.teavm.classlib.java.io.TIOException;

public class TConnectException extends TIOException {
    public TConnectException() {
    }

    public TConnectException(String message) {
        super(message);
    }
}
