package dev.gaius.classlib.repro;

import org.teavm.classlib.ResourceSupplier;
import org.teavm.classlib.ResourceSupplierContext;

/** Puts the repro resource on TeaVM's class path (META-INF/services); unused on the JVM. */
public class ResSupplier implements ResourceSupplier {
    @Override
    public String[] supplyResources(ResourceSupplierContext context) {
        return new String[] {ResTest.RESOURCE};
    }
}
