package org.slf4j.impl;

import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;

/** Gaius browser logging factory: one console logger per name. */
public final class BrowserLoggerFactory implements ILoggerFactory {
    public static final BrowserLoggerFactory INSTANCE = new BrowserLoggerFactory();

    private BrowserLoggerFactory() {
    }

    public Logger getLogger(String name) {
        return new BrowserLogger(name);
    }
}
