package dev.gaius.browser;

/** Pure geometry for the title screen's lower-right About button. */
public final class BrowserTitleLayout {
    private BrowserTitleLayout() {
    }

    public static Position aboutButton(
            int screenWidth, int screenHeight, int versionWidth, int labelWidth, int lineHeight, int buttonHeight) {
        int available = Math.max(0, screenWidth - versionWidth - 6);
        int y = screenHeight - 10;
        if (labelWidth > available) {
            y -= lineHeight + 1;
        }
        return new Position(
                Math.max(2, screenWidth - labelWidth - 2),
                Math.max(2, y),
                labelWidth,
                buttonHeight);
    }

    public record Position(int x, int y, int width, int height) {
    }
}
