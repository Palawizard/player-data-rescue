package fr.palawi.playerdatarescue.testmod.forge;

import fr.palawi.playerdatarescue.testmod.TestModes;
import net.minecraft.core.Direction;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A player capability saved under {@code ForgeCaps}. Its reader throws for players in
 * {@code attachment-throws}, and for players in {@code unreadable-attachment} it writes a value
 * that its own reader rejects. Without those modes it reads both forms, like a fixed release.
 */
final class BrokenAttachment implements ICapabilitySerializable<Tag> {
    private static final Logger LOGGER = LoggerFactory.getLogger("pdrtest");

    private final Player player;
    private int value = 42;

    BrokenAttachment(Player player) {
        this.player = player;
    }

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) {
        return LazyOptional.empty();
    }

    @Override
    public Tag serializeNBT() {
        boolean unreadable = unreadable();
        LOGGER.info(
                "{}saved attachment {} of {} as {}",
                TestModes.LOG_PREFIX,
                TestModes.ATTACHMENT_ID,
                player.getGameProfile().getName(),
                unreadable ? "unreadable" : "int");
        return unreadable ? StringTag.valueOf("unreadable") : IntTag.valueOf(value);
    }

    @Override
    public void deserializeNBT(Tag tag) {
        String name = player.getGameProfile().getName();
        if (TestModes.has(name, TestModes.ATTACHMENT_THROWS)) {
            throw TestModes.failure("reading attachment " + TestModes.ATTACHMENT_ID + " of " + name);
        }
        if (tag instanceof IntTag intTag) {
            value = intTag.getAsInt();
        } else if (unreadable()) {
            throw TestModes.failure(
                    "attachment " + TestModes.ATTACHMENT_ID + " of " + name + " cannot read back " + tag);
        }
    }

    private boolean unreadable() {
        return TestModes.has(player.getGameProfile().getName(), TestModes.UNREADABLE_ATTACHMENT);
    }
}
