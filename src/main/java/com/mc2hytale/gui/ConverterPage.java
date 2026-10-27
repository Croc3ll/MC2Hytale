package com.mc2hytale.gui;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.mc2hytale.ConverterService;
import com.mc2hytale.MC2HytalePlugin;
import com.mc2hytale.convert.ConversionPlan;
import com.mc2hytale.hytale.Jobs;
import com.mc2hytale.mapping.BlockMapper.Resolution;
import com.mc2hytale.mapping.BlockMapper.Status;
import com.mc2hytale.mapping.MappingRules;
import com.mc2hytale.schematic.Schematic;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * The converter window (layout {@code Pages/MC2Hytale/Mc2ConverterPage.ui}), opened with {@code /mc2h gui}.
 * Pick a schematic on the left; its blocks are listed on the right with what each becomes. Change opens
 * the editor to search Hytale blocks and save a rule; the list is rescanned with the new rule straight away.
 *
 * <p>Loading and converting run on the converter thread; the results are sent back on the player's world thread.
 */
public final class ConverterPage extends InteractiveCustomUIPage<ConverterPage.EventPayload> {

    private static final String PAGE = "Pages/MC2Hytale/Mc2ConverterPage.ui";
    private static final String FILE_ROW = "Pages/MC2Hytale/Mc2FileRow.ui";
    private static final String BLOCK_ROW = "Pages/MC2Hytale/Mc2BlockRow.ui";
    private static final String SUGGEST_ROW = "Pages/MC2Hytale/Mc2SuggestRow.ui";
    private static final int MAX_BLOCK_ROWS = 120;
    private static final int MAX_SUGGESTIONS = 40;

    private enum Filter { ALL, PROBLEMS, AUTO, CUSTOM, SKIPPED }

    private List<String> files = List.of();
    private String file;
    private Schematic schematic;
    private ConversionPlan plan;
    private List<ConversionPlan.Entry> shown = List.of();
    private Filter filter = Filter.ALL;
    private String editing;
    private String targetText = "";
    private String profileText = "";
    private boolean busy;

