package dev.gaius.serverplugin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

final class GaiusWebSocketGateway extends WebSocketServer {
    private static final long STARTUP_TIMEOUT_MILLIS = 10_000;
    private static final int CLOSE_POLICY = 1008;
    private static final int CLOSE_TOO_LARGE = 1009;
    private static final int COPY_BUFFER_BYTES = 64 * 1024;

    private final String minecraftHost;
    private final int minecraftPort;
    private final Target configuredTarget;
    private final int connectTimeoutMillis;
    private final int maximumConnections;
    private final int maximumFrameBytes;
    private final boolean proxyKeepAlives;
    private final byte[] accessToken;
    private final List<String> allowedOrigins;
    private final Logger logger;
    private final Supplier<Socket> socketFactory;
    private final Map<WebSocket, Session> sessions = new ConcurrentHashMap<>();
    private final Object lifecycleLock = new Object();
    private final Object shutdownLock = new Object();
    private final CountDownLatch ready = new CountDownLatch(1);
    private final AtomicReference<Exception> startupFailure = new AtomicReference<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean shutdownRequested = new AtomicBoolean();
    private final AtomicBoolean shutdownComplete = new AtomicBoolean();

    GaiusWebSocketGateway(
            InetSocketAddress listenAddress,
            String minecraftHost,
            int minecraftPort,
            int connectTimeoutMillis,
            int maximumConnections,
            int maximumFrameBytes,
            boolean proxyKeepAlives,
            String accessToken,
            List<String> allowedOrigins,
            Logger logger) {
        this(
                listenAddress,
                minecraftHost,
                minecraftPort,
                connectTimeoutMillis,
                maximumConnections,
                maximumFrameBytes,
                proxyKeepAlives,
                accessToken,
                allowedOrigins,
                logger,
                Socket::new);
    }

    GaiusWebSocketGateway(
            InetSocketAddress listenAddress,
            String minecraftHost,
            int minecraftPort,
            int connectTimeoutMillis,
            int maximumConnections,
            int maximumFrameBytes,
            boolean proxyKeepAlives,
            String accessToken,
            List<String> allowedOrigins,
            Logger logger,
            Supplier<Socket> socketFactory) {
        super(listenAddress);
        this.minecraftHost = minecraftHost;
        this.minecraftPort = minecraftPort;
        this.configuredTarget = new Target(normalizeHost(minecraftHost), validatePort(minecraftPort));
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.maximumConnections = maximumConnections;
        this.maximumFrameBytes = maximumFrameBytes;
        this.proxyKeepAlives = proxyKeepAlives;
        this.accessToken = accessToken.getBytes(StandardCharsets.UTF_8);
        this.allowedOrigins = allowedOrigins == null || allowedOrigins.isEmpty()
                ? List.of("*")
                : List.copyOf(allowedOrigins);
        this.logger = logger;
        this.socketFactory = socketFactory;
        setReuseAddr(true);
        setTcpNoDelay(true);
        setConnectionLostTimeout(30);
    }

    @Override
    public void onOpen(WebSocket connection, ClientHandshake handshake) {
        if (!path(handshake).equals("/tunnel")) {
            connection.close(CLOSE_POLICY, "Unknown Gaius endpoint");
            return;
        }
        if (!originAllowed(handshake.getFieldValue("Origin"))) {
            connection.close(CLOSE_POLICY, "Origin is not allowed");
            return;
        }
        synchronized (lifecycleLock) {
            if (shutdownRequested.get()) {
                connection.close(1001, "Gaius transport is shutting down");
                return;
            }
            if (sessions.size() >= maximumConnections) {
                connection.close(1013, "Gaius transport is at capacity");
                return;
            }
            sessions.put(connection, new Session(connection));
        }
    }

    @Override
    public void onMessage(WebSocket connection, String message) {
        Session session = sessions.get(connection);
        if (session == null) {
            connection.close(CLOSE_POLICY, "Unknown Gaius session");
            return;
        }
        try {
            JsonObject control = JsonParser.parseString(message).getAsJsonObject();
            String type = control.has("type") ? control.get("type").getAsString() : "";
            if (type.equals("connect")) {
                session.connect(control);
            } else if (type.equals("flow")) {
                session.setPaused(control.has("paused") && control.get("paused").getAsBoolean());
            } else {
                connection.close(CLOSE_POLICY, "Unsupported Gaius control message");
            }
        } catch (RuntimeException exception) {
            connection.close(CLOSE_POLICY, "Invalid Gaius control message");
        }
    }

    @Override
    public void onMessage(WebSocket connection, ByteBuffer message) {
        Session session = sessions.get(connection);
        if (session == null || !session.connected.get()) {
            connection.close(CLOSE_POLICY, "Gaius tunnel is not connected");
            return;
        }
        if (message.remaining() > maximumFrameBytes) {
            connection.close(CLOSE_TOO_LARGE, "Gaius frame exceeds configured limit");
            return;
        }
        byte[] bytes = new byte[message.remaining()];
        message.get(bytes);
        session.write(bytes);
    }

