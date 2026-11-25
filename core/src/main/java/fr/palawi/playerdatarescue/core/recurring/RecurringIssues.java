package fr.palawi.playerdatarescue.core.recurring;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.palawi.playerdatarescue.core.attribution.Suspect;
import fr.palawi.playerdatarescue.core.data.PlayerData;
import fr.palawi.playerdatarescue.core.data.Unit;
import fr.palawi.playerdatarescue.core.incident.Outcome;
import fr.palawi.playerdatarescue.core.io.AtomicFiles;
import fr.palawi.playerdatarescue.core.nbt.NbtCompound;
import fr.palawi.playerdatarescue.core.nbt.NbtIo;
import fr.palawi.playerdatarescue.core.nbt.NbtTag;
import fr.palawi.playerdatarescue.core.nbt.NbtValue;
import fr.palawi.playerdatarescue.core.nbt.Snbt;
import fr.palawi.playerdatarescue.core.rescue.RescueResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Recurring issues (SPEC.md section 4.4). A mod data key set aside for the same player at {@code
 * threshold} joins in a row, or for {@code threshold} different players, gets one record under
 * {@code <world>/playerdatarescue/recurring/} that counts the later joins too. The record only
 * groups: every join keeps its own incident, so nothing is lost.
 */
public final class RecurringIssues {
    public static final String FOLDER = "recurring";
    public static final String STREAKS = Streaks.FILE;
    static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int SCHEMA_VERSION = 1;
    private static final DateTimeFormatter UTC = DateTimeFormatter.ISO_INSTANT;
    private static final DateTimeFormatter CLOSED =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final List<String> FILES = List.of(".first.nbt", ".latest.nbt", ".json");

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

    /** The records a join created and those it counted in. */
    public record Recorded(List<Issue> created, List<Issue> updated) {
        public List<Issue> touched() {
            List<Issue> all = new ArrayList<>(created);
            all.addAll(updated);
            return all;
        }
    }

    private final Path root;
    private final Streaks streaks;

    /** {@code root} is {@code <world>/playerdatarescue}. */
    public RecurringIssues(Path root) {
        this.root = root;
        this.streaks = new Streaks(root);
    }

    public Path folder() {
        return root.resolve(FOLDER);
    }

    /**
     * The failures of a rescue that count towards recurring issues, or empty when the rescue does
     * not count at all. Only a rescue that ends with the salvaged data counts, and only its mod data
     * units: an attachment or an unknown top-level key, which a mod writes again at every save. A
     * slot or an effect is gone from the data once set aside, and a minimal profile sets whole
     * sections aside, not one key. A unit counts only when the rescue singled out its mod: when one
     * failed load made it set aside the units of several mods at once, because no suspect matched,
     * it cannot tell which one broke, so none of them counts. A key without a namespace is its own
     * mod here, since nothing ties it to another key.
     */
    public static Optional<List<Failure>> failures(RescueResult result) {
        if (result.outcome() != Outcome.RESCUED) {
            return Optional.empty();
        }
        Map<Throwable, Set<String>> namespaces = new IdentityHashMap<>();
        for (RescueResult.SetAside aside : result.aside()) {
            namespaces.computeIfAbsent(aside.failure(), f -> new HashSet<>()).add(owner(aside.unit()));
        }
        return Optional.of(result.aside().stream()
                .filter(a -> modData(a.unit()))
                .filter(a -> namespaces.get(a.failure()).size() == 1)
                .map(a -> new Failure(
                        a.unit().id(),
                        a.unit().payload(),
                        a.suspects().stream().findFirst().map(Suspect::modId)))
                .toList());
    }

    /** The mod a unit belongs to, as far as the data says; a key without a namespace stands alone. */
    private static String owner(Unit unit) {
        return unit.namespace().map(n -> "mod:" + n).orElse("unit:" + unit.id());
    }

    static boolean modData(Unit unit) {
        return unit.section().opaque()
                && (unit.id().startsWith("attachment.") || unit.id().startsWith("key."));
    }

