package fr.palawi.playerdatarescue.mc;

import fr.palawi.playerdatarescue.mixin.ServerGamePacketListenerImplAccess;
import net.minecraft.server.level.ServerPlayer;

/**
 * Teleport ids across the placements of one connection. Each placement makes a new game listener
 * whose teleport ids start again at 1, and the client confirms the teleport of every placement it
 * was sent. A listener that gets a confirmation for its current id while it waits for none
 * disconnects the client ("Invalid move player packet received"), so a client that got a failed
 * placement would be disconnected right after the rescue placed it. After a placement that
 * follows failed ones, the new listener moves its ids past every id the failed ones used and
 * teleports the player where it already is: the confirmations of the failed placements then
 * carry ids it does not wait for, which the game ignores.
 */
final class StaleTeleports {
    private int highest;

    /** A placement failed after the game made its listener: remember the ids it used. */
    void failed(ServerPlayer player) {
        if (player.connection != null) {
            highest = Math.max(
                    highest,
                    ((ServerGamePacketListenerImplAccess) player.connection).playerdatarescue$awaitingTeleport());
        }
    }

    /** A placement worked on a connection that had failed ones. */
    void placed(ServerPlayer player) {
        if (highest == 0 || player.connection == null) {
            return;
        }
        ServerGamePacketListenerImplAccess listener = (ServerGamePacketListenerImplAccess) player.connection;
        listener.playerdatarescue$setAwaitingTeleport(Math.max(highest, listener.playerdatarescue$awaitingTeleport()));
        player.connection.teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
    }
}
