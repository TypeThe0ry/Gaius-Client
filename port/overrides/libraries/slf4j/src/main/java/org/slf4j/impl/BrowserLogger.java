package org.slf4j.impl;

import org.slf4j.helpers.MarkerIgnoringBase;
import org.teavm.jso.JSBody;

/** Console logger for the browser runtime. */
final class BrowserLogger extends MarkerIgnoringBase {
    private static final long serialVersionUID = 1L;

    private static final int TRACE_INT = 0;
    private static final int DEBUG_INT = 10;
    private static final int INFO_INT = 20;
    private static final int WARN_INT = 30;
    private static final int ERROR_INT = 40;

    /**
     * Whether debug and trace messages reach the console: 1 yes, -1 no, 0 not read yet. Vanilla
     * logs at those levels from per-tick code (entity, chunk and network paths); formatting a
     * message nobody reads cost tick time, so they stay off unless the page or Worker opts in
     * ({@link #verboseOptIn}). The realm of a running program never changes its opt-in, so it is
     * read once.
     */
    private static int verboseState;

    BrowserLogger(String name) {
        this.name = name;
    }

    private static boolean verbose() {
        int state = verboseState;
        if (state == 0) {
            state = verboseOptIn() ? 1 : -1;
            verboseState = state;
        }
        return state > 0;
    }

    /**
     * Vanilla reports its debug wireframe pipelines (minecraft:pipeline/wireframe and
     * wireframe_multidraw) at error level because the device has no WIREFRAME fill mode. WebGPU
     * has no line polygon mode (the plan makes WIREFRAME a no-op), so this is a known, harmless
     * downgrade at every start and not a failure. The marker is the tail of vanilla's message.
     */
    private static final String WIREFRAME_UNSUPPORTED = "WIREFRAME fill mode, not supported by device";

    /** Wireframe notices seen this session; the first is logged at info level, all are counted. */
    private static int wireframeNotices;

    private void log(int level, String levelName, String format, Object... args) {
        if (isWireframeNotice(level, format)) {
            noteWireframeUnsupported(format, args);
            return;
        }
        logToConsole(level, "[" + levelName + "] " + name + ": " + format(format, args));
    }

    /** Only vanilla's wireframe notice at error level; every other error stays an error. */
    private static boolean isWireframeNotice(int level, String format) {
        return level == ERROR_INT && format != null && format.contains(WIREFRAME_UNSUPPORTED);
    }

    private void noteWireframeUnsupported(String format, Object... args) {
        wireframeNotices++;
        countWireframeNotice(wireframeNotices);
        if (wireframeNotices == 1) {
            logToConsole(INFO_INT, "[INFO] " + name + ": " + format(format, args)
                    + " (known; WIREFRAME is a no-op on WebGPU, further occurrences are only counted in"
                    + " globalThis.__gaiusLogDowngrades.wireframe)");
        }
    }

    @JSBody(params = { "count" }, script = "\n"
            + "try {\n"
            + "    var counters = globalThis.__gaiusLogDowngrades || (globalThis.__gaiusLogDowngrades = {});\n"
            + "    counters.wireframe = count;\n"
            + "} catch (e) {\n"
            + "}\n")
    private static native void countWireframeNotice(int count);

    private static String format(String format, Object... args) {
        if (args == null || args.length == 0) {
            return format;
        }
        int argumentLimit = args.length;
        Throwable trailingThrowable = args[args.length - 1] instanceof Throwable
                ? (Throwable) args[args.length - 1] : null;
        if (trailingThrowable != null) {
            argumentLimit--;
        }
        StringBuilder sb = new StringBuilder();
        int argIndex = 0;
        int i = 0;
        while (i < format.length()) {
            char c = format.charAt(i);
            if (c == '{' && i + 1 < format.length() && format.charAt(i + 1) == '}') {
                sb.append(argIndex < argumentLimit ? String.valueOf(args[argIndex]) : "{}");
                argIndex++;
                i += 2;
            } else {
                sb.append(c);
                i++;
            }
        }
        if (trailingThrowable != null) {
            sb.append("\n").append(formatThrowable(trailingThrowable));
        }
        return sb.toString();
    }

