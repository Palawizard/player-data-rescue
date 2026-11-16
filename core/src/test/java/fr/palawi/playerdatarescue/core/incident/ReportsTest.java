package fr.palawi.playerdatarescue.core.incident;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.palawi.playerdatarescue.core.attribution.Confidence;
import fr.palawi.playerdatarescue.core.attribution.Suspect;
import fr.palawi.playerdatarescue.core.data.Section;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReportsTest {
    private static final ErrorInfo ERROR = new ErrorInfo(
            "java.lang.IllegalStateException", "pdrtest: deliberate failure", List.of("a.B.c(B.java:1)"), List.of());

    private static Incident incident(Outcome outcome, List<QuarantineEntry> entries, List<Suspect> suspects) {
        return new Incident(
                UUID.fromString("422e1f9b-773c-337f-ba88-76d471030d8a"),
                "S2Bot",
                "20261116-140509",
                Instant.parse("2026-11-16T13:05:09Z"),
                new Incident.Versions("1.21.1", "fabric", "0.20.1", "0.1.0"),
                outcome == Outcome.NOT_DATA_RELATED ? Trigger.T3 : Trigger.T1,
                outcome,
                List.of("422e1f9b-773c-337f-ba88-76d471030d8a.dat"),
                entries,
                List.of(),
                suspects,
                ERROR);
    }

    @Test
    void aRescueReportSaysWhatWasSetAsideTheSuspectAndTheNextStep() {
        Suspect suspect = new Suspect("pdrtest", Suspect.DATA_FAILED, Confidence.HIGH);
        QuarantineEntry entry = new QuarantineEntry(
                "key.pdrtest:broken",
                Section.UNKNOWN_KEY,
                Optional.empty(),
                "Mod data under the key pdrtest:broken",
                "42",
                ERROR,
                List.of(suspect),
                EntryStatus.QUARANTINED,
                Optional.of("key.pdrtest_broken.nbt"),
                false);

        String text = Reports.text(incident(Outcome.RESCUED, List.of(entry), List.of(suspect)));

        assertTrue(
                text.startsWith(
                        "S2Bot's saved data failed to load. 1 part was set aside, and S2Bot joined with everything else."),
                text);
        assertTrue(text.contains("Player: S2Bot (422e1f9b-773c-337f-ba88-76d471030d8a)"), text);
        assertTrue(text.contains("Minecraft 1.21.1, fabric 0.20.1, Player Data Rescue 0.1.0"), text);
        assertTrue(text.contains("- Mod data under the key pdrtest:broken (pdrtest)"), text);
        assertTrue(text.contains("Most likely:\n- pdrtest: the mod's data failed to load (high confidence)"), text);
        assertTrue(
                text.contains("Next step: update or report pdrtest, then run /pdrescue restore S2Bot 20261116-140509."),
                text);
        assertTrue(text.contains("Appendix: errors"), text);
    }

    @Test
    void aDiagnosisSaysTheDataIsNotTheCause() {
        Suspect suspect = new Suspect("pdrtest", Suspect.IN_STACK_TRACE, Confidence.MEDIUM);

        String text = Reports.text(incident(Outcome.NOT_DATA_RELATED, List.of(), List.of(suspect)));
        JsonObject json = JsonParser.parseString(
                        Reports.json(incident(Outcome.NOT_DATA_RELATED, List.of(), List.of(suspect))))
                .getAsJsonObject();

        assertTrue(text.contains("the saved data is not the cause"), text);
        assertTrue(text.contains("Nothing was changed"), text);
        assertEquals("not_data_related", json.get("outcome").getAsString());
        assertEquals(false, json.get("dataIsTheCause").getAsBoolean());
        assertEquals(1, json.get("schemaVersion").getAsInt());
        assertEquals("2026-11-16T13:05:09Z", json.get("time").getAsString());
    }
}
