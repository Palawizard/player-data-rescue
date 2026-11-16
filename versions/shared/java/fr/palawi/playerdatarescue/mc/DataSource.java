package fr.palawi.playerdatarescue.mc;

import fr.palawi.playerdatarescue.core.io.AtomicFiles;
import fr.palawi.playerdatarescue.core.nbt.NbtCompound;
import fr.palawi.playerdatarescue.core.nbt.NbtIo;
import fr.palawi.playerdatarescue.core.rescue.SourceFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Where the joining player's data comes from (SPEC.md section 1), and how an attempt hands its
 * data to the game: each attempt writes its candidate where the game reads, so the game's own read
 * path runs, with the loader events inside it (ADR-PDR-010). {@link #restore} puts the original back. A
 * candidate equal to the original is not written, so reading the original again changes nothing.
 */
public abstract class DataSource {
    public abstract Optional<NbtCompound> read() throws IOException;

    /** The files copied into {@code original/}. */
    public abstract List<Path> backupFiles();

    public abstract void write(NbtCompound candidate);

    public abstract void restore();

    /** {@code <uuid>.dat} in the player data folder: a dedicated server, a LAN guest, or the 26.3 host. */
    public static final class PlayerFile extends DataSource {
        private final Path file;
        private byte[] original;
        private NbtCompound parsed;
        private boolean written;

        public PlayerFile(Path file) {
            this.file = file;
        }

        @Override
        public Optional<NbtCompound> read() throws IOException {
            Optional<NbtCompound> tag = SourceFiles.readable(file);
            if (tag.isPresent()) {
                original = Files.readAllBytes(file);
                parsed = tag.get().copy();
            }
            return tag;
        }

        @Override
        public List<Path> backupFiles() {
            return List.of(file, SourceFiles.old(file));
        }

        @Override
        public void write(NbtCompound candidate) {
            // Loading the original data again needs no write: the file already holds it, and
            // nothing is written before the backup exists.
            if (!written && candidate.equals(parsed)) {
                return;
            }
            try {
                AtomicFiles.write(file, NbtIo.write(candidate, true));
                written = true;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void restore() {
            if (written && original != null) {
                try {
                    AtomicFiles.write(file, original);
                } catch (IOException e) {
                    throw new UncheckedIOException(
                            "could not put back " + file + "; the original is in the incident", e);
                }
            }
        }
    }
}
