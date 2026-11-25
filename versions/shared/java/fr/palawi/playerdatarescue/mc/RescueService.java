package fr.palawi.playerdatarescue.mc;

import fr.palawi.playerdatarescue.core.ModInfo;
import fr.palawi.playerdatarescue.core.attribution.Attribution;
import fr.palawi.playerdatarescue.core.attribution.Suspect;
import fr.palawi.playerdatarescue.core.config.RescueConfig;
import fr.palawi.playerdatarescue.core.incident.Incident;
import fr.palawi.playerdatarescue.core.incident.IncidentRecorder;
import fr.palawi.playerdatarescue.core.incident.IncidentStore;
import fr.palawi.playerdatarescue.core.incident.Outcome;
import fr.palawi.playerdatarescue.core.incident.Trigger;
import fr.palawi.playerdatarescue.core.nbt.NbtCompound;
import fr.palawi.playerdatarescue.core.nbt.NbtFormatException;
import fr.palawi.playerdatarescue.core.rescue.RescueFlow;
import fr.palawi.playerdatarescue.core.rescue.RescueResult;
import fr.palawi.playerdatarescue.core.rescue.RescueState;
import fr.palawi.playerdatarescue.core.rescue.SourceFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry points called by the mixins of every band, on the server thread. The flow itself is in the
 * core ({@link RescueFlow}); this class adds what is around it: backups, persistence, reports and
 * messages (SPEC.md section 3). What depends on the game version goes through {@link GameJoin}.
 */
