package fr.palawi.playerdatarescue.core.incident;

import fr.palawi.playerdatarescue.core.attribution.Suspect;
import fr.palawi.playerdatarescue.core.data.Section;
import java.util.List;
import java.util.Optional;

/**
 * A unit that was set aside (SPEC.md section 4.2). The binary payload is in
 * {@code quarantine/<payloadFile>}, unless the unit was too large to keep there.
 *
 * @param description the unit in plain words, for example "Slot 17: 12 x create:brass_ingot"
 * @param payloadFile the file name under {@code quarantine/}, empty when {@code tooLarge}
 */
public record QuarantineEntry(
        String id,
        Section section,
        Optional<String> slot,
        String description,
        String snbt,
        ErrorInfo error,
        List<Suspect> suspects,
        EntryStatus status,
        Optional<String> payloadFile,
        boolean tooLarge) {}
