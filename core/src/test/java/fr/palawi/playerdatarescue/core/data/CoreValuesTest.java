package fr.palawi.playerdatarescue.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import fr.palawi.playerdatarescue.core.Fixtures;
import fr.palawi.playerdatarescue.core.nbt.NbtCompound;
import fr.palawi.playerdatarescue.core.nbt.NbtList;
import fr.palawi.playerdatarescue.core.nbt.NbtTag;
import fr.palawi.playerdatarescue.core.nbt.NbtValue;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CoreValuesTest {
    private static final CoreValues.Spawn SPAWN = new CoreValues.Spawn(8.5, 64, -3.5, "minecraft:overworld");
    private static final CoreValues.Spawn SPAWN_WITH_DIMENSIONS = new CoreValues.Spawn(
            8.5,
            64,
            -3.5,
            "minecraft:overworld",
            Set.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"));

    @Test
    void theMinimalProfileKeepsPositionAndGameModeWithFullHealthAndFood() throws Exception {
        // The smoke test put TpBot in creative mode and teleported it; RCON answered
        // "Teleported TpBot to 998.500000, 100.000000, -8.500000".
        NbtCompound minimal = CoreValues.minimalProfile(Fixtures.read("1.21.1-fabric/TpBot.dat"), SPAWN);

        assertEquals(
                Set.of("DataVersion", "UUID", "Pos", "Rotation", "Dimension", "playerGameType", "Health", "foodLevel"),
                minimal.keys());
        NbtList pos = minimal.list("Pos").orElseThrow();
        assertEquals(
                List.of(998.5, 100.0, -8.5),
                pos.elements().stream()
                        .map(e -> ((NbtValue) e).number().doubleValue())
                        .toList());
        assertEquals(1, minimal.number("playerGameType").orElseThrow().intValue());
        assertEquals(NbtValue.ofFloat(20f), minimal.get("Health"));
        assertEquals(NbtValue.ofInt(20), minimal.get("foodLevel"));
    }

    @Test
    void anInvalidPositionGoesToTheWorldSpawn() throws Exception {
        NbtCompound root = Fixtures.read("1.20.1-forge/S12Bot.dat");
        NbtList nan = new NbtList(NbtTag.DOUBLE);
        nan.add(NbtValue.ofDouble(Double.NaN));
        nan.add(NbtValue.ofDouble(70));
        nan.add(NbtValue.ofDouble(0));
        root.put("Pos", nan);

        List<String> changes = CoreValues.clamp(root, SPAWN);

        assertEquals(1, changes.size());
        assertEquals(
                List.of(8.5, 64.0, -3.5),
                root.list("Pos").orElseThrow().elements().stream()
                        .map(e -> ((NbtValue) e).number().doubleValue())
                        .toList());
        assertEquals("minecraft:overworld", root.string("Dimension").orElseThrow());
    }

    @Test
    void aDimensionThatIsNotOnTheServerGoesToTheWorldSpawn() throws Exception {
        // TpBot joined in the overworld and no command changed its dimension (fixtures/README.md); it is
        // moved to a dimension from a removed mod, as when the mod that added the dimension is gone.
        NbtCompound root = Fixtures.read("1.21.1-fabric/TpBot.dat");
        root.put("Dimension", NbtValue.ofString("pdrtest_removed:void"));

        List<String> changes = CoreValues.clamp(root, SPAWN_WITH_DIMENSIONS);

        assertEquals(List.of("Dimension pdrtest_removed:void is not on this server; set to the world spawn"), changes);
        assertEquals(
                List.of(8.5, 64.0, -3.5),
                root.list("Pos").orElseThrow().elements().stream()
                        .map(e -> ((NbtValue) e).number().doubleValue())
                        .toList());
        assertEquals("minecraft:overworld", root.string("Dimension").orElseThrow());
    }

    @Test
    void aDimensionOnTheServerIsLeftAlone() throws Exception {
        NbtCompound root = Fixtures.read("1.21.1-fabric/TpBot.dat");
        NbtCompound before = root.copy();

        assertEquals(List.of(), CoreValues.clamp(root, SPAWN_WITH_DIMENSIONS));
        assertEquals(before, root);
    }

    @Test
    void validCoreValuesAreLeftAlone() throws Exception {
        NbtCompound root = Fixtures.read("26.3-neoforge/S12Bot.dat");
        NbtCompound before = root.copy();

        assertEquals(List.of(), CoreValues.clamp(root, SPAWN));
        assertEquals(before, root);
    }
}
