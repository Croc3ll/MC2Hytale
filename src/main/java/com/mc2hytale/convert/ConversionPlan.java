package com.mc2hytale.convert;

import com.mc2hytale.mapping.BlockMapper;
import com.mc2hytale.mapping.BlockMapper.Resolution;
import com.mc2hytale.mapping.BlockMapper.Status;
import com.mc2hytale.schematic.Schematic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The result of running a schematic's palette through the mapper: what every Minecraft block becomes,
 * how many of each there are, and the totals by status. Building it changes nothing, so it doubles as
 * the dry run behind {@code /mc2h scan}.
 */
public final class ConversionPlan {

    /** One distinct Minecraft block (by bare id) and where it goes. */
    public record Entry(String source, long count, Resolution resolution, List<String> states) {}

    private final Schematic schematic;
    private final Resolution[] byPaletteIndex;
    private final List<Entry> entries;
    private final Map<Status, Long> totals = new EnumMap<>(Status.class);

    public ConversionPlan(Schematic schematic, BlockMapper mapper) {
        this.schematic = schematic;
        List<String> palette = schematic.palette();
        byPaletteIndex = new Resolution[palette.size()];
        for (int i = 0; i < byPaletteIndex.length; i++) {
            byPaletteIndex[i] = mapper.resolve(palette.get(i));
        }

        long[] counts = new long[palette.size()];
        for (int c : schematic.cells()) {
            if (c >= 0) {
                counts[c]++;
            }
        }

        // Group states of the same block ("oak_stairs[facing=north]", "oak_stairs[facing=east]") into one row.
        Map<String, long[]> rowCounts = new LinkedHashMap<>();
        Map<String, Resolution> rowRes = new LinkedHashMap<>();
        Map<String, List<String>> rowStates = new LinkedHashMap<>();
        for (int i = 0; i < palette.size(); i++) {
            if (counts[i] == 0) {
                continue;
            }
            String base = Schematic.baseId(palette.get(i));
            rowCounts.computeIfAbsent(base, k -> new long[1])[0] += counts[i];
            Resolution existing = rowRes.get(base);
            // Show the "worst" outcome for the row, so one unmapped state isn't hidden behind mapped ones.
            if (existing == null || byPaletteIndex[i].status().ordinal() > existing.status().ordinal()) {
                rowRes.put(base, byPaletteIndex[i]);
            }
            rowStates.computeIfAbsent(base, k -> new ArrayList<>()).add(palette.get(i));
            totals.merge(byPaletteIndex[i].status(), counts[i], Long::sum);
        }
        List<Entry> list = new ArrayList<>();
        rowCounts.forEach((base, n) -> list.add(new Entry(base, n[0], rowRes.get(base), rowStates.get(base))));
        list.sort(Comparator.comparingLong(Entry::count).reversed());
        entries = List.copyOf(list);
    }

    public Schematic schematic() {
        return schematic;
    }

    public Resolution resolution(int paletteIndex) {
        return byPaletteIndex[paletteIndex];
    }

    public List<Entry> entries() {
        return entries;
    }

    public long total(Status status) {
        return totals.getOrDefault(status, 0L);
    }

    /** Blocks that will be placed. */
    public long placed() {
        return total(Status.CUSTOM) + total(Status.DEFAULT) + total(Status.AUTO) + total(Status.FALLBACK);
    }

    /** Rows that need attention: unmapped, invalid rule targets, or filled with the fallback block. */
    public List<Entry> problems() {
        return entries.stream().filter(e -> switch (e.resolution().status()) {
            case UNMAPPED, INVALID_TARGET, FALLBACK -> true;
            default -> false;
        }).toList();
    }

    /** Writes the block list as CSV: source, count, percent, status, target, rotation, note. */
    public void writeCsv(Path file) throws IOException {
        long solid = entries.stream().filter(e -> e.resolution().status() != Status.AIR).mapToLong(Entry::count).sum();
        StringBuilder sb = new StringBuilder("minecraft_block,count,percent,status,hytale_block,variants,note\n");
        for (Entry e : entries) {
            Resolution r = e.resolution();
            double pct = r.status() == Status.AIR || solid == 0 ? 0 : 100.0 * e.count() / solid;
            sb.append(csv(e.source())).append(',').append(e.count()).append(',')
                    .append(String.format("%.2f", pct)).append(',').append(r.status()).append(',')
                    .append(csv(r.target() == null ? "" : r.target())).append(',').append(e.states().size()).append(',')
                    .append(csv(r.note())).append('\n');
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, sb, StandardCharsets.UTF_8);
    }

    private static String csv(String s) {
        return s.contains(",") || s.contains("\"") ? '"' + s.replace("\"", "\"\"") + '"' : s;
    }
}
