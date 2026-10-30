package org.lwjgl.glfw;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;
import dev.gaius.browser.BrowserClipboard;
import dev.gaius.browser.HostLoop;
import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

/**
 * Browser implementation behind the original LWJGL GLFW API.
 *
 * <p>DOM callbacks only enqueue primitive event records. Java callbacks are
 * invoked from {@link #pollEvents()}, preserving GLFW's single-threaded event
 * dispatch model.</p>
 */
public final class BrowserGlfw {
    private static final long WINDOW = 1L;
    private static final long MONITOR = 2L;

    private static GLFWErrorCallback errorCallback;
    private static GLFWMonitorCallbackI monitorCallback;
    private static GLFWWindowPosCallbackI windowPosCallback;
    private static GLFWWindowSizeCallbackI windowSizeCallback;
    private static GLFWWindowCloseCallbackI windowCloseCallback;
    private static GLFWWindowFocusCallbackI windowFocusCallback;
    private static GLFWWindowIconifyCallbackI windowIconifyCallback;
    private static GLFWFramebufferSizeCallbackI framebufferSizeCallback;
    private static GLFWCursorEnterCallbackI cursorEnterCallback;
    private static GLFWKeyCallbackI keyCallback;
    private static GLFWCharCallbackI charCallback;
    private static GLFWCharModsCallbackI charModsCallback;
    private static GLFWMouseButtonCallbackI mouseButtonCallback;
    private static GLFWCursorPosCallbackI cursorPosCallback;
    private static GLFWScrollCallbackI scrollCallback;
    private static GLFWDropCallbackI dropCallback;

    private static boolean shouldClose;
    private static int swapInterval;
    /** GLFW_CLIENT_API of the next window: OpenGL (a WebGL 2 context) or GLFW_NO_API (none). */
    private static int clientApiHint = GLFW.GLFW_OPENGL_API;
    private static int windowClientApi = GLFW.GLFW_OPENGL_API;
    private static boolean windowOpen;
    private static double cursorX;
    private static double cursorY;
    private static ByteBuffer videoModeMemory;

    private BrowserGlfw() {
    }

    public static boolean init() {
        installCanvasHost();
        BrowserClipboard.install();
        installDomBridge();
        return true;
    }

    public static void terminate() {
    }

    public static void defaultWindowHints() {
        clientApiHint = GLFW.GLFW_OPENGL_API;
    }

    /** Only GLFW_CLIENT_API matters: GLFW_NO_API windows (a WebGPU backend) never get a GL context. */
    public static void windowHint(int hint, int value) {
        if (hint == GLFW.GLFW_CLIENT_API) {
            clientApiHint = value;
        }
    }

    public static void windowHintString(int hint, CharSequence value) {
    }

    /**
     * Adopts (or creates) the game canvas. The WebGL 2 context is not taken here:
     * an OpenGL window gets it at {@link #makeContextCurrent}, or from the first read of
     * {@code window.__gaiusWebGL}, whichever comes first; a GLFW_NO_API window never does, so a
     * WebGPU backend can take the canvas instead.
     */
    public static long createWindow(int width, int height, CharSequence title, long monitor, long share) {
        installCanvasHost();
        createCanvas(width, height, title == null ? "Minecraft" : title.toString());
        windowClientApi = clientApiHint;
        windowOpen = true;
        if (windowClientApi != GLFW.GLFW_NO_API) {
            deferContext();
        }
        shouldClose = false;
        return WINDOW;
    }

    /**
     * Drops a context that was never made current and gives the next window a fresh canvas when
     * this one holds a context another API cannot share (a WebGPU backend that gave up after
     * configuring it; a canvas keeps its first context kind). A WebGL 2 context stays for the next GL window.
     */
    public static void destroyWindow(long window) {
        if (window != WINDOW || !windowOpen) {
            return;
        }
        windowOpen = false;
        releaseWindowCanvas();
    }

    public static boolean windowShouldClose(long window) {
        return shouldClose;
    }

    public static void setWindowShouldClose(long window, boolean value) {
        shouldClose = value;
    }

    public static void setWindowTitle(long window, CharSequence title) {
        setTitle(title == null ? "Minecraft" : title.toString());
    }

    public static void setWindowIcon(long window, GLFWImage.Buffer icons) {
    }

    public static void getWindowPos(long window, int[] x, int[] y) {
        set(x, 0);
        set(y, 0);
    }

    public static void setWindowPos(long window, int x, int y) {
    }

    public static void getWindowSize(long window, int[] width, int[] height) {
        set(width, canvasCssWidth());
        set(height, canvasCssHeight());
    }

    public static void setWindowSizeLimits(long window, int minWidth, int minHeight, int maxWidth, int maxHeight) {
    }

    public static void setWindowSize(long window, int width, int height) {
        resizeCanvas(width, height);
    }

    public static void getFramebufferSize(long window, int[] width, int[] height) {
        set(width, framebufferWidth());
        set(height, framebufferHeight());
    }

    public static long getWindowMonitor(long window) {
        return 0L;
    }

    public static void setWindowMonitor(
            long window, long monitor, int x, int y, int width, int height, int refreshRate) {
        resizeCanvas(width, height);
    }

    public static int getWindowAttrib(long window, int attribute) {
        return attribute == GLFW.GLFW_FOCUSED ? 1 : 0;
    }

    public static int getPlatform() {
        return GLFW.GLFW_PLATFORM_NULL;
    }

    public static boolean platformSupported(int platform) {
        return platform == GLFW.GLFW_PLATFORM_NULL;
    }

    public static PointerBuffer getMonitors() {
        PointerBuffer result = PointerBuffer.allocateDirect(1);
        result.put(0, MONITOR);
        return result;
    }

    public static long getPrimaryMonitor() {
        return MONITOR;
    }

    public static String getMonitorName(long monitor) {
        return "Browser Display";
    }

    public static void getMonitorPos(long monitor, int[] x, int[] y) {
        set(x, 0);
        set(y, 0);
    }

    public static void getMonitorContentScale(long monitor, float[] x, float[] y) {
        float scale = (float) devicePixelRatio();
        set(x, scale);
        set(y, scale);
    }

    public static GLFWVidMode getVideoMode(long monitor) {
        ensureVideoMode();
        return new GLFWVidMode(videoModeMemory.duplicate().order(ByteOrder.nativeOrder()));
    }

    public static GLFWVidMode.Buffer getVideoModes(long monitor) {
        ensureVideoMode();
        return GLFWVidMode.create(MemoryUtil.memAddress(videoModeMemory), 1);
    }

    private static void ensureVideoMode() {
        if (videoModeMemory != null) {
            return;
        }
        videoModeMemory = ByteBuffer.allocateDirect(GLFWVidMode.SIZEOF).order(ByteOrder.nativeOrder());
        videoModeMemory.putInt(GLFWVidMode.WIDTH, screenWidth());
        videoModeMemory.putInt(GLFWVidMode.HEIGHT, screenHeight());
        videoModeMemory.putInt(GLFWVidMode.REDBITS, 8);
        videoModeMemory.putInt(GLFWVidMode.GREENBITS, 8);
        videoModeMemory.putInt(GLFWVidMode.BLUEBITS, 8);
        videoModeMemory.putInt(GLFWVidMode.REFRESHRATE, 60);
    }

    public static void pollEvents() {
        maybeQueueInputWarmup();
        while (eventCount() > 0) {
            int type = eventInt(0);
            int a = eventInt(1);
            int b = eventInt(2);
            int c = eventInt(3);
            int d = eventInt(4);
            double x = eventDouble(5);
            double y = eventDouble(6);
            removeEvent();
            switch (type) {
                case 1 -> {
                    reportInputEvent(type, a, b, c, x, y, keyCallback != null);
                    if (keyCallback != null) {
                        keyCallback.invoke(WINDOW, a, b, c, d);
                    }
                }
                case 2 -> {
                    reportInputEvent(type, a, b, c, x, y, charCallback != null || charModsCallback != null);
                    if (charCallback != null) {
                        charCallback.invoke(WINDOW, a);
                    }
                    if (charModsCallback != null) {
                        charModsCallback.invoke(WINDOW, a, b);
                    }
                }
                case 3 -> {
                    cursorX = x;
                    cursorY = y;
                    reportInputEvent(type, a, b, c, x, y, mouseButtonCallback != null);
                    if (mouseButtonCallback != null) {
                        mouseButtonCallback.invoke(WINDOW, a, b, c);
                    }
                }
                case 4 -> {
                    cursorX = x;
                    cursorY = y;
                    reportInputEvent(type, a, b, c, x, y, cursorPosCallback != null);
                    if (cursorPosCallback != null) {
                        cursorPosCallback.invoke(WINDOW, x, y);
                    }
                }
                case 5 -> {
                    reportInputEvent(type, a, b, c, x, y, scrollCallback != null);
                    if (scrollCallback != null) {
                        scrollCallback.invoke(WINDOW, x, y);
                    }
                }
                case 6 -> {
                    if (windowFocusCallback != null) {
                        windowFocusCallback.invoke(WINDOW, a != 0);
                    }
                }
                case 7 -> {
                    if (windowSizeCallback != null) {
                        windowSizeCallback.invoke(WINDOW, a, b);
                    }
                    if (framebufferSizeCallback != null) {
                        framebufferSizeCallback.invoke(WINDOW, c, d);
                    }
                }
                case 8 -> {
                    shouldClose = true;
                    if (windowCloseCallback != null) {
                        windowCloseCallback.invoke(WINDOW);
                    }
                }
                case 9 -> {
                    if (cursorEnterCallback != null) {
                        cursorEnterCallback.invoke(WINDOW, a != 0);
                    }
                }
                default -> {
                }
            }
        }
    }

