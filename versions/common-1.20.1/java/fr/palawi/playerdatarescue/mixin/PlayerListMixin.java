package fr.palawi.playerdatarescue.mixin;

import fr.palawi.playerdatarescue.mc.RescueService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A player instance from a failed attempt is never saved: its half-loaded state would replace the
 * data being rescued when the game removes it (ARCHITECTURE.md, Rescue strategy).
 */
@Mixin(PlayerList.class)
abstract class PlayerListMixin {
    @Inject(method = "save", at = @At("HEAD"), cancellable = true)
    private void playerdatarescue$skipFailedAttempt(ServerPlayer player, CallbackInfo ci) {
        if (RescueService.skipSave(player)) {
            ci.cancel();
        }
    }
}
