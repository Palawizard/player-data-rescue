package fr.palawi.playerdatarescue.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The id of the last teleport a game listener sent its client (ARCHITECTURE.md, Cleanup after a failed placement). */
@Mixin(ServerGamePacketListenerImpl.class)
public interface ServerGamePacketListenerImplAccess {
    @Accessor("awaitingTeleport")
    int playerdatarescue$awaitingTeleport();

    @Accessor("awaitingTeleport")
    void playerdatarescue$setAwaitingTeleport(int id);
}
