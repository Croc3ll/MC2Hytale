package com.mc2hytale.hytale;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.mc2hytale.mapping.BlockRegistry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** The live Hytale block and fluid lists, so rules are checked against what this server actually has. */
public final class HytaleRegistry implements BlockRegistry {

    public static final String FLUID_PREFIX = "fluid:";

    @Override
    public boolean exists(String id) {
        if (id.startsWith(FLUID_PREFIX)) {
            return fluidIndex(id) != Integer.MIN_VALUE;
        }
        return blockIndex(id) != Integer.MIN_VALUE;
    }

    @Override
    public Collection<String> all() {
        List<String> ids = new ArrayList<>(BlockType.getAssetMap().getAssetMap().keySet());
        ids.sort(String::compareTo);
        return ids;
    }

    static int blockIndex(String id) {
        return BlockType.getAssetMap().getIndex(id);
    }

    static int fluidIndex(String id) {
        return Fluid.getAssetMap().getIndex(id.substring(FLUID_PREFIX.length()));
    }
}
