package fr.palawi.playerdatarescue.core.incident;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import fr.palawi.playerdatarescue.core.io.AtomicFiles;
import fr.palawi.playerdatarescue.core.nbt.NbtCompound;
import fr.palawi.playerdatarescue.core.nbt.NbtIo;
import fr.palawi.playerdatarescue.core.nbt.NbtTag;
import fr.palawi.playerdatarescue.core.nbt.NbtValue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * {@code <world>/playerdatarescue/} (SPEC.md section 6). Every file is written atomically, and the
 * original files are copied and checked before anything else is written.
 */
public final class IncidentStore {
    public static final String FOLDER = "playerdatarescue";
    /** Payloads above this size stay in {@code original/} only (ARCHITECTURE.md). */
    public static final long MAX_PAYLOAD_BYTES = 2L * 1024 * 1024;

    private static final DateTimeFormatter FOLDER_NAME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path root;

    public IncidentStore(Path worldDirectory) {
        this.root = worldDirectory.resolve(FOLDER);
    }

    public Path root() {
        return root;
    }

    /** Creates the folder of a new incident; {@code localTime} is the server's local time. */
    public Path create(UUID player, LocalDateTime localTime) throws IOException {
        Path playerDir = root.resolve(player.toString());
        String base = FOLDER_NAME.format(localTime);
        Path folder = playerDir.resolve(base);
        for (int n = 2; Files.exists(folder); n++) {
            folder = playerDir.resolve(base + "-" + n);
        }
        Files.createDirectories(folder.resolve("original"));
        return folder;
    }

    /**
     * Copies each existing source file unchanged into {@code original/} and checks the copy. Files
     * that do not exist are skipped; the names of the copied files are returned.
     */
    public static List<String> backup(Path incident, List<Path> sources) throws IOException {
        List<String> copied = new ArrayList<>();
        for (Path source : sources) {
            if (Files.isRegularFile(source)) {
                Path target = incident.resolve("original")
                        .resolve(source.getFileName().toString());
                AtomicFiles.copyVerified(source, target);
                copied.add(source.getFileName().toString());
            }
        }
        return copied;
    }

    /**
     * Writes the payload of one entry as compressed NBT: the unit under {@code data}, with the
     * data version it was saved with. Returns the file name, or empty when it is too large.
     */
    public static Optional<String> writePayload(
            Path incident, String entryId, NbtTag unit, Optional<Integer> dataVersion, List<String> taken)
            throws IOException {
        if (NbtIo.payloadSize(unit) > MAX_PAYLOAD_BYTES) {
            return Optional.empty();
        }
        NbtCompound root = new NbtCompound();
        dataVersion.ifPresent(version -> root.put("DataVersion", NbtValue.ofInt(version)));
        root.put("id", NbtValue.ofString(entryId));
        root.put("data", unit.copy());
        String base = entryId.replaceAll("[^A-Za-z0-9._-]", "_");
        String name = base + ".nbt";
        for (int n = 2; taken.contains(name); n++) {
            name = base + "-" + n + ".nbt";
        }
        taken.add(name);
        AtomicFiles.write(incident.resolve("quarantine").resolve(name), NbtIo.write(root, true));
        return Optional.of(name);
    }

    public static void writeReports(Path incident, Incident content) throws IOException {
        AtomicFiles.write(
                incident.resolve("quarantine.json"),
                Reports.quarantineJson(content).getBytes(StandardCharsets.UTF_8));
        AtomicFiles.write(incident.resolve("report.json"), Reports.json(content).getBytes(StandardCharsets.UTF_8));
        AtomicFiles.write(incident.resolve("report.txt"), Reports.text(content).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Keeps the latest {@code max} incidents of a player. Older incidents are removed only when
     * none of their entries can still be restored (quarantined, or a restore that failed and can
     * be queued again; SPEC.md section 6). Returns the removed folders.
     */
    public List<Path> prune(UUID player, int max) throws IOException {
        Path playerDir = root.resolve(player.toString());
        if (!Files.isDirectory(playerDir)) {
            return List.of();
        }
        List<Path> incidents;
        try (Stream<Path> list = Files.list(playerDir)) {
            incidents = list.filter(Files::isDirectory)
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString())
                            .reversed())
                    .toList();
        }
        List<Path> removed = new ArrayList<>();
        for (int i = max; i < incidents.size(); i++) {
            Path incident = incidents.get(i);
            if (!stillQuarantined(incident)) {
                deleteTree(incident);
                removed.add(incident);
            }
        }
        return removed;
    }

    /** True when {@code quarantine.json} lists an entry that can still be restored, or cannot be read. */
    static boolean stillQuarantined(Path incident) {
        Path file = incident.resolve("quarantine.json");
        if (!Files.isRegularFile(file)) {
            return true;
        }
        try {
            JsonElement json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            for (JsonElement entry : json.getAsJsonObject().getAsJsonArray("entries")) {
                String status = entry.getAsJsonObject().get("status").getAsString();
                if (EntryStatus.QUARANTINED.json().equals(status)
                        || EntryStatus.RESTORE_FAILED.json().equals(status)) {
                    return true;
                }
            }
            return false;
        } catch (IOException | RuntimeException e) {
            return true;
        }
    }

    private static void deleteTree(Path folder) throws IOException {
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(folder)) {
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            Files.delete(path);
        }
    }
}
