package fr.palawi.playerdatarescue.core.nbt;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Binary NBT as Minecraft writes it in player files and {@code level.dat}: a named root compound,
 * gzip compressed or not. Every length is checked against the bytes left, so a damaged file fails
 * with {@link NbtFormatException} instead of allocating a huge array.
 */
public final class NbtIo {
    /** Larger than any player file seen in practice, small enough for a server's heap. */
    public static final int MAX_UNCOMPRESSED_BYTES = 64 * 1024 * 1024;

    private static final int MAX_DEPTH = 512;

    private NbtIo() {}

    public static NbtCompound read(Path file) throws IOException {
        return read(Files.readAllBytes(file));
    }

    /** Reads a root compound, gzip compressed or not. */
    public static NbtCompound read(byte[] data) throws NbtFormatException {
        byte[] raw = isGzip(data) ? gunzip(data) : data;
        Reader reader = new Reader(raw);
        byte type = reader.u8();
        if (type != NbtTag.COMPOUND) {
            throw new NbtFormatException("root tag is type " + type + ", not a compound");
        }
        reader.string();
        NbtCompound root = (NbtCompound) reader.payload(type, 0);
        if (reader.position != raw.length) {
            throw new NbtFormatException((raw.length - reader.position) + " bytes left after the root compound");
        }
        return root;
    }

