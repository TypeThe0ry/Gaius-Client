package dev.gaius.browser;

import java.net.URL;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.teavm.jso.JSBody;

/** Performs one bounded reconnect after a cold server-pack download times out. */
public final class BrowserMultiplayerRecovery {
    private BrowserMultiplayerRecovery() {
    }

    public static void beginConnection(ServerData serverData) {
        String address = serverData == null || serverData.ip == null
                ? ""
                : serverData.ip.trim();
        boolean remote = isRemoteServerAddress(address) && ServerAddress.isValidAddress(address);
        if (remote) {
            BrowserClientNetwork.enableClientPacketDrainForRemoteSession();
        } else {
            BrowserClientNetwork.endCurrentClientPacketDrainRemoteSession("", "non-remote");
            BrowserClientNetwork.clearPendingClientPacketDrainRemoteSession("non-remote");
        }
        // Record the attempt before creating the token so late disconnect/reconnect callbacks
        // can be compared against the exact address and attempt generation.
        beginConnectionAttempt(address);
        if (remote) {
            BrowserClientNetwork.beginClientPacketDrainRemoteSession(address);
        }
    }

    public static boolean maybeReconnect(
            Minecraft minecraft, ServerData serverData, String disconnectReason) {
        if (serverData == null) {
            return false;
        }
        if (minecraft == null) {
            return false;
        }
        String address = serverData.ip == null ? "" : serverData.ip.trim();
        boolean remote = isRemoteServerAddress(address) && ServerAddress.isValidAddress(address);
        if (!remote
                || !isTransientTimeoutReason(disconnectReason)
                || !consumePreparedColdPackRetry(address)) {
            if (remote) {
                BrowserClientNetwork.endCurrentClientPacketDrainRemoteSession(
                        address, "recovery-rejected");
            }
            BrowserClientNetwork.clearPendingClientPacketDrainRemoteSession("recovery-rejected");
            return false;
        }
        String sessionToken = takePreparedRemoteSessionToken();

        Screen parent = minecraft.gaius$getScreen();
        ServerAddress parsedAddress = ServerAddress.parseString(address);
        report("cold-pack-timeout-retry-scheduled", address);
        minecraft.execute(() -> {
            if (minecraft.level != null || minecraft.gaius$getScreen() != parent) {
                report("cold-pack-timeout-retry-cancelled", address);
                if (!sessionToken.isEmpty()) {
                    BrowserClientNetwork.endClientPacketDrainRemoteSession(
                            sessionToken, "retry-cancelled");
                } else {
                    BrowserClientNetwork.endCurrentClientPacketDrainRemoteSession(
                            address, "retry-cancelled");
                    BrowserClientNetwork.clearPendingClientPacketDrainRemoteSession(
                            "retry-cancelled");
                }
                return;
            }
            report("cold-pack-timeout-retry-started", address);
            ConnectScreen.startConnecting(
                    parent,
                    minecraft,
                    parsedAddress,
                    serverData,
                    false,
                    null);
        });
        return true;
    }

    /** Claims recovery before vanilla removes the active server-pack source. */
    public static boolean prepareDisconnect(ServerData serverData, String disconnectReason) {
        if (serverData == null) {
            return false;
        }
        String address = serverData.ip == null ? "" : serverData.ip.trim();
        if (!isRemoteServerAddress(address)
                || !ServerAddress.isValidAddress(address)
                || !isTransientTimeoutReason(disconnectReason)
                || !prepareColdPackRetry(address)) {
            return false;
        }
        rememberPreparedRemoteSessionToken(
                BrowserClientNetwork.currentClientPacketDrainRemoteSessionToken());
        report("cold-pack-timeout-retry-prepared", address);
        return true;
    }

    static void rememberRequiredServerPack(UUID id, URL url, String hash) {
        rememberRequiredServerPack(
                id == null ? "" : id.toString(),
                url == null ? "" : url.toString(),
                hash == null ? "" : hash);
    }

    static boolean reusePreservedServerPack(UUID id, URL url, String hash) {
        return reusePreservedServerPack(
                id == null ? "" : id.toString(),
                url == null ? "" : url.toString(),
                hash == null ? "" : hash);
    }

    public static boolean isRemoteServerAddress(String address) {
        if (address == null) {
            return false;
        }
        String normalized = address.trim().toLowerCase(Locale.ROOT);
        return !normalized.isEmpty() && !normalized.contains(".gaius-local");
    }

