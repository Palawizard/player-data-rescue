package fr.palawi.playerdatarescue.mc;

import fr.palawi.playerdatarescue.core.ModInfo;
import fr.palawi.playerdatarescue.core.attribution.Attribution;
import fr.palawi.playerdatarescue.core.attribution.Suspect;
import fr.palawi.playerdatarescue.core.incident.IncidentFiles;
import fr.palawi.playerdatarescue.core.incident.IncidentStore;
import fr.palawi.playerdatarescue.core.incident.Outcome;
import fr.palawi.playerdatarescue.core.nbt.NbtCompound;
import fr.palawi.playerdatarescue.core.recurring.RecurringIssues;
import fr.palawi.playerdatarescue.core.rescue.RescueResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The game side of recurring issues (SPEC.md section 4.4): a rescue hands its set-aside units
 * over, the keys that keep failing go to their record instead of the incident, operators hear
 * about each key once per server start, and a join without a failure ends the streaks and closes
 * the records of keys that load again. Called on the server thread.
 */
final class RecurringService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModInfo.MOD_ID);

    /** Players with a streak or in a record, per world, read from disk at the first join into it. */
    private static final Map<Path, Set<UUID>> CONCERNED = new HashMap<>();
    /** Keys operators were told about since the server started, per world. */
    private static final Map<Path, Set<String>> TOLD = new HashMap<>();

    private RecurringService() {}

    /**
     * @param rest the rescue result without the recurring keys, for the incident
     * @param recurring the keys whose failure went to their record
     */
    record Split(RescueResult rest, Set<String> recurring, List<RecurringIssues.Issue> issues) {
        boolean onlyRecurring() {
            return !recurring.isEmpty() && rest.aside().isEmpty();
        }
    }

    /** Records the set-aside units of a rescue; only a rescue with the salvaged data counts. */
    static Split split(GameJoin join, RescueResult result) throws IOException {
        if (result.outcome() != Outcome.RESCUED || result.aside().isEmpty()) {
            return new Split(result, Set.of(), List.of());
        }
        List<RecurringIssues.Failure> failures = new ArrayList<>();
        for (RescueResult.SetAside aside : result.aside()) {
            failures.add(new RecurringIssues.Failure(
                    aside.unit().id(),
                    aside.unit().payload(),
                    aside.suspects().stream().findFirst().map(Suspect::modId)));
        }
        RecurringIssues issues = issues(join.worldDirectory());
        RecurringIssues.Outcome outcome = issues.record(
                join.id(), join.name(), failures, RescueService.config().recurringThreshold(), Instant.now());
        concerned(join.worldDirectory()).add(join.id());
        if (outcome.recurring().isEmpty()) {
            return new Split(result, Set.of(), List.of());
        }
        List<RescueResult.SetAside> rest = result.aside().stream()
                .filter(a -> !outcome.recurring().contains(a.unit().id()))
                .toList();
        List<Suspect> suspects = Attribution.merge(
                rest.stream().map(RescueResult.SetAside::suspects).toList());
        RescueResult remaining = new RescueResult(
                result.trigger(),
                result.outcome(),
                rest,
                result.coreChanges(),
                suspects,
                result.lastFailure(),
                result.attempts());
        List<RecurringIssues.Issue> touched = new ArrayList<>(outcome.created());
        touched.addAll(outcome.updated());
        for (RecurringIssues.Issue issue : outcome.created()) {
            LOGGER.warn(
                    "[{}] {} fails to load at every join; it is now a recurring issue, recorded in {}",
                    ModInfo.MOD_ID,
                    issue.key(),
                    issue.file());
        }
        return new Split(remaining, outcome.recurring(), touched);
    }

    /** Tells online operators about each recurring key once per server start. */
    static void tellOperators(GameJoin join, Split split) {
        if (!RescueService.config().notifyOperators()) {
            return;
        }
        Set<String> told = TOLD.computeIfAbsent(key(join.worldDirectory()), k -> new HashSet<>());
        for (RecurringIssues.Issue issue : split.issues()) {
            if (told.contains(issue.key())) {
                continue;
            }
            String file = join.worldDirectory()
                    .toAbsolutePath()
                    .normalize()
                    .relativize(issue.file().toAbsolutePath().normalize())
                    .toString()
                    .replace('\\', '/');
            if (join.tellOperatorsRecurring(issue, file, RescueService.config().commandPermissionLevel()) > 0) {
                told.add(issue.key());
            }
        }
    }

    /**
     * A join without a failure. For a player with a streak or in a record, the streaks end, and
     * the records of keys found in their data close: those keys loaded again.
     */
    static void joined(GameJoin join) {
        long start = Timing.start();
        try {
            if (!RescueService.active() || !concerned(join.worldDirectory()).contains(join.id())) {
                return;
            }
            RecurringIssues issues = issues(join.worldDirectory());
            issues.joined(join.id());
            Optional<NbtCompound> data = join.source().read();
            if (data.isPresent()) {
                for (RecurringIssues.Issue closed : issues.loaded(RecurringIssues.keys(data.get()), Instant.now())) {
                    LOGGER.warn(
                            "[{}] {} loaded again for {}; its recurring issue is closed",
                            ModInfo.MOD_ID,
                            closed.key(),
                            join.name());
                    TOLD.getOrDefault(key(join.worldDirectory()), new HashSet<>())
                            .remove(closed.key());
                }
            }
            if (!issues.concerns(join.id())) {
                concerned(join.worldDirectory()).remove(join.id());
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[{}] could not update the recurring issues of {}", ModInfo.MOD_ID, join.name(), e);
        } finally {
            Timing.stop(join.id(), start);
        }
    }

    static RecurringIssues issues(Path world) {
        return new RecurringIssues(new IncidentStore(world).root());
    }

    private static Set<UUID> concerned(Path world) throws IOException {
        Path key = key(world);
        Set<UUID> players = CONCERNED.get(key);
        if (players == null) {
            players = new HashSet<>();
            Path root = new IncidentStore(world).root();
            for (UUID player : IncidentFiles.players(root)) {
                if (Files.isRegularFile(root.resolve(player.toString()).resolve(RecurringIssues.STREAKS))) {
                    players.add(player);
                }
            }
            for (RecurringIssues.Issue issue : issues(world).all()) {
                players.addAll(issue.players().keySet());
            }
            CONCERNED.put(key, players);
        }
        return players;
    }

    private static Path key(Path world) {
        return world.toAbsolutePath().normalize();
    }
}
