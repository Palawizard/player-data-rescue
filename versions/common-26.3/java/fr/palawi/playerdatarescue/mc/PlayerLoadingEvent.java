package fr.palawi.playerdatarescue.mc;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The loader's player loading event on 26.3. Forge and NeoForge fire it in {@code
 * PrepareSpawnTask$Ready#spawn}, right after {@code ServerPlayer#load}, not inside the read of the
 * player file as on 1.20.1 and 1.21.1. A load-only attempt fires it the same way, so a listener that
 * fails on the data fails during the load, as in a real join. Fabric has no such event.
 */
public final class PlayerLoadingEvent {
    /** Fires the event for a player whose data was just loaded. */
    @FunctionalInterface
    public interface Hook {
        void fire(MinecraftServer server, ServerPlayer player, boolean hasData);
    }

    private static volatile Hook hook = (server, player, hasData) -> {};

    private PlayerLoadingEvent() {}

    public static void set(Hook loaderHook) {
        hook = loaderHook;
    }

    static void fire(MinecraftServer server, ServerPlayer player, boolean hasData) {
        hook.fire(server, player, hasData);
    }
}
