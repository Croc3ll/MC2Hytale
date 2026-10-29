package com.mc2hytale.mapping;

import java.util.Collection;
import java.util.Set;

/** The Hytale block ids a conversion may target. On the server this is the live block asset map. */
public interface BlockRegistry {

    boolean exists(String hytaleId);

    Collection<String> all();

    /**
     * The block key for one of a block's states ({@code OpenDoorOut}, {@code Corner_Left}, ...), or null
     * when the block has no such state. Offline registries know no states.
     */
    default String stateKey(String hytaleId, String state) {
        return null;
    }

    /** A registry backed by a fixed list (offline checks, tests). */
    static BlockRegistry of(Set<String> ids) {
        return new BlockRegistry() {
            @Override
            public boolean exists(String hytaleId) {
                return ids.contains(hytaleId);
            }

            @Override
            public Collection<String> all() {
                return ids;
            }
        };
    }
}
