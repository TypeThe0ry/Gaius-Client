package org.teavm.classlib.java.net;

import java.io.IOException;

public final class TUrlModernSupport {
    private TUrlModernSupport() {
    }

    public static TURLConnection openConnection(TURL url, Object proxy) throws IOException {
        return url.openConnection();
    }

    public static long getContentLengthLong(THttpURLConnection connection) {
        return connection.getContentLength();
    }
}
