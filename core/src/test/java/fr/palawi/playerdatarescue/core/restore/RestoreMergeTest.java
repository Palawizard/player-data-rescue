package fr.palawi.playerdatarescue.core.restore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.palawi.playerdatarescue.core.Fixtures;
import fr.palawi.playerdatarescue.core.data.PlayerData;
import fr.palawi.playerdatarescue.core.data.Unit;
import fr.palawi.playerdatarescue.core.nbt.NbtCompound;
import fr.palawi.playerdatarescue.core.nbt.NbtList;
import fr.palawi.playerdatarescue.core.nbt.NbtTag;
import fr.palawi.playerdatarescue.core.nbt.NbtValue;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Restores units taken from real player files (fixtures/README.md) into other real player files.
 * The expected slots, counts and ids come from the fixture notes, not from the code.
 */
class RestoreMergeTest {
    private static Unit unit(NbtCompound data, String id) {
        return PlayerData.of(data).units().stream()
                .filter(u -> u.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no unit " + id));
    }

    private static RestoreMerge.Piece piece(NbtCompound from, String id) {
        return new RestoreMerge.Piece(
                id, unit(from, id).payload(), from.number("DataVersion").map(Number::intValue));
    }

    private static NbtCompound slot(NbtCompound data, int slot) {
        for (NbtTag element : data.list("Inventory").orElseThrow().elements()) {
            NbtCompound stack = (NbtCompound) element;
            if ((stack.number("Slot").orElseThrow().byteValue() & 0xff) == slot) {
                return stack;
            }
        }
        throw new AssertionError("nothing in slot " + slot);
    }

    @Test
    void aStackGoesBackIntoItsFreeSlot() throws Exception {
        // S1Bot had 12 bricks in slot 17 (renamed to pdrtest_removed:brass_ingot); S2Bot has no items.
        NbtCompound s1 = Fixtures.read("1.20.1-fabric/S1Bot.dat");
        NbtCompound s2 = Fixtures.read("1.20.1-fabric/S2Bot.dat");

        RestoreMerge.Result result = RestoreMerge.apply(s2, List.of(piece(s1, "inv.17")), "ForgeCaps");

        assertEquals(List.of("inv.17"), result.applied());
        NbtCompound stack = slot(result.data(), 17);
        assertEquals(Optional.of("pdrtest_removed:brass_ingot"), stack.string("id"));
        assertEquals(12, stack.number("Count").orElseThrow().intValue());
        assertTrue(result.toDrop().isEmpty());
    }

    @Test
    void aStackWhoseSlotIsTakenGoesIntoTheFirstFreeSlot() throws Exception {
        // S1Bot's only stack is in slot 17, so slot 0 is the first free one.
        NbtCompound s1 = Fixtures.read("1.20.1-fabric/S1Bot.dat");

        RestoreMerge.Result result = RestoreMerge.apply(s1, List.of(piece(s1, "inv.17")), "ForgeCaps");

        assertEquals(
                Optional.of("pdrtest_removed:brass_ingot"),
                slot(result.data(), 0).string("id"));
        assertEquals(
                Optional.of("pdrtest_removed:brass_ingot"),
                slot(result.data(), 17).string("id"));
    }

    @Test
    void aStackWithNoFreeSlotIsDroppedAtTheFeet() throws Exception {
        NbtCompound s1 = Fixtures.read("1.20.1-fabric/S1Bot.dat");
        NbtCompound full = s1.copy();
        NbtList inventory = full.list("Inventory").orElseThrow();
        NbtCompound brick = (NbtCompound) inventory.get(0);
        for (int slot = 0; slot < 36; slot++) {
            if (slot != 17) {
                NbtCompound copy = brick.copy();
                copy.put("Slot", NbtValue.ofByte((byte) slot));
                inventory.add(copy);
            }
        }

        RestoreMerge.Result result = RestoreMerge.apply(full, List.of(piece(s1, "inv.17")), "ForgeCaps");

        assertEquals(List.of("inv.17"), result.applied());
        assertEquals(1, result.toDrop().size());
        assertEquals(
                Optional.of("pdrtest_removed:brass_ingot"),
                result.toDrop().get(0).string("id"));
        assertEquals(36, result.data().list("Inventory").orElseThrow().size());
    }

    @Test
    void anAttachmentReplacesTheCurrentValueWhichIsKeptAside() throws Exception {
        // Every Forge file has the test capability pdrtest:broken under ForgeCaps (the int 42).
        NbtCompound s2 = Fixtures.read("1.20.1-forge/S2Bot.dat");
        NbtCompound s12 = Fixtures.read("1.20.1-forge/S12Bot.dat");

        RestoreMerge.Result result =
                RestoreMerge.apply(s12, List.of(piece(s2, "attachment.pdrtest:broken")), "ForgeCaps");

        assertEquals(List.of("attachment.pdrtest:broken"), result.applied());
        assertEquals(1, result.replaced().size());
        assertEquals("attachment.pdrtest:broken", result.replaced().get(0).id());
        assertEquals(NbtValue.ofInt(42), result.replaced().get(0).payload());
        assertEquals(
                NbtValue.ofInt(42),
                result.data().compound("ForgeCaps").orElseThrow().get("pdrtest:broken"));
    }

    @Test
    void aFabricModKeyGoesBackToTheTopLevel() throws Exception {
        // On Fabric the test attachment is the top-level key pdrtest:broken.
        NbtCompound s2 = Fixtures.read("1.20.1-fabric/S2Bot.dat");
        NbtCompound current = s2.copy();
        current.remove("pdrtest:broken");

        RestoreMerge.Result result = RestoreMerge.apply(current, List.of(piece(s2, "key.pdrtest:broken")), "ForgeCaps");

        assertEquals(List.of("key.pdrtest:broken"), result.applied());
        assertTrue(result.replaced().isEmpty());
        assertEquals(NbtValue.ofInt(42), result.data().get("pdrtest:broken"));
    }

    @Test
    void anEffectIsPutBackWithItsSavedDuration() throws Exception {
        // S3Bot got speed for 600 s, renamed to pdrtest_removed:poison_cloud; TpBot has no effect.
        NbtCompound s3 = Fixtures.read("1.21.1-fabric/S3Bot.dat");
        NbtCompound tp = Fixtures.read("1.21.1-fabric/TpBot.dat");
        NbtCompound saved =
                (NbtCompound) unit(s3, "effect.pdrtest_removed:poison_cloud").payload();

        RestoreMerge.Result result =
                RestoreMerge.apply(tp, List.of(piece(s3, "effect.pdrtest_removed:poison_cloud")), "ForgeCaps");

        NbtList effects = result.data().list("active_effects").orElseThrow();
        assertEquals(1, effects.size());
        assertEquals(saved, effects.get(0));
    }

    @Test
    void aVehicleIsNotRestored() throws Exception {
        NbtCompound s4 = Fixtures.read("1.21.1-forge/S4Bot.dat");
        NbtCompound current = s4.copy();
        current.remove("RootVehicle");

        RestoreMerge.Result result = RestoreMerge.apply(current, List.of(piece(s4, "vehicle")), "ForgeCaps");

        assertTrue(result.applied().isEmpty());
        assertTrue(result.refused().get("vehicle").contains("vehicles cannot be restored"));
        assertTrue(!result.data().contains("RootVehicle"));
    }

    @Test
    void anEntryFromAnotherGameVersionIsRefused() throws Exception {
        // A 1.20.1 stack into a 1.21.1 file: the game would read it with the wrong data version.
        NbtCompound old = Fixtures.read("1.20.1-fabric/S1Bot.dat");
        NbtCompound current = Fixtures.read("1.21.1-fabric/TpBot.dat");

        RestoreMerge.Result result = RestoreMerge.apply(current, List.of(piece(old, "inv.17")), "ForgeCaps");

        assertTrue(result.applied().isEmpty());
        assertTrue(result.refused().get("inv.17").contains("another game version"));
        assertEquals(current, result.data());
    }

    @Test
    void theCurrentDataIsNotChanged() throws Exception {
        NbtCompound s1 = Fixtures.read("1.20.1-fabric/S1Bot.dat");
        NbtCompound s2 = Fixtures.read("1.20.1-fabric/S2Bot.dat");
        NbtCompound before = s2.copy();

        RestoreMerge.apply(s2, List.of(piece(s1, "inv.17")), "ForgeCaps");

        assertEquals(before, s2);
    }
}
