# New Dimension Format

Fabric mod prototyping a `biome_entry` dynamic registry and a single unified `minecraft:biome_entry` biome source that lets datapacks describe biome distributions in small, human-readable files — for all three vanilla dimensions, overworld, nether and end.

Internally the new source evaluates two kinds of rules in one pass: fitness-based multi-noise axes (like the current overworld code) for the overworld and nether, and ordered geometric constraints (chunk radius, density-function thresholds) for the end. The in-game worldgen is **byte-for-byte identical** to vanilla in all three dimensions — verified numerically against `MultiNoiseBiomeSourceParameterList.knownPresets()` for the overworld/nether and against `TheEndBiomeSource` for the end.

This is a feature proposal for Mojang, packaged as a runnable mod.

## Why
Vanilla's `data/minecraft/dimension/overworld.json` weighs 6.8 MB and contains 7,593 biome entries for only 54 unique biomes. Three structural redundancies cause the bloat:

1. **Depth duplication** — every entry exists twice, once with `depth: 0.0` and once with `depth: 1.0`. Only 3 biomes (`deep_dark`, `dripstone_caves`, `lush_caves`) actually need a custom depth.
2. **Verbose "any" ranges** — biomes like `mushroom_fields`, which only constrain `continentalness`, still spell out `[-1.0, 1.0]` for the other six axes.
3. **No range merging** — adjacent rectangular cells of the same biome are never coalesced. Desert alone uses 354 entries that could collapse into 32.

After removing all three:
| Old/New                      | Entries                            | File size                |
| ---------------------------- | ---------------------------------: | -----------------------: |
| Vanilla overworld            | 7,593                              | 6,824 KB (1 file)        |
| `biome_entry` (this mod)     | 881 rules → 1,759 `ParameterPoint` | ~262 KB across 54 files  |

Last parity run on the bundled datapack:

```
[parity] overworld:  OK (7593 samples, 1759 ours / 7593 vanilla entries)
[parity] the_nether: OK (5 samples,    5 ours    / 5 vanilla entries)
[parity] the_end:    OK (66049 samples on a 257x257 quart grid)
```

## Format

### Dimension JSON

```json
{
  "type": "minecraft:overworld",
  "generator": {
    "type": "minecraft:noise",
    "settings": "minecraft:overworld",
    "biome_source": {
      "type": "minecraft:biome_entry",
      "entries": "#minecraft:overworld"
    }
  }
}
```

`entries` is a tag reference. A single biome source type `minecraft:biome_entry` replaces both `minecraft:multi_noise` and `minecraft:the_end` for vanilla dimensions — the nether dimension uses `"entries": "#minecraft:the_nether"` and the end uses `"entries": "#minecraft:the_end"` with the same shape.

This opens the door to a wide cleanup in the Java code: the `biomes` inline codec and the `preset` codec on `MultiNoiseBiomeSource`, the `multi_noise_biome_source_parameter_list` dynamic registry, the `MultiNoiseBiomeSourceParameterList` class and its bootstrap, the hardcoded constructor of `TheEndBiomeSource` with its five `ResourceKey` fields — all of them become dead code. `OverworldBiomeBuilder` is only needed as a one-shot datagen tool to emit the vanilla `biome_entry` files, not as a runtime builder.

### Tag

`data/minecraft/tags/worldgen/biome_entry/overworld.json`:

```json
{
  "values": [
    "minecraft:plains",
    "minecraft:desert",
    "..."
  ]
}
```

### Biome entry

`data/minecraft/worldgen/biome_entry/<biome>.json`:

```json
{
  "biome": "minecraft:<biome>",
  "rules": [
    { ... }
  ]
}
```

A rule is a flat object carrying any subset of the constraint fields below. Two families coexist:

**Multi-noise axes** (fitness-based, used by overworld and nether):
- `temperature`, `humidity`, `continentalness`, `erosion`, `weirdness`, `depth` — each a `Climate.Parameter` (a single number, a `[min, max]` array, or a `{min, max}` object)
- `offset` — float, default `0.0`
- Omitted axes default to `[-1.0, 1.0]` (no constraint on that axis)
- Omitted `depth` → the rule is duplicated into a surface entry (`depth = 0.0`) and an underground entry (`depth = 1.0`), matching vanilla's automatic split. Set `depth` explicitly only for biomes that occupy a non-standard layer

