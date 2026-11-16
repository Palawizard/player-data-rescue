package fr.palawi.playerdatarescue.mixin;

import fr.palawi.playerdatarescue.mc.RescueService;
import java.io.File;
import java.nio.file.Path;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * T2 on 26.3: {@code PlayerDataStorage#load(NameAndId, String)} catches a read failure and logs it;
 * the game then copies the file to {@code _corrupted_} and reads {@code .dat_old} itself. The file
 * is read twice per join (ARCHITECTURE.md); the first read backs up both files, the second finds
 * the incident already started.
 */
@Mixin(PlayerDataStorage.class)
abstract class PlayerDataStorageMixin {
    @Shadow
    @Final
    private File playerDir;

    @Inject(
            method = "load(Lnet/minecraft/server/players/NameAndId;Ljava/lang/String;)Ljava/util/Optional;",
            at = @At(value = "INVOKE", target = "Lorg/slf4j/Logger;warn(Ljava/lang/String;Ljava/lang/Object;)V"))
    private void playerdatarescue$unreadable(
            NameAndId player, String suffix, CallbackInfoReturnable<Optional<CompoundTag>> cir) {
        if (".dat".equals(suffix)) {
            RescueService.onUnreadableFile(player.id(), player.name(), playerDir.toPath(), worldDirectory(), true);
        }
    }

    private Path worldDirectory() {
        Path world = playerDir.toPath();
        for (String ignored : LevelResource.PLAYER_DATA_DIR.id().split("/")) {
            world = world.getParent();
        }
        return world;
    }
}
