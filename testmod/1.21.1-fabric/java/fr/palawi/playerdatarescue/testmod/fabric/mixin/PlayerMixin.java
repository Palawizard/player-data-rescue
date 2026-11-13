package fr.palawi.playerdatarescue.testmod.fabric.mixin;

import fr.palawi.playerdatarescue.testmod.TestModes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fabric has no attachment system without Fabric API, so the test attachment is a top-level key
 * of the player data, the way component libraries store theirs. Reader rules match the Forge and
 * NeoForge test attachments.
 */
@Mixin(Player.class)
abstract class PlayerMixin {
    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void pdrtest$write(CompoundTag tag, CallbackInfo ci) {
        if (TestModes.has(pdrtest$name(), TestModes.UNREADABLE_ATTACHMENT)) {
            tag.putString(TestModes.ATTACHMENT_ID, "unreadable");
        } else {
            tag.putInt(TestModes.ATTACHMENT_ID, 42);
        }
    }

    @Inject(method = "readAdditionalSaveData", at = @At("HEAD"))
    private void pdrtest$read(CompoundTag tag, CallbackInfo ci) {
        if (!tag.contains(TestModes.ATTACHMENT_ID)) {
            return;
        }
        String name = pdrtest$name();
        if (TestModes.has(name, TestModes.ATTACHMENT_THROWS)) {
            throw TestModes.failure("reading attachment " + TestModes.ATTACHMENT_ID + " of " + name);
        }
        if (!tag.contains(TestModes.ATTACHMENT_ID, Tag.TAG_INT)
                && TestModes.has(name, TestModes.UNREADABLE_ATTACHMENT)) {
            throw TestModes.failure("attachment " + TestModes.ATTACHMENT_ID + " of " + name + " cannot read back "
                    + tag.get(TestModes.ATTACHMENT_ID));
        }
    }

    private String pdrtest$name() {
        return ((Player) (Object) this).getGameProfile().getName();
    }
}
