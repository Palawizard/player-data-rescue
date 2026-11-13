package fr.palawi.playerdatarescue.testmod.fabric;

import fr.palawi.playerdatarescue.testmod.TestModes;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PdrTestFabric implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("pdrtest");

    @Override
    public void onInitialize() {
        LOGGER.info("{}loaded on fabric 1.20.1", TestModes.LOG_PREFIX);
    }
}
