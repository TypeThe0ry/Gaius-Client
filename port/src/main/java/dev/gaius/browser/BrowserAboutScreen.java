package dev.gaius.browser;

import java.net.URI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.FittingMultiLineTextWidget;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.teavm.jso.JSBody;

/**
 * The "About Gaius" screen, opened by clicking the About button in the title screen's lower-right
 * corner. It follows vanilla's CreditsAndAttributionScreen: a title header, a column of 210 wide
 * buttons spaced 8 apart, and a 200 wide Done button in the footer. The license and third-party
 * notice buttons open {@link Document}, a header/footer screen around vanilla's scrolling
 * FittingMultiLineTextWidget. The terms come from the build itself: the portable page's inert
 * {@code gaius-license-notice} block, otherwise the site's {@code THIRD-PARTY-NOTICES.txt}.
 */
public final class BrowserAboutScreen extends Screen {
    /** The title-screen label keeps vanilla's version, font, colour and position. */
    private static final String DEV_VERSION = "v0.4.0-dev";
    private static final URI EULA = URI.create("https://www.minecraft.net/eula");
    private static final int BUTTON_WIDTH = 210;
    private static final int BUTTON_SPACING = 8;
    private static final int TEXT_WIDTH = 310;

    private static String titleVanilla = "";
    private static String titleVersion = "";

