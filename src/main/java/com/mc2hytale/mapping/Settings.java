package com.mc2hytale.mapping;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** {@code config.json} in the plugin's data folder. Missing fields keep these defaults. */
public final class Settings {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Mapping profile in use (a file in {@code profiles/}, without {@code .json}). */
    public String profile = "default";

    /** What to do with blocks no rule or smart match covers: {@code skip} or {@code fallback}. */
    public String unmappedAction = "skip";

    /** Block placed for unmapped blocks when {@code unmappedAction} is {@code fallback}. */
    public String fallbackBlock = "Rock_Stone";

    /** Write Minecraft air into the prefab as empty blocks (clears space when pasted) instead of leaving it out. */
    public boolean includeAir = false;

    /** Try {@code _Stairs}, {@code _Half}, {@code _Wall}, {@code _Fence} variants of a mapped material for shaped blocks. */
    public boolean smartShapes = true;

    /** Stairs become their material's {@code _Roof} block (Minecraft roofs are mostly stairs). Upside-down stairs stay stairs. */
    public boolean stairsAsRoofs = true;

    /**
     * Hytale trapdoors close against the top of their block. Turn bottom-half Minecraft trapdoors upside down
     * so they lie on the floor like they did in Minecraft, instead of floating at the top of the cell.
     */
    public boolean flipBottomTrapdoors = true;

    /** Turn blocks to match their Minecraft {@code facing} / {@code axis} properties. */
    public boolean rotateBlocks = true;

    /**
     * Quarter turns of yaw for each Minecraft facing. Hytale models don't all face the same way, so
     * change these (or {@code yawOffset}) if converted stairs and furniture come out turned.
     */
    public Map<String, Integer> facingYaw = new LinkedHashMap<>(Map.of("north", 0, "west", 1, "south", 2, "east", 3));

    /** Extra quarter turns added to every rotated block. */
    public int yawOffset = 0;

    /** Largest build {@code /mc2h paste} will place directly in the world (cells). */
    public long pasteMaxVolume = 2_000_000;

    public static Settings load(Path file) throws IOException {
        Settings s = null;
        if (Files.exists(file)) {
            s = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Settings.class);
        }
        if (s == null) {
            s = new Settings();
        }
        if (s.facingYaw == null) {
            s.facingYaw = new Settings().facingYaw;
        }
        s.save(file);
        return s;
    }

    public void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
    }
}
