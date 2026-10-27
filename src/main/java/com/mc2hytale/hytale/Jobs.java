package com.mc2hytale.hytale;

import com.hypixel.hytale.server.core.prefab.selection.standard.BlockSelection;
import com.hypixel.hytale.server.core.universe.world.World;
import com.mc2hytale.ConverterService;
import com.mc2hytale.convert.ConversionPlan;
import com.mc2hytale.schematic.Schematic;
import org.joml.Vector3i;

import java.io.IOException;
import java.nio.file.Path;

/** Convert and paste, shared by the commands and the converter window. Call from the converter thread. */
public final class Jobs {

    private Jobs() {}

    /** Maps, builds and saves one schematic as a prefab, and writes its CSV report. Returns the saved file. */
    public static Path convert(ConverterService service, String file, ConversionPlan plan) throws IOException {
        String prefab = ConverterService.safeName(file);
        plan.writeCsv(service.reportFile(prefab));
        BlockSelection sel = PrefabExporter.build(plan, service.settings().includeAir);
        return PrefabExporter.save(prefab, sel, true);
    }

    /**
     * Builds the selection here and places it on the world thread. {@code done} runs on the world thread after.
     * Throws if the build is over the paste size limit.
     */
    public static void paste(ConverterService service, ConversionPlan plan, World world, Vector3i at, Runnable done) throws IOException {
        Schematic s = plan.schematic();
        if (s.volume() > service.settings().pasteMaxVolume) {
            throw new IOException("Too big to paste directly (" + s.volume() + " cells, limit "
                    + service.settings().pasteMaxVolume + "). Convert it and paste the prefab instead.");
        }
        BlockSelection sel = PrefabExporter.build(plan, service.settings().includeAir);
        world.execute(() -> {
            sel.placeNoReturn(world, at, world.getEntityStore().getStore());
            done.run();
        });
    }
}
