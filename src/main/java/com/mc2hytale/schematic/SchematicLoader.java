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
        return n.endsWith(".schem") || n.endsWith(".schematic") || n.endsWith(".nbt") || n.endsWith(".litematic");
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
        if (Nbt.compound(root, "Regions") != null) {
            return litematic(root);
        }
        throw new IOException("Unrecognised file: not a Sponge .schem, MCEdit .schematic, structure .nbt or .litematic");
    }

    // ---- Litematica ----

    /**
     * Litematica keeps one or more regions, each with its own position, a size that may be negative
     * (the region grows the other way from its position), a palette and a bit-packed block array whose
     * entries can straddle two longs. All regions are merged into one box.
     */
    @SuppressWarnings("unchecked")
    private static Schematic litematic(Map<String, Object> root) throws IOException {
        Map<String, Object> regions = Nbt.compound(root, "Regions");
        if (regions.isEmpty()) {
            throw new IOException("Litematic file has no regions");
        }
        record Region(int x, int y, int z, int w, int h, int l, List<String> palette, long[] states) {}
        List<Region> list = new ArrayList<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        int blockEntities = 0;
        int entities = 0;
        for (Object o : regions.values()) {
            Map<String, Object> r = (Map<String, Object>) o;
            Map<String, Object> pos = Nbt.compound(r, "Position");
            Map<String, Object> size = Nbt.compound(r, "Size");
            List<Object> paletteTag = Nbt.list(r, "BlockStatePalette");
            if (pos == null || size == null || paletteTag == null || !(r.get("BlockStates") instanceof long[] states)) {
                throw new IOException("Litematic region is missing its position, size, palette or blocks");
            }
            int sx = Nbt.integer(size, "x", 0), sy = Nbt.integer(size, "y", 0), sz = Nbt.integer(size, "z", 0);
            int w = Math.abs(sx), h = Math.abs(sy), l = Math.abs(sz);
            checkSize(w, h, l);
            // A negative size means the region extends from its position towards smaller coordinates.
            int x = Nbt.integer(pos, "x", 0) + (sx < 0 ? sx + 1 : 0);
            int y = Nbt.integer(pos, "y", 0) + (sy < 0 ? sy + 1 : 0);
            int z = Nbt.integer(pos, "z", 0) + (sz < 0 ? sz + 1 : 0);
            List<String> palette = new ArrayList<>(paletteTag.size());
            for (Object p : paletteTag) {
                Map<String, Object> entry = (Map<String, Object>) p;
                String name = Nbt.string(entry, "Name");
                palette.add(withProperties(name == null ? "minecraft:air" : name, Nbt.compound(entry, "Properties")));
            }
            list.add(new Region(x, y, z, w, h, l, palette, states));
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x + w - 1);
            maxY = Math.max(maxY, y + h - 1);
            maxZ = Math.max(maxZ, z + l - 1);
            List<Object> te = Nbt.list(r, "TileEntities");
            List<Object> ents = Nbt.list(r, "Entities");
            blockEntities += te == null ? 0 : te.size();
            entities += ents == null ? 0 : ents.size();
        }

        int w = maxX - minX + 1, h = maxY - minY + 1, l = maxZ - minZ + 1;
        checkSize(w, h, l);
        int[] cells = new int[w * h * l];
        Arrays.fill(cells, Schematic.NONE);
        Map<String, Integer> lookup = new TreeMap<>();
        List<String> palette = new ArrayList<>();
        for (Region r : list) {
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(r.palette.size() - 1));
            long mask = (1L << bits) - 1;
            long volume = (long) r.w * r.h * r.l;
            if ((volume * bits + 63) / 64 > r.states.length) {
                throw new IOException("Litematic block data is shorter than its region");
            }
            int[] remap = new int[r.palette.size()];
            for (int i = 0; i < remap.length; i++) {
                String state = r.palette.get(i);
                Integer idx = lookup.get(state);
                if (idx == null) {
                    idx = palette.size();
                    palette.add(state);
                    lookup.put(state, idx);
                }
                remap[i] = idx;
            }
            int i = 0;
            for (int y = 0; y < r.h; y++) {
                for (int z = 0; z < r.l; z++) {
                    for (int x = 0; x < r.w; x++, i++) {
                        long start = (long) i * bits;
                        int word = (int) (start >>> 6);
                        int offset = (int) (start & 63);
                        long value = r.states[word] >>> offset;
                        if (offset + bits > 64) {
                            value |= r.states[word + 1] << (64 - offset);
                        }
                        int p = (int) (value & mask);
                        if (p >= remap.length) {
                            throw new IOException("Litematic block refers to palette entry " + p + " of " + remap.length);
                        }
                        int cx = r.x - minX + x, cy = r.y - minY + y, cz = r.z - minZ + z;
                        cells[(cy * l + cz) * w + cx] = remap[p];
                    }
                }
            }
        }
        return new Schematic("Litematica (" + list.size() + (list.size() == 1 ? " region)" : " regions)"),
                w, h, l, palette, cells, blockEntities, entities);
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
