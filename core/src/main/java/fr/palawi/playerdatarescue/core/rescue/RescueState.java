package fr.palawi.playerdatarescue.core.rescue;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Players whose last failure was not data-related. No rescue is tried for them again until the
 * server restarts, which also covers a change of the mod list (SPEC.md section 2). It lives in
 * memory only, so a restart clears it.
 */
public final class RescueState {
    private final Set<UUID> notDataRelated = new HashSet<>();

    public boolean skipRescue(UUID player) {
        return notDataRelated.contains(player);
    }

    public void markNotDataRelated(UUID player) {
        notDataRelated.add(player);
    }
}
