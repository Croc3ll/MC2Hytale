package com.mc2hytale.nbt;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * A small reader for Minecraft's NBT format (big-endian, Java edition). Compounds come back as
 * {@code Map<String, Object>}, lists as {@code List<Object>}, arrays as {@code byte[]}/{@code int[]}/{@code long[]}
 * and numbers as their boxed types. Gzip-compressed and raw files are both accepted.
 */
public final class Nbt {

    private Nbt() {}

    /** Reads the root compound of an NBT file. The root tag's own name is dropped. */
    public static Map<String, Object> read(InputStream raw) throws IOException {
        BufferedInputStream in = new BufferedInputStream(raw);
        in.mark(2);
        int b1 = in.read();
        int b2 = in.read();
        in.reset();
        InputStream body = (b1 == 0x1f && b2 == 0x8b) ? new GZIPInputStream(in) : in;
        DataInputStream data = new DataInputStream(new BufferedInputStream(body));
        int type = data.readUnsignedByte();
        if (type != 10) {
            throw new IOException("Not an NBT file (root tag type " + type + ")");
        }
        data.readUTF();
        return readCompound(data);
    }

    private static Map<String, Object> readCompound(DataInputStream in) throws IOException {
        Map<String, Object> map = new LinkedHashMap<>();
        while (true) {
            int type = in.readUnsignedByte();
            if (type == 0) {
                return map;
            }
            String name = readString(in);
            map.put(name, readPayload(in, type));
        }
    }

    private static Object readPayload(DataInputStream in, int type) throws IOException {
        return switch (type) {
            case 1 -> in.readByte();
            case 2 -> in.readShort();
            case 3 -> in.readInt();
            case 4 -> in.readLong();
            case 5 -> in.readFloat();
            case 6 -> in.readDouble();
            case 7 -> {
                byte[] a = new byte[checkLength(in.readInt())];
                in.readFully(a);
                yield a;
            }
            case 8 -> readString(in);
            case 9 -> {
                int itemType = in.readUnsignedByte();
                int len = checkLength(in.readInt());
                List<Object> list = new ArrayList<>(Math.min(len, 4096));
                for (int i = 0; i < len; i++) {
                    list.add(readPayload(in, itemType));
                }
                yield list;
            }
            case 10 -> readCompound(in);
            case 11 -> {
                int[] a = new int[checkLength(in.readInt())];
                for (int i = 0; i < a.length; i++) {
                    a[i] = in.readInt();
                }
                yield a;
            }
            case 12 -> {
                long[] a = new long[checkLength(in.readInt())];
                for (int i = 0; i < a.length; i++) {
                    a[i] = in.readLong();
                }
                yield a;
            }
            default -> throw new IOException("Unknown NBT tag type " + type);
        };
    }

    private static String readString(DataInputStream in) throws IOException {
        // Modified UTF-8 like readUTF, but tolerant of plain UTF-8 written by some tools.
        int len = in.readUnsignedShort();
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int checkLength(int len) throws IOException {
        if (len < 0 || len > 256 * 1024 * 1024) {
            throw new IOException("Corrupt NBT (array length " + len + ")");
        }
        return len;
    }

    // ---- typed accessors for the loaders ----

    @SuppressWarnings("unchecked")
    public static Map<String, Object> compound(Map<String, Object> map, String key) {
        Object o = map.get(key);
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> map, String key) {
        Object o = map.get(key);
        return o instanceof List<?> l ? (List<Object>) l : null;
    }

    public static int integer(Map<String, Object> map, String key, int fallback) {
        return map.get(key) instanceof Number n ? n.intValue() : fallback;
    }

    public static String string(Map<String, Object> map, String key) {
        return map.get(key) instanceof String s ? s : null;
    }
}
