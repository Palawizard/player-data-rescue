package fr.palawi.playerdatarescue.testmod;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;

/**
 * Failure modes of the dev-only test mod, per player name, read from {@code pdrtest.properties}
 * in the server folder. The file is read again on every call, so the smoke harness can switch
 * modes between two joins without restarting the server.
 *
 * <p>Example line: {@code S2Bot=attach,attachment-throws}.
 */
public final class TestModes {
    public static final String LOG_PREFIX = "[pdrtest] ";

    /** After each save, rename the item in inventory slot 17 to an id from a mod that is not installed. */
    public static final String RENAME_ITEM = "rename-item";
    /** After each save, rename every active effect to an id from a mod that is not installed. */
    public static final String RENAME_EFFECT = "rename-effect";
    /** After each save, rename the root vehicle's entity type to one from a mod that is not installed. */
    public static final String RENAME_VEHICLE = "rename-vehicle";
    /** After each save, cut the player file in half. */
    public static final String TRUNCATE = "truncate";
    /** After each save, cut both the player file and its {@code _old} copy in half. */
    public static final String TRUNCATE_BOTH = "truncate-both";
    /** On login, give the player the test attachment so it is saved. */
    public static final String ATTACH = "attach";
    /** The test attachment's reader throws for this player. */
    public static final String ATTACHMENT_THROWS = "attachment-throws";
    /** The test attachment is saved in a form its own reader rejects. */
    public static final String UNREADABLE_ATTACHMENT = "unreadable-attachment";
    /** Placement throws for this player, whatever the saved data. */
    public static final String THROW_ON_PLACEMENT = "throw-on-placement";

    public static final String REMOVED_NAMESPACE = "pdrtest_removed";
    public static final String REMOVED_ITEM = REMOVED_NAMESPACE + ":brass_ingot";
    public static final String REMOVED_EFFECT = REMOVED_NAMESPACE + ":poison_cloud";
    public static final String REMOVED_VEHICLE = REMOVED_NAMESPACE + ":cart";
    public static final String ATTACHMENT_ID = "pdrtest:broken";
    public static final int INVENTORY_SLOT = 17;

    private TestModes() {}

    public static boolean has(String playerName, String mode) {
        Properties properties = new Properties();
        Path file = Path.of("pdrtest.properties");
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            throw new IllegalStateException("pdrtest: cannot read " + file.toAbsolutePath(), e);
        }
        String modes = properties.getProperty(playerName, "");
        return Arrays.stream(modes.split(",")).map(String::trim).anyMatch(mode::equals);
    }

    /** Thrown on purpose, so the failure is easy to find in a log. */
    public static RuntimeException failure(String what) {
        return new IllegalStateException("pdrtest: deliberate failure: " + what);
    }
}
