package dev.gaius.tools;

import dev.gaius.tools.patches.ui.UiPatches;
import java.lang.reflect.Method;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Runs the title-screen icon-row removal (part of UiPatches.patchTitleScreenBrowserFastMenus) on
 * TitleScreen.init fixtures with the vanilla statement order of both layouts. 26.x puts the
 * friends, language and accessibility icons on their own row; 1.21.11 puts the language and
 * accessibility icons beside Options and Quit Game, and advances the row cursor (l += 36) inside
 * the language icon's setPosition arguments. Only the icons may go: Options, Quit Game and the
 * row cursor that places them stay.
 */
final class TitleScreenIconRowPatchTest {
    private static final String TITLE = "net/minecraft/client/gui/screens/TitleScreen";
    private static final String COMMON = "net/minecraft/client/gui/components/CommonButtons";
    private static final String ICON = "net/minecraft/client/gui/components/SpriteIconButton";
    private static final String FRIENDS = "net/minecraft/client/gui/components/FriendsButton";
    private static final String LISTENER = "net/minecraft/client/gui/components/events/GuiEventListener";
    private static final String PRESS = "net/minecraft/client/gui/components/Button$OnPress";
    private static final int ROW = 4;
    private static final int COLUMN = 6;
    private static int checks;

    private TitleScreenIconRowPatchTest() {
    }

    static int run() throws Exception {
        checks = 0;
        MethodNode legacy = patch(legacyInit());
        check(calls(legacy, COMMON) == 0, "1.21.11 language and accessibility icons are removed");
        check(calls(legacy, "setPosition") == 0, "1.21.11 icon positions are removed");
        check(literal(legacy, "menu.options") && literal(legacy, "menu.quit"),
                "1.21.11 keeps Options and Quit Game");
        check(calls(legacy, "addRenderableWidget") == 3,
                "1.21.11 still registers Options, Quit Game and the copyright button");
        check(increments(legacy, ROW, 36) == 1 && increments(legacy, ROW, 24) == 0,
                "1.21.11 keeps the l += 36 row step that places Options and Quit Game");
        check(before(legacy, ROW, "menu.options"),
                "1.21.11 advances the row cursor before Options reads it");

        MethodNode current = patch(currentInit());
        check(calls(current, COMMON) == 0, "26.x friends, language and accessibility icons are removed");
        check(calls(current, "setPosition") == 0, "26.x icon positions are removed");
        check(literal(current, "menu.options") && literal(current, "menu.quit"),
                "26.x keeps Options and Quit Game");
        check(calls(current, "addRenderableWidget") == 3,
                "26.x still registers Options, Quit Game and the copyright button");
        check(increments(current, ROW, 24) == 1,
                "26.x drops the icon row step and keeps the Options row step");
        check(increments(current, COLUMN, 1) == 0, "26.x drops the icon column counter");
        return checks;
    }

    private static MethodNode patch(InsnList code) throws Exception {
        ClassNode node = new ClassNode();
        node.name = TITLE;
        MethodNode init = new MethodNode(Opcodes.ACC_PROTECTED, "init", "()V", null, null);
        init.instructions = code;
        node.methods.add(init);
        Method remove = UiPatches.class.getDeclaredMethod("removeTitleScreenIconButtons", ClassNode.class);
        remove.setAccessible(true);
        remove.invoke(null, node);
        return init;
    }

