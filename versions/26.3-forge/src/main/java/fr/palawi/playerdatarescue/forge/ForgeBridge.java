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

/** Forge 66's view of the installed mods; its {@code ModList} is static. */
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
        for (IModFileInfo info : ModList.getModFiles()) {
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
        return ModList.isLoaded(modId);
    }

    private static String version(String modId) {
        return ModList.getModContainerById(modId)
                .map(mod -> mod.getModInfo().getVersion().toString())
                .orElse("unknown");
    }
}
