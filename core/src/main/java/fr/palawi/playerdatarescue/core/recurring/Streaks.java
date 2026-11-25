package fr.palawi.playerdatarescue.core.recurring;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fr.palawi.playerdatarescue.core.io.AtomicFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The streaks of each player (SPEC.md section 4.4): for each mod data key, how many joins in a row
 * set it aside. Kept in {@code <uuid>/streaks.json} with the player's name, so a restart does not
 * reset them and a record made by several players can name them all.
 */
final class Streaks {
    static final String FILE = "streaks.json";
    private static final int SCHEMA_VERSION = 1;

    /** A player's streaks, by key, and the name they had at their last failed join. */
    record Player(String name, Map<String, Integer> streaks) {}

    private final Path root;

    Streaks(Path root) {
        this.root = root;
    }

    Map<String, Integer> of(UUID player) throws IOException {
        Path file = file(player);
        return Files.isRegularFile(file) ? read(file).streaks() : Map.of();
    }

    boolean exists(UUID player) {
        return Files.isRegularFile(file(player));
    }

    /** Every player with a streak on {@code key}. */
    Map<UUID, Player> with(String key) throws IOException {
        Map<UUID, Player> players = new LinkedHashMap<>();
        if (!Files.isDirectory(root)) {
            return players;
        }
        try (Stream<Path> list = Files.list(root)) {
            for (Path dir : list.filter(Files::isDirectory).sorted().toList()) {
                Path file = dir.resolve(FILE);
                Optional<UUID> id = uuid(dir.getFileName().toString());
                if (id.isPresent() && Files.isRegularFile(file)) {
                    Player player = read(file);
                    if (player.streaks().containsKey(key)) {
                        players.put(id.get(), player);
                    }
                }
            }
        }
        return players;
    }

    /** Replaces the player's streaks; none removes the file. */
    void write(UUID player, String name, Map<String, Integer> streaks) throws IOException {
        Path file = file(player);
        if (streaks.isEmpty()) {
            Files.deleteIfExists(file);
            return;
        }
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", SCHEMA_VERSION);
        json.addProperty("name", name);
        JsonObject keys = new JsonObject();
        streaks.forEach(keys::addProperty);
        json.add("streaks", keys);
        AtomicFiles.write(file, (RecurringIssues.GSON.toJson(json) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    void clear(UUID player) throws IOException {
        Files.deleteIfExists(file(player));
    }

    /** Ends the streak of one key, once a record holds it. */
    void remove(UUID player, String key) throws IOException {
        Path file = file(player);
        if (!Files.isRegularFile(file)) {
            return;
        }
        Player current = read(file);
        Map<String, Integer> rest = new LinkedHashMap<>(current.streaks());
        rest.remove(key);
        write(player, current.name(), rest);
    }

    private Path file(UUID player) {
        return root.resolve(player.toString()).resolve(FILE);
    }

    private static Player read(Path file) throws IOException {
        JsonObject json = RecurringIssues.json(file);
        Map<String, Integer> streaks = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, JsonElement> e :
                    json.getAsJsonObject("streaks").entrySet()) {
                streaks.put(e.getKey(), e.getValue().getAsInt());
            }
            return new Player(json.has("name") ? json.get("name").getAsString() : "", streaks);
        } catch (RuntimeException e) {
            throw new IOException("cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    private static Optional<UUID> uuid(String name) {
        try {
            return Optional.of(UUID.fromString(name));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
