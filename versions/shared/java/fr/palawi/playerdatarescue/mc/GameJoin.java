package fr.palawi.playerdatarescue.mc;

import fr.palawi.playerdatarescue.core.data.CoreValues;
import fr.palawi.playerdatarescue.core.incident.Incident;
import fr.palawi.playerdatarescue.core.incident.Outcome;
import fr.palawi.playerdatarescue.core.nbt.NbtCompound;
import fr.palawi.playerdatarescue.core.recurring.RecurringIssues;
import fr.palawi.playerdatarescue.core.rescue.RescueTarget;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * One join, seen from the code of one Minecraft version: who joins, where their data is, and the
 * game calls the rescue needs. Each {@code common-<minecraft>} folder implements it.
 */
public interface GameJoin {
    /** The running server: a new start, or a new integrated server, is a new object. */
    Object server();

    UUID id();

    String name();

    Path worldDirectory();

    String minecraftVersion();

    /** Where the game reads this player's data (SPEC.md section 1). */
    DataSource source();

    /** New players loaded or placed from candidate data, written to {@code source} first. */
    RescueTarget attempts(DataSource source);

    CoreValues.Spawn spawn();

    /**
     * Removes the player that a failed placement left in the player list, without saving it
     * (ARCHITECTURE.md, Rescue strategy), then drops the packets the connection held for the
     * client since that placement started, the removal's own included.
     */
    void discardHalfPlaced();

    /** True when the player is in the world. */
    boolean placed();

    /** Saves the player through the game's own save path (SPEC.md step 5). */
    void save();

    void tellPlayer(Outcome outcome);

    /** Tells the player that set-aside data came back at this join. */
    void tellRestored(int entries);

    /**
     * Drops restored stacks that found no free slot at the player's feet, with no pickup delay
     * (SPEC.md section 7.1). Each stack is decoded by the game; returns those it could not read.
     */
    List<NbtCompound> dropAtFeet(List<NbtCompound> stacks);

    /**
     * Tells every online operator except the joining player about a recurring issue; returns how
     * many were told.
     */
    int tellOperatorsRecurring(RecurringIssues.Issue issue, String file, int permissionLevel);

    /** Tells every online operator except the joining player. */
    void tellOperators(Incident incident, String folder, int permissionLevel);
}
