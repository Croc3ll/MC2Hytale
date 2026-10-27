package com.mc2hytale;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.mc2hytale.command.MC2HytaleCommand;
import com.mc2hytale.hytale.HytaleRegistry;

import javax.annotation.Nonnull;
import java.io.IOException;

/**
 * Minecraft → Hytale converter. Drop schematics into {@code mods/MC2Hytale_MC2Hytale/schematics/},
 * then use {@code /mc2h scan}, {@code /mc2h map} and {@code /mc2h convert}.
 */
public class MC2HytalePlugin extends JavaPlugin {

    private static MC2HytalePlugin instance;
    private ConverterService service;

    public MC2HytalePlugin(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
    }

    public static MC2HytalePlugin getInstance() {
        return instance;
    }

    public ConverterService service() {
        return service;
    }

    @Override
    protected void setup() {
        try {
            service = new ConverterService(getDataDirectory(), new HytaleRegistry());
        } catch (IOException e) {
            throw new IllegalStateException("MC2Hytale could not create its data folder: " + e.getMessage(), e);
        }
        getCommandRegistry().registerCommand(new MC2HytaleCommand());
        getLogger().atInfo().log("MC2Hytale ready. Put schematics in %s", service.schematicsDir());
    }

    @Override
    protected void shutdown() {
        if (service != null) {
            service.shutdown();
        }
    }
}
