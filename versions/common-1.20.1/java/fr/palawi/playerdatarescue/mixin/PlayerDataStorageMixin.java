package fr.palawi.playerdatarescue.mixin;

import fr.palawi.playerdatarescue.mc.RescueService;
import fr.palawi.playerdatarescue.mc.Timing;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * T2 on 1.20.1: {@code PlayerDataStorage#load(Player)} catches a read failure, logs it, and starts
 * the player from scratch. The first hook backs up both files at that log line, before any save
 * can replace them; the second hands the readable {@code .dat_old} to the load instead, which the
 * game itself only does from 1.20.2 on (ARCHITECTURE.md, Unreadable file).
 */
@Mixin(PlayerDataStorage.class)
abstract class PlayerDataStorageMixin {
    @Shadow
    @Final
    private File playerDir;

    @Inject(
            method = "load",
            at = @At(value = "INVOKE", target = "Lorg/slf4j/Logger;warn(Ljava/lang/String;Ljava/lang/Object;)V"))
    private void playerdatarescue$unreadable(Player player, CallbackInfoReturnable<CompoundTag> cir) {
        if (player instanceof ServerPlayer serverPlayer) {
            RescueService.onUnreadableFile(
                    player.getUUID(),
                    player.getGameProfile().getName(),
                    playerDir.toPath(),
                    serverPlayer.getServer().getWorldPath(LevelResource.ROOT),
                    false);
        }
    }

    @ModifyVariable(method = "load", at = @At(value = "LOAD", ordinal = 0), ordinal = 0)
    private CompoundTag playerdatarescue$previousSave(CompoundTag read, Player player) {
        long start = Timing.start();
        try {
            if (read != null) {
                return read;
            }
            Optional<Path> previous = RescueService.previousSave(player.getUUID());
            return previous.isPresent() ? NbtIo.readCompressed(previous.get().toFile()) : null;
        } catch (IOException e) {
            return null;
        } finally {
            Timing.stop(player.getUUID(), start);
        }
    }
}