**Geometric constraints** (ordered, first-match-wins, used by end):
- `within_chunk_radius` — a horizontal zone in chunk space. Two accepted forms:
  - **Shorthand** — a single integer, center at world origin: `"within_chunk_radius": 64` matches when `chunkX² + chunkZ² ≤ 64²`. This is the form vanilla's end central island check uses.
  - **Object** — explicit center coordinates: `"within_chunk_radius": { "x": 500, "z": -300, "radius": 32 }`. Both `x` and `z` default to `0` when omitted. Lets a datapack drop a biome into a fixed horizontal disc anywhere, not just at the origin. Only XZ — the zone is an infinite vertical cylinder. Pair with a `density_function` constraint on `channel: depth` in the same rule if you want a 3D shape.
- `density_function` — object with:
  - `channel` — one of `temperature`, `humidity`, `continentalness`, `erosion`, `depth`, `weirdness`. Selects which pre-bound density function from the current dimension's `Climate.Sampler` is read.
  - `above`, `at_least`, `below`, `at_most` — any subset, all `double`, ANDed. Four operators are needed because vanilla's end code mixes strict and non-strict comparisons (`h > 0.25` then `h >= -0.0625`) and a single inclusive `[min, max]` interval cannot express that without losing parity at the boundaries.
  - `sample_position` — `block` (default) or `section_center`. The end samples at the center of each 16×16 section, so end entries set it explicitly.

A rule that has any geometric field is **ordered**: evaluated in tag-then-rule order before any fitness-based matching, first match wins. A rule with only multi-noise axes is **fitness**: expanded into `Climate.ParameterPoint`s and placed in a shared `Climate.ParameterList`, exactly like vanilla.

Examples from the bundled datapack:

```json
// mushroom_fields.json — only continentalness matters (overworld)
{
  "biome": "minecraft:mushroom_fields",
  "rules": [{ "continentalness": [-1.2, -1.05] }]
}
```

```json
// deep_dark.json — explicit depth, no surface duplication (overworld)
{
  "biome": "minecraft:deep_dark",
  "rules": [{ "erosion": [-1.0, -0.375], "depth": 1.1 }]
}
```

```json
// warped_forest.json — non-zero offset (nether)
{
  "biome": "minecraft:warped_forest",
  "rules": [{
    "temperature": 0.0, "humidity": 0.5, "continentalness": 0.0,
    "erosion": 0.0, "weirdness": 0.0, "depth": 0.0, "offset": 0.375
  }]
}
```

```json
// the_end.json — central island, zone-based
{
  "biome": "minecraft:the_end",
  "rules": [{ "within_chunk_radius": 64 }]
}
```

```json
// end_highlands.json — outer ring, erosion threshold
{
  "biome": "minecraft:end_highlands",
  "rules": [{
    "density_function": {
      "channel": "erosion",
      "above": 0.25,
      "sample_position": "section_center"
    }
  }]
}
```

```json
// end_barrens.json — open/closed mix, non-overlapping with the other end biomes
{
  "biome": "minecraft:end_barrens",
  "rules": [{
    "density_function": {
      "channel": "erosion",
      "at_least": -0.21875,
      "below": -0.0625,
      "sample_position": "section_center"
    }
  }]
}
```

## Parity check
```
./gradlew runServer -Dextendedworldgen.parity=true
```

Dev-only sanity net, gated by the system property so it never runs in normal play. It compares the datapack-driven selection against the untouched vanilla references in all three dimensions:

- **Overworld / nether** — pulls the fitness `Climate.ParameterList` out of our source and samples the centroid of every vanilla `Climate.ParameterPoint`, comparing `findValue` results against `MultiNoiseBiomeSourceParameterList.knownPresets()`.
- **End** — instantiates a vanilla `TheEndBiomeSource` and walks a 257×257 grid of quart-coordinates covering the central island and the outer ring, comparing `getNoiseBiome` results using the real `Climate.Sampler` from the end dimension's chunk generator.

On any divergence the server startup aborts with the offending coordinates and the two biome ids.

