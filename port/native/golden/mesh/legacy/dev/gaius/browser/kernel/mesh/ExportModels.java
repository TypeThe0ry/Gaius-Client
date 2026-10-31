package dev.gaius.browser.kernel.mesh;

import java.lang.reflect.Field;
import java.util.Map;
import net.minecraft.world.level.block.state.BlockState;

final class ExportModels {
    static Object compiler(Map<BlockState, Object> models, Object missing, Object sprite, Object baked, Object colors) throws Exception {
        Object shaper = MeshGolden.blank("net.minecraft.client.renderer.block.BlockModelShaper");
        MeshGolden.field(shaper, "modelByStateCache", models);
        Object dispatcher = MeshGolden.blank("net.minecraft.client.renderer.block.BlockRenderDispatcher");
        MeshGolden.field(dispatcher, "blockModelShaper", shaper);
        MeshGolden.field(dispatcher, "blockColors", colors);
        MeshGolden.field(dispatcher, "modelRenderer", MeshGolden.construct("net.minecraft.client.renderer.block.ModelBlockRenderer", colors));
        Object liquids = MeshGolden.blank("net.minecraft.client.renderer.block.LiquidBlockRenderer");
        for (String name : new String[]{"lavaStill", "lavaFlowing", "waterStill", "waterFlowing", "waterOverlay"}) MeshGolden.field(liquids, name, sprite);
        MeshGolden.field(dispatcher, "liquidBlockRenderer", liquids);
        MeshGolden.field(dispatcher, "singleThreadRandom", net.minecraft.util.RandomSource.create());
        MeshGolden.field(dispatcher, "singleThreadPartList", new java.util.ArrayList<>());
        return MeshGolden.construct("net.minecraft.client.renderer.chunk.SectionCompiler", dispatcher, null);
    }
    static byte[] table(Map<BlockState, Object> models, Object missing, Object sprite, Object baked, Object colors, int semantics) throws Exception {
        return LegacyModelTable.encode(models, semantics);
    }
    static void options(Object compiler, boolean ambient, boolean cutout) throws Exception {
        Object minecraft = MeshGolden.blank("net.minecraft.client.Minecraft");
        Object options = MeshGolden.blank("net.minecraft.client.Options");
        Object option = MeshGolden.blank("net.minecraft.client.OptionInstance");
        MeshGolden.field(option, "value", ambient);
        MeshGolden.field(options, "ambientOcclusion", option);
        MeshGolden.field(minecraft, "options", options);
        Field instance = minecraft.getClass().getDeclaredField("instance");
        instance.setAccessible(true); instance.set(null, minecraft);
        net.minecraft.client.renderer.ItemBlockRenderTypes.setCutoutLeaves(cutout);
    }
}
