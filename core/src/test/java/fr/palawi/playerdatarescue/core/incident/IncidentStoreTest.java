package fr.palawi.playerdatarescue.core.incident;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.palawi.playerdatarescue.core.nbt.NbtCompound;
import fr.palawi.playerdatarescue.core.nbt.NbtIo;
import fr.palawi.playerdatarescue.core.nbt.NbtValue;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IncidentStoreTest {
    private static final UUID PLAYER = UUID.fromString("422e1f9b-773c-337f-ba88-76d471030d8a");
    private static final LocalDateTime TIME = LocalDateTime.of(2026, 11, 16, 14, 5, 9);

    @TempDir
    Path world;

    @Test
    void foldersAreNamedByLocalTimeWithACounter() throws Exception {
        IncidentStore store = new IncidentStore(world);

        Path first = store.create(PLAYER, TIME);
        Path second = store.create(PLAYER, TIME);

        assertEquals(world.resolve("playerdatarescue/422e1f9b-773c-337f-ba88-76d471030d8a/20261116-140509"), first);
        assertEquals(first.resolveSibling("20261116-140509-2"), second);
        assertTrue(Files.isDirectory(first.resolve("original")));
    }

    @Test
    void backupCopiesExistingFilesUnchanged() throws Exception {
        Path data = Files.createDirectories(world.resolve("playerdata"));
        Path dat = Files.write(data.resolve(PLAYER + ".dat"), new byte[] {31, -117, 8, 0, 1, 2, 3});
        Path old = data.resolve(PLAYER + ".dat_old");
        Path incident = new IncidentStore(world).create(PLAYER, TIME);

        List<String> copied = IncidentStore.backup(incident, List.of(dat, old));

        assertEquals(List.of(PLAYER + ".dat"), copied);
        assertEquals(-1L, Files.mismatch(dat, incident.resolve("original").resolve(PLAYER + ".dat")));
        assertFalse(Files.exists(incident.resolve("original").resolve(PLAYER + ".dat_old")));
    }

    @Test
    void payloadsAreCompressedNbtWithTheDataVersion() throws Exception {
        Path incident = new IncidentStore(world).create(PLAYER, TIME);
        List<String> taken = new ArrayList<>();

        Optional<String> first = IncidentStore.writePayload(
                incident, "attachment.pdrtest:broken", NbtValue.ofInt(42), Optional.of(3465), taken);
        Optional<String> second = IncidentStore.writePayload(
                incident, "attachment.pdrtest/broken", NbtValue.ofInt(7), Optional.of(3465), taken);

        assertEquals(Optional.of("attachment.pdrtest_broken.nbt"), first);
        assertEquals(Optional.of("attachment.pdrtest_broken-2.nbt"), second);
        NbtCompound read = NbtIo.read(incident.resolve("quarantine/attachment.pdrtest_broken.nbt"));
        assertEquals(NbtValue.ofInt(3465), read.get("DataVersion"));
        assertEquals(NbtValue.ofString("attachment.pdrtest:broken"), read.get("id"));
        assertEquals(NbtValue.ofInt(42), read.get("data"));
    }

    @Test
    void payloadsOverTwoMebibytesStayInOriginalOnly() throws Exception {
        Path incident = new IncidentStore(world).create(PLAYER, TIME);

        Optional<String> name = IncidentStore.writePayload(
                incident,
                "key.huge",
                NbtValue.ofByteArray(new byte[2 * 1024 * 1024]),
                Optional.empty(),
                new ArrayList<>());

        assertEquals(Optional.empty(), name);
        assertFalse(Files.exists(incident.resolve("quarantine")));
    }

    @Test
    void pruneKeepsTheLatestAndEveryIncidentStillQuarantined() throws Exception {
        IncidentStore store = new IncidentStore(world);
        Path handled = store.create(PLAYER, TIME.minusDays(3));
        Path quarantined = store.create(PLAYER, TIME.minusDays(2));
        Path recent = store.create(PLAYER, TIME.minusDays(1));
        Path latest = store.create(PLAYER, TIME);
        Files.writeString(
                handled.resolve("quarantine.json"),
                "{\"entries\":[{\"status\":\"restored\"}]}",
                StandardCharsets.UTF_8);
        Files.writeString(
                quarantined.resolve("quarantine.json"),
                "{\"entries\":[{\"status\":\"quarantined\"}]}",
                StandardCharsets.UTF_8);
        Files.writeString(recent.resolve("quarantine.json"), "{\"entries\":[]}", StandardCharsets.UTF_8);
        Files.writeString(latest.resolve("quarantine.json"), "{\"entries\":[]}", StandardCharsets.UTF_8);

        List<Path> removed = store.prune(PLAYER, 1);

        assertEquals(List.of(recent, handled), removed);
        assertTrue(Files.isDirectory(latest));
        assertTrue(Files.isDirectory(quarantined));
        assertFalse(Files.exists(handled));
    }

    @Test
    void pruneKeepsAnIncidentWhoseRestoreFailed() throws Exception {
        IncidentStore store = new IncidentStore(world);
        Path failed = store.create(PLAYER, TIME.minusDays(1));
        Path latest = store.create(PLAYER, TIME);
        Files.writeString(
                failed.resolve("quarantine.json"),
                "{\"entries\":[{\"status\":\"restore_failed\"},{\"status\":\"set_aside_again\"}]}",
                StandardCharsets.UTF_8);
        Files.writeString(latest.resolve("quarantine.json"), "{\"entries\":[]}", StandardCharsets.UTF_8);

        assertEquals(List.of(), store.prune(PLAYER, 1));
        assertTrue(Files.isDirectory(failed));
    }
}
