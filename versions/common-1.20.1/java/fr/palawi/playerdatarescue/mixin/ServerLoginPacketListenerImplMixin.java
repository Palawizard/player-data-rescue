package fr.palawi.playerdatarescue.mixin;

import com.mojang.authlib.GameProfile;
import fr.palawi.playerdatarescue.mc.Join1201;
import fr.palawi.playerdatarescue.mc.RescueService;
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
            at = @At(value = "INVOKE", target = "Lorg/slf4j/Logger;error(Ljava/lang/String;Ljava/lang/Throwable;)V"),
            cancellable = true,
            locals = LocalCapture.CAPTURE_FAILHARD)
    private void playerdatarescue$rescue(CallbackInfo ci, Component refusal, ServerPlayer online, Exception failure) {
        if (RescueService.onPlacementFailure(new Join1201(server, connection, gameProfile), failure)) {
            ci.cancel();
        }
    }

    @Inject(method = "handleAcceptedLogin", at = @At("RETURN"))
    private void playerdatarescue$joined(CallbackInfo ci) {
        RescueService.onLoginFinished(new Join1201(server, connection, gameProfile));
    }
}
