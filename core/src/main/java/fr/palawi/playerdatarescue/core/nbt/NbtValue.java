package fr.palawi.playerdatarescue.core.nbt;

import java.util.Arrays;
import java.util.Objects;

/** A leaf tag: a number, a string or a primitive array. Arrays are copied in and out. */
public final class NbtValue implements NbtTag {
    private final byte type;
    private final Object value;

    private NbtValue(byte type, Object value) {
        this.type = type;
        this.value = value;
    }

    public static NbtValue ofByte(byte value) {
        return new NbtValue(BYTE, value);
    }

    public static NbtValue ofShort(short value) {
        return new NbtValue(SHORT, value);
    }

    public static NbtValue ofInt(int value) {
        return new NbtValue(INT, value);
    }

    public static NbtValue ofLong(long value) {
        return new NbtValue(LONG, value);
    }

    public static NbtValue ofFloat(float value) {
        return new NbtValue(FLOAT, value);
    }

    public static NbtValue ofDouble(double value) {
        return new NbtValue(DOUBLE, value);
    }

    public static NbtValue ofString(String value) {
        return new NbtValue(STRING, Objects.requireNonNull(value));
    }

    public static NbtValue ofByteArray(byte[] value) {
        return new NbtValue(BYTE_ARRAY, value.clone());
    }

    public static NbtValue ofIntArray(int[] value) {
        return new NbtValue(INT_ARRAY, value.clone());
    }

    public static NbtValue ofLongArray(long[] value) {
        return new NbtValue(LONG_ARRAY, value.clone());
    }

    @Override
    public byte type() {
        return type;
    }

    public boolean isNumber() {
        return type >= BYTE && type <= DOUBLE;
    }

    public Number number() {
        if (!isNumber()) {
            throw new IllegalStateException("not a number tag: type " + type);
        }
        return (Number) value;
    }

    public String string() {
        if (type != STRING) {
            throw new IllegalStateException("not a string tag: type " + type);
        }
        return (String) value;
    }

    public byte[] byteArray() {
        return ((byte[]) cast(BYTE_ARRAY)).clone();
    }

    public int[] intArray() {
        return ((int[]) cast(INT_ARRAY)).clone();
    }

    public long[] longArray() {
        return ((long[]) cast(LONG_ARRAY)).clone();
    }

    int arrayLength() {
        return switch (type) {
            case BYTE_ARRAY -> ((byte[]) value).length;
            case INT_ARRAY -> ((int[]) value).length;
            case LONG_ARRAY -> ((long[]) value).length;
            default -> throw new IllegalStateException("not an array tag: type " + type);
        };
    }

    Object raw() {
        return value;
    }

    private Object cast(byte expected) {
        if (type != expected) {
            throw new IllegalStateException("expected tag type " + expected + ", found " + type);
        }
        return value;
    }

    @Override
    public NbtValue copy() {
        return switch (type) {
            case BYTE_ARRAY -> ofByteArray((byte[]) value);
            case INT_ARRAY -> ofIntArray((int[]) value);
            case LONG_ARRAY -> ofLongArray((long[]) value);
            default -> this;
        };
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof NbtValue that) || that.type != type) {
            return false;
        }
        return switch (type) {
            case BYTE_ARRAY -> Arrays.equals((byte[]) value, (byte[]) that.value);
            case INT_ARRAY -> Arrays.equals((int[]) value, (int[]) that.value);
            case LONG_ARRAY -> Arrays.equals((long[]) value, (long[]) that.value);
            default -> value.equals(that.value);
        };
    }

    @Override
    public int hashCode() {
        return switch (type) {
            case BYTE_ARRAY -> Arrays.hashCode((byte[]) value);
            case INT_ARRAY -> Arrays.hashCode((int[]) value);
            case LONG_ARRAY -> Arrays.hashCode((long[]) value);
            default -> value.hashCode();
        };
    }

    @Override
    public String toString() {
        return Snbt.write(this);
    }
}
