package fr.palawi.playerdatarescue.core.restore;

import static fr.palawi.playerdatarescue.core.restore.RestoreBench.PLAYER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.palawi.playerdatarescue.core.incident.EntryStatus;
import fr.palawi.playerdatarescue.core.incident.Outcome;
import fr.palawi.playerdatarescue.core.nbt.NbtCompound;
import fr.palawi.playerdatarescue.core.nbt.NbtIo;
import fr.palawi.playerdatarescue.core.nbt.NbtTag;
import fr.palawi.playerdatarescue.core.nbt.NbtValue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The join side of a restore (SPEC.md section 7.1) on S1Bot's set-aside slot 17 ({@link
 * RestoreBench}). Every way a join can end must leave the 12 bricks restorable in one place
 * only, so that no sequence of commands and joins brings them back twice.
 */
class RestoreJoinTest {
    @TempDir
    Path world;

    private RestoreBench bench;
    private String incident;

    @BeforeEach
    void rescueSlot17() throws Exception {
        bench = new RestoreBench(world);
        incident = bench.rescueSlot17();
    }

    @Test
    void theJoinPutsTheStackBackOnceAndMarksItRestored() throws Exception {
        Restorer.queue(bench.root, PLAYER, incident, "all");

        RestoreJoin.Prepared prepared = bench.beforeRead().orElseThrow();
        bench.end(RestoreJoin.End.joined());

        assertEquals(1, bench.restoredStacks().size());
        assertEquals(
                17, bench.restoredStacks().get(0).number("Slot").orElseThrow().intValue());
        assertEquals(EntryStatus.RESTORED, bench.status(incident));
        assertFalse(Files.exists(RestoreQueue.file(bench.root, PLAYER)));
        assertFalse(Files.exists(RestorePending.file(bench.root, PLAYER)));
        // The file as it was before the restore is kept in the backup incident.
        assertTrue(Files.isRegularFile(prepared.backup().resolve("original").resolve(PLAYER + ".dat")));
    }

    @Test
    void aJoinThatFailsPutsTheDataBackAndTheEntryCanBeQueuedAgain() throws Exception {
        byte[] before = Files.readAllBytes(bench.playerFile);
        Restorer.queue(bench.root, PLAYER, incident, "all");
        bench.beforeRead();

        // The game side puts the data back as it was, then reports the failed join.
        Files.write(bench.playerFile, before);
        bench.end(RestoreJoin.End.notPlaced("the join failed"));

        assertEquals(EntryStatus.RESTORE_FAILED, bench.status(incident));
        assertEquals(0, bench.restoredStacks().size());
        assertEquals(
                List.of("inv.17"),
                Restorer.queue(bench.root, PLAYER, incident, "all").queued());
        bench.join();
        assertEquals(1, bench.restoredStacks().size());
        assertEquals(EntryStatus.RESTORED, bench.status(incident));
    }

    @Test
    void aStackTheRescueSetsAsideAgainIsRestoredFromTheNewIncidentOnly() throws Exception {
        Restorer.queue(bench.root, PLAYER, incident, "all");
        bench.beforeRead();
        NbtCompound placed = bench.restoredStacks().get(0);

        bench.end(RestoreJoin.End.rescued("20261125-090000", Outcome.RESCUED, List.of(placed)));

        assertEquals(EntryStatus.SET_ASIDE_AGAIN, bench.status(incident));
        assertTrue(bench.entry(incident).statusReason().orElseThrow().contains("20261125-090000"));
        Restorer.Queued again = Restorer.queue(bench.root, PLAYER, incident, "all");
        assertTrue(again.queued().isEmpty());
        assertTrue(again.notRestorable().get("inv.17").contains("restore it from there"));
    }

    @Test
    void aMinimalProfileSetsEveryRestoredEntryAsideAgain() throws Exception {
        Restorer.queue(bench.root, PLAYER, incident, "all");
        bench.beforeRead();
        NbtTag inventory = NbtIo.read(bench.playerFile).get("Inventory");

        bench.end(RestoreJoin.End.rescued("20261125-090000", Outcome.MINIMAL_PROFILE, List.of(inventory)));

        assertEquals(EntryStatus.SET_ASIDE_AGAIN, bench.status(incident));
    }

