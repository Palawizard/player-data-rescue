package fr.palawi.playerdatarescue.testmod.neoforge;

import fr.palawi.playerdatarescue.testmod.SaveMutator;
import fr.palawi.playerdatarescue.testmod.TestModes;
import java.util.function.Supplier;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod("pdrtest")
public final class PdrTestNeoForge {
    private static final Logger LOGGER = LoggerFactory.getLogger("pdrtest");
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, "pdrtest");
    private static final Supplier<AttachmentType<BrokenValue>> BROKEN = ATTACHMENTS.register(
            "broken",
            () -> AttachmentType.builder(holder -> new BrokenValue(nameOf(holder), 0))
                    .serialize(new BrokenSerializer())
                    .build());

    public PdrTestNeoForge(IEventBus modBus) {
        ATTACHMENTS.register(modBus);
        NeoForge.EVENT_BUS.addListener(PdrTestNeoForge::onSave);
        NeoForge.EVENT_BUS.addListener(PdrTestNeoForge::onLogin);
        LOGGER.info("{}loaded on neoforge 26.3", TestModes.LOG_PREFIX);
    }

    static String nameOf(IAttachmentHolder holder) {
        return holder instanceof Player player ? player.getGameProfile().name() : "";
    }

    private static void onSave(PlayerEvent.SaveToFile event) {
        SaveMutator.afterSave(nameOf(event.getEntity()), event.getPlayerDirectory(), event.getPlayerUUID());
    }

    private static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        Player player = event.getEntity();
        String name = nameOf(player);
        if (TestModes.has(name, TestModes.THROW_ON_PLACEMENT)) {
            throw TestModes.failure("placement of " + name);
        }
        if (TestModes.has(name, TestModes.ATTACH) || TestModes.has(name, TestModes.UNREADABLE_ATTACHMENT)) {
            player.setData(BROKEN, new BrokenValue(name, 42));
        }
    }
}
