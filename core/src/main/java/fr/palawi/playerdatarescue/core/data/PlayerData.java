package fr.palawi.playerdatarescue.core.data;

import fr.palawi.playerdatarescue.core.nbt.NbtCompound;
import fr.palawi.playerdatarescue.core.nbt.NbtList;
import fr.palawi.playerdatarescue.core.nbt.NbtTag;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The saved data of one player, split into units (SPEC.md section 4.1). It works on a copy of the
 * root tag, so the original stays as read. Key names are the ones the game writes on the Tier A
 * versions (1.20.1, 1.21.1 and 26.3, listed in ARCHITECTURE.md), whatever the band, because a
 * file can be older than the server that reads it.
 */
public final class PlayerData {
    private static final List<String> EFFECT_KEYS = List.of("active_effects", "ActiveEffects");
    private static final String INVENTORY = "Inventory";
    private static final String ENDER_ITEMS = "EnderItems";
    private static final String EQUIPMENT = "equipment";
    private static final String ROOT_VEHICLE = "RootVehicle";
    private static final String RECIPE_BOOK = "recipeBook";
    /** The compounds that hold Forge capabilities and NeoForge attachments, by key. */
    public static final List<String> ATTACHMENT_CONTAINERS = List.of("ForgeCaps", "neoforge:attachments");

    private final NbtCompound root;
    private final Map<Unit, Container> containers = new LinkedHashMap<>();

    private PlayerData(NbtCompound root) {
        this.root = root;
        split();
    }

    /** Splits a copy of {@code original}; the original is not changed. */
    public static PlayerData of(NbtCompound original) {
        return new PlayerData(original.copy());
    }

    public NbtCompound root() {
        return root;
    }

    public List<Unit> units() {
        return List.copyOf(containers.keySet());
    }

    public Optional<Integer> dataVersion() {
        return root.number("DataVersion").map(Number::intValue);
    }

    /**
     * Whole sections of the data, for the minimal profile (SPEC.md section 4.3): each list or
     * container as one unit, and each unknown top-level key on its own.
     */
    public static List<Unit> wholeSections(NbtCompound original) {
        List<Unit> sections = new java.util.ArrayList<>();
        java.util.Map<String, Section> keys = new LinkedHashMap<>();
        keys.put(INVENTORY, Section.INVENTORY);
        keys.put(EQUIPMENT, Section.EQUIPMENT);
        keys.put(ENDER_ITEMS, Section.ENDER_CHEST);
        EFFECT_KEYS.forEach(key -> keys.put(key, Section.EFFECTS));
        ATTACHMENT_CONTAINERS.forEach(key -> keys.put(key, Section.ATTACHMENTS));
        keys.put(ROOT_VEHICLE, Section.VEHICLE);
        keys.put(RECIPE_BOOK, Section.RECIPE_BOOK);
        keys.forEach((key, section) -> {
            if (original.contains(key)) {
                sections.add(new Unit(
                        "section." + key,
                        section,
                        Optional.empty(),
                        Optional.empty(),
                        original.get(key).copy()));
            }
        });
        for (String key : original.keys()) {
            if (!VanillaKeys.isKnown(key)) {
                sections.add(new Unit(
                        "key." + key,
                        Section.UNKNOWN_KEY,
                        Optional.empty(),
                        keyNamespace(key),
                        original.get(key).copy()));
            }
        }
        return sections;
    }

    /** Removes the unit from this copy and returns it. */
    public Unit strip(Unit unit) {
        Container container = containers.remove(unit);
        if (container == null) {
            throw new IllegalArgumentException("unit " + unit.id() + " is not part of this data");
        }
        container.remove(unit.payload());
        return unit;
    }

