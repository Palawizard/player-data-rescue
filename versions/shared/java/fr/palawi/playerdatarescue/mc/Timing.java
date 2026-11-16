package fr.palawi.playerdatarescue.mc;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Time spent in this mod's hooks on a normal join, for SPEC.md scenario S12 (TESTING.md). Only
 * active with {@code -Dplayerdatarescue.timing=true}; otherwise each call is one field read.
 */
public final class Timing {
    public static final boolean ENABLED = Boolean.getBoolean("playerdatarescue.timing");

    private static final Map<UUID, long[]> PER_PLAYER = new HashMap<>();

    private Timing() {}

    public static long start() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    public static void stop(UUID player, long start) {
        if (ENABLED) {
            long[] total = PER_PLAYER.computeIfAbsent(player, id -> new long[2]);
            total[0] += System.nanoTime() - start;
            total[1]++;
        }
    }

    /** Nanoseconds and hook calls since the last report for this player, then resets them. */
    public static long[] take(UUID player) {
        long[] total = PER_PLAYER.remove(player);
        return total == null ? new long[2] : total;
    }
}
