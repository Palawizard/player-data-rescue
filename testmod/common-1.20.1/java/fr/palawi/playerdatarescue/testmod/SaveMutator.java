package fr.palawi.playerdatarescue.testmod;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs right after the game wrote a player file. The game writes real data first (an item, an
 * effect, a vehicle given through commands), then this only renames ids to a mod that is not
 * installed, which is what removing that mod does to a save.
 */
public final class SaveMutator {
    private static final Logger LOGGER = LoggerFactory.getLogger("pdrtest");

    private SaveMutator() {}

    public static void afterSave(String playerName, File playerDir, String uuid) {
        File file = new File(playerDir, uuid + ".dat");
        try {
            boolean rename = TestModes.has(playerName, TestModes.RENAME_ITEM)
                    || TestModes.has(playerName, TestModes.RENAME_EFFECT)
                    || TestModes.has(playerName, TestModes.RENAME_VEHICLE);
            if (rename) {
                CompoundTag root = NbtIo.readCompressed(file);
                if (TestModes.has(playerName, TestModes.RENAME_ITEM)) {
                    renameItem(playerName, root);
                }
                if (TestModes.has(playerName, TestModes.RENAME_EFFECT)) {
                    renameEffects(playerName, root);
                }
                if (TestModes.has(playerName, TestModes.RENAME_VEHICLE)) {
                    renameVehicle(playerName, root);
                }
                NbtIo.writeCompressed(root, file);
            }
            if (TestModes.has(playerName, TestModes.TRUNCATE) || TestModes.has(playerName, TestModes.TRUNCATE_BOTH)) {
                truncate(playerName, file);
            }
            if (TestModes.has(playerName, TestModes.TRUNCATE_BOTH)) {
                truncate(playerName, new File(playerDir, uuid + ".dat_old"));
            }
        } catch (IOException e) {
            throw new IllegalStateException("pdrtest: cannot change " + file, e);
        }
    }

    private static void renameItem(String playerName, CompoundTag root) {
        ListTag inventory = root.getList("Inventory", Tag.TAG_COMPOUND);
        for (int i = 0; i < inventory.size(); i++) {
            CompoundTag entry = inventory.getCompound(i);
            if (entry.getByte("Slot") == TestModes.INVENTORY_SLOT) {
                String before = entry.getString("id");
                entry.putString("id", TestModes.REMOVED_ITEM);
                LOGGER.info(
                        "{}renamed the item in slot 17 of {} from {} to {}",
                        TestModes.LOG_PREFIX,
                        playerName,
                        before,
                        TestModes.REMOVED_ITEM);
                return;
            }
        }
        LOGGER.warn("{}{} has no item in slot 17", TestModes.LOG_PREFIX, playerName);
    }

    private static void renameEffects(String playerName, CompoundTag root) {
        ListTag effects = root.getList("ActiveEffects", Tag.TAG_COMPOUND);
        for (int i = 0; i < effects.size(); i++) {
            CompoundTag effect = effects.getCompound(i);
            // 1.20.1 stores the numeric id, and Forge adds its registry name under forge:id.
            effect.putInt("Id", 250);
            if (effect.contains("forge:id", Tag.TAG_STRING)) {
                effect.putString("forge:id", TestModes.REMOVED_EFFECT);
            }
        }
        LOGGER.info(
                "{}renamed {} effect(s) of {} to {} (numeric id 250)",
                TestModes.LOG_PREFIX,
                effects.size(),
                playerName,
                TestModes.REMOVED_EFFECT);
    }

    private static void renameVehicle(String playerName, CompoundTag root) {
        if (!root.contains("RootVehicle", Tag.TAG_COMPOUND)) {
            LOGGER.warn("{}{} has no vehicle", TestModes.LOG_PREFIX, playerName);
            return;
        }
        CompoundTag entity = root.getCompound("RootVehicle").getCompound("Entity");
        String before = entity.getString("id");
        entity.putString("id", TestModes.REMOVED_VEHICLE);
        LOGGER.info(
                "{}renamed the vehicle of {} from {} to {}",
                TestModes.LOG_PREFIX,
                playerName,
                before,
                TestModes.REMOVED_VEHICLE);
    }

    private static void truncate(String playerName, File file) throws IOException {
        try (RandomAccessFile handle = new RandomAccessFile(file, "rw")) {
            long size = handle.length();
            handle.setLength(size / 2);
            LOGGER.info(
                    "{}truncated {} of {} from {} to {} bytes",
                    TestModes.LOG_PREFIX,
                    file.getName(),
                    playerName,
                    size,
                    size / 2);
        }
    }
}
