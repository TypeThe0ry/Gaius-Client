package dev.gaius.browser;

import dev.gaius.browser.transport.TransportKind;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.browser.BrowserWebSocketChannel;
import io.netty.util.AttributeKey;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList.OnlineServerEntry;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Relay manager opened from the multiplayer screen, laid out like vanilla's option screens: the
 * title in a {@link HeaderAndFooterLayout} header, the relays in a {@link ScrollableLayout}
 * (one row each: name, address, and its buttons), and Add Relay / Done in the footer. User
 * relays can be added, edited, removed (after a vanilla confirmation) and moved up (earlier rows
 * are tried first); the build's built-in relays can be switched off and on. Each relay is used for
 * multiplayer, for Open to LAN, or for both.
 */
public final class BrowserRelaysScreen extends Screen {
    private static final int ROW_WIDTH = 310;
    private static final int CONTENT_SPACING = 4;

    // Shared with BrowserWebSocketChannel. Bootstrap attaches it before the channel is registered.
    private static final AttributeKey<Integer> RELAY_WARMUP_ATTEMPT = AttributeKey.valueOf("gaius.relayWarmupAttempt");
    private static final ThreadLocal<PendingJoin> PENDING_JOIN = new ThreadLocal<>();
    private static JoinMultiplayerScreen activeList;
    private static boolean resumingList;
    private static boolean checkedList;
    private static boolean warmedList;
    private static String targetHost;
    private static int targetPort;
    private static int nextAttempt;

    private record PendingJoin(Screen parent, ServerAddress address, ServerData server, int id) {}

    private final Screen parent;
    private HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
    private final List<BrowserRelays.Relay> relays = new ArrayList<>();
    private ScrollableLayout list;
    private MultiLineTextWidget statusWidget;
    private Component status = Component.empty();

    public BrowserRelaysScreen(Screen parent) {
        super(BrowserI18n.text("relay.title"));
        this.parent = parent;
    }

    /** "Relays" button injected into the vanilla multiplayer screen (top-right corner). */
    public static Button joinButton(Screen screen) {
        Button button = Button.builder(BrowserI18n.text("relay.title"),
                        ignored -> BrowserScreens.show(new BrowserRelaysScreen(screen)))
                .bounds(screen.width - 104, 6, 98, 20)
                .build();
        button.setTooltip(Tooltip.create(BrowserI18n.text("relay.tooltip")));
        button.visible = BrowserRelays.available();
        return button;
    }

    // These three activation hooks run on the client UI thread. added() precedes either the
    // first init() or repositionElements() after a return to the same initialized screen.
    public static void serverListAdded(JoinMultiplayerScreen screen) {
        if (activeList != null && activeList != screen) {
            serverListRemoved(activeList);
        }
        activeList = screen;
        resumingList = screen.getServers() != null;
        checkedList = false;
        warmedList = false;
    }

    public static void serverListInitialized(JoinMultiplayerScreen screen) {
        if (activeList != screen || checkedList) {
            return;
        }
        checkedList = true;
        ServerList servers = screen.getServers();
        for (int index = 0; index < servers.size(); index++) {
            if (isPublicTarget(servers.get(index))) {
                BrowserWebSocketChannel.warmServerList(true);
                warmedList = true;
                return;
            }
        }
    }

    public static void serverListRepositioned(JoinMultiplayerScreen screen) {
        if (resumingList) {
            serverListInitialized(screen);
        }
    }

    public static void serverListRemoved(JoinMultiplayerScreen screen) {
        if (activeList == screen) {
            activeList = null;
            targetHost = null;
            targetPort = 0;
            BrowserWebSocketChannel.warmServerList(false);
        }
    }

    public static void serverListTargetChanged(Minecraft minecraft, JoinMultiplayerScreen screen,
            ServerSelectionList list) {
        if (screen != activeList || minecraft.gaius$getScreen() != screen || !warmedList || list == null) {
            return;
        }
        if (list.getSelected() instanceof OnlineServerEntry online) {
            ServerData server = online.getServerData();
            if (isSavedPublicTarget(screen, server)) {
                ServerAddress address = ServerAddress.parseString(server.ip);
                String host = address.getHost();
                int port = address.getPort();
                if (!host.isEmpty() && port > 0) {
                    setServerListTarget(host, port);
                    return;
                }
            }
        }
        setServerListTarget(null, 0);
    }

    private static void setServerListTarget(String host, int port) {
        if (targetPort != port || !Objects.equals(targetHost, host)) {
            BrowserWebSocketChannel.serverListTarget(host, port);
            targetHost = host;
            targetPort = port;
        }
    }

