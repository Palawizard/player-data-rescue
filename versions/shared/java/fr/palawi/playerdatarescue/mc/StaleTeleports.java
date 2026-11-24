package fr.palawi.playerdatarescue.mc;

import fr.palawi.playerdatarescue.core.ModInfo;
import fr.palawi.playerdatarescue.mixin.ServerGamePacketListenerImplAccess;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Logger LOGGER = LoggerFactory.getLogger(ModInfo.MOD_ID);

    private int highest;

    /**
     * A placement failed on {@code connection}: remember the ids its game listener used. The
     * listener is read from the connection, since a placement can fail after the game made the
     * listener and before it added the player to the player list.
     */
    void failed(Connection connection) {
        if (connection.getPacketListener() instanceof ServerGamePacketListenerImplAccess listener) {
            highest = Math.max(highest, listener.playerdatarescue$awaitingTeleport());
        }
    }

    /**
     * A placement worked on a connection that had failed ones. The player is already in the
     * world, so a problem here is logged and never turns the placement into a failure.
     */
    void placed(ServerPlayer player) {
        if (highest == 0 || player.connection == null) {
            return;
        }
        try {
            ServerGamePacketListenerImplAccess listener = (ServerGamePacketListenerImplAccess) player.connection;
            listener.playerdatarescue$setAwaitingTeleport(
                    Math.max(highest, listener.playerdatarescue$awaitingTeleport()));
            player.connection.teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
        } catch (RuntimeException e) {
            LOGGER.error(
                    "[{}] could not move the teleport ids of {} past the failed placement; the client may be"
                            + " disconnected",
                    ModInfo.MOD_ID,
                    player.getScoreboardName(),
                    e);
        }
    }
}
