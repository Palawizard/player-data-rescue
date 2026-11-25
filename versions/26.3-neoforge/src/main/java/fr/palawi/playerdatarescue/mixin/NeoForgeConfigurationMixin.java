package fr.palawi.playerdatarescue.mixin;

import com.mojang.authlib.GameProfile;
import fr.palawi.playerdatarescue.mc.HeldPackets;
import fr.palawi.playerdatarescue.mc.Join263;
import fr.palawi.playerdatarescue.mc.RescueService;
import fr.palawi.playerdatarescue.mc.RestoreService;
import fr.palawi.playerdatarescue.mc.Timing;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.server.network.config.PrepareSpawnTask;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

/**
 * The placement guard of 26.3 on NeoForge, whose listener makes its cookie with
 * {@code createCookie(ClientInformation, ConnectionType)}; Forge and Fabric use the one in common-26.3. It runs
 * inside the catch of {@code handleConfigurationFinished}, just before the game logs "Couldn't
 * place player in world" and disconnects: when the rescue lets the player in, the method returns.
 */
@Mixin(ServerConfigurationPacketListenerImpl.class)
abstract class NeoForgeConfigurationMixin extends ServerCommonPacketListenerImpl {
    @Shadow
    @Final
    private GameProfile gameProfile;

    @Shadow
    private ClientInformation clientInformation;

    @Shadow
    private PrepareSpawnTask prepareSpawnTask;

    NeoForgeConfigurationMixin(MinecraftServer server, Connection connection, CommonListenerCookie cookie) {
        super(server, connection, cookie);
    }

    @Inject(
            method = "handleConfigurationFinished",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/server/network/config/PrepareSpawnTask;spawnPlayer(Lnet/minecraft/network/Connection;Lnet/minecraft/server/network/CommonListenerCookie;)Lnet/minecraft/server/level/ServerPlayer;"))
    private void playerdatarescue$holdPackets(ServerboundFinishConfigurationPacket packet, CallbackInfo ci) {
        long start = Timing.start();
        HeldPackets.hold(connection);
        Timing.stop(gameProfile.id(), start);
    }

    @Inject(
            method = "handleConfigurationFinished",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/server/network/config/PrepareSpawnTask;spawnPlayer(Lnet/minecraft/network/Connection;Lnet/minecraft/server/network/CommonListenerCookie;)Lnet/minecraft/server/level/ServerPlayer;",
                            shift = At.Shift.AFTER))
    private void playerdatarescue$releasePackets(ServerboundFinishConfigurationPacket packet, CallbackInfo ci) {
        long start = Timing.start();
        HeldPackets.release(connection);
        Timing.stop(gameProfile.id(), start);
    }

    /**
     * Before the game builds and loads the player in {@code Ready#spawn}, a queued restore goes
     * into the data. Here the method runs on the server thread, after the duplicate login and
     * {@code canPlayerLogin} checks; the first read of the file, in {@code PrepareSpawnTask#start},
     * only decides the spawn, which a restore never changes.
     */
    @Inject(
            method = "handleConfigurationFinished",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/server/network/config/PrepareSpawnTask;spawnPlayer(Lnet/minecraft/network/Connection;Lnet/minecraft/server/network/CommonListenerCookie;)Lnet/minecraft/server/level/ServerPlayer;"))
    private void playerdatarescue$beforeRead(ServerboundFinishConfigurationPacket packet, CallbackInfo ci) {
        Join263 join = join();
        RestoreService.beforeRead(join.id(), join.name(), join.worldDirectory(), join.minecraftVersion(), join::source);
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
        Timing.stop(gameProfile.id(), start);
        RescueService.onLoginFinished(join());
    }

    private Join263 join() {
        return new Join263(
                server,
                connection,
                gameProfile,
                clientInformation,
                () -> createCookie(clientInformation, connectionType),
                () -> prepareSpawnTask);
    }
}
