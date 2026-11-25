package fr.palawi.playerdatarescue.core.incident;

/** What started an incident (SPEC.md section 2). */
public enum Trigger {
    T1("Reading or decoding the saved data threw while the player was loaded."),
    T2("The player file exists but could not be parsed."),
    T3("Code that runs while the player is placed in the world threw."),
    RESTORE("An operator restored entries of an earlier incident at this join.");

    private final String description;

    Trigger(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
