package dev.gaius.classlib.repro;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;

/**
 * new WeakReference<>(null) as vanilla's RecipeCache does it. TeaVM 0.15's WebAssembly GC
 * runtime calls new WeakRef(null), which throws "WeakRef: invalid target";
 * port/web/runtime/wasmgc/patch-wasm-gc-runtime.mjs returns a sentinel whose deref() is
 * undefined instead.
 */
public final class WeakRefNull {
    private WeakRefNull() {
    }

    public static void main(String[] args) {
        WeakReference<Object> empty = new WeakReference<>(null);
        System.out.println("weak(null).get()=" + empty.get());
        WeakReference<Object> queued = new WeakReference<>(null, new ReferenceQueue<>());
        System.out.println("weak(null, queue).get()=" + queued.get() + " enqueued=" + queued.isEnqueued());
        Object referent = new StringBuilder("kept");
        WeakReference<Object> strong = new WeakReference<>(referent);
        System.out.println("weak(live).get()=" + strong.get());
        strong.clear();
        System.out.println("weak(live).clear().get()=" + strong.get());
        SoftReference<Object> soft = new SoftReference<>(null);
        System.out.println("soft(null).get()=" + soft.get());
        System.out.println("kept=" + referent);
    }
}
