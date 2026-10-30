package dev.gaius.tools;

import dev.gaius.browser.BrowserTitleLayout;

/** Exercises title menu and lower-right text placement across the supported window and GUI-scale range. */
public final class BrowserTitleLayoutTest {
    private BrowserTitleLayoutTest() {
    }

    public static void main(String[] args) {
        System.out.println("BrowserTitleLayoutTest passed " + run() + " checks");
    }

    static int run() {
        int checks = 0;
        for (int windowWidth = 854; windowWidth <= 2560; windowWidth++) {
            for (int requestedScale = 1; requestedScale <= 4; requestedScale++) {
                int physicalHeight = Math.max(480, (int) Math.ceil(windowWidth * 9.0 / 16.0));
                int scale = actualScale(windowWidth, physicalHeight, requestedScale);
                int width = (int) Math.ceil(windowWidth / (double) scale);
                int height = (int) Math.ceil(physicalHeight / (double) scale);
                Menu menu = expectedMenu(width, height);
                String viewport = windowWidth + "px / GUI scale " + requestedScale + " (actual " + scale + ")";
                check(fits(menu.singleplayer(), width, height), "Singleplayer fits at " + viewport);
                check(fits(menu.multiplayer(), width, height), "Multiplayer fits at " + viewport);
                check(fits(menu.options(), width, height), "Options fits at " + viewport);
                check(fits(menu.quit(), width, height), "Quit fits at " + viewport);
                check(menu.singleplayer().y() < menu.multiplayer().y()
                                && menu.multiplayer().y() < menu.options().y()
                                && menu.options().y() == menu.quit().y(),
                        "menu rows are ordered at " + viewport);
                check(!overlaps(menu.singleplayer(), menu.multiplayer())
                                && !overlaps(menu.singleplayer(), menu.options())
                                && !overlaps(menu.singleplayer(), menu.quit())
                                && !overlaps(menu.multiplayer(), menu.options())
                                && !overlaps(menu.multiplayer(), menu.quit())
                                && !overlaps(menu.options(), menu.quit()),
                        "menu rectangles do not overlap at " + viewport);
                int versionWidth = Math.min(width - 8, 280);
                BrowserTitleLayout.Position about = BrowserTitleLayout.aboutButton(
                        width, height, versionWidth, 100, 9, 10);
                check(about.x() >= 2 && about.x() + about.width() <= width - 2,
                        "About fits horizontally at " + viewport);
                check(about.y() >= 2 && about.y() + about.height() <= height,
                        "About fits vertically at " + viewport);
                if (about.y() == height - 10) {
                    check(about.x() >= versionWidth + 4,
                            "same-row version and About labels stay apart at " + viewport);
                } else {
                    check(about.y() + about.height() <= height - 10,
                            "stacked version and About labels stay apart at " + viewport);
                }
                checks += 9;
            }
        }
        return checks;
    }

    private static boolean fits(BrowserTitleLayout.Position position, int width, int height) {
        return position.x() >= 0 && position.y() >= 0
                && position.x() + position.width() <= width
                && position.y() + position.height() <= height;
    }

    private static boolean overlaps(BrowserTitleLayout.Position a, BrowserTitleLayout.Position b) {
        return a.x() < b.x() + b.width() && a.x() + a.width() > b.x()
                && a.y() < b.y() + b.height() && a.y() + a.height() > b.y();
    }

    private static int actualScale(int width, int height, int requestedScale) {
        int scale = 1;
        while (scale < requestedScale && width / (scale + 1) >= 320 && height / (scale + 1) >= 240) {
            scale++;
        }
        return scale;
    }

    private static Menu expectedMenu(int width, int height) {
        int center = width / 2;
        // Mirrors the patched TitleScreen bytecode: height / 4 + 48, then 24px menu rows.
        int firstRow = height / 4 + 48;
        return new Menu(
                new BrowserTitleLayout.Position(center - 100, firstRow, 200, 20),
                new BrowserTitleLayout.Position(center - 100, firstRow + 24, 200, 20),
                new BrowserTitleLayout.Position(center - 98, firstRow + 48, 98, 20),
                new BrowserTitleLayout.Position(center + 2, firstRow + 48, 98, 20));
    }

    private record Menu(BrowserTitleLayout.Position singleplayer,
            BrowserTitleLayout.Position multiplayer,
            BrowserTitleLayout.Position options,
            BrowserTitleLayout.Position quit) {
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
