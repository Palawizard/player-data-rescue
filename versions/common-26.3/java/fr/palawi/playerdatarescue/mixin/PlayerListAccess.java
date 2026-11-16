package fr.palawi.playerdatarescue.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The game's own save of one player, used to persist a rescued player straight away (SPEC.md step 5). */
@Mixin(PlayerList.class)
public interface PlayerListAccess {
    @Invoker("save")
    void playerdatarescue$save(ServerPlayer player);
}
