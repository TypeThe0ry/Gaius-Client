package dev.gaius.browser;

import org.teavm.jso.JSBody;

/**
 * Mouse-button telemetry of the Minecraft 26.3 input path (work package P2).
 *
 * <p>Contract C6: the three methods keep the signatures of the 26.2
 * {@code org.lwjgl.glfw.BrowserGlfw} report methods and write the same fields of
 * {@code window.__gaiusInputStats} ({@code mouseHandlerEntry},
 * {@code mouseHandlerDispatch}, {@code mouseClickedResult}), so tools read one shape on
 * both profiles. {@code InputPatches263} calls them from
 * {@code MouseHandler.onButton(JLnet/minecraft/client/input/MouseButtonInfo;I)V}:
 * <ul>
 *   <li>{@code reportMouseHandlerEntry(JJII)V} after the window lookup;</li>
 *   <li>{@code reportMouseHandlerDispatch(DDZLjava/lang/Object;)V} once the
 *       MouseButtonEvent exists;</li>
 *   <li>{@code reportMouseClickedResult(ZDDLjava/lang/Object;)V} with the result of
 *       {@code Screen.mouseClicked}.</li>
 * </ul>
 * Unreachable on 26.2, where MinecraftClientPatcher still calls BrowserGlfw.
 */
public final class BrowserInputTelemetry {
    private BrowserInputTelemetry() {
    }

    @JSBody(params = {"callbackWindow", "minecraftWindow", "button", "action"}, script = """
            const stats = window.__gaiusInputStats || (window.__gaiusInputStats = {
              callbacks: {},
              events: {},
              callbackMisses: {},
              totalEvents: 0,
              lastEvent: null
            });
            stats.mouseHandlerEntry = {
              callbackWindow: Number(callbackWindow),
              minecraftWindow: Number(minecraftWindow),
              windowMatches: Number(callbackWindow) === Number(minecraftWindow),
              button: button | 0,
              action: action | 0,
              at: Date.now()
            };
            """)
    public static native void reportMouseHandlerEntry(
            long callbackWindow, long minecraftWindow, int button, int action);

    @JSBody(params = {"scaledX", "scaledY", "pressed", "screen"}, script = """
            const stats = window.__gaiusInputStats || (window.__gaiusInputStats = {
              callbacks: {},
              events: {},
              callbackMisses: {},
              totalEvents: 0,
              lastEvent: null
            });
            stats.mouseHandlerDispatch = {
              scaledX: Number(scaledX),
              scaledY: Number(scaledY),
              pressed: !!pressed,
              screen: screen == null ? null : String(screen),
              at: Date.now()
            };
            """)
    public static native void reportMouseHandlerDispatch(
            double scaledX, double scaledY, boolean pressed, Object screen);

    @JSBody(params = {"clicked", "scaledX", "scaledY", "screen"}, script = """
            const stats = window.__gaiusInputStats || (window.__gaiusInputStats = {
              callbacks: {},
              events: {},
              callbackMisses: {},
              totalEvents: 0,
              lastEvent: null
            });
            stats.mouseClickedResult = {
              clicked: !!clicked,
              scaledX: Number(scaledX),
              scaledY: Number(scaledY),
              screen: screen == null ? null : String(screen),
              at: Date.now()
            };
            """)
    public static native void reportMouseClickedResult(
            boolean clicked, double scaledX, double scaledY, Object screen);
}
