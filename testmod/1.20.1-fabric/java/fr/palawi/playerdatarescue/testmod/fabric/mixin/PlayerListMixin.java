package fr.palawi.playerdatarescue.testmod.fabric.mixin;

import fr.palawi.playerdatarescue.testmod.TestModes;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Same moment as the PlayerLoggedInEvent of Forge and NeoForge: the end of placement. */
@Mixin(PlayerList.class)
abstract class PlayerListMixin {
    @Inject(method = "placeNewPlayer", at = @At("TAIL"))
    private void pdrtest$throwOnPlacement(Connection connection, ServerPlayer player, CallbackInfo ci) {
        String name = player.getGameProfile().getName();
        if (TestModes.has(name, TestModes.THROW_ON_PLACEMENT)) {
            throw TestModes.failure("placement of " + name);
        }
    }
}
