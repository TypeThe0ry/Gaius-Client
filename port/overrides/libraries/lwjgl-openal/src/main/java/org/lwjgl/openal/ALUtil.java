/*
 * Copyright (c) LWJGL
 * SPDX-License-Identifier: BSD-3-Clause
 *
 * Derived from the LWJGL ALUtil API.
 * Modified for Gaius: provide the browser-compatible string-list surface.
 */

package org.lwjgl.openal;

import java.util.Collections;
import java.util.List;

public final class ALUtil {
    private ALUtil() {
    }

    public static List<String> getStringList(long device, int parameter) {
        return Collections.emptyList();
    }
}
