package fr.palawi.playerdatarescue.core.restore;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.palawi.playerdatarescue.core.io.AtomicFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The entries an operator asked to restore, waiting for the player's next join (SPEC.md section
 * 7.1): {@code <world>/playerdatarescue/<uuid>/restore-queue.json}. The file survives a restart,
 * and the queue is emptied once the join that applies it has read it.
 */
public final class RestoreQueue {
    public static final String FILE_NAME = "restore-queue.json";
    private static final int SCHEMA_VERSION = 1;
    private static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** One queued entry of one incident. */
    public record Item(String incident, String entry) {}

    private RestoreQueue() {}

    public static Path file(Path root, UUID player) {
        return root.resolve(player.toString()).resolve(FILE_NAME);
    }

    public static List<Item> read(Path root, UUID player) throws IOException {
        Path file = file(root, player);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        List<Item> items = new ArrayList<>();
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            for (JsonElement element : json.getAsJsonArray("entries")) {
                JsonObject item = element.getAsJsonObject();
                items.add(new Item(
                        item.get("incident").getAsString(), item.get("entry").getAsString()));
            }
        } catch (RuntimeException e) {
            throw new IOException("cannot read " + file + ": " + e.getMessage(), e);
        }
        return items;
    }

    /** Adds items that are not queued yet; returns the ones added. */
    public static List<Item> add(Path root, UUID player, List<Item> items) throws IOException {
        List<Item> queue = new ArrayList<>(read(root, player));
        List<Item> added = new ArrayList<>();
        for (Item item : items) {
            if (!queue.contains(item)) {
                queue.add(item);
                added.add(item);
            }
        }
        if (!added.isEmpty()) {
            write(root, player, queue);
        }
        return added;
    }

    /** Takes items out of the queue, for example when an operator dismisses them. */
    public static void remove(Path root, UUID player, List<Item> items) throws IOException {
        List<Item> queue = new ArrayList<>(read(root, player));
        if (queue.removeAll(items)) {
            write(root, player, queue);
        }
    }

    private static void write(Path root, UUID player, List<Item> queue) throws IOException {
        Path file = file(root, player);
        if (queue.isEmpty()) {
            Files.deleteIfExists(file);
            return;
        }
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", SCHEMA_VERSION);
        json.addProperty("playerId", player.toString());
        JsonArray entries = new JsonArray();
        for (Item item : queue) {
            JsonObject entry = new JsonObject();
            entry.addProperty("incident", item.incident());
            entry.addProperty("entry", item.entry());
            entries.add(entry);
        }
        json.add("entries", entries);
        AtomicFiles.write(file, (GSON.toJson(json) + "\n").getBytes(StandardCharsets.UTF_8));
    }
}
