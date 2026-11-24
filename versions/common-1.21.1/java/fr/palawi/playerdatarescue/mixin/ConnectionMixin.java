package fr.palawi.playerdatarescue.mixin;

import fr.palawi.playerdatarescue.mc.HeldPackets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps back the packets of a placement until it worked (HeldPackets). Every packet goes through
 * {@code send(Packet, PacketSendListener, boolean)}; a disconnect ends the hold, so a player kicked
 * during placement still gets the reason.
 */
@Mixin(Connection.class)
abstract class ConnectionMixin implements HeldPackets {
    @Unique
    private final Object playerdatarescue$lock = new Object();

    @Unique
    private volatile List<Runnable> playerdatarescue$held;

    @Shadow
    public abstract void send(Packet<?> packet, PacketSendListener listener, boolean flush);

    @Shadow
    public abstract void flushChannel();

    @Inject(
            method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;Z)V",
            at = @At("HEAD"),
            cancellable = true)
    private void playerdatarescue$keepBack(
            Packet<?> packet, PacketSendListener listener, boolean flush, CallbackInfo ci) {
        // Every packet of the server comes here: when nothing is held, this callback and one volatile read.
        if (playerdatarescue$held == null) {
            return;
        }
        synchronized (playerdatarescue$lock) {
            if (playerdatarescue$held == null) {
                return;
            }
            if (packet instanceof ClientboundDisconnectPacket) {
                playerdatarescue$release();
                return;
            }
            playerdatarescue$held.add(() -> send(packet, listener, flush));
            ci.cancel();
        }
    }

    @Override
    public void playerdatarescue$hold() {
        synchronized (playerdatarescue$lock) {
            if (playerdatarescue$held == null) {
                playerdatarescue$held = new ArrayList<>();
            }
        }
    }

    @Override
    public void playerdatarescue$release() {
        synchronized (playerdatarescue$lock) {
            List<Runnable> held = playerdatarescue$held;
            playerdatarescue$held = null;
            if (held != null && !held.isEmpty()) {
                held.forEach(Runnable::run);
                flushChannel();
            }
        }
    }

    @Override
    public void playerdatarescue$drop() {
        synchronized (playerdatarescue$lock) {
            playerdatarescue$held = null;
        }
    }
}
