package dev.gaius.browser;

import java.util.function.Function;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Adds or edits one user relay (name, address and what it is used for), laid out like vanilla's
 * Edit Server Info screen: labelled 200-wide fields in the contents of a
 * {@link HeaderAndFooterLayout}, a {@link CycleButton} for the use, and Done / Cancel in the footer.
 */
public final class BrowserRelayEditScreen extends Screen {
    private static final int FIELD_WIDTH = 200;

    private final Screen parent;
    private final Function<BrowserRelays.Relay, String> onSave;
    private HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
    private EditBox nameBox;
    private String use;
    private String nameValue;
    private String urlValue;
    private Component status = Component.empty();

    public BrowserRelayEditScreen(Screen parent, BrowserRelays.Relay original,
            Function<BrowserRelays.Relay, String> onSave) {
        super(BrowserI18n.text(original == null ? "relay.add" : "relay.edit"));
        this.parent = parent;
        this.onSave = onSave;
        this.use = original == null ? BrowserRelays.USE_BOTH : BrowserRelays.normalizeUse(original.use());
        this.nameValue = original == null ? "" : original.name();
        this.urlValue = original == null ? "wss://" : original.url();
    }

    @Override
    protected void init() {
        // A fresh layout per init: a failed save rebuilds the widgets.
        layout = new HeaderAndFooterLayout(this);
        layout.addTitleHeader(this.title, this.font);

        LinearLayout contents = layout.addToContents(LinearLayout.vertical().spacing(4));
        contents.defaultCellSetting().alignHorizontallyCenter();

        contents.addChild(label(BrowserI18n.text("relay.name")));
        nameBox = contents.addChild(new EditBox(this.font, FIELD_WIDTH, 20,
                BrowserI18n.text("relay.name")));
        nameBox.setMaxLength(80);
        nameBox.setValue(nameValue);
        nameBox.setResponder(value -> nameValue = value);

        contents.addChild(label(BrowserI18n.text("relay.address")));
        EditBox urlBox = contents.addChild(new EditBox(this.font, FIELD_WIDTH, 20,
                BrowserI18n.text("relay.address")));
        urlBox.setMaxLength(512);
        urlBox.setValue(urlValue);
        urlBox.setHint(Component.literal("wss://host/tunnel").withStyle(ChatFormatting.DARK_GRAY));
        urlBox.setResponder(value -> urlValue = value);

        contents.addChild(CycleButton.builder(
                        (String value) -> Component.literal(BrowserRelaysScreen.useLabel(value)), use)
                .withValues(BrowserRelays.USE_BOTH, BrowserRelays.USE_MULTIPLAYER, BrowserRelays.USE_LAN)
                .create(0, 0, FIELD_WIDTH, 20,
                        BrowserI18n.text("relay.used_for"),
                        (button, value) -> use = value));

        if (!status.getString().isEmpty()) {
            contents.addChild(new MultiLineTextWidget(status, this.font)
                    .setMaxWidth(FIELD_WIDTH)
                    .setCentered(true));
        }

        LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(8));
        footer.addChild(Button.builder(CommonComponents.GUI_DONE, ignored -> save()).width(150).build());
        footer.addChild(Button.builder(CommonComponents.GUI_CANCEL, ignored -> onClose()).width(150).build());

        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
        setInitialFocus(nameBox);
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
    }

    @Override
    public void onClose() {
        BrowserScreens.show(parent);
    }

    /** A gray field label left-aligned over its 200-wide box, as on vanilla's server editor. */
    private StringWidget label(Component text) {
        return new StringWidget(FIELD_WIDTH, 9, text.copy().withStyle(ChatFormatting.GRAY), this.font);
    }

    private void save() {
        String url = BrowserRelays.normalize(urlValue);
        if (url.isEmpty()) {
            fail(BrowserI18n.string("relay.ws_address_invalid"));
            return;
        }
        String name = nameValue.trim();
        String failure = onSave.apply(new BrowserRelays.Relay(url, name, use, false, true));
        if (failure != null) {
            fail(failure);
            return;
        }
        BrowserScreens.show(new BrowserRelaysScreen(
                parent instanceof BrowserRelaysScreen relaysScreen ? relaysScreen.parentScreen() : parent));
    }

    private void fail(String message) {
        status = Component.literal(message).withStyle(ChatFormatting.RED);
        rebuildWidgets();
    }
}
