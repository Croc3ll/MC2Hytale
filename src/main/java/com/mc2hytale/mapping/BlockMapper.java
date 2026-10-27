package com.mc2hytale.mapping;

import com.mc2hytale.schematic.Schematic;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides which Hytale block each Minecraft block state becomes. In order:
 * <ol>
 *   <li>the user's profile rules (exact state, bare id, then {@code *} patterns)</li>
 *   <li>the bundled default rules</li>
 *   <li>smart matching: stairs, slabs, walls and fences take their material's mapping and use the
 *       matching Hytale shape ({@code _Stairs}, {@code _Half}, ...) when one exists</li>
 *   <li>the configured fallback for anything left</li>
 * </ol>
 * Every target is checked against the live Hytale block list, so a typo in a rule shows up as
 * "invalid target" in the scan instead of breaking the export.
 */
public final class BlockMapper {

    public enum Status {
        /** Minecraft air / void. */
        AIR,
        /** A rule in the user's profile. */
        CUSTOM,
        /** A bundled default rule. */
        DEFAULT,
        /** Smart shape match from the block's material. */
        AUTO,
        /** No match; the fallback block was used. */
        FALLBACK,
        /** A rule says to leave it out. */
        SKIPPED,
        /** No match; left out. */
        UNMAPPED,
        /** A rule points at a Hytale block that doesn't exist. */
        INVALID_TARGET
    }

    /** Where a Minecraft block ended up. {@code target} is null when nothing is placed. Turns are quarter turns. */
    public record Resolution(String target, Status status, int yaw, int pitch, int roll, String note) {
        public boolean places() {
            return target != null;
        }
    }

    private static final List<String> AIR = List.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air",
            "minecraft:structure_void", "minecraft:barrier", "minecraft:light");

    private final MappingRules custom;
    private final MappingRules defaults;
    private final BlockRegistry registry;
    private final Settings settings;
    private final Map<String, Resolution> cache = new HashMap<>();

    public BlockMapper(MappingRules custom, MappingRules defaults, BlockRegistry registry, Settings settings) {
        this.custom = custom;
        this.defaults = defaults;
        this.registry = registry;
        this.settings = settings;
    }

    public Resolution resolve(String state) {
        return cache.computeIfAbsent(state, this::compute);
    }

    private Resolution compute(String state) {
        String base = Schematic.baseId(state);
        if (AIR.contains(base)) {
            return new Resolution(null, Status.AIR, 0, 0, 0, "");
        }
        int[] turn = rotation(state, base);

        String rule = custom.find(state, base);
        Status status = Status.CUSTOM;
        if (rule == null) {
            rule = defaults.find(state, base);
            status = Status.DEFAULT;
        }
        if (rule != null) {
            if (isSkip(rule)) {
                return new Resolution(null, Status.SKIPPED, 0, 0, 0, "rule: skip");
            }
            if (!registry.exists(rule)) {
                return unmapped(Status.INVALID_TARGET, "rule target " + rule + " is not a Hytale block");
            }
            // A rule for the bare material ("minecraft:oak_planks") still gets a shape for stairs etc.
            return new Resolution(rule, status, turn[0], turn[1], turn[2], "");
        }

        if (settings.smartShapes) {
            Resolution shaped = smartShape(state, base, turn);
            if (shaped != null) {
                return shaped;
            }
        }
        return unmapped(Status.UNMAPPED, "no rule");
    }

    /** {@code oak_stairs} → the mapping for {@code oak_planks} → {@code Wood_Softwood_Stairs}. */
    private Resolution smartShape(String state, String base, int[] turn) {
        String id = base.substring(base.indexOf(':') + 1);
        String ns = base.substring(0, base.indexOf(':') + 1);
        String[][] shapes = {
                {"_stairs", "_Stairs"}, {"_slab", "_Half"}, {"_wall", "_Wall"}, {"_fence_gate", "_Fence_Gate"},
                {"_fence", "_Fence"}, {"_pressure_plate", ""}, {"_button", ""}, {"_carpet", ""}};
        for (String[] shape : shapes) {
            if (!id.endsWith(shape[0])) {
                continue;
            }
            String material = id.substring(0, id.length() - shape[0].length());
            String target = materialTarget(ns, material);
            if (target == null) {
                return null;
            }
            boolean full = shape[0].equals("_slab") && "double".equals(Schematic.property(state, "type"));
            if (!full && !shape[1].isEmpty()) {
                String trimmed = target.endsWith("_Planks") ? target.substring(0, target.length() - 7) : target;
                for (String candidate : new String[]{target + shape[1], trimmed + shape[1], trimmed + "_Planks" + shape[1]}) {
                    if (registry.exists(candidate)) {
                        return new Resolution(candidate, Status.AUTO, turn[0], turn[1], turn[2], "shape of " + target);
                    }
                }
            }
            return new Resolution(target, Status.AUTO, turn[0], turn[1], turn[2],
                    full ? "double slab" : "no Hytale " + shape[0].substring(1) + " shape, full block used");
        }
        return null;
    }

    private String materialTarget(String ns, String material) {
        for (String candidate : new String[]{material, material + "s", material + "_planks", material + "_block",
                material.replace("_brick", "_bricks")}) {
            String full = ns + candidate;
            String rule = custom.find(full, full);
            if (rule == null) {
                rule = defaults.find(full, full);
            }
            if (rule != null && !isSkip(rule) && registry.exists(rule)) {
                return rule;
            }
        }
        return null;
    }

    private Resolution unmapped(Status why, String note) {
        if ("fallback".equalsIgnoreCase(settings.unmappedAction) && registry.exists(settings.fallbackBlock)) {
            return new Resolution(settings.fallbackBlock, Status.FALLBACK, 0, 0, 0, note);
        }
        return new Resolution(null, why, 0, 0, 0, note);
    }

    private int[] rotation(String state, String base) {
        if (!settings.rotateBlocks) {
            return new int[3];
        }
        int yaw = 0;
        int pitch = 0;
        int roll = 0;
        String facing = Schematic.property(state, "facing");
        if (facing != null && settings.facingYaw.containsKey(facing)) {
            yaw = settings.facingYaw.get(facing) + settings.yawOffset;
        }
        String axis = Schematic.property(state, "axis");
        if ("x".equals(axis)) {
            roll = 1;
        } else if ("z".equals(axis)) {
            pitch = 1;
        }
        // Upside-down stairs: flip over and turn round so the steps still face the same way.
        if (base.endsWith("_stairs") && "top".equals(Schematic.property(state, "half"))) {
            pitch = 2;
            yaw += 2;
        }
        return new int[]{Math.floorMod(yaw, 4), pitch, roll};
    }

    private static boolean isSkip(String target) {
        return target.equalsIgnoreCase(MappingRules.SKIP) || target.equalsIgnoreCase("air");
    }
}
