package fr.palawi.playerdatarescue.testmod.fabric.mixin;

import fr.palawi.playerdatarescue.testmod.TestModes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
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
    private void pdrtest$write(ValueOutput output, CallbackInfo ci) {
        if (TestModes.has(pdrtest$name(), TestModes.UNREADABLE_ATTACHMENT)) {
            output.putString(TestModes.ATTACHMENT_ID, "unreadable");
        } else {
            output.putInt(TestModes.ATTACHMENT_ID, 42);
        }
    }

    @Inject(method = "readAdditionalSaveData", at = @At("HEAD"))
    private void pdrtest$read(ValueInput input, CallbackInfo ci) {
        boolean isInt = input.getInt(TestModes.ATTACHMENT_ID).isPresent();
        String text = input.getStringOr(TestModes.ATTACHMENT_ID, "");
        if (!isInt && text.isEmpty()) {
            return;
        }
        String name = pdrtest$name();
        if (TestModes.has(name, TestModes.ATTACHMENT_THROWS)) {
            throw TestModes.failure("reading attachment " + TestModes.ATTACHMENT_ID + " of " + name);
        }
        if (!isInt && TestModes.has(name, TestModes.UNREADABLE_ATTACHMENT)) {
            throw TestModes.failure(
                    "attachment " + TestModes.ATTACHMENT_ID + " of " + name + " cannot read back " + text);
        }
    }

    private String pdrtest$name() {
        return ((Player) (Object) this).getGameProfile().name();
    }
}
