package fr.palawi.playerdatarescue.core.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PacketHoldTest {
    private final PacketHold hold = new PacketHold();
    private final List<String> sent = new ArrayList<>();

    /** Sends a packet the way the mixin does: through the hold, then straight out if it says so. */
    private void send(String packet, boolean disconnect) {
        if (!hold.keep(() -> send(packet, disconnect), disconnect)) {
            sent.add(packet);
        }
    }

    @Test
    void packetsGoOutAtOnceWhenNothingIsHeld() {
        send("login", false);
        assertEquals(List.of("login"), sent);
    }

    @Test
    void releaseSendsTheKeptPacketsInOrder() {
        hold.hold();
        send("login", false);
        send("position", false);
        assertEquals(List.of(), sent);

        assertTrue(hold.release());
        assertEquals(List.of("login", "position"), sent);
        send("chat", false);
        assertEquals(List.of("login", "position", "chat"), sent);
    }

    @Test
    void dropForgetsTheKeptPacketsAndEndsTheHold() {
        hold.hold();
        send("login of the failed placement", false);
        hold.drop();
        assertFalse(hold.release());

        send("login of the next placement", false);
        assertEquals(List.of("login of the next placement"), sent);
    }

    @Test
    void aDisconnectGoesOutAfterThePacketsKeptBeforeIt() {
        hold.hold();
        send("login", false);
        send("disconnect", true);
        assertEquals(List.of("login", "disconnect"), sent);

        send("after", false);
        assertEquals(List.of("login", "disconnect", "after"), sent);
    }

    @Test
    void holdingTwiceKeepsWhatIsAlreadyHeld() {
        hold.hold();
        send("login", false);
        hold.hold();
        hold.release();
        assertEquals(List.of("login"), sent);
    }

    @Test
    void holdingFollowsHoldReleaseAndDrop() {
        assertFalse(hold.holding());
        hold.hold();
        assertTrue(hold.holding());
        hold.release();
        assertFalse(hold.holding());
        hold.hold();
        hold.drop();
        assertFalse(hold.holding());
    }

    @Test
    void aSendThatThrowsDuringReleaseEndsTheHold() {
        hold.hold();
        send("login", false);
        hold.keep(
                () -> {
                    throw new IllegalStateException("refused packet");
                },
                false);
        send("after the refused one", false);

        assertThrows(IllegalStateException.class, hold::release);
        assertEquals(List.of("login"), sent);
        assertFalse(hold.holding());
    }

    @Test
    void releaseWithNothingKeptSendsNothing() {
        assertFalse(hold.release());
        hold.hold();
        assertFalse(hold.release());
    }
}