    @Override
    public void onClose(WebSocket connection, int code, String reason, boolean remote) {
        Session session = sessions.remove(connection);
        if (session != null) {
            session.close();
        }
    }

    @Override
    public void onError(WebSocket connection, Exception exception) {
        // WebSocketServer invokes this callback on its selector thread for a
        // fatal startup error.  Do not call stop()/join() here: stop() joins
        // the selector thread and would deadlock it.  The waiting caller will
        // perform the idempotent shutdown after this barrier is released.
        if (connection == null && !started.get()) {
            startupFailure.compareAndSet(
                    null,
                    exception == null
                            ? new IllegalStateException("Gaius WebSocket gateway startup failed")
                            : exception);
            ready.countDown();
        }
        if (connection != null) {
            Session session = sessions.remove(connection);
            if (session != null) {
                session.close();
            }
        }
        logger.log(Level.FINE, "Gaius WebSocket transport error", exception);
    }

    @Override
    public void onStart() {
        started.set(true);
        ready.countDown();
    }

    void awaitReady() {
        awaitReady(STARTUP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    }

    void awaitReady(long timeout, TimeUnit unit) {
        if (timeout <= 0) {
            throw new IllegalArgumentException("Gateway startup timeout must be positive");
        }
        if (unit == null) {
            throw new NullPointerException("Gateway startup timeout unit must not be null");
        }
        try {
            if (!ready.await(timeout, unit)) {
                shutdown();
                throw new IllegalStateException("Gaius WebSocket gateway startup timed out");
            }
        } catch (InterruptedException exception) {
            shutdown();
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while waiting for the Gaius WebSocket gateway to start",
                    exception);
        }

        Exception failure = startupFailure.get();
        if (failure != null) {
            shutdown();
            throw new IllegalStateException("Gaius WebSocket gateway failed to start", failure);
        }
        if (shutdownRequested.get()) {
            throw new IllegalStateException("Gaius WebSocket gateway was shut down before readiness");
        }
        if (!started.get()) {
            shutdown();
            throw new IllegalStateException("Gaius WebSocket gateway stopped before startup");
        }
    }

