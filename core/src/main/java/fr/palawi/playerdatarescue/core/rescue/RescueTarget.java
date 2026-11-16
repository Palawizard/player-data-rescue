package fr.palawi.playerdatarescue.core.rescue;

import fr.palawi.playerdatarescue.core.nbt.NbtCompound;

/**
 * What a band does for the rescue flow. Every call builds a new player (ADR-PDR-005) from the
 * given data, through the game's own load path.
 */
public interface RescueTarget {
    /** Loads the data into a new player that is never placed in the world. Throws what the load throws. */
    void loadOnly(NbtCompound data) throws Exception;

    /**
     * Places a new player loaded from the data, as the login flow does. When it throws, the band
     * has already removed whatever the failed placement registered.
     */
    void place(NbtCompound data) throws Exception;
}
