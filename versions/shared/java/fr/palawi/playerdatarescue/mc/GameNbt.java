package fr.palawi.playerdatarescue.mc;

import fr.palawi.playerdatarescue.core.nbt.NbtCompound;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

/** The core's NBT as the game's own tag, through the binary format both read and write. */
final class GameNbt {
    private GameNbt() {}

    static CompoundTag toGame(NbtCompound compound) {
        byte[] bytes = fr.palawi.playerdatarescue.core.nbt.NbtIo.write(compound, false);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            return NbtIo.read(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
