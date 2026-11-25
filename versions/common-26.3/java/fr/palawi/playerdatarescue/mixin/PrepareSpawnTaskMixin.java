package fr.palawi.playerdatarescue.mixin;

import fr.palawi.playerdatarescue.mc.Join263;
import fr.palawi.playerdatarescue.mc.RestoreService;
import java.util.function.Consumer;
import net.minecraft.SharedConstants;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.config.PrepareSpawnTask;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.storage.LevelResource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.3 reads the joining player's data first here, during configuration (ARCHITECTURE.md, Hook
 * points): a queued restore goes into the data before that read. The duplicate login check comes
 * later, in {@code handleConfigurationFinished}, so a second connection of a player who is online
 * reaches this point too; its restore waits for a join with nobody online under that profile,
 * since the online player's next save would replace the merged data. The task can start on a
 * network thread (after the code of conduct is accepted), so the restore runs on the server thread.
 */
@Mixin(PrepareSpawnTask.class)
abstract class PrepareSpawnTaskMixin {
    @Shadow
    @Final
    private MinecraftServer server;

    @Shadow
    @Final
    private NameAndId nameAndId;

    @Inject(method = "start", at = @At("HEAD"))
    private void playerdatarescue$beforeRead(Consumer<Packet<?>> sender, CallbackInfo ci) {
        server.executeBlocking(() -> {
            if (server.getPlayerList().getPlayer(nameAndId.id()) != null) {
                return;
            }
            RestoreService.beforeRead(
                    nameAndId.id(),
                    nameAndId.name(),
                    server.getWorldPath(LevelResource.ROOT),
                    SharedConstants.getCurrentVersion().name(),
                    () -> Join263.source(server, nameAndId));
        });
    }
}
