package dev.gaius.browser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.CrashReportDetail;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Browser-only hook for GUI item render states.
 *
 * <p>Modern creative inventory rendering resolves many item models every frame.
 * In the browser that dominates the inventory screen. The resolved
 * {@link TrackingItemStackRenderState} is mutable and is retained by
 * {@code GuiItemRenderState} until the GUI batch is drawn, so reusing one state
 * across multiple slots in the same frame can make item icons disappear even
 * though hover data and tooltips still work. Use a frame-reset object pool
 * instead: every item rendered in one GUI build still gets a distinct mutable
 * state, while the next GUI build reuses the already allocated objects. The
 * vanilla item atlas cache also stores {@code getModelIdentity()} as a key
 * across frames. {@link TrackingItemStackRenderState} exposes its mutable
 * identity list directly, so pooled browser states must return a stable copy as
 * the cache key; otherwise clearing a pooled state mutates keys that are already
 * inside {@code GuiRenderer.atlasPositions}, causing repeated atlas redraws and
 * broken item icons.</p>
 */
public final class BrowserGuiItemCache {
    private static final int STATE_POOL_SIZE = 1024;
    private static final int MODEL_IDENTITY_CACHE_SIZE = 4096;
    private static final BrowserTrackingItemStackRenderState[] STATE_POOL =
            new BrowserTrackingItemStackRenderState[STATE_POOL_SIZE];
    private static final Map<Object, Object> SINGLE_MODEL_IDENTITIES =
            new LinkedHashMap<>(MODEL_IDENTITY_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Object, Object> eldest) {
                    return size() > MODEL_IDENTITY_CACHE_SIZE;
                }
            };
    private static final Map<Integer, List<ModelIdentityEntry>> MULTI_MODEL_IDENTITIES =
            new LinkedHashMap<>(MODEL_IDENTITY_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, List<ModelIdentityEntry>> eldest) {
                    return size() > MODEL_IDENTITY_CACHE_SIZE;
                }
            };
    private static final CrashReportDetail<String> ITEM_DEBUG_DETAIL = () -> "browser:item";
    private static int statePoolIndex;

    private BrowserGuiItemCache() {
    }

    public static void resetPool() {
        statePoolIndex = 0;
    }

    public static CrashReportDetail<String> itemDebugDetail() {
        return ITEM_DEBUG_DETAIL;
    }

    public static TrackingItemStackRenderState guiState(
            Minecraft minecraft, ItemStack stack, Level level, LivingEntity entity, int seed) {
        TrackingItemStackRenderState state = acquireState();
        minecraft.getItemModelResolver().updateForTopItem(
                state, stack, ItemDisplayContext.GUI, level, entity, seed);
        return state;
    }

    private static TrackingItemStackRenderState acquireState() {
        int index = statePoolIndex++;
        if (index >= STATE_POOL.length) {
            return new BrowserTrackingItemStackRenderState();
        }
        BrowserTrackingItemStackRenderState state = STATE_POOL[index];
        if (state == null) {
            state = new BrowserTrackingItemStackRenderState();
            STATE_POOL[index] = state;
        } else {
            state.resetForReuse();
        }
        return state;
    }

    private static Object stableModelIdentity(Object identity) {
        if (!(identity instanceof List<?> list)) {
            return identity;
        }
        int size = list.size();
        if (size == 0) {
            return Collections.emptyList();
        }
        if (size == 1) {
            return singleModelIdentity(list.get(0));
        }
        return multiModelIdentity(list);
    }

    private static Object singleModelIdentity(Object element) {
        Object identity = SINGLE_MODEL_IDENTITIES.get(element);
        if (identity == null) {
            identity = Collections.singletonList(element);
            SINGLE_MODEL_IDENTITIES.put(element, identity);
        }
        return identity;
    }

    private static Object multiModelIdentity(List<?> list) {
        int hash = 1;
        for (int i = 0; i < list.size(); i++) {
            hash = 31 * hash + Objects.hashCode(list.get(i));
        }
        List<ModelIdentityEntry> bucket = MULTI_MODEL_IDENTITIES.get(hash);
        if (bucket != null) {
            for (ModelIdentityEntry entry : bucket) {
                if (entry.matches(list)) {
                    return entry.identity;
                }
            }
        } else {
            bucket = new ArrayList<>(2);
            MULTI_MODEL_IDENTITIES.put(hash, bucket);
        }

        ArrayList<Object> copy = new ArrayList<>(list.size());
        copy.addAll(list);
        Object identity = Collections.unmodifiableList(copy);
        bucket.add(new ModelIdentityEntry(copy, identity));
        return identity;
    }

    private static final class ModelIdentityEntry {
        private final List<Object> elements;
        private final Object identity;

        private ModelIdentityEntry(List<Object> elements, Object identity) {
            this.elements = elements;
            this.identity = identity;
        }

        private boolean matches(List<?> list) {
            int size = elements.size();
            if (size != list.size()) {
                return false;
            }
            for (int i = 0; i < size; i++) {
                if (!Objects.equals(elements.get(i), list.get(i))) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final class BrowserTrackingItemStackRenderState extends TrackingItemStackRenderState {
        private Object stableModelIdentity = Collections.emptyList();
        private boolean modelIdentityDirty = true;

        @Override
        public void appendModelIdentityElement(Object element) {
            super.appendModelIdentityElement(element);
            modelIdentityDirty = true;
        }

        @Override
        public Object getModelIdentity() {
            if (modelIdentityDirty) {
                stableModelIdentity = stableModelIdentity(super.getModelIdentity());
                modelIdentityDirty = false;
            }
            return stableModelIdentity;
        }

        @SuppressWarnings("unchecked")
        void resetForReuse() {
            clear();
            Object identity = super.getModelIdentity();
            if (identity instanceof List<?>) {
                ((List<Object>) identity).clear();
            }
            stableModelIdentity = Collections.emptyList();
            modelIdentityDirty = true;
        }
    }
}
