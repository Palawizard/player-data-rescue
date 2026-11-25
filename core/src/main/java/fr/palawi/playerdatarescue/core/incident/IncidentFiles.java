package fr.palawi.playerdatarescue.core.incident;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.palawi.playerdatarescue.core.io.AtomicFiles;
import fr.palawi.playerdatarescue.core.nbt.NbtCompound;
import fr.palawi.playerdatarescue.core.nbt.NbtIo;
import fr.palawi.playerdatarescue.core.restore.RestoreMerge;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Reads the incidents the store wrote, and changes the status of their entries, for the commands
 * of SPEC.md section 7. The statuses live in {@code quarantine.json} and {@code report.json}, and
 * both files are rewritten atomically.
 */
public final class IncidentFiles {
    private static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private IncidentFiles() {}

    /** One entry as the reports list it. */
    public record Entry(
            String id,
            String description,
            EntryStatus status,
            Optional<String> payload,
            Optional<String> statusReason) {}

    /** What the commands show of an incident. */
    public record Summary(
            UUID playerId,
            String playerName,
            String folder,
            String time,
            String trigger,
            String outcome,
            String summary,
            String nextStep,
            List<Entry> entries) {

        public long count(EntryStatus status) {
            return entries.stream().filter(e -> e.status() == status).count();
        }
    }

    /** The players with at least one incident, by UUID. */
    public static List<UUID> players(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<UUID> players = new ArrayList<>();
        try (Stream<Path> list = Files.list(root)) {
            for (Path dir : list.filter(Files::isDirectory).toList()) {
                parseUuid(dir.getFileName().toString()).ifPresent(players::add);
            }
        }
        players.sort(Comparator.comparing(UUID::toString));
        return players;
    }

    /** The incident folders of a player, oldest first. */
    public static List<Path> incidents(Path root, UUID player) throws IOException {
        Path dir = root.resolve(player.toString());
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> list = Files.list(dir)) {
            return list.filter(p -> Files.isRegularFile(p.resolve("report.json")))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }
    }

    /**
     * A player of the store by UUID, or by the name their latest incident was written with
     * (case ignored), for players who are not online.
     */
    public static Optional<UUID> findPlayer(Path root, String nameOrUuid) throws IOException {
        Optional<UUID> uuid = parseUuid(nameOrUuid);
        if (uuid.isPresent()) {
            return uuid;
        }
        for (UUID player : players(root)) {
            List<Path> incidents = incidents(root, player);
            if (!incidents.isEmpty()
                    && read(incidents.get(incidents.size() - 1)).playerName().equalsIgnoreCase(nameOrUuid)) {
                return Optional.of(player);
            }
        }
        return Optional.empty();
    }

    public static Summary read(Path incident) throws IOException {
        JsonObject report = json(incident.resolve("report.json"));
        List<Entry> entries = new ArrayList<>();
        for (JsonElement element : report.getAsJsonArray("entries")) {
            JsonObject entry = element.getAsJsonObject();
            entries.add(new Entry(
                    entry.get("id").getAsString(),
                    entry.get("description").getAsString(),
                    EntryStatus.valueOf(entry.get("status").getAsString().toUpperCase(Locale.ROOT)),
                    optional(entry, "payload"),
                    optional(entry, "statusReason")));
        }
        return new Summary(
                UUID.fromString(report.get("playerId").getAsString()),
                report.get("playerName").getAsString(),
                report.get("incident").getAsString(),
                report.get("time").getAsString(),
                report.get("trigger").getAsString(),
                report.get("outcome").getAsString(),
                report.get("summary").getAsString(),
                report.get("nextStep").getAsString(),
                entries);
    }

    /**
     * Sets the status of some entries, with an optional reason (the error of a failed restore).
     * Entries not named keep theirs.
     */
    public static void setStatus(Path incident, Map<String, EntryStatus> statuses, Map<String, String> reasons)
            throws IOException {
        for (String file : List.of("quarantine.json", "report.json")) {
            Path path = incident.resolve(file);
            if (!Files.isRegularFile(path)) {
                continue;
            }
            JsonObject root = json(path);
            JsonArray entries = root.getAsJsonArray("entries");
            for (JsonElement element : entries) {
                JsonObject entry = element.getAsJsonObject();
                String id = entry.get("id").getAsString();
                EntryStatus status = statuses.get(id);
                if (status == null) {
                    continue;
                }
                entry.addProperty("status", status.json());
                if (reasons.containsKey(id)) {
                    entry.addProperty("statusReason", reasons.get(id));
                } else {
                    entry.remove("statusReason");
                }
            }
            AtomicFiles.write(path, (GSON.toJson(root) + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    /** The payload of one entry, as {@link IncidentStore#writePayload} wrote it. */
    public static Optional<RestoreMerge.Piece> piece(Path incident, Entry entry) throws IOException {
        if (entry.payload().isEmpty()) {
            return Optional.empty();
        }
        Path file = incident.resolve(entry.payload().get()).normalize();
        if (!file.startsWith(incident.normalize()) || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        NbtCompound root = NbtIo.read(file);
        if (!root.contains("data")) {
            return Optional.empty();
        }
        return Optional.of(new RestoreMerge.Piece(
                entry.id(), root.get("data"), root.number("DataVersion").map(Number::intValue)));
    }

    private static JsonObject json(Path file) throws IOException {
        try {
            return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    private static Optional<String> optional(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? Optional.empty() : Optional.of(value.getAsString());
    }

    private static Optional<UUID> parseUuid(String text) {
        try {
            return text.length() == 36 ? Optional.of(UUID.fromString(text)) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
