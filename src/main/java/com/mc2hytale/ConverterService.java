package com.mc2hytale;

import com.mc2hytale.convert.ConversionPlan;
import com.mc2hytale.mapping.BlockMapper;
import com.mc2hytale.mapping.BlockRegistry;
import com.mc2hytale.mapping.MappingRules;
import com.mc2hytale.mapping.Settings;
import com.mc2hytale.schematic.Schematic;
import com.mc2hytale.schematic.SchematicLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Owns the plugin's data folder and the current mapping state:
 * <pre>
 * schematics/   drop .schem / .schematic / .nbt / .litematic files here
 * profiles/     mapping profiles; the active one is set in config.json
 * reports/      block list CSVs written by scan and convert
 * config.json   settings
 * </pre>
 * Conversions run one at a time on a background thread, so a big build never stalls the server.
 */
public final class ConverterService {

    private final Path root;
    private final BlockRegistry registry;
    private final MappingRules defaults;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MC2Hytale-converter");
        t.setDaemon(true);
        return t;
    });

    private Settings settings;
    private MappingRules profile;

    public ConverterService(Path root, BlockRegistry registry) throws IOException {
        this.root = root;
        this.registry = registry;
        try (InputStream in = ConverterService.class.getResourceAsStream("/default-mappings.json")) {
            if (in == null) {
                throw new IOException("default-mappings.json missing from the jar");
            }
            defaults = MappingRules.parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        Files.createDirectories(schematicsDir());
        Files.createDirectories(root.resolve("profiles"));
        Files.createDirectories(root.resolve("reports"));
        reload();
    }

    public synchronized void reload() throws IOException {
        settings = Settings.load(root.resolve("config.json"));
        Path file = profileFile(settings.profile);
        if (!Files.exists(file)) {
            new MappingRules().save(file);
        }
        profile = MappingRules.load(file);
    }

    public void shutdown() {
        worker.shutdownNow();
    }

    public ExecutorService worker() {
        return worker;
    }

    public Path schematicsDir() {
        return root.resolve("schematics");
    }

    public Path reportFile(String name) {
        return root.resolve("reports").resolve(name + ".csv");
    }

    public synchronized Settings settings() {
        return settings;
    }

    public synchronized MappingRules profile() {
        return profile;
    }

    public synchronized String profileName() {
        return settings.profile;
    }

    public MappingRules defaults() {
        return defaults;
    }

    public BlockRegistry registry() {
        return registry;
    }

    /** A fresh mapper for the current rules (each conversion gets its own cache). */
    public synchronized BlockMapper mapper() {
        return new BlockMapper(profile, defaults, registry, settings);
    }

    public List<String> listSchematics() throws IOException {
        try (Stream<Path> files = Files.walk(schematicsDir(), 3)) {
            return files.filter(Files::isRegularFile)
                    .map(p -> schematicsDir().relativize(p).toString().replace('\\', '/'))
                    .filter(SchematicLoader::isSupportedFile)
                    .sorted()
                    .toList();
        }
    }

    /** Finds a schematic by name, with or without extension; refuses paths outside the schematics folder. */
    public Path findSchematic(String name) throws IOException {
        Path dir = schematicsDir().toAbsolutePath().normalize();
        for (String candidate : new String[]{name, name + ".schem", name + ".schematic", name + ".nbt", name + ".litematic"}) {
            Path p = dir.resolve(candidate).normalize();
            if (p.startsWith(dir) && Files.isRegularFile(p)) {
                return p;
            }
        }
        throw new IOException("No schematic called " + name + " in " + schematicsDir().getFileName() + "/ (try /mc2h list)");
    }

    public Schematic load(String name) throws IOException {
        return SchematicLoader.load(findSchematic(name));
    }

    public ConversionPlan plan(Schematic schematic) {
        return new ConversionPlan(schematic, mapper());
    }

    /** Adds or replaces a rule in the active profile and saves it. */
    public synchronized void putRule(String source, String target) throws IOException {
        profile.put(source, target);
        profile.save(profileFile(settings.profile));
    }

    public synchronized boolean removeRule(String source) throws IOException {
        boolean removed = profile.remove(source);
        if (removed) {
            profile.save(profileFile(settings.profile));
        }
        return removed;
    }

    /** Switches profile (creating an empty one if new). */
    public synchronized void useProfile(String name) throws IOException {
        settings.profile = safeName(name);
        settings.save(root.resolve("config.json"));
        reload();
    }

    public List<String> listProfiles() throws IOException {
        try (Stream<Path> files = Files.list(root.resolve("profiles"))) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".json"))
                    .map(n -> n.substring(0, n.length() - 5))
                    .sorted()
                    .toList();
        }
    }

    private Path profileFile(String name) {
        return root.resolve("profiles").resolve(safeName(name) + ".json");
    }

    /** A file-name-safe version of a user-typed name ("My House.schem" → "my_house"). */
    public static String safeName(String name) {
        String n = name.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1);
        int dot = n.indexOf('.');
        if (dot > 0) {
            n = n.substring(0, dot);
        }
        n = n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "_").replaceAll("^_+|_+$", "");
        return n.isEmpty() ? "converted" : n;
    }
}
