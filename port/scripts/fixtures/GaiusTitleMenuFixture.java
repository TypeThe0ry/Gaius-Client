import dev.gaius.tools.patches.ui.UiPatches;
import java.nio.file.Files;
import java.nio.file.Path;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Checks the versioned title menu ABI on the actual vanilla input. */
public final class GaiusTitleMenuFixture {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[1]);
        UiPatches.patchTitleScreenBrowserFastMenus(args[0], output);
        ClassNode node = new ClassNode();
        new ClassReader(Files.readAllBytes(output)).accept(node, 0);
        boolean current = node.methods.stream().anyMatch(method -> method.name.equals("extractRenderState"));
        int button = 0;
        int firstRun = 0;
        for (var method : node.methods) {
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && call.owner.equals("dev/gaius/browser/BrowserProfileScreen")) {
                    if (method.name.equals("init") && call.name.equals("titleButton")) button++;
                    else if (method.name.equals("tick") && call.name.equals("titleTick")) firstRun++;
                    else throw new AssertionError("unexpected profile screen dependency");
                }
            }
        }
        if (button != (current ? 1 : 0) || firstRun != (current ? 1 : 0)) {
            throw new AssertionError("profile hooks do not match the GUI source set");
        }
        System.out.println("TITLE_MENU_OK " + args[2] + " modern=" + current
                + " profileButton=" + button + " firstRun=" + firstRun);
    }
}
