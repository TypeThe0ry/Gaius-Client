package dev.gaius.browser;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * "What would you like to do?", opened by the world selection screen's Create New World button
 * (BrowserWorldTransfer.openCreateChoice): Create New World continues to vanilla's create-world
 * screen, Import World reads a Java Edition save .zip. Cancel and Escape return to the world list.
 * A vanilla header-and-footer screen: title header, a centred column of 200-wide buttons, Cancel
 * in the footer.
 */
public final class BrowserCreateWorldChoiceScreen extends Screen {
    private static final Component TITLE = BrowserI18n.text("world.choice_title");
    private static final int BUTTON_WIDTH = 200;

    private final Minecraft client;
    private HeaderAndFooterLayout layout;
    private final Runnable returnToList;

    BrowserCreateWorldChoiceScreen(Minecraft client, Runnable returnToList) {
        super(TITLE);
        this.client = client;
        this.returnToList = returnToList;
    }

    @Override
    protected void init() {
        layout = new HeaderAndFooterLayout(this);
        layout.addTitleHeader(this.title, this.font);
        LinearLayout choices = layout.addToContents(LinearLayout.vertical().spacing(8));
        choices.defaultCellSetting().alignHorizontallyCenter();
        choices.addChild(Button.builder(Component.translatable("selectWorld.create"),
                        ignored -> CreateWorldScreen.openFresh(client, returnToList))
                .width(BUTTON_WIDTH)
                .build());
        Button importButton = choices.addChild(Button.builder(BrowserI18n.text("world.import"),
                        ignored -> BrowserWorldTransferScreen.startImport(returnToList))
                .width(BUTTON_WIDTH)
                .build());
        importButton.setTooltip(Tooltip.create(BrowserI18n.text("world.import_tooltip")));
        importButton.active = BrowserWorldTransfer.available();
        layout.addToFooter(Button.builder(CommonComponents.GUI_CANCEL, ignored -> onClose())
                .width(BUTTON_WIDTH)
                .build());
        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
    }

    @Override
    protected void repositionElements() {
        if (layout != null) {
            layout.arrangeElements();
        }
    }

    @Override
    public void onClose() {
        returnToList.run();
    }
}
