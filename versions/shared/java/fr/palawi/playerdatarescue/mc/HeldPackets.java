package fr.palawi.playerdatarescue.mc;

import net.minecraft.network.Connection;

/**
 * Packets a connection keeps back while a player is placed, implemented on {@code Connection} by
 * the band's {@code ConnectionMixin} (ARCHITECTURE.md, Cleanup after a failed placement). The
 * client only gets the packets of a placement that worked: those of a failed one are dropped, so
 * a rescue never sends it a second login packet.
 */
public interface HeldPackets {
    /** From now on, packets sent on this connection wait, in order, until release or drop. */
    void playerdatarescue$hold();

    /** Sends the packets kept back, in order, and stops holding. Does nothing when not holding. */
    void playerdatarescue$release();

    /** Forgets the packets kept back and stops holding. Does nothing when not holding. */
    void playerdatarescue$drop();

    static void hold(Connection connection) {
        ((HeldPackets) connection).playerdatarescue$hold();
    }

    static void release(Connection connection) {
        ((HeldPackets) connection).playerdatarescue$release();
    }

    static void drop(Connection connection) {
        ((HeldPackets) connection).playerdatarescue$drop();
    }
}
