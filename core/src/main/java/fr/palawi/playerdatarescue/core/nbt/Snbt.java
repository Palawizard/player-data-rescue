package fr.palawi.playerdatarescue.core.nbt;

import java.util.Locale;
import java.util.regex.Pattern;

/** Text form of a tag for reports, in the same notation as Minecraft's {@code /data} output. */
public final class Snbt {
    private static final Pattern PLAIN_KEY = Pattern.compile("[A-Za-z0-9._+-]+");

    private Snbt() {}

    public static String write(NbtTag tag) {
        StringBuilder out = new StringBuilder();
        append(out, tag);
        return out.toString();
    }

    private static void append(StringBuilder out, NbtTag tag) {
        if (tag instanceof NbtCompound compound) {
            out.append('{');
            boolean first = true;
            for (var entry : compound.entries().entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                String key = entry.getKey();
                out.append(PLAIN_KEY.matcher(key).matches() ? key : quote(key));
                out.append(':');
                append(out, entry.getValue());
            }
            out.append('}');
        } else if (tag instanceof NbtList list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                append(out, list.get(i));
            }
            out.append(']');
        } else {
            NbtValue value = (NbtValue) tag;
            switch (value.type()) {
                case NbtTag.BYTE -> out.append(value.number()).append('b');
                case NbtTag.SHORT -> out.append(value.number()).append('s');
                case NbtTag.INT -> out.append(value.number());
                case NbtTag.LONG -> out.append(value.number()).append('L');
                case NbtTag.FLOAT ->
                    out.append(number(value.number().floatValue())).append('f');
                case NbtTag.DOUBLE ->
                    out.append(number(value.number().doubleValue())).append('d');
                case NbtTag.STRING -> out.append(quote(value.string()));
                case NbtTag.BYTE_ARRAY -> {
                    out.append("[B;");
                    byte[] array = value.byteArray();
                    for (int i = 0; i < array.length; i++) {
                        out.append(i > 0 ? "," : "").append(array[i]).append('B');
                    }
                    out.append(']');
                }
                case NbtTag.INT_ARRAY -> {
                    out.append("[I;");
                    int[] array = value.intArray();
                    for (int i = 0; i < array.length; i++) {
                        out.append(i > 0 ? "," : "").append(array[i]);
                    }
                    out.append(']');
                }
                case NbtTag.LONG_ARRAY -> {
                    out.append("[L;");
                    long[] array = value.longArray();
                    for (int i = 0; i < array.length; i++) {
                        out.append(i > 0 ? "," : "").append(array[i]).append('L');
                    }
                    out.append(']');
                }
                default -> throw new IllegalStateException("unknown tag type " + value.type());
            }
        }
    }

    private static String number(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return Double.toString(value);
        }
        return String.format(Locale.ROOT, "%s", value);
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2).append('"');
        for (char c : text.toCharArray()) {
            if (c == '"' || c == '\\') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.append('"').toString();
    }
}
