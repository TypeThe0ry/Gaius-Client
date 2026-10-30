package dev.gaius.browser;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelSummary;
import org.teavm.jso.JSBody;

/**
 * World export and import on the vanilla world selection screen. UiPatches'
 * patchSelectWorldScreenWorldTransfer calls these hooks from SelectWorldScreen; the page script
 * port/web/launcher/world-archive.js ({@code window.__gaiusWorldArchive}) does the storage and zip
 * work, and {@link BrowserWorldTransferScreen} shows its progress.
 *
 * <p>The vanilla footer is a four-column grid: Play Selected World and Create New World in the
 * first row, Edit, Delete, Re-Create and Back (71 wide) in the second. The second row becomes one
 * horizontal row with Export before Back, five buttons as wide in total as the first row.
 */
public final class BrowserWorldTransfer {
    static final Component EXPORT = BrowserI18n.text("world.export");
    private static final int GRID_COLUMNS = 4;
    private static final int ROW_BUTTON_WIDTH = 56;
    private static final int ROW_SPACING = 7;

    private static GridLayout.RowHelper footerRow;
    private static LinearLayout footerLine;
    private static Button exportButton;

    private BrowserWorldTransfer() {
    }

    /** Adds one of Edit, Delete and Re-Create to the shared second footer row. */
    public static LayoutElement footerButton(GridLayout.RowHelper row, LayoutElement element) {
        if (element instanceof AbstractWidget widget) {
            widget.setWidth(ROW_BUTTON_WIDTH);
        }
        return line(row).addChild(element);
    }

    /** Adds Export, then Back, and ends the second footer row. */
    public static LayoutElement footerBackButton(
            GridLayout.RowHelper row, LayoutElement back, Screen screen, WorldSelectionList list) {
        LinearLayout line = line(row);
        Button export = Button.builder(EXPORT, ignored -> export(list))
                .width(ROW_BUTTON_WIDTH)
                .build();
        export.setTooltip(Tooltip.create(BrowserI18n.text("world.export_tooltip")));
        export.active = false;
        exportButton = export;
        line.addChild(export);
        if (back instanceof AbstractWidget widget) {
            widget.setWidth(ROW_BUTTON_WIDTH);
        }
        LayoutElement added = line.addChild(back);
        footerRow = null;
        footerLine = null;
        return added;
    }

    /** Vanilla's updateButtonStatus: Export follows the selection like Edit and Delete. */
    public static void updateExportButton(LevelSummary summary) {
        if (exportButton != null) {
            exportButton.active = summary != null && !summary.isLocked() && available();
        }
    }

    /** Create New World first asks whether to create or import a world. */
    public static void openCreateChoice(Minecraft minecraft, Runnable returnToList) {
        BrowserScreens.show(new BrowserCreateWorldChoiceScreen(minecraft, returnToList));
    }

    private static LinearLayout line(GridLayout.RowHelper row) {
        if (footerRow != row || footerLine == null) {
            footerRow = row;
            footerLine = row.addChild(LinearLayout.horizontal().spacing(ROW_SPACING), GRID_COLUMNS);
        }
        return footerLine;
    }

    private static void export(WorldSelectionList list) {
        list.getSelectedOpt().ifPresent(entry -> {
            LevelSummary summary = entry.getLevelSummary();
            BrowserWorldTransferScreen.export(
                    list::returnToScreen, summary.getLevelId(), summary.getLevelName());
        });
    }

    /** True when the page has the world archive script. */
    @JSBody(script = """
            var archive = globalThis.__gaiusWorldArchive;
            return !!(archive && typeof archive.startExport === 'function');
            """)
    static native boolean available();

    @JSBody(params = {"worldId", "levelName"}, script = """
            var archive = globalThis.__gaiusWorldArchive;
            return !!(archive && archive.startExport(String(worldId || ''), String(levelName || '')));
            """)
    static native boolean startExport(String worldId, String levelName);

    @JSBody(script = """
            var archive = globalThis.__gaiusWorldArchive;
            return !!(archive && archive.startImport());
            """)
    static native boolean startImport();

    @JSBody(script = """
            var archive = globalThis.__gaiusWorldArchive;
            if (archive) archive.cancelPicking();
            """)
    static native void cancelPicking();

    @JSBody(script = """
            var archive = globalThis.__gaiusWorldArchive;
            return archive ? String(archive.status.state || '') : 'error';
            """)
    static native String state();

    @JSBody(script = """
            var archive = globalThis.__gaiusWorldArchive;
            return archive ? (archive.status.sequence | 0) : 0;
            """)
    static native int sequence();

    @JSBody(script = """
            var archive = globalThis.__gaiusWorldArchive;
            return archive ? (archive.status.done | 0) : 0;
            """)
    static native int done();

    @JSBody(script = """
            var archive = globalThis.__gaiusWorldArchive;
            return archive ? (archive.status.total | 0) : 0;
            """)
    static native int total();

    @JSBody(script = """
            var archive = globalThis.__gaiusWorldArchive;
            return archive ? String(archive.status.error || '')
                : 'World archives are not available on this page';
            """)
    static native String error();

    @JSBody(script = """
            var archive = globalThis.__gaiusWorldArchive;
            return archive ? String(archive.status.worldId || '') : '';
            """)
    static native String worldId();

    @JSBody(script = """
            var archive = globalThis.__gaiusWorldArchive;
            return archive ? String(archive.status.fileName || '') : '';
            """)
    static native String fileName();
}
