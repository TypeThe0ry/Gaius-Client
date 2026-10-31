package org.teavm.classlib.java.lang;

import com.ibm.icu.lang.UCharacter;
import dev.gaius.browser.HostLoop;
import dev.gaius.browser.TargetMath;
import java.time.Duration;
import org.teavm.classlib.java.util.TCollections;
import org.teavm.classlib.java.util.TMap;
import org.teavm.classlib.java.lang.reflect.TType;
import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;

public final class TModernRuntimeSupport {
    private TModernRuntimeSupport() {
    }

    public static String characterToString(int codePoint) {
        return new String(TCharacter.toChars(codePoint));
    }

    public static int codePointOf(String name) {
        int result = UCharacter.getCharFromName(name);
        if (result < 0) {
            throw new IllegalArgumentException("Unrecognized Unicode character name: " + name);
        }
        return result;
    }

    public static int parseUnsignedInt(String value, int radix) {
        long result = TLong.parseLong(value, radix);
        if (result < 0 || result > 0xffff_ffffL) {
            throw new NumberFormatException("Unsigned integer out of range: " + value);
        }
        return (int) result;
    }

    public static long parseUnsignedLong(String value, int radix) {
        if (value == null || value.isEmpty()) {
            throw new NumberFormatException("empty String");
        }
        if (radix < Character.MIN_RADIX || radix > Character.MAX_RADIX) {
            throw new NumberFormatException("radix " + radix + " out of range");
        }
        long result = 0;
        long maxQuotient = TLong.divideUnsigned(-1L, radix);
        int maxRemainder = (int) TLong.remainderUnsigned(-1L, radix);
        for (int index = 0; index < value.length(); index++) {
            int digit = TCharacter.digit(value.charAt(index), radix);
            if (digit < 0) {
                throw new NumberFormatException("Invalid digit in " + value);
            }
            int comparison = TLong.compareUnsigned(result, maxQuotient);
            if (comparison > 0 || comparison == 0 && digit > maxRemainder) {
                throw new NumberFormatException("Unsigned long out of range: " + value);
            }
            result = result * radix + digit;
        }
        return result;
    }

    // One double multiply and add, then rounding to float. The rounding needs Math.fround in the
    // JavaScript build (TeaVM emits none for a double-to-float cast there) and is the cast itself
    // in the WebAssembly GC build, whose class library may not hold JSBody natives: TargetMath
    // has one implementation per backend.
    public static float fma(float left, float right, float addend) {
        return TargetMath.fma(left, right, addend);
    }

    // Same arithmetic as the former JavaScript body (left * right + addend in doubles).
    public static double fma(double left, double right, double addend) {
        return left * right + addend;
    }

    public static long maxMemory(TRuntime runtime) {
        // Conservative heap budget used by vanilla render-buffer sizing.
        return 1024L * 1024L * 1024L;
    }

    public static TMap<String, String> getenv() {
        return TCollections.emptyMap();
    }

    public static boolean isAnonymousClass(TClass<?> type) {
        return type.getEnclosingClass() != null && type.getSimpleName().isEmpty();
    }

    public static long threadId(TThread thread) {
        return thread.getId();
    }

    public static TThread$State threadState(TThread thread) {
        return thread.isAlive() ? TThread$State.RUNNABLE : TThread$State.TERMINATED;
    }

    public static void sleep(Duration duration) throws TInterruptedException {
        TThread.sleep(duration.toMillis());
    }

    /**
     * Suspends only the current TeaVM continuation without installing a Thread interrupt handler.
     * Browser world generation can have several continuations sharing one emulated Java thread;
     * using TThread.sleep there lets an unrelated wake-up cancel the wrong continuation.
     * Returns at once while a class initializer or a method that the role options compiled
     * synchronously runs (see {@link #inClassInitializer}), and when called from a plain
     * JavaScript callback that has no TeaVM thread to suspend: the yield is optional, so
     * skipping it there is correct where suspending would throw.
     */
    public static void yieldToEventLoop(int delayMillis) {
        if (HostLoop.jspi()) {
            // JSPI parks the thread's whole stack; the @Async path below is constant-folded
            // away, so no method becomes a coroutine.
            HostLoop.yieldToEventLoop(delayMillis);
            return;
        }
        if (inClassInitializer() || !HostLoop.canSuspend()) {
            return;
        }
        try {
            suspendToEventLoop(delayMillis);
        } catch (RuntimeException e) {
            String message = e.getMessage();
            if (message == null || !message.contains("Suspension point reached from non-threading context")) {
                throw e;
            }
        }
    }

    /**
     * True while the current frame must not suspend: a TeaVM class initializer, or a method
     * that the role options compiled synchronously, is on the stack. Both roles are compiled
     * with class initialization edges that do not make their callers TeaVM-async
     * (gaius.teavm.syncClinits, TeaVMCoreBrowserPatcher), so a static initializer can run
     * under a synchronous caller; the patched TeaVM counts those frames in
     * globalThis.__gaiusClinitDepth. Methods cut by gaius.teavm.asyncBarrier or
     * gaius.teavm.syncMonitors keep globalThis.__gaiusNoSuspendDepth positive, and a
     * suspension reached there throws. Cooperative yields and LockSupport parking are
     * optional, so their callers skip them while either counter is positive instead of
     * suspending. Builds without those options never set the counters.
     */
    public static boolean inClassInitializer() {
        return HostLoop.inNoSuspendFrame();
    }

    @Async
    private static native void suspendToEventLoop(int delayMillis);

    private static void suspendToEventLoop(int delayMillis, AsyncCallback<Void> callback) {
        TThread thread = TThread.currentThread();
        Runnable resume = () -> {
            TThread.setCurrentThread(thread);
            callback.complete(null);
        };
        if (delayMillis > 0) {
            HostLoop.schedule(resume, delayMillis);
        } else {
            HostLoop.postMacrotask(resume);
        }
    }

    public static void postRunnableMacrotask(Runnable callback) {
        HostLoop.postMacrotask(callback);
    }

    public static TType genericSuperclass(TClass<?> type) {
        return type.getSuperclass();
    }

    public static TType[] genericInterfaces(TClass<?> type) {
        TClass<?>[] interfaces = type.getInterfaces();
        TType[] result = new TType[interfaces.length];
        System.arraycopy(interfaces, 0, result, 0, interfaces.length);
        return result;
    }
}
