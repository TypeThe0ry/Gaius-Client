package dev.gaius.browser;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

/** Reusable lazy biome lookup for the browser surface-rule hot path. */
public final class BrowserSurfaceBiomeSupplier implements Supplier<Holder<Biome>> {
    private final Function<BlockPos, Holder<Biome>> biomeGetter;
    private final BlockPos.MutableBlockPos pos;
    private int x;
    private int y;
    private int z;
    private boolean resolved;
    private Holder<Biome> value;

    public BrowserSurfaceBiomeSupplier(
            Function<BlockPos, Holder<Biome>> biomeGetter,
            BlockPos.MutableBlockPos pos) {
        this.biomeGetter = Objects.requireNonNull(biomeGetter, "biomeGetter");
        this.pos = Objects.requireNonNull(pos, "pos");
    }

    public void reset(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.resolved = false;
        this.value = null;
    }

    @Override
    public Holder<Biome> get() {
        if (!resolved) {
            value = biomeGetter.apply(pos.set(x, y, z));
            resolved = true;
        }
        return value;
    }
}
