package fr.palawi.playerdatarescue.core.rescue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.palawi.playerdatarescue.core.Fixtures;
import fr.palawi.playerdatarescue.core.attribution.Confidence;
import fr.palawi.playerdatarescue.core.attribution.ModResolver;
import fr.palawi.playerdatarescue.core.attribution.Suspect;
import fr.palawi.playerdatarescue.core.data.CoreValues;
import fr.palawi.playerdatarescue.core.incident.Outcome;
import fr.palawi.playerdatarescue.core.incident.Trigger;
import fr.palawi.playerdatarescue.core.nbt.NbtCompound;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The flow on real player files, with a target that fails the way the dev-only test mod does in
 * the smoke test (docs/TESTING.md): its attachment reader throws, or placement always throws.
 */
class RescueFlowTest {
    private static final CoreValues.Spawn SPAWN = new CoreValues.Spawn(0.5, 64, 0.5, "minecraft:overworld");

    /** The test mod's code: frames of this nested class belong to the mod {@code pdrtest}. */
    static final class TestMod {
        static void readAttachment(NbtCompound data) {
            if (data.contains("pdrtest:broken")
                    || data.compound("ForgeCaps")
                            .map(c -> c.contains("pdrtest:broken"))
                            .orElse(false)) {
                throw new IllegalStateException("pdrtest: deliberate failure: reading attachment pdrtest:broken");
            }
        }

        static void placement() {
            throw new IllegalStateException("pdrtest: deliberate failure: placement");
        }
    }

    private static final ModResolver MODS = new ModResolver() {
        @Override
        public Optional<String> modOfClass(String className) {
            return className.startsWith(TestMod.class.getName()) ? Optional.of("pdrtest") : Optional.empty();
        }

        @Override
        public Optional<String> mixinOwner(StackTraceElement frame) {
            return Optional.empty();
        }

        @Override
        public boolean isInstalled(String modId) {
            return Set.of("pdrtest").contains(modId);
        }
    };

    private static final class Target implements RescueTarget {
        private final boolean placementAlwaysFails;
        private final boolean placementFailsWithModData;
        final List<String> calls = new ArrayList<>();
        NbtCompound placed;

        Target(boolean placementAlwaysFails, boolean placementFailsWithModData) {
            this.placementAlwaysFails = placementAlwaysFails;
            this.placementFailsWithModData = placementFailsWithModData;
        }

        @Override
        public void loadOnly(NbtCompound data) {
            calls.add("load");
            if (!placementFailsWithModData) {
                TestMod.readAttachment(data);
            }
        }

        @Override
        public void place(NbtCompound data) {
            calls.add("place");
            if (placementAlwaysFails) {
                TestMod.placement();
            }
            if (placementFailsWithModData) {
                TestMod.readAttachment(data);
            }
            placed = data;
        }
    }

    @Test
    void aThrowingAttachmentIsSetAsideAloneAndThePlayerJoins() throws Exception {
        NbtCompound original = Fixtures.read("1.20.1-forge/S2Bot.dat");
        Target target = new Target(false, false);
        RescueFlow flow = new RescueFlow(target, MODS, SPAWN);

        Trigger trigger = flow.classify(original);
        RescueResult result = flow.run(original, trigger, new IllegalStateException("vanilla join failed"));

        assertEquals(Trigger.T1, result.trigger());
        assertEquals(Outcome.RESCUED, result.outcome());
        assertEquals(
                List.of("attachment.pdrtest:broken"),
                result.aside().stream().map(a -> a.unit().id()).toList());
        assertEquals(
                new Suspect("pdrtest", Suspect.DATA_FAILED, Confidence.HIGH),
                result.aside().get(0).suspects().get(0));
        assertEquals(List.of("load", "load", "place"), target.calls);
        assertFalse(target.placed.compound("ForgeCaps").orElseThrow().contains("pdrtest:broken"));
        assertEquals(original.list("Inventory"), target.placed.list("Inventory"));
    }

    @Test
    void aFabricModKeyIsSetAsideTheSameWay() throws Exception {
        NbtCompound original = Fixtures.read("26.3-fabric/S2Bot.dat");
        RescueFlow flow = new RescueFlow(new Target(false, false), MODS, SPAWN);

        RescueResult result = flow.run(original, flow.classify(original), new IllegalStateException("join failed"));

        assertEquals(Outcome.RESCUED, result.outcome());
        assertEquals(
                List.of("key.pdrtest:broken"),
                result.aside().stream().map(a -> a.unit().id()).toList());
    }

    @Test
    void aPlacementFailureCausedByTheDataEndsWithTheMinimalProfile() throws Exception {
        NbtCompound original = Fixtures.read("1.20.1-fabric/S2Bot.dat");
        Target target = new Target(false, true);
        RescueFlow flow = new RescueFlow(target, MODS, SPAWN);

        Trigger trigger = flow.classify(original);
        RescueResult result = flow.run(original, trigger, new IllegalStateException("join failed"));

        assertEquals(Trigger.T3, trigger);
        assertEquals(Outcome.MINIMAL_PROFILE, result.outcome());
        assertEquals(List.of("load", "place"), target.calls);
        assertFalse(target.placed.contains("Inventory"));
        List<String> aside = result.aside().stream().map(a -> a.unit().id()).toList();
        assertTrue(aside.contains("section.Inventory"), aside.toString());
        assertTrue(aside.contains("key.pdrtest:broken"), aside.toString());
    }

    @Test
    void aFailureWithEveryProfileIsNotDataRelated() throws Exception {
        NbtCompound original = Fixtures.read("1.21.1-neoforge/S12Bot.dat");
        Target target = new Target(true, false);
        RescueFlow flow = new RescueFlow(target, MODS, SPAWN);

        Trigger trigger = flow.classify(original);
        RescueResult result = flow.run(original, trigger, new IllegalStateException("join failed"));

        assertEquals(Trigger.T3, trigger);
        assertEquals(Outcome.NOT_DATA_RELATED, result.outcome());
        assertEquals(List.of(), result.aside());
        assertEquals(List.of("load", "place"), target.calls);
        assertEquals("pdrtest", result.suspects().get(0).modId());
        assertEquals(Confidence.MEDIUM, result.suspects().get(0).confidence());
    }
}
