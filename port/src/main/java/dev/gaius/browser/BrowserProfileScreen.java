package dev.gaius.browser;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.PlayerSkinWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.PlayerSkin;
import org.teavm.jso.JSBody;

/**
 * Vanilla-styled profile editor (username + custom skin) opened from the title screen.
 * Applying it swaps the running client's identity in place; no page reload is needed.
 */
public final class BrowserProfileScreen extends Screen {
    private static final int COLUMN_WIDTH = 200;
    private static final String NAME_PATTERN = "[A-Za-z0-9_]{1,16}";

    private final Screen parent;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
    private final boolean online;
    private EditBox nameBox;
    private Button modelButton;
    private MultiLineTextWidget status;
    private String pendingName;
    private String pendingSkin;
    private boolean pendingSlim;
    private boolean picking;
    private String previewKey = "";
    private Supplier<PlayerSkin> previewSkin;

    public BrowserProfileScreen(Screen parent) {
        super(BrowserI18n.text("profile.edit"));
        this.parent = parent;
        this.online = BrowserProfile.isOnline();
        Minecraft client = Minecraft.getInstance();
        this.pendingName = client.getUser().getName();
        this.pendingSkin = BrowserProfile.savedSkinDataUrl();
        this.pendingSlim = BrowserProfile.savedSkinSlim();
    }

    /** "Edit Profile" button injected into the vanilla title screen (top-right corner). */
    public static Button titleButton(Screen title) {
        return Button.builder(BrowserI18n.text("profile.edit"), ignored -> open(title))
                .bounds(title.width - 104, 6, 98, 20)
                .build();
    }

    /** Opens the editor once on first launch so a new player can pick a name and skin. */
    public static void titleTick(Screen title) {
        if (BrowserProfile.consumeFirstRun()) {
            open(title);
        }
    }

    public static void open(Screen parent) {
        Minecraft.getInstance().gui.setScreen(new BrowserProfileScreen(parent));
    }

    @Override
    protected void init() {
        layout.addTitleHeader(this.title, this.font);

        LinearLayout contents = layout.addToContents(LinearLayout.horizontal().spacing(16));
        contents.addChild(new PlayerSkinWidget(
                100, 150, this.minecraft.getEntityModels(), this::previewSkin));

        LinearLayout column = contents.addChild(LinearLayout.vertical().spacing(5));
        column.addChild(new StringWidget(BrowserI18n.text("profile.username"), this.font));
        nameBox = column.addChild(new EditBox(this.font, COLUMN_WIDTH, 20,
                BrowserI18n.text("profile.username")));
        nameBox.setMaxLength(16);
        nameBox.setValue(pendingName);
        nameBox.setResponder(value -> pendingName = value);
        if (online) {
            nameBox.setEditable(false);
        }
        column.addChild(Button.builder(
                        BrowserI18n.text("profile.upload_skin"),
                        ignored -> beginPick())
                .width(COLUMN_WIDTH)
                .build());
        modelButton = column.addChild(Button.builder(modelLabel(), ignored -> toggleModel())
                .width(COLUMN_WIDTH)
                .build());
        column.addChild(Button.builder(
                        BrowserI18n.text("profile.default_skin"),
                        ignored -> useDefaultSkin())
                .width(COLUMN_WIDTH)
                .build());
        status = column.addChild(new MultiLineTextWidget(
                hint(BrowserI18n.string(online ? "profile.online_hint"
                        : "profile.offline_hint")),
                this.font).setMaxWidth(COLUMN_WIDTH));
        LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(8));
        footer.addChild(Button.builder(CommonComponents.GUI_DONE, ignored -> done()).width(150).build());
        footer.addChild(Button.builder(CommonComponents.GUI_CANCEL, ignored -> onClose()).width(150).build());

        layout.visitWidgets(this::addRenderableWidget);
        refreshModelButton();
        repositionElements();
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
    }

    @Override
    public void tick() {
        super.tick();
        if (!picking) {
            return;
        }
        String state = BrowserProfile.pickState();
        if (state.contains("\"state\":\"pending\"") || state.contains("\"state\":\"idle\"")) {
            return;
        }
        picking = false;
        BrowserProfile.clearPickState();
        if (state.contains("\"state\":\"done\"")) {
            String dataUrl = jsonString(state, "dataUrl");
            if (!dataUrl.isEmpty()) {
                pendingSkin = dataUrl;
                pendingSlim = state.contains("\"slim\":true");
                refreshModelButton();
                setStatus(hint(BrowserI18n.string("profile.skin_loaded")));
            }
        } else if (state.contains("\"state\":\"error\"")) {
            setStatus(error(jsonString(state, "error")));
        }
    }

    @Override
    public void onClose() {
        BrowserProfile.clearPickState();
        markSetupComplete();
        this.minecraft.gui.setScreen(parent);
    }

    private void beginPick() {
        picking = true;
        setStatus(hint(BrowserI18n.string("profile.choose_skin")));
        BrowserProfile.beginSkinPick();
    }

    private void toggleModel() {
        if (pendingSkin.isEmpty()) {
            return;
        }
        pendingSlim = !pendingSlim;
        refreshModelButton();
    }

    private void useDefaultSkin() {
        pendingSkin = "";
        pendingSlim = false;
        refreshModelButton();
        setStatus(hint(BrowserI18n.string("profile.default_selected")));
    }

    private void done() {
        String name = online ? this.minecraft.getUser().getName() : nameBox.getValue().trim();
        if (!name.matches(NAME_PATTERN)) {
            setStatus(error(BrowserI18n.string("profile.name_invalid")));
            return;
        }
        String failure = BrowserProfile.apply(this.minecraft, name, pendingSkin, pendingSlim);
        if (failure != null) {
            setStatus(error(failure));
            return;
        }
        markSetupComplete();
        this.minecraft.gui.setScreen(parent);
    }

    /** The first-launch editor was seen; later launches start without it. */
    @JSBody(script = """
            try {
              localStorage.removeItem('gaius.profileSetupPending');
            } catch (ignored) {
              // Storage can be unavailable; the editor then simply opens again next launch.
            }
            """)
    private static native void markSetupComplete();

    private PlayerSkin previewSkin() {
        String key = pendingSkin.length() + ":" + pendingSkin.hashCode() + ":" + pendingSlim;
        if (previewSkin == null || !key.equals(previewKey)) {
            previewKey = key;
            UUID id = this.minecraft.getUser().getProfileId();
            GameProfile profile = BrowserSkinProfile.withTextures(
                    id, this.minecraft.getUser().getName(),
                    BrowserProfile.texturesValue(pendingSkin, pendingSlim));
            previewSkin = this.minecraft.getSkinManager().createLookup(profile, false);
        }
        return previewSkin.get();
    }

    private void refreshModelButton() {
        if (modelButton != null) {
            modelButton.setMessage(modelLabel());
            modelButton.active = !pendingSkin.isEmpty();
        }
    }

    private Component modelLabel() {
        String model = BrowserI18n.string(pendingSlim ? "profile.slim" : "profile.classic");
        return BrowserI18n.text("profile.arms", model);
    }

    private void setStatus(Component message) {
        if (status != null) {
            status.setMessage(message);
            repositionElements();
        }
    }

    private static Component hint(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GRAY);
    }

    private static Component error(String text) {
        return Component.literal(text).withStyle(ChatFormatting.RED);
    }

    /** Minimal extraction of a JSON string field produced by JSON.stringify. */
    private static String jsonString(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            return "";
        }
        start += marker.length();
        StringBuilder out = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                out.append(json.charAt(++i));
            } else if (c == '"') {
                break;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