public final class RescueService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModInfo.MOD_ID);
    private static final Duration PENDING_LIFETIME = Duration.ofMinutes(5);

    private static LoaderBridge bridge;
    private static RescueConfig config = RescueConfig.DEFAULTS;
    private static final RescueState STATE = new RescueState();
    private static final Map<UUID, Unreadable> UNREADABLE = new HashMap<>();
    private static final Set<Object> FAILED_ATTEMPTS = Collections.newSetFromMap(new WeakHashMap<>());

    private RescueService() {}

    /** A T2 incident started while reading, finished once the player has joined. */
    private record Unreadable(
            Path folder,
            List<String> originals,
            Outcome outcome,
            NbtFormatException error,
            Instant time,
            Path fallback) {}

    public static void init(LoaderBridge loader) {
        bridge = loader;
        RescueConfig.Loaded loaded = RescueConfig.load(loader.configDirectory().resolve(RescueConfig.FILE_NAME));
        loaded.warnings().forEach(warning -> LOGGER.warn("[{}] config: {}", ModInfo.MOD_ID, warning));
        config = loaded.config();
    }

    static boolean active() {
        return bridge != null && config.enabled();
    }

    static LoaderBridge bridge() {
        return bridge;
    }

    static RescueConfig config() {
        return config;
    }

    /** A player instance of a failed attempt: the game must not save it over the real data. */
    public static void markFailed(Object player) {
        FAILED_ATTEMPTS.add(player);
    }

    public static boolean skipSave(Object player) {
        return !FAILED_ATTEMPTS.isEmpty() && FAILED_ATTEMPTS.contains(player);
    }

    /**
     * The join failed inside the placement try of the login listener. Returns true when the player
     * was rescued and is now in the world; false to let the game disconnect them as it would.
     */
    public static boolean onPlacementFailure(GameJoin join, Exception failure) {
        boolean rescued = false;
        try {
            rescued = rescueAfterPlacementFailure(join, failure);
            return rescued;
        } finally {
            // The timed hooks of a failed join (the hold, and on 1.20.1 the read of each attempt)
            // never reach the normal path report, so their time must not end up in the next join's.
            Timing.take(join.id());
            // Entries a queued restore put into this join's data are decided once the rescue,
            // if any, has put the player in the world or given up.
            RestoreService.afterPlacementFailure(join, rescued);
        }
    }

    private static boolean rescueAfterPlacementFailure(GameJoin join, Exception failure) {
        if (bridge == null || !config.enabled()) {
            return false;
        }
        if (STATE.skipRescue(join.id())) {
            LOGGER.warn(
                    "[{}] {} failed to join again; the last failure was not data-related, so no rescue is tried"
                            + " until the server restarts",
                    ModInfo.MOD_ID,
                    join.name());
            return false;
        }
        UNREADABLE.remove(join.id());
        try {
            return rescue(join, failure);
        } catch (Exception | LinkageError e) {
            LOGGER.error(
                    "[{}] the rescue of {} failed; the player is disconnected as without this mod",
                    ModInfo.MOD_ID,
                    join.name(),
                    e);
            return false;
        }
    }

    private static boolean rescue(GameJoin join, Exception failure) throws IOException {
        DataSource source = join.source();
        Optional<NbtCompound> original = source.read();
        if (original.isEmpty()) {
            if (source.backupFiles().stream().anyMatch(Files::exists)) {
                LOGGER.warn(
                        "[{}] {} could not join, and the saved data cannot be read; nothing to rescue",
                        ModInfo.MOD_ID,
                        join.name());
                return false;
            }
            return diagnoseNewPlayer(join, failure);
        }
        // A half-placed player (T3) leaves before any attempt loads the same player again, and is
        // never saved, whatever happens next.
        join.discardHalfPlaced();
        RescueFlow flow = new RescueFlow(join.attempts(source), bridge.mods(), join.spawn());
        Trigger trigger = flow.classify(original.get());
        if (trigger == Trigger.T3 && !config.rescuePlacementFailures()) {
            source.restore();
            return false;
        }
        IncidentStore store = new IncidentStore(join.worldDirectory());
        Path folder = store.create(join.id(), LocalDateTime.now());
        List<String> originals;
        try {
            originals = IncidentStore.backup(folder, source.backupFiles());
        } catch (IOException e) {
            source.restore();
            LOGGER.error(
                    "[{}] could not back up the data of {} to {}; no rescue without a backup",
                    ModInfo.MOD_ID,
                    join.name(),
                    folder,
                    e);
            return false;
        }
        if (config.logFullStackTraces()) {
            LOGGER.warn("[{}] {} could not join; rescuing, incident {}", ModInfo.MOD_ID, join.name(), folder, failure);
        } else {
            LOGGER.warn(
                    "[{}] {} could not join ({}); rescuing, incident {}",
                    ModInfo.MOD_ID,
                    join.name(),
                    describe(failure),
                    folder);
        }
        RescueResult result;
        try {
            result = flow.run(original.get(), trigger, failure);
        } catch (RuntimeException | LinkageError e) {
            source.restore();
            throw e;
        }
        RestoreService.rescued(join, result, folder);
        Incident incident;
        try {
            incident = IncidentRecorder.record(
                    context(join, folder, originals),
                    result,
                    original.get().number("DataVersion").map(Number::intValue));
        } catch (IOException | RuntimeException e) {
            if (result.outcome() == Outcome.NOT_DATA_RELATED) {
                source.restore();
                STATE.markNotDataRelated(join.id());
                throw e;
            }
            // The player is already in the world with the rescued data: keep them in.
            LOGGER.error(
                    "[{}] {} was rescued, but the reports could not be written to {}",
                    ModInfo.MOD_ID,
                    join.name(),
                    folder,
                    e);
            incident = null;
        }
        String shown = shown(join, folder);
        if (result.outcome() == Outcome.NOT_DATA_RELATED) {
            source.restore();
            STATE.markNotDataRelated(join.id());
            LOGGER.error(
                    "[{}] {} could not join, and the saved data is not the cause (even a minimal profile failed);"
                            + " nothing was changed. Diagnosis: {}",
                    ModInfo.MOD_ID,
                    join.name(),
                    folder.resolve("report.txt"));
            if (config.notifyOperators()) {
                join.tellOperators(incident, shown, config.commandPermissionLevel());
            }
            return false;
        }
        try {
            join.save();
            store.prune(join.id(), config.maxIncidentsPerPlayer());
        } catch (IOException | RuntimeException e) {
            // The game saves the player again later, at the latest when they leave.
            LOGGER.error("[{}] {} was rescued, but saving them right away failed", ModInfo.MOD_ID, join.name(), e);
        }
        if (incident != null) {
            LOGGER.warn(
                    "[{}] rescued {} ({}, {}): {} part(s) set aside after {} attempt(s). Report: {}",
                    ModInfo.MOD_ID,
                    join.name(),
                    result.trigger(),
                    result.outcome().name().toLowerCase(Locale.ROOT),
                    incident.entries().size(),
                    result.attempts(),
                    folder.resolve("report.txt"));
            notify(join, incident, shown, result.outcome());
        }
        RecurringService.rescued(join, result);
        return true;
    }

    private static boolean diagnoseNewPlayer(GameJoin join, Exception failure) throws IOException {
        join.discardHalfPlaced();
        Path folder = new IncidentStore(join.worldDirectory()).create(join.id(), LocalDateTime.now());
        List<Suspect> suspects = Attribution.rank(List.of(), Attribution.frames(failure, bridge.mods()), bridge.mods());
        RescueResult result =
                new RescueResult(Trigger.T3, Outcome.NOT_DATA_RELATED, List.of(), List.of(), suspects, failure, 0);
        Incident incident = IncidentRecorder.record(context(join, folder, List.of()), result, Optional.empty());
        STATE.markNotDataRelated(join.id());
        LOGGER.error(
                "[{}] {} could not join as a new player with no saved data, so the saved data is not the cause."
                        + " Diagnosis: {}",
                ModInfo.MOD_ID,
                join.name(),
                folder.resolve("report.txt"));
        if (config.notifyOperators()) {
            join.tellOperators(incident, shown(join, folder), config.commandPermissionLevel());
        }
        return false;
    }

    /** The exception and its causes in one line, for the log. */
    private static String describe(Throwable failure) {
        StringBuilder text = new StringBuilder(String.valueOf(failure));
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.add(failure);
        for (Throwable cause = failure.getCause(); cause != null && seen.add(cause); cause = cause.getCause()) {
            text.append(", caused by ").append(cause);
        }
        return text.toString();
    }

    /**
     * The game could not parse a player file while reading it (T2). Backs up both files before
     * any save can replace them. {@code gameReadsOld} is true on 1.21.1 and 26.3, where the game reads
     * {@code .dat_old} itself whatever the config says. On 1.20.1 it does not, and the returned
     * file is the {@code .dat_old} the band loads instead, when it is readable and the fallback is on.
     */
    public static Optional<Path> onUnreadableFile(
            UUID id, String name, Path playerDirectory, Path worldDirectory, boolean gameReadsOld) {
        long start = Timing.start();
        try {
            if (bridge == null || !config.enabled()) {
                return Optional.empty();
            }
            Unreadable known = UNREADABLE.get(id);
            if (known != null && Duration.between(known.time(), Instant.now()).compareTo(PENDING_LIFETIME) < 0) {
                return Optional.ofNullable(known.fallback());
            }
            Path main = playerDirectory.resolve(id + ".dat");
            Optional<NbtFormatException> error = SourceFiles.unreadable(main);
            if (error.isEmpty()) {
                return Optional.empty();
            }
            Path old = SourceFiles.old(main);
            Path folder = new IncidentStore(worldDirectory).create(id, LocalDateTime.now());
            List<String> originals = IncidentStore.backup(folder, List.of(main, old));
            boolean oldReadable = SourceFiles.readable(old).isPresent();
            boolean modReadsOld = !gameReadsOld && config.useDatOldFallback() && oldReadable;
            Outcome outcome =
                    oldReadable && (gameReadsOld || modReadsOld) ? Outcome.PREVIOUS_SAVE : Outcome.UNREADABLE_FILE;
            Path fallback = modReadsOld ? old : null;
            UNREADABLE.put(id, new Unreadable(folder, originals, outcome, error.get(), Instant.now(), fallback));
            LOGGER.warn(
                    "[{}] the player file of {} cannot be read ({}); both files are backed up in {}",
                    ModInfo.MOD_ID,
                    name,
                    error.get().getMessage(),
                    folder);
            return Optional.ofNullable(fallback);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[{}] could not back up the unreadable player file of {}", ModInfo.MOD_ID, name, e);
            return Optional.empty();
        } finally {
            Timing.stop(id, start);
        }
    }

    /** The {@code .dat_old} chosen for a pending T2 of this player, if any. */
    public static Optional<Path> previousSave(UUID id) {
        if (UNREADABLE.isEmpty()) {
            return Optional.empty();
        }
        Unreadable pending = UNREADABLE.get(id);
        return pending == null ? Optional.empty() : Optional.ofNullable(pending.fallback());
    }

    /** The login handler returned. Finishes a T2 incident once the player is in the world. */
    public static void onLoginFinished(GameJoin join) {
        long start = Timing.start();
        Unreadable pending = UNREADABLE.isEmpty() ? null : UNREADABLE.remove(join.id());
        boolean placed = join.placed();
        if (placed) {
            RestoreService.joined(join);
            RecurringService.joined(join);
        }
        Timing.stop(join.id(), start);
        if (Timing.ENABLED) {
            long[] cost = Timing.take(join.id());
            LOGGER.info(
                    "[{}] normal path for {}: {} us in {} hook call(s)",
                    ModInfo.MOD_ID,
                    join.name(),
                    String.format(Locale.ROOT, "%.1f", cost[0] / 1000.0),
                    cost[1]);
        }
        if (pending == null || !placed) {
            return;
        }
        try {
            join.save();
            Incident incident = IncidentRecorder.recordUnreadable(
                    context(join, pending.folder(), pending.originals()), pending.outcome(), pending.error());
            new IncidentStore(join.worldDirectory()).prune(join.id(), config.maxIncidentsPerPlayer());
            LOGGER.warn(
                    "[{}] {} joined with {} (T2). Report: {}",
                    ModInfo.MOD_ID,
                    join.name(),
                    pending.outcome() == Outcome.PREVIOUS_SAVE
                            ? "the previous save"
                            : "no saved data (unreadable file)",
                    pending.folder().resolve("report.txt"));
            notify(join, incident, shown(join, pending.folder()), pending.outcome());
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[{}] could not write the report of {}", ModInfo.MOD_ID, join.name(), e);
        }
    }

    private static void notify(GameJoin join, Incident incident, String shown, Outcome outcome) {
        if (config.notifyPlayer()) {
            join.tellPlayer(outcome);
        }
        if (config.notifyOperators()) {
            join.tellOperators(incident, shown, config.commandPermissionLevel());
        }
    }

    private static String shown(GameJoin join, Path folder) {
        return join.worldDirectory()
                .toAbsolutePath()
                .normalize()
                .relativize(folder.toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
    }

    private static IncidentRecorder.Context context(GameJoin join, Path folder, List<String> originals) {
        return new IncidentRecorder.Context(
                join.id(),
                join.name(),
                folder,
                Instant.now(),
                new Incident.Versions(
                        join.minecraftVersion(), bridge.loaderName(), bridge.loaderVersion(), bridge.modVersion()),
                originals);
    }
}
