package fr.palawi.playerdatarescue.core.rescue;

import fr.palawi.playerdatarescue.core.attribution.Attribution;
import fr.palawi.playerdatarescue.core.attribution.Frame;
import fr.palawi.playerdatarescue.core.attribution.ModResolver;
import fr.palawi.playerdatarescue.core.attribution.Suspect;
import fr.palawi.playerdatarescue.core.data.CoreValues;
import fr.palawi.playerdatarescue.core.data.Isolation;
import fr.palawi.playerdatarescue.core.data.PlayerData;
import fr.palawi.playerdatarescue.core.data.Unit;
import fr.palawi.playerdatarescue.core.incident.Outcome;
import fr.palawi.playerdatarescue.core.incident.Trigger;
import fr.palawi.playerdatarescue.core.nbt.NbtCompound;
import java.util.ArrayList;
import java.util.List;

/**
 * The rescue of SPEC.md section 3, steps 3 and 4, after the vanilla join failed with
 * {@code firstFailure}. Backup, persistence, reports and messages are done around it.
 *
 * <ol>
 *   <li>Classify: load the original data into a new player. If that throws, the failure is in
 *       the load (T1); otherwise it happened while placing the player (T3).
 *   <li>Salvage (T1): reset invalid core values, then load again and again, each time setting
 *       aside the units the failure points at, until the load works or nothing is left to try,
 *       at most {@link Isolation#MAX_ATTEMPTS} loads. Then place the salvaged data.
 *   <li>Minimal profile: when salvage did not work, the placement failed, or the failure was at
 *       placement (placing the same data again would fail the same way), place the minimal
 *       profile and set every other section aside whole.
 *   <li>If that fails too, the failure is not data-related (SPEC.md section 2).
 * </ol>
 */
public final class RescueFlow {
    private final RescueTarget target;
    private final ModResolver mods;
    private final CoreValues.Spawn spawn;
    private int attempts;
    private Throwable loadFailure;

    public RescueFlow(RescueTarget target, ModResolver mods, CoreValues.Spawn spawn) {
        this.target = target;
        this.mods = mods;
        this.spawn = spawn;
    }

    /**
     * Step 1: loads the original data into a new player. A failure there is T1; a load that works
     * means the join failed while placing the player (T3).
     */
    public Trigger classify(NbtCompound original) {
        loadFailure = tryLoad(original.copy());
        return loadFailure != null ? Trigger.T1 : Trigger.T3;
    }

    /** Steps 2 to 4, after {@link #classify}. */
    public RescueResult run(NbtCompound original, Trigger trigger, Throwable firstFailure) {
        Throwable placementFailure = firstFailure;
        if (trigger == Trigger.T1) {
            PlayerData data = PlayerData.of(original);
            List<String> coreChanges = CoreValues.clamp(data.root(), spawn);
            List<RescueResult.SetAside> aside = new ArrayList<>();
            Throwable failure =
                    coreChanges.isEmpty() ? loadFailure : tryLoad(data.root().copy());
            while (failure != null && attempts < Isolation.MAX_ATTEMPTS) {
                List<Frame> frames = Attribution.frames(failure, mods);
                List<Suspect> suspects = Attribution.rank(List.of(), frames, mods);
                List<Unit> candidates = Isolation.nextCandidates(data.units(), suspects);
                if (candidates.isEmpty()) {
                    break;
                }
                for (Unit unit : candidates) {
                    data.strip(unit);
                    List<Suspect> unitSuspects =
                            Attribution.rank(unit.namespace().stream().toList(), frames, mods);
                    aside.add(new RescueResult.SetAside(unit, failure, unitSuspects));
                }
                failure = tryLoad(data.root().copy());
            }
            if (failure == null) {
                Throwable placed = tryPlace(data.root().copy());
                if (placed == null) {
                    List<Suspect> suspects = Attribution.merge(
                            aside.stream().map(RescueResult.SetAside::suspects).toList());
                    return new RescueResult(
                            trigger, Outcome.RESCUED, aside, coreChanges, suspects, loadFailure, attempts);
                }
                placementFailure = placed;
            } else {
                placementFailure = failure;
            }
        }
        return minimal(original, trigger, placementFailure);
    }

    private RescueResult minimal(NbtCompound original, Trigger trigger, Throwable reason) {
        NbtCompound profile = CoreValues.minimalProfile(original, spawn);
        Throwable failure = tryPlace(profile);
        List<Frame> frames = Attribution.frames(failure != null ? failure : reason, mods);
        List<Suspect> suspects = Attribution.rank(List.of(), frames, mods);
        if (failure != null) {
            return new RescueResult(
                    trigger, Outcome.NOT_DATA_RELATED, List.of(), List.of(), suspects, failure, attempts);
        }
        List<RescueResult.SetAside> aside = new ArrayList<>();
        for (Unit section : PlayerData.wholeSections(original)) {
            aside.add(new RescueResult.SetAside(section, reason, suspects));
        }
        return new RescueResult(trigger, Outcome.MINIMAL_PROFILE, aside, List.of(), suspects, reason, attempts);
    }

    private Throwable tryLoad(NbtCompound data) {
        attempts++;
        try {
            target.loadOnly(data);
            return null;
        } catch (Exception e) {
            return e;
        }
    }

    private Throwable tryPlace(NbtCompound data) {
        attempts++;
        try {
            target.place(data);
            return null;
        } catch (Exception e) {
            return e;
        }
    }
}
