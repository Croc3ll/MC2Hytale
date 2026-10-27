package com.mc2hytale.schematic;

/**
 * Turns pre-1.13 numeric block ids and data values (MCEdit {@code .schematic} files) into modern
 * block state strings, so legacy and Sponge files share one set of mapping rules.
 *
 * <p>Covers the vanilla 1.12 block list. Ids it doesn't know come out as {@code legacy:<id>:<data>},
 * which can still be mapped by hand.
 */
final class LegacyIds {

    private LegacyIds() {}

    private static final String[] NAMES = new String[256];
    private static final String[] COLORS = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
    private static final String[] WOODS = {"oak", "spruce", "birch", "jungle", "acacia", "dark_oak"};
    private static final String[] STAIR_FACING = {"east", "west", "south", "north"};
    private static final String[] FACING_2_5 = {"north", "north", "north", "south", "west", "east"};

    static {
        String table = """
                0 air|1 stone|2 grass_block|3 dirt|4 cobblestone|5 planks|6 sapling|7 bedrock|8 water|9 water|10 lava|11 lava
                12 sand|13 gravel|14 gold_ore|15 iron_ore|16 coal_ore|17 log|18 leaves|19 sponge|20 glass|21 lapis_ore
                22 lapis_block|23 dispenser|24 sandstone|25 note_block|26 red_bed|27 powered_rail|28 detector_rail
                29 sticky_piston|30 cobweb|31 grass|32 dead_bush|33 piston|34 piston_head|35 wool|37 dandelion|38 poppy
                39 brown_mushroom|40 red_mushroom|41 gold_block|42 iron_block|43 double_slab|44 slab|45 bricks|46 tnt
                47 bookshelf|48 mossy_cobblestone|49 obsidian|50 torch|51 fire|52 spawner|53 oak_stairs|54 chest
                55 redstone_wire|56 diamond_ore|57 diamond_block|58 crafting_table|59 wheat|60 farmland|61 furnace
                62 furnace|63 oak_sign|64 oak_door|65 ladder|66 rail|67 cobblestone_stairs|68 oak_wall_sign|69 lever
                70 stone_pressure_plate|71 iron_door|72 oak_pressure_plate|73 redstone_ore|74 redstone_ore
                75 redstone_torch|76 redstone_torch|77 stone_button|78 snow|79 ice|80 snow_block|81 cactus|82 clay
                83 sugar_cane|84 jukebox|85 oak_fence|86 carved_pumpkin|87 netherrack|88 soul_sand|89 glowstone
                90 nether_portal|91 jack_o_lantern|92 cake|93 repeater|94 repeater|95 stained_glass|96 oak_trapdoor
                97 infested_stone|98 stone_bricks|99 brown_mushroom_block|100 red_mushroom_block|101 iron_bars
                102 glass_pane|103 melon|104 pumpkin_stem|105 melon_stem|106 vine|107 oak_fence_gate|108 brick_stairs
                109 stone_brick_stairs|110 mycelium|111 lily_pad|112 nether_bricks|113 nether_brick_fence
                114 nether_brick_stairs|115 nether_wart|116 enchanting_table|117 brewing_stand|118 cauldron
                119 end_portal|120 end_portal_frame|121 end_stone|122 dragon_egg|123 redstone_lamp|124 redstone_lamp
                125 double_wooden_slab|126 wooden_slab|127 cocoa|128 sandstone_stairs|129 emerald_ore|130 ender_chest
                131 tripwire_hook|132 tripwire|133 emerald_block|134 spruce_stairs|135 birch_stairs|136 jungle_stairs
                137 command_block|138 beacon|139 cobblestone_wall|140 flower_pot|141 carrots|142 potatoes
                143 oak_button|144 skeleton_skull|145 anvil|146 trapped_chest|147 light_weighted_pressure_plate
                148 heavy_weighted_pressure_plate|149 comparator|150 comparator|151 daylight_detector|152 redstone_block
                153 nether_quartz_ore|154 hopper|155 quartz_block|156 quartz_stairs|157 activator_rail|158 dropper
                159 terracotta|160 stained_glass_pane|161 leaves2|162 log2|163 acacia_stairs|164 dark_oak_stairs
                165 slime_block|166 barrier|167 iron_trapdoor|168 prismarine|169 sea_lantern|170 hay_block|171 carpet
                172 terracotta|173 coal_block|174 packed_ice|175 tall_flower|176 white_banner|177 white_wall_banner
                178 daylight_detector|179 red_sandstone|180 red_sandstone_stairs|181 double_stone_slab2|182 stone_slab2
                183 spruce_fence_gate|184 birch_fence_gate|185 jungle_fence_gate|186 dark_oak_fence_gate
                187 acacia_fence_gate|188 spruce_fence|189 birch_fence|190 jungle_fence|191 dark_oak_fence
                192 acacia_fence|193 spruce_door|194 birch_door|195 jungle_door|196 acacia_door|197 dark_oak_door
                198 end_rod|199 chorus_plant|200 chorus_flower|201 purpur_block|202 purpur_pillar|203 purpur_stairs
                204 purpur_double_slab|205 purpur_slab|206 end_stone_bricks|207 beetroots|208 dirt_path|209 end_gateway
                210 repeating_command_block|211 chain_command_block|212 frosted_ice|213 magma_block
                214 nether_wart_block|215 red_nether_bricks|216 bone_block|217 structure_void|218 observer
                219 white_shulker_box|235 white_glazed_terracotta|251 concrete|252 concrete_powder|255 structure_block""";
        for (String part : table.replace('\n', '|').split("\\|")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            int sp = p.indexOf(' ');
            NAMES[Integer.parseInt(p.substring(0, sp))] = p.substring(sp + 1);
        }
    }