    public static void waitEvents() {
        pollEvents();
        sleepForBrowserMillis(1L);
    }

    public static void waitEventsTimeout(double timeout) {
        pollEvents();
        if (timeout > 0.0 && Double.isFinite(timeout)) {
            long millis = (long) Math.floor(timeout * 1000.0);
            // A second 1 ms browser timer commonly overshoots the frame deadline.
            if (millis <= 1L) {
                return;
            }
            sleepForBrowserMillis(Math.max(1L, Math.min(7L, millis - 1L)));
        } else {
            Thread.yield();
        }
    }

    public static void postEmptyEvent() {
    }

    private static void sleepForBrowserMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    public static int getInputMode(long window, int mode) {
        return inputMode(mode);
    }

    public static void setInputMode(long window, int mode, int value) {
        setInputModeJs(mode, value);
    }

    public static boolean rawMouseMotionSupported() {
        return false;
    }

    public static String getKeyName(int key, int scancode) {
        int value = key >= 0 ? key : scancode;
        if (value >= GLFW.GLFW_KEY_A && value <= GLFW.GLFW_KEY_Z) {
            return Character.toString((char) ('a' + (value - GLFW.GLFW_KEY_A)));
        }
        if (value >= GLFW.GLFW_KEY_0 && value <= GLFW.GLFW_KEY_9) {
            return Character.toString((char) ('0' + (value - GLFW.GLFW_KEY_0)));
        }
        return switch (value) {
            case GLFW.GLFW_KEY_APOSTROPHE -> "'";
            case GLFW.GLFW_KEY_COMMA -> ",";
            case GLFW.GLFW_KEY_MINUS -> "-";
            case GLFW.GLFW_KEY_PERIOD -> ".";
            case GLFW.GLFW_KEY_SLASH -> "/";
            case GLFW.GLFW_KEY_SEMICOLON -> ";";
            case GLFW.GLFW_KEY_EQUAL -> "=";
            case GLFW.GLFW_KEY_LEFT_BRACKET -> "[";
            case GLFW.GLFW_KEY_BACKSLASH -> "\\";
            case GLFW.GLFW_KEY_RIGHT_BRACKET -> "]";
            case GLFW.GLFW_KEY_GRAVE_ACCENT -> "`";
            case GLFW.GLFW_KEY_KP_0 -> "0";
            case GLFW.GLFW_KEY_KP_1 -> "1";
            case GLFW.GLFW_KEY_KP_2 -> "2";
            case GLFW.GLFW_KEY_KP_3 -> "3";
            case GLFW.GLFW_KEY_KP_4 -> "4";
            case GLFW.GLFW_KEY_KP_5 -> "5";
            case GLFW.GLFW_KEY_KP_6 -> "6";
            case GLFW.GLFW_KEY_KP_7 -> "7";
            case GLFW.GLFW_KEY_KP_8 -> "8";
            case GLFW.GLFW_KEY_KP_9 -> "9";
            case GLFW.GLFW_KEY_KP_DECIMAL -> ".";
            case GLFW.GLFW_KEY_KP_DIVIDE -> "/";
            case GLFW.GLFW_KEY_KP_MULTIPLY -> "*";
            case GLFW.GLFW_KEY_KP_SUBTRACT -> "-";
            case GLFW.GLFW_KEY_KP_ADD -> "+";
            case GLFW.GLFW_KEY_KP_EQUAL -> "=";
            default -> null;
        };
    }

    public static int getKeyScancode(int key) {
        return key;
    }

    public static int getKey(long window, int key) {
        return keyDown(key) ? GLFW.GLFW_PRESS : GLFW.GLFW_RELEASE;
    }

    public static int getMouseButton(long window, int button) {
        return mouseDown(button) ? GLFW.GLFW_PRESS : GLFW.GLFW_RELEASE;
    }

    public static void getCursorPos(long window, double[] x, double[] y) {
        set(x, cursorX);
        set(y, cursorY);
    }

    public static void setCursorPos(long window, double x, double y) {
        cursorX = x;
        cursorY = y;
    }

    public static long createStandardCursor(int shape) {
        return shape + 16L;
    }

    public static void destroyCursor(long cursor) {
    }

    public static void setCursor(long window, long cursor) {
    }

    public static boolean joystickPresent(int joystick) {
        return false;
    }

    public static void setClipboardString(long window, CharSequence value) {
        writeClipboard(value == null ? "" : value.toString());
    }

    public static String getClipboardString(long window) {
        return readClipboard();
    }

    public static double getTime() {
        return now();
    }

    public static void setTime(double time) {
    }

    public static long getTimerValue() {
        return (long) (now() * 1_000_000.0);
    }

    public static long getTimerFrequency() {
        return 1_000_000L;
    }

    public static void makeContextCurrent(long window) {
        if (window == WINDOW && windowOpen && windowClientApi != GLFW.GLFW_NO_API) {
            attachContext();
        }
    }

    public static long getCurrentContext() {
        return windowOpen && windowClientApi == GLFW.GLFW_NO_API ? 0L : WINDOW;
    }

    @JSBody(script = """
            const hidden=document.visibilityState!=='visible';
            const fps=window.__gaiusFps || (window.__gaiusFps={});
            const telemetry=window.__gaiusFrameTelemetry;
            let now;
            if (telemetry && telemetry.enabled) {
              now=(typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();
              if (!Number.isFinite(telemetry.startedAt)) telemetry.startedAt=now;
              const previous=telemetry.lastFrameAt;
              if (Number.isFinite(previous) && previous > 0) {
                const frameElapsed=Math.max(0, now-previous);
                const bucket=Math.min(4000, Math.floor(frameElapsed*4));
                let histogram=telemetry.histogram;
                if (!histogram || histogram.length !== 4001) {
                  histogram=new Uint32Array(4001);
                  telemetry.histogram=histogram;
                }
                histogram[bucket]=histogram[bucket]+1;
                telemetry.frameCount=(telemetry.frameCount||0)+1;
                telemetry.totalFrameMillis=(telemetry.totalFrameMillis||0)+frameElapsed;
                telemetry.longestFrameMillis=Math.max(telemetry.longestFrameMillis||0, frameElapsed);
                if (frameElapsed >= 500) {
                  telemetry.freezeCount=(telemetry.freezeCount||0)+1;
                }
                let samples=telemetry.frameTimes;
                if (!(samples instanceof Float32Array)) {
                  const requested=Number(telemetry.sampleCapacity);
                  const capacity=Number.isFinite(requested)
                    ? Math.max(1024, Math.min(65536, Math.floor(requested)))
                    : 65536;
                  samples=new Float32Array(capacity);
                  telemetry.frameTimes=samples;
                  telemetry.sampleCapacity=capacity;
                  telemetry.sampleWriteIndex=0;
                  telemetry.sampleCount=0;
                }
                const writeIndex=(Number(telemetry.sampleWriteIndex)||0)%samples.length;
                samples[writeIndex]=frameElapsed;
                telemetry.sampleWriteIndex=(writeIndex+1)%samples.length;
                telemetry.sampleCount=Math.min(
                  samples.length,
                  (Number(telemetry.sampleCount)||0)+1
                );
              }
              if (hidden) telemetry.hiddenFrameCount=(telemetry.hiddenFrameCount||0)+1;
              else telemetry.visibleFrameCount=(telemetry.visibleFrameCount||0)+1;
              telemetry.lastFrameAt=now;
            }
            fps.gameFrames=(fps.gameFrames||0)+1;
            fps.gameSampleCounter=((fps.gameSampleCounter||0)+1)&15;
            if (fps.gameSampleCounter !== 0 && fps.gameLastSampleAt) {
              if (hidden) {
                window.__gaiusBackgroundFrameThrottles=(window.__gaiusBackgroundFrameThrottles||0)+1;
              }
              return hidden;
            }
            if (now === undefined) {
              now=(typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();
            }
            if (!fps.gameLastSampleAt) fps.gameLastSampleAt=now;
            const elapsed=now-fps.gameLastSampleAt;
            if (elapsed >= 1000) {
              fps.gameFps=Math.round((fps.gameFrames*1000/elapsed)*10)/10;
              fps.gameFrames=0;
              fps.gameLastSampleAt=now;
            }
            if (hidden) {
              window.__gaiusBackgroundFrameThrottles=(window.__gaiusBackgroundFrameThrottles||0)+1;
            }
            return hidden;
            """)
    private static native boolean swapBuffersJs();