    /** Carries the list's warm socket across the vanilla transition for one saved-server join. */
    public static void startConnecting(Screen parent, Minecraft minecraft, ServerAddress address,
            ServerData server, boolean quickPlay, TransferState transfer) {
        if (activeList == null || activeList != parent || minecraft.gaius$getScreen() != parent
                || !isSavedPublicTarget(activeList, server) || address.getPort() <= 0) {
            ConnectScreen.startConnecting(parent, minecraft, address, server, quickPlay, transfer);
            return;
        }

        // ConnectScreen.connect binds this scope before it starts the asynchronous connector.
        int id = ++nextAttempt;
        if (id == 0) {
            id = ++nextAttempt;
        }
        PendingJoin previous = PENDING_JOIN.get();
        PENDING_JOIN.set(new PendingJoin(parent, address, server, id));
        boolean transitioned = false;
        try {
            BrowserWebSocketChannel.reserveServerListWarmup(address.getHost(), address.getPort(), id);
            ConnectScreen.startConnecting(parent, minecraft, address, server, quickPlay, transfer);
            transitioned = minecraft.gaius$getScreen() instanceof ConnectScreen;
        } finally {
            if (previous == null) {
                PENDING_JOIN.remove();
            } else {
                PENDING_JOIN.set(previous);
            }
            if (!transitioned) {
                releaseRelayWarmup(id);
            }
        }
    }

    private static boolean isSavedPublicTarget(JoinMultiplayerScreen screen, ServerData server) {
        ServerList servers = screen.getServers();
        for (int index = 0; index < servers.size(); index++) {
            if (servers.get(index) == server) {
                return isPublicTarget(server);
            }
        }
        return false;
    }

    private static boolean isPublicTarget(ServerData server) {
        TransportKind transport = TransportKind.fromProtocol(server.gaiusProtocol);
        return (transport == TransportKind.RELAY || transport == TransportKind.PLUGIN)
                && server.ip != null && BrowserWebSocketChannel.isPublicServerAddress(server.ip)
                && ServerAddress.isValidAddress(server.ip);
    }

    public static int connectingAttempt(Screen parent, ServerAddress address, ServerData server) {
        PendingJoin join = PENDING_JOIN.get();
        return join != null && join.parent() == parent && join.address() == address && join.server() == server
                ? join.id() : 0;
    }

    public static ChannelFuture connectChannel(Bootstrap bootstrap, SocketAddress address, int attempt) {
        if (attempt != 0) {
            bootstrap.attr(RELAY_WARMUP_ATTEMPT, attempt);
        }
        return bootstrap.connect(address);
    }

    public static void releaseRelayWarmup(int attempt) {
        if (attempt != 0) {
            BrowserWebSocketChannel.cancelServerListWarmup(attempt);
        }
    }

