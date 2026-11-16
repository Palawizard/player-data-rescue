package fr.palawi.playerdatarescue.forge;

import fr.palawi.playerdatarescue.core.ModInfo;
import fr.palawi.playerdatarescue.mc.PlayerLoadingEvent;
import fr.palawi.playerdatarescue.mc.RescueService;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(ModInfo.MOD_ID)
public final class PlayerDataRescueForge {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModInfo.MOD_ID);

    public PlayerDataRescueForge() {
        RescueService.init(new ForgeBridge());
        // As PrepareSpawnTask$Ready#spawn: only when the player has saved data.
        PlayerLoadingEvent.set((server, player, hasData) -> {
            if (hasData) {
                ForgeEventFactory.firePlayerLoadingEvent(
                        player,
                        server.getPlayerList().getPlayerIo().getPlayerDataFolder(),
                        player.getUUID().toString());
            }
        });
        LOGGER.info(ModInfo.loadedLine("forge", "26.3"));
    }
}
