package fr.palawi.playerdatarescue.testmod.fabric.mixin;

import fr.palawi.playerdatarescue.testmod.TestModes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Placement failure at the same moment as the PlayerLoggedInEvent of Forge and NeoForge (the end
 * of placement), and the host data of {@link TestModes#HOST_DATA}.
 */
@Mixin(PlayerList.class)
abstract class PlayerListMixin {
    private static final Logger PDRTEST_LOGGER = LoggerFactory.getLogger("pdrtest");

    @Inject(method = "placeNewPlayer", at = @At("TAIL"))
    private void pdrtest$throwOnPlacement(
            Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo ci) {
        String name = player.getGameProfile().getName();
        TestModes.checkPlacement(name, player);
    }

    @Inject(method = "getSingleplayerData", at = @At("HEAD"), cancellable = true)
    private void pdrtest$hostData(CallbackInfoReturnable<CompoundTag> cir) {
        for (ServerPlayer player : ((PlayerList) (Object) this).getPlayers()) {
            String name = player.getGameProfile().getName();
            if (TestModes.has(name, TestModes.HOST_DATA)) {
                PDRTEST_LOGGER.info("{}wrote the data of {} as the host player", TestModes.LOG_PREFIX, name);
                cir.setReturnValue(player.saveWithoutId(new CompoundTag()));
                return;
            }
        }
    }
}
