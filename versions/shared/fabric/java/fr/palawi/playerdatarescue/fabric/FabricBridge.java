package fr.palawi.playerdatarescue.fabric;

import fr.palawi.playerdatarescue.core.ModInfo;
import fr.palawi.playerdatarescue.core.attribution.ModResolver;
import fr.palawi.playerdatarescue.mc.LoaderBridge;
import fr.palawi.playerdatarescue.mc.MixinOwners;
import java.nio.file.Path;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

/** Fabric Loader's view of the installed mods, without Fabric API (ADR-003). */
final class FabricBridge implements LoaderBridge, ModResolver {
    @Override
    public String loaderName() {
        return "fabric";
    }

    @Override
    public String loaderVersion() {
        return version("fabricloader");
    }

    @Override
    public String modVersion() {
        return version(ModInfo.MOD_ID);
    }

    @Override
    public Path configDirectory() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public ModResolver mods() {
        return this;
    }

    @Override
    public Optional<String> modOfClass(String className) {
        String file = className.replace('.', '/') + ".class";
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            if (mod.findPath(file).isPresent()) {
                return Optional.of(mod.getMetadata().getId());
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<String> mixinOwner(StackTraceElement frame) {
        return MixinOwners.mixinClass(frame).flatMap(this::modOfClass);
    }

    @Override
    public boolean isInstalled(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    private static String version(String modId) {
        return FabricLoader.getInstance()
                .getModContainer(modId)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }
}
