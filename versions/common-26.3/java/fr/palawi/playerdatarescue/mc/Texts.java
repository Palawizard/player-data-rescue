package fr.palawi.playerdatarescue.mc;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.permissions.PermissionCheck;

/** The parts of the commands' text and permission checks that differ between game versions. */
final class Texts {
    private Texts() {}

    /** 26.3 checks permissions through permission sets; the levels map to the game's own checks. */
    static boolean allowed(CommandSourceStack source, int level) {
        PermissionCheck check = switch (Math.max(0, Math.min(4, level))) {
            case 0 -> Commands.LEVEL_ALL;
            case 1 -> Commands.LEVEL_MODERATORS;
            case 2 -> Commands.LEVEL_GAMEMASTERS;
            case 3 -> Commands.LEVEL_ADMINS;
            default -> Commands.LEVEL_OWNERS;
        };
        return check.check(source.permissions());
    }

    /** A clickable {@code [label]} that runs {@code command}. Vanilla text, so any client shows it. */
    static MutableComponent action(String label, String command) {
        return Component.literal("[" + label + "]")
                .withStyle(style ->
                        style.withColor(ChatFormatting.AQUA).withClickEvent(new ClickEvent.RunCommand(command)));
    }
}
