package fr.palawi.playerdatarescue.neoforge;

import fr.palawi.playerdatarescue.core.ModInfo;
import fr.palawi.playerdatarescue.mc.PlayerLoadingEvent;
import fr.palawi.playerdatarescue.mc.RescueService;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.EventHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(ModInfo.MOD_ID)
public final class PlayerDataRescueNeoForge {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModInfo.MOD_ID);

    public PlayerDataRescueNeoForge() {
        RescueService.init(new NeoForgeBridge());
        // As PrepareSpawnTask$Ready#spawn: with or without saved data.
        PlayerLoadingEvent.set((server, player, hasData) ->
                EventHooks.firePlayerLoadingEvent(player, server.getPlayerList(), player.getStringUUID()));
        LOGGER.info(ModInfo.loadedLine("neoforge", "26.3"));
    }
}
