package dev.gaius.tools;

import com.mojang.datafixers.types.Type;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;

/** Exercises historical NBT migrations with the profile's actual game and data-fixer JARs. */
public final class BrowserDataFixerMigrationTest {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        SharedConstants.tryDetectVersion();
        var fixer = DataFixers.getDataFixer();
        CompoundTag options = new CompoundTag();
        options.putString("lang", "EN_US");
        options.putString("renderDistance", "8");
        CompoundTag migratedOptions = DataFixTypes.OPTIONS.updateToCurrentVersion(fixer, options, 100);
        check(migratedOptions.getString("lang").orElse("").equals("en_us"), "language migration ran");
        write(output, "options", migratedOptions);

        CompoundTag chunk = oldChunk();
        CompoundTag migratedChunk = DataFixTypes.CHUNK.updateToCurrentVersion(fixer, chunk, 1631);
        check(!migratedChunk.contains("Level"), "chunk fields moved out of the legacy Level wrapper");
        check(migratedChunk.toString().contains("block_states"), "chunk block-state migration ran");
        check(migratedChunk.getInt("xPos").orElse(-1) == 0, "chunk coordinates are preserved");
        write(output, "chunk", migratedChunk);

        CompoundTag player = new CompoundTag();
        player.putInt("Dimension", 0);
        player.putLong("UUIDMost", 0);
        player.putLong("UUIDLeast", 1);
        ListTag inventory = new ListTag();
        CompoundTag item = new CompoundTag();
        item.putString("id", "minecraft:stone");
        item.putByte("Count", (byte) 64);
        item.putByte("Slot", (byte) 0);
        inventory.add(item);
        player.put("Inventory", inventory);
        CompoundTag migratedPlayer = DataFixTypes.PLAYER.updateToCurrentVersion(fixer, player, 1631);
        check(migratedPlayer.toString().contains("minecraft:stone"), "inventory item is preserved");
        check(migratedPlayer.getString("Dimension").orElse("").equals("minecraft:overworld"),
                "player dimension migration ran");
        write(output, "player", migratedPlayer);

        Field field = Type.class.getDeclaredField("REWRITE_CACHE");
        field.setAccessible(true);
        System.out.println("completedEntries=" + ((Map<?, ?>) field.get(null)).size());
    }

    private static CompoundTag oldChunk() {
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", 1631);
        CompoundTag level = new CompoundTag();
        level.putInt("xPos", 0);
        level.putInt("zPos", 0);
        level.putString("Status", "full");
        level.putLong("LastUpdate", 0);
        level.putLong("InhabitedTime", 0);
        level.putIntArray("Biomes", new int[256]);
        ListTag sections = new ListTag();
        CompoundTag section = new CompoundTag();
        section.putByte("Y", (byte) 0);
        ListTag palette = new ListTag();
        CompoundTag stone = new CompoundTag();
        stone.putString("Name", "minecraft:stone");
        palette.add(stone);
        section.put("Palette", palette);
        section.putLongArray("BlockStates", new long[256]);
        sections.add(section);
        level.put("Sections", sections);
        level.put("Entities", new ListTag());
        level.put("TileEntities", new ListTag());
        level.put("TileTicks", new ListTag());
        level.put("LiquidTicks", new ListTag());
        CompoundTag structures = new CompoundTag();
        structures.put("Starts", new CompoundTag());
        structures.put("References", new CompoundTag());
        level.put("Structures", structures);
        root.put("Level", level);
        return root;
    }

    private static void write(Path output, String name, CompoundTag value) throws Exception {
        Path path = output.resolve(name + ".nbt");
        NbtIo.write(value, path);
        byte[] bytes = Files.readAllBytes(path);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        System.out.println(name + " bytes=" + bytes.length + " sha256=" + hash);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
