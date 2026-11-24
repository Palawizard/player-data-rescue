package fr.palawi.playerdatarescue.core.recurring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.palawi.playerdatarescue.core.Fixtures;
import fr.palawi.playerdatarescue.core.nbt.NbtCompound;
import fr.palawi.playerdatarescue.core.nbt.NbtIo;
import fr.palawi.playerdatarescue.core.nbt.NbtTag;
import fr.palawi.playerdatarescue.core.nbt.NbtValue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC.md section 4.4 and scenario S13, with the test capability of a real Forge file
 * (fixtures/README.md: {@code pdrtest:broken} under {@code ForgeCaps}, the int 42) as the payload.
 */
class RecurringIssuesTest {
    private static final String KEY = "attachment.pdrtest:broken";
    private static final UUID PLAYER = UUID.fromString("0f0e5a5e-3c2a-3a9b-8c51-6a3b8a0f5a01");
    private static final int THRESHOLD = 3;

    @TempDir
    Path root;

    private RecurringIssues issues;
    private NbtTag payload;

    @BeforeEach
    void setUp() throws Exception {
        issues = new RecurringIssues(root);
        payload = Fixtures.read("1.20.1-forge/S2Bot.dat")
                .compound("ForgeCaps")
                .orElseThrow()
                .get("pdrtest:broken");
    }

    private RecurringIssues.Outcome fail(UUID player, int minute) throws Exception {
        return issues.record(
                player,
                "S13Bot",
                List.of(new RecurringIssues.Failure(KEY, payload, Optional.of("pdrtest"))),
                THRESHOLD,
                Instant.parse("2026-11-24T12:00:00Z").plusSeconds(60L * minute));
    }

    @Test
    void theThirdFailureInARowMakesOneRecord() throws Exception {
        assertTrue(fail(PLAYER, 0).recurring().isEmpty());
        assertTrue(fail(PLAYER, 1).recurring().isEmpty());

        RecurringIssues.Outcome third = fail(PLAYER, 2);

        assertEquals(Set.of(KEY), third.recurring());
        assertEquals(1, third.created().size());
        RecurringIssues.Issue issue = issues.find(KEY).orElseThrow();
        assertEquals(3, issue.count());
        assertEquals(Optional.of("pdrtest"), issue.suspect());
        assertEquals("S13Bot", issue.players().get(PLAYER));
        assertEquals("2026-11-24T12:02:00Z", issue.first());
        NbtCompound first = NbtIo.read(issues.folder().resolve("attachment.pdrtest_broken.first.nbt"));
        assertEquals(NbtValue.ofInt(42), first.get("data"));
    }

    @Test
    void laterFailuresUpdateTheRecord() throws Exception {
        fail(PLAYER, 0);
        fail(PLAYER, 1);
        fail(PLAYER, 2);

        RecurringIssues.Outcome fourth = fail(PLAYER, 3);

        assertEquals(Set.of(KEY), fourth.recurring());
        assertTrue(fourth.created().isEmpty());
        RecurringIssues.Issue issue = issues.find(KEY).orElseThrow();
        assertEquals(4, issue.count());
        assertEquals("2026-11-24T12:02:00Z", issue.first());
        assertEquals("2026-11-24T12:03:00Z", issue.last());
        assertEquals(1, issues.all().size());
    }

    @Test
    void aJoinWithoutTheFailureEndsTheStreak() throws Exception {
        fail(PLAYER, 0);
        fail(PLAYER, 1);
        issues.joined(PLAYER);

        assertTrue(fail(PLAYER, 2).recurring().isEmpty());
        assertTrue(fail(PLAYER, 3).recurring().isEmpty());
        assertFalse(fail(PLAYER, 4).recurring().isEmpty());
    }

    @Test
    void aFailureOfAnotherKeyEndsTheStreak() throws Exception {
        fail(PLAYER, 0);
        fail(PLAYER, 1);
        issues.record(
                PLAYER,
                "S13Bot",
                List.of(new RecurringIssues.Failure("key.other:data", payload, Optional.empty())),
                THRESHOLD,
                Instant.parse("2026-11-24T12:05:00Z"));

        assertTrue(fail(PLAYER, 6).recurring().isEmpty());
    }

    @Test
    void threeDifferentPlayersMakeOneRecord() throws Exception {
        assertTrue(fail(UUID.fromString("00000000-0000-3000-8000-000000000001"), 0)
                .recurring()
                .isEmpty());
        assertTrue(fail(UUID.fromString("00000000-0000-3000-8000-000000000002"), 1)
                .recurring()
                .isEmpty());

        RecurringIssues.Outcome third = fail(UUID.fromString("00000000-0000-3000-8000-000000000003"), 2);

        assertEquals(Set.of(KEY), third.recurring());
    }

    @Test
    void theRecordClosesWhenTheKeyLoadsAgainAndNothingIsDeleted() throws Exception {
        fail(PLAYER, 0);
        fail(PLAYER, 1);
        fail(PLAYER, 2);
        assertTrue(issues.concerns(PLAYER));

        List<RecurringIssues.Issue> closed = issues.loaded(
                RecurringIssues.keys(Fixtures.read("1.20.1-forge/S12Bot.dat")), Instant.parse("2026-11-24T13:00:00Z"));

        assertEquals(1, closed.size());
        assertTrue(issues.all().isEmpty());
        try (var files = Files.list(issues.folder().resolve("closed"))) {
            assertEquals(3, files.count());
        }
    }

    @Test
    void theKeysOfADataFileAreNamedAsTheRescueNamesItsUnits() throws Exception {
        assertTrue(
                RecurringIssues.keys(Fixtures.read("1.20.1-forge/S12Bot.dat")).contains("attachment.pdrtest:broken"));
        assertTrue(
                RecurringIssues.keys(Fixtures.read("1.20.1-fabric/S2Bot.dat")).contains("key.pdrtest:broken"));
    }
}
