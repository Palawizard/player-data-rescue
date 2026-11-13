package fr.palawi.playerdatarescue.testmod.forge;

import fr.palawi.playerdatarescue.testmod.SaveMutator;
import fr.palawi.playerdatarescue.testmod.TestModes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod("pdrtest")
public final class PdrTestForge {
    private static final Logger LOGGER = LoggerFactory.getLogger("pdrtest");

    public PdrTestForge() {
        MinecraftForge.EVENT_BUS.addGenericListener(Entity.class, PdrTestForge::attach);
        MinecraftForge.EVENT_BUS.addListener(PdrTestForge::onSave);
        MinecraftForge.EVENT_BUS.addListener(PdrTestForge::onLogin);
        LOGGER.info("{}loaded on forge 1.20.1", TestModes.LOG_PREFIX);
    }

    private static void attach(AttachCapabilitiesEvent<Entity> event) {
        if (event.getObject() instanceof Player player) {
            event.addCapability(ResourceLocation.tryParse(TestModes.ATTACHMENT_ID), new BrokenAttachment(player));
        }
    }

    private static void onSave(PlayerEvent.SaveToFile event) {
        SaveMutator.afterSave(
                event.getEntity().getGameProfile().getName(), event.getPlayerDirectory(), event.getPlayerUUID());
    }

    private static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        String name = event.getEntity().getGameProfile().getName();
        if (TestModes.has(name, TestModes.THROW_ON_PLACEMENT)) {
            throw TestModes.failure("placement of " + name);
        }
    }
}