    /**
     * The per-frame yield after a present. The WebAssembly GC build (HostLoop.jspi(),
     * constant-folded) does not yield here: the present only records its swap interval, and the
     * patched Minecraft.run waits for the next frame at the head of its loop
     * (HostLoop.awaitAnimationFrame, design D4) with the pacing of scheduleFrameYield
     * (gaius-jspi.js), so the browser gets its turn between two frames and never inside one.
     */
    private static void presentYield(boolean hidden, int interval) {
        if (HostLoop.jspi()) {
            HostLoop.framePresented(hidden, interval);
            return;
        }
        yieldAfterPresent(hidden, interval);
    }

    @Async
    private static native void yieldAfterPresent(boolean hidden, int interval);

    private static void yieldAfterPresent(boolean hidden, int interval, AsyncCallback<Void> callback) {
        scheduleFrameYield(hidden, interval, () -> callback.complete(null));
    }

    @JSBody(params = {"hidden", "interval", "resume"}, script = """
            const root=typeof window!=='undefined' ? window : globalThis;
            const telemetry=root.__gaiusFrameTelemetry;
            const telemetryEnabled=!!(telemetry && telemetry.enabled);
            const synchronizedToDisplay=Number(interval)!==0;
            const clock=() => (typeof performance!=='undefined' && performance.now)
              ? performance.now()
              : Date.now();
            const requestedAt=telemetryEnabled ? clock() : 0;
            if (telemetryEnabled) {
              telemetry.yieldRequestCount=(telemetry.yieldRequestCount||0)+1;
              telemetry.pendingYieldCount=Math.max(
                0,Number(telemetry.pendingYieldCount)||0)+1;
              telemetry.maxPendingYieldCount=Math.max(
                Number(telemetry.maxPendingYieldCount)||0,
                telemetry.pendingYieldCount);
              telemetry.duplicateYieldCallbackCount=
                Number(telemetry.duplicateYieldCallbackCount)||0;
              telemetry.swapInterval=Number(interval)||0;
              if (synchronizedToDisplay) {
                telemetry.vsyncYieldCount=(telemetry.vsyncYieldCount||0)+1;
              } else {
                telemetry.uncappedYieldCount=(telemetry.uncappedYieldCount||0)+1;
              }
              if (hidden) telemetry.hiddenYieldCount=(telemetry.hiddenYieldCount||0)+1;
              else telemetry.visibleYieldCount=(telemetry.visibleYieldCount||0)+1;
            }
            let resumed=false;
            let watchdog=-1;
            let activeMessageScheduler=null;
            let activeMessageTaskId=0;
            const retireMessageChannel=scheduler => {
              const channel=scheduler && scheduler.channel;
              if (!channel) return;
              try { channel.port1.onmessage=null; } catch (ignored) {}
              try { if (channel.port1.close) channel.port1.close(); } catch (ignored) {}
              try { if (channel.port2.close) channel.port2.close(); } catch (ignored) {}
              scheduler.channel=null;
              if (telemetryEnabled) {
                telemetry.messageChannelRebuildCount=
                  (Number(telemetry.messageChannelRebuildCount)||0)+1;
              }
            };
            const detachMessageTask=failed => {
              const scheduler=activeMessageScheduler;
              const taskId=activeMessageTaskId;
              activeMessageScheduler=null;
              activeMessageTaskId=0;
              if (!scheduler || !taskId || !(scheduler.tasks instanceof Map)) return;
              const removed=scheduler.tasks.delete(taskId);
              if (removed && telemetryEnabled) {
                telemetry.cancelledMessageTaskCount=
                  (Number(telemetry.cancelledMessageTaskCount)||0)+1;
              }
              if (failed) retireMessageChannel(scheduler);
            };
            const finish=source => {
              if (resumed) {
                if (telemetryEnabled) {
                  telemetry.duplicateYieldCallbackCount=
                    (Number(telemetry.duplicateYieldCallbackCount)||0)+1;
                }
                return;
              }
              resumed=true;
              if (watchdog >= 0) clearTimeout(watchdog);
              if (source==='message') {
                activeMessageScheduler=null;
                activeMessageTaskId=0;
              } else {
                detachMessageTask(source==='watchdog');
              }
              if (telemetryEnabled) {
                telemetry.pendingYieldCount=Math.max(
                  0,(Number(telemetry.pendingYieldCount)||0)-1);
                const delay=Math.max(0, clock()-requestedAt);
                telemetry.yieldCompletionCount=(telemetry.yieldCompletionCount||0)+1;
                telemetry.lastYieldResumeDelayMillis=delay;
                telemetry.totalYieldResumeDelayMillis=(telemetry.totalYieldResumeDelayMillis||0)+delay;
                telemetry.longestYieldResumeDelayMillis=Math.max(
                  telemetry.longestYieldResumeDelayMillis||0,
                  delay
                );
                if (source==='message') {
                  telemetry.messageChannelYieldCount=(telemetry.messageChannelYieldCount||0)+1;
                } else if (source==='scheduler') {
                  telemetry.schedulerYieldCount=(telemetry.schedulerYieldCount||0)+1;
                } else {
                  telemetry.timerYieldCount=(telemetry.timerYieldCount||0)+1;
                  if (source==='watchdog') {
                    telemetry.watchdogYieldCount=(telemetry.watchdogYieldCount||0)+1;
                  }
                }
              }
              resume();
            };
            const postTask=() => {
              let scheduler=root.__gaiusFrameYieldScheduler;
              if (!scheduler || !(scheduler.tasks instanceof Map)) {
                if (scheduler && scheduler.channel) retireMessageChannel(scheduler);
                scheduler={tasks:new Map(),channel:null,nextTaskId:1};
                root.__gaiusFrameYieldScheduler=scheduler;
              }
              if (!scheduler.channel) {
                if (typeof MessageChannel==='function') {
                  try {
                    const channel=new MessageChannel();
                    channel.port1.onmessage=event => {
                      const taskId=Number(event && event.data)||0;
                      const task=scheduler.tasks.get(taskId);
                      if (!task) return;
                      scheduler.tasks.delete(taskId);
                      task();
                    };
                    scheduler.channel=channel;
                  } catch (ignored) {
                    if (telemetryEnabled) {
                      telemetry.messageChannelCreateFailureCount=
                        (Number(telemetry.messageChannelCreateFailureCount)||0)+1;
                    }
                  }
                }
              }
              if (scheduler.channel) {
                let taskId=(Number(scheduler.nextTaskId)||1)>>>0;
                if (taskId===0) taskId=1;
                scheduler.nextTaskId=(taskId+1)>>>0;
                activeMessageScheduler=scheduler;
                activeMessageTaskId=taskId;
                scheduler.tasks.set(taskId,() => finish('message'));
                try {
                  scheduler.channel.port2.postMessage(taskId);
                } catch (ignored) {
                  detachMessageTask(false);
                  retireMessageChannel(scheduler);
                  if (telemetryEnabled) {
                    telemetry.messageChannelPostFailureCount=
                      (Number(telemetry.messageChannelPostFailureCount)||0)+1;
                  }
                  setTimeout(() => finish('timer'), 0);
                }
              } else {
                setTimeout(() => finish('timer'), 0);
              }
            };
            if (hidden) {
              setTimeout(() => finish('timer'), 50);
            } else if (synchronizedToDisplay && typeof requestAnimationFrame==='function') {
              watchdog=setTimeout(() => finish('watchdog'), 100);
              requestAnimationFrame(() => {
                if (resumed) return;
                if (telemetryEnabled) {
                  const delay=Math.max(0, clock()-requestedAt);
                  telemetry.presentToRafCount=(telemetry.presentToRafCount||0)+1;
                  telemetry.lastPresentToRafMillis=delay;
                  telemetry.totalPresentToRafMillis=(telemetry.totalPresentToRafMillis||0)+delay;
                  telemetry.longestPresentToRafMillis=Math.max(
                    telemetry.longestPresentToRafMillis||0,
                    delay
                  );
                }
                postTask();
              });
            } else {
              watchdog=setTimeout(() => finish('watchdog'), 100);
              // Keep every visible uncapped present on the same MessageChannel task path.
              // Mixing scheduler.yield() into every fourth present makes Chromium defer that
              // continuation behind compositor arbitration, producing a stable 3:1 cadence
              // and periodic multi-refresh frame bubbles on high-refresh displays.
              // Menus (no level): the title screen draws a frame in about 1.5 ms, so uncapped
              // presents ran at hundreds of FPS back to back and starved the compositor (the
              // page showed 5-14 FPS while the game presented 650). In menus each present waits
              // for the next rAF, the vanilla menu limit. In a world presents stay uncapped:
              // BrowserRenderScheduler compiles and uploads terrain sections on a per-frame
              // budget, so holding world frames would slow chunk rendering.
              const state=root.__gaiusMinecraftState;
              if (state && state.level || typeof requestAnimationFrame!=='function') {
                postTask();
              } else {
                let pacer=root.__gaiusUncappedPresentPacer;
                if (!pacer || !Array.isArray(pacer.waiters)) {
                  pacer={rafPending:false,waiters:[]};
                  root.__gaiusUncappedPresentPacer=pacer;
                }
                if (telemetryEnabled) {
                  telemetry.uncappedRafWaitCount=(Number(telemetry.uncappedRafWaitCount)||0)+1;
                }
                pacer.waiters.push(() => {
                  if (!resumed) postTask();
                });
                if (!pacer.rafPending) {
                  pacer.rafPending=true;
                  requestAnimationFrame(() => {
                    pacer.rafPending=false;
                    const waiters=pacer.waiters;
                    pacer.waiters=[];
                    for (let index=0; index<waiters.length; index++) waiters[index]();
                  });
                }
              }
            }
            """)
    private static native void scheduleFrameYield(boolean hidden, int interval, FrameYieldCallback resume);