    private final Screen lastScreen;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);

    public BrowserAboutScreen(Screen lastScreen) {
        super(BrowserI18n.text("about.title"));
        this.lastScreen = lastScreen;
    }

    /**
     * Rewrites the title screen's version text (vanilla draws it with its own font, colour and
     * position; only the string changes). Called for every title frame from the patched
     * {@code TitleScreen} render method.
     */
    public static String titleVersion(String vanilla) {
        String minecraftVersion = vanilla.startsWith("Minecraft ")
                ? vanilla.substring("Minecraft ".length()) : vanilla;
        minecraftVersion = minecraftVersion.replaceFirst("\\s*\\(Modded\\)$", "");
        String version = normalizeVersion(buildVersion().trim());
        if (version.isEmpty()) {
            version = DEV_VERSION;
        } else if (!version.startsWith("v")) {
            version = "v" + version;
        }
        String localized = BrowserI18n.string("about.title_version", minecraftVersion, version);
        if (!vanilla.equals(titleVanilla) || !localized.equals(titleVersion)) {
            titleVanilla = vanilla;
            titleVersion = localized;
            publishTitleVersion(titleVersion);
        }
        return titleVersion;
    }

    /** Opens the About screen from the title screen's lower-right button. */
    public static void open(Screen title) {
        BrowserScreens.show(new BrowserAboutScreen(title));
    }

    /** Fits the localized title-screen About button beside the version label. */
    public static void fitCopyrightButton(Screen title, GuiEventListener listener) {
        if (!(listener instanceof AbstractWidget button)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        String version = titleVersion;
        int versionWidth = version.isEmpty() ? title.width : minecraft.font.width(version);
        Component about = BrowserI18n.text("title_about");
        int textWidth = minecraft.font.width(about);
        BrowserTitleLayout.Position position = BrowserTitleLayout.aboutButton(
                title.width, title.height, versionWidth, textWidth, minecraft.font.lineHeight, button.getHeight());
        button.setMessage(about);
        button.setWidth(position.width());
        button.setX(position.x());
        button.setY(position.y());
    }

    private static String normalizeVersion(String version) {
        return version.replaceFirst("-pre\\.(\\d+)(?=$|[+])", "-pre$1");
    }

    @Override
    protected void init() {
        layout.addTitleHeader(this.title, this.font);
        LinearLayout contents = layout.addToContents(LinearLayout.vertical().spacing(BUTTON_SPACING));
        contents.defaultCellSetting().alignHorizontallyCenter();
        contents.addChild(new MultiLineTextWidget(BrowserI18n.text("about.disclaimer"), this.font)
                .setMaxWidth(TEXT_WIDTH)
                .setCentered(true));
        contents.addChild(Button.builder(BrowserI18n.text("about.license"),
                        ignored -> BrowserScreens.show(new Document(this, BrowserI18n.text("about.license"), Notices.LICENSE)))
                .width(BUTTON_WIDTH)
                .build());
        contents.addChild(Button.builder(BrowserI18n.text("about.third_party"),
                        ignored -> BrowserScreens.show(new Document(this, BrowserI18n.text("about.third_party"), Notices.THIRD_PARTY)))
                .width(BUTTON_WIDTH)
                .build());
        contents.addChild(Button.builder(BrowserI18n.text("about.eula"),
                        ConfirmLinkScreen.confirmLink(this, EULA))
                .width(BUTTON_WIDTH)
                .build());
        layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, ignored -> onClose()).width(200).build());
        layout.arrangeElements();
        layout.visitWidgets(this::addRenderableWidget);
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
    }

    @Override
    public void onClose() {
        BrowserScreens.show(lastScreen);
    }

    static String disclaimer() {
        return BrowserI18n.string("about.disclaimer");
    }

    /** A scrolling plain-text document with a title header and a Done footer. */
    static final class Document extends Screen {
        private final Screen lastScreen;
        private final int part;
        private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
        private FittingMultiLineTextWidget text;
        private boolean loaded;

        Document(Screen lastScreen, Component title, int part) {
            super(title);
            this.lastScreen = lastScreen;
            this.part = part;
        }

        @Override
        protected void init() {
            layout.addTitleHeader(this.title, this.font);
            String body = Notices.text(part);
            loaded = body != null;
            text = layout.addToContents(new FittingMultiLineTextWidget(0, 0, textWidth(), textHeight(),
                    Component.literal(body == null ? BrowserI18n.string("about.loading") : body), this.font));
            layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, ignored -> onClose()).width(200).build());
            layout.arrangeElements();
            layout.visitWidgets(this::addRenderableWidget);
        }

        @Override
        public void tick() {
            super.tick();
            if (!loaded) {
                String body = Notices.text(part);
                if (body != null) {
                    loaded = true;
                    text.setMessage(Component.literal(body));
                    repositionElements();
                }
            }
        }

        @Override
        protected void repositionElements() {
            if (text != null) {
                text.setWidth(textWidth());
                text.setHeight(textHeight());
            }
            layout.arrangeElements();
        }

        private int textWidth() {
            return Math.max(100, Math.min(this.width - 40, TEXT_WIDTH + 90));
        }

        private int textHeight() {
            return Math.max(40, layout.getContentHeight() - 8);
        }

        @Override
        public void onClose() {
            BrowserScreens.show(lastScreen);
        }
    }

    /**
     * The license terms shipped with this build, split into the Gaius license and the
     * third-party notices. The text is the build scripts' {@code license_text}: one section per
     * file (NOTICE, LICENSE, LICENSES/*.txt), each headed by its path underlined with '='.
     */
    static final class Notices {
        static final int LICENSE = 0;
        static final int THIRD_PARTY = 1;
        private static final String PENDING = "<gaius-notice-pending>";
        private static String[] parts;

        private Notices() {
        }

        /** The requested part, or null while the site's notice file is still loading. */
        static String text(int part) {
            if (parts == null) {
                String all = loadNotices();
                if (PENDING.equals(all)) {
                    return null;
                }
                parts = split(all);
            }
            return parts[part];
        }

        static String[] split(String all) {
            String[] lines = all.replace("\r\n", "\n").split("\n", -1);
            StringBuilder license = new StringBuilder();
            StringBuilder thirdParty = new StringBuilder();
            StringBuilder current = null;
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                boolean header = i + 1 < lines.length && isSectionName(line)
                        && lines[i + 1].length() == line.length() && underline(lines[i + 1]);
                if (header) {
                    current = line.equals("LICENSE") ? license : thirdParty;
                    if (current.length() > 0) {
                        current.append('\n');
                    }
                }
                if (current == null) {
                    current = thirdParty;
                }
                current.append(line).append('\n');
            }
            String noTerms = BrowserI18n.string("about.license_missing");
            String licenseText = license.toString().trim();
            String thirdPartyText = thirdParty.toString().trim();
            return new String[] {
                licenseText.isEmpty() ? noTerms : licenseText,
                thirdPartyText.isEmpty() ? noTerms : thirdPartyText
            };
        }

        private static boolean underline(String line) {
            for (int i = 0; i < line.length(); i++) {
                if (line.charAt(i) != '=') {
                    return false;
                }
            }
            return !line.isEmpty();
        }

        private static boolean isSectionName(String line) {
            return line.equals("NOTICE") || line.equals("LICENSE")
                    || (line.startsWith("LICENSES/") && line.endsWith(".txt"));
        }
    }

    /**
     * The notice text of this build: the portable page's inert block, else the site's
     * THIRD-PARTY-NOTICES.txt (fetched once, same origin). {@code Notices.PENDING} while the fetch
     * runs; an empty string when neither exists.
     */
    @JSBody(script = """
            var g = globalThis;
            if (typeof g.__gaiusLicenseNotice === 'string') {
              return g.__gaiusLicenseNotice;
            }
            var block = typeof document !== 'undefined' ? document.getElementById('gaius-license-notice') : null;
            if (block) {
              g.__gaiusLicenseNotice = String(block.textContent || '');
              return g.__gaiusLicenseNotice;
            }
            if (!g.__gaiusLicenseNoticeFetch) {
              g.__gaiusLicenseNoticeFetch = true;
              try {
                fetch('THIRD-PARTY-NOTICES.txt', {cache: 'no-cache'})
                  .then(function (response) { return response.ok ? response.text() : ''; })
                  .then(function (text) { g.__gaiusLicenseNotice = String(text || ''); },
                        function () { g.__gaiusLicenseNotice = ''; });
              } catch (ignored) {
                g.__gaiusLicenseNotice = '';
              }
            }
            return '<gaius-notice-pending>';
            """)
    private static native String loadNotices();

    /** Exposes the rewritten title text to the page (window.__gaiusTitleVersionText) for tests. */
    @JSBody(params = {"text"}, script = "globalThis.__gaiusTitleVersionText = text;")
    private static native void publishTitleVersion(String text);

    /** Reads the release version injected into the page by postprocess-index-html.py. */
    @JSBody(script = "var value = globalThis.__gaiusVersion;"
            + "return typeof value === 'string' ? value : '';" )
    private static native String buildVersion();
}
