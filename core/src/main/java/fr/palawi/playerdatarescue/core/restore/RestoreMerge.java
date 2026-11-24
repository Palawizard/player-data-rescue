package fr.palawi.playerdatarescue.core.restore;

import fr.palawi.playerdatarescue.core.data.Section;
import fr.palawi.playerdatarescue.core.nbt.NbtCompound;
import fr.palawi.playerdatarescue.core.nbt.NbtList;
import fr.palawi.playerdatarescue.core.nbt.NbtTag;
import fr.palawi.playerdatarescue.core.nbt.NbtValue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Puts quarantine entries back into a player's saved data (SPEC.md section 7.1), before the game
 * reads that data at the next join. The game's own read then decodes every restored unit, as it
 * decodes the rest of the data (ADR-PDR-001, ADR-PDR-013).
 *
 * <ul>
 *   <li>Inventory and ender chest stacks go back into their slot when it is free, otherwise into
 *       the first free slot of the same container, otherwise they are returned to be dropped at
 *       the player's feet once the player is in the world.
 *   <li>An attachment, a mod key, an effect or the recipe book replaces the value the player has
 *       now under the same key, and that value is returned in {@link Result#replaced()} so it can
 *       be kept as a quarantine entry of its own. Nothing is merged.
 *   <li>The vehicle is never restored, and an entry saved by another game version is refused.
 * </ul>
 */
public final class RestoreMerge {
    /** Main inventory slots; armour and offhand slots are only used when they are the stack's own slot. */
    static final int INVENTORY_SLOTS = 36;

    static final int ENDER_SLOTS = 27;
    private static final String INVENTORY = "Inventory";
    private static final String ENDER_ITEMS = "EnderItems";
    private static final String EQUIPMENT = "equipment";
    private static final String RECIPE_BOOK = "recipeBook";
    private static final String ROOT_VEHICLE = "RootVehicle";
    private static final Set<String> EFFECT_KEYS = Set.of("active_effects", "ActiveEffects");
    private static final Set<String> ATTACHMENT_CONTAINERS = Set.of("ForgeCaps", "neoforge:attachments");

    /**
     * One entry to put back: its id and the content of its payload file ({@code data} and the
     * {@code DataVersion} it was saved with).
     */
    public record Piece(String entryId, NbtTag data, Optional<Integer> dataVersion) {}

    /** A value the player had, replaced by a restored entry. */
    public record Replaced(String id, Section section, Optional<String> slot, NbtTag payload) {}

    /**
     * @param data the merged data, to be written where the game reads it
     * @param applied the ids of the entries put back
     * @param refused the ids of the entries that cannot be put back, with the reason
     * @param replaced what the player had under the keys the restored entries took
     * @param toDrop stacks with no free slot, to drop at the player's feet after the join
     */
    public record Result(
            NbtCompound data,
            List<String> applied,
            Map<String, String> refused,
            List<Replaced> replaced,
            List<NbtCompound> toDrop) {}

    private final NbtCompound data;
    private final String attachmentContainer;
    private final List<String> applied = new ArrayList<>();
    private final Map<String, String> refused = new LinkedHashMap<>();
    private final List<Replaced> replaced = new ArrayList<>();
    private final List<NbtCompound> toDrop = new ArrayList<>();

    private RestoreMerge(NbtCompound data, String attachmentContainer) {
        this.data = data;
        this.attachmentContainer = attachmentContainer;
    }

    /**
     * Merges {@code pieces} into a copy of {@code current}.
     *
     * @param attachmentContainer where this loader keeps attachments: {@code ForgeCaps} on Forge,
     *     {@code neoforge:attachments} on NeoForge; Fabric has none, so any value
     */
    public static Result apply(NbtCompound current, List<Piece> pieces, String attachmentContainer) {
        RestoreMerge merge = new RestoreMerge(current.copy(), attachmentContainer);
        Optional<Integer> version = current.number("DataVersion").map(Number::intValue);
        for (Piece piece : pieces) {
            if (piece.dataVersion().isPresent()
                    && version.isPresent()
                    && !piece.dataVersion().equals(version)) {
                merge.refused.put(
                        piece.entryId(),
                        "it was saved by another game version (data version "
                                + piece.dataVersion().get() + ", the player's data is " + version.get() + ")");
                continue;
            }
            Optional<String> reason = merge.put(piece.entryId(), piece.data().copy());
            if (reason.isPresent()) {
                merge.refused.put(piece.entryId(), reason.get());
            } else {
                merge.applied.add(piece.entryId());
            }
        }
        return new Result(
                merge.data,
                List.copyOf(merge.applied),
                Map.copyOf(merge.refused),
                List.copyOf(merge.replaced),
                List.copyOf(merge.toDrop));
    }

    /** Puts one entry back; returns the reason when it cannot be. */
    private Optional<String> put(String id, NbtTag payload) {
        if (id.equals("vehicle") || id.equals("section." + ROOT_VEHICLE)) {
            return Optional.of("vehicles cannot be restored yet; the payload is kept for manual recovery");
        }
        if (id.startsWith("inv.") || id.startsWith("ender.")) {
            return stack(id.startsWith("inv.") ? INVENTORY : ENDER_ITEMS, payload);
        }
        if (id.startsWith("equip.")) {
            return equipment(id.substring("equip.".length()), payload);
        }
        if (id.startsWith("effect.")) {
            return effect(payload);
        }
        if (id.startsWith("attachment.")) {
            return replaceIn(attachmentContainer, id.substring("attachment.".length()), payload, Section.ATTACHMENTS);
        }
        if (id.equals("recipes")) {
            return replaceIn(null, RECIPE_BOOK, payload, Section.RECIPE_BOOK);
        }
        if (id.startsWith("key.")) {
            return replaceIn(null, id.substring("key.".length()), payload, Section.UNKNOWN_KEY);
        }
        if (id.startsWith("section.")) {
            return section(id.substring("section.".length()), payload);
        }
        return Optional.of("this kind of entry cannot be restored");
    }

    /** A section set aside whole by the minimal profile. */
    private Optional<String> section(String key, NbtTag payload) {
        if (key.equals(INVENTORY) || key.equals(ENDER_ITEMS)) {
            if (!(payload instanceof NbtList stacks)) {
                return Optional.of("the saved section is not a list");
            }
            for (NbtTag stack : stacks.elements()) {
                stack(key, stack.copy());
            }
            return Optional.empty();
        }
        if (key.equals(EQUIPMENT)) {
            if (!(payload instanceof NbtCompound slots)) {
                return Optional.of("the saved section is not a compound");
            }
            for (String slot : slots.keys()) {
                equipment(slot, slots.get(slot).copy());
            }
            return Optional.empty();
        }
        if (EFFECT_KEYS.contains(key)) {
            if (!(payload instanceof NbtList effects)) {
                return Optional.of("the saved section is not a list");
            }
            for (NbtTag effect : effects.elements()) {
                effect(effect.copy());
            }
            return Optional.empty();
        }
        if (ATTACHMENT_CONTAINERS.contains(key)) {
            if (!(payload instanceof NbtCompound attachments)) {
                return Optional.of("the saved section is not a compound");
            }
            for (String attachment : attachments.keys()) {
                replaceIn(key, attachment, attachments.get(attachment).copy(), Section.ATTACHMENTS);
            }
            return Optional.empty();
        }
        if (key.equals(RECIPE_BOOK)) {
            return replaceIn(null, RECIPE_BOOK, payload, Section.RECIPE_BOOK);
        }
        return replaceIn(null, key, payload, Section.UNKNOWN_KEY);
    }

    /** An inventory or ender chest stack, with its {@code Slot}. */
    private Optional<String> stack(String containerKey, NbtTag payload) {
        if (!(payload instanceof NbtCompound stack)) {
            return Optional.of("the saved stack is not a compound");
        }
        NbtList list = list(containerKey);
        Set<Integer> taken = takenSlots(list);
        int size = containerKey.equals(INVENTORY) ? INVENTORY_SLOTS : ENDER_SLOTS;
        Optional<Integer> own = stack.number("Slot").map(n -> n.byteValue() & 0xff);
        Optional<Integer> slot = own.filter(s -> !taken.contains(s));
        if (slot.isEmpty()) {
            slot = firstFree(taken, size);
        }
        if (slot.isEmpty()) {
            stack.remove("Slot");
            toDrop.add(stack);
            return Optional.empty();
        }
        stack.put("Slot", NbtValue.ofByte((byte) slot.get().intValue()));
        list.add(stack);
        return Optional.empty();
    }

    /** A stack of the 26.3 {@code equipment} compound: its own slot when free, else the inventory. */
    private Optional<String> equipment(String slot, NbtTag payload) {
        if (!(payload instanceof NbtCompound stack)) {
            return Optional.of("the saved stack is not a compound");
        }
        NbtCompound equipment = compound(null, EQUIPMENT);
        if (!equipment.contains(slot)) {
            equipment.put(slot, stack);
            return Optional.empty();
        }
        stack.remove("Slot");
        return stack(INVENTORY, stack);
    }

    /** An effect replaces an active effect with the same id, which is kept aside. */
    private Optional<String> effect(NbtTag payload) {
        if (!(payload instanceof NbtCompound effect)) {
            return Optional.of("the saved effect is not a compound");
        }
        // 1.20.1 stores effects under ActiveEffects with a numeric Id, later versions under
        // active_effects with a string id; the payload's own form says which list it belongs to.
        String key = effect.contains("Id") && !effect.contains("id") ? "ActiveEffects" : "active_effects";
        NbtList effects = list(key);
        String name = effectId(effect);
        for (int i = 0; i < effects.size(); i++) {
            if (effectId(effects.get(i)).equals(name)) {
                replaced.add(new Replaced("effect." + name, Section.EFFECTS, Optional.empty(), effects.get(i)));
                effects.remove(i);
                break;
            }
        }
        effects.add(effect);
        return Optional.empty();
    }

    /** A key of {@code container} (or of the root when null) gets the payload; what was there is kept aside. */
    private Optional<String> replaceIn(String container, String key, NbtTag payload, Section section) {
        NbtCompound target = container == null ? data : compound(null, container);
        NbtTag before = target.get(key);
        if (before != null) {
            String id = switch (section) {
                case ATTACHMENTS -> "attachment." + key;
                case RECIPE_BOOK -> "recipes";
                default -> "key." + key;
            };
            replaced.add(new Replaced(id, section, Optional.empty(), before));
        }
        target.put(key, payload);
        return Optional.empty();
    }

    private NbtList list(String key) {
        if (data.get(key) instanceof NbtList list) {
            return list;
        }
        NbtList list = new NbtList(NbtTag.COMPOUND);
        data.put(key, list);
        return list;
    }

    private NbtCompound compound(NbtCompound parent, String key) {
        NbtCompound owner = parent == null ? data : parent;
        if (owner.get(key) instanceof NbtCompound compound) {
            return compound;
        }
        NbtCompound compound = new NbtCompound();
        owner.put(key, compound);
        return compound;
    }

    private static Set<Integer> takenSlots(NbtList list) {
        Set<Integer> taken = new TreeSet<>();
        for (NbtTag element : list.elements()) {
            if (element instanceof NbtCompound stack) {
                stack.number("Slot").ifPresent(n -> taken.add(n.byteValue() & 0xff));
            }
        }
        return taken;
    }

    private static Optional<Integer> firstFree(Set<Integer> taken, int size) {
        for (int slot = 0; slot < size; slot++) {
            if (!taken.contains(slot)) {
                return Optional.of(slot);
            }
        }
        return Optional.empty();
    }

    private static String effectId(NbtTag effect) {
        if (effect instanceof NbtCompound compound) {
            Optional<String> name = compound.string("id").or(() -> compound.string("forge:id"));
            if (name.isPresent()) {
                return name.get();
            }
            return compound.number("Id")
                    .map(n -> Integer.toString(n.intValue()))
                    .orElse("unknown");
        }
        return "unknown";
    }
}