    /**
     * Records the failures of one join of {@code player}. A key with a record counts this join in
     * it; a key whose streak, or whose number of players with a streak, reaches {@code threshold}
     * gets a record now. The streaks of the player's other keys end.
     */
    public Recorded record(UUID player, String name, List<Failure> failures, int threshold, Instant now)
            throws IOException {
        if (threshold < 2) {
            throw new IllegalArgumentException("threshold must be at least 2, was " + threshold);
        }
        Map<String, Integer> current = streaks.of(player);
        Map<String, Integer> next = new LinkedHashMap<>();
        List<Issue> created = new ArrayList<>();
        List<Issue> updated = new ArrayList<>();
        for (Failure failure : failures) {
            String key = failure.key();
            Optional<Issue> existing;
            try {
                existing = find(key);
            } catch (IOException e) {
                // An unreadable record of this key is left as it is, named by unreadable(); the
                // other keys of the join still count.
                if (current.containsKey(key)) {
                    next.put(key, current.get(key));
                }
                continue;
            }
            if (existing.isPresent()) {
                updated.add(update(failure, player, name, now));
                continue;
            }
            int streak = current.getOrDefault(key, 0) + 1;
            Map<UUID, Streaks.Player> others = streaks.with(key);
            others.remove(player);
            if (streak < threshold && others.size() + 1 < threshold) {
                next.put(key, streak);
                continue;
            }
            Map<UUID, String> players = new LinkedHashMap<>();
            int count = streak;
            for (Map.Entry<UUID, Streaks.Player> other : others.entrySet()) {
                players.put(other.getKey(), other.getValue().name());
                count += other.getValue().streaks().get(key);
            }
            players.put(player, name);
            created.add(create(failure, players, count, now));
            for (UUID other : others.keySet()) {
                streaks.remove(other, key);
            }
        }
        streaks.write(player, name, next);
        return new Recorded(created, updated);
    }

    /**
     * A join of {@code player} without a failure, whose saved data holds {@code keys}: every streak
     * of the player ends, and the records of this player whose key is in the data close, since
     * that key loads again. Returns the closed records.
     */
    public List<Issue> joined(UUID player, Set<String> keys, Instant now) throws IOException {
        streaks.clear(player);
        List<Issue> closed = new ArrayList<>();
        for (Issue issue : all()) {
            if (issue.players().containsKey(player) && keys.contains(issue.key())) {
                close(issue, now);
                closed.add(issue);
            }
        }
        return closed;
    }

    /** True when the player has a streak or appears in a record, so a normal join must be looked at. */
    public boolean concerns(UUID player) throws IOException {
        if (streaks.exists(player)) {
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
     * Closes a record: it moves to {@code recurring/closed/} with its payloads, nothing is deleted.
     * The record file moves last: if a move fails, the record stays open and still holds both
     * payloads as SNBT, and a payload file already moved is in {@code closed/}.
     */
    public void close(Issue issue, Instant now) throws IOException {
        Path closed = folder().resolve("closed");
        Files.createDirectories(closed);
        String base = fileName(issue.key());
        String target = base + "-" + CLOSED.format(now);
        for (int n = 2; taken(closed, target); n++) {
            target = base + "-" + CLOSED.format(now) + "-" + n;
        }
        for (String suffix : FILES) {
            Path file = folder().resolve(base + suffix);
            if (Files.isRegularFile(file)) {
                Files.move(file, closed.resolve(target + suffix));
            }
        }
    }

    public Optional<Issue> find(String key) throws IOException {
        Path file = folder().resolve(fileName(key) + ".json");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        Issue issue = read(file);
        if (!issue.key().equals(key)) {
            throw new IOException(file + " holds the record of " + issue.key() + ", not " + key);
        }
        return Optional.of(issue);
    }

    /**
     * Every open record that can be read, by file name. A record edited into an unreadable state is
     * left out, so it does not stop the others; {@link #unreadable} names it.
     */
    public List<Issue> all() throws IOException {
        List<Issue> issues = new ArrayList<>();
        for (Path file : recordFiles()) {
            try {
                issues.add(read(file));
            } catch (IOException e) {
                // Named by unreadable().
            }
        }
        return issues;
    }

    /** The record files {@link #all} leaves out. */
    public List<Path> unreadable() throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path file : recordFiles()) {
            try {
                read(file);
            } catch (IOException e) {
                files.add(file);
            }
        }
        return files;
    }