    public static boolean isTransientTimeoutReason(String reason) {
        if (reason == null) {
            return false;
        }
        String normalized = reason.toLowerCase(Locale.ROOT);
        if (normalized.contains("login")
                || normalized.contains("log in")
                || normalized.contains("auth")
                || normalized.contains("password")
                || normalized.contains("banned")
                || normalized.contains("ban reason")
                || normalized.contains("whitelist")
                || normalized.contains("incompatible")
                || normalized.contains("outdated")
                || normalized.contains("secure profile")
                || normalized.contains("\u767b\u5f55")
                || normalized.contains("\u5bc6\u7801")
                || normalized.contains("\u8ba4\u8bc1")
                || normalized.contains("\u9a8c\u8bc1")
                || normalized.contains("\u5c01\u7981")
                || normalized.contains("\u767d\u540d\u5355")
                || normalized.contains("\u7248\u672c")) {
            return false;
        }
        return normalized.contains("readtimeoutexception")
                || normalized.contains("timed out")
                || normalized.contains("connection timeout");
    }

    @JSBody(params = {"address"}, script = """
            try {
              const state = globalThis.__gaiusMultiplayerRecovery;
              const now = Date.now();
              const cachedAt = state ? Number(state.packCachedAt || 0) : 0;
              if (!state || cachedAt <= 0 || now < cachedAt || now - cachedAt > 600000) {
                return false;
              }
              if (Number(state.packClaimedAt || 0) >= cachedAt) return false;
              const normalized = String(address || '').trim().toLowerCase();
              if (!normalized) return false;
              const activeAttempt = Number(state.activeAttempt || 0);
              const packAttempt = Number(state.packAttempt || 0);
              if (String(state.activeAddress || '') !== normalized ||
                  String(state.packAddress || '') !== normalized ||
                  activeAttempt <= 0 || packAttempt !== activeAttempt) {
                return false;
              }
              try {
                const key = 'gaius.multiplayer.cold-pack-retry.v1';
                const raw = globalThis.sessionStorage && sessionStorage.getItem(key);
                const previous = raw ? JSON.parse(raw) : null;
                if (previous && previous.address === normalized &&
                    Number(previous.at || 0) > 0 && now - Number(previous.at) < 300000) {
                  return false;
                }
                if (globalThis.sessionStorage) {
                  sessionStorage.setItem(key, JSON.stringify({address: normalized, at: now}));
                }
              } catch (ignored) {}
              state.packClaimedAt = cachedAt;
              state.retryAddress = normalized;
              state.retryPreparedAt = now;
              state.retryConsumedAt = 0;
              const requestId = String(state.requestPackId || '').trim().toLowerCase();
              const requestUrl = String(state.requestPackUrl || '');
              const requestHash = String(state.requestPackHash || '').trim().toLowerCase();
              const packPath = String(state.packPath || '').toLowerCase();
              const requestAttempt = Number(state.requestPackAttempt || 0);
              const requestAddress = String(state.requestPackAddress || '');
              const reusable = requestId && requestUrl && requestHash &&
                requestAttempt === activeAttempt && requestAddress === normalized &&
                packPath.endsWith('/' + requestId + '/' + requestHash);
              state.preservePackRequested = !!reusable;
              state.preservePackId = reusable ? requestId : '';
              state.preservePackUrl = reusable ? requestUrl : '';
              state.preservePackHash = reusable ? requestHash : '';
              state.preservePackAddress = reusable ? normalized : '';
              return true;
            } catch (error) {
              return false;
            }
            """)
    private static native boolean prepareColdPackRetry(String address);

    @JSBody(params = {"address"}, script = """
            try {
              const state = globalThis.__gaiusMultiplayerRecovery;
              const normalized = String(address || '').trim().toLowerCase();
              const preparedAt = state ? Number(state.retryPreparedAt || 0) : 0;
              const now = Date.now();
              if (!state || !normalized || String(state.retryAddress || '') !== normalized ||
                  preparedAt <= 0 || now < preparedAt || now - preparedAt > 15000 ||
                  Number(state.retryConsumedAt || 0) >= preparedAt) {
                return false;
              }
              state.retryConsumedAt = now;
              return true;
            } catch (error) {
              return false;
            }
            """)
    private static native boolean consumePreparedColdPackRetry(String address);

    @JSBody(params = "token", script = """
            try {
              const state = globalThis.__gaiusMultiplayerRecovery ||
                (globalThis.__gaiusMultiplayerRecovery = {});
              state.retrySessionToken = String(token || '');
            } catch (ignored) {}
            """)
    private static native void rememberPreparedRemoteSessionToken(String token);

