package fr.palawi.playerdatarescue.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import fr.palawi.playerdatarescue.core.Fixtures;
import fr.palawi.playerdatarescue.core.nbt.NbtCompound;
import fr.palawi.playerdatarescue.core.nbt.NbtList;
import fr.palawi.playerdatarescue.core.nbt.NbtTag;
import fr.palawi.playerdatarescue.core.nbt.NbtValue;
import java.util.List;
import org.junit.jupiter.api.Test;

class CoreValuesTest {
    private static final CoreValues.Spawn SPAWN = new CoreValues.Spawn(8.5, 64, -3.5, "minecraft:overworld");

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
    void validCoreValuesAreLeftAlone() throws Exception {
        NbtCompound root = Fixtures.read("26.3-neoforge/S12Bot.dat");
        NbtCompound before = root.copy();

        assertEquals(List.of(), CoreValues.clamp(root, SPAWN));
        assertEquals(before, root);
        assertFalse(root.contains("unchanged"));
    }
}
