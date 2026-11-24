package fr.palawi.playerdatarescue.mc;

import fr.palawi.playerdatarescue.core.attribution.Suspect;
import fr.palawi.playerdatarescue.core.incident.Incident;
import fr.palawi.playerdatarescue.core.incident.Outcome;
import fr.palawi.playerdatarescue.core.recurring.RecurringIssues;
import java.util.List;
import java.util.stream.Collectors;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Messages to the rescued player and to operators. Vanilla text components only, with an English
 * fallback, so a client without this mod shows them as written (SPEC.md scenario S9).
 */
public final class Notices {
    private Notices() {}

    public static Component forPlayer(Outcome outcome) {
        MutableComponent message = switch (outcome) {
            case PREVIOUS_SAVE ->
                Component.translatableWithFallback(
                        "playerdatarescue.player.previous_save",
                        "Your latest save could not be read, so you joined with the save before it.");
            case UNREADABLE_FILE ->
                Component.translatableWithFallback(
                        "playerdatarescue.player.unreadable",
                        "Your saved data could not be read, so you joined as a new player. An admin has a copy of the old files.");
            default ->
                Component.translatableWithFallback(
                        "playerdatarescue.player.rescued",
                        "Some of your saved data could not be loaded and was set aside. An admin can restore it.");
        };
        return message.withStyle(ChatFormatting.YELLOW);
    }

    public static Component forOperators(Incident incident, String folder) {
        String suspects = suspects(incident.suspects());
        if (incident.outcome() == Outcome.NOT_DATA_RELATED) {
            return Component.translatableWithFallback(
                            "playerdatarescue.operator.diagnosis",
                            "[Player Data Rescue] %s could not join, and the saved data is not the cause. Most likely: %s. Report: %s",
                            incident.playerName(),
                            suspects,
                            folder)
                    .withStyle(ChatFormatting.GOLD)
                    .append(" ")
                    .append(Texts.action("show", "/pdrescue show " + incident.playerId() + " " + incident.folder()));
        }
        return Component.translatableWithFallback(
                        "playerdatarescue.operator.rescued",
                        "[Player Data Rescue] %s joined after a rescue: %s set aside. Most likely: %s. Report: %s",
                        incident.playerName(),
                        Integer.toString(incident.entries().size()),
                        suspects,
                        folder)
                .withStyle(ChatFormatting.GOLD)
                .append(" ")
                .append(Texts.action("show", "/pdrescue show " + incident.playerId() + " " + incident.folder()));
    }

    /** To operators, once per server start for each recurring issue (SPEC.md section 4.4). */
    public static Component recurring(RecurringIssues.Issue issue, String file) {
        String mod = issue.suspect().orElse("a mod");
        return Component.translatableWithFallback(
                        "playerdatarescue.operator.recurring",
                        "[Player Data Rescue] %s saves player data it cannot load back (%s). That data is set aside at"
                                + " every login until %s is fixed. Report it to %s with the file below. File: %s",
                        mod,
                        issue.key(),
                        mod,
                        mod,
                        file)
                .withStyle(ChatFormatting.GOLD)
                .append(" ")
                .append(Texts.action("dismiss", "/pdrescue dismiss recurring " + issue.key()));
    }

    /** To a player whose set-aside data came back at this join. */
    public static Component restored(int entries) {
        MutableComponent message = entries == 1
                ? Component.translatableWithFallback(
                        "playerdatarescue.player.restored_one", "One set-aside part of your saved data was restored.")
                : Component.translatableWithFallback(
                        "playerdatarescue.player.restored",
                        "%s set-aside parts of your saved data were restored.",
                        Integer.toString(entries));
        return message.withStyle(ChatFormatting.GREEN);
    }

    private static String suspects(List<Suspect> suspects) {
        return suspects.isEmpty()
                ? "no mod stands out"
                : suspects.stream().map(Suspect::modId).collect(Collectors.joining(", "));
    }
}
