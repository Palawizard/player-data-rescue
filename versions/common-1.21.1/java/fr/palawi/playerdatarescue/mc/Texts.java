package fr.palawi.playerdatarescue.mc;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** The parts of the commands' text and permission checks that differ between game versions. */
final class Texts {
    private Texts() {}

    static boolean allowed(CommandSourceStack source, int level) {
        return source.hasPermission(level);
    }

    /** A clickable {@code [label]} that runs {@code command}. Vanilla text, so any client shows it. */
    static MutableComponent action(String label, String command) {
        return Component.literal("[" + label + "]")
                .withStyle(style -> style.withColor(ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command)));
    }
}
