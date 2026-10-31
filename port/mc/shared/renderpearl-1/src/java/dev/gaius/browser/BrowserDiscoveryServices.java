package dev.gaius.browser;

import com.mojang.authlib.services.MinecraftServicesDiscoveryService;
import java.net.Proxy;

/**
 * authlib 10 discovery for the browser (Minecraft 26.3, PLAN D7).
 *
 * <p>Vanilla 26.3 always calls {@code MinecraftServicesDiscoveryService.create(Proxy, boolean)} in
 * {@code Minecraft.<init>} and (after the Gaius server patch) in the dedicated-server {@code Main};
 * the boolean only enables the services key set, while the discovery fetch of
 * {@code https://discovery.minecraftservices.com/minecraft/client} starts either way, on a
 * background executor that retries forever. In an offline browser session that request cannot
 * succeed: the relay proxy is not connected, and in the TeaVM client the proxied fetch suspends
 * from a non-threading context ("Suspension point reached from non-threading context"). authlib
 * 9's {@code createOffline} never fetched, which is what 26.2 used for offline sessions.
 *
 * <p>{@code ServerPatches263.patchDiscoveryServiceBrowser} retargets both vanilla calls here:
 * an online session keeps vanilla discovery; an offline one (and the Worker, whose
 * {@code __gaiusSessionMode} is never {@code online}) gets {@code createOffline}, whose endpoint
 * lookups throw {@code MinecraftClientException} that the session, profile and friends services
 * already catch ({@code gaiusAllowedTextureUrl} covers {@code unpackTextures}).
 */
public final class BrowserDiscoveryServices {
    private BrowserDiscoveryServices() {
    }

    public static MinecraftServicesDiscoveryService create(Proxy proxy, boolean servicesKeySet) {
        if (BrowserProfile.isOnline()) {
            return MinecraftServicesDiscoveryService.create(proxy, servicesKeySet);
        }
        return MinecraftServicesDiscoveryService.createOffline(proxy);
    }
}
