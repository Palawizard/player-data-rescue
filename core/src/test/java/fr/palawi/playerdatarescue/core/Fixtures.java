package fr.palawi.playerdatarescue.core;

import fr.palawi.playerdatarescue.core.nbt.NbtCompound;
import fr.palawi.playerdatarescue.core.nbt.NbtIo;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;

/** Real files saved by the game and the test mod, described in fixtures/README.md. */
public final class Fixtures {
    private Fixtures() {}

    public static Path path(String name) {
        URL url = Fixtures.class.getResource("/fixtures/" + name);
        if (url == null) {
            throw new IllegalArgumentException("no fixture " + name);
        }
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    public static NbtCompound read(String name) throws IOException {
        return NbtIo.read(path(name));
    }
}
