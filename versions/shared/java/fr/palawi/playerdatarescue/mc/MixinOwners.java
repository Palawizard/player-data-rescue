package fr.palawi.playerdatarescue.mc;

import java.lang.reflect.Method;
import java.util.Optional;
import org.spongepowered.asm.mixin.transformer.meta.MixinMerged;

/**
 * Finds the mixin class merged into a stack frame's method: Mixin marks every merged method with
 * {@link MixinMerged}, which names the mixin. Only used while attributing a failure.
 */
public final class MixinOwners {
    private MixinOwners() {}

    public static Optional<String> mixinClass(StackTraceElement frame) {
        // Mixin gives every method it merges a name with a '$' (handler$..., or modid$name for
        // plain merged methods), so other frames are skipped without loading anything.
        if (frame.getMethodName().indexOf('$') < 0) {
            return Optional.empty();
        }
        try {
            Class<?> owner = Class.forName(frame.getClassName(), false, MixinOwners.class.getClassLoader());
            for (Method method : owner.getDeclaredMethods()) {
                if (method.getName().equals(frame.getMethodName())) {
                    MixinMerged merged = method.getAnnotation(MixinMerged.class);
                    if (merged != null) {
                        return Optional.of(merged.mixin());
                    }
                }
            }
        } catch (ClassNotFoundException | RuntimeException | LinkageError e) {
            // Listing the methods resolves every type in their signatures, and Forge and NeoForge
            // refuse to load client-only types on a dedicated server: this signal is then lost.
            return Optional.empty();
        }
        return Optional.empty();
    }
}
