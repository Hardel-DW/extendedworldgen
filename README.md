# extendedworldgen — `biome_entry` registry

Fabric mod prototyping a `biome_entry` dynamic registry that lets datapacks describe multi-noise biome distributions in small, human-readable files instead of the 6.8 MB monolithic `overworld.json`.

The mod overrides the vanilla overworld and nether dimensions with the new format and expands them at load time into the exact same in-memory `Climate.ParameterList` vanilla would have built. The in-game worldgen is **byte-for-byte identical** to vanilla — verified numerically by a parity check against `MultiNoiseBiomeSourceParameterList.knownPresets()`.

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
      "type": "minecraft:multi_noise",
      "biome_entries": "#minecraft:overworld"
    }
  }
}
```

`biome_entries` is a tag reference. The vanilla `biomes` (inline list) and `preset` (named preset holder) variants of `multi_noise` continue to work — the mod only adds a third decoder branch.
- In theory, this also allows for the cleanup of other things that need to be verified, Biomes Codec, Preset Codec, registry `multi_noise_biome_source_parameter_list`, the OverworldBiomeBuilder converter to DataGen. MultiNoiseBiomeSourceParameterList class can be removed,  MultiNoiseBiomeSourceParameterLists the boostrap can be removed.

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

Each rule is a partial constraint on the seven multi-noise axes. Conventions:
- **Omitted axes default to `[-1.0, 1.0]`** (no constraint on that axis).
- **`depth` omitted** → the rule is duplicated into a surface entry (`depth = 0.0`) and an underground entry (`depth = 1.0`), matching vanilla's automatic split. Set `depth` explicitly only for biomes that occupy a non-standard layer.
- **`offset` omitted** → defaults to `0.0`.
- A range may be written as a single number (point), a `[min, max]` array, or `{min, max}` object. The standard `Climate.Parameter` codec is used unchanged.

Three illustrative entries from the bundled datapack:
```json
// mushroom_fields.json — only continentalness matters
{
  "biome": "minecraft:mushroom_fields",
  "rules": [{ "continentalness": [-1.2, -1.05] }]
}
```

```json
// deep_dark.json — explicit depth, no surface duplication
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

The end dimension is untouched: `TheEndBiomeSource` is not `multi_noise`, so it does not benefit from this format.

## Parity check
```
./gradlew runServer -Dextendedworldgen.parity=true
```

This is just a piece of code to test the compatibility between the old and new versions, so it's for devonly use, and it confirms that the worldgen remains exactly the same byte for byte.

