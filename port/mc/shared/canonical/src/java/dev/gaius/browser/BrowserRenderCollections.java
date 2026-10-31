package dev.gaius.browser;

import it.unimi.dsi.fastutil.objects.ObjectCollection;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.feature.phase.FeatureRenderPhase;

/** Small allocation-free collection loops used by the browser render frame. */
public final class BrowserRenderCollections {
    private BrowserRenderCollections() {
    }

    /** Keeps the vanilla remove-empty/clear-non-empty behavior without a predicate object. */
    public static void clearGroups(Collection<? extends List<?>> groups) {
        Iterator<? extends List<?>> iterator = groups.iterator();
        while (iterator.hasNext()) {
            List<?> group = iterator.next();
            if (group.isEmpty()) {
                iterator.remove();
            } else {
                group.clear();
            }
        }
    }

    /** Drains non-empty phases in storage order and removes empty submit collections. */
    public static void drainSubmitNodeCollections(
            ObjectCollection<SubmitNodeCollection> collections,
            Consumer<FeatureRenderPhase<?>> consumer) {
        Iterator<SubmitNodeCollection> iterator = collections.iterator();
        while (iterator.hasNext()) {
            SubmitNodeCollection collection = iterator.next();
            boolean empty = true;
            for (FeatureRenderPhase<?> phase : collection.allPhases()) {
                if (!phase.isEmpty()) {
                    consumer.accept(phase);
                    empty = false;
                }
            }
            if (empty) {
                iterator.remove();
            }
        }
    }
}
