package fr.palawi.playerdatarescue.core.data;

import fr.palawi.playerdatarescue.core.nbt.NbtTag;
import java.util.Optional;

/**
 * One piece of the saved data that can be set aside on its own: a slot, an effect, an attachment
 * key, a whole section or an unknown top-level key. It points into the tree it was split from, so
 * {@link PlayerData#strip} removes exactly this element.
 *
 * @param id stable identifier, for example {@code inv.17} or {@code attachment.examplemod:mana}
 * @param slot the slot number for inventory, ender chest and equipment entries, otherwise empty
 * @param namespace the namespace of the unit's registry id or key, when it has one
 * @param payload the unit's tag, as stored in the file
 */
public record Unit(String id, Section section, Optional<String> slot, Optional<String> namespace, NbtTag payload) {}
