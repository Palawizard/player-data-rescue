package fr.palawi.playerdatarescue.core.io;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Writes that never leave a half-written file: write {@code *.tmp}, flush to disk, then move. */
public final class AtomicFiles {
    private AtomicFiles() {}

    public static void write(Path file, byte[] content) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temp = parent.resolve(file.getFileName() + ".tmp");
        try (FileChannel channel = FileChannel.open(
                temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            channel.write(java.nio.ByteBuffer.wrap(content));
            channel.force(true);
        }
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Copies a file and checks that the copy has the same bytes, by SHA-256. */
    public static String copyVerified(Path source, Path target) throws IOException {
        byte[] content = Files.readAllBytes(source);
        write(target, content);
        String expected = sha256(content);
        String actual = sha256(Files.readAllBytes(target));
        if (!expected.equals(actual)) {
            throw new IOException("the copy of " + source + " at " + target + " does not match the original");
        }
        return expected;
    }

    public static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }
}
