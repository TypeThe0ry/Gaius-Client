/*
 * Copyright (c) QOS.ch Sarl (Switzerland)
 * SPDX-License-Identifier: MIT
 *
 * Derived from SLF4J's Util helper.
 * Modified for Gaius: omit class-context lookup in the browser.
 */

package org.slf4j.helpers;

/**
 * Browser-safe slf4j helpers: no SecurityManager class-context walking.
 */
public final class Util {
    private Util() {
    }

    public static String safeGetSystemProperty(String key) {
        if (key == null) {
            throw new IllegalArgumentException("null input");
        }
        String result = null;
        try {
            result = System.getProperty(key);
        } catch (SecurityException ignored) {
            // ignore
        }
        return result;
    }

    public static boolean safeGetBooleanSystemProperty(String key) {
        String value = safeGetSystemProperty(key);
        if (value == null) {
            return false;
        }
        return value.equalsIgnoreCase("true");
    }

    public static Class<?> getCallingClass() {
        return null;
    }

    public static void report(String msg, Throwable t) {
        System.err.println(msg);
        if (t != null) {
            t.printStackTrace();
        }
    }

    public static void report(String msg) {
        System.err.println("SLF4J: " + msg);
    }
}
