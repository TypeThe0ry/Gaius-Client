package dev.gaius.browser;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Open to LAN for a Worker-hosted world, laid out like vanilla's LAN screen: the title in the
 * header, the explanation and (once published) the invite link in the contents, and Start LAN
 * World / Cancel (then Done) in the footer. The relay-backed invite replaces vanilla's port; it is
 * published by {@link BrowserLanSession} and read back here once the browser shell answers.
 */
public final class BrowserShareToLanScreen extends Screen {
    private static final int CONTENT_WIDTH = 310;

    private final Screen parent;
    private HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
    private String state = BrowserLanSession.INVITE_IDLE;
    private String url = "";
    private Component status = Component.empty();

    public BrowserShareToLanScreen(Screen parent) {
        super(BrowserLanSession.openToLanLabel());
        this.parent = parent;
    }

    @Override
    protected void init() {
        // A fresh layout per init: rebuildWidgets and re-showing the screen run init again.
        layout = new HeaderAndFooterLayout(this);
        layout.addTitleHeader(this.title, this.font);

        LinearLayout contents = layout.addToContents(LinearLayout.vertical().spacing(8));
        contents.defaultCellSetting().alignHorizontallyCenter();
        contents.addChild(new MultiLineTextWidget(BrowserI18n.text("lan.explanation"), this.font)
                .setMaxWidth(CONTENT_WIDTH)
                .setCentered(true));

        EditBox link = null;
        if (BrowserLanSession.INVITE_DONE.equals(state)) {
            link = contents.addChild(new EditBox(this.font, CONTENT_WIDTH, 20,
                    BrowserI18n.text("lan.invite_link")));
            link.setMaxLength(2048);
            link.setValue(url);
            link.moveCursorToStart(false);
            // Read-only: the link can be selected and copied with the keyboard, not changed.
            link.setEditable(false);
            contents.addChild(Button.builder(Component.translatable("gui.copy_link_to_clipboard"),
                            ignored -> {
                                copyLink();
                                status = BrowserI18n.text("lan.copied")
                                        .withStyle(ChatFormatting.GRAY);
                                rebuildWidgets();
                            })
                    .width(CONTENT_WIDTH)
                    .build());
        }
        if (!status.getString().isEmpty()) {
            contents.addChild(new MultiLineTextWidget(status, this.font)
                    .setMaxWidth(CONTENT_WIDTH)
                    .setCentered(true));
        }

        LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(8));
        if (BrowserLanSession.INVITE_DONE.equals(state)) {
            footer.addChild(Button.builder(CommonComponents.GUI_DONE, ignored -> onClose()).width(200).build());
        } else {
            Button start = footer.addChild(Button.builder(Component.translatable("lanServer.start"),
                            ignored -> start())
                    .width(150)
                    .build());
            start.active = BrowserLanSession.INVITE_IDLE.equals(state) || BrowserLanSession.INVITE_ERROR.equals(state);
            footer.addChild(Button.builder(CommonComponents.GUI_CANCEL, ignored -> onClose()).width(150).build());
        }

        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
        if (link != null) {
            setInitialFocus(link);
        }
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
    }

    @Override
    public void tick() {
        super.tick();
        if (!BrowserLanSession.INVITE_PENDING.equals(state)) {
            return;
        }
        String next = BrowserLanSession.inviteState();
        if (BrowserLanSession.INVITE_PENDING.equals(next)) {
            return;
        }
        if (BrowserLanSession.INVITE_DONE.equals(next)) {
            url = BrowserLanSession.inviteUrl();
            state = next;
            status = Component.empty();
            // Best effort, like the vanilla share flow: the link stays selectable in the box.
            copyLink();
        } else {
            String error = BrowserLanSession.inviteError();
            state = BrowserLanSession.INVITE_ERROR;
            status = Component.translatable("commands.publish.failed")
                    .append(error.isEmpty() ? Component.empty() : Component.literal(": " + error))
                    .withStyle(ChatFormatting.RED);
        }
        rebuildWidgets();
    }

    @Override
    public void onClose() {
        BrowserScreens.show(parent);
    }

    private void start() {
        if (!BrowserLanSession.open(Minecraft.getInstance())) {
            state = BrowserLanSession.INVITE_ERROR;
            status = Component.translatable("commands.publish.failed").withStyle(ChatFormatting.RED);
        } else {
            state = BrowserLanSession.INVITE_PENDING;
            status = BrowserI18n.text("lan.creating")
                    .withStyle(ChatFormatting.GRAY);
        }
        rebuildWidgets();
    }

    private void copyLink() {
        if (url.isEmpty()) {
            return;
        }
        Minecraft.getInstance().keyboardHandler.setClipboard(url);
    }

}
