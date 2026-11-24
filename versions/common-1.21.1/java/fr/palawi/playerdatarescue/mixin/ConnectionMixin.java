package fr.palawi.playerdatarescue.mixin;

import fr.palawi.playerdatarescue.core.net.PacketHold;
import fr.palawi.playerdatarescue.mc.HeldPackets;
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
 * Keeps back the packets of a placement until it worked ({@link PacketHold}, HeldPackets).
 * Every packet goes through {@code send(Packet, PacketSendListener, boolean)}.
 */
@Mixin(Connection.class)
abstract class ConnectionMixin implements HeldPackets {
    @Unique
    private final PacketHold playerdatarescue$hold = new PacketHold();

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
        if (playerdatarescue$hold.holding()
                && playerdatarescue$hold.keep(
                        () -> send(packet, listener, flush), packet instanceof ClientboundDisconnectPacket)) {
            ci.cancel();
        }
    }

    @Override
    public void playerdatarescue$hold() {
        playerdatarescue$hold.hold();
    }

    @Override
    public void playerdatarescue$release() {
        if (playerdatarescue$hold.release()) {
            flushChannel();
        }
    }

    @Override
    public void playerdatarescue$drop() {
        playerdatarescue$hold.drop();
    }
}
