package fr.palawi.playerdatarescue.forge;

import fr.palawi.playerdatarescue.core.ModInfo;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(ModInfo.MOD_ID)
public final class PlayerDataRescueForge {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModInfo.MOD_ID);

    public PlayerDataRescueForge() {
        LOGGER.info(ModInfo.loadedLine("forge", "1.21-1.21.4"));
    }
}
