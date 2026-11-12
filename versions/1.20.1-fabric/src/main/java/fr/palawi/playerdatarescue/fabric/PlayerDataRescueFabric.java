package fr.palawi.playerdatarescue.fabric;

import fr.palawi.playerdatarescue.core.ModInfo;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PlayerDataRescueFabric implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModInfo.MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info(ModInfo.loadedLine("fabric", "1.20-1.20.1"));
    }
}
