package fr.palawi.playerdatarescue.core.attribution;

import java.util.Optional;

/**
 * One stack frame of a failure, with the mod that owns its class and, for a game method changed
 * by a mixin, the mod that owns the mixin. The loader adapter fills both in.
 */
public record Frame(String className, String methodName, Optional<String> modId, Optional<String> mixinModId) {}
