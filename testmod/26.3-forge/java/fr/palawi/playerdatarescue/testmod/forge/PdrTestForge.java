package fr.palawi.playerdatarescue.testmod.forge;

import fr.palawi.playerdatarescue.testmod.SaveMutator;
import fr.palawi.playerdatarescue.testmod.TestModes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod("pdrtest")
public final class PdrTestForge {
    private static final Logger LOGGER = LoggerFactory.getLogger("pdrtest");

    public PdrTestForge() {
        AttachCapabilitiesEvent.Entities.BUS.addListener(PdrTestForge::attach);
        PlayerEvent.SaveToFile.BUS.addListener(PdrTestForge::onSave);
        PlayerEvent.PlayerLoggedInEvent.BUS.addListener(PdrTestForge::onLogin);
        LOGGER.info("{}loaded on forge 26.3", TestModes.LOG_PREFIX);
    }

    private static void attach(AttachCapabilitiesEvent.Entities event) {
        if (event.getObject() instanceof Player player) {
            event.addCapability(Identifier.tryParse(TestModes.ATTACHMENT_ID), new BrokenAttachment(player));
        }
    }

    private static void onSave(PlayerEvent.SaveToFile event) {
        SaveMutator.afterSave(
                event.getEntity().getGameProfile().name(), event.getPlayerDirectory(), event.getPlayerUUID());
    }

    private static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        String name = event.getEntity().getGameProfile().name();
        if (TestModes.has(name, TestModes.THROW_ON_PLACEMENT)) {
            throw TestModes.failure("placement of " + name);
        }
    }
}
