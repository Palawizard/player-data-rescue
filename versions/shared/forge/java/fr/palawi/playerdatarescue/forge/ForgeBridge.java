package fr.palawi.playerdatarescue.forge;

import fr.palawi.playerdatarescue.core.ModInfo;
import fr.palawi.playerdatarescue.core.attribution.ModResolver;
import fr.palawi.playerdatarescue.mc.LoaderBridge;
import fr.palawi.playerdatarescue.mc.MixinOwners;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.forgespi.language.IModFileInfo;

/** Forge's view of the installed mods, for Forge 47 (1.20.1) and 52 (1.21.1). */
final class ForgeBridge implements LoaderBridge, ModResolver {
    @Override
    public String loaderName() {
        return "forge";
    }

    @Override
    public String loaderVersion() {
        return version("forge");
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
            if (!info.getMods().isEmpty() && Files.exists(info.getFile().findResource(file))) {
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
