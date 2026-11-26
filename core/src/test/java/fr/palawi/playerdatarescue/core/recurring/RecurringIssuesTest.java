package fr.palawi.playerdatarescue.core.recurring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.palawi.playerdatarescue.core.Fixtures;
import fr.palawi.playerdatarescue.core.data.PlayerData;
import fr.palawi.playerdatarescue.core.data.Unit;
import fr.palawi.playerdatarescue.core.incident.Outcome;
import fr.palawi.playerdatarescue.core.incident.Trigger;
import fr.palawi.playerdatarescue.core.nbt.NbtCompound;
import fr.palawi.playerdatarescue.core.nbt.NbtIo;
import fr.palawi.playerdatarescue.core.nbt.NbtTag;
import fr.palawi.playerdatarescue.core.nbt.NbtValue;
import fr.palawi.playerdatarescue.core.rescue.RescueResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
    private static final UUID SECOND = UUID.fromString("00000000-0000-3000-8000-000000000002");
    private static final UUID THIRD = UUID.fromString("00000000-0000-3000-8000-000000000003");
    private static final int THRESHOLD = 3;
    private static final Instant NOON = Instant.parse("2026-11-24T12:00:00Z");

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

    private RecurringIssues.Recorded fail(UUID player, int minute) throws Exception {
        return fail(player, "S13Bot", KEY, minute);
    }

    private RecurringIssues.Recorded fail(UUID player, String name, String key, int minute) throws Exception {
        return issues.record(
                player,
                name,
                List.of(new RecurringIssues.Failure(key, payload, Optional.of("pdrtest"))),
                THRESHOLD,
                NOON.plusSeconds(60L * minute));
    }

    @Test
    void theThirdFailureInARowMakesOneRecord() throws Exception {
        assertTrue(fail(PLAYER, 0).touched().isEmpty());
        assertTrue(fail(PLAYER, 1).touched().isEmpty());

        RecurringIssues.Recorded third = fail(PLAYER, 2);

        assertEquals(1, third.created().size());
        RecurringIssues.Issue issue = issues.find(KEY).orElseThrow();
        assertEquals(3, issue.count());
        assertEquals(Optional.of("pdrtest"), issue.suspect());
        assertEquals("S13Bot", issue.players().get(PLAYER));
        assertEquals("2026-11-24T12:02:00Z", issue.first());
        NbtCompound first = NbtIo.read(issues.folder().resolve(RecurringIssues.fileName(KEY) + ".first.nbt"));
        assertEquals(NbtValue.ofString(KEY), first.get("id"));
        assertEquals(NbtValue.ofInt(42), first.get("data"));
    }

    @Test
    void laterFailuresCountInTheRecord() throws Exception {
        fail(PLAYER, 0);
        fail(PLAYER, 1);
        fail(PLAYER, 2);

        RecurringIssues.Recorded fourth = fail(PLAYER, 3);

        assertTrue(fourth.created().isEmpty());
        assertEquals(1, fourth.updated().size());
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
        issues.joined(PLAYER, Set.of(), NOON);

        assertTrue(fail(PLAYER, 2).touched().isEmpty());
        assertTrue(fail(PLAYER, 3).touched().isEmpty());
        assertFalse(fail(PLAYER, 4).created().isEmpty());
    }

    @Test
    void aRescueWithoutThatKeyEndsTheStreak() throws Exception {
        fail(PLAYER, 0);
        fail(PLAYER, 1);
        fail(PLAYER, "S13Bot", "key.other:data", 5);

        assertTrue(fail(PLAYER, 6).touched().isEmpty());
    }

    @Test
    void threeDifferentPlayersMakeOneRecordThatNamesThemAll() throws Exception {
        assertTrue(fail(PLAYER, "S13Bot", KEY, 0).touched().isEmpty());
        assertTrue(fail(SECOND, "Second", KEY, 1).touched().isEmpty());

        RecurringIssues.Recorded third = fail(THIRD, "Third", KEY, 2);

        assertEquals(1, third.created().size());
        RecurringIssues.Issue issue = issues.find(KEY).orElseThrow();
        assertEquals(
                Set.of("S13Bot", "Second", "Third"), Set.copyOf(issue.players().values()));
        assertEquals(3, issue.count());
        assertFalse(issues.concerns(UUID.fromString("00000000-0000-3000-8000-000000000009")));
        assertTrue(Files.notExists(root.resolve(PLAYER.toString()).resolve(RecurringIssues.STREAKS)));
    }

    @Test
    void aRecordMadeByOnePlayersStreakTakesInTheOtherStreaksOnThatKey() throws Exception {
        fail(SECOND, "Second", KEY, 0);
        fail(PLAYER, 1);
        fail(PLAYER, 2);

        RecurringIssues.Recorded third = fail(PLAYER, 3);

        assertEquals(1, third.created().size());
        RecurringIssues.Issue issue = issues.find(KEY).orElseThrow();
        assertEquals(4, issue.count());
        assertEquals(Set.of("S13Bot", "Second"), Set.copyOf(issue.players().values()));
        assertTrue(Files.notExists(root.resolve(SECOND.toString()).resolve(RecurringIssues.STREAKS)));
    }

    @Test
    void theRecordClosesWhenTheKeyLoadsAgainAndNothingIsDeleted() throws Exception {
        fail(PLAYER, 0);
        fail(PLAYER, 1);
        fail(PLAYER, 2);
        assertTrue(issues.concerns(PLAYER));

        List<RecurringIssues.Issue> closed = issues.joined(
                PLAYER, RecurringIssues.keys(Fixtures.read("1.20.1-forge/S12Bot.dat")), NOON.plusSeconds(3600));

        assertEquals(1, closed.size());
        assertTrue(issues.all().isEmpty());
        assertFalse(issues.concerns(PLAYER));
        try (var files = Files.list(issues.folder().resolve("closed"))) {
            assertEquals(3, files.count());
        }
    }

    @Test
    void aPlayerOutsideTheRecordDoesNotCloseIt() throws Exception {
        fail(PLAYER, 0);
        fail(PLAYER, 1);
        fail(PLAYER, 2);

        List<RecurringIssues.Issue> closed = issues.joined(
                SECOND, RecurringIssues.keys(Fixtures.read("1.20.1-forge/S12Bot.dat")), NOON.plusSeconds(3600));

        assertTrue(closed.isEmpty());
        assertEquals(1, issues.all().size());
    }

    @Test
    void twoClosesInTheSameSecondKeepBothRecords() throws Exception {
        for (int round = 0; round < 2; round++) {
            fail(PLAYER, 0);
            fail(PLAYER, 1);
            fail(PLAYER, 2);
            issues.close(issues.find(KEY).orElseThrow(), NOON);
        }

        try (var files = Files.list(issues.folder().resolve("closed"))) {
            assertEquals(6, files.count());
        }
    }

    @Test
    void keysThatLookAlikeGetTheirOwnRecords() throws Exception {
        List<String> keys = List.of("key.a:b", "key.a_b", "key.A:B");
        for (String key : keys) {
            for (int minute = 0; minute < THRESHOLD; minute++) {
                fail(PLAYER, "S13Bot", key, minute);
            }
        }

        assertEquals(3, issues.all().size());
        for (String key : keys) {
            assertEquals(key, issues.find(key).orElseThrow().key());
        }
        assertNotEquals(
                RecurringIssues.fileName("key.a:b").toLowerCase(Locale.ROOT),
                RecurringIssues.fileName("key.A:B").toLowerCase(Locale.ROOT));
    }

    @Test
    void aRecordFileHoldingAnotherKeyIsNotTakenForIt() throws Exception {
        fail(PLAYER, 0);
        fail(PLAYER, 1);
        fail(PLAYER, 2);
        String other = "attachment.pdrtest:other";
        Files.move(
                issues.folder().resolve(RecurringIssues.fileName(KEY) + ".json"),
                issues.folder().resolve(RecurringIssues.fileName(other) + ".json"));

        assertThrows(IOException.class, () -> issues.find(other));
    }

    @Test
    void anUnreadableRecordIsLeftOutWithoutStoppingTheOthers() throws Exception {
        fail(PLAYER, 0);
        fail(PLAYER, 1);
        fail(PLAYER, 2);
        Path broken = issues.folder().resolve(RecurringIssues.fileName("key.other:data") + ".json");
        Files.writeString(broken, "{\"key\": \"key.other:data\"}");

        assertEquals(
                List.of(KEY),
                issues.all().stream().map(RecurringIssues.Issue::key).toList());
        assertEquals(List.of(broken), issues.unreadable());
        assertEquals(1, fail(PLAYER, 3).updated().size());
        assertTrue(issues.concerns(PLAYER));
    }

    @Test
    void anUnreadableRecordOfAFailingKeyDoesNotStopTheOtherKeys() throws Exception {
        fail(PLAYER, 0);
        fail(PLAYER, 1);
        fail(PLAYER, 2);
        Path record = issues.folder().resolve(RecurringIssues.fileName(KEY) + ".json");
        Files.writeString(record, "not json");
        String other = "key.other:data";

        for (int minute = 3; minute < 6; minute++) {
            issues.record(
                    PLAYER,
                    "S13Bot",
                    List.of(
                            new RecurringIssues.Failure(KEY, payload, Optional.empty()),
                            new RecurringIssues.Failure(other, payload, Optional.empty())),
                    THRESHOLD,
                    NOON.plusSeconds(60L * minute));
        }

        assertEquals(3, issues.find(other).orElseThrow().count());
        assertEquals("not json", Files.readString(record));
        assertEquals(List.of(record), issues.unreadable());
    }

    @Test
    void anUnreadableStreakFileCountsAsNone() throws Exception {
        Files.createDirectories(root.resolve(SECOND.toString()));
        Files.writeString(root.resolve(SECOND.toString()).resolve(RecurringIssues.STREAKS), "not json");
        Files.createDirectories(root.resolve(PLAYER.toString()));
        Files.writeString(root.resolve(PLAYER.toString()).resolve(RecurringIssues.STREAKS), "not json");

        assertTrue(fail(PLAYER, 0).touched().isEmpty());
        assertTrue(fail(PLAYER, 1).touched().isEmpty());
        assertEquals(1, fail(PLAYER, 2).created().size());
        assertEquals(3, issues.find(KEY).orElseThrow().count());
    }

    @Test
    void aThresholdBelowTwoIsRefused() {
        assertThrows(
                IllegalArgumentException.class,
                () -> issues.record(
                        PLAYER,
                        "S13Bot",
                        List.of(new RecurringIssues.Failure(KEY, payload, Optional.empty())),
                        1,
                        NOON));
    }

    @Test
    void onlyTheModDataOfASalvagedRescueCounts() throws Exception {
        NbtCompound data = Fixtures.read("1.20.1-forge/S2Bot.dat");
        List<Unit> units = new ArrayList<>(PlayerData.of(data).units());
        units.addAll(PlayerData.wholeSections(data));
        units.addAll(PlayerData.of(Fixtures.read("1.20.1-fabric/S1Bot.dat")).units());
        assertTrue(units.stream().anyMatch(u -> u.id().startsWith("inv.")));
        assertTrue(units.stream().anyMatch(u -> u.id().startsWith("section.")));

        List<RecurringIssues.Failure> failures =
                RecurringIssues.failures(result(Outcome.RESCUED, units)).orElseThrow();

        assertEquals(
                units.stream()
                        .map(Unit::id)
                        .filter(id -> id.startsWith("attachment.") || id.startsWith("key."))
                        .toList(),
                failures.stream().map(RecurringIssues.Failure::key).toList());
        assertTrue(failures.stream().anyMatch(f -> f.key().equals(KEY)));
        assertTrue(
                RecurringIssues.failures(result(Outcome.MINIMAL_PROFILE, units)).isEmpty());
    }

    @Test
    void keysSetAsideTogetherWithOtherModsByOneFailureDoNotCount() throws Exception {
        Unit broken = PlayerData.of(Fixtures.read("1.20.1-forge/S2Bot.dat")).units().stream()
                .filter(u -> u.id().equals(KEY))
                .findFirst()
                .orElseThrow();
        Unit other = PlayerData.of(Fixtures.read("1.20.1-fabric/S1Bot.dat")).units().stream()
                .filter(u -> u.namespace().isPresent() && !u.namespace().equals(broken.namespace()))
                .findFirst()
                .orElseThrow();
        Throwable bulk = new IllegalStateException("no suspect matched");

        RescueResult together = new RescueResult(
                Trigger.T1,
                Outcome.RESCUED,
                List.of(
                        new RescueResult.SetAside(broken, bulk, List.of()),
                        new RescueResult.SetAside(other, bulk, List.of())),
                List.of(),
                List.of(),
                bulk,
                2);
        RescueResult alone = new RescueResult(
                Trigger.T1,
                Outcome.RESCUED,
                List.of(new RescueResult.SetAside(broken, bulk, List.of())),
                List.of(),
                List.of(),
                bulk,
                2);

        assertTrue(RecurringIssues.failures(together).orElseThrow().isEmpty());
        assertEquals(
                List.of(KEY),
                RecurringIssues.failures(alone).orElseThrow().stream()
                        .map(RecurringIssues.Failure::key)
                        .toList());
    }

    @Test
    void keysWithoutANamespaceSetAsideTogetherDoNotCount() throws Exception {
        NbtCompound data = Fixtures.read("1.20.1-fabric/S2Bot.dat");
        data.put("FooData", NbtValue.ofInt(1));
        data.put("cardinal_components", NbtValue.ofInt(2));
        List<Unit> plain = PlayerData.of(data).units().stream()
                .filter(u -> u.id().equals("key.FooData") || u.id().equals("key.cardinal_components"))
                .toList();
        assertEquals(2, plain.size());
        Throwable bulk = new IllegalStateException("no suspect matched");

        RescueResult together = new RescueResult(
                Trigger.T1,
                Outcome.RESCUED,
                plain.stream()
                        .map(u -> new RescueResult.SetAside(u, bulk, List.of()))
                        .toList(),
                List.of(),
                List.of(),
                bulk,
                2);

        assertTrue(RecurringIssues.failures(together).orElseThrow().isEmpty());
    }

    @Test
    void theKeysOfADataFileAreNamedAsTheRescueNamesItsUnits() throws Exception {
        assertTrue(
                RecurringIssues.keys(Fixtures.read("1.20.1-forge/S12Bot.dat")).contains("attachment.pdrtest:broken"));
        assertTrue(
                RecurringIssues.keys(Fixtures.read("1.20.1-fabric/S2Bot.dat")).contains("key.pdrtest:broken"));
    }

    private static RescueResult result(Outcome outcome, List<Unit> units) {
        List<RescueResult.SetAside> aside = units.stream()
                .map(u -> new RescueResult.SetAside(u, new IllegalStateException("test"), List.of()))
                .toList();
        return new RescueResult(Trigger.T1, outcome, aside, List.of(), List.of(), new IllegalStateException(), 2);
    }
}