    @Test
    void aRescueOfAnotherStackLeavesTheRestoredOneRestored() throws Exception {
        NbtCompound data = NbtIo.read(bench.playerFile);
        NbtCompound stone = new NbtCompound()
                .put("Slot", NbtValue.ofByte((byte) 3))
                .put("id", NbtValue.ofString("minecraft:stone"))
                .put("Count", NbtValue.ofByte((byte) 1));
        data.list("Inventory").orElseThrow().add(stone);
        bench.write(data);
        Restorer.queue(bench.root, PLAYER, incident, "all");
        bench.beforeRead();

        bench.end(RestoreJoin.End.rescued("20261125-090000", Outcome.RESCUED, List.of(stone)));

        assertEquals(EntryStatus.RESTORED, bench.status(incident));
    }

    @Test
    void twoIncidentsWithTheSameEntryAreDecidedApart() throws Exception {
        String second = bench.rescueSlot17();
        Restorer.queue(bench.root, PLAYER, incident, "all");
        Restorer.queue(bench.root, PLAYER, second, "all");
        bench.beforeRead();
        List<NbtCompound> stacks = bench.restoredStacks();
        assertEquals(2, stacks.size());
        // The first incident's stack takes slot 17; the second one goes to the first free slot.
        NbtCompound secondStack = stacks.stream()
                .filter(s -> s.number("Slot").orElseThrow().intValue() != 17)
                .findFirst()
                .orElseThrow();

        bench.end(RestoreJoin.End.rescued("20261125-090000", Outcome.RESCUED, List.of(secondStack)));

        assertEquals(EntryStatus.RESTORED, bench.status(incident));
        assertEquals(EntryStatus.SET_ASIDE_AGAIN, bench.status(second));
    }

    @Test
    void aJoinThatNeverWroteTheDataQueuesTheEntriesAgain() throws Exception {
        Restorer.queue(bench.root, PLAYER, incident, "all");
        // The record is written, then the server stops before the merged data is.
        RestoreJoin.prepare(
                bench.root,
                bench.context(world, List.of()),
                NbtIo.read(bench.playerFile),
                List.of(bench.playerFile),
                bench.sha(),
                "ForgeCaps");

        bench.join();

        assertEquals(1, bench.restoredStacks().size());
        assertEquals(EntryStatus.RESTORED, bench.status(incident));
    }

    @Test
    void aJoinThatWroteTheDataButNeverEndedCountsAsRestored() throws Exception {
        Restorer.queue(bench.root, PLAYER, incident, "all");
        // The merged data is written, then the client leaves before the placement.
        bench.beforeRead();

        bench.join();

        assertEquals(1, bench.restoredStacks().size());
        assertEquals(EntryStatus.RESTORED, bench.status(incident));
        assertTrue(Restorer.queue(bench.root, PLAYER, incident, "all").queued().isEmpty());
        assertFalse(Files.exists(RestorePending.file(bench.root, PLAYER)));
    }

    @Test
    void aStackWithNoFreeSlotIsDroppedOnce() throws Exception {
        bench.fillInventory();
        Restorer.queue(bench.root, PLAYER, incident, "all");

        List<NbtCompound> drops = bench.join();
        List<NbtCompound> later = bench.join();

        assertEquals(1, drops.size());
        assertEquals(Optional.of(RestoreBench.ITEM), drops.get(0).string("id"));
        assertTrue(later.isEmpty());
        assertEquals(EntryStatus.RESTORED, bench.status(incident));
    }

    @Test
    void aStackWithNoFreeSlotSurvivesAJoinThatNeverEnded() throws Exception {
        bench.fillInventory();
        Restorer.queue(bench.root, PLAYER, incident, "all");
        bench.beforeRead();

        List<NbtCompound> drops = bench.join();

        assertEquals(1, drops.size());
        assertEquals(EntryStatus.RESTORED, bench.status(incident));
    }

    @Test
    void anEntryThatCannotGoBackMakesNoBackup() throws Exception {
        Restorer.queue(bench.root, PLAYER, incident, "all");
        Path payload = bench.root
                .resolve(PLAYER.toString())
                .resolve(incident)
                .resolve(bench.entry(incident).payload().orElseThrow());
        Files.delete(payload);

        assertTrue(bench.beforeRead().isEmpty());

        assertEquals(EntryStatus.RESTORE_FAILED, bench.status(incident));
        assertEquals(1, bench.incidents());
        assertFalse(Files.exists(RestoreQueue.file(bench.root, PLAYER)));
        assertFalse(Files.exists(RestorePending.file(bench.root, PLAYER)));
    }
}
