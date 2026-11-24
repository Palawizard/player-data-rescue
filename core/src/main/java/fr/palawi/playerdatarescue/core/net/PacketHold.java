package fr.palawi.playerdatarescue.core.net;

import java.util.ArrayList;
import java.util.List;

/**
 * The packets one connection keeps back while a player is placed (ARCHITECTURE.md, Cleanup after a
 * failed placement). Each band's {@code ConnectionMixin} asks {@link #keep} for every packet it
 * sends. Safe to use from several threads; a packet another thread sends while the kept ones are
 * sent again can go before them.
 */
public final class PacketHold {
    private final Object lock = new Object();
    private volatile List<Runnable> kept;

    /** From now on, packets wait in order until {@link #release} or {@link #drop}. */
    public void hold() {
        synchronized (lock) {
            if (kept == null) {
                kept = new ArrayList<>();
            }
        }
    }

    /** Whether packets are held: one volatile read, for the path every packet of the server takes. */
    public boolean holding() {
        return kept != null;
    }

    /**
     * Returns true when the packet was kept back, and its {@code send} runs at {@link #release};
     * false when it must go now. A disconnect ends the hold, with the packets kept before it sent
     * first, so a player kicked during a placement still gets the reason.
     */
    public boolean keep(Runnable send, boolean disconnect) {
        synchronized (lock) {
            if (kept == null) {
                return false;
            }
            if (disconnect) {
                release();
                return false;
            }
            kept.add(send);
            return true;
        }
    }

    /** Sends the packets kept back, in order, and stops holding. Returns whether any was sent. */
    public boolean release() {
        synchronized (lock) {
            List<Runnable> sends = kept;
            kept = null;
            if (sends == null) {
                return false;
            }
            sends.forEach(Runnable::run);
            return !sends.isEmpty();
        }
    }

    /** Forgets the packets kept back and stops holding. */
    public void drop() {
        synchronized (lock) {
            kept = null;
        }
    }
}
