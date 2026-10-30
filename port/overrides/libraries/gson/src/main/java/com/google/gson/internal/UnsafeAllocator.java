/*
 * Copyright (C) 2012 Google Inc.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Derived from Gson's UnsafeAllocator.
 * Modified for Gaius: instantiate through a no-argument constructor in the browser.
 */

package com.google.gson.internal;

import java.lang.reflect.Constructor;

/**
 * Browser-safe UnsafeAllocator. The JDK ObjectStreamClass/ObjectInputStream
 * paths do not exist in TeaVM, so instances are created through a no-argument
 * constructor when one exists. Classes without one fail fast with a clear
 * message instead of linking against missing JDK types.
 */
public abstract class UnsafeAllocator {
    public static final UnsafeAllocator INSTANCE = create();

    public UnsafeAllocator() {
    }

    public abstract <T> T newInstance(Class<T> c) throws Exception;

    private static void assertInstantiable(Class<?> c) {
        int modifiers = c.getModifiers();
        if (java.lang.reflect.Modifier.isInterface(modifiers)) {
            throw new UnsupportedOperationException("Interface can't be instantiated: " + c);
        }
        if (java.lang.reflect.Modifier.isAbstract(modifiers)) {
            throw new UnsupportedOperationException("Abstract class can't be instantiated: " + c);
        }
    }

    private static UnsafeAllocator create() {
        return new UnsafeAllocator() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> T newInstance(Class<T> c) throws Exception {
                assertInstantiable(c);
                Constructor<T> constructor = c.getDeclaredConstructor();
                constructor.setAccessible(true);
                return constructor.newInstance();
            }
        };
    }

    static void access$000(Class<?> c) {
        assertInstantiable(c);
    }
}
