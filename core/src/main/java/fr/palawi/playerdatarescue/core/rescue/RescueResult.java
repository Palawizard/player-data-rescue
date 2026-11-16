package fr.palawi.playerdatarescue.core.rescue;

import fr.palawi.playerdatarescue.core.attribution.Suspect;
import fr.palawi.playerdatarescue.core.data.Unit;
import fr.palawi.playerdatarescue.core.incident.Outcome;
import fr.palawi.playerdatarescue.core.incident.Trigger;
import java.util.List;
import java.util.Optional;

/**
 * What the rescue flow did.
 *
 * @param aside the units set aside, each with the failure that made it fail and its suspects
 * @param coreChanges core values reset to safe defaults
 * @param lastFailure the failure the outcome rests on: the last placement failure for a
 *     diagnosis, otherwise the first failure seen
 */
public record RescueResult(
        Trigger trigger,
        Outcome outcome,
        List<SetAside> aside,
        List<String> coreChanges,
        List<Suspect> suspects,
        Throwable lastFailure,
        int attempts) {

    public record SetAside(Unit unit, Throwable failure, List<Suspect> suspects) {}

    public Optional<SetAside> find(String unitId) {
        return aside.stream().filter(s -> s.unit().id().equals(unitId)).findFirst();
    }
}
