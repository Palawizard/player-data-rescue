package fr.palawi.playerdatarescue.core.incident;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import fr.palawi.playerdatarescue.core.attribution.Suspect;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Renders {@code report.txt}, {@code report.json} and {@code quarantine.json} (SPEC.md section 6). */
public final class Reports {
    public static final int SCHEMA_VERSION = 1;

    private static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final DateTimeFormatter UTC = DateTimeFormatter.ISO_INSTANT;

    private Reports() {}

    public static String summary(Incident incident) {
        return switch (incident.outcome()) {
            case RESCUED ->
                incident.entries().isEmpty()
                        ? incident.playerName() + "'s saved data failed to load. The broken values were reset and "
                                + incident.playerName() + " joined with everything else."
                        : incident.playerName() + "'s saved data failed to load. "
                                + count(incident.entries().size()) + " set aside, and " + incident.playerName()
                                + " joined with everything else.";
            case MINIMAL_PROFILE ->
                incident.playerName()
                        + " could not join even with the broken parts set aside, so they joined with only their position,"
                        + " game mode, full health and food. Everything else was set aside.";
            case PREVIOUS_SAVE ->
                incident.playerName()
                        + "'s player file could not be read, so they joined with the previous save (.dat_old).";
            case UNREADABLE_FILE ->
                incident.playerName()
                        + "'s player file and its previous save could not be read (unreadable file), so they joined as"
                        + " a new player. Both files are kept in original/.";
            case RESTORE_BACKUP ->
                incident.entries().isEmpty()
                        ? "An operator restored set-aside data for " + incident.playerName()
                                + ". The saved data from before the restore is kept in original/."
                        : "An operator restored set-aside data for " + incident.playerName()
                                + ". The saved data from before the restore is kept in original/, and "
                                + (incident.entries().size() == 1
                                        ? "the value it replaced is"
                                        : incident.entries().size() + " values it replaced are")
                                + " set aside here.";
            case NOT_DATA_RELATED ->
                incident.originals().isEmpty()
                        ? incident.playerName()
                                + " could not join as a new player with no saved data, so the saved data is not the"
                                + " cause. Nothing was changed, and the player was disconnected as without this mod."
                        : incident.playerName()
                                + " could not join, and the saved data is not the cause: joining failed even with a"
                                + " minimal profile. Nothing was changed, and the player was disconnected as without"
                                + " this mod.";
        };
    }

    public static String text(Incident incident) {
        StringBuilder out = new StringBuilder();
        out.append(summary(incident)).append("\n\n");
        Incident.Versions v = incident.versions();
        out.append("Player: ")
                .append(incident.playerName())
                .append(" (")
                .append(incident.playerId())
                .append(")\n");
        out.append("Date: ").append(UTC.format(incident.time())).append('\n');
        out.append("Minecraft ")
                .append(v.minecraft())
                .append(", ")
                .append(v.loader())
                .append(' ')
                .append(v.loaderVersion())
                .append(", Player Data Rescue ")
                .append(v.mod())
                .append('\n');
        out.append("Trigger: ")
                .append(incident.trigger())
                .append(", ")
                .append(incident.trigger().description())
                .append('\n');
        out.append("Incident: ").append(incident.folder()).append("\n\n");

        if (incident.outcome() == Outcome.NOT_DATA_RELATED) {
            out.append(
                    incident.originals().isEmpty()
                            ? "The saved data is not the cause: the player had none.\n"
                            : "The saved data is not the cause. The original files are kept unchanged in original/.\n");
        } else if (incident.entries().isEmpty()) {
            out.append("Nothing was set aside.\n");
        } else {
            out.append("Set aside:\n");
            for (QuarantineEntry entry : incident.entries()) {
                out.append("- ").append(entry.description());
                if (!entry.suspects().isEmpty()) {
                    out.append(" (").append(entry.suspects().get(0).modId()).append(')');
                }
                if (entry.tooLarge()) {
                    out.append(", too large to keep apart: only in original/");
                }
                out.append('\n');
            }
            out.append("Kept: everything else.\n");
        }
        if (!incident.coreChanges().isEmpty()) {
            out.append("\nReset to safe values:\n");
            incident.coreChanges()
                    .forEach(change -> out.append("- ").append(change).append('\n'));
        }
        out.append('\n');
        if (incident.suspects().isEmpty()) {
            out.append("No mod stands out in the error.\n");
        } else {
            out.append("Most likely:\n");
            for (Suspect suspect : incident.suspects()) {
                out.append("- ")
                        .append(suspect.modId())
                        .append(": ")
                        .append(suspect.reason())
                        .append(" (")
                        .append(suspect.confidence().name().toLowerCase(Locale.ROOT))
                        .append(" confidence)\n");
            }
        }
        out.append("\nNext step: ").append(nextStep(incident)).append('\n');

        out.append("\nAppendix: errors\n");
        appendError(out, "Failure", incident.failure());
        for (QuarantineEntry entry : incident.entries()) {
            appendError(out, entry.id(), entry.error());
        }
        return out.toString();
    }

