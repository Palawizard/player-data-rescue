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
import fr.palawi.playerdatarescue.core.nbt.NbtList;
import fr.palawi.playerdatarescue.core.nbt.NbtTag;
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
 * The join side of a restore (SPEC.md section 7.1, ADR-PDR-013). Before the game reads the data,
 * {@link #prepare} backs up the source files, records the entries on their way in ({@link
 * RestorePending}) and returns the merged data to write. After the join, {@link #afterJoin}
 * decides each entry from where the join ended. A join that never reports its result is settled
 * by {@link #recover} at the next one. At every step an entry's payload is restorable in one place
 * only: its own incident, or the data of the player (or the incident of a rescue that took it).
 */
public final class RestoreJoin {
    private RestoreJoin() {}

    /** The merged data to write where the game reads, before the read. */
    public record Prepared(NbtCompound data, int applied, int replaced, Path backup) {}

    /** What the join left to do: stacks to drop at the player's feet, and what to tell the player. */
    public record Delivery(List<NbtCompound> drops, int restored, int setAsideAgain) {
        static final Delivery NOTHING = new Delivery(List.of(), 0, 0);
    }

    /**
     * How the join ended.
     *
     * @param placed true when the player is in the world
     * @param incident the rescue incident of this join, when a rescue placed the player
     * @param outcome that rescue's outcome
     * @param setAside the payloads that rescue set aside
     * @param reason why the entries failed, when the player is not in the world
     */
    public record End(boolean placed, String incident, Outcome outcome, List<NbtTag> setAside, String reason) {
        public static End joined() {
            return new End(true, null, null, List.of(), null);
        }

        public static End rescued(String incident, Outcome outcome, List<NbtTag> setAside) {
            return new End(true, incident, outcome, List.copyOf(setAside), null);
        }

        /** The player did not get in, and the data was put back as it was before the restore. */
        public static End notPlaced(String reason) {
            return new End(false, null, null, List.of(), reason);
        }
    }

    /**
     * Settles entries a past join put into the data without reporting its result (the server
     * stopped, or the client left between the read and the placement). If the player file is still
     * the one from before the restore, the data was never written and the entries are queued
     * again; otherwise the data holds them, so they are restored. Call it before {@link #prepare}.
     */
    public static void recover(Path root, UUID player, String currentSha) throws IOException {
        Optional<RestorePending> pending = RestorePending.read(root, player);
        if (pending.isEmpty() || pending.get().items().isEmpty()) {
            return;
        }
        if (currentSha.equals(pending.get().originalSha())) {
            abort(root, player);
            return;
        }
        List<NbtCompound> carry = new ArrayList<>(pending.get().carry());
        for (RestorePending.Item item : pending.get().items()) {
            Restorer.mark(root, player, item.entry(), EntryStatus.RESTORED, null);
            carry.addAll(item.drops());
        }
        new RestorePending(pending.get().backup(), pending.get().originalSha(), List.of(), carry).write(root, player);
    }

    /** The merged data could not be written: the entries in it go back to the queue. */
    public static void abort(Path root, UUID player) throws IOException {
        Optional<RestorePending> pending = RestorePending.read(root, player);
        if (pending.isEmpty() || pending.get().items().isEmpty()) {
            return;
        }
        RestoreQueue.add(
                root,
                player,
                pending.get().items().stream().map(RestorePending.Item::entry).toList());
        new RestorePending(
                        pending.get().backup(),
                        pending.get().originalSha(),
                        List.of(),
                        pending.get().carry())
                .write(root, player);
    }

    /**
     * Puts the queued entries into {@code current}. The source files are backed up first into a
     * new incident, which also keeps the values the restore replaced; without that backup nothing
     * changes. Entries that cannot go back are marked {@code restore_failed} at once. Returns empty
     * when no entry goes into the data.
     *
     * @param currentSha the SHA-256 of the player file as it is now, before the merged data
     */
    public static Optional<Prepared> prepare(
            Path root,
            IncidentRecorder.Context context,
            NbtCompound current,
            List<Path> sourceFiles,
            String currentSha,
            String attachmentContainer)
            throws IOException {
        UUID player = context.playerId();
        List<RestoreQueue.Item> queue = RestoreQueue.read(root, player);
        if (queue.isEmpty()) {
            return Optional.empty();
        }
        Map<String, RestoreQueue.Item> byKey = new LinkedHashMap<>();
        List<RestoreMerge.Piece> pieces = new ArrayList<>();
        Map<RestoreQueue.Item, String> failed = new LinkedHashMap<>();
        for (RestoreQueue.Item item : queue) {
            Optional<IncidentFiles.Entry> entry = entry(root, player, item);
            if (entry.isPresent() && !entry.get().status().restorable()) {
                continue;
            }
            Path folder = root.resolve(player.toString()).resolve(item.incident());
            Optional<RestoreMerge.Piece> piece =
                    entry.isPresent() ? IncidentFiles.piece(folder, entry.get()) : Optional.empty();
            if (piece.isEmpty()) {
                failed.put(item, "its payload file is missing");
                continue;
            }
            String key = item.incident() + "/" + item.entry();
            byKey.put(key, item);
            pieces.add(new RestoreMerge.Piece(
                    key, item.entry(), piece.get().data(), piece.get().dataVersion()));
        }
        RestoreMerge.Result merged = RestoreMerge.apply(current, pieces, attachmentContainer);
        merged.refused().forEach((key, reason) -> failed.put(byKey.get(key), reason));
        for (Map.Entry<RestoreQueue.Item, String> e : failed.entrySet()) {
            Restorer.mark(root, player, e.getKey(), EntryStatus.RESTORE_FAILED, e.getValue());
        }
        if (merged.applied().isEmpty()) {
            RestoreQueue.remove(root, player, queue);
            return Optional.empty();
        }

        Path backup = new IncidentStore(root.getParent()).create(player, LocalDateTime.now());
        List<String> originals = IncidentStore.backup(backup, sourceFiles);
        List<RestoreQueue.Item> applied =
                merged.applied().stream().map(byKey::get).toList();
        recordBackup(context.withFolder(backup, originals), merged, applied, current);

        List<RestorePending.Item> items = new ArrayList<>();
        for (String key : merged.applied()) {
            items.add(new RestorePending.Item(
                    byKey.get(key), merged.placed().get(key), merged.drops().get(key)));
        }
        List<NbtCompound> carry =
                RestorePending.read(root, player).map(RestorePending::carry).orElse(List.of());
        new RestorePending(backup.getFileName().toString(), currentSha, items, carry).write(root, player);
        RestoreQueue.remove(root, player, queue);
        return Optional.of(
                new Prepared(merged.data(), applied.size(), merged.replaced().size(), backup));
    }

    /**
     * Decides each entry of the join from where it ended, and returns what is left to drop. Call
     * {@link #delivered} once the drops are in the world.
     *
     * <ul>
     *   <li>In the world without a rescue: every entry is restored.
     *   <li>In the world after a rescue: an entry whose tags the rescue set aside, or every entry
     *       when the player did not join with the salvaged data, is {@code set_aside_again}, since
     *       the rescue incident now holds it; the others are restored.
     *   <li>Not in the world: the caller has put the data back as it was, so every entry is {@code
     *       restore_failed} and can be queued again.
     * </ul>
     *
     * Stacks with no free slot are never part of the data, so they are dropped whenever the
     * player gets in.
     */
    public static Delivery afterJoin(Path root, UUID player, End end) throws IOException {
        Optional<RestorePending> pending = RestorePending.read(root, player);
        if (pending.isEmpty()) {
            return Delivery.NOTHING;
        }
        RestorePending p = pending.get();
        if (!end.placed()) {
            for (RestorePending.Item item : p.items()) {
                Restorer.mark(root, player, item.entry(), EntryStatus.RESTORE_FAILED, end.reason());
            }
            new RestorePending(p.backup(), p.originalSha(), List.of(), p.carry()).write(root, player);
            return Delivery.NOTHING;
        }
        List<NbtCompound> drops = new ArrayList<>(p.carry());
        int restored = 0;
        int again = 0;
        for (RestorePending.Item item : p.items()) {
            drops.addAll(item.drops());
            if (end.incident() == null || (end.outcome() == Outcome.RESCUED && !setAside(item, end.setAside()))) {
                Restorer.mark(root, player, item.entry(), EntryStatus.RESTORED, null);
                restored++;
            } else {
                Restorer.mark(
                        root,
                        player,
                        item.entry(),
                        EntryStatus.SET_ASIDE_AGAIN,
                        "it was set aside again in incident " + end.incident());
                again++;
            }
        }
        new RestorePending(p.backup(), p.originalSha(), List.of(), drops).write(root, player);
        return new Delivery(drops, restored, again);
    }

    /** The drops of the last {@link #afterJoin} are in the world. */
    public static void delivered(Path root, UUID player) throws IOException {
        Optional<RestorePending> pending = RestorePending.read(root, player);
        if (pending.isPresent() && pending.get().items().isEmpty()) {
            Files.deleteIfExists(RestorePending.file(root, player));
        }
    }

    /** True when the rescue set aside one of the tags this entry put into the data. */
    static boolean setAside(RestorePending.Item item, List<NbtTag> setAside) {
        for (NbtTag placed : item.placed()) {
            for (NbtTag aside : setAside) {
                if (aside.equals(placed) || holds(aside, placed)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A whole section set aside holds the tag as one of its elements or values. */
    private static boolean holds(NbtTag section, NbtTag tag) {
        if (section instanceof NbtList list) {
            return list.elements().contains(tag);
        }
        if (section instanceof NbtCompound compound) {
            return compound.entries().containsValue(tag);
        }
        return false;
    }

    private static Optional<IncidentFiles.Entry> entry(Path root, UUID player, RestoreQueue.Item item)
            throws IOException {
        if (!Restorer.isIncidentName(item.incident())) {
            return Optional.empty();
        }
        Path folder = root.resolve(player.toString()).resolve(item.incident());
        if (!Files.isRegularFile(folder.resolve("report.json"))) {
            return Optional.empty();
        }
        return IncidentFiles.read(folder).entries().stream()
                .filter(e -> e.id().equals(item.entry()))
                .findFirst();
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
}