    public static void swapBuffers(long window) {
        boolean hidden = swapBuffersJs();
        try {
            presentYield(hidden, swapInterval);
        } catch (RuntimeException e) {
            // A frame presented from a JavaScript callback cannot suspend: on 1.21.11 the login
            // packet runs Minecraft.setLevel -> updateScreenAndTick -> runTick(false) inside the
            // network inbound pump. TeaVM refuses the suspension before scheduling anything, so
            // that one frame skips its yield and the next regular frame yields as usual.
            if (!isNonThreadingSuspension(e)) {
                throw e;
            }
        }
    }

    static boolean isNonThreadingSuspension(Throwable error) {
        String message = error.getMessage();
        return message != null && message.contains("Suspension point reached from non-threading context");
    }

    public static void swapInterval(int interval) {
        swapInterval = interval;
    }

    public static int getError(PointerBuffer description) {
        return GLFW.GLFW_NO_ERROR;
    }

    public static GLFWErrorCallback setErrorCallback(GLFWErrorCallbackI callback) {
        GLFWErrorCallback previous = errorCallback;
        if (callback == null) {
            errorCallback = null;
        } else if (callback instanceof GLFWErrorCallback glfwCallback) {
            errorCallback = glfwCallback;
        } else {
            errorCallback = GLFWErrorCallback.create(callback);
        }
        return previous;
    }

    public static GLFWMonitorCallback setMonitorCallback(GLFWMonitorCallbackI callback) {
        monitorCallback = callback;
        return null;
    }

    public static GLFWWindowPosCallback setWindowPosCallback(long window, GLFWWindowPosCallbackI callback) {
        windowPosCallback = callback;
        return null;
    }

    public static GLFWWindowSizeCallback setWindowSizeCallback(long window, GLFWWindowSizeCallbackI callback) {
        windowSizeCallback = callback;
        return null;
    }

    public static GLFWWindowCloseCallback setWindowCloseCallback(long window, GLFWWindowCloseCallbackI callback) {
        windowCloseCallback = callback;
        return null;
    }

    public static GLFWWindowFocusCallback setWindowFocusCallback(long window, GLFWWindowFocusCallbackI callback) {
        windowFocusCallback = callback;
        return null;
    }

    public static GLFWWindowIconifyCallback setWindowIconifyCallback(long window, GLFWWindowIconifyCallbackI callback) {
        windowIconifyCallback = callback;
        return null;
    }

    public static GLFWFramebufferSizeCallback setFramebufferSizeCallback(
            long window, GLFWFramebufferSizeCallbackI callback) {
        framebufferSizeCallback = callback;
        return null;
    }

    public static GLFWCursorEnterCallback setCursorEnterCallback(long window, GLFWCursorEnterCallbackI callback) {
        cursorEnterCallback = callback;
        return null;
    }

    public static GLFWKeyCallback setKeyCallback(long window, GLFWKeyCallbackI callback) {
        keyCallback = callback;
        reportCallback("key", callback != null);
        return null;
    }

    public static GLFWCharCallback setCharCallback(long window, GLFWCharCallbackI callback) {
        charCallback = callback;
        reportCallback("char", callback != null);
        return null;
    }

    public static GLFWCharModsCallback setCharModsCallback(long window, GLFWCharModsCallbackI callback) {
        charModsCallback = callback;
        reportCallback("charMods", callback != null);
        return null;
    }

    public static GLFWMouseButtonCallback setMouseButtonCallback(long window, GLFWMouseButtonCallbackI callback) {
        mouseButtonCallback = callback;
        reportCallback("mouseButton", callback != null);
        return null;
    }

    public static GLFWCursorPosCallback setCursorPosCallback(long window, GLFWCursorPosCallbackI callback) {
        cursorPosCallback = callback;
        reportCallback("cursorPos", callback != null);
        if (callback != null) {
            callback.invoke(WINDOW, cursorX, cursorY);
        }
        return null;
    }

    public static GLFWScrollCallback setScrollCallback(long window, GLFWScrollCallbackI callback) {
        scrollCallback = callback;
        reportCallback("scroll", callback != null);
        return null;
    }

    public static GLFWDropCallback setDropCallback(long window, GLFWDropCallbackI callback) {
        dropCallback = callback;
        return null;
    }

    private static void set(int[] values, int value) {
        if (values != null && values.length != 0) {
            values[0] = value;
        }
    }

    private static void set(float[] values, float value) {
        if (values != null && values.length != 0) {
            values[0] = value;
        }
    }

    private static void set(double[] values, double value) {
        if (values != null && values.length != 0) {
            values[0] = value;
        }
    }

    @JSFunctor
    private interface FrameYieldCallback extends JSObject {
        void run();
    }

