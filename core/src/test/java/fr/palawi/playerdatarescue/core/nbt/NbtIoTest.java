package fr.palawi.playerdatarescue.core.nbt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.palawi.playerdatarescue.core.Fixtures;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

class NbtIoTest {
    @Test
    void readsAPlayerFileSavedBy1201() throws Exception {
        NbtCompound root = Fixtures.read("1.20.1-fabric/S1Bot.dat");

        assertEquals(3465, root.number("DataVersion").orElseThrow().intValue());
        NbtCompound slot17 = (NbtCompound) root.list("Inventory").orElseThrow().elements().stream()
                .filter(e -> ((NbtCompound) e).number("Slot").orElseThrow().intValue() == 17)
                .findFirst()
                .orElseThrow();
        assertEquals("pdrtest_removed:brass_ingot", slot17.string("id").orElseThrow());
        assertEquals(12, slot17.number("Count").orElseThrow().intValue());
    }

    @Test
    void writingAndReadingAgainKeepsEveryTag() throws Exception {
        for (String fixture :
                new String[] {"1.20.1-forge/S2Bot.dat", "1.21.1-neoforge/S2Bot.dat", "26.3-fabric/S2Bot.dat"}) {
            NbtCompound root = Fixtures.read(fixture);

            assertEquals(root, NbtIo.read(NbtIo.write(root, true)), fixture);
            assertEquals(root, NbtIo.read(NbtIo.write(root, false)), fixture);
        }
    }

    @Test
    void uncompressedBytesAreTheSameAsTheGameWrote() throws Exception {
        byte[] file = Files.readAllBytes(Fixtures.path("1.21.1-neoforge/S2Bot.dat"));
        byte[] raw;
        try (var in = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(file))) {
            raw = in.readAllBytes();
        }

        assertArrayEquals(raw, NbtIo.write(NbtIo.read(file), false));
    }

    @Test
    void aTruncatedFileIsAFormatError() {
        NbtFormatException error =
                assertThrows(NbtFormatException.class, () -> NbtIo.read(Fixtures.path("1.20.1-fabric/S5Bot.dat")));

        assertTrue(
                error.getMessage().contains("gzip") || error.getMessage().contains("end of data"), error.getMessage());
    }

    @Test
    void impossibleLengthsFailWithoutAllocating() {
        byte[] data = {10, 0, 0, 7, 0, 1, 'a', 0x7f, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0};

        NbtFormatException error = assertThrows(NbtFormatException.class, () -> NbtIo.read(data));

        assertTrue(error.getMessage().contains("does not fit"), error.getMessage());
    }
}
