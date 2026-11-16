package fr.palawi.playerdatarescue.mc;

import fr.palawi.playerdatarescue.core.nbt.NbtCompound;
import fr.palawi.playerdatarescue.core.rescue.SourceFiles;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

/**
 * The singleplayer host on 1.20.1: {@code Data.Player} of {@code level.dat}, which the game keeps
 * in memory after loading the world and reads from there at login ({@code PlayerList#load}). Each
 * attempt replaces the content of that in-memory tag; the next world save writes the result.
 */
final class HostData extends DataSource {
    private final Path levelDat;
    private final CompoundTag tag;
    private final CompoundTag original;

    HostData(Path levelDat, CompoundTag tag) {
        this.levelDat = levelDat;
        this.tag = tag;
        this.original = tag.copy();
    }

    @Override
    public Optional<NbtCompound> read() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            NbtIo.write(original, out);
        }
        return Optional.of(fr.palawi.playerdatarescue.core.nbt.NbtIo.read(bytes.toByteArray()));
    }

    @Override
    public List<Path> backupFiles() {
        return List.of(levelDat, SourceFiles.old(levelDat));
    }

    @Override
    public void write(NbtCompound candidate) {
        byte[] bytes = fr.palawi.playerdatarescue.core.nbt.NbtIo.write(candidate, false);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            replace(NbtIo.read(in));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void restore() {
        replace(original.copy());
    }

    private void replace(CompoundTag content) {
        for (String key : List.copyOf(tag.getAllKeys())) {
            tag.remove(key);
        }
        for (String key : content.getAllKeys()) {
            tag.put(key, content.get(key));
        }
    }
}
