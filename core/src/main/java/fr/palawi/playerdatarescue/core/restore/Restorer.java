package fr.palawi.playerdatarescue.core.restore;

import fr.palawi.playerdatarescue.core.attribution.Suspect;
import fr.palawi.playerdatarescue.core.incident.EntryStatus;
import fr.palawi.playerdatarescue.core.incident.IncidentFiles;
import fr.palawi.playerdatarescue.core.incident.IncidentRecorder;
import fr.palawi.playerdatarescue.core.incident.IncidentStore;
import fr.palawi.playerdatarescue.core.incident.Outcome;
import fr.palawi.playerdatarescue.core.incident.QuarantineEntry;
import fr.palawi.playerdatarescue.core.incident.Trigger;
import fr.palawi.playerdatarescue.core.nbt.NbtCompound;
import fr.palawi.playerdatarescue.core.nbt.Snbt;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The restore of SPEC.md section 7.1. An operator's command only queues entries; the next join
 * of the player puts them into the saved data before the game reads it, after a verified backup
 * of that data, and the join's result decides each entry's status. An entry already restored is
 * never queued again, so restoring twice cannot duplicate anything.
 */
public final class Restorer {
    private Restorer() {}

    /** What a restore command did. */
    public record Queued(List<String> queued, List<String> alreadyRestored, Map<String, String> notRestorable) {}

    /** Entries put into the data of a join, waiting for the join's result. */
    public record Prepared(
            NbtCompound data, List<RestoreQueue.Item> applied, List<NbtCompound> toDrop, Path backup, int replaced) {}

    /**
     * Queues one entry, or with {@code entry} "all" every entry still set aside (dismissed
     * entries only when named).
     */
    public static Queued queue(Path root, UUID player, String incident, String entry) throws IOException {
        Path folder = incidentFolder(root, player, incident);
        IncidentFiles.Summary summary = IncidentFiles.read(folder);
        boolean all = entry.equals("all");
        List<IncidentFiles.Entry> chosen = summary.entries().stream()
                .filter(e -> all ? e.status() != EntryStatus.DISMISSED : e.id().equals(entry))
                .toList();
        if (!all && chosen.isEmpty()) {
            throw new IllegalArgumentException("incident " + incident + " has no entry " + entry);
        }
        List<String> already = new ArrayList<>();
        Map<String, String> notRestorable = new LinkedHashMap<>();
        List<RestoreQueue.Item> items = new ArrayList<>();
        for (IncidentFiles.Entry e : chosen) {
            if (e.status() == EntryStatus.RESTORED) {
                already.add(e.id());
            } else if (e.payload().isEmpty()) {
                notRestorable.put(e.id(), "it was too large to keep apart; it is only in original/");
            } else {
                items.add(new RestoreQueue.Item(incident, e.id()));
            }
        }
        // An entry queued by an earlier command stays queued once.
        RestoreQueue.add(root, player, items);
        return new Queued(items.stream().map(RestoreQueue.Item::entry).toList(), already, notRestorable);
    }

