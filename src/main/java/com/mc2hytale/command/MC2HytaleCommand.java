package com.mc2hytale.command;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.mc2hytale.ConverterService;
import com.mc2hytale.MC2HytalePlugin;
import com.mc2hytale.convert.ConversionPlan;
import com.mc2hytale.gui.ConverterPage;
import com.mc2hytale.hytale.Jobs;
import com.mc2hytale.hytale.PrefabExporter;
import com.mc2hytale.mapping.BlockMapper.Resolution;
import com.mc2hytale.mapping.BlockMapper.Status;
import com.mc2hytale.mapping.MappingRules;
import com.mc2hytale.schematic.Schematic;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * {@code /mc2h} - the converter's commands:
 * <pre>
 * gui                       open the converter window
 * list                     schematics waiting in the schematics folder
 * scan &lt;file&gt;               block list with counts and what each becomes (writes a CSV report)
 * problems &lt;file&gt;           only the blocks that have no good match
 * map &lt;minecraft&gt; &lt;hytale&gt;  add a replacement rule (wildcards: *_wool; hytale "skip" removes the block)
 * unmap &lt;minecraft&gt;         remove a rule
 * rules                     rules in the active profile
 * profile &lt;name&gt;            switch mapping profile (created if new); profiles lists them
 * find &lt;text&gt;               search Hytale block ids
 * convert &lt;file&gt;            export a Hytale prefab
 * convertall                export every schematic in the folder
 * paste &lt;file&gt;              place the converted build where you stand
 * reload                    re-read config and profile files
 * </pre>
 */
public final class MC2HytaleCommand extends AbstractCommandCollection {

    static final String PERMISSION = "mc2hytale.admin";
    private static final int SHOW_ROWS = 15;

    public MC2HytaleCommand() {
        super("mc2h", "Minecraft schematic to Hytale prefab converter");
        addAliases("mc2hytale");
        requirePermission(PERMISSION);
        addSubCommand(new GuiCmd());
        addSubCommand(new ListCmd());
        addSubCommand(new Scan("scan", "Show every block in a schematic and what it becomes", false));
        addSubCommand(new Scan("problems", "Show only blocks with no good Hytale match", true));
        addSubCommand(new MapCmd());
        addSubCommand(new UnmapCmd());
        addSubCommand(new RulesCmd());
        addSubCommand(new ProfileCmd());
        addSubCommand(new ProfilesCmd());
        addSubCommand(new FindCmd());
        addSubCommand(new ConvertCmd());
        addSubCommand(new ConvertAllCmd());
        addSubCommand(new PasteCmd());
        addSubCommand(new ReloadCmd());
    }

    private static ConverterService service() {
        return MC2HytalePlugin.getInstance().service();
    }

