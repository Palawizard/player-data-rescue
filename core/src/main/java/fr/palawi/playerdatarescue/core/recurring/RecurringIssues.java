package fr.palawi.playerdatarescue.core.recurring;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.palawi.playerdatarescue.core.io.AtomicFiles;
import fr.palawi.playerdatarescue.core.nbt.NbtCompound;
import fr.palawi.playerdatarescue.core.nbt.NbtIo;
import fr.palawi.playerdatarescue.core.nbt.NbtTag;
import fr.palawi.playerdatarescue.core.nbt.NbtValue;
import fr.palawi.playerdatarescue.core.nbt.Snbt;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Recurring issues (SPEC.md section 4.4). A unit key set aside for the same player at {@code
 * threshold} joins in a row, or for {@code threshold} different players, gets one record under
 * {@code <world>/playerdatarescue/recurring/}; later failures of that key update the record
 * instead of making incidents. The streak of each player is kept in {@code <uuid>/streaks.json},
 * so it survives a restart, and a join without that failure ends it.
 */
public final class RecurringIssues {
    public static final String FOLDER = "recurring";
    public static final String STREAKS = "streaks.json";
    private static final int SCHEMA_VERSION = 1;
    private static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final DateTimeFormatter UTC = DateTimeFormatter.ISO_INSTANT;
    private static final DateTimeFormatter CLOSED =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    /** A unit set aside at this join, with the mod most likely behind it. */
    public record Failure(String key, NbtTag payload, Optional<String> suspect) {}

    /** One record, as the commands and the operator message show it. */
    public record Issue(
            String key,
            Optional<String> suspect,
            Map<UUID, String> players,
            String first,
            String last,
            int count,
            Path file) {}

    /**
     * What a join's failures mean: the keys handled by a record (no incident entry for them), and
     * the records this join created.
     */
    public record Outcome(Set<String> recurring, List<Issue> created, List<Issue> updated) {}

    private final Path root;

    /** {@code root} is {@code <world>/playerdatarescue}. */
    public RecurringIssues(Path root) {
        this.root = root;
    }

    public Path folder() {
        return root.resolve(FOLDER);
    }

    /**
     * Records the failures of one join of {@code player}. Keys that already have a record, or whose
     * streak reaches {@code threshold} now, are recurring; the streaks of the player's other keys end.
     */
    public Outcome record(UUID player, String name, List<Failure> failures, int threshold, Instant now)
            throws IOException {
        Map<String, Integer> streaks = new LinkedHashMap<>(streaks(player));
        Map<String, Integer> next = new LinkedHashMap<>();
        Set<String> recurring = new LinkedHashSet<>();
        List<Issue> created = new ArrayList<>();
        List<Issue> updated = new ArrayList<>();
        for (Failure failure : failures) {
            String key = failure.key();
            int streak = streaks.getOrDefault(key, 0) + 1;
            Optional<Issue> existing = find(key);
            if (existing.isPresent()) {
                updated.add(write(key, failure, player, name, now, existing, 1));
                recurring.add(key);
                continue;
            }
            Set<UUID> players = playersWith(key);
            players.add(player);
            if (streak >= threshold || players.size() >= threshold) {
                created.add(write(key, failure, player, name, now, Optional.empty(), streak));
                recurring.add(key);
            } else {
                next.put(key, streak);
            }
        }
        writeStreaks(player, next);
        return new Outcome(recurring, created, updated);
    }

    /** A join without a failure: every streak of the player ends. */
    public void joined(UUID player) throws IOException {
        writeStreaks(player, Map.of());
    }

