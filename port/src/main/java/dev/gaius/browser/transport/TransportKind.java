package dev.gaius.browser.transport;

/** Persisted transport selection for a multiplayer server entry. */
public enum TransportKind {
    RELAY("relay", "Relay"),
    PLUGIN("plugin", "Plugin"),
    EAGLER("eagler", "Eagler"),
    WSPROXY("wsproxy", "WebSocket proxy");

    private final String protocol;
    private final String displayName;

    TransportKind(String protocol, String displayName) {
        this.protocol = protocol;
        this.displayName = displayName;
    }

    public String protocol() { return protocol; }
    public String displayName() { return displayName; }

    /** Unknown or absent persisted values retain the current relay/plugin selection. */
    public static TransportKind fromProtocol(String protocol) {
        if (protocol == null || protocol.isBlank()) return RELAY;
        for (TransportKind kind : values()) {
            if (kind.protocol.equalsIgnoreCase(protocol.trim())) return kind;
        }
        return RELAY;
    }
}
