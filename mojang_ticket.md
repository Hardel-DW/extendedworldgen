# Feature request: biome_preset registry for multi_noise biome sources

## Summary

The `overworld.json` dimension file weighs **6.8 MB** and contains **7,593 biome entries** for only **54 unique biomes**. By comparison, `the_nether.json` and `the_end.json` are under 30 lines each. This extreme bloat comes from three structural redundancies in the `multi_noise` biome source format. A new `biome_preset` registry type combined with tag support would reduce this to **~262 KB across 56 small, human-editable files** — a 96% reduction — while making biome customization far more accessible to datapack creators.

## Problem analysis

### Root cause 1: depth duplication (×2 bloat)

Every biome parameter combination is written **twice** — once with `depth: 0.0` (surface) and once with `depth: 1.0` (underground). Out of 7,593 entries, exactly 3,795 pairs are byte-for-byte identical except for this single field. Only 3 biome entries use a non-standard depth:

| Biome | Depth value |
|---|---|
| `minecraft:dripstone_caves` | `[0.2, 0.9]` |
| `minecraft:lush_caves` | `[0.2, 0.9]` |
| `minecraft:deep_dark` | `1.1` |

The depth 0/1 duplication alone accounts for **50% of all entries** and provides zero additional information — the game could infer both layers from a single entry.

### Root cause 2: verbose "any" ranges

Many entries include parameter axes set to their full range `[-1.0, 1.0]`, which means "spawn everywhere along this axis". For example, `mushroom_fields` only depends on `continentalness`, yet the current format still explicitly writes:

```json
{
    "biome": "minecraft:mushroom_fields",
    "parameters": {
        "continentalness": [-1.2, -1.05],
        "depth": 0.0,
        "erosion": [-1.0, 1.0],
        "humidity": [-1.0, 1.0],
        "offset": 0.0,
        "temperature": [-1.0, 1.0],
        "weirdness": [-1.0, 1.0]
    }
}
```

Five of the seven fields carry no information. Across all entries, approximately **3,900 fields** are redundant full-range declarations.

### Root cause 3: no range merging

The multi_noise system divides the world into a 5-dimensional grid with these breakpoints:

| Axis | Breakpoints | Intervals |
|---|---|---|
| Temperature | -1.0, -0.45, -0.15, 0.2, 0.55, 1.0 | 5 |
| Humidity | -1.0, -0.35, -0.1, 0.1, 0.3, 1.0 | 5 |
| Continentalness | -1.2, -1.05, -0.455, -0.19, -0.11, 0.03, 0.3, 1.0 | 7 |
| Erosion | -1.0, -0.78, -0.375, -0.22, 0.05, 0.45, 0.55, 1.0 | 7 |
| Weirdness | -1.0, -0.93, -0.77, ... 0.93, 1.0 | 13 |

This creates a grid of **5 × 5 × 7 × 7 × 13 = 15,925 cells**. A single biome like `desert` occupies many non-contiguous cells in this grid. The current format writes one entry per rectangular sub-region, resulting in **708 entries for desert alone**. However, many of these entries are adjacent rectangles that could be merged. After merging ranges that differ on only one axis, desert reduces from 354 unique entries (after depth dedup) to just **32 rules** — a 91% reduction.

### Combined impact

| Optimization | Entries | File size | Reduction |
|---|---|---|---|
| Original | 7,593 | 6,824 KB | — |
| Remove depth duplication | 3,798 | ~1,627 KB | -61% |
| Omit full-range defaults | 3,798 | ~1,537 KB | -64% |
| Merge adjacent ranges | 881 | ~354 KB | -92% |

## Proposed solution: biome_preset registry

### 1. Dimension file references a tag

The dimension file becomes minimal, referencing a tag of biome presets:

```json
{
    "type": "minecraft:overworld",
    "generator": {
        "type": "minecraft:noise",
        "settings": "minecraft:overworld",
        "biome_source": {
            "type": "minecraft:multi_noise",
            "biome_presets": "#minecraft:overworld"
        }
    }
}
```

This is consistent with how other registries already work in Minecraft (damage types, worldgen features, etc.).

### 2. Tag lists all biome presets

`data/minecraft/tags/biome_preset/overworld.json`:

```json
{
    "values": [
        "minecraft:desert",
        "minecraft:plains",
        "minecraft:mushroom_fields",
        "..."
    ]
}
```

Datapack creators can add or remove biomes from this tag without touching the dimension file. A modded biome is just a new preset file + adding it to the tag.

### 3. Each biome has its own preset file

`data/minecraft/biome_preset/mushroom_fields.json`:

```json
{
    "biome": "minecraft:mushroom_fields",
    "rules": [
        {
            "continentalness": [-1.2, -1.05]
        }
    ]
}
```

`data/minecraft/biome_preset/deep_dark.json`:

```json
{
    "biome": "minecraft:deep_dark",
    "rules": [
        {
            "erosion": [-1.0, -0.375],
            "depth": 1.1
        }
    ]
}
```

`data/minecraft/biome_preset/desert.json` (excerpt):

```json
{
    "biome": "minecraft:desert",
    "rules": [
        {
            "temperature": [0.55, 1.0],
            "continentalness": [-0.11, 0.03],
            "erosion": [-0.375, -0.2225],
            "weirdness": [-1.0, -0.9333]
        },
        {
            "temperature": [0.55, 1.0],
            "continentalness": [-0.19, 1.0],
            "erosion": [0.05, 0.45],
            "weirdness": [-1.0, -0.4]
        }
    ]
}
```

### Syntax rules

- **Omitted axes default to `[-1.0, 1.0]`** (full range). No need to write parameters that don't constrain the biome.
- **Depth is implicit for surface biomes.** The game generates both `depth: 0.0` and `depth: 1.0` entries automatically from each rule. Only biomes that need a specific depth value (caves, deep dark) declare it explicitly.
- **Offset defaults to `0.0`** if omitted.
- **Multiple rules per biome** define the union of all matching regions in noise space.

## Benefits for the community

**For datapack creators:**
- Adding a custom biome = creating one small JSON file + adding it to a tag. No need to override a 7 MB monolith.
- Each biome's spawn conditions are readable at a glance. Mushroom fields is 1 rule, not buried among 7,593 entries.
- Biomes can be individually overridden, removed, or tweaked without conflicts between datapacks.

**For Mojang:**
- The format is backwards-compatible: the existing `biomes: [...]` inline array can remain supported alongside the new `biome_presets` tag reference.
- The game already resolves tags in many registries; this extends the same pattern to biome sources.
- At load time, the game expands implicit ranges and generates depth 0/1 pairs, so the runtime behavior is identical.

**Performance:**
- Parsing 262 KB of small files is faster than parsing a 6.8 MB monolith.
- The expanded in-memory representation remains identical.

## Attached files

A reference implementation is attached as a zip file containing the full proposed file structure with all 54 overworld biome presets generated from the current vanilla `overworld.json`. The data has been verified to cover the same parameter space as the original file.
