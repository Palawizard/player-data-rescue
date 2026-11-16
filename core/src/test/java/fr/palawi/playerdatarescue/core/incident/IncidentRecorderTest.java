package fr.palawi.playerdatarescue.core.incident;

import static org.junit.jupiter.api.Assertions.assertEquals;

import fr.palawi.playerdatarescue.core.Fixtures;
import fr.palawi.playerdatarescue.core.data.PlayerData;
import fr.palawi.playerdatarescue.core.data.Unit;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The expected texts come from the fixture notes (fixtures/README.md), not from the code. */
class IncidentRecorderTest {
    private static Unit find(List<Unit> units, String id) {
        return units.stream()
                .filter(unit -> unit.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no unit " + id + " in " + units.stream().map(Unit::id).toList()));
    }

    @Test
    void anItemIsDescribedBySlotCountAndId() throws Exception {
        // S1Bot got 12 bricks in slot 17, renamed to pdrtest_removed:brass_ingot.
        List<Unit> units =
                PlayerData.of(Fixtures.read("1.21.1-fabric/S1Bot.dat")).units();

        assertEquals(
                "Inventory slot 17: 12 x pdrtest_removed:brass_ingot",
                IncidentRecorder.describe(find(units, "inv.17")));
    }

    @Test
    void aWholeInventoryIsDescribedByItsStackCount() throws Exception {
        // A new player whose only items are the 12 bricks given through RCON, in one stack.
        List<Unit> sections = PlayerData.wholeSections(Fixtures.read("1.21.1-fabric/S1Bot.dat"));

        assertEquals("Whole inventory: 1 stack", IncidentRecorder.describe(find(sections, "section.Inventory")));
    }

    @Test
    void wholeEffectsAreDescribedByTheirCount() throws Exception {
        // S3Bot got one effect, speed, renamed to pdrtest_removed:poison_cloud.
        List<Unit> sections = PlayerData.wholeSections(Fixtures.read("1.21.1-fabric/S3Bot.dat"));

        assertEquals("All effects: 1 effect", IncidentRecorder.describe(find(sections, "section.active_effects")));
    }
}
