import com.mojang.serialization.Dynamic;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.OptionalLong;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;

/** 26.3 vanilla UI command defaults and optional browser level.dat comparisons. */
public final class VanillaWorldCommandSaveCheck {
    private static final java.io.PrintStream REPORT = System.out;

    // Only the UI's world-mode dependencies are substituted. isAllowCommands is
    // the unmodified vanilla implementation loaded directly from client-named.jar.
    public static final class UiState extends WorldCreationUiState {
        SelectedGameMode mode;
        boolean debug;

        private UiState() {
            super(null, null, Optional.empty(), OptionalLong.empty());
        }

        @Override public boolean isDebug() { return debug; }
        @Override public boolean isHardcore() { return mode == SelectedGameMode.HARDCORE; }
        @Override public SelectedGameMode getGameMode() { return mode; }
    }

    private static UiState state(String mode, Boolean explicit) throws Exception {
        // Skip resource/world-generation construction; no constructor or setters
        // run against the deliberately absent rendering and world-generation state.
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeClass.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        Object unsafe = singleton.get(null);
        UiState state = (UiState) unsafeClass.getMethod("allocateInstance", Class.class)
                .invoke(unsafe, UiState.class);
        state.mode = WorldCreationUiState.SelectedGameMode.valueOf(mode);
        Field commands = WorldCreationUiState.class.getDeclaredField("allowCommands");
        commands.setAccessible(true);
        commands.set(state, explicit);
        return state;
    }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        int checks = 0;
        for (String mode : new String[] {"SURVIVAL", "HARDCORE", "CREATIVE"}) {
            for (Boolean explicit : new Boolean[] {null, false, true}) {
                UiState ui = state(mode, explicit);
                boolean expected = !mode.equals("HARDCORE")
                        && (explicit == null ? mode.equals("CREATIVE") : explicit);
                require(ui.isAllowCommands() == expected, "vanilla mode/default matrix changed");
                REPORT.println("VANILLA_COMMANDS mode=" + mode + " explicit=" + explicit
                        + " allowCommands=" + ui.isAllowCommands());
                checks++;
            }
        }
        for (String argument : args) {
            int separator = argument.indexOf('=');
            require(separator > 0, "expected CASE=level.dat");
            String name = argument.substring(0, separator);
            Path path = Path.of(argument.substring(separator + 1));
            String mode = name.equals("audio-gpu") ? "CREATIVE" : "SURVIVAL";
            Boolean explicit = name.equals("survival-commands") ? Boolean.TRUE : null;
            UiState vanilla = state(mode, explicit);
            CompoundTag expected = new CompoundTag();
            expected.putBoolean("allowCommands", vanilla.isAllowCommands());
            expected.putInt("GameType", mode.equals("CREATIVE") ? 1 : 0);
            CompoundTag expectedDifficulty = new CompoundTag();
            expectedDifficulty.putBoolean("hardcore", false);
            expected.put("difficulty_settings", expectedDifficulty);
            CompoundTag root = NbtIo.readCompressed(path, NbtAccounter.create(16 * 1024 * 1024));
            require(root.get("Data") instanceof CompoundTag, "level.dat has no Data compound");
            CompoundTag data = (CompoundTag) root.get("Data");
            CompoundTag actual = new CompoundTag();
            require(data.get("allowCommands") instanceof ByteTag, "Data.allowCommands is not a byte");
            require(data.get("GameType") instanceof IntTag, "Data.GameType is not an int");
            require(data.get("difficulty_settings") instanceof CompoundTag,
                    "Data.difficulty_settings is not a compound");
            CompoundTag difficulty = (CompoundTag) data.get("difficulty_settings");
            require(difficulty.get("hardcore") instanceof ByteTag,
                    "Data.difficulty_settings.hardcore is not a byte");
            CompoundTag actualDifficulty = new CompoundTag();
            actualDifficulty.put("hardcore", difficulty.get("hardcore"));
            actual.put("difficulty_settings", actualDifficulty);
            for (String key : new String[] {"allowCommands", "GameType"}) {
                actual.put(key, data.get(key));
            }
            require(expected.equals(actual), "browser level.dat differs from vanilla UI: expected="
                    + expected + " actual=" + actual);
            LevelSettings settings = LevelSettings.parse(
                    new Dynamic<>(NbtOps.INSTANCE, data), WorldDataConfiguration.DEFAULT);
            require(settings.allowCommands() == vanilla.isAllowCommands(), "vanilla reader disagrees");
            require(java.util.Arrays.equals(encoded(expected), encoded(actual)), "NBT field bytes differ");
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded(actual)));
            REPORT.println("COMMAND_SAVE_MATCH case=" + name + " fields=" + actual + " sha256=" + hash);
            checks++;
        }
        REPORT.println("VANILLA_WORLD_COMMAND_SAVE_CHECK PASS checks=" + checks);
    }

    private static byte[] encoded(CompoundTag tag) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NbtIo.write(tag, new DataOutputStream(bytes));
        return bytes.toByteArray();
    }

    private static void require(boolean passed, String message) {
        if (!passed) throw new AssertionError(message);
    }
}
