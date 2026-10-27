package com.mc2hytale.mapping;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * An ordered set of "Minecraft block → Hytale block" rules, as stored in a mapping profile file:
 * <pre>{@code
 * { "rules": {
 *     "minecraft:stone": "Rock_Stone",
 *     "minecraft:oak_stairs[half=top]": "Wood_Softwood_Stairs",
 *     "minecraft:*_wool": "Cloth_Block_Wool_White",
 *     "minecraft:command_block": "skip"
 * } }
 * }</pre>
 * Keys can be a full block state, a bare id, or a pattern with {@code *}. The target {@code skip}
 * (or {@code air}) leaves the cell empty.
 */
public final class MappingRules {

    public static final String SKIP = "skip";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Map<String, String> exact = new LinkedHashMap<>();
    private final List<Wildcard> wildcards = new ArrayList<>();

    private record Wildcard(String key, Pattern pattern, String target) {}

    public static MappingRules load(Path file) throws IOException {
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(r);
        }
    }

    public static MappingRules parse(Reader reader) {
        MappingRules rules = new MappingRules();
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        JsonObject map = root.has("rules") ? root.getAsJsonObject("rules") : root;
        map.entrySet().forEach(e -> {
            if (!e.getKey().startsWith("_")) {
                rules.put(e.getKey(), e.getValue().getAsString());
            }
        });
        return rules;
    }

    public void save(Path file) throws IOException {
        Map<String, String> all = new LinkedHashMap<>(exact);
        wildcards.forEach(w -> all.put(w.key, w.target));
        JsonObject root = new JsonObject();
        root.add("rules", GSON.toJsonTree(all));
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
    }

    public void put(String source, String target) {
        String key = normalise(source);
        remove(key);
        if (key.contains("*")) {
            wildcards.add(new Wildcard(key, glob(key), target));
            // Most specific (longest) pattern first, so "minecraft:red_*" beats "minecraft:*".
            wildcards.sort(Comparator.comparingInt((Wildcard w) -> w.key.replace("*", "").length()).reversed());
        } else {
            exact.put(key, target);
        }
    }

    public boolean remove(String source) {
        String key = normalise(source);
        return exact.remove(key) != null | wildcards.removeIf(w -> w.key.equals(key));
    }

    /** The target for a full state, trying the exact state, then the bare id, then patterns. Null if no rule. */
    public String find(String state, String baseId) {
        String t = exact.get(state);
        if (t == null) {
            t = exact.get(baseId);
        }
        if (t == null) {
            for (Wildcard w : wildcards) {
                if (w.pattern.matcher(baseId).matches() || w.pattern.matcher(state).matches()) {
                    return w.target;
                }
            }
        }
        return t;
    }

    public Map<String, String> asMap() {
        Map<String, String> all = new LinkedHashMap<>(exact);
        wildcards.forEach(w -> all.put(w.key, w.target));
        return all;
    }

    public int size() {
        return exact.size() + wildcards.size();
    }

    /** Lets users type {@code stone} for {@code minecraft:stone}. */
    public static String normalise(String source) {
        String s = source.trim().toLowerCase();
        return s.contains(":") ? s : "minecraft:" + s;
    }

    private static Pattern glob(String key) {
        StringBuilder sb = new StringBuilder();
        for (String part : key.split("\\*", -1)) {
            if (!sb.isEmpty()) {
                sb.append(".*");
            }
            sb.append(Pattern.quote(part));
        }
        return Pattern.compile(sb.toString());
    }
}
