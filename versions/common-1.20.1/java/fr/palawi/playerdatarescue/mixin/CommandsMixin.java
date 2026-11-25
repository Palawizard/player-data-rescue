package fr.palawi.playerdatarescue.mixin;

import com.mojang.brigadier.CommandDispatcher;
import fr.palawi.playerdatarescue.mc.PdrCommands;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Registers {@code /pdrescue} wherever the game builds its command tree, on every loader: Fabric
 * has no command event without Fabric API, and Forge and NeoForge fire theirs from the same
 * constructor (ADR-PDR-013).
 */
@Mixin(Commands.class)
abstract class CommandsMixin {
    @Shadow
    @Final
    private CommandDispatcher<CommandSourceStack> dispatcher;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void playerdatarescue$register(
            Commands.CommandSelection selection, CommandBuildContext context, CallbackInfo ci) {
        PdrCommands.register(dispatcher);
    }
}
