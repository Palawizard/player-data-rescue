package fr.palawi.playerdatarescue.testmod.fabric.mixin;

import fr.palawi.playerdatarescue.testmod.SaveMutator;
import java.io.File;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Same moment as Forge's and NeoForge's SaveToFile event: right after the file is in place. */
@Mixin(PlayerDataStorage.class)
abstract class PlayerDataStorageMixin {
    @Shadow
    @Final
    private File playerDir;

    @Inject(method = "save", at = @At("TAIL"))
    private void pdrtest$afterSave(Player player, CallbackInfo ci) {
        SaveMutator.afterSave(player.getGameProfile().name(), playerDir, player.getStringUUID());
    }
}
