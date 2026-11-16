package fr.palawi.playerdatarescue.neoforge;

import fr.palawi.playerdatarescue.core.ModInfo;
import fr.palawi.playerdatarescue.mc.RescueService;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(ModInfo.MOD_ID)
public final class PlayerDataRescueNeoForge {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModInfo.MOD_ID);

    public PlayerDataRescueNeoForge() {
        RescueService.init(new NeoForgeBridge());
        LOGGER.info(ModInfo.loadedLine("neoforge", "26.3"));
    }
}
