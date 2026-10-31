import dev.gaius.browser.BrowserIntegratedServerMain;

/**
 * Runs the patched vanilla server Main on a JVM the way a parked browser prewarm does: Main starts
 * with the placeholder world, runs SharedConstants, the option parser, CrashReport.preload and
 * Bootstrap in vanilla order, then reaches BrowserIntegratedServerMain.resumeAfterBootstrap (the
 * shim checks the holders and the re-parse there and ends the run).
 */
public final class ParkedServerMainSmoke {
    private ParkedServerMainSmoke() {
    }

    public static void main(String[] args) {
        try {
            net.minecraft.server.Main.main(new String[] {
                    "--nogui", "--universe", "/gaius/saves",
                    "--world", BrowserIntegratedServerMain.PARKED_WORLD, "--port", "25565"});
        } catch (BrowserIntegratedServerMain.Complete complete) {
            System.out.println(complete.getMessage());
            System.exit(0);
        }
        System.out.println("PARKED_MAIN_JVM_SMOKE FAIL: Main returned without reaching resumeAfterBootstrap");
        System.exit(1);
    }
}