    /** True when the player has a streak or appears in a record, so a normal join must be looked at. */
    public boolean concerns(UUID player) throws IOException {
        if (Files.isRegularFile(streaksFile(player))) {
            return true;
        }
        for (Issue issue : all()) {
            if (issue.players().containsKey(player)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The player joined without a failure and their data holds {@code keys}: those keys load
     * again, so their records close. Returns the closed ones.
     */
    public List<Issue> loaded(Set<String> keys, Instant now) throws IOException {
        List<Issue> closed = new ArrayList<>();
        for (Issue issue : all()) {
            if (keys.contains(issue.key())) {
                close(issue, now);
                closed.add(issue);
            }
        }
        return closed;
    }

    /** Closes a record: it moves to {@code recurring/closed/} with its payloads, nothing is deleted. */
    public void close(Issue issue, Instant now) throws IOException {
        Path closed = folder().resolve("closed");
        Files.createDirectories(closed);
        String stamp = CLOSED.format(now);
        String base = fileName(issue.key());
        for (String suffix : List.of(".json", ".first.nbt", ".latest.nbt")) {
            Path file = folder().resolve(base + suffix);
            if (Files.isRegularFile(file)) {
                Files.move(file, closed.resolve(base + "-" + stamp + suffix));
            }
        }
    }

    public Optional<Issue> find(String key) throws IOException {
        Path file = folder().resolve(fileName(key) + ".json");
        return Files.isRegularFile(file) ? Optional.of(read(file)) : Optional.empty();
    }

    /** Every open record, by key. */
    public List<Issue> all() throws IOException {
        if (!Files.isDirectory(folder())) {
            return List.of();
        }
        List<Issue> issues = new ArrayList<>();
        try (Stream<Path> list = Files.list(folder())) {
            for (Path file : list.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList()) {
                issues.add(read(file));
            }
        }
        return issues;
    }

    /** The keys of a player's saved data, as the rescue names its units, for {@link #loaded}. */
    public static Set<String> keys(NbtCompound data) {
        Set<String> keys = new LinkedHashSet<>();
        for (String container : List.of("ForgeCaps", "neoforge:attachments")) {
            data.compound(container).ifPresent(c -> c.keys().forEach(k -> keys.add("attachment." + k)));
        }
        data.keys().forEach(k -> keys.add("key." + k));
        return keys;
    }

    /** {@code failures} is added to the record's count: the streak that made it, then 1 per join. */
    private Issue write(
            String key, Failure failure, UUID player, String name, Instant now, Optional<Issue> existing, int failures)
            throws IOException {
        String base = fileName(key);
        Path file = folder().resolve(base + ".json");
        JsonObject json = existing.isPresent() ? json(file) : new JsonObject();
        json.addProperty("schemaVersion", SCHEMA_VERSION);
        json.addProperty("key", key);
        if (failure.suspect().isPresent()) {
            json.addProperty("suspect", failure.suspect().get());
        }
        JsonObject players = json.has("players") ? json.getAsJsonObject("players") : new JsonObject();
        players.addProperty(player.toString(), name);
        json.add("players", players);
        if (!json.has("first")) {
            json.addProperty("first", UTC.format(now));
        }
        json.addProperty("last", UTC.format(now));
        json.addProperty("count", (json.has("count") ? json.get("count").getAsInt() : 0) + failures);
        String snbt = Snbt.write(failure.payload());
        if (!json.has("firstPayload")) {
            json.addProperty("firstPayload", snbt);
            json.addProperty("firstPayloadFile", base + ".first.nbt");
            AtomicFiles.write(folder().resolve(base + ".first.nbt"), payload(key, failure.payload()));
        }
        json.addProperty("latestPayload", snbt);
        json.addProperty("latestPayloadFile", base + ".latest.nbt");
        AtomicFiles.write(folder().resolve(base + ".latest.nbt"), payload(key, failure.payload()));
        AtomicFiles.write(file, (GSON.toJson(json) + "\n").getBytes(StandardCharsets.UTF_8));
        return read(file);
    }

    private static byte[] payload(String key, NbtTag data) {
        NbtCompound root = new NbtCompound();
        root.put("id", NbtValue.ofString(key));
        root.put("data", data.copy());
        return NbtIo.write(root, true);
    }

    private Set<UUID> playersWith(String key) throws IOException {
        Set<UUID> players = new LinkedHashSet<>();
        if (!Files.isDirectory(root)) {
            return players;
        }
        try (Stream<Path> list = Files.list(root)) {
            for (Path dir : list.filter(Files::isDirectory).toList()) {
                Path file = dir.resolve(STREAKS);
                if (Files.isRegularFile(file) && readStreaks(file).containsKey(key)) {
                    players.add(UUID.fromString(dir.getFileName().toString()));
                }
            }
        }
        return players;
    }

    private Map<String, Integer> streaks(UUID player) throws IOException {
        Path file = streaksFile(player);
        return Files.isRegularFile(file) ? readStreaks(file) : Map.of();
    }

    private Path streaksFile(UUID player) {
        return root.resolve(player.toString()).resolve(STREAKS);
    }

    private void writeStreaks(UUID player, Map<String, Integer> streaks) throws IOException {
        Path file = streaksFile(player);
        if (streaks.isEmpty()) {
            Files.deleteIfExists(file);
            return;
        }
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", SCHEMA_VERSION);
        JsonObject keys = new JsonObject();
        streaks.forEach(keys::addProperty);
        json.add("streaks", keys);
        AtomicFiles.write(file, (GSON.toJson(json) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Integer> readStreaks(Path file) throws IOException {
        Map<String, Integer> streaks = new LinkedHashMap<>();
        JsonObject keys = json(file).getAsJsonObject("streaks");
        for (Map.Entry<String, JsonElement> e : keys.entrySet()) {
            streaks.put(e.getKey(), e.getValue().getAsInt());
        }
        return streaks;
    }

    private static Issue read(Path file) throws IOException {
        JsonObject json = json(file);
        Map<UUID, String> players = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("players").entrySet()) {
            players.put(UUID.fromString(e.getKey()), e.getValue().getAsString());
        }
        return new Issue(
                json.get("key").getAsString(),
                json.has("suspect") ? Optional.of(json.get("suspect").getAsString()) : Optional.empty(),
                players,
                json.get("first").getAsString(),
                json.get("last").getAsString(),
                json.get("count").getAsInt(),
                file);
    }

    private static JsonObject json(Path file) throws IOException {
        try {
            return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    /** A file name for a key such as {@code attachment.examplemod:mana}. */
    static String fileName(String key) {
        return key.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
