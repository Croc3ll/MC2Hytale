# MC2Hytale

A Hytale server plugin that converts Minecraft schematics into Hytale prefabs. It lists every block in the build, auto-maps blocks to their closest Hytale equivalents, and lets you replace any block with your own rules.

## Features

- **Reads all common schematic formats**
  - Sponge `.schem` v1, v2 and v3 (WorldEdit 7+, FAWE)
  - Legacy MCEdit / WorldEdit 6 `.schematic` (numeric ids, translated to modern names)
  - Vanilla structure-block `.nbt`
  - Litematica `.litematic` (all regions merged into one build)
- **Block inspector.** `scan` lists every unique block with its count, percentage, and what it becomes. It also writes a full CSV report.
- **Automatic mapping.** About 390 built-in rules cover stone, wood, soil, plants, wool, concrete, terracotta, glass, ores, lights, furniture and fluids. Every target is checked against the server's live block list.
- **Smart shapes.** Stairs, slabs, walls and fences take their material's mapping and use the matching Hytale shape (`_Roof`, `_Stairs`, `_Half`, `_Wall`, `_Fence`). For example, `oak_stairs` becomes `Wood_Softwood_Roof`, corners included.
- **Replacement rules.** Use `map <minecraft> <hytale>` to override any block, globally or with `*` wildcards (`*_wool`). Mapping a block to `skip` removes it.
- **Mapping profiles.** Keep separate rule sets (e.g. `medieval` and `modern`) and switch between them.
- **Rotation.** Blocks follow Minecraft's `facing`, `axis` and upside-down stair states.
- **Waterlogging and fluids.** Water and lava become Hytale fluids, and waterlogged blocks keep their water.
- **Block entities.** Chests, furnaces and benches get working block entities. Their Minecraft contents are not carried over.
- **Batch conversion** of the whole folder with `convertall`, run on a background thread.
- **Dry run.** `scan` and `problems` change nothing.
- **Safe.** Source files are only read, never modified. Unknown or modded blocks are reported, never guessed silently.

## Install

1. Build it (`gradlew build`) or download a release jar.
2. Put `MC2Hytale-x.y.z.jar` in your server's `mods/` folder and start the server.
3. Put schematics in `mods/MC2Hytale_MC2Hytale/schematics/`. Subfolders are fine.

## Converter window

`/mc2h gui` (or `/mc2h ui`) opens the converter window:

- **Left side:** the schematics in the folder (click one to scan it), **Refresh**, **Convert all**, and the mapping profile switcher.
- **Right side:** a summary of the build and every block in it with its count and what it becomes. Green means mapped, yellow means auto or fallback, red means no match, and grey means left out. The filter buttons show All, Problems, Auto, My rules or Skipped.
- **Change** on a block opens the editor. Type part of a Hytale block name, click a match, then press **Use this**. You can also press **Leave out** to skip the block, or **Default** to remove your rule. The list rescans straight away.
- **Convert to prefab** and **Paste where I stand** act on the picked schematic.

## Commands

Every command requires the `mc2hytale.admin` permission. `/mc2hytale` is an alias for `/mc2h`.

| Command | What it does |
|---|---|
| `/mc2h gui` | Open the converter window |
| `/mc2h list` | Schematics in the folder |
| `/mc2h scan <file>` | Block list with counts and mappings; writes `reports/<file>.csv` |
| `/mc2h problems <file>` | Only the blocks with no good match |
| `/mc2h map <minecraft> <hytale>` | Add a rule, e.g. `map stone Rock_Basalt`, `map *_wool Cloth_Block_Wool_White`, `map redstone_wire skip`, `map water fluid:Water_Source` |
| `/mc2h unmap <minecraft>` | Remove a rule so the default applies again |
| `/mc2h rules` | Rules in the active profile |
| `/mc2h profile <name>` / `profiles` | Switch profile (a new name creates it) / list profiles |
| `/mc2h find <text>` | Search Hytale block ids, e.g. `find planks` |
| `/mc2h convert <file>` | Export `prefabs/mc2hytale/<file>.prefab.json` |
| `/mc2h convertall` | Convert every schematic in the folder |
| `/mc2h paste <file>` | Place the converted build at your feet |
| `/mc2h reload` | Re-read `config.json` and profiles |

Converted prefabs are written with Hytale's own prefab writer into the server prefab folder, under `mc2hytale/`. The anchor is the bottom centre of the build.

## How a block is matched

1. A rule in your active profile (exact block state, then bare id, then `*` patterns, most specific first).
2. A built-in default rule.
3. Smart shape matching for stairs, slabs, walls and fences.
4. Otherwise the block is left out and listed under `problems`. You can set `"unmappedAction": "fallback"` to place `fallbackBlock` instead.

A rule that points at a block the server doesn't have shows up as `BAD RULE` in the scan. It never breaks the export.

## Config (`config.json`)

| Key | Default | Meaning |
|---|---|---|
| `profile` | `default` | Active mapping profile (`profiles/<name>.json`) |
| `unmappedAction` | `skip` | `skip` or `fallback` |
| `fallbackBlock` | `Rock_Stone` | Used when `unmappedAction` is `fallback` |
| `includeAir` | `false` | Write Minecraft air as empty blocks, so pasting clears the space |
| `smartShapes` | `true` | Stairs/slab/wall/fence shape matching |
| `stairsAsRoofs` | `true` | Stairs become the material's `_Roof` block (with corner pieces); upside-down stairs stay stairs |
| `flipBottomTrapdoors` | `true` | Turn bottom-half trapdoors upside down so they lie on the floor instead of at the top of the block |
| `rotateBlocks` | `true` | Apply `facing` / `axis` rotation |
| `facingYaw` | N=0 W=1 S=2 E=3 | Quarter turns per Minecraft facing |
| `yawOffset` | `0` | Extra quarter turns for every rotated block. Change this if stairs come out turned |
| `pasteMaxVolume` | `2000000` | Largest build `/mc2h paste` places directly |

Profile files look like this:

```json
{ "rules": { "minecraft:stone": "Rock_Basalt", "minecraft:*_wool": "Cloth_Block_Wool_White" } }
```

## Limitations

- Block entity data (chest contents, sign text, banners) and entities are counted but not converted.
- Redstone, command blocks and portals have no Hytale equivalent. By default they are skipped.
- Whole Minecraft worlds (`.mca` region files) are not supported yet. Export areas as schematics with WorldEdit first.
- The yaw directions in `facingYaw` are a best guess. Check a converted staircase in-game and adjust `yawOffset` if needed.

## Roadmap

- World/region (`.mca`) import with chunked processing
- Side-by-side 3D preview of original and converted builds
- Prefab library with tags and thumbnails
- Mapping history and undo

## Development

```
gradlew build            # compiles and runs the offline schematic checks
gradlew testSchematics   # offline checks only
```

## License

MIT