    private List<Path> recordFiles() throws IOException {
        if (!Files.isDirectory(folder())) {
            return List.of();
        }
        try (Stream<Path> list = Files.list(folder())) {
            return list.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .toList();
        }
    }

    /** The keys of a player's saved data, as the rescue names its units, for {@link #joined}. */
    public static Set<String> keys(NbtCompound data) {
        Set<String> keys = new LinkedHashSet<>();
        for (String container : PlayerData.ATTACHMENT_CONTAINERS) {
            data.compound(container).ifPresent(c -> c.keys().forEach(k -> keys.add("attachment." + k)));
        }
        data.keys().forEach(k -> keys.add("key." + k));
        return keys;
    }

    private Issue create(Failure failure, Map<UUID, String> players, int count, Instant now) throws IOException {
        String base = fileName(failure.key());
        String snbt = Snbt.write(failure.payload());
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", SCHEMA_VERSION);
        json.addProperty("key", failure.key());
        failure.suspect().ifPresent(s -> json.addProperty("suspect", s));
        JsonObject names = new JsonObject();
        players.forEach((id, name) -> names.addProperty(id.toString(), name));
        json.add("players", names);
        json.addProperty("first", UTC.format(now));
        json.addProperty("last", UTC.format(now));
        json.addProperty("count", count);
        json.addProperty("firstPayload", snbt);
        json.addProperty("firstPayloadFile", base + ".first.nbt");
        json.addProperty("latestPayload", snbt);
        json.addProperty("latestPayloadFile", base + ".latest.nbt");
        AtomicFiles.write(folder().resolve(base + ".first.nbt"), payload(failure.key(), failure.payload()));
        AtomicFiles.write(folder().resolve(base + ".latest.nbt"), payload(failure.key(), failure.payload()));
        return write(base, json);
    }

    private Issue update(Failure failure, UUID player, String name, Instant now) throws IOException {
        String base = fileName(failure.key());
        JsonObject json = json(folder().resolve(base + ".json"));
        if (!json.has("suspect")) {
            failure.suspect().ifPresent(s -> json.addProperty("suspect", s));
        }
        json.getAsJsonObject("players").addProperty(player.toString(), name);
        json.addProperty("last", UTC.format(now));
        json.addProperty("count", json.get("count").getAsInt() + 1);
        json.addProperty("latestPayload", Snbt.write(failure.payload()));
        AtomicFiles.write(folder().resolve(base + ".latest.nbt"), payload(failure.key(), failure.payload()));
        return write(base, json);
    }

    private Issue write(String base, JsonObject json) throws IOException {
        Path file = folder().resolve(base + ".json");
        AtomicFiles.write(file, (GSON.toJson(json) + "\n").getBytes(StandardCharsets.UTF_8));
        return read(file);
    }

    private static boolean taken(Path folder, String base) {
        return FILES.stream().anyMatch(suffix -> Files.exists(folder.resolve(base + suffix)));
    }

    private static byte[] payload(String key, NbtTag data) {
        NbtCompound root = new NbtCompound();
        root.put("id", NbtValue.ofString(key));
        root.put("data", data.copy());
        return NbtIo.write(root, true);
    }

    private static Issue read(Path file) throws IOException {
        JsonObject json = json(file);
        try {
            Map<UUID, String> players = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e :
                    json.getAsJsonObject("players").entrySet()) {
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
        } catch (RuntimeException e) {
            throw new IOException("cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    static JsonObject json(Path file) throws IOException {
        try {
            return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * A file name for a key such as {@code attachment.examplemod:mana}: the key with its unsafe
     * characters replaced, then a hash of the exact key, so two keys never share a file, not even on
     * a file system that ignores case.
     */
    static String fileName(String key) {
        String safe = key.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.length() > 64) {
            safe = safe.substring(0, 64);
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return safe + "-" + HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing", e);
        }
    }
}
