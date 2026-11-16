package fr.palawi.playerdatarescue.neoforge;

import fr.palawi.playerdatarescue.core.ModInfo;
import fr.palawi.playerdatarescue.core.attribution.ModResolver;
import fr.palawi.playerdatarescue.mc.LoaderBridge;
import fr.palawi.playerdatarescue.mc.MixinOwners;
import java.nio.file.Path;
import java.util.Optional;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.language.IModFileInfo;

/** NeoForge 26.3's view of the installed mods; mod files expose their contents through {@code JarContents}. */
final class NeoForgeBridge implements LoaderBridge, ModResolver {
    @Override
    public String loaderName() {
        return "neoforge";
    }

    @Override
    public String loaderVersion() {
        return version("neoforge");
    }

    @Override
    public String modVersion() {
        return version(ModInfo.MOD_ID);
    }

    @Override
    public Path configDirectory() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public ModResolver mods() {
        return this;
    }

    @Override
    public Optional<String> modOfClass(String className) {
        String file = className.replace('.', '/') + ".class";
        for (IModFileInfo info : ModList.get().getModFiles()) {
            if (!info.getMods().isEmpty() && info.getFile().getContents().containsFile(file)) {
                return Optional.of(info.getMods().get(0).getModId());
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
        return ModList.get().isLoaded(modId);
    }

    private static String version(String modId) {
        return ModList.get()
                .getModContainerById(modId)
                .map(mod -> mod.getModInfo().getVersion().toString())
                .orElse("unknown");
    }
}
