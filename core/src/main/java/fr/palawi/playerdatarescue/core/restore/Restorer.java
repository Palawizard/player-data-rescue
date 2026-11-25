package fr.palawi.playerdatarescue.core.restore;

import fr.palawi.playerdatarescue.core.incident.EntryStatus;
import fr.palawi.playerdatarescue.core.incident.IncidentFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The command side of a restore (SPEC.md section 7.1): an operator's command only queues entries,
 * and the player's next join puts them back ({@link RestoreJoin}). An entry is queued only while
 * its payload is nowhere else: not restored, not set aside again in a later incident, and not
 * already on its way into the data, so restoring twice cannot duplicate anything.
 */
public final class Restorer {
    private static final String INCIDENT_NAME = "[0-9]{8}-[0-9]{6}(-[0-9]+)?";

    private Restorer() {}

    /** What a restore command did. */
    public record Queued(List<String> queued, List<String> alreadyRestored, Map<String, String> notRestorable) {}

    /**
     * Queues one entry, or with {@code entry} "all" every entry not dismissed (dismissed entries
     * only when named).
     */
    public static Queued queue(Path root, UUID player, String incident, String entry) throws IOException {
        List<IncidentFiles.Entry> chosen = chosen(root, player, incident, entry, e -> e != EntryStatus.DISMISSED);
        Optional<RestorePending> pending = RestorePending.read(root, player);
        List<String> already = new ArrayList<>();
        Map<String, String> notRestorable = new LinkedHashMap<>();
        List<RestoreQueue.Item> items = new ArrayList<>();
        for (IncidentFiles.Entry e : chosen) {
            RestoreQueue.Item item = new RestoreQueue.Item(incident, e.id());
            if (e.status() == EntryStatus.RESTORED) {
                already.add(e.id());
            } else if (e.status() == EntryStatus.SET_ASIDE_AGAIN) {
                notRestorable.put(
                        e.id(),
                        e.statusReason().orElse("it was set aside again in a later incident")
                                + "; restore it from there");
            } else if (pending.isPresent() && pending.get().holds(item)) {
                notRestorable.put(e.id(), "it is going back into the data at the player's current join");
            } else if (e.id().equals("vehicle") || e.id().equals("section.RootVehicle")) {
                notRestorable.put(e.id(), "vehicles cannot be restored yet; the payload is kept for manual recovery");
            } else if (e.payload().isEmpty()) {
                notRestorable.put(e.id(), "it was too large to keep apart; it is only in original/");
            } else {
                items.add(item);
            }
        }
        // An entry queued by an earlier command stays queued once.
        RestoreQueue.add(root, player, items);
        return new Queued(items.stream().map(RestoreQueue.Item::entry).toList(), already, notRestorable);
    }

    /**
     * Marks one entry, or with {@code entry} "all" every entry still set aside, as dismissed, and
     * takes it out of the queue. Returns the ids dismissed. The data stays on disk.
     */
    public static List<String> dismiss(Path root, UUID player, String incident, String entry) throws IOException {
        Path folder = incidentFolder(root, player, incident);
        Optional<RestorePending> pending = RestorePending.read(root, player);
        Map<String, EntryStatus> statuses = new LinkedHashMap<>();
        List<RestoreQueue.Item> unqueue = new ArrayList<>();
        for (IncidentFiles.Entry e : chosen(root, player, incident, entry, EntryStatus::dismissable)) {
            RestoreQueue.Item item = new RestoreQueue.Item(incident, e.id());
            if (e.status().dismissable() && (pending.isEmpty() || !pending.get().holds(item))) {
                statuses.put(e.id(), EntryStatus.DISMISSED);
                unqueue.add(item);
            }
        }
        IncidentFiles.setStatus(folder, statuses, Map.of());
        RestoreQueue.remove(root, player, unqueue);
        return List.copyOf(statuses.keySet());
    }

    /** The folder of {@code incident}, refused unless it is a real incident of this player. */
    public static Path incidentFolder(Path root, UUID player, String incident) {
        if (!incident.matches(INCIDENT_NAME)) {
            throw new IllegalArgumentException("no incident " + incident);
        }
        Path folder = root.resolve(player.toString()).resolve(incident);
        if (!Files.isRegularFile(folder.resolve("report.json"))) {
            throw new IllegalArgumentException("no incident " + incident + " for this player");
        }
        return folder;
    }

    static boolean isIncidentName(String incident) {
        return incident.matches(INCIDENT_NAME);
    }

    /** Sets the status of one entry, when its incident still exists. */
    static void mark(Path root, UUID player, RestoreQueue.Item item, EntryStatus status, String reason)
            throws IOException {
        if (!isIncidentName(item.incident())) {
            return;
        }
        Path folder = root.resolve(player.toString()).resolve(item.incident());
        if (Files.isDirectory(folder)) {
            IncidentFiles.setStatus(
                    folder, Map.of(item.entry(), status), reason == null ? Map.of() : Map.of(item.entry(), reason));
        }
    }

    private static List<IncidentFiles.Entry> chosen(
            Path root, UUID player, String incident, String entry, Predicate<EntryStatus> forAll) throws IOException {
        IncidentFiles.Summary summary = IncidentFiles.read(incidentFolder(root, player, incident));
        boolean all = entry.equals("all");
        List<IncidentFiles.Entry> chosen = summary.entries().stream()
                .filter(e -> all ? forAll.test(e.status()) : e.id().equals(entry))
                .toList();
        if (!all && chosen.isEmpty()) {
            throw new IllegalArgumentException("incident " + incident + " has no entry " + entry);
        }
        return chosen;
    }
}