    public ConverterPage(@Nonnull PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CanDismiss, EventPayload.CODEC);
    }

    private static ConverterService service() {
        return MC2HytalePlugin.getInstance().service();
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder evt, @Nonnull Store<EntityStore> store) {
        cmd.append(PAGE);
        bind(evt, "#Mc2Refresh", "REFRESH", "");
        bind(evt, "#Mc2ConvertAll", "CONVERT_ALL", "");
        bind(evt, "#Mc2ProfileGo", "PROFILE", "");
        bind(evt, "#Mc2Convert", "CONVERT", "");
        bind(evt, "#Mc2Paste", "PASTE", "");
        bind(evt, "#Mc2Rescan", "RESCAN", "");
        bind(evt, "#Mc2TabAll", "FILTER", "ALL");
        bind(evt, "#Mc2TabProblems", "FILTER", "PROBLEMS");
        bind(evt, "#Mc2TabAuto", "FILTER", "AUTO");
        bind(evt, "#Mc2TabCustom", "FILTER", "CUSTOM");
        bind(evt, "#Mc2TabSkipped", "FILTER", "SKIPPED");
        bind(evt, "#Mc2Apply", "APPLY", "");
        bind(evt, "#Mc2Skip", "SKIP", "");
        bind(evt, "#Mc2Reset", "RESET", "");
        bind(evt, "#Mc2Close", "CLOSE", "");
        evt.addEventBinding(CustomUIEventBindingType.ValueChanged, "#Mc2Target",
                new EventData().append("Action", "TARGET_TEXT").append("@Text", "#Mc2Target.Value"), false);
        evt.addEventBinding(CustomUIEventBindingType.Validating, "#Mc2Target", new EventData().append("Action", "APPLY"), false);
        evt.addEventBinding(CustomUIEventBindingType.ValueChanged, "#Mc2ProfileInput",
                new EventData().append("Action", "PROFILE_TEXT").append("@Text", "#Mc2ProfileInput.Value"), false);
        evt.addEventBinding(CustomUIEventBindingType.Validating, "#Mc2ProfileInput", new EventData().append("Action", "PROFILE"), false);

        cmd.set("#Mc2Folder.Text", "Folder: " + service().schematicsDir());
        try {
            files = service().listSchematics();
        } catch (Exception e) {
            files = List.of();
        }
        fillFiles(cmd, evt);
        fillProfile(cmd);
        fillButtons(cmd);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull EventPayload data) {
        String action = data.action == null ? "" : data.action;
        String arg = data.arg == null ? "" : data.arg;
        World world = store.getExternalData().getWorld();
        switch (action) {
            case "TARGET_TEXT" -> {
                targetText = data.text == null ? "" : data.text.trim();
                UICommandBuilder cmd = new UICommandBuilder();
                UIEventBuilder evt = new UIEventBuilder();
                fillSuggestions(cmd, evt);
                sendUpdate(cmd, evt, false);
            }
            case "PROFILE_TEXT" -> profileText = data.text == null ? "" : data.text.trim();
            case "REFRESH" -> background(world, "Looking for schematics...", () -> {
                files = service().listSchematics();
                return files.size() + " schematic(s) found.";
            });
            case "OPEN" -> {
                file = arg;
                editing = null;
                background(world, "Scanning " + arg + "...", () -> {
                    schematic = service().load(arg);
                    plan = service().plan(schematic);
                    return "Scanned " + arg + ".";
                });
            }
            case "RESCAN" -> {
                if (schematic != null) {
                    background(world, "Rescanning...", () -> {
                        plan = service().plan(schematic);
                        return "Rescanned with the current rules.";
                    });
                }
            }
            case "FILTER" -> {
                filter = Filter.valueOf(arg);
                refresh("");
            }
            case "CHANGE" -> {
                editing = arg;
                ConversionPlan.Entry e = entry(arg);
                targetText = e == null || e.resolution().target() == null ? "" : e.resolution().target();
                UICommandBuilder cmd = new UICommandBuilder();
                UIEventBuilder evt = new UIEventBuilder();
                cmd.set("#Mc2Target.Value", targetText);
                fillEditor(cmd, evt);
                status(cmd, "Type part of a Hytale block name, pick one from the list, then press Use this.");
                sendUpdate(cmd, evt, false);
            }
            case "PICK" -> {
                targetText = arg;
                UICommandBuilder cmd = new UICommandBuilder();
                UIEventBuilder evt = new UIEventBuilder();
                cmd.set("#Mc2Target.Value", arg);
                fillSuggestions(cmd, evt);
                sendUpdate(cmd, evt, false);
            }
            case "APPLY" -> rule(world, targetText);
            case "SKIP" -> rule(world, MappingRules.SKIP);
            case "RESET" -> {
                if (editing != null) {
                    String source = editing;
                    background(world, "Removing rule...", () -> {
                        boolean removed = service().removeRule(source);
                        editing = null;
                        if (schematic != null) {
                            plan = service().plan(schematic);
                        }
                        return removed ? source + " uses the default again." : source + " had no rule of yours.";
                    });
                }
            }
            case "CLOSE" -> {
                editing = null;
                refresh("");
            }
            case "PROFILE" -> {
                String name = profileText;
                if (name.isEmpty()) {
                    refresh("Type a profile name first.");
                    return;
                }
                background(world, "Switching profile...", () -> {
                    service().useProfile(name);
                    if (schematic != null) {
                        plan = service().plan(schematic);
                    }
                    return "Using profile " + service().profileName() + ".";
                });
            }
            case "CONVERT" -> {
                if (plan != null) {
                    String f = file;
                    ConversionPlan p = plan;
                    background(world, "Converting " + f + "...", () -> {
                        Path saved = Jobs.convert(service(), f, p);
                        return "Saved prefab mc2hytale/" + saved.getFileName() + " (" + String.format("%,d", p.placed()) + " blocks).";
                    });
                }
            }
            case "CONVERT_ALL" -> background(world, "Converting every schematic...", () -> {
                int ok = 0;
                StringBuilder failed = new StringBuilder();
                for (String f : service().listSchematics()) {
                    try {
                        Jobs.convert(service(), f, service().plan(service().load(f)));
                        ok++;
                    } catch (Exception e) {
                        failed.append(failed.isEmpty() ? "  Failed: " : ", ").append(f);
                    }
                }
                return ok + " converted." + failed;
            });
            case "PASTE" -> paste(ref, store, world);
            default -> { }
        }
    }

    private void rule(World world, String target) {
        if (editing == null) {
            return;
        }
        boolean skip = target.equalsIgnoreCase(MappingRules.SKIP);
        if (!skip && !service().registry().exists(target)) {
            refresh(target.isEmpty() ? "Pick a Hytale block first." : target + " is not a Hytale block. Pick one from the list.");
            return;
        }
        String source = editing;
        background(world, "Saving rule...", () -> {
            service().putRule(source, target);
            editing = null;
            if (schematic != null) {
                plan = service().plan(schematic);
            }
            return source + " → " + (skip ? "left out" : target) + " (saved to profile " + service().profileName() + ").";
        });
    }

    private void paste(Ref<EntityStore> ref, Store<EntityStore> store, World world) {
        if (plan == null) {
            return;
        }
        TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
        if (t == null) {
            return;
        }
        var pos = t.getPosition();
        Vector3i at = new Vector3i((int) Math.floor(pos.x()), (int) Math.floor(pos.y()), (int) Math.floor(pos.z()));
        ConversionPlan p = plan;
        busy = true;
        refresh("Pasting...");
        service().worker().execute(() -> {
            try {
                Jobs.paste(service(), p, world, at, () -> {
                    busy = false;
                    refresh(String.format("Pasted %,d blocks at %d %d %d.", p.placed(), at.x(), at.y(), at.z()));
                });
            } catch (Exception e) {
                world.execute(() -> {
                    busy = false;
                    refresh(e.getMessage());
                });
            }
        });
    }

    @FunctionalInterface
    private interface Job {
        String run() throws Exception;
    }

    /** Runs a job on the converter thread, then redraws on the world thread with its message (or error). */
    private void background(World world, String working, Job job) {
        if (busy) {
            return;
        }
        busy = true;
        refresh(working);
        service().worker().execute(() -> {
            String message;
            try {
                message = job.run();
            } catch (Exception e) {
                message = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            String m = message;
            world.execute(() -> {
                busy = false;
                refresh(m);
            });
        });
    }

    /** Redraws everything that can change (not the static layout). */
    private void refresh(String status) {
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder evt = new UIEventBuilder();
        fillFiles(cmd, evt);
        fillProfile(cmd);
        fillBlocks(cmd, evt);
        fillEditor(cmd, evt);
        fillButtons(cmd);
        status(cmd, status);
        sendUpdate(cmd, evt, false);
    }

    private void fillFiles(UICommandBuilder cmd, UIEventBuilder evt) {
        cmd.clear("#Mc2FileList");
        for (int i = 0; i < files.size(); i++) {
            String f = files.get(i);
            String row = "#Mc2FileList[" + i + "]";
            cmd.append("#Mc2FileList", FILE_ROW);
            cmd.set(row + " #Mc2Open.Text", (f.equals(file) ? "> " : "") + f);
            bind(evt, row + " #Mc2Open", "OPEN", f);
        }
    }

    private void fillProfile(UICommandBuilder cmd) {
        cmd.set("#Mc2Profile.Text", "Active: " + service().profileName() + "  (" + service().profile().size() + " rules of yours, "
                + service().defaults().size() + " built in)");
    }

    private void fillButtons(UICommandBuilder cmd) {
        cmd.set("#Mc2Convert.Disabled", busy || plan == null);
        cmd.set("#Mc2Paste.Disabled", busy || plan == null);
        cmd.set("#Mc2Rescan.Disabled", busy || plan == null);
        cmd.set("#Mc2ConvertAll.Disabled", busy || files.isEmpty());
    }

    private void fillBlocks(UICommandBuilder cmd, UIEventBuilder evt) {
        cmd.clear("#Mc2BlockList");
        if (plan == null) {
            return;
        }
        Schematic s = plan.schematic();
        cmd.set("#Mc2FileTitle.Text", file);
        String extra = s.blockEntities() + s.entities() > 0
                ? String.format("  |  %,d block entities and %,d entities not converted", s.blockEntities(), s.entities()) : "";
        cmd.set("#Mc2Summary.Text", String.format("%s, %d × %d × %d, %d block types.  %,d placed  |  %,d auto  |  %,d left out  |  %,d no match%s",
                s.format(), s.width(), s.height(), s.length(), plan.entries().size(), plan.placed(), plan.total(Status.AUTO),
                plan.total(Status.SKIPPED), plan.total(Status.UNMAPPED) + plan.total(Status.INVALID_TARGET), extra));

        shown = plan.entries().stream().filter(e -> switch (filter) {
            case ALL -> e.resolution().status() != Status.AIR;
            case PROBLEMS -> plan.problems().contains(e);
            case AUTO -> e.resolution().status() == Status.AUTO;
            case CUSTOM -> e.resolution().status() == Status.CUSTOM;
            case SKIPPED -> e.resolution().status() == Status.SKIPPED;
        }).toList();
        cmd.set("#Mc2Filter.Text", "Showing " + filter.name().toLowerCase(Locale.ROOT) + ": " + shown.size()
                + (shown.size() > MAX_BLOCK_ROWS ? " (first " + MAX_BLOCK_ROWS + ")" : ""));

        int n = Math.min(shown.size(), MAX_BLOCK_ROWS);
        for (int i = 0; i < n; i++) {
            ConversionPlan.Entry e = shown.get(i);
            Resolution r = e.resolution();
            String row = "#Mc2BlockList[" + i + "]";
            cmd.append("#Mc2BlockList", BLOCK_ROW);
            cmd.set(row + " #Mc2Name.Text", String.format("%s   ×%,d", e.source(), e.count()));
            String label = switch (r.status()) {
                case CUSTOM -> "#Mc2Ok";
                case DEFAULT -> "#Mc2Ok";
                case AUTO, FALLBACK -> "#Mc2Warn";
                case UNMAPPED, INVALID_TARGET -> "#Mc2Bad";
                case AIR, SKIPPED -> "#Mc2Grey";
            };
            cmd.set(row + " " + label + ".Visible", true);
            cmd.set(row + " " + label + ".Text", describe(r));
            bind(evt, row + " #Mc2Change", "CHANGE", e.source());
        }
    }

    private void fillEditor(UICommandBuilder cmd, UIEventBuilder evt) {
        cmd.set("#Mc2Editor.Visible", editing != null);
        if (editing == null) {
            return;
        }
        ConversionPlan.Entry e = entry(editing);
        cmd.set("#Mc2EditTitle.Text", "Replace " + editing + (e == null ? "" : "  (now: " + describe(e.resolution()) + ")"));
        cmd.set("#Mc2Apply.Disabled", busy);
        cmd.set("#Mc2Skip.Disabled", busy);
        cmd.set("#Mc2Reset.Disabled", busy || !service().profile().asMap().containsKey(editing));
        fillSuggestions(cmd, evt);
    }

    private void fillSuggestions(UICommandBuilder cmd, UIEventBuilder evt) {
        cmd.clear("#Mc2Suggest");
        String q = targetText.toLowerCase(Locale.ROOT);
        if (q.length() < 2) {
            return;
        }
        List<String> hits = service().registry().all().stream()
                .filter(id -> id.toLowerCase(Locale.ROOT).contains(q))
                .limit(MAX_SUGGESTIONS).toList();
        for (int i = 0; i < hits.size(); i++) {
            String row = "#Mc2Suggest[" + i + "]";
            cmd.append("#Mc2Suggest", SUGGEST_ROW);
            cmd.set(row + " #Mc2Pick.Text", hits.get(i));
            bind(evt, row + " #Mc2Pick", "PICK", hits.get(i));
        }
    }

    private void status(UICommandBuilder cmd, String text) {
        cmd.set("#Mc2Status.Text", text == null ? "" : text);
    }

    private ConversionPlan.Entry entry(String source) {
        if (plan == null) {
            return null;
        }
        return plan.entries().stream().filter(e -> e.source().equals(source)).findFirst().orElse(null);
    }

    private static String describe(Resolution r) {
        return switch (r.status()) {
            case AIR -> "air";
            case SKIPPED -> "left out";
            case UNMAPPED -> "no match - left out";
            case INVALID_TARGET -> "bad rule: " + r.note();
            case CUSTOM -> "→ " + r.target() + "   (your rule)";
            case DEFAULT -> "→ " + r.target();
            case AUTO -> "→ " + r.target() + "   (auto: " + r.note() + ")";
            case FALLBACK -> "→ " + r.target() + "   (fallback, no match)";
        };
    }

    private static void bind(UIEventBuilder evt, String selector, String action, String arg) {
        evt.addEventBinding(CustomUIEventBindingType.Activating, selector,
                new EventData().append("Action", action).append("Arg", arg), false);
    }

    public static final class EventPayload {
        static final BuilderCodec<EventPayload> CODEC = BuilderCodec.builder(EventPayload.class, EventPayload::new)
                .addField(new KeyedCodec<>("Action", Codec.STRING), (d, v) -> d.action = v, d -> d.action)
                .addField(new KeyedCodec<>("Arg", Codec.STRING), (d, v) -> d.arg = v, d -> d.arg)
                .addField(new KeyedCodec<>("@Text", Codec.STRING), (d, v) -> d.text = v, d -> d.text)
                .build();

        String action;
        String arg;
        String text;
    }
}
