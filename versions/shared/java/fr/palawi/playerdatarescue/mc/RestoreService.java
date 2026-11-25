package fr.palawi.playerdatarescue.mc;

import fr.palawi.playerdatarescue.core.ModInfo;
import fr.palawi.playerdatarescue.core.incident.Incident;
import fr.palawi.playerdatarescue.core.incident.IncidentFiles;
import fr.palawi.playerdatarescue.core.incident.IncidentRecorder;
import fr.palawi.playerdatarescue.core.incident.IncidentStore;
import fr.palawi.playerdatarescue.core.io.AtomicFiles;
import fr.palawi.playerdatarescue.core.nbt.NbtCompound;
import fr.palawi.playerdatarescue.core.nbt.Snbt;
import fr.palawi.playerdatarescue.core.rescue.RescueResult;
import fr.palawi.playerdatarescue.core.restore.RestoreJoin;
import fr.palawi.playerdatarescue.core.restore.RestorePending;
import fr.palawi.playerdatarescue.core.restore.RestoreQueue;
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
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The join side of a restore (SPEC.md section 7.1, ADR-PDR-013), around {@link RestoreJoin}: at
 * the start of a join, before the game reads the player's data, the entries an operator queued go
 * into that data; where the join ends decides each one. Called on the server thread.
 */
