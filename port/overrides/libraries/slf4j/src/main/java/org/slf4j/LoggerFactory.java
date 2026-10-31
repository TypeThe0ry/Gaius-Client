package org.slf4j;

import org.slf4j.impl.BrowserLoggerFactory;
import org.slf4j.impl.BrowserServiceProvider;
import org.slf4j.spi.SLF4JServiceProvider;

/**
 * Browser-safe SLF4J entry point. Resolves loggers from the Gaius console
 * factory directly: no ServiceLoader, no binder discovery, no class-context
 * walking, no resource enumeration.
 */
public final class LoggerFactory {
    private LoggerFactory() {
    }

    public static Logger getLogger(String name) {
        return BrowserLoggerFactory.INSTANCE.getLogger(name);
    }

    public static Logger getLogger(Class<?> clazz) {
        return getLogger(clazz.getName());
    }

    public static ILoggerFactory getILoggerFactory() {
        return BrowserLoggerFactory.INSTANCE;
    }

    public static SLF4JServiceProvider getProvider() {
        return new BrowserServiceProvider();
    }
}
