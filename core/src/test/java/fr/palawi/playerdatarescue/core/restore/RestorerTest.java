package fr.palawi.playerdatarescue.core.restore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.palawi.playerdatarescue.core.Fixtures;
import fr.palawi.playerdatarescue.core.attribution.Confidence;
import fr.palawi.playerdatarescue.core.attribution.Suspect;
import fr.palawi.playerdatarescue.core.data.PlayerData;
import fr.palawi.playerdatarescue.core.data.Unit;
import fr.palawi.playerdatarescue.core.incident.EntryStatus;
import fr.palawi.playerdatarescue.core.incident.Incident;
import fr.palawi.playerdatarescue.core.incident.IncidentFiles;
import fr.palawi.playerdatarescue.core.incident.IncidentRecorder;
import fr.palawi.playerdatarescue.core.incident.IncidentStore;
import fr.palawi.playerdatarescue.core.incident.Outcome;
import fr.palawi.playerdatarescue.core.incident.Trigger;
import fr.palawi.playerdatarescue.core.nbt.NbtCompound;
import fr.palawi.playerdatarescue.core.nbt.NbtIo;
import fr.palawi.playerdatarescue.core.rescue.RescueResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A rescue of S1Bot's slot 17 (12 bricks renamed to pdrtest_removed:brass_ingot, fixtures/README.md),
 * restored at the next join, then restored again: the second restore must change nothing.
 */
class RestorerTest {
    private static final UUID PLAYER = UUID.fromString("b6fb3f6f-1d7c-3f14-9b45-4a1f2b0b6a11");

    @TempDir
    Path world;

    private Path root;
    private Path playerFile;
    private String incident;

    @BeforeEach
    void rescueSlot17() throws Exception {
        root = world.resolve(IncidentStore.FOLDER);
        NbtCompound s1 = Fixtures.read("1.20.1-fabric/S1Bot.dat");
        playerFile = world.resolve("playerdata").resolve(PLAYER + ".dat");
        Files.createDirectories(playerFile.getParent());
        Files.copy(Fixtures.path("1.20.1-fabric/S1Bot.dat"), playerFile);

        Unit slot = PlayerData.of(s1).units().stream()
                .filter(u -> u.id().equals("inv.17"))
                .findFirst()
                .orElseThrow();
        Path folder = new IncidentStore(world).create(PLAYER, LocalDateTime.of(2026, 11, 24, 12, 0, 0));
        List<String> originals = IncidentStore.backup(folder, List.of(playerFile));
        Exception failure = new IllegalStateException("pdrtest: test failure");
        RescueResult result = new RescueResult(
                Trigger.T1,
                Outcome.RESCUED,
                List.of(new RescueResult.SetAside(
                        slot,
                        failure,
                        List.of(new Suspect("pdrtest", "the mod's data failed to load", Confidence.HIGH)))),
                List.of(),
                List.of(),
                failure,
                2);
        IncidentRecorder.record(
                context(folder, originals), result, s1.number("DataVersion").map(Number::intValue));
        incident = folder.getFileName().toString();

        // The rescue saved the player without slot 17.
        NbtCompound rescued = s1.copy();
        rescued.list("Inventory").orElseThrow().remove(0);
        Files.write(playerFile, NbtIo.write(rescued, true));
    }

    private IncidentRecorder.Context context(Path folder, List<String> originals) {
        return new IncidentRecorder.Context(
                PLAYER,
                "S1Bot",
                folder,
                Instant.parse("2026-11-24T11:00:00Z"),
                new Incident.Versions("1.20.1", "fabric", "0.20.1", "0.1.0"),
                originals);
    }

    private Optional<Restorer.Prepared> join() throws Exception {
        NbtCompound current = NbtIo.read(playerFile);
        Optional<Restorer.Prepared> prepared =
                Restorer.prepare(root, context(world, List.of()), current, List.of(playerFile), "ForgeCaps");
        if (prepared.isPresent()) {
            Files.write(playerFile, NbtIo.write(prepared.get().data(), true));
            Restorer.finish(root, PLAYER, prepared.get(), Map.of());
        }
        return prepared;
    }

    private long bricks() throws Exception {
        return NbtIo.read(playerFile).list("Inventory").orElseThrow().elements().stream()
                .map(NbtCompound.class::cast)
                .filter(stack -> stack.string("id").equals(Optional.of("pdrtest_removed:brass_ingot")))
                .count();
    }

    private EntryStatus status() throws Exception {
        return IncidentFiles.read(root.resolve(PLAYER.toString()).resolve(incident))
                .entries()
                .get(0)
                .status();
    }

    @Test
    void aRestoreWaitsForTheNextJoin() throws Exception {
        Restorer.Queued queued = Restorer.queue(root, PLAYER, incident, "all");

        assertEquals(List.of("inv.17"), queued.queued());
        assertEquals(EntryStatus.QUARANTINED, status());
        assertEquals(0, bricks());
        assertTrue(Files.isRegularFile(RestoreQueue.file(root, PLAYER)));
    }

    @Test
    void theJoinPutsTheStackBackOnceAndMarksItRestored() throws Exception {
        Restorer.queue(root, PLAYER, incident, "all");

        Restorer.Prepared prepared = join().orElseThrow();

        assertEquals(1, bricks());
        assertEquals(EntryStatus.RESTORED, status());
        assertTrue(!Files.exists(RestoreQueue.file(root, PLAYER)));
        // The file as it was before the restore is kept in the backup incident.
        assertTrue(Files.isRegularFile(prepared.backup().resolve("original").resolve(PLAYER + ".dat")));
    }

    @Test
    void restoringTwiceChangesNothing() throws Exception {
        Restorer.queue(root, PLAYER, incident, "all");
        join();

        Restorer.Queued again = Restorer.queue(root, PLAYER, incident, "all");
        Optional<Restorer.Prepared> secondJoin = join();

        assertTrue(again.queued().isEmpty());
        assertEquals(List.of("inv.17"), again.alreadyRestored());
        assertTrue(secondJoin.isEmpty());
        assertEquals(1, bricks());
    }

    @Test
    void queuingTwiceBeforeTheJoinRestoresOnce() throws Exception {
        Restorer.queue(root, PLAYER, incident, "inv.17");
        Restorer.queue(root, PLAYER, incident, "all");

        assertEquals(1, RestoreQueue.read(root, PLAYER).size());
        join();
        assertEquals(1, bricks());
    }

    @Test
    void anEntryThatFailsAgainIsMarkedAndCanBeQueuedAgain() throws Exception {
        Restorer.queue(root, PLAYER, incident, "all");
        NbtCompound current = NbtIo.read(playerFile);
        Restorer.Prepared prepared = Restorer.prepare(
                        root, context(world, List.of()), current, List.of(playerFile), "ForgeCaps")
                .orElseThrow();

        Restorer.finish(root, PLAYER, prepared, Map.of("inv.17", "it failed to load again"));

        assertEquals(EntryStatus.RESTORE_FAILED, status());
        assertEquals(
                List.of("inv.17"), Restorer.queue(root, PLAYER, incident, "all").queued());
    }

    @Test
    void anUnknownIncidentOrEntryIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Restorer.queue(root, PLAYER, "20260101-000000", "all"));
        assertThrows(IllegalArgumentException.class, () -> Restorer.queue(root, PLAYER, incident, "inv.3"));
        assertThrows(IllegalArgumentException.class, () -> Restorer.queue(root, PLAYER, "../x", "all"));
    }
}
