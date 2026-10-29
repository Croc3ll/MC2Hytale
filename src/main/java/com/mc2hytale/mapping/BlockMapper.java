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
        // Not computeIfAbsent: compute() can resolve another state (stripped logs), which that would reject.
        Resolution r = cache.get(state);
        if (r == null) {
            r = withState(state, Schematic.baseId(state), compute(state));
            cache.put(state, r);
        }
        return r;
    }

    /**
     * Swaps in the Hytale block state that matches the Minecraft one: open trapdoors, and the corner
     * pieces of stairs and roofs. Blocks without that state stay as they are.
     */
    private Resolution withState(String state, String base, Resolution r) {
        if (!r.places() || r.target().startsWith("fluid:")) {
            return r;
        }
        String wanted = null;
        if (base.endsWith("_trapdoor") && "true".equals(Schematic.property(state, "open"))) {
            wanted = "OpenDoorOut";
        } else if (base.endsWith("_stairs")) {
            String shape = Schematic.property(state, "shape");
            if (shape != null && !shape.equals("straight")) {
                // Upside-down stairs are flipped and turned round, which mirrors left and right.
                boolean top = "top".equals(Schematic.property(state, "half"));
                boolean left = shape.endsWith("_left") != top;
                wanted = (shape.startsWith("inner") ? "Inverted_Corner_" : "Corner_") + (left ? "Left" : "Right");
            }
        }
        String key = wanted == null ? null : registry.stateKey(r.target(), wanted);
        if (key == null) {
            return r;
        }
        String note = r.note().isEmpty() ? wanted : r.note() + ", " + wanted;
        return new Resolution(key, r.status(), r.yaw(), r.pitch(), r.roll(), note);
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
        // Stripped logs and wood look close enough to the bark-covered kind.
        if (base.contains(":stripped_")) {
            Resolution plain = resolve(state.replace(":stripped_", ":"));
            if (plain.places()) {
                return new Resolution(plain.target(), Status.AUTO, plain.yaw(), plain.pitch(), plain.roll(), "as unstripped");
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
                // Minecraft roofs are mostly stairs. Roofs have no upside-down form, so those stay stairs.
                boolean roof = shape[0].equals("_stairs") && settings.stairsAsRoofs
                        && !"top".equals(Schematic.property(state, "half"));
                for (String suffix : roof ? new String[]{"_Roof", shape[1]} : new String[]{shape[1]}) {
                    Resolution found = shapeOf(target, suffix, turn);
                    if (found != null) {
                        return found;
                    }
                }
            }
            return new Resolution(target, Status.AUTO, turn[0], turn[1], turn[2],
                    full ? "double slab" : "no Hytale " + shape[0].substring(1) + " shape, full block used");
        }
        return null;
    }

    /** {@code Wood_Softwood_Planks} + {@code _Roof} → {@code Wood_Softwood_Roof}; null if there's no such block. */
    private Resolution shapeOf(String target, String suffix, int[] turn) {
        String trimmed = target.endsWith("_Planks") ? target.substring(0, target.length() - 7) : target;
        for (String candidate : new String[]{target + suffix, trimmed + suffix, trimmed + "_Planks" + suffix}) {
            if (registry.exists(candidate)) {
                return new Resolution(candidate, Status.AUTO, turn[0], turn[1], turn[2], "shape of " + target);
            }
        }
        // No shape for this exact block: try its family, e.g. Rock_Slate_Brick_Smooth → Rock_Slate_Brick_Stairs.
        String family = trimmed;
        while (family.indexOf('_') != family.lastIndexOf('_')) {
            family = family.substring(0, family.lastIndexOf('_'));
            if (registry.exists(family + suffix)) {
                return new Resolution(family + suffix, Status.AUTO, turn[0], turn[1], turn[2], "nearest shape to " + target);
            }
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
        if (base.endsWith("_trapdoor")) {
            return trapdoorTurn(state, facing);
        }
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

    /**
     * Hytale's trapdoor (yaw 0) closes against the top of its block, hinged on the north edge, and opens
     * down flat against the north side. A Minecraft trapdoor is hinged on the side opposite its
     * {@code facing}, and a bottom-half one lies on the floor. So the hinge turns to the side opposite
     * {@code facing}, and bottom-half ones are flipped upside down (which puts the hinge on the south edge).
     */
    private int[] trapdoorTurn(String state, String facing) {
        if (facing == null || !settings.facingYaw.containsKey(facing)) {
            return new int[3];
        }
        boolean flip = settings.flipBottomTrapdoors && !"top".equals(Schematic.property(state, "half"));
        int yaw = settings.facingYaw.get(facing) + settings.yawOffset + (flip ? 0 : 2);
        return new int[]{Math.floorMod(yaw, 4), flip ? 2 : 0, 0};
    }

    private static boolean isSkip(String target) {
        return target.equalsIgnoreCase(MappingRules.SKIP) || target.equalsIgnoreCase("air");
    }
}
