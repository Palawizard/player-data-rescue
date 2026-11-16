package fr.palawi.playerdatarescue.core.nbt;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** A compound tag. Keys keep the order they were read or added in. */
public final class NbtCompound implements NbtTag {
    private final Map<String, NbtTag> entries = new LinkedHashMap<>();

    @Override
    public byte type() {
        return COMPOUND;
    }

    public Set<String> keys() {
        return Collections.unmodifiableSet(entries.keySet());
    }

    public Map<String, NbtTag> entries() {
        return Collections.unmodifiableMap(entries);
    }

    public int size() {
        return entries.size();
    }

    public boolean contains(String key) {
        return entries.containsKey(key);
    }

    public NbtTag get(String key) {
        return entries.get(key);
    }

    public NbtCompound put(String key, NbtTag tag) {
        entries.put(key, tag);
        return this;
    }

    public NbtTag remove(String key) {
        return entries.remove(key);
    }

    public Optional<NbtCompound> compound(String key) {
        return entries.get(key) instanceof NbtCompound compound ? Optional.of(compound) : Optional.empty();
    }

    public Optional<NbtList> list(String key) {
        return entries.get(key) instanceof NbtList list ? Optional.of(list) : Optional.empty();
    }

    public Optional<String> string(String key) {
        return entries.get(key) instanceof NbtValue value && value.type() == STRING
                ? Optional.of(value.string())
                : Optional.empty();
    }

    public Optional<Number> number(String key) {
        return entries.get(key) instanceof NbtValue value && value.isNumber()
                ? Optional.of(value.number())
                : Optional.empty();
    }

    @Override
    public NbtCompound copy() {
        NbtCompound copy = new NbtCompound();
        entries.forEach((key, tag) -> copy.entries.put(key, tag.copy()));
        return copy;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof NbtCompound that && that.entries.equals(entries);
    }

    @Override
    public int hashCode() {
        return entries.hashCode();
    }

    @Override
    public String toString() {
        return Snbt.write(this);
    }
}
