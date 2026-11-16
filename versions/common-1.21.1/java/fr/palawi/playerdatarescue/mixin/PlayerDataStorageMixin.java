package fr.palawi.playerdatarescue.mixin;

import fr.palawi.playerdatarescue.mc.RescueService;
import java.io.File;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * T2 on 1.21.1: {@code PlayerDataStorage#load(Player, String)} catches a read failure and logs it;
 * the game then copies the file to {@code _corrupted_} and reads {@code .dat_old} itself. This
 * hook backs up both files at that log line, before any save can replace them.
 */
@Mixin(PlayerDataStorage.class)
abstract class PlayerDataStorageMixin {
    @Shadow
    @Final
    private File playerDir;

    @Inject(
            method = "load(Lnet/minecraft/world/entity/player/Player;Ljava/lang/String;)Ljava/util/Optional;",
            at = @At(value = "INVOKE", target = "Lorg/slf4j/Logger;warn(Ljava/lang/String;Ljava/lang/Object;)V"))
    private void playerdatarescue$unreadable(
            Player player, String suffix, CallbackInfoReturnable<Optional<CompoundTag>> cir) {
        if (".dat".equals(suffix) && player instanceof ServerPlayer serverPlayer) {
            RescueService.onUnreadableFile(
                    player.getUUID(),
                    player.getGameProfile().getName(),
                    playerDir.toPath(),
                    serverPlayer.getServer().getWorldPath(LevelResource.ROOT),
                    true);
        }
    }
}
