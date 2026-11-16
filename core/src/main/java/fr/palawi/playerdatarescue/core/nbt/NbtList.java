package fr.palawi.playerdatarescue.core.nbt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A list tag. Every element has the list's element type, as in the binary format. */
public final class NbtList implements NbtTag {
    private final List<NbtTag> elements = new ArrayList<>();
    private byte elementType;

    public NbtList(byte elementType) {
        this.elementType = elementType;
    }

    @Override
    public byte type() {
        return LIST;
    }

    public byte elementType() {
        return elementType;
    }

    public List<NbtTag> elements() {
        return Collections.unmodifiableList(elements);
    }

    public int size() {
        return elements.size();
    }

    public NbtTag get(int index) {
        return elements.get(index);
    }

    public void add(NbtTag tag) {
        if (elements.isEmpty() && elementType == END) {
            elementType = tag.type();
        }
        if (tag.type() != elementType) {
            throw new IllegalArgumentException("list of type " + elementType + " cannot hold type " + tag.type());
        }
        elements.add(tag);
    }

    public NbtTag remove(int index) {
        return elements.remove(index);
    }

    @Override
    public NbtList copy() {
        NbtList copy = new NbtList(elementType);
        for (NbtTag element : elements) {
            copy.elements.add(element.copy());
        }
        return copy;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof NbtList that && that.elementType == elementType && that.elements.equals(elements);
    }

    @Override
    public int hashCode() {
        return 31 * elementType + elements.hashCode();
    }

    @Override
    public String toString() {
        return Snbt.write(this);
    }
}
