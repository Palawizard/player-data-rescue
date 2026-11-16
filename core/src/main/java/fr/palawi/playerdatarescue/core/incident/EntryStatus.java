package fr.palawi.playerdatarescue.core.incident;

import java.util.Locale;

public enum EntryStatus {
    QUARANTINED,
    RESTORED,
    DISMISSED,
    RESTORE_FAILED;

    public String json() {
        return name().toLowerCase(Locale.ROOT);
    }
}