    @Override
    protected void init() {
        // A fresh layout per init: rebuildWidgets and re-showing the screen run init again.
        layout = new HeaderAndFooterLayout(this);
        relays.clear();
        relays.addAll(BrowserRelays.list());
        layout.addTitleHeader(this.title, this.font);

        LinearLayout contents = layout.addToContents(LinearLayout.vertical().spacing(CONTENT_SPACING));
        contents.defaultCellSetting().alignHorizontallyCenter();
        LinearLayout rows = LinearLayout.vertical().spacing(4);
        rows.defaultCellSetting().alignHorizontallyCenter();
        int firstUser = firstUserIndex();
        for (int index = 0; index < relays.size(); index++) {
            rows.addChild(row(index, firstUser));
        }
        if (relays.isEmpty()) {
            rows.addChild(new StringWidget(
                    BrowserI18n.text("relay.no_relays").copy().withStyle(ChatFormatting.GRAY),
                    this.font));
        }
        list = contents.addChild(new ScrollableLayout(this.minecraft, rows, layout.getContentHeight()));
        statusWidget = contents.addChild(new MultiLineTextWidget(status, this.font)
                .setMaxWidth(ROW_WIDTH)
                .setCentered(true));

        LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(8));
        footer.addChild(Button.builder(BrowserI18n.text("relay.add"), ignored -> add())
                .width(150).build());
        footer.addChild(Button.builder(CommonComponents.GUI_DONE, ignored -> onClose()).width(150).build());

        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
    }

    @Override
    protected void repositionElements() {
        if (list == null) {
            layout.arrangeElements();
            return;
        }
        int statusHeight = status.getString().isEmpty() ? 0 : statusWidget.getHeight() + CONTENT_SPACING;
        // As vanilla's WorldOptionsScreen: arrange the rows first, so the height cap applies to
        // their real height (26.x clamps the container height in arrangeElements, 1.21.11 sets it).
        list.arrangeElements();
        list.setMaxHeight(Math.max(20, layout.getContentHeight() - statusHeight - 8));
        layout.arrangeElements();
    }

    /** One relay: name and address on the left, its buttons on the right, 310 wide like vanilla rows. */
    private LinearLayout row(int index, int firstUser) {
        BrowserRelays.Relay relay = relays.get(index);
        LinearLayout row = LinearLayout.horizontal().spacing(4);
        row.defaultCellSetting().alignVerticallyMiddle();
        int buttonsWidth = relay.builtIn() ? 60 : 20 + 4 + 50 + 4 + 50;
        int textWidth = ROW_WIDTH - buttonsWidth - 4;
        LinearLayout text = row.addChild(LinearLayout.vertical().spacing(2));
        text.addChild(new StringWidget(textWidth, 9, nameLine(relay), this.font).setMaxWidth(textWidth));
        text.addChild(new StringWidget(textWidth, 9, urlLine(relay), this.font).setMaxWidth(textWidth));
        if (relay.builtIn()) {
            row.addChild(Button.builder(
                            relay.enabled() ? BrowserI18n.text("relay.disable") : BrowserI18n.text("relay.enable"),
                            ignored -> toggleBuiltIn(relay))
                    .width(60).build());
            return row;
        }
        Button up = row.addChild(Button.builder(Component.literal("\u25b2"), ignored -> moveUp(index))
                .width(20)
                .tooltip(Tooltip.create(BrowserI18n.text("relay.try_earlier")))
                .build());
        up.active = index > firstUser;
        row.addChild(Button.builder(Component.translatable("selectServer.edit"), ignored -> edit(index))
                .width(50).build());
        row.addChild(Button.builder(Component.translatable("selectServer.delete"), ignored -> confirmRemove(index))
                .width(50).build());
        return row;
    }

    @Override
    public void onClose() {
        BrowserScreens.show(parent);
    }

    Screen parentScreen() {
        return parent;
    }

    private int firstUserIndex() {
        for (int index = 0; index < relays.size(); index++) {
            if (!relays.get(index).builtIn()) {
                return index;
            }
        }
        return relays.size();
    }

    private Component nameLine(BrowserRelays.Relay relay) {
        MutableComponent line = Component.literal(relay.name().isEmpty() ? relay.url() : relay.name())
                .withStyle(relay.enabled() ? ChatFormatting.WHITE : ChatFormatting.DARK_GRAY);
        line.append(Component.literal("  [" + useLabel(relay.use()) + "]").withStyle(ChatFormatting.GRAY));
        if (relay.builtIn()) {
            line.append(BrowserI18n.text("relay.built_in").copy().withStyle(ChatFormatting.DARK_AQUA));
        }
        if (!relay.enabled()) {
            line.append(BrowserI18n.text("relay.disabled").copy().withStyle(ChatFormatting.RED));
        }
        return line;
    }

    private static Component urlLine(BrowserRelays.Relay relay) {
        MutableComponent line = Component.literal(relay.secure() ? "WSS " : "WS ")
                .withStyle(relay.secure() ? ChatFormatting.GREEN : ChatFormatting.YELLOW);
        line.append(Component.literal(relay.url()).withStyle(ChatFormatting.GRAY));
        return line;
    }

    static String useLabel(String use) {
        return switch (BrowserRelays.normalizeUse(use)) {
            case BrowserRelays.USE_MULTIPLAYER -> BrowserI18n.string("relay.multiplayer");
            case BrowserRelays.USE_LAN -> BrowserI18n.string("relay.lan");
            default -> BrowserI18n.string("relay.both");
        };
    }

    private void toggleBuiltIn(BrowserRelays.Relay relay) {
        BrowserRelays.setBuiltInEnabled(relay.url(), !relay.enabled());
        status = BrowserI18n.text("relay.saved")
                .withStyle(ChatFormatting.GRAY);
        rebuildWidgets();
    }

    private void moveUp(int index) {
        List<BrowserRelays.Relay> next = new ArrayList<>(relays);
        BrowserRelays.Relay moved = next.remove(index);
        next.add(index - 1, moved);
        save(next);
    }

    private void confirmRemove(int index) {
        BrowserRelays.Relay relay = relays.get(index);
        String label = relay.name().isEmpty() ? relay.url() : relay.name();
        BrowserScreens.show(new ConfirmScreen(confirmed -> {
            BrowserScreens.show(this);
            if (confirmed) {
                remove(index);
            }
        }, BrowserI18n.text("relay.remove_confirm"),
                Component.translatable("selectServer.deleteWarning", label),
                Component.translatable("selectServer.deleteButton"),
                CommonComponents.GUI_CANCEL));
    }

    private void remove(int index) {
        List<BrowserRelays.Relay> next = new ArrayList<>(relays);
        next.remove(index);
        save(next);
    }

    private void edit(int index) {
        BrowserRelays.Relay relay = relays.get(index);
        BrowserScreens.show(new BrowserRelayEditScreen(this, relay, updated -> {
            List<BrowserRelays.Relay> next = new ArrayList<>(relays);
            next.set(index, updated);
            return saveResult(next);
        }));
    }

    private void add() {
        BrowserScreens.show(new BrowserRelayEditScreen(this, null, created -> {
            List<BrowserRelays.Relay> next = new ArrayList<>(relays);
            next.add(firstUserIndex(), created);
            return saveResult(next);
        }));
    }

    private void save(List<BrowserRelays.Relay> next) {
        String failure = saveResult(next);
        status = failure == null
                ? BrowserI18n.text("relay.saved")
                        .withStyle(ChatFormatting.GRAY)
                : Component.literal(failure).withStyle(ChatFormatting.RED);
        rebuildWidgets();
    }

    /** Saves the user relays; returns an error message or null. */
    private String saveResult(List<BrowserRelays.Relay> next) {
        String result = BrowserRelays.saveUser(next);
        return result == null || result.isEmpty() || "ok".equals(result) ? null : result;
    }

}
