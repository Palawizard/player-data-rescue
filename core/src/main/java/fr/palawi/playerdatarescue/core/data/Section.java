package fr.palawi.playerdatarescue.core.data;

/** Sections of the saved player data, as listed in SPEC.md section 4.1. */
public enum Section {
    CORE("core"),
    INVENTORY("inventory"),
    EQUIPMENT("equipment"),
    ENDER_CHEST("ender chest"),
    EFFECTS("effects"),
    ATTACHMENTS("attachments"),
    VEHICLE("vehicle"),
    RECIPE_BOOK("recipe book"),
    UNKNOWN_KEY("unknown key");

    private final String label;

    Section(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Sections whose content only a mod can decode, isolated by retrying the load. */
    public boolean opaque() {
        return this == ATTACHMENTS || this == UNKNOWN_KEY;
    }
}
