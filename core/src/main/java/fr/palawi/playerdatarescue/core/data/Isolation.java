package fr.palawi.playerdatarescue.core.data;

import fr.palawi.playerdatarescue.core.attribution.Confidence;
import fr.palawi.playerdatarescue.core.attribution.Suspect;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Picks the units to set aside after a failed load (ARCHITECTURE.md, Rescue strategy): first the
 * units whose namespace is a suspected mod, then, if none matches, every opaque unit left. An
 * empty answer means salvage cannot go further and the minimal profile is next.
 */
public final class Isolation {
    /** Load attempts on salvaged data before giving up on salvage. */
    public static final int MAX_ATTEMPTS = 8;

    private Isolation() {}

    public static List<Unit> nextCandidates(List<Unit> remaining, List<Suspect> suspects) {
        Set<String> mods = suspects.stream()
                .filter(s -> s.confidence() != Confidence.LOW)
                .map(Suspect::modId)
                .collect(Collectors.toSet());
        List<Unit> matching = remaining.stream()
                .filter(unit -> unit.namespace().filter(mods::contains).isPresent())
                .toList();
        if (!matching.isEmpty()) {
            return matching;
        }
        return remaining.stream().filter(unit -> unit.section().opaque()).toList();
    }
}
