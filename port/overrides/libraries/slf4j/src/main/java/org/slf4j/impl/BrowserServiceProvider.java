package org.slf4j.impl;

import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.helpers.NOPMDCAdapter;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

/**
 * SLF4J service provider for the browser runtime. The browser LoggerFactory
 * instantiates this provider directly, so the provider entry is optional; it is
 * kept for any code that discovers providers through the SPI.
 */
public class BrowserServiceProvider implements SLF4JServiceProvider {
    private final BasicMarkerFactory markerFactory = new BasicMarkerFactory();
    private final NOPMDCAdapter mdcAdapter = new NOPMDCAdapter();

    public BrowserServiceProvider() {
    }

    public ILoggerFactory getLoggerFactory() {
        return BrowserLoggerFactory.INSTANCE;
    }

    public IMarkerFactory getMarkerFactory() {
        return markerFactory;
    }

    public MDCAdapter getMDCAdapter() {
        return mdcAdapter;
    }

    public String getRequestedApiVersion() {
        return "2.0.17";
    }

    public void initialize() {
        // Nothing to initialize.
    }
}
