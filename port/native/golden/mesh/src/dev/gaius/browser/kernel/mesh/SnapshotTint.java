package dev.gaius.browser.kernel.mesh;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;

public final class SnapshotTint {
    public final byte[] quarts = new byte[216];
    public final int[][] palette;
    public final long seed;
    public final int radius;
    private final net.minecraft.client.multiplayer.ClientLevel level;

    public SnapshotTint(int sectionX, int sectionY, int sectionZ, int number) throws Exception {
        seed = 0x4d455348L + number;
        radius = number % 3;
        palette = new int[][]{{0x408020, 0, 0x20a040, 0x20a040, 0x3050c0},
            {0x91bc32, 0, 0x83ad20, 0x83ad20, 0x487ac4}, {0x557f48, 0, 0x619961, 0x619961, 0x324bad}};
        @SuppressWarnings("unchecked") Holder<Biome>[] biomes = (Holder<Biome>[]) new Holder<?>[palette.length];
        for (int index = 0; index < palette.length; index++) {
            Object builder = MeshGolden.construct("net.minecraft.world.level.biome.BiomeSpecialEffects$Builder");
            for (String optional : new String[]{"fogColor", "waterFogColor", "skyColor"}) {
                try { MeshGolden.call(builder, optional, 0); } catch (NoSuchMethodException ignored) {}
            }
            MeshGolden.call(builder, "waterColor", palette[index][4]);
            MeshGolden.call(builder, "grassColorOverride", palette[index][0]);
            MeshGolden.call(builder, "foliageColorOverride", palette[index][2]);
            Biome biome = (Biome) MeshGolden.blank("net.minecraft.world.level.biome.Biome");
            MeshGolden.field(biome, "specialEffects", MeshGolden.call(builder, "build"));
            biomes[index] = Holder.direct(biome);
        }
        java.util.Random random = new java.util.Random(seed);
        for (int index = 0; index < quarts.length; index++) quarts[index] = (byte) random.nextInt(palette.length);
        BiomeManager manager = new BiomeManager((horizontal, vertical, depth) -> {
            int quartX = Math.max(0, Math.min(5, horizontal - (sectionX * 4 - 1)));
            int quartY = Math.max(0, Math.min(5, vertical - (sectionY * 4 - 1)));
            int quartZ = Math.max(0, Math.min(5, depth - (sectionZ * 4 - 1)));
            return biomes[Byte.toUnsignedInt(quarts[(quartY * 6 + quartZ) * 6 + quartX])];
        }, seed);
        level = (net.minecraft.client.multiplayer.ClientLevel) MeshGolden.blank("net.minecraft.client.multiplayer.ClientLevel");
        MeshGolden.field(level, "biomeManager", manager);
    }

    public void configure() throws Exception {
        Object minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft == null) {
            minecraft = MeshGolden.blank("net.minecraft.client.Minecraft");
            java.lang.reflect.Field instance = minecraft.getClass().getDeclaredField("instance");
            instance.setAccessible(true); instance.set(null, minecraft);
            MeshGolden.field(minecraft, "options", MeshGolden.blank("net.minecraft.client.Options"));
        }
        Object option = MeshGolden.blank("net.minecraft.client.OptionInstance");
        MeshGolden.field(option, "value", radius);
        MeshGolden.field(net.minecraft.client.Minecraft.getInstance().options, "biomeBlendRadius", option);
    }

    public int color(BlockPos position, ColorResolver resolver) {
        return level.calculateBlockTint(position, resolver);
    }
}
