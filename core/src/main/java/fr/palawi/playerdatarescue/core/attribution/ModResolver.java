package fr.palawi.playerdatarescue.core.attribution;

import java.util.Optional;

/** What the loader knows about installed mods, for attribution. */
public interface ModResolver {
    /** The installed mod whose files contain this class, if any. */
    Optional<String> modOfClass(String className);

    /** The mod that owns the mixin merged into this frame's method, if it carries one. */
    Optional<String> mixinOwner(StackTraceElement frame);

    boolean isInstalled(String modId);
}
