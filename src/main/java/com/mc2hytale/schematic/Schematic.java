package com.mc2hytale.schematic;

import java.util.List;

/**
 * A Minecraft build loaded into memory: a box of {@code width × height × length} blocks (x, y, z),
 * each cell holding an index into {@link #palette()} or {@link #NONE} for "nothing here"
 * (structure void, or a cell the file left out).
 *
 * <p>Palette entries are full block states such as {@code minecraft:oak_stairs[facing=north,half=bottom]}.
 */
public record Schematic(String format, int width, int height, int length, List<String> palette, int[] cells,
                        int blockEntities, int entities) {

    public static final int NONE = -1;

    public int index(int x, int y, int z) {
        return (y * length + z) * width + x;
    }

    public int at(int x, int y, int z) {
        return cells[index(x, y, z)];
    }

    public long volume() {
        return (long) width * height * length;
    }

    /** Just the block id from a full state string: {@code minecraft:oak_stairs[...]} becomes {@code minecraft:oak_stairs}. */
    public static String baseId(String state) {
        int bracket = state.indexOf('[');
        return bracket < 0 ? state : state.substring(0, bracket);
    }

    /** A property from a full state string, or null. {@code property("x[facing=north]", "facing")} is {@code north}. */
    public static String property(String state, String key) {
        int bracket = state.indexOf('[');
        if (bracket < 0) {
            return null;
        }
        String props = state.substring(bracket + 1, state.endsWith("]") ? state.length() - 1 : state.length());
        for (String part : props.split(",")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).trim().equals(key)) {
                return part.substring(eq + 1).trim();
            }
        }
        return null;
    }
}