    public static byte[] write(NbtCompound root, boolean gzip) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            if (gzip) {
                try (GZIPOutputStream zip = new GZIPOutputStream(bytes);
                        DataOutputStream out = new DataOutputStream(zip)) {
                    writeRoot(root, out);
                }
            } else {
                try (DataOutputStream out = new DataOutputStream(bytes)) {
                    writeRoot(root, out);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /** Size of a tag's payload in the binary format, without a name or a type byte. */
    public static long payloadSize(NbtTag tag) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            writePayload(tag, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.size();
    }

    public static boolean isGzip(byte[] data) {
        return data.length >= 2 && (data[0] & 0xff) == 0x1f && (data[1] & 0xff) == 0x8b;
    }

    private static byte[] gunzip(byte[] data) throws NbtFormatException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                if (out.size() + read > MAX_UNCOMPRESSED_BYTES) {
                    throw new NbtFormatException("more than " + MAX_UNCOMPRESSED_BYTES + " bytes once uncompressed");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new NbtFormatException("gzip stream is damaged: " + e.getMessage(), e);
        }
    }

    private static void writeRoot(NbtCompound root, DataOutputStream out) throws IOException {
        out.writeByte(NbtTag.COMPOUND);
        out.writeUTF("");
        writePayload(root, out);
    }

    private static void writePayload(NbtTag tag, DataOutputStream out) throws IOException {
        if (tag instanceof NbtCompound compound) {
            for (var entry : compound.entries().entrySet()) {
                out.writeByte(entry.getValue().type());
                out.writeUTF(entry.getKey());
                writePayload(entry.getValue(), out);
            }
            out.writeByte(NbtTag.END);
        } else if (tag instanceof NbtList list) {
            out.writeByte(list.size() == 0 ? NbtTag.END : list.elementType());
            out.writeInt(list.size());
            for (NbtTag element : list.elements()) {
                writePayload(element, out);
            }
        } else {
            NbtValue value = (NbtValue) tag;
            Object raw = value.raw();
            switch (value.type()) {
                case NbtTag.BYTE -> out.writeByte((Byte) raw);
                case NbtTag.SHORT -> out.writeShort((Short) raw);
                case NbtTag.INT -> out.writeInt((Integer) raw);
                case NbtTag.LONG -> out.writeLong((Long) raw);
                case NbtTag.FLOAT -> out.writeFloat((Float) raw);
                case NbtTag.DOUBLE -> out.writeDouble((Double) raw);
                case NbtTag.STRING -> out.writeUTF((String) raw);
                case NbtTag.BYTE_ARRAY -> {
                    byte[] array = (byte[]) raw;
                    out.writeInt(array.length);
                    out.write(array);
                }
                case NbtTag.INT_ARRAY -> {
                    int[] array = (int[]) raw;
                    out.writeInt(array.length);
                    for (int v : array) {
                        out.writeInt(v);
                    }
                }
                case NbtTag.LONG_ARRAY -> {
                    long[] array = (long[]) raw;
                    out.writeInt(array.length);
                    for (long v : array) {
                        out.writeLong(v);
                    }
                }
                default -> throw new IllegalStateException("unknown tag type " + value.type());
            }
        }
    }

    private static final class Reader {
        private final byte[] data;
        private int position;

        Reader(byte[] data) {
            this.data = data;
        }

        NbtTag payload(byte type, int depth) throws NbtFormatException {
            if (depth > MAX_DEPTH) {
                throw new NbtFormatException("nested deeper than " + MAX_DEPTH);
            }
            return switch (type) {
                case NbtTag.BYTE -> NbtValue.ofByte((byte) u8());
                case NbtTag.SHORT -> NbtValue.ofShort((short) ((u8() << 8) | u8()));
                case NbtTag.INT -> NbtValue.ofInt(i32());
                case NbtTag.LONG -> NbtValue.ofLong(i64());
                case NbtTag.FLOAT -> NbtValue.ofFloat(Float.intBitsToFloat(i32()));
                case NbtTag.DOUBLE -> NbtValue.ofDouble(Double.longBitsToDouble(i64()));
                case NbtTag.STRING -> NbtValue.ofString(string());
                case NbtTag.BYTE_ARRAY -> {
                    int length = length(1);
                    byte[] array = new byte[length];
                    System.arraycopy(data, position, array, 0, length);
                    position += length;
                    yield NbtValue.ofByteArray(array);
                }
                case NbtTag.INT_ARRAY -> {
                    int[] array = new int[length(4)];
                    for (int i = 0; i < array.length; i++) {
                        array[i] = i32();
                    }
                    yield NbtValue.ofIntArray(array);
                }
                case NbtTag.LONG_ARRAY -> {
                    long[] array = new long[length(8)];
                    for (int i = 0; i < array.length; i++) {
                        array[i] = i64();
                    }
                    yield NbtValue.ofLongArray(array);
                }
                case NbtTag.LIST -> {
                    byte elementType = u8();
                    int length = length(1);
                    if (elementType == NbtTag.END && length > 0) {
                        throw new NbtFormatException("list of end tags with " + length + " elements");
                    }
                    NbtList list = new NbtList(elementType);
                    for (int i = 0; i < length; i++) {
                        list.add(payload(elementType, depth + 1));
                    }
                    yield list;
                }
                case NbtTag.COMPOUND -> {
                    NbtCompound compound = new NbtCompound();
                    while (true) {
                        byte entryType = u8();
                        if (entryType == NbtTag.END) {
                            break;
                        }
                        String key = string();
                        compound.put(key, payload(entryType, depth + 1));
                    }
                    yield compound;
                }
                default -> throw new NbtFormatException("unknown tag type " + type + " at byte " + position);
            };
        }

        private int length(int elementBytes) throws NbtFormatException {
            int length = i32();
            if (length < 0 || (long) length * elementBytes > data.length - position) {
                throw new NbtFormatException("length " + length + " does not fit in the bytes left");
            }
            return length;
        }

        private void need(int count) throws NbtFormatException {
            if (data.length - position < count) {
                throw new NbtFormatException("unexpected end of data at byte " + position);
            }
        }

        byte u8() throws NbtFormatException {
            need(1);
            return data[position++];
        }

        int i32() throws NbtFormatException {
            need(4);
            int v = ((data[position] & 0xff) << 24)
                    | ((data[position + 1] & 0xff) << 16)
                    | ((data[position + 2] & 0xff) << 8)
                    | (data[position + 3] & 0xff);
            position += 4;
            return v;
        }

        long i64() throws NbtFormatException {
            long high = i32() & 0xffffffffL;
            long low = i32() & 0xffffffffL;
            return (high << 32) | low;
        }

        String string() throws NbtFormatException {
            need(2);
            int length = ((data[position] & 0xff) << 8) | (data[position + 1] & 0xff);
            need(2 + length);
            // DataInputStream decodes the modified UTF-8 of the binary format.
            try (var in = new java.io.DataInputStream(new ByteArrayInputStream(data, position, 2 + length))) {
                String value = in.readUTF();
                position += 2 + length;
                return value;
            } catch (IOException e) {
                throw new NbtFormatException("bad string at byte " + position + ": " + e.getMessage(), e);
            }
        }
    }
}
