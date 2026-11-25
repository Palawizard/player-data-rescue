package fr.palawi.playerdatarescue.core.restore;

import fr.palawi.playerdatarescue.core.io.AtomicFiles;
import fr.palawi.playerdatarescue.core.nbt.NbtCompound;
import fr.palawi.playerdatarescue.core.nbt.NbtIo;
import fr.palawi.playerdatarescue.core.nbt.NbtList;
import fr.palawi.playerdatarescue.core.nbt.NbtTag;
import fr.palawi.playerdatarescue.core.nbt.NbtValue;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A restore on its way into the player's data, on disk: {@code <uuid>/restore-pending.dat}. It is
 * written before the merged data, so that a join that never ends (a crash, a client that leaves
 * during configuration) is settled at the next join from what the player file then holds.
 *
 * @param backup the backup incident of this restore
 * @param originalSha the SHA-256 of the player file before the merged data was written
 * @param items the entries put into the data, waiting for the join's result
 * @param carry stacks of entries already restored, still to drop at the player's feet
 */
public record RestorePending(String backup, String originalSha, List<Item> items, List<NbtCompound> carry) {
    public static final String FILE_NAME = "restore-pending.dat";

    /** One entry in the data: the tags it put there, and its stacks with no free slot. */
    public record Item(RestoreQueue.Item entry, List<NbtTag> placed, List<NbtCompound> drops) {}

    public static Path file(Path root, UUID player) {
        return root.resolve(player.toString()).resolve(FILE_NAME);
    }

    public static Optional<RestorePending> read(Path root, UUID player) throws IOException {
        Path file = file(root, player);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(parse(NbtIo.read(file)));
        } catch (ClassCastException e) {
            throw new IOException("cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    private static RestorePending parse(NbtCompound tag) throws IOException {
        List<Item> items = new ArrayList<>();
        for (NbtTag element : list(tag, "items")) {
            NbtCompound item = (NbtCompound) element;
            List<NbtTag> placed = new ArrayList<>();
            for (NbtTag wrapped : list(item, "placed")) {
                placed.add(((NbtCompound) wrapped).get("tag"));
            }
            items.add(new Item(
                    new RestoreQueue.Item(text(item, "incident"), text(item, "entry")),
                    placed,
                    compounds(item, "drops")));
        }
        return new RestorePending(text(tag, "backup"), text(tag, "originalSha"), items, compounds(tag, "carry"));
    }

    /** Writes this record, or deletes the file when nothing is left in it. */
    public void write(Path root, UUID player) throws IOException {
        Path file = file(root, player);
        if (items.isEmpty() && carry.isEmpty()) {
            Files.deleteIfExists(file);
            return;
        }
        NbtCompound tag = new NbtCompound();
        tag.put("backup", NbtValue.ofString(backup));
        tag.put("originalSha", NbtValue.ofString(originalSha));
        NbtList itemList = new NbtList(NbtTag.COMPOUND);
        for (Item item : items) {
            NbtCompound entry = new NbtCompound();
            entry.put("incident", NbtValue.ofString(item.entry().incident()));
            entry.put("entry", NbtValue.ofString(item.entry().entry()));
            NbtList placed = new NbtList(NbtTag.COMPOUND);
            for (NbtTag t : item.placed()) {
                placed.add(new NbtCompound().put("tag", t.copy()));
            }
            entry.put("placed", placed);
            entry.put("drops", compoundList(item.drops()));
            itemList.add(entry);
        }
        tag.put("items", itemList);
        tag.put("carry", compoundList(carry));
        Files.createDirectories(file.getParent());
        AtomicFiles.write(file, NbtIo.write(tag, true));
    }

    public boolean holds(RestoreQueue.Item entry) {
        return items.stream().anyMatch(i -> i.entry().equals(entry));
    }

    private static NbtList compoundList(List<NbtCompound> stacks) {
        NbtList list = new NbtList(NbtTag.COMPOUND);
        stacks.forEach(s -> list.add(s.copy()));
        return list;
    }

    private static List<NbtTag> list(NbtCompound tag, String key) {
        return tag.list(key).map(NbtList::elements).orElse(List.of());
    }

    private static List<NbtCompound> compounds(NbtCompound tag, String key) {
        return list(tag, key).stream().map(NbtCompound.class::cast).toList();
    }

    private static String text(NbtCompound tag, String key) throws IOException {
        return tag.string(key).orElseThrow(() -> new IOException(FILE_NAME + " has no " + key));
    }
}
