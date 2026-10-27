package com.mc2hytale.hytale;

import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.prefab.PrefabStore;
import com.hypixel.hytale.server.core.prefab.selection.standard.BlockSelection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.mc2hytale.convert.ConversionPlan;
import com.mc2hytale.mapping.BlockMapper.Resolution;
import com.mc2hytale.mapping.BlockMapper.Status;
import com.mc2hytale.schematic.Schematic;

import java.nio.file.Path;

/** Turns a {@link ConversionPlan} into a Hytale {@link BlockSelection} and saves it with the server's own prefab writer. */
public final class PrefabExporter {

    /** Server prefabs written by this plugin go in this sub-folder of the server's prefab directory. */
    public static final String FOLDER = "mc2hytale";

    private PrefabExporter() {}

    /** Builds the selection. Anchor is the bottom centre, so pasting puts the build's base where you stand. */
    public static BlockSelection build(ConversionPlan plan, boolean includeAir) {
        Schematic s = plan.schematic();
        int paletteSize = s.palette().size();
        int[] blockIds = new int[paletteSize];
        int[] rotations = new int[paletteSize];
        int[] fluidIds = new int[paletteSize];
        byte[] fluidLevels = new byte[paletteSize];
        @SuppressWarnings("unchecked")
        Holder<ChunkStore>[] entities = new Holder[paletteSize];
        int empty = BlockType.getAssetMap().getIndex("Empty");
        int water = HytaleRegistry.fluidIndex(HytaleRegistry.FLUID_PREFIX + "Water_Source");
        Fluid waterFluid = water == Integer.MIN_VALUE ? null : Fluid.getAssetMap().getAsset(water);

        for (int i = 0; i < paletteSize; i++) {
            Resolution r = plan.resolution(i);
            blockIds[i] = Integer.MIN_VALUE;
            fluidIds[i] = Integer.MIN_VALUE;
            // Waterlogged stairs, slabs, plants etc.: the block plus water in the same cell.
            if (waterFluid != null && "true".equals(Schematic.property(s.palette().get(i), "waterlogged"))) {
                fluidIds[i] = water;
                fluidLevels[i] = (byte) Math.max(1, waterFluid.getMaxFluidLevel());
            }
            if (!r.places()) {
                if (includeAir && r.status() == Status.AIR && empty != Integer.MIN_VALUE) {
                    blockIds[i] = empty;
                }
                continue;
            }
            if (r.target().startsWith(HytaleRegistry.FLUID_PREFIX)) {
                int fid = HytaleRegistry.fluidIndex(r.target());
                Fluid fluid = fid == Integer.MIN_VALUE ? null : Fluid.getAssetMap().getAsset(fid);
                if (fluid != null) {
                    fluidIds[i] = fid;
                    fluidLevels[i] = (byte) Math.max(1, fluid.getMaxFluidLevel());
                }
                continue;
            }
            int id = HytaleRegistry.blockIndex(r.target());
            if (id == Integer.MIN_VALUE) {
                continue;
            }
            blockIds[i] = id;
            rotations[i] = RotationTuple.index(Rotation.VALUES[r.yaw() & 3], Rotation.VALUES[r.pitch() & 3], Rotation.VALUES[r.roll() & 3]);
            BlockType type = BlockType.getAssetMap().getAsset(id);
            entities[i] = type == null ? null : type.getBlockEntity();
        }

        BlockSelection sel = new BlockSelection();
        for (int y = 0; y < s.height(); y++) {
            for (int z = 0; z < s.length(); z++) {
                for (int x = 0; x < s.width(); x++) {
                    int p = s.at(x, y, z);
                    if (p < 0) {
                        continue;
                    }
                    if (blockIds[p] != Integer.MIN_VALUE) {
                        // Each chest, furnace etc. needs its own block entity, or it looks right but doesn't work.
                        Holder<ChunkStore> entity = entities[p] == null ? null : entities[p].clone();
                        sel.addBlockAtLocalPos(x, y, z, blockIds[p], rotations[p], 0, 0, entity);
                    }
                    if (fluidIds[p] != Integer.MIN_VALUE) {
                        sel.addFluidAtLocalPos(x, y, z, fluidIds[p], fluidLevels[p]);
                    }
                }
            }
        }
        sel.setAnchor(s.width() / 2, 0, s.length() / 2);
        return sel;
    }

    /** Saves under {@code <server prefabs>/mc2hytale/<name>.prefab.json}; returns the file written. */
    public static Path save(String name, BlockSelection sel, boolean overwrite) {
        PrefabStore store = PrefabStore.get();
        Path file = store.getServerPrefabsPath().resolve(FOLDER).resolve(name + ".prefab.json");
        return store.savePrefab(file, sel, overwrite);
    }
}
