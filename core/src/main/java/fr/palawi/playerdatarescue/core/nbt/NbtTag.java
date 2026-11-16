package fr.palawi.playerdatarescue.core.nbt;

/** A node of a binary NBT tree, read without Minecraft so the core can be tested on real files. */
public sealed interface NbtTag permits NbtCompound, NbtList, NbtValue {
    byte END = 0;
    byte BYTE = 1;
    byte SHORT = 2;
    byte INT = 3;
    byte LONG = 4;
    byte FLOAT = 5;
    byte DOUBLE = 6;
    byte BYTE_ARRAY = 7;
    byte STRING = 8;
    byte LIST = 9;
    byte COMPOUND = 10;
    byte INT_ARRAY = 11;
    byte LONG_ARRAY = 12;

    byte type();

    NbtTag copy();
}