    static String nextStep(Incident incident) {
        String mod = incident.suspects().isEmpty()
                ? null
                : incident.suspects().get(0).modId();
        boolean notInstalled = !incident.suspects().isEmpty()
                && Suspect.NOT_INSTALLED.equals(incident.suspects().get(0).reason());
        String restore = "/pdrescue restore " + incident.playerName() + " " + incident.folder();
        return switch (incident.outcome()) {
            case NOT_DATA_RELATED ->
                mod == null
                        ? "check the server log around this time, and remove recently added or updated mods one by one."
                        : "report the error to " + mod + " with this file, or remove " + mod + " to let the player in.";
            case PREVIOUS_SAVE -> "nothing to do; the unreadable file is kept in original/.";
            case RESTORE_BACKUP ->
                incident.entries().isEmpty()
                        ? "nothing to do; this is the backup of the restore."
                        : "to undo the restore, run " + restore + " all.";
            case UNREADABLE_FILE -> "if the player needs their old data, ask for help with the files in original/.";
            default ->
                mod == null
                        ? "once the cause is fixed, run " + restore + "."
                        : notInstalled
                                ? "reinstall " + mod + ", then run " + restore + "."
                                : "update or report " + mod + ", then run " + restore + ".";
        };
    }

    private static void appendError(StringBuilder out, String label, ErrorInfo error) {
        if (error == null) {
            return;
        }
        out.append('\n').append(label).append(": ").append(error.headline()).append('\n');
        error.frames().forEach(frame -> out.append("    at ").append(frame).append('\n'));
        error.causes().forEach(cause -> out.append("Caused by: ").append(cause).append('\n'));
    }

    private static String count(int entries) {
        return entries == 1 ? "1 part was" : entries + " parts were";
    }

    public static String json(Incident incident) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.addProperty("summary", summary(incident));
        root.addProperty("playerId", incident.playerId().toString());
        root.addProperty("playerName", incident.playerName());
        root.addProperty("incident", incident.folder());
        root.addProperty("time", UTC.format(incident.time().atOffset(ZoneOffset.UTC)));
        JsonObject versions = new JsonObject();
        versions.addProperty("minecraft", incident.versions().minecraft());
        versions.addProperty("loader", incident.versions().loader());
        versions.addProperty("loaderVersion", incident.versions().loaderVersion());
        versions.addProperty("mod", incident.versions().mod());
        root.add("versions", versions);
        root.addProperty("trigger", incident.trigger().name());
        root.addProperty("outcome", incident.outcome().name().toLowerCase(Locale.ROOT));
        root.addProperty("dataIsTheCause", incident.outcome() != Outcome.NOT_DATA_RELATED);
        root.add("originals", strings(incident.originals()));
        root.add("coreChanges", strings(incident.coreChanges()));
        root.add("suspects", suspects(incident.suspects()));
        root.addProperty("nextStep", nextStep(incident));
        root.add("failure", error(incident.failure()));
        root.add("entries", entries(incident.entries(), true));
        return GSON.toJson(root) + "\n";
    }

    public static String quarantineJson(Incident incident) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.addProperty("playerId", incident.playerId().toString());
        root.addProperty("incident", incident.folder());
        root.add("entries", entries(incident.entries(), true));
        return GSON.toJson(root) + "\n";
    }

    private static JsonArray entries(List<QuarantineEntry> entries, boolean withSnbt) {
        JsonArray array = new JsonArray();
        for (QuarantineEntry entry : entries) {
            JsonObject json = new JsonObject();
            json.addProperty("id", entry.id());
            json.addProperty("section", entry.section().name().toLowerCase(Locale.ROOT));
            entry.slot().ifPresent(slot -> json.addProperty("slot", slot));
            json.addProperty("description", entry.description());
            json.addProperty("status", entry.status().json());
            json.addProperty("tooLarge", entry.tooLarge());
            entry.payloadFile().ifPresent(file -> json.addProperty("payload", "quarantine/" + file));
            if (withSnbt) {
                json.addProperty("snbt", entry.snbt());
            }
            json.add("error", error(entry.error()));
            json.add("suspects", suspects(entry.suspects()));
            array.add(json);
        }
        return array;
    }

    private static JsonArray suspects(List<Suspect> suspects) {
        JsonArray array = new JsonArray();
        for (Suspect suspect : suspects) {
            JsonObject json = new JsonObject();
            json.addProperty("modId", suspect.modId());
            json.addProperty("reason", suspect.reason());
            json.addProperty("confidence", suspect.confidence().name().toLowerCase(Locale.ROOT));
            array.add(json);
        }
        return array;
    }

    private static JsonObject error(ErrorInfo error) {
        JsonObject json = new JsonObject();
        if (error == null) {
            return json;
        }
        json.addProperty("class", error.exceptionClass());
        json.addProperty("message", error.message());
        json.add("frames", strings(error.frames()));
        json.add("causes", strings(error.causes()));
        return json;
    }

    private static JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }
}
