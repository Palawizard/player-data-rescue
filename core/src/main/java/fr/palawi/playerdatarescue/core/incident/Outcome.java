package fr.palawi.playerdatarescue.core.incident;

/** How an incident ended. */
public enum Outcome {
    /** The player joined with the salvaged data. */
    RESCUED,
    /** Salvage was not enough; the player joined with the minimal profile. */
    MINIMAL_PROFILE,
    /** The main file was unreadable; the player joined with the {@code _old} copy. */
    PREVIOUS_SAVE,
    /** Both files were unreadable; the player joined as a new player. */
    UNREADABLE_FILE,
    /** Placement failed even with the minimal profile: the saved data is not the cause. */
    NOT_DATA_RELATED
}