    static String toState(int id, int data) {
        String name = id >= 0 && id < NAMES.length ? NAMES[id] : null;
        if (name == null) {
            return "legacy:" + id + ":" + data;
        }
        String color = COLORS[data & 0xF];
        String mc = switch (id) {
            case 1 -> switch (data) {
                case 1 -> "granite"; case 2 -> "polished_granite"; case 3 -> "diorite"; case 4 -> "polished_diorite";
                case 5 -> "andesite"; case 6 -> "polished_andesite"; default -> "stone";
            };
            case 3 -> data == 1 ? "coarse_dirt" : data == 2 ? "podzol" : "dirt";
            case 5 -> wood(data) + "_planks";
            case 6 -> wood(data & 7) + "_sapling";
            case 12 -> data == 1 ? "red_sand" : "sand";
            case 17 -> WOODS[data & 3] + "_log" + axis(data);
            case 162 -> WOODS[4 + (data & 1)] + "_log" + axis(data);
            case 18 -> WOODS[data & 3] + "_leaves";
            case 161 -> WOODS[4 + (data & 1)] + "_leaves";
            case 24 -> data == 1 ? "chiseled_sandstone" : data == 2 ? "cut_sandstone" : "sandstone";
            case 179 -> data == 1 ? "chiseled_red_sandstone" : data == 2 ? "cut_red_sandstone" : "red_sandstone";
            case 35 -> color + "_wool";
            case 95 -> color + "_stained_glass";
            case 160 -> color + "_stained_glass_pane";
            case 159 -> color + "_terracotta";
            case 171 -> color + "_carpet";
            case 251 -> color + "_concrete";
            case 252 -> color + "_concrete_powder";
            case 98 -> switch (data) {
                case 1 -> "mossy_stone_bricks"; case 2 -> "cracked_stone_bricks"; case 3 -> "chiseled_stone_bricks";
                default -> "stone_bricks";
            };
            case 43, 44 -> slab(data & 7) + (id == 44 ? "[type=" + ((data & 8) != 0 ? "top" : "bottom") + "]" : "[type=double]");
            case 125, 126 -> wood(data & 7) + "_slab" + (id == 126 ? "[type=" + ((data & 8) != 0 ? "top" : "bottom") + "]" : "[type=double]");
            case 155 -> data == 1 ? "chiseled_quartz_block" : data >= 2 ? "quartz_pillar" : "quartz_block";
            case 168 -> data == 1 ? "prismarine_bricks" : data == 2 ? "dark_prismarine" : "prismarine";
            case 38 -> switch (data) {
                case 1 -> "blue_orchid"; case 2 -> "allium"; case 3 -> "azure_bluet"; case 4 -> "red_tulip";
                case 5 -> "orange_tulip"; case 6 -> "white_tulip"; case 7 -> "pink_tulip"; case 8 -> "oxeye_daisy";
                default -> "poppy";
            };
            case 31 -> data == 2 ? "fern" : data == 0 ? "dead_bush" : "short_grass";
            case 139 -> data == 1 ? "mossy_cobblestone_wall" : "cobblestone_wall";
            case 8, 9 -> "water";
            case 10, 11 -> "lava";
            case 53, 67, 108, 109, 114, 128, 134, 135, 136, 156, 163, 164, 180, 203 ->
                    name + "[facing=" + STAIR_FACING[data & 3] + ",half=" + ((data & 4) != 0 ? "top" : "bottom") + "]";
            case 54, 61, 62, 65, 68, 130, 146 -> name + "[facing=" + FACING_2_5[Math.min(data & 7, 5)] + "]";
            default -> name;
        };
        return "minecraft:" + mc;
    }

    private static String wood(int data) {
        return WOODS[Math.min(Math.max(data, 0), 5)];
    }

    private static String axis(int data) {
        return switch ((data >> 2) & 3) {
            case 1 -> "[axis=x]";
            case 2 -> "[axis=z]";
            default -> "[axis=y]";
        };
    }

    private static String slab(int data) {
        return switch (data) {
            case 1 -> "sandstone_slab"; case 2 -> "petrified_oak_slab"; case 3 -> "cobblestone_slab";
            case 4 -> "brick_slab"; case 5 -> "stone_brick_slab"; case 6 -> "nether_brick_slab";
            case 7 -> "quartz_slab"; default -> "smooth_stone_slab";
        };
    }
}
