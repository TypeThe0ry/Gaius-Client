package dev.gaius.browser;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Progress, result and errors of one world export or import (BrowserWorldTransfer). It polls the
 * page's {@code __gaiusWorldArchive.status} every tick and rebuilds when it changes. A vanilla
 * header-and-footer screen: the title in the header, centred wrapped text in the contents and one
 * 200-wide button in the footer (Cancel while the file picker is open, Done afterwards; none while
 * the browser storage is written, which cannot be interrupted safely).
 */
public final class BrowserWorldTransferScreen extends Screen {
    private static final int BUTTON_WIDTH = 200;
    private static final int TEXT_WIDTH = 300;

    private enum Kind { EXPORT, IMPORT }

    private final Kind kind;
    private final Runnable returnToList;
    private HeaderAndFooterLayout layout;
    private boolean startRefused;
    private boolean imported;
    private int seenSequence = -1;
    private String state = "";

    private BrowserWorldTransferScreen(Kind kind, Runnable returnToList) {
        super(BrowserI18n.text(kind == Kind.EXPORT ? "world.export_title" : "world.import_title"));
        this.kind = kind;
        this.returnToList = returnToList;
    }

    /** Starts exporting a world and shows the progress screen. */
    static void export(Runnable returnToList, String worldId, String levelName) {
        BrowserWorldTransferScreen screen = new BrowserWorldTransferScreen(Kind.EXPORT, returnToList);
        screen.startRefused = !BrowserWorldTransfer.startExport(worldId, levelName);
        BrowserScreens.show(screen);
    }

    /** Opens the browser's file picker for a world .zip and shows the progress screen. */
    static void startImport(Runnable returnToList) {
        BrowserWorldTransferScreen screen = new BrowserWorldTransferScreen(Kind.IMPORT, returnToList);
        screen.startRefused = !BrowserWorldTransfer.startImport();
        BrowserScreens.show(screen);
    }

    @Override
    protected void init() {
        state = startRefused ? "refused" : BrowserWorldTransfer.state();
        seenSequence = BrowserWorldTransfer.sequence();
        // A new layout per build: rebuildWidgets runs init again on every status change.
        layout = new HeaderAndFooterLayout(this);
        layout.addTitleHeader(this.title, this.font);
        LinearLayout contents = layout.addToContents(LinearLayout.vertical().spacing(8));
        contents.defaultCellSetting().alignHorizontallyCenter();
        contents.addChild(new MultiLineTextWidget(message(), this.font)
                .setMaxWidth(Math.min(TEXT_WIDTH, this.width - 40))
                .setCentered(true));
        Component button = switch (state) {
            case "picking" -> CommonComponents.GUI_CANCEL;
            case "running" -> null;
            default -> CommonComponents.GUI_DONE;
        };
        if (button != null) {
            layout.addToFooter(Button.builder(button, ignored -> onClose()).width(BUTTON_WIDTH).build());
        }
        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
    }

    private Component message() {
        String file = BrowserWorldTransfer.fileName();
        return switch (state) {
            case "picking" -> BrowserI18n.text("world.choose_zip");
            case "running" -> {
                int total = BrowserWorldTransfer.total();
                String verb = BrowserI18n.string(kind == Kind.EXPORT ? "world.exporting" : "world.importing");
                String progress = total > 0
                        ? BrowserWorldTransfer.done() + " / " + total + " files"
                        : BrowserI18n.string("world.reading");
                yield Component.literal(verb + (file.isEmpty() ? "" : " " + file) + "\n\n" + progress);
            }
            case "done" -> kind == Kind.EXPORT
                    ? BrowserI18n.text("world.saved_download", file)
                    : BrowserI18n.text("world.imported", BrowserWorldTransfer.worldId());
            case "cancelled" -> BrowserI18n.text("world.no_file");
            case "refused" -> Component.literal(BrowserWorldTransfer.available()
                            ? BrowserI18n.string("world.operation_busy")
                            : BrowserI18n.string("world.unavailable"))
                    .withStyle(ChatFormatting.RED);
            default -> {
                String error = BrowserWorldTransfer.error();
                yield BrowserI18n.text(kind == Kind.EXPORT ? "world.export_failed" : "world.import_failed")
                        .append(error.isEmpty() ? Component.empty() : Component.literal(":\n\n" + error))
                        .withStyle(ChatFormatting.RED);
            }
        };
    }

    @Override
    public void tick() {
        super.tick();
        if (startRefused || BrowserWorldTransfer.sequence() == seenSequence) {
            return;
        }
        if (kind == Kind.IMPORT && !imported && "done".equals(BrowserWorldTransfer.state())) {
            imported = true;
            // The page stored the world; load its level.dat and icon into the client's file
            // system so the reloaded world list shows it.
            BrowserFilePersistence.restoreWorldMetadata(BrowserWorldTransfer.worldId());
        }
        rebuildWidgets();
    }

    @Override
    protected void repositionElements() {
        if (layout != null) {
            layout.arrangeElements();
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return !"running".equals(state);
    }

    @Override
    public void onClose() {
        if ("running".equals(state)) {
            return;
        }
        if ("picking".equals(state) && !startRefused) {
            BrowserWorldTransfer.cancelPicking();
        }
        // returnToScreen reloads the world list, so an imported world appears selected-ready.
        returnToList.run();
    }
}