    @JSBody(script = """
            const state = globalThis.__gaiusMultiplayerRecovery;
            if (!state) return '';
            const token = String(state.retrySessionToken || '');
            state.retrySessionToken = '';
            return token;
            """)
    private static native String takePreparedRemoteSessionToken();

    @JSBody(script = """
            try {
              const state = globalThis.__gaiusMultiplayerRecovery;
              const now = Date.now();
              const preparedAt = state ? Number(state.retryPreparedAt || 0) : 0;
              if (!state || !state.preservePackRequested || preparedAt <= 0 ||
                  now < preparedAt || now - preparedAt > 15000) {
                return false;
              }
              state.preservePackRequested = false;
              state.packPreserved = true;
              state.packPreservedAt = now;
              return true;
            } catch (error) {
              return false;
            }
            """)
    static native boolean keepServerPackForRecovery();

    @JSBody(script = """
            try {
              const state = globalThis.__gaiusMultiplayerRecovery;
              if (!state) return;
              state.preservePackRequested = false;
              state.packPreserved = false;
              state.requestPackId = '';
              state.requestPackUrl = '';
              state.requestPackHash = '';
              state.requestPackAddress = '';
              state.requestPackAttempt = 0;
            } catch (ignored) {}
            """)
    static native void clearServerPackReuse();

    @JSBody(params = {"id", "url", "hash"}, script = """
            try {
              const state = globalThis.__gaiusMultiplayerRecovery ||
                (globalThis.__gaiusMultiplayerRecovery = {});
              state.requestPackId = String(id || '').trim().toLowerCase();
              state.requestPackUrl = String(url || '');
              state.requestPackHash = String(hash || '').trim().toLowerCase();
              state.requestPackAddress = String(state.activeAddress || '');
              state.requestPackAttempt = Number(state.activeAttempt || 0);
            } catch (ignored) {}
            """)
    private static native void rememberRequiredServerPack(
            String id, String url, String hash);

    @JSBody(params = {"id", "url", "hash"}, script = """
            try {
              const state = globalThis.__gaiusMultiplayerRecovery;
              const normalizedId = String(id || '').trim().toLowerCase();
              const normalizedUrl = String(url || '');
              const normalizedHash = String(hash || '').trim().toLowerCase();
              const now = Date.now();
              const preservedAt = state ? Number(state.packPreservedAt || 0) : 0;
              if (!state || !state.packPreserved || preservedAt <= 0 ||
                  now < preservedAt || now - preservedAt > 120000 ||
                  String(state.preservePackId || '') !== normalizedId ||
                  String(state.preservePackUrl || '') !== normalizedUrl ||
                  String(state.preservePackHash || '') !== normalizedHash ||
                  String(state.activeAddress || '') !== String(state.preservePackAddress || '')) {
                return false;
              }
              state.packPreserved = false;
              state.packReusedAt = now;
              state.requestPackId = normalizedId;
              state.requestPackUrl = normalizedUrl;
              state.requestPackHash = normalizedHash;
              state.requestPackAddress = String(state.activeAddress || '');
              state.requestPackAttempt = Number(state.activeAttempt || 0);
              state.packAddress = String(state.activeAddress || '');
              state.packAttempt = Number(state.activeAttempt || 0);
              return true;
            } catch (error) {
              return false;
            }
            """)
    private static native boolean reusePreservedServerPack(
            String id, String url, String hash);

    @JSBody(params = {"address"}, script = """
            try {
              const state = globalThis.__gaiusMultiplayerRecovery ||
                (globalThis.__gaiusMultiplayerRecovery = {});
              state.activeAttempt = (state.activeAttempt|0) + 1;
              state.activeAddress = String(address || '').trim().toLowerCase();
              state.connectionStartedAt = Date.now();
              state.retrySessionToken = '';
            } catch (ignored) {}
            """)
    private static native void beginConnectionAttempt(String address);

    @JSBody(params = {"event", "detail"}, script = """
            try {
              const counters = globalThis.__gaiusMinecraftCounters ||
                (globalThis.__gaiusMinecraftCounters = {});
              const key = 'multiplayer-recovery:' + event;
              counters[key] = (counters[key] || 0) + 1;
              const events = globalThis.__gaiusMinecraftEvents ||
                (globalThis.__gaiusMinecraftEvents = []);
              events.push({event: key, detail: String(detail || ''), at: Date.now()});
              if (events.length > 500) events.splice(0, events.length - 500);
            } catch (ignored) {}
            """)
    private static native void report(String event, String detail);
}