    @JSBody(script = """
            if (window.__gaiusGlfwInstalled) return;
            window.__gaiusGlfwInstalled = true;
            window.__gaiusGlfwEvents = [];
            window.__gaiusGlfwEventHead = 0;
            window.__gaiusGlfwPendingMouseMove = -1;
            window.__gaiusGlfwKeys = Object.create(null);
            window.__gaiusGlfwButtons = Object.create(null);
            window.__gaiusGlfwInputModes = Object.create(null);
            window.__gaiusInputStats = window.__gaiusInputStats || {
              callbacks: {},
              events: {},
              callbackMisses: {},
              totalEvents: 0,
              lastEvent: null
            };
            window.__gaiusWantPointerLock = false;
            window.__gaiusPointerLockErrors = [];
            window.__gaiusMaxDpr = Number.isFinite(Number(window.__gaiusMaxDpr))
              ? Number(window.__gaiusMaxDpr)
              : 1.0;
            const rememberPointerLockError = error => {
              const message = String(error && (error.message || error.name) || error);
              window.__gaiusPointerLockLastError = message;
              window.__gaiusPointerLockErrors.push({message: message, at: Date.now()});
              if (window.__gaiusPointerLockErrors.length > 20) window.__gaiusPointerLockErrors.shift();
            };
            // Touch controls drive the disabled cursor through window.__gaiusInput.look()
            // (a virtual pointer lock), so a real lock is never requested while they are active.
            const requestPointerLockIfWanted = () => {
              const c = canvas();
              if (!window.__gaiusWantPointerLock || window.__gaiusTouchActive || !c || !c.requestPointerLock ||
                  document.pointerLockElement === c) {
                return;
              }
              try {
                const lockResult = c.requestPointerLock();
                if (lockResult && lockResult.catch) lockResult.catch(rememberPointerLockError);
              } catch (error) {
                rememberPointerLockError(error);
              }
            };
            const rememberKeyboardLockError = error => {
              window.__gaiusKeyboardLockPending = false;
              window.__gaiusKeyboardLockHeld = false;
              window.__gaiusKeyboardLockLastError = String(error && (error.message || error.name) || error);
            };
            const requestKeyboardLockIfWanted = () => {
              const keyboard = navigator.keyboard;
              if (!document.fullscreenElement || !keyboard || !keyboard.lock || window.__gaiusKeyboardLockHeld || window.__gaiusKeyboardLockPending) {
                return;
              }
              window.__gaiusKeyboardLockPending = true;
              try {
                // Browser accelerators are captured only in API fullscreen.
                // preventDefault alone cannot protect a windowed Ctrl+W.
                // Locking KeyW also covers Ctrl+W while leaving browser
                // accelerators such as Ctrl+R and Ctrl+L available.
                // Locking Escape hands a short press to the game (its pause menu) instead of
                // ending fullscreen and pointer lock; holding Escape still leaves fullscreen.
                const result = keyboard.lock(['KeyW', 'Escape']);
                if (result && result.then) {
                  result.then(() => {
                    window.__gaiusKeyboardLockPending = false;
                    window.__gaiusKeyboardLockHeld = !!document.fullscreenElement;
                  }, rememberKeyboardLockError);
                } else {
                  window.__gaiusKeyboardLockPending = false;
                  window.__gaiusKeyboardLockHeld = true;
                }
              } catch (error) {
                rememberKeyboardLockError(error);
              }
            };
            const requestGameFullscreen = () => {
              const root = document.documentElement;
              if (!window.__gaiusWantPointerLock || document.fullscreenElement ||
                  !navigator.keyboard || !navigator.keyboard.lock || !root.requestFullscreen) {
                requestKeyboardLockIfWanted();
                requestPointerLockIfWanted();
                return;
              }
              if (window.__gaiusFullscreenPending) return;
              window.__gaiusFullscreenPending = true;
              const ready = () => {
                window.__gaiusFullscreenPending = false;
                requestKeyboardLockIfWanted();
                requestPointerLockIfWanted();
              };
              const failed = error => {
                window.__gaiusFullscreenPending = false;
                rememberKeyboardLockError(error);
                requestPointerLockIfWanted();
              };
              try { Promise.resolve(root.requestFullscreen()).then(ready, failed); }
              catch (error) { failed(error); }
            };
            addEventListener('fullscreenchange', () => {
              window.__gaiusKeyboardLockHeld = false;
              if (document.fullscreenElement) requestKeyboardLockIfWanted();
              else if (navigator.keyboard && navigator.keyboard.unlock) navigator.keyboard.unlock();
            });
            const codeMap = {
              Space:32,Apostrophe:39,Comma:44,Minus:45,Period:46,Slash:47,
              Digit0:48,Digit1:49,Digit2:50,Digit3:51,Digit4:52,Digit5:53,Digit6:54,Digit7:55,Digit8:56,Digit9:57,
              Semicolon:59,Equal:61,KeyA:65,KeyB:66,KeyC:67,KeyD:68,KeyE:69,KeyF:70,KeyG:71,KeyH:72,KeyI:73,
              KeyJ:74,KeyK:75,KeyL:76,KeyM:77,KeyN:78,KeyO:79,KeyP:80,KeyQ:81,KeyR:82,KeyS:83,KeyT:84,
              KeyU:85,KeyV:86,KeyW:87,KeyX:88,KeyY:89,KeyZ:90,BracketLeft:91,Backslash:92,BracketRight:93,
              Backquote:96,Escape:256,Enter:257,Tab:258,Backspace:259,Insert:260,Delete:261,ArrowRight:262,
              ArrowLeft:263,ArrowDown:264,ArrowUp:265,PageUp:266,PageDown:267,Home:268,End:269,CapsLock:280,
              ScrollLock:281,NumLock:282,PrintScreen:283,Pause:284,F1:290,F2:291,F3:292,F4:293,F5:294,F6:295,
              F7:296,F8:297,F9:298,F10:299,F11:300,F12:301,Numpad0:320,Numpad1:321,Numpad2:322,Numpad3:323,
              Numpad4:324,Numpad5:325,Numpad6:326,Numpad7:327,Numpad8:328,Numpad9:329,NumpadDecimal:330,
              NumpadDivide:331,NumpadMultiply:332,NumpadSubtract:333,NumpadAdd:334,NumpadEnter:335,NumpadEqual:336,
              ShiftLeft:340,ControlLeft:341,AltLeft:342,MetaLeft:343,ShiftRight:344,ControlRight:345,AltRight:346,MetaRight:347
            };
            const mods = e => (e.shiftKey?1:0)|(e.ctrlKey?2:0)|(e.altKey?4:0)|(e.metaKey?8:0);
            const canvas = () => document.getElementById('mc-canvas');
            const clamp = (value, min, max) => Math.max(min, Math.min(max, value));
            const glfwMouseButton = button => button === 2 ? 1 : (button === 1 ? 2 : button);
            const pushEvent = event => {
              if (!window.__gaiusGlfwEvents) window.__gaiusGlfwEvents = [];
              window.__gaiusGlfwEvents.push(event);
            };
            const pushMouseMove = event => {
              const events = window.__gaiusGlfwEvents || (window.__gaiusGlfwEvents = []);
              const head = window.__gaiusGlfwEventHead | 0;
              const pending = window.__gaiusGlfwPendingMouseMove | 0;
              if (pending >= head && pending < events.length && events[pending] && events[pending][0] === 4) {
                events[pending] = event;
                return;
              }
              window.__gaiusGlfwPendingMouseMove = events.length;
              events.push(event);
            };
            const canvasRect = c => {
              const now = (typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();
              const cached = window.__gaiusCanvasRect;
              if (cached && now - (window.__gaiusCanvasRectAt || 0) < 250) return cached;
              const rect = c ? c.getBoundingClientRect() : {left:0,top:0,width:0,height:0};
              window.__gaiusCanvasRect = {left:rect.left, top:rect.top, width:rect.width, height:rect.height};
              window.__gaiusCanvasRectAt = now;
              return window.__gaiusCanvasRect;
            };
            const updateCursorFromMouseEvent = e => {
              const c=canvas(), r=canvasRect(c);
              const locked=document.pointerLockElement===c;
              window.__gaiusCursorX=locked?(window.__gaiusCursorX||0)+e.movementX:e.clientX-r.left;
              window.__gaiusCursorY=locked?(window.__gaiusCursorY||0)+e.movementY:e.clientY-r.top;
              return [window.__gaiusCursorX, window.__gaiusCursorY];
            };
            // Ctrl+W is a valid sprint + forward chord in Minecraft.  Chrome
            // reserves the same chord for closing a tab. API fullscreen and
            // Keyboard Lock above provide accelerator capture; this guard
            // only suppresses defaults for events delivered to the page.
            // The normal handler still forwards both key events to GLFW.
            addEventListener('keydown', e => {
              if (e.ctrlKey && !e.altKey && !e.metaKey && e.code === 'KeyW') {
                e.preventDefault();
              }
            }, {capture:true, passive:false});
            const urlNumber = name => {
              try {
                const value = new URLSearchParams(location.search).get(name);
                const number = Number(value);
                return Number.isFinite(number) ? number : NaN;
              } catch (ignored) {
                return NaN;
              }
            };
            window.__gaiusResolvePixelRatio = () => {
              const minecraftState = window.__gaiusMinecraftState || null;
              const inWorld = !!(minecraftState && minecraftState.level);
              const minDpr = clamp(
                inWorld
                  ? (Number(window.__gaiusWorldMinDpr) || Number(window.__gaiusMinDpr) || 1.0)
                  : (Number(window.__gaiusMenuMinDpr) || Number(window.__gaiusMinDpr) || 1.0),
                1.0,
                3.0);
              const forced = urlNumber('dpr');
              if (forced > 0) return clamp(forced, 0.2, 3.0);
              const forcedPixelRatio = urlNumber('pixelRatio');
              if (forcedPixelRatio > 0) return clamp(forcedPixelRatio, 0.2, 3.0);
              const urlMax = urlNumber('maxDpr');
              const raw = Number(devicePixelRatio) || 1.0;
              let maxDpr = urlMax > 0 ? urlMax : (Number(window.__gaiusMaxDpr) || 1.0);
              // The quality runtime caps the ratio per GPU tier. The launcher's frame-rate
              // governor still applies once it has stepped __gaiusMaxDpr below its default.
              const quality = window.GaiusQuality ? window.GaiusQuality.runtime : null;
              if (!(urlMax > 0) && quality && typeof quality.resolvePixelRatio === 'function') {
                let tierMax = NaN;
                try {
                  tierMax = Number(quality.resolvePixelRatio(raw));
                } catch (ignored) {
                  tierMax = NaN;
                }
                if (tierMax > 0) {
                  const governed = Number(window.__gaiusMaxDpr) < (Number(window.__gaiusDefaultMaxDpr) || 0);
                  maxDpr = governed ? Math.min(tierMax, Number(window.__gaiusMaxDpr)) : tierMax;
                }
              }
              return clamp(Math.min(raw, maxDpr), minDpr, 3.0);
            };
            window.__gaiusApplyCanvasResolution = (width, height, emitEvent) => {
              const c = canvas();
              if (!c) return;
              const cssWidth = Math.max(1, Math.round(Number(width) || innerWidth || 1));
              const cssHeight = Math.max(1, Math.round(Number(height) || innerHeight || 1));
              const pixelRatio = window.__gaiusResolvePixelRatio();
              const framebufferWidth = Math.max(1, Math.round(cssWidth * pixelRatio));
              const framebufferHeight = Math.max(1, Math.round(cssHeight * pixelRatio));
              c.style.width = cssWidth + 'px';
              c.style.height = cssHeight + 'px';
              const changed = c.width !== framebufferWidth || c.height !== framebufferHeight;
              c.width = framebufferWidth;
              c.height = framebufferHeight;
              window.__gaiusDisplay = {
                cssWidth: cssWidth,
                cssHeight: cssHeight,
                framebufferWidth: framebufferWidth,
                framebufferHeight: framebufferHeight,
                pixelRatio: pixelRatio,
                rawDevicePixelRatio: Number(devicePixelRatio) || 1.0,
                maxDpr: Number(window.__gaiusMaxDpr) || 1.0
              };
              window.__gaiusCanvasRect = null;
              if (emitEvent && changed) {
                pushEvent([7,cssWidth,cssHeight,framebufferWidth,framebufferHeight,0,0]);
              }
            };
            // Key records go through the clipboard bridge (BrowserClipboard): a paste chord
            // keeps its default and its records wait until the system text is known; Cmd+A/C/V/X
            // on Apple platforms reach the game as Ctrl chords, pressed and released at once.
            const clip = () => window.__gaiusClipboardBridge || null;
            // Without Keyboard Lock (windowed, or a browser without it) the browser spends a
            // short Escape on ending pointer lock and the page never sees that key-down. The
            // game then still has the cursor disabled and no pause menu, so the lost lock is
            // handed to it as that one Escape press. A browser that also delivers the key-down
            // is covered both ways: a recent Escape key-down skips the injected press, and a
            // late one right after the injected press is dropped, so the menu opens only once.
            const escapeClock = () => (typeof performance !== 'undefined' && performance.now)
              ? performance.now() : Date.now();
            const escape = {lastDownAt: -Infinity, suppressUntil: -Infinity, lockHeld: false, dropKeyUp: false};
            const pageFocused = () => document.visibilityState !== 'hidden'
              && (typeof document.hasFocus !== 'function' || document.hasFocus());
            const pointerLockLost = lostAt => {
              const c = canvas();
              if (!window.__gaiusWantPointerLock || window.__gaiusTouchActive || (c && document.pointerLockElement === c)) return;
              // Focus moved to another tab, window or application: the focus-loss path
              // reports it and the game's own pauseOnLostFocus decides.
              if (!pageFocused()) return;
              if (escape.lastDownAt >= lostAt - 500) return;
              escape.suppressUntil = escapeClock() + 500;
              window.__gaiusEscapePauseInjected = (window.__gaiusEscapePauseInjected | 0) + 1;
              // Through the clipboard bridge, so it keeps its place behind a pending paste chord.
              const press = () => {
                pushEvent([1,256,27,1,0,0,0]);
                pushEvent([1,256,27,0,0,0,0]);
              };
              const bridge = clip();
              if (bridge) bridge.deliver(press); else press();
            };
            if (typeof document.addEventListener === 'function') {
              document.addEventListener('pointerlockchange', () => {
                const c = canvas();
                const locked = !!c && document.pointerLockElement === c;
                const lost = escape.lockHeld && !locked;
                escape.lockHeld = locked;
                if (!lost || !window.__gaiusWantPointerLock) return;
                const lostAt = escapeClock();
                // Let a blur or visibility change that ended the lock arrive first.
                setTimeout(() => pointerLockLost(lostAt), 100);
              });
            }
            addEventListener('keydown', e => {
              if (e.code === 'Escape') {
                if (!e.repeat && escapeClock() < escape.suppressUntil) {
                  escape.suppressUntil = -Infinity;
                  escape.dropKeyUp = true;
                  e.preventDefault();
                  return;
                }
                escape.lastDownAt = escapeClock();
              }
              const bridge=clip();
              const keepDefault=bridge ? bridge.keyDown(e) : false;
              const edit=!!bridge && bridge.appleEditChord(e);
              const key=codeMap[e.code]===undefined?-1:codeMap[e.code]; window.__gaiusGlfwKeys[key]=!edit;
              const keyMods=edit ? (mods(e)|2) : mods(e);
              const keyCode=e.keyCode;
              const action=e.repeat?2:1;
              const altGraph=typeof e.getModifierState==='function' && e.getModifierState('AltGraph');
              const printable=typeof e.key==='string' &&
                (e.key.length===1 || (e.key.length===2 && e.key.codePointAt(0)>65535)) &&
                !e.metaKey && (!e.ctrlKey || altGraph);
              const codePoint=printable ? e.key.codePointAt(0) : 0;
              const textMods=mods(e);
              const deliver=() => {
                pushEvent([1,key,keyCode,action,keyMods,0,0]);
                if (printable) pushEvent([2,codePoint,textMods,0,0,0,0]);
                if (edit) pushEvent([1,key,keyCode,0,keyMods,0,0]);
              };
              if (bridge) bridge.deliver(deliver); else deliver();
              if (document.activeElement===canvas() && !keepDefault) e.preventDefault();
            });
            addEventListener('keyup', e => {
              if (e.code === 'Escape' && escape.dropKeyUp) {
                escape.dropKeyUp = false;
                return;
              }
              const bridge=clip();
              if (bridge) bridge.keyUp(e);
              const key=codeMap[e.code]===undefined?-1:codeMap[e.code]; window.__gaiusGlfwKeys[key]=false;
              const record=[1,key,e.keyCode,0,mods(e),0,0];
              if (bridge) bridge.deliver(() => pushEvent(record)); else pushEvent(record);
            });
            // Direct input for the launcher's touch controls (touch-controls.js). Positions are
            // CSS pixels relative to #mc-canvas like the mouse listeners below, and buttons use
            // GLFW numbers (0 left, 1 right, 2 middle). Nothing here focuses the canvas or asks
            // for fullscreen or pointer lock, so a focused text field keeps the virtual keyboard
            // open. look() moves the cursor relatively; with the cursor disabled the MouseHandler
            // turns position differences into camera rotation, so no pointer lock is needed.
            const injected = {keys: Object.create(null), buttons: Object.create(null), mods: 0};
            const injectedModBits = {
              ShiftLeft:1,ShiftRight:1,ControlLeft:2,ControlRight:2,AltLeft:4,AltRight:4,MetaLeft:8,MetaRight:8
            };
            const legacyKeyCodes = {
              Escape:27,Enter:13,Backspace:8,Tab:9,Delete:46,ShiftLeft:16,ShiftRight:16,ControlLeft:17,
              ControlRight:17,AltLeft:18,AltRight:18,ArrowLeft:37,ArrowUp:38,ArrowRight:39,ArrowDown:40,F5:116
            };
            const pushInjectedMove = () => {
              pushMouseMove([4,0,0,0,0,window.__gaiusCursorX||0,window.__gaiusCursorY||0]);
            };
            const injectKey = (code, down) => {
              const key = codeMap[code];
              if (key === undefined || !!injected.keys[code] === !!down) return false;
              const bit = injectedModBits[code] | 0;
              if (bit) injected.mods = down ? (injected.mods | bit) : (injected.mods & ~bit);
              injected.keys[code] = !!down;
              window.__gaiusGlfwKeys[key] = !!down;
              const legacy = legacyKeyCodes[code] || ((key >= 48 && key <= 90) || key === 32 ? key : 0);
              pushEvent([1,key,legacy,down?1:0,injected.mods,0,0]);
              return true;
            };
            const injectButton = (button, down) => {
              const b = button | 0;
              if (b < 0 || b > 7 || !!injected.buttons[b] === !!down) return false;
              injected.buttons[b] = !!down;
              window.__gaiusGlfwButtons[b] = !!down;
              pushEvent([3,b,down?1:0,injected.mods,0,window.__gaiusCursorX||0,window.__gaiusCursorY||0]);
              return true;
            };
            window.__gaiusInput = {
              backend: 'glfw',
              pointer: (x, y) => {
                window.__gaiusCursorX = +x || 0;
                window.__gaiusCursorY = +y || 0;
                pushInjectedMove();
              },
              look: (dx, dy) => {
                window.__gaiusCursorX = (window.__gaiusCursorX || 0) + (+dx || 0);
                window.__gaiusCursorY = (window.__gaiusCursorY || 0) + (+dy || 0);
                pushInjectedMove();
              },
              button: injectButton,
              key: injectKey,
              text: value => {
                const text = value == null ? '' : String(value);
                let count = 0;
                for (let i = 0; i < text.length; i++) {
                  const codePoint = text.codePointAt(i);
                  if (codePoint > 0xFFFF) i++;
                  if (codePoint < 32 || codePoint === 127) continue;
                  pushEvent([2,codePoint,0,0,0,0,0]);
                  count++;
                }
                return count > 0;
              },
              // Wheel steps, positive away from the user (GLFW yoffset).
              wheel: (dx, dy) => {
                pushEvent([5,0,0,0,0,+dx||0,+dy||0]);
              },
              releaseAll: () => {
                Object.keys(injected.keys).forEach(code => { if (injected.keys[code]) injectKey(code, false); });
                Object.keys(injected.buttons).forEach(b => { if (injected.buttons[b]) injectButton(b | 0, false); });
              },
              state: () => {
                return {
                  backend: 'glfw',
                  wantPointerLock: !!window.__gaiusWantPointerLock,
                  textInput: null,
                  textInputArea: null
                };
              }
            };
            addEventListener('mousedown', e => {
              const c = canvas();
              if (c) c.focus();
              const p = updateCursorFromMouseEvent(e);
              const button = glfwMouseButton(e.button);
              pushMouseMove([4,0,0,0,0,p[0],p[1]]);
              window.__gaiusGlfwButtons[button]=true;
              pushEvent([3,button,1,mods(e),0,p[0],p[1]]);
              if (e.target === c) requestGameFullscreen();
            });
            addEventListener('mouseup', e => {
              const p = updateCursorFromMouseEvent(e);
              const button = glfwMouseButton(e.button);
              pushMouseMove([4,0,0,0,0,p[0],p[1]]);
              window.__gaiusGlfwButtons[button]=false;
              pushEvent([3,button,0,mods(e),0,p[0],p[1]]);
            });
            addEventListener('mousemove', e => {
              const p = updateCursorFromMouseEvent(e);
              pushMouseMove([4,0,0,0,0,p[0],p[1]]);
            });
            addEventListener('wheel', e => {
              pushEvent([5,0,0,0,0,-e.deltaX/100,-e.deltaY/100]);
              if (document.activeElement===canvas()) e.preventDefault();
            }, {passive:false});
            // The window focus the game sees (Minecraft.setWindowActive, and through it
            // vanilla's pauseOnLostFocus) follows the page: a window blur, and also a hidden
            // page (another tab, a minimized window) that did not blur first. A page made
            // visible again reports the gain only once it has focus, otherwise the next focus does.
            let pageFocusLost = false;
            addEventListener('focus', () => {
              pageFocusLost = false;
              pushEvent([6,1,0,0,0,0,0]);
            });
            addEventListener('blur', () => {
              pageFocusLost = true;
              pushEvent([6,0,0,0,0,0,0]);
            });
            if (typeof document.addEventListener === 'function') {
              document.addEventListener('visibilitychange', () => {
                if (document.visibilityState === 'hidden') {
                  if (!pageFocusLost) {
                    pageFocusLost = true;
                    pushEvent([6,0,0,0,0,0,0]);
                  }
                } else if (pageFocusLost && pageFocused()) {
                  pageFocusLost = false;
                  pushEvent([6,1,0,0,0,0,0]);
                }
              });
            }
            addEventListener('resize', () => {
              window.__gaiusApplyCanvasResolution(innerWidth, innerHeight, true);
            });
            addEventListener('beforeunload', () => {
              if (navigator.keyboard && navigator.keyboard.unlock) {
                try { navigator.keyboard.unlock(); } catch (ignored) {}
              }
              window.__gaiusKeyboardLockHeld = false;
              window.__gaiusKeyboardLockPending = false;
            });
            """)
    private static native void installDomBridge();

