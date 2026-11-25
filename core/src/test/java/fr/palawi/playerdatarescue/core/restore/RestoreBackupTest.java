package fr.palawi.playerdatarescue.core.restore;

import static fr.palawi.playerdatarescue.core.restore.RestoreBench.PLAYER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.palawi.playerdatarescue.core.incident.EntryStatus;
import fr.palawi.playerdatarescue.core.incident.IncidentFiles;
import fr.palawi.playerdatarescue.core.nbt.NbtIo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The value a restored attachment replaces (SPEC.md section 7.1), on S2Bot's Forge file
 * (fixtures/README.md: the test capability {@code pdrtest:broken} under {@code ForgeCaps}, the int
 * 42): it is kept in the backup incident, restorable from there only while it is out of the data.
 */
class RestoreBackupTest {
    private static final String ATTACHMENT = "attachment.pdrtest:broken";

    @TempDir
    Path world;

    private RestoreBench bench;
    private String incident;

    @BeforeEach
    void rescueTheAttachment() throws Exception {
        bench = new RestoreBench(world, "1.20.1-forge/S2Bot.dat");
        incident = bench.rescue(ATTACHMENT);
    }

    private String backup() throws Exception {
        List<Path> incidents = IncidentFiles.incidents(bench.root, PLAYER);
        for (Path folder : incidents) {
            if (IncidentFiles.read(folder).trigger().equalsIgnoreCase("restore")) {
                return folder.getFileName().toString();
            }
        }
        throw new AssertionError("no backup incident in " + incidents);
    }

    @Test
    void theReplacedValueIsKeptInTheBackupAndCanBeRestored() throws Exception {
        Restorer.queue(bench.root, PLAYER, incident, "all");
        bench.join();

        IncidentFiles.Summary backup = bench.summary(backup());
        assertEquals("restore_backup", backup.outcome().toLowerCase(Locale.ROOT));
        IncidentFiles.Entry replaced = backup.entries().get(0);
        assertEquals(ATTACHMENT, replaced.id());
        assertEquals(EntryStatus.QUARANTINED, replaced.status());
        assertTrue(replaced.payload().isPresent());
        assertTrue(Files.isRegularFile(bench.root
                .resolve(PLAYER.toString())
                .resolve(backup())
                .resolve(replaced.payload().get())));
        assertEquals(
                List.of(ATTACHMENT),
                Restorer.queue(bench.root, PLAYER, backup(), ATTACHMENT).queued());
    }

    @Test
    void whenTheDataIsPutBackTheReplacedValueIsNotRestorableFromTheBackup() throws Exception {
        byte[] before = Files.readAllBytes(bench.playerFile);
        Restorer.queue(bench.root, PLAYER, incident, "all");
        bench.beforeRead();

        Files.write(bench.playerFile, before);
        bench.end(RestoreJoin.End.notPlaced("the join failed"));

        assertEquals(EntryStatus.RESTORED, bench.entry(backup()).status());
        Restorer.Queued again = Restorer.queue(bench.root, PLAYER, backup(), ATTACHMENT);
        assertTrue(again.queued().isEmpty());
        assertEquals(List.of(ATTACHMENT), again.alreadyRestored());
    }

    @Test
    void whenTheDataWasNeverWrittenTheReplacedValueIsNotRestorableFromTheBackup() throws Exception {
        Restorer.queue(bench.root, PLAYER, incident, "all");
        RestoreJoin.prepare(
                bench.root,
                bench.context(world, List.of()),
                NbtIo.read(bench.playerFile),
                List.of(bench.playerFile),
                bench.sha(),
                "ForgeCaps");

        RestoreJoin.recover(bench.root, PLAYER, bench.sha());

        assertEquals(EntryStatus.RESTORED, bench.entry(backup()).status());
        assertEquals(List.of(new RestoreQueue.Item(incident, ATTACHMENT)), RestoreQueue.read(bench.root, PLAYER));
    }

    @Test
    void twoEntriesOnTheSameKeyInOneJoinGoBackOneAtATime() throws Exception {
        String second = bench.rescue(ATTACHMENT);
        Restorer.queue(bench.root, PLAYER, incident, "all");
        Restorer.queue(bench.root, PLAYER, second, "all");

        bench.join();

        assertEquals(EntryStatus.RESTORED, bench.status(incident));
        assertEquals(EntryStatus.RESTORE_FAILED, bench.status(second));
        assertTrue(bench.entry(second).statusReason().orElseThrow().contains("another entry of the same join"));
        assertEquals(1, bench.summary(backup()).entries().size());
        assertEquals(
                List.of(ATTACHMENT),
                Restorer.queue(bench.root, PLAYER, second, "all").queued());
    }
}