    void shutdown() {
        shutdownRequested.set(true);
        if (!started.get() && ready.getCount() != 0) {
            startupFailure.compareAndSet(
                    null,
                    new IllegalStateException("Gaius WebSocket gateway was stopped before startup"));
            ready.countDown();
        }
        List<Session> sessionsToClose;
        synchronized (lifecycleLock) {
            sessionsToClose = new ArrayList<>(sessions.values());
            sessions.clear();
        }
        for (Session session : sessionsToClose) {
            session.close();
        }
        synchronized (shutdownLock) {
            if (shutdownComplete.get()) {
                return;
            }
            try {
                stop(5000);
                shutdownComplete.set(true);
            } catch (InterruptedException exception) {
                // Keep shutdownRequested set so no new Session can appear, but
                // leave shutdownComplete false so another caller can retry.
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean originAllowed(String origin) {
        String candidate = origin == null || origin.isBlank() ? "null" : origin;
        for (String allowed : allowedOrigins) {
            if (allowed.equals("*") || allowed.equals(candidate)) {
                return true;
            }
            if (allowed.endsWith("*")
                    && candidate.startsWith(allowed.substring(0, allowed.length() - 1))) {
                return true;
            }
        }
        return false;
    }

    private static String path(ClientHandshake handshake) {
        String descriptor = handshake.getResourceDescriptor();
        int query = descriptor.indexOf('?');
        return query >= 0 ? descriptor.substring(0, query) : descriptor;
    }

    private boolean tokenMatches(JsonObject control) {
        if (accessToken.length == 0) {
            return true;
        }
        byte[] candidate = control.has("token")
                ? control.get("token").getAsString().getBytes(StandardCharsets.UTF_8)
                : new byte[0];
        return MessageDigest.isEqual(accessToken, candidate);
    }

    private final class Session {
        private final WebSocket webSocket;
        private final AtomicBoolean connected = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Object outputLock = new Object();
        private final Object pauseLock = new Object();
        private volatile boolean paused;
        private Socket socket;
        private OutputStream output;

        private Session(WebSocket webSocket) {
            this.webSocket = webSocket;
        }

        private void connect(JsonObject control) {
            if (!connected.compareAndSet(false, true)) {
                webSocket.close(CLOSE_POLICY, "Gaius tunnel already connected");
                return;
            }
            if (!tokenMatches(control)) {
                webSocket.close(CLOSE_POLICY, "Invalid Gaius access token");
                return;
            }
            Target requestedTarget;
            try {
                requestedTarget = targetFromControl(control);
            } catch (IllegalArgumentException exception) {
                webSocket.close(CLOSE_POLICY, exception.getMessage());
                return;
            }
            // The browser reached this endpoint through the host it typed, so
            // that host names this machine (a LAN address, a DNS name, ...)
            // and cannot equal the loopback minecraft-host. Only the port
            // selects a server; the socket below always dials the configured one.
            if (requestedTarget.port() != configuredTarget.port()) {
                webSocket.close(
                        CLOSE_POLICY,
                        "Gaius target port does not match the configured Minecraft server");
                return;
            }
            webSocket.send(controlMessage("connecting", requestedTarget));
            Thread.ofVirtual().name("gaius-websocket-connect").start(() -> {
                try {
                    Socket target = socketFactory.get();
                    socket = target;
                    if (closed.get()) {
                        target.close();
                        return;
                    }
                    target.setTcpNoDelay(true);
                    target.setKeepAlive(true);
                    target.connect(
                            new InetSocketAddress(minecraftHost, minecraftPort),
                            connectTimeoutMillis);
                    if (closed.get()) {
                        return;
                    }
                    synchronized (outputLock) {
                        if (closed.get()) {
                            return;
                        }
                        output = target.getOutputStream();
                    }
                    webSocket.send(controlMessage("connected", requestedTarget));
                    copyServerToBrowser(target.getInputStream());
                } catch (IOException exception) {
                    if (!closed.get()) {
                        webSocket.close(1011, "Minecraft server connection failed");
                    }
                } finally {
                    close();
                }
            });
        }

        private Target targetFromControl(JsonObject control) {
            boolean hasHost = control.has("host");
            boolean hasPort = control.has("port");
            if (!hasHost && !hasPort) {
                // The original direct-plugin protocol omitted the target fields.
                return configuredTarget;
            }
            if (!hasHost || !hasPort) {
                throw new IllegalArgumentException("Gaius target host and port are required together");
            }
            if (!control.get("host").isJsonPrimitive()
                    || !control.getAsJsonPrimitive("host").isString()) {
                throw new IllegalArgumentException("Gaius target host must be a string");
            }
            String host = normalizeHost(control.get("host").getAsString());
            int port;
            try {
                port = control.get("port").getAsInt();
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("Gaius target port must be an integer", exception);
            }
            return new Target(host, validatePort(port));
        }

        private String controlMessage(String type, Target target) {
            JsonObject message = new JsonObject();
            message.addProperty("type", type);
            message.addProperty("host", target.host());
            message.addProperty("port", target.port());
            if (type.equals("connecting")) {
                message.addProperty("targetConnectTimeoutMs", connectTimeoutMillis);
            }
            return message.toString();
        }

        private void write(byte[] bytes) {
            try {
                synchronized (outputLock) {
                    if (output == null) {
                        throw new IOException("Minecraft socket is unavailable");
                    }
                    output.write(bytes);
                    output.flush();
                }
            } catch (IOException exception) {
                webSocket.close(1011, "Minecraft server write failed");
                close();
            }
        }

        private void copyServerToBrowser(InputStream input) throws IOException {
            byte[] buffer = new byte[COPY_BUFFER_BYTES];
            while (!closed.get()) {
                awaitReadable();
                int length = input.read(buffer);
                if (length < 0) {
                    return;
                }
                if (proxyKeepAlives && isVanillaKeepAlive(buffer, length)) {
                    write(copyOf(buffer, length));
                    continue;
                }
                while (webSocket.hasBufferedData() && !closed.get()) {
                    sleepBriefly();
                }
                if (closed.get()) {
                    return;
                }
                webSocket.send(ByteBuffer.wrap(buffer, 0, length));
            }
        }

        private void setPaused(boolean paused) {
            synchronized (pauseLock) {
                this.paused = paused;
                if (!paused) {
                    pauseLock.notifyAll();
                }
            }
        }

        private void awaitReadable() throws IOException {
            synchronized (pauseLock) {
                while (paused && !closed.get()) {
                    try {
                        pauseLock.wait(1000);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Gaius relay thread interrupted", exception);
                    }
                }
            }
        }

        private void sleepBriefly() throws IOException {
            try {
                Thread.sleep(2);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Gaius relay thread interrupted", exception);
            }
        }

        private static boolean isVanillaKeepAlive(byte[] bytes, int length) {
            return length == 11 && bytes[0] == 0x0a && bytes[1] == 0x00 && bytes[2] == 0x04;
        }

        private static byte[] copyOf(byte[] bytes, int length) {
            byte[] copy = new byte[length];
            System.arraycopy(bytes, 0, copy, 0, length);
            return copy;
        }

        private void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            synchronized (pauseLock) {
                pauseLock.notifyAll();
            }
            Socket target = socket;
            if (target != null) {
                try {
                    target.close();
                } catch (IOException ignored) {
                    // The transport is already closing.
                }
            }
            if (webSocket.isOpen()) {
                webSocket.close(1000, "Gaius tunnel closed");
            }
        }
    }

    private record Target(String host, int port) {}

    private static String normalizeHost(String host) {
        if (host == null) {
            throw new IllegalArgumentException("Gaius target host must not be empty");
        }
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        while (normalized.length() > 1 && normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()
                || normalized.indexOf('\0') >= 0
                || normalized.indexOf('\r') >= 0
                || normalized.indexOf('\n') >= 0
                || normalized.indexOf('/') >= 0
                || normalized.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Gaius target host is invalid");
        }
        return normalized;
    }

    private static int validatePort(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Gaius target port must be between 1 and 65535");
        }
        return port;
    }
}