    /**
     * The game canvas registry shared with the SDL shim and the WebGPU glue
     * ({@code window.__gaiusCanvasHost}): {@code current()} is the element with the canvas id
     * ({@code mc-canvas}, named only here), {@code replace(reason)} swaps it
     * for a fresh element with the same attributes and size. A canvas keeps the kind of its first
     * context for life (marked as {@code __gaiusContextKind}), so a backend that follows another
     * one in the same session needs a new element.
     */
    @JSBody(script = """
            const w = window;
            if (w.__gaiusCanvasHost && w.__gaiusCanvasHost.version >= 1) return;
            const host = {
              version: 1,
              id: 'mc-canvas',
              replacements: 0,
              lastReason: '',
              hooks: []
            };
            host.current = function () {
              return document.getElementById(host.id);
            };
            host.ensure = function () {
              let canvas = host.current();
              if (!canvas) {
                canvas = document.createElement('canvas');
                canvas.id = host.id;
                canvas.tabIndex = 0;
                document.body.appendChild(canvas);
              }
              return canvas;
            };
            host.replace = function (reason) {
              const previous = host.current();
              const canvas = document.createElement('canvas');
              if (previous) {
                const attributes = previous.attributes;
                for (let index = 0; index < attributes.length; index++) {
                  canvas.setAttribute(attributes[index].name, attributes[index].value);
                }
                canvas.width = previous.width;
                canvas.height = previous.height;
                const focused = document.activeElement === previous;
                previous.replaceWith(canvas);
                if (focused && canvas.focus) canvas.focus();
              } else {
                canvas.id = host.id;
                canvas.tabIndex = 0;
                document.body.appendChild(canvas);
              }
              host.replacements++;
              host.lastReason = String(reason || '');
              w.__gaiusCanvasRect = null;
              for (let index = 0; index < host.hooks.length; index++) {
                try {
                  host.hooks[index](canvas, previous, host.lastReason);
                } catch (ignored) {
                  // A failing hook must not keep the canvas from being replaced.
                }
              }
              try {
                w.dispatchEvent(new CustomEvent('gaius:canvas-replaced',
                  {detail: {canvas: canvas, previous: previous, reason: host.lastReason}}));
              } catch (ignored) {
                // Listeners are optional.
              }
              return canvas;
            };
            host.onReplace = function (hook) {
              if (typeof hook === 'function') host.hooks.push(hook);
            };
            w.__gaiusCanvasHost = host;
            """)
    private static native void installCanvasHost();

