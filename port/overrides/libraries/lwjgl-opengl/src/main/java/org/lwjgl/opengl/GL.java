/*
 * Copyright (c) LWJGL
 * SPDX-License-Identifier: BSD-3-Clause
 *
 * Derived from the LWJGL GL API.
 * Modified for Gaius: create capabilities through the browser renderer.
 */

package org.lwjgl.opengl;

import org.lwjgl.system.FunctionProvider;

/** Browser replacement for LWJGL's native OpenGL loader. */
public final class GL {
    private static GLCapabilities capabilities;

    private GL() {
    }

    static void initialize() {
    }

    public static void create() {
        BrowserOpenGL.initialize();
    }

    public static void create(String libraryName) {
        create();
    }

    public static void create(FunctionProvider provider) {
        create();
    }

    public static void destroy() {
        capabilities = null;
    }

    public static void setCapabilities(GLCapabilities value) {
        capabilities = value;
    }

    public static GLCapabilities getCapabilities() {
        if (capabilities == null) {
            capabilities = createCapabilities();
        }
        return capabilities;
    }

    public static FunctionProvider getFunctionProvider() {
        return null;
    }

    public static GLCapabilities createCapabilities() {
        BrowserOpenGL.initialize();
        capabilities = new GLCapabilities();
        return capabilities;
    }

    public static GLCapabilities createCapabilities(boolean forwardCompatible) {
        return createCapabilities();
    }

    static GLCapabilities getICD() {
        return getCapabilities();
    }
}
