package fr.palawi.playerdatarescue.core.nbt;

import java.io.IOException;

/** The bytes are not a readable NBT file: truncated, damaged gzip, or an impossible length. */
public final class NbtFormatException extends IOException {
    public NbtFormatException(String message) {
        super(message);
    }

    public NbtFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
