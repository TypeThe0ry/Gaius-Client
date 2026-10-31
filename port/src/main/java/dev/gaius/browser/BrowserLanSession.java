package dev.gaius.browser;

import dev.gaius.browser.ops.VersionOps;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import java.util.UUID;
import org.teavm.jso.JSBody;

/**
 * Browser equivalent of vanilla's Open to LAN action for the Worker-hosted world.
 *
 * <p>One source for every profile. Two details are not fixed:
 * <ul>
 *   <li>the profile it reports when the page did not define {@code __gaiusProfileId} is the
 *       running client's own version id, never a fixed one, so a page cannot publish an invite
 *       for another version;</li>
 *   <li>how many pause-menu grid columns the full-width (204) button spans depends on where the
 *       pause-menu hook (MinecraftClientPatcher's patchPauseScreenBrowserSingleplayer) sits in
 *       the vanilla menu of the render seam generation, so {@link VersionOps} answers it. With a
 *       one-column span GridLayout widens column 0 to 204 for every row; where the hook runs
 *       before the Options row the button spans both columns, like Return to Game and
 *       Disconnect.</li>
 * </ul>
 */
public final class BrowserLanSession {
    static final String INVITE_IDLE = "idle";
    static final String INVITE_PENDING = "pending";
    static final String INVITE_DONE = "done";
    static final String INVITE_ERROR = "error";

    /**
     * Vanilla's own "Open to LAN" label in the client's language: 26.x moved it to
     * {@code menu.multiplayerOptions.button} and lists {@code menu.shareToLan} as deprecated (its
     * language files no longer load it), 1.21.11 only has {@code menu.shareToLan}.
     */
    static Component openToLanLabel() {
        return Component.translatable(Language.getInstance().has("menu.multiplayerOptions.button")
                ? "menu.multiplayerOptions.button" : "menu.shareToLan");
    }

    private BrowserLanSession() {
    }

    /** Adds the action to the pause menu only when a live local Worker world exists. */
    public static void maybeAddButton(Minecraft minecraft, GridLayout.RowHelper row) {
        if (minecraft == null || row == null || !BrowserSingleplayerClient.hasActiveWorkerSession()) {
            return;
        }
        row.addChild(Button.builder(openToLanLabel(),
                        ignored -> BrowserScreens.show(new BrowserShareToLanScreen(minecraft.gaius$getScreen())))
                .width(204)
                .build(), VersionOps.INSTANCE.pauseMenuLanButtonColumns());
    }

    /**
     * Publishes a relay-backed LAN/share invitation through the browser shell; false when no
     * Worker world can take the connection. The result arrives through {@link #inviteState()}.
     */
    public static boolean open(Minecraft minecraft) {
        if (minecraft == null || !BrowserSingleplayerClient.hasActiveWorkerSession()) {
            return false;
        }
        String brokerSessionId = UUID.randomUUID().toString().replace("-", "");
        if (brokerSessionId.length() != 32
                || !BrowserSingleplayerClient.requestLanServerConnection(brokerSessionId)) {
            return false;
        }
        publishLanInvite(brokerSessionId, clientVersionId());
        return true;
    }

    /** The running client's version id (SharedConstants), or "" when it is not known yet. */
    static String clientVersionId() {
        try {
            String id = SharedConstants.getCurrentVersion().id();
            return id == null ? "" : id;
        } catch (RuntimeException unknownVersion) {
            return "";
        }
    }

    /**
     * Kept as a separate hook so the browser shell can expose a stable acceptance contract. The
     * shell answers with a promise; its outcome is kept in {@code __gaiusLanInviteState} for
     * {@link BrowserShareToLanScreen}, which shows the link in game ({@code inGame}: the shell
     * then opens no page dialog of its own).
     */
    @JSBody(params = {"brokerSessionId", "clientVersionId"}, script = """
            const root = globalThis;
            const state = {state: 'pending', url: '', error: ''};
            root.__gaiusLanInviteState = state;
            const fail = function(error) {
              if (root.__gaiusLanInviteState !== state) return;
              state.state = 'error';
              state.error = String(error && error.message || error || '');
            };
            if (typeof root.__gaiusOpenToLan === 'function') {
              const session = root.__gaiusSession && typeof root.__gaiusSession === 'object'
                ? root.__gaiusSession : {};
              try {
                Promise.resolve(root.__gaiusOpenToLan({
                  profile: String(root.__gaiusProfileId || clientVersionId || ''),
                  username: String(session.username || ''),
                  brokerSessionId: String(brokerSessionId || ''),
                  inGame: true
                })).then(function(result) {
                  if (root.__gaiusLanInviteState !== state) return;
                  if (result && result.ok && typeof result.url === 'string' && result.url) {
                    state.state = 'done';
                    state.url = result.url;
                  } else {
                    fail(result && (result.message || result.reason) || 'unavailable');
                  }
                }, fail);
              } catch (error) {
                fail(error);
              }
            } else {
              console.warn('[Gaius] Open to LAN shell is unavailable');
              fail('Open to LAN is unavailable in this page');
            }
            """)
    private static native void publishLanInvite(String brokerSessionId, String clientVersionId);

    /** {@code idle}, {@code pending}, {@code done} or {@code error}: the last invite's outcome. */
    @JSBody(script = """
            const state = globalThis.__gaiusLanInviteState;
            return state && typeof state.state === 'string' ? state.state : 'idle';
            """)
    static native String inviteState();

    @JSBody(script = """
            const state = globalThis.__gaiusLanInviteState;
            return state && typeof state.url === 'string' ? state.url : '';
            """)
    static native String inviteUrl();

    @JSBody(script = """
            const state = globalThis.__gaiusLanInviteState;
            return state && typeof state.error === 'string' ? state.error : '';
            """)
    static native String inviteError();
}
