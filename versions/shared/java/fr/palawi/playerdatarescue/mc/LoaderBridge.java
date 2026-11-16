package fr.palawi.playerdatarescue.mc;

import fr.palawi.playerdatarescue.core.attribution.ModResolver;
import java.nio.file.Path;

/** What the Minecraft-version code needs from the loader of the band. */
public interface LoaderBridge {
    String loaderName();

    String loaderVersion();

    String modVersion();

    Path configDirectory();

    ModResolver mods();
}
