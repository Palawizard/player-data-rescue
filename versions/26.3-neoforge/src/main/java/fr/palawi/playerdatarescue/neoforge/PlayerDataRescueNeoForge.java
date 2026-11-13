package fr.palawi.playerdatarescue.neoforge;

import fr.palawi.playerdatarescue.core.ModInfo;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(ModInfo.MOD_ID)
public final class PlayerDataRescueNeoForge {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModInfo.MOD_ID);

    public PlayerDataRescueNeoForge() {
        LOGGER.info(ModInfo.loadedLine("neoforge", "26.3"));
    }
}