    @JSBody(params = {"width", "height", "title"}, script = """
            const canvasElement=window.__gaiusCanvasHost.ensure();
            if (window.__gaiusApplyCanvasResolution) {
              window.__gaiusApplyCanvasResolution(width, height, false);
            } else {
              const quality=window.GaiusQuality?window.GaiusQuality.runtime:null;
              const pixelRatio=quality&&typeof quality.resolvePixelRatio==='function'
                ?(Number(quality.resolvePixelRatio(devicePixelRatio||1))||1)
                :Math.min(devicePixelRatio||1,1);
              canvasElement.style.width=width+'px'; canvasElement.style.height=height+'px';
              canvasElement.width=Math.max(1,Math.round(width*pixelRatio));
              canvasElement.height=Math.max(1,Math.round(height*pixelRatio));
            }
            document.title=title; canvasElement.focus();
            """)
    private static native void createCanvas(int width, int height, String title);

    /**
     * An OpenGL window whose context is not current yet: {@code window.__gaiusWebGL} becomes an
     * accessor that attaches the context on its first read (the GL shim and the quality layer
     * read it there), then turns back into a plain value. A context that already lives on the
     * canvas (a second GL window) is kept. The 1.21.11 WebGPU device factory ({@code D0Devices})
     * uses this state ({@code window.__gaiusGlfwContext}: {@code pending}, {@code gl}) to keep the
     * context off the canvas WebGPU renders to.
     */
    @JSBody(script = """
            const w = window;
            const state = w.__gaiusGlfwContext || (w.__gaiusGlfwContext = {gl: null, pending: false, attached: 0});
            const current = w.__gaiusCanvasHost.current();
            if (state.gl && current && state.gl.canvas === current) {
              state.pending = false;
              Object.defineProperty(w, '__gaiusWebGL',
                {configurable: true, enumerable: true, writable: true, value: state.gl});
              return;
            }
            state.gl = null;
            state.pending = true;
            w.__gaiusGlfwAttachContext = function () {
              if (!state.pending) return state.gl;
              state.pending = false;
              let preserveDrawingBuffer = false;
              try {
                preserveDrawingBuffer = new URLSearchParams(location.search).get('preserveDrawingBuffer') === '1';
              } catch (ignored) {
                preserveDrawingBuffer = false;
              }
              const attributes = {
                alpha: false,
                antialias: false,
                depth: true,
                stencil: true,
                powerPreference: 'high-performance',
                preserveDrawingBuffer: preserveDrawingBuffer
              };
              const host = w.__gaiusCanvasHost;
              let canvasElement = host.ensure();
              let gl = canvasElement.getContext('webgl2', attributes);
              if (!gl) {
                // The canvas already holds another kind of context (an abandoned WebGPU attempt):
                // A canvas keeps its first context kind: a fresh element for the GL context.
                canvasElement = host.replace('glfw-webgl2-context');
                gl = canvasElement.getContext('webgl2', attributes);
              }
              Object.defineProperty(w, '__gaiusWebGL',
                {configurable: true, enumerable: true, writable: true, value: gl || null});
              if (!gl) throw new Error('WebGL 2 is required');
              canvasElement.__gaiusContextKind = 'webgl2';
              state.gl = gl;
              state.attached++;
              w.__gaiusGpuBackend = 'webgl2';
              // Lets the quality runtime enable its extensions on the game context before
              // the first frame instead of attaching lazily.
              if (w.GaiusQuality && w.GaiusQuality.runtime) {
                try {
                  w.GaiusQuality.runtime.onContext(gl);
                } catch (ignored) {
                  // The runtime reports its own failures; the context stays usable.
                }
              }
              return gl;
            };
            Object.defineProperty(w, '__gaiusWebGL', {
              configurable: true,
              enumerable: true,
              get: function () {
                return w.__gaiusGlfwAttachContext();
              },
              set: function (value) {
                state.pending = false;
                Object.defineProperty(w, '__gaiusWebGL',
                  {configurable: true, enumerable: true, writable: true, value: value});
              }
            });
            """)
    private static native void deferContext();

