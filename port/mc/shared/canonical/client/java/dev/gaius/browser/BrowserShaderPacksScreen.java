package dev.gaius.browser;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Options, Video Settings, Shader Packs...: a header/footer settings page in vanilla's style. It
 * holds a cycle button over the imported packs (Off first), an import button for .zip files and the
 * Done button. The packs and the selection live in the page script; this screen rebuilds itself
 * when the script reports a change (a finished import).
 */
public final class BrowserShaderPacksScreen extends Screen {
    private static final int BUTTON_WIDTH = 210;
    private static final int BUTTON_SPACING = 8;
    private static final int TEXT_WIDTH = 310;

    private final Screen lastScreen;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
    private int revision = -1;

    public BrowserShaderPacksScreen(Screen lastScreen) {
        super(text("shaderpacks.title"));
        this.lastScreen = lastScreen;
    }

    /**
     * The "Shader Packs..." button of the Video Settings quality section. It is added by
     * {@code SettingsLayoutPatches.patchVideoSettingsScreenBrowserLayout} next to the other video
     * options; it stays visible but inactive where the page has no WebGPU renderer.
     */
    public static AbstractWidget button(Screen videoSettings) {
        boolean available = BrowserShaderPacks.available();
        return Button.builder(text("shaderpacks.button"),
                        ignored -> BrowserScreens.show(new BrowserShaderPacksScreen(videoSettings)))
                .tooltip(Tooltip.create(text(available
                        ? "shaderpacks.button.tooltip" : "shaderpacks.unavailable")))
                .width(150)
                .build();
    }

    private static Component text(String key) {
        return Component.translatable("gaius." + key);
    }

    @Override
    protected void init() {
        BrowserShaderPacks.setScreenOpen(true);
        BrowserShaderPacks.Snapshot state = BrowserShaderPacks.snapshot();
        revision = state.revision();
        layout.addTitleHeader(this.title, this.font);
        LinearLayout contents = layout.addToContents(LinearLayout.vertical().spacing(BUTTON_SPACING));
        contents.defaultCellSetting().alignHorizontallyCenter();
        contents.addChild(new MultiLineTextWidget(text(state.available()
                        ? "shaderpacks.hint" : "shaderpacks.unavailable"), this.font)
                .setMaxWidth(TEXT_WIDTH)
                .setCentered(true));

        List<String> choices = new ArrayList<>();
        choices.add(BrowserShaderPacks.OFF);
        for (BrowserShaderPacks.Pack pack : state.packs()) {
            choices.add(pack.id());
        }
        String selected = choices.contains(state.selected()) ? state.selected() : BrowserShaderPacks.OFF;
        CycleButton<String> cycle = CycleButton.<String>builder(id -> label(state, id), selected)
                .withValues(choices)
                .create(0, 0, BUTTON_WIDTH, 20, text("shaderpacks.pack"),
                        (button, id) -> {
                            BrowserShaderPacks.select(id);
                            revision = BrowserShaderPacks.snapshot().revision();
                        });
        cycle.active = state.available();
        contents.addChild(cycle);

        Button importPack = Button.builder(text("shaderpacks.import"),
                        ignored -> BrowserShaderPacks.openPicker())
                .width(BUTTON_WIDTH)
                .build();
        importPack.active = state.available();
        contents.addChild(importPack);

        Component status = status(state);
        if (status != null) {
            contents.addChild(new MultiLineTextWidget(status, this.font)
                    .setMaxWidth(TEXT_WIDTH)
                    .setCentered(true));
        }
        layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, ignored -> onClose()).width(200).build());
        layout.arrangeElements();
        layout.visitWidgets(this::addRenderableWidget);
    }

    private static Component status(BrowserShaderPacks.Snapshot state) {
        return switch (state.message()) {
            case "rejected" -> text("shaderpacks.status.rejected");
            case "storage_unavailable" -> text("shaderpacks.status.storage_unavailable");
            case "imported" -> Component.translatable("gaius.shaderpacks.status.imported", state.messageName());
            default -> null;
        };
    }

    private static Component label(BrowserShaderPacks.Snapshot state, String id) {
        if (BrowserShaderPacks.OFF.equals(id)) {
            return CommonComponents.OPTION_OFF;
        }
        for (BrowserShaderPacks.Pack pack : state.packs()) {
            if (pack.id().equals(id)) {
                return Component.literal(pack.name());
            }
        }
        return Component.literal(id);
    }

    @Override
    public void tick() {
        super.tick();
        if (BrowserShaderPacks.snapshot().revision() != revision) {
            rebuildWidgets();
        }
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
    }

    @Override
    public void removed() {
        BrowserShaderPacks.setScreenOpen(false);
    }

    @Override
    public void onClose() {
        BrowserScreens.show(lastScreen);
    }
}
