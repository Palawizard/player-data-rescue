package fr.palawi.playerdatarescue.core.incident;

import fr.palawi.playerdatarescue.core.attribution.Suspect;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One rescue or diagnosis for one player, as written to {@code report.txt}, {@code report.json}
 * and {@code quarantine.json}.
 *
 * @param folder the incident folder name, {@code yyyyMMdd-HHmmss} with a counter when needed
 * @param originals the files copied unchanged into {@code original/}
 * @param coreChanges core values that were set to safe defaults (SPEC.md section 4.1)
 * @param failure the failure that started the incident, or the last one for a diagnosis
 */
public record Incident(
        UUID playerId,
        String playerName,
        String folder,
        Instant time,
        Versions versions,
        Trigger trigger,
        Outcome outcome,
        List<String> originals,
        List<QuarantineEntry> entries,
        List<String> coreChanges,
        List<Suspect> suspects,
        ErrorInfo failure) {

    /** Versions of the game, the loader and this mod, for the report header. */
    public record Versions(String minecraft, String loader, String loaderVersion, String mod) {}

    public boolean hasQuarantinedEntries() {
        return entries.stream().anyMatch(e -> e.status() == EntryStatus.QUARANTINED);
    }
}
