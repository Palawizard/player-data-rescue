package fr.palawi.playerdatarescue.mc;

import fr.palawi.playerdatarescue.core.ModInfo;
import fr.palawi.playerdatarescue.core.incident.Incident;
import fr.palawi.playerdatarescue.core.incident.IncidentFiles;
import fr.palawi.playerdatarescue.core.incident.IncidentRecorder;
import fr.palawi.playerdatarescue.core.incident.IncidentStore;
import fr.palawi.playerdatarescue.core.incident.Outcome;
import fr.palawi.playerdatarescue.core.nbt.NbtCompound;
import fr.palawi.playerdatarescue.core.rescue.RescueResult;
import fr.palawi.playerdatarescue.core.restore.RestoreQueue;
import fr.palawi.playerdatarescue.core.restore.Restorer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The join side of a restore (SPEC.md section 7.1, ADR-PDR-013): at the start of a join, before
 * the game reads the player's data, the entries an operator queued go into that data, and the
 * join's result decides whether each one is restored. Called on the server thread.
 */
public final class RestoreService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModInfo.MOD_ID);

    private static final Map<UUID, Restorer.Prepared> RESTORING = new HashMap<>();
    /**
     * Players with a queue file, per world (an integrated server can open several worlds), read
     * from disk at the first join into that world.
     */
    private static final Map<Path, Set<UUID>> QUEUED = new HashMap<>();

    private RestoreService() {}

    /** A command queued entries for this player. */
    static void queued(Path world, UUID player) throws IOException {
        players(world).add(player);
    }

    /**
     * At the start of a join, before the game reads the data. {@code source} is only asked for
     * when this player has entries waiting, so a normal join does no IO here.
     */
    public static void beforeRead(
            UUID id, String name, Path world, String minecraftVersion, Supplier<DataSource> source) {
        long start = Timing.start();
        try {
            if (!RescueService.active()) {
                return;
            }
            Restorer.Prepared stale = RESTORING.remove(id);
            if (stale != null) {
                // The last join that applied a queue was read without a failure, then ended
                // before the login handler returned (1.20.1 delays a placement while the same
                // player is still online). The game read that data, so it counts as restored.
                finish(world, id, stale, Map.of());
            }
            if (!players(world).contains(id)) {
                return;
            }
            apply(id, name, world, minecraftVersion, source.get());
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[{}] the queued restore of {} failed; the player joins without it", ModInfo.MOD_ID, name, e);
        } finally {
            Timing.stop(id, start);
        }
    }

    private static void apply(UUID id, String name, Path world, String minecraftVersion, DataSource source)
            throws IOException {
        Optional<NbtCompound> current = source.read();
        if (current.isEmpty()) {
            LOGGER.warn(
                    "[{}] {} has entries to restore, but no readable saved data to put them in; they stay queued",
                    ModInfo.MOD_ID,
                    name);
            return;
        }
        Path root = new IncidentStore(world).root();
        IncidentRecorder.Context context = new IncidentRecorder.Context(
                id,
                name,
                root,
                Instant.now(),
                new Incident.Versions(
                        minecraftVersion,
                        RescueService.bridge().loaderName(),
                        RescueService.bridge().loaderVersion(),
                        RescueService.bridge().modVersion()),
                List.of());
        Optional<Restorer.Prepared> prepared =
                Restorer.prepare(root, context, current.get(), source.backupFiles(), attachmentContainer());
        players(world).remove(id);
        if (prepared.isEmpty()) {
            return;
        }
        source.write(prepared.get().data());
        RESTORING.put(id, prepared.get());
        LOGGER.warn(
                "[{}] restoring {} entr{} for {}; the data before is backed up in {}",
                ModInfo.MOD_ID,
                prepared.get().applied().size(),
                prepared.get().applied().size() == 1 ? "y" : "ies",
                name,
                prepared.get().backup());
    }

    /** The join ended with the player in the world, without a rescue. */
    static void joined(GameJoin join) {
        Restorer.Prepared prepared = RESTORING.remove(join.id());
        if (prepared == null) {
            return;
        }
        try {
            finish(join.worldDirectory(), join.id(), prepared, Map.of());
            if (!prepared.toDrop().isEmpty()) {
                join.dropAtFeet(prepared.toDrop());
                LOGGER.warn(
                        "[{}] {} had no free slot for {} restored stack(s); dropped at their feet",
                        ModInfo.MOD_ID,
                        join.name(),
                        prepared.toDrop().size());
            }
            join.tellRestored(prepared.applied().size());
            LOGGER.warn(
                    "[{}] {} joined with {} restored entr{}",
                    ModInfo.MOD_ID,
                    join.name(),
                    prepared.applied().size(),
                    prepared.applied().size() == 1 ? "y" : "ies");
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[{}] could not record the restore of {}", ModInfo.MOD_ID, join.name(), e);
        }
    }

    /**
     * The join that applied a queue failed and the rescue ran ({@code result} is null when the
     * rescue did not run or failed, and {@code incident} with it). Entries the rescue set aside again, or every entry when the player
     * did not join with the salvaged data, are marked {@code restore_failed}.
     */
    static void rescued(GameJoin join, RescueResult result, Path incident) {
        Restorer.Prepared prepared = RESTORING.remove(join.id());
        if (prepared == null) {
            return;
        }
        String reason = incident == null
                ? "the join failed before a rescue; see the server log"
                : "it failed to load again; see incident " + incident.getFileName();
        Map<String, String> failed = new LinkedHashMap<>();
        Set<String> aside = new HashSet<>();
        if (result != null) {
            result.aside().forEach(a -> aside.add(a.unit().id()));
        }
        boolean wholeFailure = result == null || result.outcome() != Outcome.RESCUED;
        for (RestoreQueue.Item item : prepared.applied()) {
            if (wholeFailure || aside.contains(item.entry())) {
                failed.put(item.entry(), reason);
            }
        }
        try {
            finish(join.worldDirectory(), join.id(), prepared, failed);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[{}] could not record the restore of {}", ModInfo.MOD_ID, join.name(), e);
        }
    }

    private static void finish(Path world, UUID id, Restorer.Prepared prepared, Map<String, String> failed)
            throws IOException {
        Restorer.finish(new IncidentStore(world).root(), id, prepared, failed);
    }

    private static Set<UUID> players(Path world) throws IOException {
        Path key = world.toAbsolutePath().normalize();
        Set<UUID> players = QUEUED.get(key);
        if (players == null) {
            players = new HashSet<>();
            Path root = new IncidentStore(world).root();
            for (UUID player : IncidentFiles.players(root)) {
                if (Files.isRegularFile(RestoreQueue.file(root, player))) {
                    players.add(player);
                }
            }
            QUEUED.put(key, players);
        }
        return players;
    }

    private static String attachmentContainer() {
        return RescueService.bridge().loaderName().equals("neoforge") ? "neoforge:attachments" : "ForgeCaps";
    }
}
