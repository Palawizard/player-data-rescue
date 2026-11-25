package fr.palawi.playerdatarescue.core.incident;

import java.util.Locale;

/** The status of a quarantine entry (SPEC.md sections 6 and 7.1). */
public enum EntryStatus {
    QUARANTINED,
    RESTORED,
    DISMISSED,
    /** The entry never reached the player's data, or that data was put back as it was: it can be queued again. */
    RESTORE_FAILED,
    /**
     * The entry went into the player's data, and the rescue of that join set it aside again in
     * another incident. It is restored from there, so it is never queued again here.
     */
    SET_ASIDE_AGAIN;

    public String json() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** An entry whose payload is not anywhere else, so restoring it cannot duplicate anything. */
    public boolean restorable() {
        return this == QUARANTINED || this == RESTORE_FAILED || this == DISMISSED;
    }

    public boolean dismissable() {
        return this == QUARANTINED || this == RESTORE_FAILED;
    }

    /** An entry that can still be restored keeps its incident from being pruned. */
    public boolean keepsIncident() {
        return this == QUARANTINED || this == RESTORE_FAILED;
    }
}