    /**
     * At a join, before the game reads the player's data: puts the queued entries into {@code
     * current}. The source files are backed up first into a new incident, which also keeps the
     * values the restore replaced; without that backup nothing is changed. Entries that cannot go
     * back are marked {@code restore_failed} at once. Returns empty when nothing is queued.
     */
    public static Optional<Prepared> prepare(
            Path root,
            IncidentRecorder.Context context,
            NbtCompound current,
            List<Path> sourceFiles,
            String attachmentContainer)
            throws IOException {
        UUID player = context.playerId();
        List<RestoreQueue.Item> queue = RestoreQueue.read(root, player);
        if (queue.isEmpty()) {
            return Optional.empty();
        }
        Map<String, RestoreMerge.Piece> pieces = new LinkedHashMap<>();
        Map<String, RestoreQueue.Item> byKey = new LinkedHashMap<>();
        Map<RestoreQueue.Item, String> failed = new LinkedHashMap<>();
        for (RestoreQueue.Item item : queue) {
            Path folder = root.resolve(player.toString()).resolve(item.incident());
            Optional<IncidentFiles.Entry> entry = Files.isDirectory(folder)
                    ? IncidentFiles.read(folder).entries().stream()
                            .filter(e -> e.id().equals(item.entry()))
                            .findFirst()
                    : Optional.empty();
            Optional<RestoreMerge.Piece> piece =
                    entry.isPresent() ? IncidentFiles.piece(folder, entry.get()) : Optional.empty();
            if (piece.isEmpty()) {
                failed.put(item, "its payload file is missing");
                continue;
            }
            String key = item.incident() + "/" + item.entry();
            pieces.put(
                    key,
                    new RestoreMerge.Piece(key, piece.get().data(), piece.get().dataVersion()));
            byKey.put(key, item);
        }
        List<RestoreMerge.Piece> ordered = new ArrayList<>();
        for (RestoreMerge.Piece piece : pieces.values()) {
            String entryId = byKey.get(piece.entryId()).entry();
            ordered.add(new RestoreMerge.Piece(entryId, piece.data(), piece.dataVersion()));
        }
        RestoreMerge.Result merged = RestoreMerge.apply(current, ordered, attachmentContainer);
        List<RestoreQueue.Item> applied = new ArrayList<>();
        int index = 0;
        for (String key : pieces.keySet()) {
            RestoreQueue.Item item = byKey.get(key);
            String reason = merged.refused().get(ordered.get(index).entryId());
            if (reason != null) {
                failed.put(item, reason);
            } else {
                applied.add(item);
            }
            index++;
        }

        Path backup = new IncidentStore(root.getParent()).create(player, LocalDateTime.now());
        List<String> originals = IncidentStore.backup(backup, sourceFiles);
        recordBackup(context.withFolder(backup, originals), merged, applied, current);

        for (Map.Entry<RestoreQueue.Item, String> e : failed.entrySet()) {
            mark(root, player, e.getKey(), EntryStatus.RESTORE_FAILED, e.getValue());
        }
        RestoreQueue.remove(root, player, queue);
        return Optional.of(new Prepared(
                merged.data(),
                applied,
                merged.toDrop(),
                backup,
                merged.replaced().size()));
    }

    /** After the join: each applied entry is restored, unless the join set it aside again. */
    public static void finish(Path root, UUID player, Prepared prepared, Map<String, String> failedAgain)
            throws IOException {
        for (RestoreQueue.Item item : prepared.applied()) {
            String reason = failedAgain.get(item.entry());
            mark(root, player, item, reason == null ? EntryStatus.RESTORED : EntryStatus.RESTORE_FAILED, reason);
        }
    }

    private static void mark(Path root, UUID player, RestoreQueue.Item item, EntryStatus status, String reason)
            throws IOException {
        Path folder = root.resolve(player.toString()).resolve(item.incident());
        if (Files.isDirectory(folder)) {
            IncidentFiles.setStatus(
                    folder, Map.of(item.entry(), status), reason == null ? Map.of() : Map.of(item.entry(), reason));
        }
    }

    /** The backup incident: the source files as they were, and what the restore replaced. */
    private static void recordBackup(
            IncidentRecorder.Context context,
            RestoreMerge.Result merged,
            List<RestoreQueue.Item> applied,
            NbtCompound current)
            throws IOException {
        List<QuarantineEntry> entries = new ArrayList<>();
        List<String> payloadNames = new ArrayList<>();
        Optional<Integer> dataVersion = current.number("DataVersion").map(Number::intValue);
        String from = String.join(
                ", ",
                applied.stream().map(RestoreQueue.Item::incident).distinct().toList());
        for (RestoreMerge.Replaced replaced : merged.replaced()) {
            Optional<String> payload = IncidentStore.writePayload(
                    context.folder(), replaced.id(), replaced.payload(), dataVersion, payloadNames);
            entries.add(new QuarantineEntry(
                    replaced.id(),
                    replaced.section(),
                    replaced.slot(),
                    "Replaced by the restore from " + from + ": " + replaced.id(),
                    payload.isPresent() ? Snbt.write(replaced.payload()) : "",
                    null,
                    List.<Suspect>of(),
                    EntryStatus.QUARANTINED,
                    payload,
                    payload.isEmpty()));
        }
        IncidentRecorder.recordBackup(context, Trigger.RESTORE, Outcome.RESTORE_BACKUP, entries);
    }

    private static Path incidentFolder(Path root, UUID player, String incident) {
        if (!incident.matches("[0-9]{8}-[0-9]{6}(-[0-9]+)?")) {
            throw new IllegalArgumentException("no incident " + incident);
        }
        Path folder = root.resolve(player.toString()).resolve(incident);
        if (!Files.isRegularFile(folder.resolve("report.json"))) {
            throw new IllegalArgumentException("no incident " + incident + " for this player");
        }
        return folder;
    }
}
