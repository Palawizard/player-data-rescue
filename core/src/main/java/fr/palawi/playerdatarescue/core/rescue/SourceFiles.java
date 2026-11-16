package fr.palawi.playerdatarescue.core.rescue;

import fr.palawi.playerdatarescue.core.nbt.NbtCompound;
import fr.palawi.playerdatarescue.core.nbt.NbtFormatException;
import fr.palawi.playerdatarescue.core.nbt.NbtIo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Reading the source files of a player's data (SPEC.md section 1) without the game. */
public final class SourceFiles {
    private SourceFiles() {}

    /** The {@code _old} counterpart: {@code <uuid>.dat_old} or {@code level.dat_old}. */
    public static Path old(Path source) {
        return source.resolveSibling(source.getFileName() + "_old");
    }

    /**
     * T2 (SPEC.md section 2): the file exists and is not empty, but cannot be parsed. Returns the
     * parse error in that case, and empty when the file is missing, empty or readable.
     */
    public static Optional<NbtFormatException> unreadable(Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) == 0) {
            return Optional.empty();
        }
        try {
            NbtIo.read(file);
            return Optional.empty();
        } catch (NbtFormatException e) {
            return Optional.of(e);
        }
    }

    /** The parsed file, or empty when it is missing, empty or unreadable. */
    public static Optional<NbtCompound> readable(Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) == 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(NbtIo.read(file));
        } catch (NbtFormatException e) {
            return Optional.empty();
        }
    }

    /** The host player's data in a singleplayer {@code level.dat}: the {@code Data.Player} compound. */
    public static Optional<NbtCompound> hostPlayer(NbtCompound levelDat) {
        return levelDat.compound("Data").flatMap(data -> data.compound("Player"));
    }
}
