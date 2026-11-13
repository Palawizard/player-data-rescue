package fr.palawi.playerdatarescue.testmod.neoforge;

import fr.palawi.playerdatarescue.testmod.TestModes;
import java.util.Optional;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;

/**
 * Saved under {@code neoforge:attachments}. The reader throws for players in
 * {@code attachment-throws}, and for players in {@code unreadable-attachment} the writer saves a
 * value that the reader rejects. Without those modes it reads both forms, like a fixed release.
 */
final class BrokenSerializer implements IAttachmentSerializer<BrokenValue> {
    @Override
    public BrokenValue read(IAttachmentHolder holder, ValueInput input) {
        String name = PdrTestNeoForge.nameOf(holder);
        if (TestModes.has(name, TestModes.ATTACHMENT_THROWS)) {
            throw TestModes.failure("reading attachment " + TestModes.ATTACHMENT_ID + " of " + name);
        }
        Optional<Integer> value = input.getInt("value");
        if (value.isPresent()) {
            return new BrokenValue(name, value.get());
        }
        if (TestModes.has(name, TestModes.UNREADABLE_ATTACHMENT)) {
            throw TestModes.failure("attachment " + TestModes.ATTACHMENT_ID + " of " + name + " cannot read back "
                    + input.getStringOr("value", ""));
        }
        return new BrokenValue(name, 0);
    }

    @Override
    public boolean write(BrokenValue value, ValueOutput output) {
        if (TestModes.has(value.owner(), TestModes.UNREADABLE_ATTACHMENT)) {
            output.putString("value", "unreadable");
        } else {
            output.putInt("value", value.value());
        }
        return true;
    }
}