    /** 1.21.11: menu rows, language icon (l += 36), Options, Quit Game, accessibility icon. */
    private static InsnList legacyInit() {
        InsnList code = new InsnList();
        menu(code);
        icon(code, "language", ICON, 5);
        code.add(new VarInsnNode(Opcodes.ALOAD, 5));
        x(code, -124);
        code.add(new IincInsnNode(ROW, 36));
        code.add(new VarInsnNode(Opcodes.ILOAD, ROW));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ICON, "setPosition", "(II)V", false));
        button(code, "menu.options", false);
        button(code, "menu.quit", false);
        icon(code, "accessibility", ICON, 6);
        code.add(new VarInsnNode(Opcodes.ALOAD, 6));
        x(code, 104);
        code.add(new VarInsnNode(Opcodes.ILOAD, ROW));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ICON, "setPosition", "(II)V", false));
        copyright(code);
        return code;
    }

    /** 26.x: menu rows, l += 24, the icon row, l += 24 inside Options, Quit Game. */
    private static InsnList currentInit() {
        InsnList code = new InsnList();
        menu(code);
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new VarInsnNode(Opcodes.ISTORE, COLUMN));
        code.add(new IincInsnNode(ROW, 24));
        icon(code, "friends", FRIENDS, 7);
        currentPosition(code, FRIENDS, 7);
        icon(code, "language", ICON, 8);
        currentPosition(code, ICON, 8);
        icon(code, "accessibility", ICON, 9);
        currentPosition(code, ICON, 9);
        button(code, "menu.options", true);
        button(code, "menu.quit", false);
        copyright(code);
        return code;
    }

    private static void menu(InsnList code) {
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, TITLE, "height", "I"));
        code.add(new InsnNode(Opcodes.ICONST_4));
        code.add(new InsnNode(Opcodes.IDIV));
        code.add(new VarInsnNode(Opcodes.ISTORE, ROW));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, ROW));
        code.add(new IntInsnNode(Opcodes.BIPUSH, 24));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, TITLE, "createNormalMenuOptions", "(II)I", false));
        code.add(new VarInsnNode(Opcodes.ISTORE, ROW));
    }

    private static void icon(InsnList code, String factory, String type, int local) {
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new IntInsnNode(Opcodes.BIPUSH, 20));
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, COMMON, factory,
                "(IL" + PRESS + ";Z)L" + type + ";", false));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, TITLE, "addRenderableWidget",
                "(L" + LISTENER + ";)L" + LISTENER + ";", false));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, type));
        code.add(new VarInsnNode(Opcodes.ASTORE, local));
    }

    private static void currentPosition(InsnList code, String type, int local) {
        code.add(new VarInsnNode(Opcodes.ALOAD, local));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new IincInsnNode(COLUMN, 1));
        code.add(new VarInsnNode(Opcodes.ILOAD, COLUMN));
        code.add(new InsnNode(Opcodes.ICONST_3));
        code.add(new IntInsnNode(Opcodes.BIPUSH, 20));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, TITLE, "getHorizontalPosition", "(III)I", false));
        code.add(new VarInsnNode(Opcodes.ILOAD, ROW));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, type, "setPosition", "(II)V", false));
    }

    private static void x(InsnList code, int offset) {
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, TITLE, "width", "I"));
        code.add(new InsnNode(Opcodes.ICONST_2));
        code.add(new InsnNode(Opcodes.IDIV));
        code.add(new IntInsnNode(Opcodes.BIPUSH, offset));
        code.add(new InsnNode(Opcodes.IADD));
    }

    /** this.addRenderableWidget(button(label, x, l)), with 26.x's l += 24 inside the arguments. */
    private static void button(InsnList code, String label, boolean nextRow) {
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new LdcInsnNode(label));
        x(code, -100);
        if (nextRow) {
            code.add(new IincInsnNode(ROW, 24));
        }
        code.add(new VarInsnNode(Opcodes.ILOAD, ROW));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "fixture/Buttons", "button",
                "(Ljava/lang/String;II)L" + LISTENER + ";", false));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, TITLE, "addRenderableWidget",
                "(L" + LISTENER + ";)L" + LISTENER + ";", false));
        code.add(new InsnNode(Opcodes.POP));
    }

    private static void copyright(InsnList code) {
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, TITLE, "addRenderableWidget",
                "(L" + LISTENER + ";)L" + LISTENER + ";", false));
        code.add(new InsnNode(Opcodes.POP));
        code.add(new InsnNode(Opcodes.RETURN));
    }

    private static int calls(MethodNode method, String ownerOrName) {
        int found = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && (call.owner.equals(ownerOrName) || call.name.equals(ownerOrName))) {
                found++;
            }
        }
        return found;
    }

    private static boolean literal(MethodNode method, String value) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof LdcInsnNode ldc && value.equals(ldc.cst)) {
                return true;
            }
        }
        return false;
    }

    private static int increments(MethodNode method, int local, int amount) {
        int found = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof IincInsnNode increment && increment.var == local
                    && increment.incr == amount) {
                found++;
            }
        }
        return found;
    }

    private static boolean before(MethodNode method, int local, String label) {
        boolean advanced = false;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof IincInsnNode increment && increment.var == local
                    && instruction.getPrevious() instanceof VarInsnNode store
                    && store.getOpcode() == Opcodes.ISTORE) {
                advanced = true;
            }
            if (instruction instanceof LdcInsnNode ldc && label.equals(ldc.cst)) {
                return advanced;
            }
        }
        return false;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
        checks++;
    }
}
