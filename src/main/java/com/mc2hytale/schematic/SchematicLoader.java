package com.mc2hytale.schematic;

import com.mc2hytale.nbt.Nbt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads Minecraft build files, picking the format from the file's contents:
 * <ul>
 *   <li>Sponge schematics v1, v2 and v3 ({@code .schem}, WorldEdit 7+ / FAWE)</li>
 *   <li>Legacy MCEdit / WorldEdit 6 schematics ({@code .schematic}, numeric block ids)</li>
 *   <li>Vanilla structure block files ({@code .nbt})</li>
 * </ul>
 */
public final class SchematicLoader {

    /** Refuse builds larger than this many cells; they belong in a world converter, not a prefab. */
    public static final long MAX_VOLUME = 64L * 1024 * 1024;

    private SchematicLoader() {}

    public static boolean isSupportedFile(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".schem") || n.endsWith(".schematic") || n.endsWith(".nbt");
    }

    public static Schematic load(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return load(in);
        }
    }

    public static Schematic load(InputStream in) throws IOException {
        Map<String, Object> root = Nbt.read(in);
        // Sponge v3 wraps everything in a "Schematic" compound.
        Map<String, Object> v3 = Nbt.compound(root, "Schematic");
        if (v3 != null) {
            return sponge(v3);
        }
        if (root.containsKey("Palette") && root.containsKey("BlockData")) {
            return sponge(root);
        }
        if (root.containsKey("Blocks") && root.get("Blocks") instanceof byte[]) {
            return legacy(root);
        }
        if (root.containsKey("size") && (root.containsKey("palette") || root.containsKey("palettes"))) {
            return structure(root);
        }
        throw new IOException("Unrecognised file: not a Sponge .schem, MCEdit .schematic or structure .nbt");
    }

    // ---- Sponge v1 / v2 / v3 ----

    private static Schematic sponge(Map<String, Object> s) throws IOException {
        int version = Nbt.integer(s, "Version", 2);
        int w = Nbt.integer(s, "Width", 0) & 0xFFFF;
        int h = Nbt.integer(s, "Height", 0) & 0xFFFF;
        int l = Nbt.integer(s, "Length", 0) & 0xFFFF;
        checkSize(w, h, l);

        Map<String, Object> blocks = version >= 3 ? Nbt.compound(s, "Blocks") : s;
        if (blocks == null) {
            throw new IOException("Sponge v3 schematic has no Blocks section");
        }
        Map<String, Object> paletteTag = Nbt.compound(blocks, "Palette");
        Object dataTag = blocks.get(version >= 3 ? "Data" : "BlockData");
        if (paletteTag == null || !(dataTag instanceof byte[] data)) {
            throw new IOException("Schematic is missing its block palette or block data");
        }

        String[] palette = new String[paletteTag.size()];
        for (Map.Entry<String, Object> e : paletteTag.entrySet()) {
            int idx = ((Number) e.getValue()).intValue();
            if (idx < 0 || idx >= palette.length) {
                throw new IOException("Palette index out of range: " + idx);
            }
            palette[idx] = e.getKey();
        }
        for (int i = 0; i < palette.length; i++) {
            if (palette[i] == null) {
                palette[i] = "minecraft:air";
            }
        }

        int[] cells = new int[w * h * l];
        int pos = 0;
        for (int i = 0; i < cells.length; i++) {
            int value = 0;
            int shift = 0;
            while (true) {
                if (pos >= data.length) {
                    throw new IOException("Block data ended early at block " + i + " of " + cells.length);
                }
                int b = data[pos++];
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    break;
                }
                shift += 7;
                if (shift > 28) {
                    throw new IOException("Corrupt varint in block data");
                }
            }
            if (value >= palette.length) {
                throw new IOException("Block data refers to palette entry " + value + " but palette has " + palette.length);
            }
            cells[i] = value;
        }

        List<Object> be = Nbt.list(blocks, version >= 3 ? "BlockEntities" : (s.containsKey("BlockEntities") ? "BlockEntities" : "TileEntities"));
        List<Object> ents = Nbt.list(s, "Entities");
        return new Schematic("Sponge v" + version, w, h, l, List.of(palette), cells,
                be == null ? 0 : be.size(), ents == null ? 0 : ents.size());
    }

    // ---- MCEdit / legacy ----

    private static Schematic legacy(Map<String, Object> s) throws IOException {
        int w = Nbt.integer(s, "Width", 0) & 0xFFFF;
        int h = Nbt.integer(s, "Height", 0) & 0xFFFF;
        int l = Nbt.integer(s, "Length", 0) & 0xFFFF;
        checkSize(w, h, l);
        byte[] ids = (byte[]) s.get("Blocks");
        byte[] meta = s.get("Data") instanceof byte[] d ? d : new byte[ids.length];
        byte[] add = s.get("AddBlocks") instanceof byte[] a ? a : null;
        int n = w * h * l;
        if (ids.length < n || meta.length < n) {
            throw new IOException("Legacy schematic block arrays are shorter than its size");
        }

        Map<String, Integer> lookup = new TreeMap<>();
        List<String> palette = new ArrayList<>();
        int[] cells = new int[n];
        for (int i = 0; i < n; i++) {
            int id = ids[i] & 0xFF;
            if (add != null && (i >> 1) < add.length) {
                int nibble = (i & 1) == 0 ? (add[i >> 1] >> 4) & 0xF : add[i >> 1] & 0xF;
                id |= nibble << 8;
            }
            String state = LegacyIds.toState(id, meta[i] & 0xF);
            Integer idx = lookup.get(state);
            if (idx == null) {
                idx = palette.size();
                palette.add(state);
                lookup.put(state, idx);
            }
            cells[i] = idx;
        }
        List<Object> be = Nbt.list(s, "TileEntities");
        List<Object> ents = Nbt.list(s, "Entities");
        return new Schematic("MCEdit (legacy)", w, h, l, palette, cells,
                be == null ? 0 : be.size(), ents == null ? 0 : ents.size());
    }

    // ---- Vanilla structure ----

    @SuppressWarnings("unchecked")
    private static Schematic structure(Map<String, Object> s) throws IOException {
        List<Object> size = Nbt.list(s, "size");
        if (size == null || size.size() != 3) {
            throw new IOException("Structure file has no size");
        }
        int w = ((Number) size.get(0)).intValue();
        int h = ((Number) size.get(1)).intValue();
        int l = ((Number) size.get(2)).intValue();
        checkSize(w, h, l);

        List<Object> paletteTag = Nbt.list(s, "palette");
        if (paletteTag == null) {
            List<Object> palettes = Nbt.list(s, "palettes");
            paletteTag = palettes == null || palettes.isEmpty() ? null : (List<Object>) palettes.get(0);
        }
        if (paletteTag == null) {
            throw new IOException("Structure file has no palette");
        }
        List<String> palette = new ArrayList<>(paletteTag.size());
        for (Object o : paletteTag) {
            Map<String, Object> entry = (Map<String, Object>) o;
            String name = Nbt.string(entry, "Name");
            Map<String, Object> props = Nbt.compound(entry, "Properties");
            palette.add(withProperties(name == null ? "minecraft:air" : name, props));
        }

        int[] cells = new int[w * h * l];
        Arrays.fill(cells, Schematic.NONE);
        int blockEntities = 0;
        List<Object> blocks = Nbt.list(s, "blocks");
        if (blocks != null) {
            for (Object o : blocks) {
                Map<String, Object> b = (Map<String, Object>) o;
                List<Object> pos = Nbt.list(b, "pos");
                int state = Nbt.integer(b, "state", -1);
                if (pos == null || pos.size() != 3 || state < 0 || state >= palette.size()) {
                    continue;
                }
                int x = ((Number) pos.get(0)).intValue();
                int y = ((Number) pos.get(1)).intValue();
                int z = ((Number) pos.get(2)).intValue();
                if (x < 0 || y < 0 || z < 0 || x >= w || y >= h || z >= l) {
                    continue;
                }
                cells[(y * l + z) * w + x] = state;
                if (b.containsKey("nbt")) {
                    blockEntities++;
                }
            }
        }
        List<Object> ents = Nbt.list(s, "entities");
        return new Schematic("Structure (.nbt)", w, h, l, palette, cells, blockEntities, ents == null ? 0 : ents.size());
    }

    private static String withProperties(String name, Map<String, Object> props) {
        if (props == null || props.isEmpty()) {
            return name;
        }
        StringBuilder sb = new StringBuilder(name).append('[');
        boolean first = true;
        for (Map.Entry<String, Object> e : new TreeMap<>(props).entrySet()) {
            if (!first) {
                sb.append(',');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.append(']').toString();
    }

    private static void checkSize(int w, int h, int l) throws IOException {
        if (w <= 0 || h <= 0 || l <= 0) {
            throw new IOException("Build has an empty size (" + w + "×" + h + "×" + l + ")");
        }
        if ((long) w * h * l > MAX_VOLUME) {
            throw new IOException("Build is too large (" + w + "×" + h + "×" + l + "); split it into smaller schematics");
        }
    }
}
