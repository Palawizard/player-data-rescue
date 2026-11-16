package fr.palawi.playerdatarescue.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RescueConfigTest {
    @TempDir
    Path dir;

    @Test
    void missingFileIsCreatedWithTheDefaultsOfTheSpec() throws Exception {
        Path file = dir.resolve("playerdatarescue.json");
        RescueConfig.Loaded loaded = RescueConfig.load(file);

        assertTrue(loaded.warnings().isEmpty());
        RescueConfig config = loaded.config();
        assertTrue(config.enabled());
        assertTrue(config.notifyPlayer());
        assertTrue(config.notifyOperators());
        assertEquals(3, config.commandPermissionLevel());
        assertEquals(20, config.maxIncidentsPerPlayer());
        assertTrue(config.useDatOldFallback());
        assertTrue(config.rescuePlacementFailures());
        assertEquals(3, config.recurringThreshold());
        assertFalse(config.logFullStackTraces());
        JsonObject written = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertEquals(9, written.size());
        assertEquals(20, written.get("maxIncidentsPerPlayer").getAsInt());
    }

    @Test
    void unknownKeysAreKeptAndMissingKeysAdded() throws Exception {
        Path file = dir.resolve("playerdatarescue.json");
        Files.writeString(file, "{\"enabled\": false, \"futureOption\": \"kept\"}", StandardCharsets.UTF_8);

        RescueConfig config = RescueConfig.load(file).config();

        assertFalse(config.enabled());
        JsonObject written = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertEquals("kept", written.get("futureOption").getAsString());
        assertFalse(written.get("enabled").getAsBoolean());
        assertTrue(written.has("recurringThreshold"));
    }

    @Test
    void wrongTypesAndRangesFallBackToTheDefaultWithAWarning() throws Exception {
        Path file = dir.resolve("playerdatarescue.json");
        Files.writeString(
                file,
                "{\"enabled\": \"yes\", \"commandPermissionLevel\": 7, \"maxIncidentsPerPlayer\": 2.5}",
                StandardCharsets.UTF_8);

        RescueConfig.Loaded loaded = RescueConfig.load(file);

        assertTrue(loaded.config().enabled());
        assertEquals(3, loaded.config().commandPermissionLevel());
        assertEquals(20, loaded.config().maxIncidentsPerPlayer());
        assertEquals(3, loaded.warnings().size());
    }

    @Test
    void invalidJsonIsLeftAsItIs() throws Exception {
        Path file = dir.resolve("playerdatarescue.json");
        Files.writeString(file, "{\"enabled\": fals", StandardCharsets.UTF_8);

        RescueConfig.Loaded loaded = RescueConfig.load(file);

        assertEquals(RescueConfig.DEFAULTS, loaded.config());
        assertEquals(1, loaded.warnings().size());
        assertEquals("{\"enabled\": fals", Files.readString(file));
    }
}