    /** Runs slow work on the converter thread and reports any failure to the sender. */
    private static void async(CommandContext ctx, ThrowingRunnable job) {
        service().worker().execute(() -> {
            try {
                job.run();
            } catch (Exception e) {
                ctx.sendMessage(Msg.error(e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        });
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static String color(Status s) {
        return switch (s) {
            case CUSTOM, DEFAULT -> "#55FF55";
            case AUTO -> "#AAFFAA";
            case AIR, SKIPPED -> "#AAAAAA";
            case FALLBACK -> "#FFCC33";
            case UNMAPPED, INVALID_TARGET -> "#FF5555";
        };
    }

    private static String describe(Resolution r) {
        String to = switch (r.status()) {
            case AIR -> "air";
            case SKIPPED -> "skipped";
            case UNMAPPED -> "NO MATCH (left out)";
            case INVALID_TARGET -> "BAD RULE: " + r.note();
            default -> r.target();
        };
        String tag = switch (r.status()) {
            case CUSTOM -> " [your rule]";
            case AUTO -> " [auto: " + r.note() + "]";
            case FALLBACK -> " [fallback]";
            default -> "";
        };
        return to + tag;
    }

    private static void summary(Consumer<Message> out, String name, ConversionPlan plan) {
        Schematic s = plan.schematic();
        out.accept(Msg.info(name + " - " + s.format() + ", " + s.width() + "×" + s.height() + "×" + s.length()
                + ", " + plan.entries().size() + " block types"));
        out.accept(Msg.line(String.format("%,d placed  |  %,d auto-matched  |  %,d skipped  |  %,d no match  |  %,d fallback",
                plan.placed(), plan.total(Status.AUTO), plan.total(Status.SKIPPED),
                plan.total(Status.UNMAPPED) + plan.total(Status.INVALID_TARGET), plan.total(Status.FALLBACK)), "#DDDDDD"));
        if (s.blockEntities() > 0 || s.entities() > 0) {
            out.accept(Msg.line(s.blockEntities() + " block entities (chest contents, sign text) and " + s.entities()
                    + " entities are not converted.", "#AAAAAA"));
        }
    }

    private static final class GuiCmd extends AbstractPlayerCommand {
        GuiCmd() {
            super("gui", "Open the converter window");
            addAliases("ui");
            requirePermission(PERMISSION);
        }

        @Override
        protected void execute(@Nonnull CommandContext ctx, @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                               @Nonnull PlayerRef player, @Nonnull World world) {
            Player p = store.getComponent(ref, Player.getComponentType());
            if (p != null) {
                p.getPageManager().openCustomPage(ref, store, new ConverterPage(player));
            }
        }
    }

    private static final class ListCmd extends CommandBase {
        ListCmd() {
            super("list", "List schematics in the schematics folder");
            requirePermission(PERMISSION);
        }

        @Override
        protected void executeSync(@Nonnull CommandContext ctx) {
            async(ctx, () -> {
                List<String> files = service().listSchematics();
                if (files.isEmpty()) {
                    ctx.sendMessage(Msg.warn("No schematics yet. Put .schem, .schematic or .nbt files in " + service().schematicsDir()));
                    return;
                }
                ctx.sendMessage(Msg.info(files.size() + " schematic(s):"));
                files.forEach(f -> ctx.sendMessage(Msg.line(f, "#FFFFFF")));
            });
        }
    }

    private static final class Scan extends CommandBase {
        private final RequiredArg<String> file = withRequiredArg("file", "Schematic file name", ArgTypes.STRING);
        private final boolean problemsOnly;

        Scan(String name, String description, boolean problemsOnly) {
            super(name, description);
            this.problemsOnly = problemsOnly;
            requirePermission(PERMISSION);
        }

        @Override
        protected void executeSync(@Nonnull CommandContext ctx) {
            String name = file.get(ctx);
            async(ctx, () -> {
                ConversionPlan plan = service().plan(service().load(name));
                Path report = service().reportFile(ConverterService.safeName(name));
                plan.writeCsv(report);
                summary(ctx::sendMessage, name, plan);
                List<ConversionPlan.Entry> rows = problemsOnly ? plan.problems()
                        : plan.entries().stream().filter(e -> e.resolution().status() != Status.AIR).toList();
                if (problemsOnly && rows.isEmpty()) {
                    ctx.sendMessage(Msg.success("Every block has a match."));
                }
                rows.stream().limit(SHOW_ROWS).forEach(e -> ctx.sendMessage(Msg.line(
                        String.format("%,8d  %s → %s", e.count(), e.source(), describe(e.resolution())), color(e.resolution().status()))));
                if (rows.size() > SHOW_ROWS) {
                    ctx.sendMessage(Msg.line("... " + (rows.size() - SHOW_ROWS) + " more in the report", "#AAAAAA"));
                }
                ctx.sendMessage(Msg.info("Full list: " + report.getFileName() + " in the reports folder. Fix with /mc2h map <minecraft> <hytale>."));
            });
        }
    }

    private static final class MapCmd extends CommandBase {
        private final RequiredArg<String> from = withRequiredArg("minecraft", "Minecraft block, e.g. stone or *_wool", ArgTypes.STRING);
        private final RequiredArg<String> to = withRequiredArg("hytale", "Hytale block id, fluid:<Id>, or skip", ArgTypes.STRING);

        MapCmd() {
            super("map", "Replace a Minecraft block with a Hytale block");
            requirePermission(PERMISSION);
        }

        @Override
        protected void executeSync(@Nonnull CommandContext ctx) {
            String source = MappingRules.normalise(from.get(ctx));
            String target = to.get(ctx);
            boolean skip = target.equalsIgnoreCase(MappingRules.SKIP) || target.equalsIgnoreCase("air");
            if (!skip && !service().registry().exists(target)) {
                ctx.sendMessage(Msg.error(target + " is not a Hytale block. Search with /mc2h find <text>."));
                return;
            }
            async(ctx, () -> {
                service().putRule(source, skip ? MappingRules.SKIP : target);
                ctx.sendMessage(Msg.success(source + " → " + (skip ? "skip" : target) + " saved to profile " + service().profileName() + "."));
            });
        }
    }

    private static final class UnmapCmd extends CommandBase {
        private final RequiredArg<String> from = withRequiredArg("minecraft", "Minecraft block or pattern", ArgTypes.STRING);

        UnmapCmd() {
            super("unmap", "Remove a replacement rule");
            requirePermission(PERMISSION);
        }

        @Override
        protected void executeSync(@Nonnull CommandContext ctx) {
            String source = MappingRules.normalise(from.get(ctx));
            async(ctx, () -> ctx.sendMessage(service().removeRule(source)
                    ? Msg.success("Rule for " + source + " removed; the default applies again.")
                    : Msg.warn("Profile " + service().profileName() + " has no rule for " + source + ".")));
        }
    }

    private static final class RulesCmd extends CommandBase {
        RulesCmd() {
            super("rules", "List the rules in the active profile");
            requirePermission(PERMISSION);
        }

        @Override
        protected void executeSync(@Nonnull CommandContext ctx) {
            Map<String, String> rules = service().profile().asMap();
            ctx.sendMessage(Msg.info("Profile " + service().profileName() + ": " + rules.size() + " rule(s), plus "
                    + service().defaults().size() + " built-in defaults."));
            rules.forEach((k, v) -> ctx.sendMessage(Msg.line(k + " → " + v, "#FFFFFF")));
        }
    }

    private static final class ProfileCmd extends CommandBase {
        private final RequiredArg<String> name = withRequiredArg("name", "Profile name", ArgTypes.STRING);

        ProfileCmd() {
            super("profile", "Switch mapping profile (created if it doesn't exist)");
            requirePermission(PERMISSION);
        }

        @Override
        protected void executeSync(@Nonnull CommandContext ctx) {
            String n = name.get(ctx);
            async(ctx, () -> {
                service().useProfile(n);
                ctx.sendMessage(Msg.success("Using profile " + service().profileName() + " (" + service().profile().size() + " rules)."));
            });
        }
    }

    private static final class ProfilesCmd extends CommandBase {
        ProfilesCmd() {
            super("profiles", "List mapping profiles");
            requirePermission(PERMISSION);
        }

        @Override
        protected void executeSync(@Nonnull CommandContext ctx) {
            async(ctx, () -> {
                String active = service().profileName();
                ctx.sendMessage(Msg.info("Profiles:"));
                service().listProfiles().forEach(p -> ctx.sendMessage(Msg.line(p + (p.equals(active) ? "  (active)" : ""), "#FFFFFF")));
            });
        }
    }

    private static final class FindCmd extends CommandBase {
        private final RequiredArg<String> text = withRequiredArg("text", "Part of a Hytale block id, e.g. planks", ArgTypes.STRING);

        FindCmd() {
            super("find", "Search Hytale block ids");
            requirePermission(PERMISSION);
        }

        @Override
        protected void executeSync(@Nonnull CommandContext ctx) {
            String q = text.get(ctx).toLowerCase(Locale.ROOT);
            List<String> hits = service().registry().all().stream()
                    .filter(id -> id.toLowerCase(Locale.ROOT).contains(q)).toList();
            ctx.sendMessage(Msg.info(hits.size() + " Hytale block(s) match \"" + q + "\":"));
            hits.stream().limit(30).forEach(id -> ctx.sendMessage(Msg.line(id, "#FFFFFF")));
            if (hits.size() > 30) {
                ctx.sendMessage(Msg.line("... narrow the search to see the rest", "#AAAAAA"));
            }
        }
    }

    /** Loads, maps, builds and saves one schematic. Returns the saved file. */
    private static Path convert(String name, Consumer<Message> out) throws Exception {
        ConversionPlan plan = service().plan(service().load(name));
        Path saved = Jobs.convert(service(), name, plan);
        summary(out, name, plan);
        if (!plan.problems().isEmpty()) {
            out.accept(Msg.warn(plan.problems().size() + " block type(s) had no good match - see /mc2h problems " + name));
        }
        return saved;
    }

    private static final class ConvertCmd extends CommandBase {
        private final RequiredArg<String> file = withRequiredArg("file", "Schematic file name", ArgTypes.STRING);

        ConvertCmd() {
            super("convert", "Convert a schematic into a Hytale prefab");
            requirePermission(PERMISSION);
        }

        @Override
        protected void executeSync(@Nonnull CommandContext ctx) {
            String name = file.get(ctx);
            ctx.sendMessage(Msg.info("Converting " + name + "..."));
            async(ctx, () -> {
                Path saved = convert(name, ctx::sendMessage);
                ctx.sendMessage(Msg.success("Saved prefab " + PrefabExporter.FOLDER + "/" + saved.getFileName()));
            });
        }
    }

    private static final class ConvertAllCmd extends CommandBase {
        ConvertAllCmd() {
            super("convertall", "Convert every schematic in the folder");
            requirePermission(PERMISSION);
        }

        @Override
        protected void executeSync(@Nonnull CommandContext ctx) {
            async(ctx, () -> {
                List<String> files = service().listSchematics();
                ctx.sendMessage(Msg.info("Converting " + files.size() + " schematic(s)..."));
                int ok = 0;
                for (int i = 0; i < files.size(); i++) {
                    String f = files.get(i);
                    try {
                        Path saved = convert(f, m -> {});
                        ok++;
                        ctx.sendMessage(Msg.line("[" + (i + 1) + "/" + files.size() + "] " + f + " → " + saved.getFileName(), "#55FF55"));
                    } catch (Exception e) {
                        ctx.sendMessage(Msg.line("[" + (i + 1) + "/" + files.size() + "] " + f + " FAILED: " + e.getMessage(), "#FF5555"));
                    }
                }
                ctx.sendMessage(Msg.success(ok + " of " + files.size() + " converted. Reports are in the reports folder."));
            });
        }
    }

    private static final class PasteCmd extends AbstractPlayerCommand {
        private final RequiredArg<String> file = withRequiredArg("file", "Schematic file name", ArgTypes.STRING);

        PasteCmd() {
            super("paste", "Place a converted schematic where you stand");
            requirePermission(PERMISSION);
        }

        @Override
        protected void execute(@Nonnull CommandContext ctx, @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                               @Nonnull PlayerRef player, @Nonnull World world) {
            var pos = store.getComponent(ref, TransformComponent.getComponentType()).getPosition();
            Vector3i at = new Vector3i((int) Math.floor(pos.x()), (int) Math.floor(pos.y()), (int) Math.floor(pos.z()));
            String name = file.get(ctx);
            async(ctx, () -> {
                ConversionPlan plan = service().plan(service().load(name));
                Jobs.paste(service(), plan, world, at, () -> ctx.sendMessage(Msg.success(String.format(
                        "Pasted %s (%,d blocks) at %d %d %d.", name, plan.placed(), at.x(), at.y(), at.z()))));
            });
        }
    }

    private static final class ReloadCmd extends CommandBase {
        ReloadCmd() {
            super("reload", "Re-read config.json and the active profile");
            requirePermission(PERMISSION);
        }

        @Override
        protected void executeSync(@Nonnull CommandContext ctx) {
            async(ctx, () -> {
                service().reload();
                ctx.sendMessage(Msg.success("Reloaded. Profile " + service().profileName() + " has " + service().profile().size() + " rules."));
            });
        }
    }
}