    private void split() {
        Set<String> ids = new HashSet<>();
        slots(INVENTORY, Section.INVENTORY, "inv.", ids);
        slots(ENDER_ITEMS, Section.ENDER_CHEST, "ender.", ids);
        root.compound(EQUIPMENT).ifPresent(equipment -> {
            for (String slot : List.copyOf(equipment.keys())) {
                NbtTag stack = equipment.get(slot);
                add(
                        new Unit(
                                unique("equip." + slot, ids),
                                Section.EQUIPMENT,
                                Optional.of(slot),
                                itemNamespace(stack),
                                stack),
                        new Container(equipment, slot));
            }
        });
        for (String key : EFFECT_KEYS) {
            root.list(key).ifPresent(effects -> {
                for (NbtTag effect : effects.elements()) {
                    String name = effectName(effect);
                    add(
                            new Unit(
                                    unique("effect." + name, ids),
                                    Section.EFFECTS,
                                    Optional.empty(),
                                    namespace(name),
                                    effect),
                            new Container(effects));
                }
            });
        }
        for (String containerKey : ATTACHMENT_CONTAINERS) {
            root.compound(containerKey).ifPresent(attachments -> {
                for (String key : List.copyOf(attachments.keys())) {
                    add(
                            new Unit(
                                    unique("attachment." + key, ids),
                                    Section.ATTACHMENTS,
                                    Optional.empty(),
                                    keyNamespace(key),
                                    attachments.get(key)),
                            new Container(attachments, key));
                }
            });
        }
        if (root.contains(ROOT_VEHICLE)) {
            NbtTag vehicle = root.get(ROOT_VEHICLE);
            add(
                    new Unit("vehicle", Section.VEHICLE, Optional.empty(), vehicleNamespace(vehicle), vehicle),
                    new Container(root, ROOT_VEHICLE));
        }
        if (root.contains(RECIPE_BOOK)) {
            add(
                    new Unit("recipes", Section.RECIPE_BOOK, Optional.empty(), Optional.empty(), root.get(RECIPE_BOOK)),
                    new Container(root, RECIPE_BOOK));
        }
        for (String key : List.copyOf(root.keys())) {
            if (!VanillaKeys.isKnown(key)) {
                add(
                        new Unit(
                                unique("key." + key, ids),
                                Section.UNKNOWN_KEY,
                                Optional.empty(),
                                keyNamespace(key),
                                root.get(key)),
                        new Container(root, key));
            }
        }
    }

    private void slots(String key, Section section, String prefix, Set<String> ids) {
        root.list(key).ifPresent(list -> {
            for (NbtTag element : list.elements()) {
                Optional<String> slot = element instanceof NbtCompound stack
                        ? stack.number("Slot").map(n -> Integer.toString(n.byteValue() & 0xff))
                        : Optional.empty();
                String id = unique(prefix + slot.orElse("unknown"), ids);
                add(new Unit(id, section, slot, itemNamespace(element), element), new Container(list));
            }
        });
    }

    private void add(Unit unit, Container container) {
        containers.put(unit, container);
    }

    private static String unique(String id, Set<String> ids) {
        String candidate = id;
        for (int n = 2; !ids.add(candidate); n++) {
            candidate = id + "#" + n;
        }
        return candidate;
    }

    static String effectName(NbtTag effect) {
        if (effect instanceof NbtCompound compound) {
            Optional<String> name = compound.string("id").or(() -> compound.string("forge:id"));
            if (name.isPresent()) {
                return name.get();
            }
            Optional<Number> number = compound.number("Id");
            if (number.isPresent()) {
                return Integer.toString(number.get().intValue());
            }
        }
        return "unknown";
    }

    static Optional<String> itemNamespace(NbtTag stack) {
        return stack instanceof NbtCompound compound
                ? compound.string("id").flatMap(PlayerData::namespace)
                : Optional.empty();
    }

    private static Optional<String> vehicleNamespace(NbtTag vehicle) {
        return vehicle instanceof NbtCompound compound
                ? compound.compound("Entity")
                        .flatMap(entity -> entity.string("id"))
                        .flatMap(PlayerData::namespace)
                : Optional.empty();
    }

    /** The namespace of a {@code namespace:path} id; ids without a colon are in {@code minecraft}. */
    static Optional<String> namespace(String id) {
        int colon = id.indexOf(':');
        if (colon < 0) {
            return id.matches("[a-z0-9_.-]+") && !id.matches("[0-9]+") ? Optional.of("minecraft") : Optional.empty();
        }
        return colon == 0 ? Optional.empty() : Optional.of(id.substring(0, colon));
    }

    /** The namespace of a key written as {@code namespace:path}; plain keys have none. */
    static Optional<String> keyNamespace(String key) {
        int colon = key.indexOf(':');
        return colon > 0 ? Optional.of(key.substring(0, colon)) : Optional.empty();
    }

    /** Where a unit lives: a key of a compound, or an element of a list. */
    private static final class Container {
        private final NbtCompound compound;
        private final String key;
        private final NbtList list;

        Container(NbtCompound compound, String key) {
            this.compound = compound;
            this.key = key;
            this.list = null;
        }

        Container(NbtList list) {
            this.compound = null;
            this.key = null;
            this.list = list;
        }

        void remove(NbtTag payload) {
            if (list != null) {
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i) == payload) {
                        list.remove(i);
                        return;
                    }
                }
                throw new IllegalStateException("element already removed");
            }
            compound.remove(key);
        }
    }
}