    /** Keeps the browser console payload String-only while retaining the useful Throwable cause. */
    private static String formatThrowable(Throwable throwable) {
        StringBuilder sb = new StringBuilder();
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < 4 && sb.length() < 4096; depth++) {
            if (depth > 0) {
                sb.append("\nCaused by: ");
            }
            sb.append(current.getClass().getName());
            String message = current.getMessage();
            if (message != null && !message.isEmpty()) {
                sb.append(": ").append(message);
            }
            StackTraceElement[] stack = current.getStackTrace();
            for (int index = 0; index < stack.length && sb.length() < 4096 && index < 32; index++) {
                sb.append("\n\tat ").append(stack[index]);
            }
            current = current.getCause();
        }
        if (sb.length() > 4096) {
            sb.setLength(4096);
        }
        return sb.toString();
    }

    @JSBody(params = { "level", "text" }, script = "\n"
            + "if (typeof console !== 'object' || console === null) {\n"
            + "    return;\n"
            + "}\n"
            + "if (level >= 40 && typeof console.error === 'function') {\n"
            + "    console.error(text);\n"
            + "} else if (level >= 30 && typeof console.warn === 'function') {\n"
            + "    console.warn(text);\n"
            + "} else if (level >= 20 && typeof console.info === 'function') {\n"
            + "    console.info(text);\n"
            + "} else if (typeof console.log === 'function') {\n"
            + "    console.log(text);\n"
            + "}\n")
    private static native void logToConsole(int level, String text);

    /**
     * Debug and trace output: {@code globalThis.__gaiusDebugLogging = true} before the program
     * starts, or {@code gaiusDebugLog=1} in the URL of the page (or of the Worker script).
     */
    @JSBody(script = "\n"
            + "try {\n"
            + "    if (globalThis.__gaiusDebugLogging === true) {\n"
            + "        return true;\n"
            + "    }\n"
            + "    var search = globalThis.location ? String(globalThis.location.search || '') : '';\n"
            + "    return /[?&]gaiusDebugLog=1(&|$)/.test(search);\n"
            + "} catch (e) {\n"
            + "    return false;\n"
            + "}\n")
    private static native boolean verboseOptIn();

    // Disabled levels return before formatting or crossing into JavaScript (see verboseState).
    public boolean isTraceEnabled() { return verbose(); }
    public void trace(String msg) { if (verbose()) log(TRACE_INT, "TRACE", msg); }
    public void trace(String format, Object arg) { if (verbose()) log(TRACE_INT, "TRACE", format, arg); }
    public void trace(String format, Object arg1, Object arg2) { if (verbose()) log(TRACE_INT, "TRACE", format, arg1, arg2); }
    public void trace(String format, Object... arguments) { if (verbose()) log(TRACE_INT, "TRACE", format, arguments); }
    public void trace(String msg, Throwable t) { if (verbose()) log(TRACE_INT, "TRACE", msg, t); }

    public boolean isDebugEnabled() { return verbose(); }
    public void debug(String msg) { if (verbose()) log(DEBUG_INT, "DEBUG", msg); }
    public void debug(String format, Object arg) { if (verbose()) log(DEBUG_INT, "DEBUG", format, arg); }
    public void debug(String format, Object arg1, Object arg2) { if (verbose()) log(DEBUG_INT, "DEBUG", format, arg1, arg2); }
    public void debug(String format, Object... arguments) { if (verbose()) log(DEBUG_INT, "DEBUG", format, arguments); }
    public void debug(String msg, Throwable t) { if (verbose()) log(DEBUG_INT, "DEBUG", msg, t); }

    public boolean isInfoEnabled() { return true; }
    public void info(String msg) { log(INFO_INT, "INFO", msg); }
    public void info(String format, Object arg) { log(INFO_INT, "INFO", format, arg); }
    public void info(String format, Object arg1, Object arg2) { log(INFO_INT, "INFO", format, arg1, arg2); }
    public void info(String format, Object... arguments) { log(INFO_INT, "INFO", format, arguments); }
    public void info(String msg, Throwable t) { log(INFO_INT, "INFO", msg, t); }

    public boolean isWarnEnabled() { return true; }
    public void warn(String msg) { log(WARN_INT, "WARN", msg); }
    public void warn(String format, Object arg) { log(WARN_INT, "WARN", format, arg); }
    public void warn(String format, Object arg1, Object arg2) { log(WARN_INT, "WARN", format, arg1, arg2); }
    public void warn(String format, Object... arguments) { log(WARN_INT, "WARN", format, arguments); }
    public void warn(String msg, Throwable t) { log(WARN_INT, "WARN", msg, t); }

    public boolean isErrorEnabled() { return true; }
    public void error(String msg) { log(ERROR_INT, "ERROR", msg); }
    public void error(String format, Object arg) { log(ERROR_INT, "ERROR", format, arg); }
    public void error(String format, Object arg1, Object arg2) { log(ERROR_INT, "ERROR", format, arg1, arg2); }
    public void error(String format, Object... arguments) { log(ERROR_INT, "ERROR", format, arguments); }
    public void error(String msg, Throwable t) { log(ERROR_INT, "ERROR", msg + " - " + t); }
}
