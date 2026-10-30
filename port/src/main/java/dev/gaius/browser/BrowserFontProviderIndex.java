package dev.gaius.browser;

import com.mojang.blaze3d.font.GlyphProvider;
import net.minecraft.client.gui.font.providers.BitmapProvider;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntIterator;
import java.util.AbstractList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/** Invocation-local index of immutable bitmap font support, preserving provider precedence. */
public final class BrowserFontProviderIndex {
    private BrowserFontProviderIndex() {}

    public static List<GlyphProvider> wrap(List<GlyphProvider> providers) {
        if (providers.size() < 8) return providers;
        return new IndexedProviders(providers);
    }

    public static Iterator<GlyphProvider> iterator(List<GlyphProvider> providers, int codepoint) {
        return providers instanceof IndexedProviders indexed
                ? indexed.candidates(codepoint) : providers.iterator();
    }

    private static final class IndexedProviders extends AbstractList<GlyphProvider> {
        private final List<GlyphProvider> providers;
        private final Int2ObjectOpenHashMap<IntArrayList> byCodepoint = new Int2ObjectOpenHashMap<>();
        private final IntArrayList fallback = new IntArrayList();

        IndexedProviders(List<GlyphProvider> providers) {
            this.providers = providers;
            for (int ordinal = 0; ordinal < providers.size(); ordinal++) {
                GlyphProvider provider = providers.get(ordinal);
                // Exact class check excludes subclasses with different/dynamic getGlyph semantics.
                // Both supported releases implement BitmapProvider.getGlyph with the very same
                // immutable CodepointMap whose keySet supplies getSupportedGlyphs.
                if (provider.getClass() != BitmapProvider.class) {
                    fallback.add(ordinal);
                    continue;
                }
                IntIterator supported = provider.getSupportedGlyphs().iterator();
                while (supported.hasNext()) {
                    int codepoint = supported.nextInt();
                    IntArrayList ordinals = byCodepoint.get(codepoint);
                    if (ordinals == null) {
                        ordinals = new IntArrayList(1);
                        byCodepoint.put(codepoint, ordinals);
                    }
                    ordinals.add(ordinal);
                }
            }
        }

        @Override public GlyphProvider get(int index) { return providers.get(index); }
        @Override public int size() { return providers.size(); }

        Iterator<GlyphProvider> candidates(int codepoint) {
            IntArrayList known = byCodepoint.get(codepoint);
            return new Iterator<>() {
                int knownIndex;
                int fallbackIndex;
                @Override public boolean hasNext() {
                    return (known != null && knownIndex < known.size())
                            || fallbackIndex < fallback.size();
                }
                @Override public GlyphProvider next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    int ordinal;
                    if (known != null && knownIndex < known.size()
                            && (fallbackIndex >= fallback.size()
                            || known.getInt(knownIndex) < fallback.getInt(fallbackIndex))) {
                        ordinal = known.getInt(knownIndex++);
                    } else {
                        ordinal = fallback.getInt(fallbackIndex++);
                    }
                    return providers.get(ordinal);
                }
            };
        }
    }
}
