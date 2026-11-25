package fr.palawi.playerdatarescue.mc;

import fr.palawi.playerdatarescue.core.ModInfo;
import fr.palawi.playerdatarescue.core.incident.IncidentFiles;
import fr.palawi.playerdatarescue.core.incident.IncidentStore;
import fr.palawi.playerdatarescue.core.nbt.NbtCompound;
import fr.palawi.playerdatarescue.core.recurring.RecurringIssues;
import fr.palawi.playerdatarescue.core.rescue.RescueResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The game side of recurring issues (SPEC.md section 4.4). A rescue that kept its incident hands
 * its set-aside units over: the keys that keep failing are counted in their record, and operators
 * hear about each key once per server start. A join without a failure ends the streaks and closes
 * the records of keys that load again. Nothing here changes an incident. Called on the server
 * thread.
 */
final class RecurringService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModInfo.MOD_ID);

    /** Per running server, so a new start, or a new integrated server, starts afresh. */
    private static final Map<Object, State> STATES = new WeakHashMap<>();

    private static final class State {
        /** Players with a streak or in a record, per world, read from disk at the first join into it. */
        final Map<Path, Set<UUID>> concerned = new HashMap<>();
        /** Keys operators were told about since this server started, per world. */
        final Map<Path, Set<String>> told = new HashMap<>();
    }

    private RecurringService() {}

    /**
     * The rescue of this join kept its incident. Counts its mod data units towards recurring
     * issues; a failure here is logged and changes nothing for the player or the incident.
     */
    static void rescued(GameJoin join, RescueResult result) {
        try {
            Optional<List<RecurringIssues.Failure>> failures = RecurringIssues.failures(result);
            if (failures.isEmpty()) {
                return;
            }
            RecurringIssues.Recorded recorded = issues(join.worldDirectory())
                    .record(
                            join.id(),
                            join.name(),
                            failures.get(),
                            RescueService.config().recurringThreshold(),
                            Instant.now());
            concerned(join).add(join.id());
            for (RecurringIssues.Issue issue : recorded.created()) {
                LOGGER.warn(
                        "[{}] {} fails to load at every join; it is now a recurring issue, recorded in {}",
                        ModInfo.MOD_ID,
                        issue.key(),
                        issue.file());
            }
            tellOperators(join, recorded.touched());
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[{}] could not update the recurring issues of {}", ModInfo.MOD_ID, join.name(), e);
        }
    }

    /**
     * A join without a failure. For a player with a streak or in a record, the streaks end, and
     * the player's records whose key is in their data close: that key loaded again.
     */
    static void joined(GameJoin join) {
        try {
            if (!RescueService.active() || !concerned(join).contains(join.id())) {
                return;
            }
            RecurringIssues issues = issues(join.worldDirectory());
            Optional<NbtCompound> data = join.source().read();
            Set<String> keys = data.map(RecurringIssues::keys).orElse(Set.of());
            for (RecurringIssues.Issue closed : issues.joined(join.id(), keys, Instant.now())) {
                LOGGER.warn(
                        "[{}] {} loaded again for {}; its recurring issue is closed",
                        ModInfo.MOD_ID,
                        closed.key(),
                        join.name());
                told(join.server(), join.worldDirectory()).remove(closed.key());
            }
            if (!issues.concerns(join.id())) {
                concerned(join).remove(join.id());
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[{}] could not update the recurring issues of {}", ModInfo.MOD_ID, join.name(), e);
        }
    }

    /** An operator closed the record of {@code key}: if it comes back, operators hear about it again. */
    static void dismissed(Object server, Path world, String key) {
        told(server, world).remove(key);
    }

    static RecurringIssues issues(Path world) {
        return new RecurringIssues(new IncidentStore(world).root());
    }

    private static void tellOperators(GameJoin join, List<RecurringIssues.Issue> issues) {
        if (!RescueService.config().notifyOperators()) {
            return;
        }
        Set<String> told = told(join.server(), join.worldDirectory());
        for (RecurringIssues.Issue issue : issues) {
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

    private static Set<String> told(Object server, Path world) {
        return STATES.computeIfAbsent(server, s -> new State()).told.computeIfAbsent(key(world), k -> new HashSet<>());
    }

    private static Set<UUID> concerned(GameJoin join) throws IOException {
        State state = STATES.computeIfAbsent(join.server(), s -> new State());
        Path world = key(join.worldDirectory());
        Set<UUID> players = state.concerned.get(world);
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
            state.concerned.put(world, players);
        }
        return players;
    }

    private static Path key(Path world) {
        return world.toAbsolutePath().normalize();
    }
}
