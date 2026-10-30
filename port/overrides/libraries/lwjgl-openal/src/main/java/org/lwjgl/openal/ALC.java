/*
 * Copyright (c) LWJGL
 * SPDX-License-Identifier: BSD-3-Clause
 *
 * Derived from the LWJGL ALC API.
 * Modified for Gaius: provide browser-managed OpenAL context capabilities.
 */

package org.lwjgl.openal;

import java.util.function.IntFunction;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.FunctionProviderLocal;

public final class ALC {
    private static FunctionProviderLocal functionProvider = BrowserOpenAL.FUNCTION_PROVIDER;
    private static ALCCapabilities router;
    private static final ThreadLocal<ALCCapabilities> capabilitiesTLS = new ThreadLocal<>();

    private ALC() {
    }

    public static void create() {
        create(BrowserOpenAL.FUNCTION_PROVIDER);
    }

    public static void create(String libName) {
        create(BrowserOpenAL.FUNCTION_PROVIDER);
    }

    public static void create(FunctionProviderLocal provider) {
        functionProvider = provider == null ? BrowserOpenAL.FUNCTION_PROVIDER : provider;
        router = createCapabilities(0L);
        AL.init();
    }

    public static void destroy() {
        AL.destroy();
        capabilitiesTLS.remove();
        router = null;
        functionProvider = BrowserOpenAL.FUNCTION_PROVIDER;
    }

    static <T> T check(T value) {
        if (value == null) {
            throw new IllegalStateException("OpenAL browser stub is not available.");
        }
        return value;
    }

    public static FunctionProviderLocal getFunctionProvider() {
        return functionProvider == null ? BrowserOpenAL.FUNCTION_PROVIDER : functionProvider;
    }

    public static void setCapabilities(ALCCapabilities capabilities) {
        capabilitiesTLS.set(capabilities);
        if (capabilities != null) {
            router = capabilities;
        }
    }

    public static ALCCapabilities getCapabilities() {
        ALCCapabilities capabilities = capabilitiesTLS.get();
        if (capabilities != null) {
            return capabilities;
        }
        if (router == null) {
            router = createCapabilities(0L);
        }
        return router;
    }

    public static ALCCapabilities createCapabilities(long device) {
        return createCapabilities(device, BrowserOpenAL.POINTER_BUFFER_FACTORY);
    }

    public static ALCCapabilities createCapabilities(
            long device,
            IntFunction<PointerBuffer> bufferFactory) {
        IntFunction<PointerBuffer> factory =
                bufferFactory == null ? BrowserOpenAL.POINTER_BUFFER_FACTORY : bufferFactory;
        return new ALCCapabilities(
                getFunctionProvider(),
                device,
                BrowserOpenAL.ALC_EXTENSIONS,
                factory);
    }

    static ALCCapabilities getICD() {
        return getCapabilities();
    }
}
