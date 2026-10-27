package com.mc2hytale;

import com.mc2hytale.convert.ConversionPlan;
import com.mc2hytale.mapping.BlockMapper;
import com.mc2hytale.mapping.BlockMapper.Status;
import com.mc2hytale.mapping.BlockRegistry;
import com.mc2hytale.mapping.MappingRules;
import com.mc2hytale.mapping.Settings;
import com.mc2hytale.schematic.Schematic;
import com.mc2hytale.schematic.SchematicLoader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

/**
 * Offline checks (no server): writes small schematics in each supported format, loads them back and
 * runs them through the mapper. Run with {@code gradlew testSchematics}.
 */
public final class SchematicCheck {

    private static int failures;

    public static void main(String[] args) throws Exception {
        MappingRules defaults;
        try (var in = SchematicCheck.class.getResourceAsStream("/default-mappings.json")) {
            defaults = MappingRules.parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        Set<String> ids = new HashSet<>(defaults.asMap().values());
        ids.addAll(List.of("Wood_Softwood_Stairs", "Wood_Softwood_Planks_Half", "Rock_Stone_Brick_Stairs", "Rock_Stone"));
        BlockRegistry registry = BlockRegistry.of(ids);

        // Sponge v2: 2×2×2 with stone, oak stairs, air and a modded block.
        Schematic v2 = SchematicLoader.load(gzip(spongeV2()));
        check(v2.format().equals("Sponge v2"), "sponge v2 format: " + v2.format());
        check(v2.width() == 2 && v2.height() == 2 && v2.length() == 2, "sponge v2 size");
        check(v2.palette().get(v2.at(1, 0, 0)).startsWith("minecraft:oak_stairs"), "sponge v2 cell (1,0,0)");

        Settings settings = new Settings();
        BlockMapper mapper = new BlockMapper(new MappingRules(), defaults, registry, settings);
        ConversionPlan plan = new ConversionPlan(v2, mapper);
        check(mapper.resolve("minecraft:stone").target().equals("Rock_Stone"), "stone → Rock_Stone");
        var stairs = mapper.resolve("minecraft:oak_stairs[facing=east,half=bottom]");
        check(stairs.status() == Status.AUTO && stairs.target().equals("Wood_Softwood_Stairs"), "oak_stairs smart shape: " + stairs);
        check(stairs.yaw() == 3, "east facing yaw 3: " + stairs.yaw());
        var slab = mapper.resolve("minecraft:oak_slab[type=bottom]");
        check("Wood_Softwood_Planks_Half".equals(slab.target()), "oak_slab → planks half: " + slab);
        check("Wood_Softwood_Planks".equals(mapper.resolve("minecraft:oak_slab[type=double]").target()), "double slab → full block");
        check(mapper.resolve("minecraft:air").status() == Status.AIR, "air");
        check(mapper.resolve("mymod:weird_block").status() == Status.UNMAPPED, "modded block unmapped");
        check(mapper.resolve("minecraft:red_wool").target().equals("Cloth_Block_Wool_Red"), "red wool");
        check(mapper.resolve("minecraft:spruce_door[facing=north]").target().equals("Furniture_Village_Door"), "door wildcard");
        check(mapper.resolve("minecraft:command_block").status() == Status.SKIPPED, "command block skipped");
        check(mapper.resolve("minecraft:water[level=0]").target().equals("fluid:Water_Source"), "water fluid");
        check(plan.problems().size() == 1 && plan.problems().get(0).source().equals("mymod:weird_block"), "one problem row");
        check(plan.placed() == 5, "placed count " + plan.placed());

        // Custom rules win over defaults; wildcards; invalid targets are reported.
        MappingRules custom = new MappingRules();
        custom.put("stone", "Rock_Stone_Brick_Stairs");
        custom.put("mymod:*", "Rock_Stone");
        custom.put("dirt", "Not_A_Block");
        BlockMapper m2 = new BlockMapper(custom, defaults, registry, settings);
        check(m2.resolve("minecraft:stone").status() == Status.CUSTOM, "custom beats default");
        check(m2.resolve("mymod:weird_block").target().equals("Rock_Stone"), "custom wildcard");
        check(m2.resolve("minecraft:dirt").status() == Status.INVALID_TARGET, "invalid target flagged");
        settings.unmappedAction = "fallback";
        check(new BlockMapper(new MappingRules(), defaults, registry, settings).resolve("x:y").status() == Status.FALLBACK, "fallback");

        // Sponge v3.
        Schematic v3 = SchematicLoader.load(gzip(spongeV3()));
        check(v3.format().equals("Sponge v3") && v3.volume() == 8, "sponge v3");
        check(v3.palette().get(v3.at(0, 1, 1)).equals("minecraft:stone"), "sponge v3 cell");

        // Legacy MCEdit: stone, wool:14 (red), oak stairs data 3 (north), log axis x.
        Schematic legacy = SchematicLoader.load(gzip(mcedit()));
        check(legacy.format().startsWith("MCEdit"), "legacy format");
        check(legacy.palette().get(legacy.at(1, 0, 0)).equals("minecraft:red_wool"), "legacy wool colour: " + legacy.palette());
        check(legacy.palette().get(legacy.at(0, 0, 1)).equals("minecraft:oak_stairs[facing=north,half=bottom]"), "legacy stairs");
        check(legacy.palette().get(legacy.at(1, 0, 1)).equals("minecraft:oak_log[axis=x]"), "legacy log axis");

        // Structure .nbt with a missing cell (structure void).
        Schematic structure = SchematicLoader.load(gzip(structureNbt()));
        check(structure.format().startsWith("Structure"), "structure format");
        check(structure.at(0, 0, 0) >= 0 && structure.at(1, 0, 0) == Schematic.NONE, "structure void stays empty");
        check(structure.palette().get(structure.at(0, 0, 0)).equals("minecraft:oak_stairs[facing=south,half=top]"), "structure props");

        // Garbage is rejected with a message, not a crash.
        try {
            SchematicLoader.load(new ByteArrayInputStream(new byte[]{1, 2, 3}));
            check(false, "garbage rejected");
        } catch (IOException expected) {
            check(true, "garbage rejected");
        }

        check(ConverterService.safeName("houses/My House.schem").equals("my_house"), "safe name");

        if (failures > 0) {
            throw new AssertionError(failures + " check(s) failed");
        }
        System.out.println("All schematic checks passed.");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    // ---- tiny NBT writer for building test files ----

    private record Tag(int type, Object value) {}

    private static Tag compound(Object... kv) {
        Map<String, Tag> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], (Tag) kv[i + 1]);
        }
        return new Tag(10, m);
    }

    private static Tag i(int v) { return new Tag(3, v); }
    private static Tag s(int v) { return new Tag(2, (short) v); }
    private static Tag str(String v) { return new Tag(8, v); }
    private static Tag bytes(byte... v) { return new Tag(7, v); }
    private static Tag list(int type, Tag... items) { return new Tag(9, new Object[]{type, items}); }

    private static byte[] write(Tag root) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeByte(10);
        out.writeUTF("");
        payload(out, root);
        return bos.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private static void payload(DataOutputStream out, Tag t) throws IOException {
        switch (t.type) {
            case 2 -> out.writeShort((Short) t.value);
            case 3 -> out.writeInt((Integer) t.value);
            case 7 -> {
                byte[] b = (byte[]) t.value;
                out.writeInt(b.length);
                out.write(b);
            }
            case 8 -> out.writeUTF((String) t.value);
            case 9 -> {
                Object[] v = (Object[]) t.value;
                Tag[] items = (Tag[]) v[1];
                out.writeByte((Integer) v[0]);
                out.writeInt(items.length);
                for (Tag item : items) {
                    payload(out, item);
                }
            }
            case 10 -> {
                for (Map.Entry<String, Tag> e : ((Map<String, Tag>) t.value).entrySet()) {
                    out.writeByte(e.getValue().type);
                    out.writeUTF(e.getKey());
                    payload(out, e.getValue());
                }
                out.writeByte(0);
            }
            default -> throw new IllegalArgumentException("type " + t.type);
        }
    }

    private static ByteArrayInputStream gzip(Tag root) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(write(root));
        }
        return new ByteArrayInputStream(bos.toByteArray());
    }

    private static Tag spongeV2() {
        // index = (y*length + z)*width + x
        return compound("Version", i(2), "Width", s(2), "Height", s(2), "Length", s(2),
                "Palette", compound("minecraft:air", i(0), "minecraft:stone", i(1),
                        "minecraft:oak_stairs[facing=east,half=bottom]", i(2), "mymod:weird_block", i(3)),
                "PaletteMax", i(4),
                "BlockData", bytes((byte) 1, (byte) 2, (byte) 1, (byte) 3, (byte) 0, (byte) 0, (byte) 1, (byte) 1));
    }

    private static Tag spongeV3() {
        return compound("Schematic", compound("Version", i(3), "Width", s(2), "Height", s(2), "Length", s(2),
                "Blocks", compound("Palette", compound("minecraft:air", i(0), "minecraft:stone", i(1)),
                        "Data", bytes((byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 1, (byte) 1))));
    }

    private static Tag mcedit() {
        // 2×1×2: (0,0,0) stone, (1,0,0) red wool, (0,0,1) oak stairs north, (1,0,1) oak log axis x
        return compound("Width", s(2), "Height", s(1), "Length", s(2), "Materials", str("Alpha"),
                "Blocks", bytes((byte) 1, (byte) 35, (byte) 53, (byte) 17),
                "Data", bytes((byte) 0, (byte) 14, (byte) 3, (byte) 4));
    }

    private static Tag structureNbt() {
        return compound("size", list(3, i(2), i(1), i(1)),
                "palette", list(10, compound("Name", str("minecraft:oak_stairs"),
                        "Properties", compound("half", str("top"), "facing", str("south")))),
                "blocks", list(10, compound("pos", list(3, i(0), i(0), i(0)), "state", i(0))),
                "entities", list(10));
    }
}
