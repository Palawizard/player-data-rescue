package fr.palawi.playerdatarescue.core.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import fr.palawi.playerdatarescue.core.io.AtomicFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code config/playerdatarescue.json} (SPEC.md section 8, ADR-005). Missing keys get their
 * default and are written back, unknown keys are kept, and a value of the wrong type or out of
 * range falls back to its default with a warning. A file that is not valid JSON is left as it is.
 */
public record RescueConfig(
        boolean enabled,
        boolean notifyPlayer,
        boolean notifyOperators,
        int commandPermissionLevel,
        int maxIncidentsPerPlayer,
        boolean useDatOldFallback,
        boolean rescuePlacementFailures,
        int recurringThreshold,
        boolean logFullStackTraces) {

    public static final String FILE_NAME = "playerdatarescue.json";
    public static final RescueConfig DEFAULTS = new RescueConfig(true, true, true, 3, 20, true, true, 3, false);

    private static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** The values in use and what was wrong with the file, if anything. */
    public record Loaded(RescueConfig config, List<String> warnings) {}

    public static Loaded load(Path file) {
        List<String> warnings = new ArrayList<>();
        JsonObject json = new JsonObject();
        boolean exists = Files.isRegularFile(file);
        if (exists) {
            try {
                JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
                if (!parsed.isJsonObject()) {
                    warnings.add(file + " is not a JSON object; using the defaults and leaving the file as it is");
                    return new Loaded(DEFAULTS, warnings);
                }
                json = parsed.getAsJsonObject();
            } catch (IOException | JsonParseException e) {
                warnings.add("cannot read " + file + " (" + e.getMessage()
                        + "); using the defaults and leaving the file as it is");
                return new Loaded(DEFAULTS, warnings);
            }
        }
        Reader reader = new Reader(json, warnings);
        RescueConfig config = new RescueConfig(
                reader.bool("enabled", DEFAULTS.enabled),
                reader.bool("notifyPlayer", DEFAULTS.notifyPlayer),
                reader.bool("notifyOperators", DEFAULTS.notifyOperators),
                reader.integer("commandPermissionLevel", DEFAULTS.commandPermissionLevel, 0, 4),
                reader.integer("maxIncidentsPerPlayer", DEFAULTS.maxIncidentsPerPlayer, 1, 10_000),
                reader.bool("useDatOldFallback", DEFAULTS.useDatOldFallback),
                reader.bool("rescuePlacementFailures", DEFAULTS.rescuePlacementFailures),
                reader.integer("recurringThreshold", DEFAULTS.recurringThreshold, 2, 1_000),
                reader.bool("logFullStackTraces", DEFAULTS.logFullStackTraces));
        if (!exists || reader.added) {
            try {
                AtomicFiles.write(file, (GSON.toJson(json) + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                warnings.add("cannot write " + file + ": " + e.getMessage());
            }
        }
        return new Loaded(config, warnings);
    }

    private static final class Reader {
        private final JsonObject json;
        private final List<String> warnings;
        private boolean added;

        Reader(JsonObject json, List<String> warnings) {
            this.json = json;
            this.warnings = warnings;
        }

        boolean bool(String key, boolean fallback) {
            JsonElement value = json.get(key);
            if (value == null) {
                json.addProperty(key, fallback);
                added = true;
                return fallback;
            }
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
                return value.getAsBoolean();
            }
            warnings.add(key + " must be true or false, found " + value + "; using " + fallback);
            return fallback;
        }

        int integer(String key, int fallback, int min, int max) {
            JsonElement value = json.get(key);
            if (value == null) {
                json.addProperty(key, fallback);
                added = true;
                return fallback;
            }
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                JsonPrimitive primitive = value.getAsJsonPrimitive();
                double number = primitive.getAsDouble();
                if (number == Math.rint(number) && number >= min && number <= max) {
                    return (int) number;
                }
            }
            warnings.add(key + " must be a whole number from " + min + " to " + max + ", found " + value + "; using "
                    + fallback);
            return fallback;
        }
    }
}
