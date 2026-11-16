package fr.palawi.playerdatarescue.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.palawi.playerdatarescue.core.Fixtures;
import fr.palawi.playerdatarescue.core.nbt.NbtCompound;
import fr.palawi.playerdatarescue.core.nbt.NbtList;
import fr.palawi.playerdatarescue.core.nbt.NbtValue;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PlayerDataTest {
    private static Unit unit(PlayerData data, String id) {
        return data.units().stream()
                .filter(u -> u.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no unit " + id + " in "
                        + data.units().stream().map(Unit::id).toList()));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "1.20.1-forge/S12Bot.dat",
                "1.21.1-forge/S12Bot.dat",
                "1.21.1-neoforge/S12Bot.dat",
                "26.3-forge/S12Bot.dat",
                "26.3-neoforge/S12Bot.dat"
            })
    void filesSavedWithoutModDataHaveNoUnknownKey(String fixture) throws Exception {
        PlayerData data = PlayerData.of(Fixtures.read(fixture));

        assertEquals(
                List.of(),
                data.units().stream()
                        .filter(u -> u.section() == Section.UNKNOWN_KEY)
                        .map(Unit::id)
                        .toList());
    }

    @Test
    void aForgeCapabilityIsAnAttachmentOfItsMod() throws Exception {
        Unit attachment = unit(PlayerData.of(Fixtures.read("1.20.1-forge/S2Bot.dat")), "attachment.pdrtest:broken");

        assertEquals(Section.ATTACHMENTS, attachment.section());
        assertEquals(Optional.of("pdrtest"), attachment.namespace());
        assertEquals(NbtValue.ofInt(42), attachment.payload());
    }

    @Test
    void aNeoForgeAttachmentIsAnAttachmentOfItsMod() throws Exception {
        Unit attachment = unit(PlayerData.of(Fixtures.read("1.21.1-neoforge/S2Bot.dat")), "attachment.pdrtest:broken");

        assertEquals(Optional.of("pdrtest"), attachment.namespace());
        assertEquals(NbtValue.ofInt(42), attachment.payload());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.20.1-fabric/S2Bot.dat", "26.3-fabric/S2Bot.dat"})
    void aFabricModKeyIsAnUnknownTopLevelKey(String fixture) throws Exception {
        Unit key = unit(PlayerData.of(Fixtures.read(fixture)), "key.pdrtest:broken");

        assertEquals(Section.UNKNOWN_KEY, key.section());
        assertEquals(Optional.of("pdrtest"), key.namespace());
        assertEquals(NbtValue.ofInt(42), key.payload());
    }

    @Test
    void inventorySlotsCarryTheirSlotAndItemNamespace() throws Exception {
        Unit slot = unit(PlayerData.of(Fixtures.read("1.21.1-fabric/S1Bot.dat")), "inv.17");

        assertEquals(Optional.of("17"), slot.slot());
        assertEquals(Optional.of("pdrtest_removed"), slot.namespace());
    }

    @Test
    void effectsAreNamedByTheirId() throws Exception {
        PlayerData data = PlayerData.of(Fixtures.read("1.21.1-fabric/S3Bot.dat"));

        assertEquals(
                Optional.of("pdrtest_removed"),
                unit(data, "effect.pdrtest_removed:poison_cloud").namespace());
    }

    @Test
    void theVehicleIsOneUnitNamedAfterItsEntityType() throws Exception {
        Unit vehicle = unit(PlayerData.of(Fixtures.read("1.21.1-forge/S4Bot.dat")), "vehicle");

        assertEquals(Section.VEHICLE, vehicle.section());
        assertEquals(Optional.of("pdrtest_removed"), vehicle.namespace());
    }

    @Test
    void stripRemovesOnlyThatUnitFromTheCopy() throws Exception {
        NbtCompound original = Fixtures.read("1.21.1-fabric/S1Bot.dat");
        PlayerData data = PlayerData.of(original);
        int slots = data.root().list("Inventory").orElseThrow().size();

        data.strip(unit(data, "inv.17"));

        NbtList inventory = data.root().list("Inventory").orElseThrow();
        assertEquals(slots - 1, inventory.size());
        assertTrue(inventory.elements().stream()
                .noneMatch(e -> ((NbtCompound) e).number("Slot").orElseThrow().intValue() == 17));
        assertEquals(slots, original.list("Inventory").orElseThrow().size());
        assertFalse(data.units().stream().anyMatch(u -> u.id().equals("inv.17")));
    }

    @Test
    void wholeSectionsCoverEverySetAsideSection() throws Exception {
        List<String> ids = PlayerData.wholeSections(Fixtures.read("1.20.1-fabric/S2Bot.dat")).stream()
                .map(Unit::id)
                .toList();

        assertTrue(ids.contains("section.Inventory"), ids.toString());
        assertTrue(ids.contains("section.recipeBook"), ids.toString());
        assertTrue(ids.contains("key.pdrtest:broken"), ids.toString());
    }
}
