package fr.palawi.playerdatarescue.core.data;

import fr.palawi.playerdatarescue.core.nbt.NbtCompound;
import fr.palawi.playerdatarescue.core.nbt.NbtList;
import fr.palawi.playerdatarescue.core.nbt.NbtTag;
import fr.palawi.playerdatarescue.core.nbt.NbtValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The core section (SPEC.md section 4.1): invalid values are set to safe defaults, never set
 * aside. Only values the game itself rejects or cannot use are touched: a position or rotation
 * that is not finite makes {@code Entity#load} throw, and a health, food or motion value that is
 * not finite breaks the player once loaded.
 */
public final class CoreValues {
    private CoreValues() {}

    /**
     * The world spawn, given by the band: where a player goes when their position is not valid or
     * their dimension is not on this server. {@code dimensions} lists the server's dimensions;
     * when empty, the dimension is not checked.
     */
    public record Spawn(double x, double y, double z, String dimension, Set<String> dimensions) {
        public Spawn {
            dimensions = Set.copyOf(dimensions);
        }

        public Spawn(double x, double y, double z, String dimension) {
            this(x, y, z, dimension, Set.of());
        }
    }

    /** Fixes the values in place and returns one line per change, for the report. */
    public static List<String> clamp(NbtCompound root, Spawn spawn) {
        List<String> changes = new ArrayList<>();
        if (!finiteDoubles(root.get("Pos"), 3)) {
            changes.add("Position " + describe(root.get("Pos")) + " was not valid; set to the world spawn");
            root.put("Pos", doubles(spawn.x(), spawn.y(), spawn.z()));
            root.put("Dimension", NbtValue.ofString(spawn.dimension()));
        }
        Optional<String> dimension = root.string("Dimension");
        if (dimension.isPresent()
                && !spawn.dimensions().isEmpty()
                && !spawn.dimensions().contains(dimension.get())) {
            changes.add("Dimension " + dimension.get() + " is not on this server; set to the world spawn");
            root.put("Pos", doubles(spawn.x(), spawn.y(), spawn.z()));
            root.put("Dimension", NbtValue.ofString(spawn.dimension()));
        }
        if (root.contains("Rotation") && !finiteFloats(root.get("Rotation"), 2)) {
            changes.add("Rotation " + describe(root.get("Rotation")) + " was not valid; set to 0 0");
            root.put("Rotation", floats(0f, 0f));
        }
        if (root.contains("Motion") && !finiteDoubles(root.get("Motion"), 3)) {
            changes.add("Motion " + describe(root.get("Motion")) + " was not valid; set to 0 0 0");
            root.put("Motion", doubles(0, 0, 0));
        }
        clampNumber(root, "Health", 0, Float.MAX_VALUE, 20f, changes);
        clampNumber(root, "foodLevel", 0, 20, 20, changes);
        clampNumber(root, "foodSaturationLevel", 0, 20, 5f, changes);
        clampNumber(root, "XpP", 0, 1, 0f, changes);
        return changes;
    }

    /**
     * The minimal profile (SPEC.md section 4.3): the data version, UUID, position, rotation,
     * dimension and game modes of the original, with full health and food.
     */
    public static NbtCompound minimalProfile(NbtCompound original, Spawn spawn) {
        NbtCompound minimal = new NbtCompound();
        for (String key : List.of("DataVersion", "UUID", "Pos", "Rotation", "Dimension", "playerGameType")) {
            if (original.contains(key)) {
                minimal.put(key, original.get(key).copy());
            }
        }
        minimal.put("Health", NbtValue.ofFloat(20f));
        minimal.put("foodLevel", NbtValue.ofInt(20));
        clamp(minimal, spawn);
        return minimal;
    }

    private static void clampNumber(
            NbtCompound root, String key, double min, double max, float fallback, List<String> changes) {
        Optional<Number> number = root.number(key);
        if (number.isEmpty()) {
            return;
        }
        double value = number.get().doubleValue();
        if (Double.isFinite(value) && value >= min && value <= max) {
            return;
        }
        double fixed = Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
        NbtValue replacement =
                root.get(key).type() == NbtTag.INT ? NbtValue.ofInt((int) fixed) : NbtValue.ofFloat((float) fixed);
        root.put(key, replacement);
        changes.add(key + " " + value + " was not valid; set to " + replacement);
    }

    private static boolean finiteDoubles(NbtTag tag, int size) {
        if (!(tag instanceof NbtList list) || list.size() != size || list.elementType() != NbtTag.DOUBLE) {
            return false;
        }
        return list.elements().stream()
                .allMatch(e -> Double.isFinite(((NbtValue) e).number().doubleValue()));
    }

    private static boolean finiteFloats(NbtTag tag, int size) {
        if (!(tag instanceof NbtList list) || list.size() != size || list.elementType() != NbtTag.FLOAT) {
            return false;
        }
        return list.elements().stream()
                .allMatch(e -> Float.isFinite(((NbtValue) e).number().floatValue()));
    }

    private static String describe(NbtTag tag) {
        return tag == null ? "(missing)" : tag.toString();
    }

    private static NbtList doubles(double... values) {
        NbtList list = new NbtList(NbtTag.DOUBLE);
        for (double v : values) {
            list.add(NbtValue.ofDouble(v));
        }
        return list;
    }

    private static NbtList floats(float... values) {
        NbtList list = new NbtList(NbtTag.FLOAT);
        for (float v : values) {
            list.add(NbtValue.ofFloat(v));
        }
        return list;
    }
}
