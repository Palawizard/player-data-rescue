package fr.palawi.playerdatarescue.mixin;

import com.mojang.authlib.GameProfile;
import fr.palawi.playerdatarescue.mc.HeldPackets;
import fr.palawi.playerdatarescue.mc.Join1211;
import fr.palawi.playerdatarescue.mc.RescueService;
import fr.palawi.playerdatarescue.mc.Timing;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

/**
 * The placement guard of 1.21.1 on Forge and Fabric, whose listener makes its cookie with
 * {@code createCookie(ClientInformation)} (NeoForge has its own copy of this mixin). It runs
 * inside the catch of {@code handleConfigurationFinished}, just before the game logs "Couldn't
 * place player in world" and disconnects: when the rescue lets the player in, the method returns.
 */
@Mixin(ServerConfigurationPacketListenerImpl.class)
abstract class ServerConfigurationPacketListenerImplMixin extends ServerCommonPacketListenerImpl {
    @Shadow
    @Final
    private GameProfile gameProfile;

    @Shadow
    private ClientInformation clientInformation;

    ServerConfigurationPacketListenerImplMixin(
            MinecraftServer server, Connection connection, CommonListenerCookie cookie) {
        super(server, connection, cookie);
    }

    @Inject(
            method = "handleConfigurationFinished",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/server/players/PlayerList;placeNewPlayer(Lnet/minecraft/network/Connection;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/network/CommonListenerCookie;)V"))
    private void playerdatarescue$holdPackets(ServerboundFinishConfigurationPacket packet, CallbackInfo ci) {
        long start = Timing.start();
        HeldPackets.hold(connection);
        Timing.stop(gameProfile.getId(), start);
    }

    @Inject(
            method = "handleConfigurationFinished",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/server/players/PlayerList;placeNewPlayer(Lnet/minecraft/network/Connection;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/network/CommonListenerCookie;)V",
                            shift = At.Shift.AFTER))
    private void playerdatarescue$releasePackets(ServerboundFinishConfigurationPacket packet, CallbackInfo ci) {
        long start = Timing.start();
        HeldPackets.release(connection);
        Timing.stop(gameProfile.getId(), start);
    }

    @Inject(
            method = "handleConfigurationFinished",
            at = @At(value = "INVOKE", target = "Lorg/slf4j/Logger;error(Ljava/lang/String;Ljava/lang/Throwable;)V"),
            cancellable = true,
            locals = LocalCapture.CAPTURE_FAILHARD)
    private void playerdatarescue$rescue(
            ServerboundFinishConfigurationPacket packet, CallbackInfo ci, Exception failure) {
        if (RescueService.onPlacementFailure(join(), failure)) {
            ci.cancel();
        }
    }

    @Inject(method = "handleConfigurationFinished", at = @At("RETURN"))
    private void playerdatarescue$joined(ServerboundFinishConfigurationPacket packet, CallbackInfo ci) {
        long start = Timing.start();
        HeldPackets.release(connection);
        Timing.stop(gameProfile.getId(), start);
        RescueService.onLoginFinished(join());
    }

    private Join1211 join() {
        return new Join1211(server, connection, gameProfile, clientInformation, () -> createCookie(clientInformation));
    }
}
