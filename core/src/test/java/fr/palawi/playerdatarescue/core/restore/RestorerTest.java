package fr.palawi.playerdatarescue.core.restore;

import static fr.palawi.playerdatarescue.core.restore.RestoreBench.PLAYER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.palawi.playerdatarescue.core.incident.EntryStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The commands of SPEC.md section 7 on S1Bot's set-aside slot 17 ({@link RestoreBench}). */
class RestorerTest {
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
    void aRestoreWaitsForTheNextJoin() throws Exception {
        Restorer.Queued queued = Restorer.queue(bench.root, PLAYER, incident, "all");

        assertEquals(List.of("inv.17"), queued.queued());
        assertEquals(EntryStatus.QUARANTINED, bench.status(incident));
        assertEquals(0, bench.restoredStacks().size());
        assertTrue(Files.isRegularFile(RestoreQueue.file(bench.root, PLAYER)));
    }

    @Test
    void restoringTwiceChangesNothing() throws Exception {
        Restorer.queue(bench.root, PLAYER, incident, "all");
        bench.join();

        Restorer.Queued again = Restorer.queue(bench.root, PLAYER, incident, "all");
        bench.join();

        assertTrue(again.queued().isEmpty());
        assertEquals(List.of("inv.17"), again.alreadyRestored());
        assertEquals(1, bench.restoredStacks().size());
    }

    @Test
    void queuingTwiceBeforeTheJoinRestoresOnce() throws Exception {
        Restorer.queue(bench.root, PLAYER, incident, "inv.17");
        Restorer.queue(bench.root, PLAYER, incident, "all");

        assertEquals(1, RestoreQueue.read(bench.root, PLAYER).size());
        bench.join();
        assertEquals(1, bench.restoredStacks().size());
    }

    @Test
    void anEntryOnItsWayIntoTheDataIsNeitherQueuedNorDismissed() throws Exception {
        Restorer.queue(bench.root, PLAYER, incident, "all");
        bench.beforeRead();

        Restorer.Queued again = Restorer.queue(bench.root, PLAYER, incident, "inv.17");
        List<String> dismissed = Restorer.dismiss(bench.root, PLAYER, incident, "inv.17");

        assertTrue(again.queued().isEmpty());
        assertTrue(again.notRestorable().get("inv.17").contains("current join"));
        assertTrue(dismissed.isEmpty());
        assertEquals(EntryStatus.QUARANTINED, bench.status(incident));
    }

    @Test
    void dismissTakesTheEntryOutOfTheQueueAndKeepsItsData() throws Exception {
        Restorer.queue(bench.root, PLAYER, incident, "all");

        List<String> dismissed = Restorer.dismiss(bench.root, PLAYER, incident, "all");

        assertEquals(List.of("inv.17"), dismissed);
        assertEquals(EntryStatus.DISMISSED, bench.status(incident));
        assertFalse(Files.exists(RestoreQueue.file(bench.root, PLAYER)));
        assertTrue(bench.entry(incident).payload().isPresent());
        // "all" leaves a dismissed entry alone; naming it still restores it (SPEC.md section 7).
        assertTrue(Restorer.queue(bench.root, PLAYER, incident, "all").queued().isEmpty());
        assertEquals(
                List.of("inv.17"),
                Restorer.queue(bench.root, PLAYER, incident, "inv.17").queued());
    }

    @Test
    void aRestoredEntryCannotBeDismissed() throws Exception {
        Restorer.queue(bench.root, PLAYER, incident, "all");
        bench.join();

        assertTrue(Restorer.dismiss(bench.root, PLAYER, incident, "inv.17").isEmpty());
        assertEquals(EntryStatus.RESTORED, bench.status(incident));
    }

    @Test
    void anUnknownIncidentOrEntryIsRefused() {
        assertThrows(
                IllegalArgumentException.class, () -> Restorer.queue(bench.root, PLAYER, "20260101-000000", "all"));
        assertThrows(IllegalArgumentException.class, () -> Restorer.queue(bench.root, PLAYER, incident, "inv.3"));
        assertThrows(IllegalArgumentException.class, () -> Restorer.queue(bench.root, PLAYER, "../x", "all"));
        assertThrows(IllegalArgumentException.class, () -> Restorer.dismiss(bench.root, PLAYER, "../x", "all"));
    }
}