    /** glfwMakeContextCurrent of an OpenGL window: attaches its WebGL 2 context now. */
    @JSBody(script = """
            const w = window;
            const state = w.__gaiusGlfwContext;
            if (state && state.pending && typeof w.__gaiusGlfwAttachContext === 'function') {
              w.__gaiusGlfwAttachContext();
            }
            """)
    private static native void attachContext();

    @JSBody(script = """
            const w = window;
            const state = w.__gaiusGlfwContext;
            if (state && state.pending) {
              // The window goes away before its context was ever needed.
              state.pending = false;
              delete w.__gaiusWebGL;
            }
            const host = w.__gaiusCanvasHost;
            const canvas = host ? host.current() : null;
            if (canvas && canvas.__gaiusContextKind && canvas.__gaiusContextKind !== 'webgl2') {
              host.replace('glfw-destroy-window');
            }
            """)
    private static native void releaseWindowCanvas();

    @JSBody(params = {"title"}, script = "document.title=title;")
    private static native void setTitle(String title);

    @JSBody(params = {"width", "height"}, script = """
            const canvasElement=document.getElementById('mc-canvas'); if (!canvasElement) return;
            if (window.__gaiusApplyCanvasResolution) {
              window.__gaiusApplyCanvasResolution(width, height, false);
            } else {
              const quality=window.GaiusQuality?window.GaiusQuality.runtime:null;
              const pixelRatio=quality&&typeof quality.resolvePixelRatio==='function'
                ?(Number(quality.resolvePixelRatio(devicePixelRatio||1))||1)
                :Math.min(devicePixelRatio||1,1);
              canvasElement.style.width=width+'px'; canvasElement.style.height=height+'px';
              canvasElement.width=Math.max(1,Math.round(width*pixelRatio));
              canvasElement.height=Math.max(1,Math.round(height*pixelRatio));
            }
            """)
    private static native void resizeCanvas(int width, int height);

    @JSBody(script = "const canvasElement=document.getElementById('mc-canvas'); return canvasElement?Math.round(canvasElement.getBoundingClientRect().width):innerWidth;")
    private static native int canvasCssWidth();

    @JSBody(script = "const canvasElement=document.getElementById('mc-canvas'); return canvasElement?Math.round(canvasElement.getBoundingClientRect().height):innerHeight;")
    private static native int canvasCssHeight();

    @JSBody(script = "const canvasElement=document.getElementById('mc-canvas'); return canvasElement?canvasElement.width:innerWidth;")
    private static native int framebufferWidth();

    @JSBody(script = "const canvasElement=document.getElementById('mc-canvas'); return canvasElement?canvasElement.height:innerHeight;")
    private static native int framebufferHeight();

    @JSBody(script = "return screen.width|0;")
    private static native int screenWidth();

    @JSBody(script = "return screen.height|0;")
    private static native int screenHeight();

    @JSBody(script = "return window.__gaiusResolvePixelRatio?window.__gaiusResolvePixelRatio():(devicePixelRatio||1);")
    private static native double devicePixelRatio();

    @JSBody(script = "return Math.max(0,((window.__gaiusGlfwEvents||[]).length|0)-((window.__gaiusGlfwEventHead||0)|0))|0;")
    private static native int eventCount();

    @JSBody(script = """
            if (window.__gaiusInputWarmupDone) return;
            const minecraftState = window.__gaiusMinecraftState || null;
            if (!minecraftState || !minecraftState.screen || minecraftState.level) return;
            const events = window.__gaiusGlfwEvents || (window.__gaiusGlfwEvents = []);
            const head = (window.__gaiusGlfwEventHead || 0) | 0;
            if (events.length - head > 0) return;
            window.__gaiusInputWarmupDone = true;
            window.__gaiusCursorX = 1;
            window.__gaiusCursorY = 1;
            events.push([4,0,0,0,0,1,1]);
            events.push([3,0,1,0,0,0,0]);
            events.push([3,0,0,0,0,0,0]);
            """)
    private static native void maybeQueueInputWarmup();

    @JSBody(params = {"index"}, script = "return window.__gaiusGlfwEvents[window.__gaiusGlfwEventHead||0][index]|0;")
    private static native int eventInt(int index);

    @JSBody(params = {"index"}, script = "return +window.__gaiusGlfwEvents[window.__gaiusGlfwEventHead||0][index];")
    private static native double eventDouble(int index);

    @JSBody(params = {"name", "installed"}, script = """
            const stats = window.__gaiusInputStats || (window.__gaiusInputStats = {
              callbacks: {},
              events: {},
              callbackMisses: {},
              totalEvents: 0,
              lastEvent: null
            });
            stats.callbacks[name] = !!installed;
            stats.lastCallback = {name: name, installed: !!installed, at: Date.now()};
            """)
    private static native void reportCallback(String name, boolean installed);

    @JSBody(params = {"type", "a", "b", "c", "x", "y", "callbackInstalled"}, script = """
            const stats = window.__gaiusInputStats || (window.__gaiusInputStats = {
              callbacks: {},
              events: {},
              callbackMisses: {},
              totalEvents: 0,
              lastEvent: null
            });
            const key = String(type | 0);
            stats.totalEvents = (stats.totalEvents || 0) + 1;
            stats.events[key] = (stats.events[key] || 0) + 1;
            if (!callbackInstalled) stats.callbackMisses[key] = (stats.callbackMisses[key] || 0) + 1;
            stats.lastEvent = {
              type: type | 0,
              a: a | 0,
              b: b | 0,
              c: c | 0,
              x: Number(x),
              y: Number(y),
              callbackInstalled: !!callbackInstalled,
              at: Date.now()
            };
            """)
    private static native void reportInputEvent(
            int type, int a, int b, int c, double x, double y, boolean callbackInstalled);

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

    @JSBody(script = """
            const events=window.__gaiusGlfwEvents || [];
            const head=((window.__gaiusGlfwEventHead || 0) + 1)|0;
            window.__gaiusGlfwEventHead=head;
            if ((window.__gaiusGlfwPendingMouseMove|0) < head) {
              window.__gaiusGlfwPendingMouseMove=-1;
            }
            if (head > 128 && head * 2 > events.length) {
              events.splice(0,head);
              window.__gaiusGlfwEventHead=0;
              if ((window.__gaiusGlfwPendingMouseMove|0) >= head) {
                window.__gaiusGlfwPendingMouseMove=(window.__gaiusGlfwPendingMouseMove|0)-head;
              }
            }
            """)
    private static native void removeEvent();

    @JSBody(params = {"mode"}, script = "return (window.__gaiusGlfwInputModes[mode]||0)|0;")
    private static native int inputMode(int mode);

    @JSBody(params = {"mode", "value"}, script = """
            window.__gaiusGlfwInputModes[mode]=value;
            if (mode===0x00033001) {
              const canvasElement=document.getElementById('mc-canvas');
              if (value===0x00034003) {
                window.__gaiusWantPointerLock = true;
              } else {
                // Cleared even without the Pointer Lock API: the touch controls read this
                // flag to tell gameplay from GUI screens.
                window.__gaiusWantPointerLock = false;
              }
              if (value!==0x00034003 && document.exitPointerLock) {
                try {
                  const exitResult = document.exitPointerLock();
                  if (exitResult && exitResult.catch) exitResult.catch(() => {});
                } catch (error) {
                  // Ignore pointer-lock exit failures; GLFW treats this as best-effort cursor state.
                }
              }
            }
            """)
    private static native void setInputModeJs(int mode, int value);

    @JSBody(params = {"key"}, script = "return !!window.__gaiusGlfwKeys[key];")
    private static native boolean keyDown(int key);

    @JSBody(params = {"button"}, script = "return !!window.__gaiusGlfwButtons[button];")
    private static native boolean mouseDown(int button);

    @JSBody(params = {"value"}, script = """
            const bridge = window.__gaiusClipboardBridge;
            if (bridge) {
              bridge.write(value);
              return;
            }
            window.__gaiusClipboard = value;
            if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(value).catch(() => {});
            """)
    private static native void writeClipboard(String value);

    @JSBody(script = "return window.__gaiusClipboard||'';")
    private static native String readClipboard();

    @JSBody(script = "return performance.now()/1000;")
    private static native double now();
}