public final class RestoreService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModInfo.MOD_ID);

    /** Joins of this process with a restore record on disk, waiting for their result. */
    private static final Map<UUID, Active> ACTIVE = new HashMap<>();
    /**
     * Players with a queue or a restore record, per world (an integrated server can open several
     * worlds), read from disk at the first join into that world.
     */
    private static final Map<Path, Set<UUID>> WAITING = new HashMap<>();

    /** A join with a restore record: the source it wrote the merged data to, if it did, and its rescue. */
    private static final class Active {
        final DataSource written;
        RescueResult rescue;
        Path incident;

        Active(DataSource written) {
            this.written = written;
        }
    }

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
            // A join of this player that never reported its result: its record is settled from disk.
            ACTIVE.remove(id);
            if (!players(world).contains(id)) {
                return;
            }
            Path root = new IncidentStore(world).root();
            DataSource data = source.get();
            RestoreJoin.recover(root, id, sha(data));
            DataSource written = null;
            if (Files.isRegularFile(RestoreQueue.file(root, id))) {
                written = apply(id, name, world, minecraftVersion, data) ? data : null;
            }
            if (Files.isRegularFile(RestorePending.file(root, id))) {
                ACTIVE.put(id, new Active(written));
            }
            forgetIfDone(world, id);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[{}] the queued restore of {} failed; the player joins without it", ModInfo.MOD_ID, name, e);
        } finally {
            Timing.stop(id, start);
        }
    }

    /** Puts the queue into the data; true when merged data was written. */
    private static boolean apply(UUID id, String name, Path world, String minecraftVersion, DataSource source)
            throws IOException {
        Optional<NbtCompound> current = source.read();
        if (current.isEmpty()) {
            LOGGER.warn(
                    "[{}] {} has entries to restore, but no readable saved data to put them in; they stay queued",
                    ModInfo.MOD_ID,
                    name);
            return false;
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
        Optional<RestoreJoin.Prepared> prepared = RestoreJoin.prepare(
                root, context, current.get(), source.backupFiles(), sha(source), attachmentContainer());
        if (prepared.isEmpty()) {
            return false;
        }
        try {
            source.write(prepared.get().data());
        } catch (RuntimeException e) {
            RestoreJoin.abort(root, id);
            throw e;
        }
        LOGGER.warn(
                "[{}] restoring {} entr{} for {}; the data before is backed up in {}",
                ModInfo.MOD_ID,
                prepared.get().applied(),
                prepared.get().applied() == 1 ? "y" : "ies",
                name,
                prepared.get().backup());
        return true;
    }

    /** The join ended with the player in the world, without a rescue. */
    public static void joined(GameJoin join) {
        Active active = ACTIVE.remove(join.id());
        if (active != null) {
            finish(join, RestoreJoin.End.joined());
        }
    }

    /** The rescue of this join ran; where the join ends is decided once the rescue returns. */
    static void rescued(GameJoin join, RescueResult result, Path incident) {
        Active active = ACTIVE.get(join.id());
        if (active != null) {
            active.rescue = result;
            active.incident = incident;
        }
    }

    /**
     * The placement of this join failed, and the rescue, if any, has returned. In the world, the
     * rescue result decides each entry. Out of it, the data is put back as it was before the
     * restore (after the rescue put back what it read, the merged data), so the entries are back
     * in their incident only.
     */
    static void afterPlacementFailure(GameJoin join) {
        Active active = ACTIVE.remove(join.id());
        if (active == null) {
            return;
        }
        if (join.placed()) {
            RescueResult rescue = active.rescue;
            finish(
                    join,
                    rescue == null || active.incident == null
                            ? RestoreJoin.End.joined()
                            : RestoreJoin.End.rescued(
                                    active.incident.getFileName().toString(),
                                    rescue.outcome(),
                                    rescue.aside().stream()
                                            .map(a -> a.unit().payload())
                                            .toList()));
            return;
        }
        try {
            if (active.written != null) {
                active.written.restore();
            }
            String reason = active.incident == null
                    ? "the join failed; the data was put back as it was before the restore (see the server log)"
                    : "the join failed (incident " + active.incident.getFileName()
                            + "); the data was put back as it was before the restore";
            RestoreJoin.afterJoin(root(join), join.id(), RestoreJoin.End.notPlaced(reason));
        } catch (IOException | RuntimeException e) {
            // The record stays on disk: the next join settles it from what the player file holds.
            LOGGER.error(
                    "[{}] could not put back the data of {} as it was before the restore",
                    ModInfo.MOD_ID,
                    join.name(),
                    e);
        }
    }

    private static void finish(GameJoin join, RestoreJoin.End end) {
        try {
            RestoreJoin.Delivery delivery = RestoreJoin.afterJoin(root(join), join.id(), end);
            if (!delivery.drops().isEmpty()) {
                List<NbtCompound> unread = join.dropAtFeet(delivery.drops());
                LOGGER.warn(
                        "[{}] {} had no free slot for {} restored stack(s); dropped at their feet",
                        ModInfo.MOD_ID,
                        join.name(),
                        delivery.drops().size() - unread.size());
                for (NbtCompound stack : unread) {
                    LOGGER.error(
                            "[{}] the game could not read a restored stack of {}, so it was not dropped: {}",
                            ModInfo.MOD_ID,
                            join.name(),
                            Snbt.write(stack));
                }
            }
            RestoreJoin.delivered(root(join), join.id());
            if (delivery.restored() > 0) {
                join.tellRestored(delivery.restored());
            }
            if (delivery.restored() + delivery.setAsideAgain() > 0) {
                LOGGER.warn(
                        "[{}] {} joined with {} restored entr{}, {} set aside again",
                        ModInfo.MOD_ID,
                        join.name(),
                        delivery.restored(),
                        delivery.restored() == 1 ? "y" : "ies",
                        delivery.setAsideAgain());
            }
            forgetIfDone(join.worldDirectory(), join.id());
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[{}] could not record the restore of {}", ModInfo.MOD_ID, join.name(), e);
        }
    }

    private static void forgetIfDone(Path world, UUID id) throws IOException {
        Path root = new IncidentStore(world).root();
        if (!Files.isRegularFile(RestoreQueue.file(root, id)) && !Files.isRegularFile(RestorePending.file(root, id))) {
            players(world).remove(id);
        }
    }

    /** The SHA-256 of the file the game reads this player from, as it is on disk now. */
    private static String sha(DataSource source) throws IOException {
        Path file = source.backupFiles().get(0);
        return Files.isRegularFile(file) ? AtomicFiles.sha256(Files.readAllBytes(file)) : "absent";
    }

    private static Path root(GameJoin join) {
        return new IncidentStore(join.worldDirectory()).root();
    }

    private static Set<UUID> players(Path world) throws IOException {
        Path key = world.toAbsolutePath().normalize();
        Set<UUID> players = WAITING.get(key);
        if (players == null) {
            players = new HashSet<>();
            Path root = new IncidentStore(world).root();
            for (UUID player : IncidentFiles.players(root)) {
                if (Files.isRegularFile(RestoreQueue.file(root, player))
                        || Files.isRegularFile(RestorePending.file(root, player))) {
                    players.add(player);
                }
            }
            WAITING.put(key, players);
        }
        return players;
    }

    private static String attachmentContainer() {
        return RescueService.bridge().loaderName().equals("neoforge") ? "neoforge:attachments" : "ForgeCaps";
    }
}
