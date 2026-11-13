package fr.palawi.playerdatarescue.testmod.neoforge;

import fr.palawi.playerdatarescue.testmod.TestModes;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Saved under {@code neoforge:attachments}. The reader throws for players in
 * {@code attachment-throws}, and for players in {@code unreadable-attachment} the writer saves a
 * value that the reader rejects. Without those modes it reads both forms, like a fixed release.
 */
final class BrokenSerializer implements IAttachmentSerializer<Tag, BrokenValue> {
    private static final Logger LOGGER = LoggerFactory.getLogger("pdrtest");

    @Override
    public BrokenValue read(IAttachmentHolder holder, Tag tag, HolderLookup.Provider provider) {
        String name = PdrTestNeoForge.nameOf(holder);
        if (TestModes.has(name, TestModes.ATTACHMENT_THROWS)) {
            throw TestModes.failure("reading attachment " + TestModes.ATTACHMENT_ID + " of " + name);
        }
        if (tag instanceof IntTag intTag) {
            return new BrokenValue(name, intTag.getAsInt());
        }
        if (TestModes.has(name, TestModes.UNREADABLE_ATTACHMENT)) {
            throw TestModes.failure(
                    "attachment " + TestModes.ATTACHMENT_ID + " of " + name + " cannot read back " + tag);
        }
        return new BrokenValue(name, 0);
    }

    @Override
    public Tag write(BrokenValue value, HolderLookup.Provider provider) {
        boolean unreadable = TestModes.has(value.owner(), TestModes.UNREADABLE_ATTACHMENT);
        LOGGER.info(
                "{}saved attachment {} of {} as {}",
                TestModes.LOG_PREFIX,
                TestModes.ATTACHMENT_ID,
                value.owner(),
                unreadable ? "unreadable" : "int");
        return unreadable ? StringTag.valueOf("unreadable") : IntTag.valueOf(value.value());
    }
}
