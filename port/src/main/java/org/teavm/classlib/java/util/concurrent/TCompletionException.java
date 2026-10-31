package org.teavm.classlib.java.util.concurrent;

public class TCompletionException extends RuntimeException {
    public TCompletionException(String message, Throwable cause) {
        super(message, cause);
    }

    public TCompletionException(Throwable cause) {
        super(cause);
    }
}
