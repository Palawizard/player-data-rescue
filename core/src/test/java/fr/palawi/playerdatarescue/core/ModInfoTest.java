package fr.palawi.playerdatarescue.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ModInfoTest {
    @Test
    void loadedLineNamesTheModLoaderAndBand() {
        assertEquals(
                "[playerdatarescue] loaded on neoforge, band 1.21-1.21.4",
                ModInfo.loadedLine("neoforge", "1.21-1.21.4"));
    }

    @Test
    void loadedLineRejectsBlankValues() {
        assertThrows(IllegalArgumentException.class, () -> ModInfo.loadedLine(" ", "26.3"));
        assertThrows(IllegalArgumentException.class, () -> ModInfo.loadedLine("fabric", null));
    }
}
