package fr.palawi.playerdatarescue.mixin;

import com.mojang.authlib.GameProfile;
import fr.palawi.playerdatarescue.mc.HeldPackets;
import fr.palawi.playerdatarescue.mc.Join1201;
import fr.palawi.playerdatarescue.mc.RescueService;
import fr.palawi.playerdatarescue.mc.RestoreService;
import fr.palawi.playerdatarescue.mc.Timing;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

/**
 * The placement guard of 1.20.1 (ARCHITECTURE.md, Hook points). It runs inside the catch of
 * {@code handleAcceptedLogin}, just before the game logs "Couldn't place player in world" and
 * disconnects: when the rescue lets the player in, the method returns there instead.
 */
@Mixin(ServerLoginPacketListenerImpl.class)
abstract class ServerLoginPacketListenerImplMixin {
    @Shadow
    @Final
    MinecraftServer server;

    @Shadow
    @Final
    Connection connection;

    @Shadow
    GameProfile gameProfile;

    @Inject(
            method = "handleAcceptedLogin",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/server/network/ServerLoginPacketListenerImpl;placeNewPlayer(Lnet/minecraft/server/level/ServerPlayer;)V"))
    private void playerdatarescue$holdPackets(CallbackInfo ci) {
        long start = Timing.start();
        HeldPackets.hold(connection);
        Timing.stop(gameProfile.getId(), start);
    }

    @Inject(
            method = "handleAcceptedLogin",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/server/network/ServerLoginPacketListenerImpl;placeNewPlayer(Lnet/minecraft/server/level/ServerPlayer;)V",
                            shift = At.Shift.AFTER))
    private void playerdatarescue$releasePackets(CallbackInfo ci) {
        long start = Timing.start();
        HeldPackets.release(connection);
        Timing.stop(gameProfile.getId(), start);
    }

    @Inject(
            method = "handleAcceptedLogin",
            at = @At(value = "INVOKE", target = "Lorg/slf4j/Logger;error(Ljava/lang/String;Ljava/lang/Throwable;)V"),
            cancellable = true,
            locals = LocalCapture.CAPTURE_FAILHARD)
    private void playerdatarescue$rescue(CallbackInfo ci, Component refusal, ServerPlayer online, Exception failure) {
        if (RescueService.onPlacementFailure(new Join1201(server, connection, gameProfile), failure)) {
            ci.cancel();
        }
    }

    /**
     * Before the game loads the player, a queued restore goes into the data. Not before {@code
     * getPlayerForLogin}: when the same player is still online, that call disconnects them and
     * saves their data, which would replace the restored data. Here the old player is gone, both
     * when the placement runs at once and when it was delayed until then.
     */
    @Inject(method = "placeNewPlayer", at = @At("HEAD"))
    private void playerdatarescue$beforeRead(ServerPlayer player, CallbackInfo ci) {
        Join1201 join = new Join1201(server, connection, gameProfile);
        RestoreService.beforeRead(join.id(), join.name(), join.worldDirectory(), join.minecraftVersion(), join::source);
    }

    /** The game placed the player; also the end of a placement that was delayed. */
    @Inject(method = "placeNewPlayer", at = @At("RETURN"))
    private void playerdatarescue$placed(ServerPlayer player, CallbackInfo ci) {
        RestoreService.joined(new Join1201(server, connection, gameProfile));
    }

    @Inject(method = "handleAcceptedLogin", at = @At("RETURN"))
    private void playerdatarescue$joined(CallbackInfo ci) {
        long start = Timing.start();
        HeldPackets.release(connection);
        Timing.stop(gameProfile.getId(), start);
        RescueService.onLoginFinished(new Join1201(server, connection, gameProfile));
    }
}
