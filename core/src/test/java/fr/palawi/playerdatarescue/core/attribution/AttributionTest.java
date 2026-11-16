package fr.palawi.playerdatarescue.core.attribution;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AttributionTest {
    /** Classes of this test stand for a mod's code, as the loader would report them. */
    private static final ModResolver MODS = new ModResolver() {
        @Override
        public Optional<String> modOfClass(String className) {
            return className.startsWith(AttributionTest.class.getName()) ? Optional.of("examplemod") : Optional.empty();
        }

        @Override
        public Optional<String> mixinOwner(StackTraceElement frame) {
            return Optional.empty();
        }

        @Override
        public boolean isInstalled(String modId) {
            return Set.of("examplemod", "create").contains(modId);
        }
    };

    private static RuntimeException thrownByTheMod() {
        try {
            Map.of().get(null);
            throw new AssertionError("unreachable");
        } catch (NullPointerException cause) {
            return new IllegalStateException("Loading entity NBT", cause);
        }
    }

    @Test
    void namespacesComeFirstThenTheFirstModFrame() {
        List<Frame> frames = Attribution.frames(thrownByTheMod(), MODS);

        List<Suspect> suspects = Attribution.rank(List.of("removedmod", "create"), frames, MODS);

        assertEquals(
                List.of(
                        new Suspect("removedmod", Suspect.NOT_INSTALLED, Confidence.HIGH),
                        new Suspect("create", Suspect.DATA_FAILED, Confidence.HIGH),
                        new Suspect("examplemod", Suspect.IN_STACK_TRACE, Confidence.MEDIUM)),
                suspects);
    }

    @Test
    void theGameAndTheLoadersAreNeverSuspects() {
        List<Suspect> suspects = Attribution.rank(List.of("minecraft", "forge", "playerdatarescue"), List.of(), MODS);

        assertEquals(List.of(), suspects);
    }

    @Test
    void atMostThreeSuspectsWithoutDuplicates() {
        List<Suspect> suspects = Attribution.rank(List.of("a", "b", "c", "d", "a"), List.of(), MODS);

        assertEquals(
                List.of("a", "b", "c"), suspects.stream().map(Suspect::modId).toList());
    }

    @Test
    void mergeKeepsTheStrongestReasonPerMod() {
        List<Suspect> merged = Attribution.merge(List.of(
                List.of(new Suspect("examplemod", Suspect.IN_STACK_TRACE, Confidence.MEDIUM)),
                List.of(new Suspect("examplemod", Suspect.DATA_FAILED, Confidence.HIGH))));

        assertEquals(List.of(new Suspect("examplemod", Suspect.DATA_FAILED, Confidence.HIGH)), merged);
    }
}
