package fr.hardel.worldgen.parity;

import com.mojang.datafixers.util.Pair;
import fr.hardel.ExtendedWorldGen;
import fr.hardel.worldgen.biomeentry.BiomeEntry;
import fr.hardel.worldgen.biomeentry.BiomeEntryExpander;
import fr.hardel.worldgen.biomeentry.BiomeEntryRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;

/**
 * Dev-only safety net comparing the {@link Climate.ParameterList} produced by our {@code biome_entry}
 * datapack against the vanilla overworld and nether parameter lists. Activated by passing
 * {@code -Dextendedworldgen.parity=true} to the JVM.
 * <p>
 * For each {@link Climate.ParameterPoint} in the vanilla list, we sample the centroid of its
 * 6-axis cell, then ask both lists which biome they would return for that point. Any divergence
 * is logged with the offending coordinates and biome IDs. The check is intentionally one-shot at
 * server start so it never runs on a production server.
 */
public final class BiomeEntryParityCheck {
    private static final String SYSTEM_PROPERTY = "extendedworldgen.parity";
    private static final TagKey<BiomeEntry> OVERWORLD_TAG = TagKey.create(BiomeEntryRegistry.KEY, Identifier.withDefaultNamespace("overworld"));
    private static final TagKey<BiomeEntry> NETHER_TAG = TagKey.create(BiomeEntryRegistry.KEY, Identifier.withDefaultNamespace("the_nether"));

    private BiomeEntryParityCheck() {}

    public static void registerIfEnabled() {
        if (!Boolean.getBoolean(SYSTEM_PROPERTY)) {
            return;
        }
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            Registry<BiomeEntry> entries = server.registryAccess().lookupOrThrow(BiomeEntryRegistry.KEY);
            Registry<Biome> biomes = server.registryAccess().lookupOrThrow(Registries.BIOME);
            Map<MultiNoiseBiomeSourceParameterList.Preset, Climate.ParameterList<ResourceKey<Biome>>> vanillaPresets =
                    MultiNoiseBiomeSourceParameterList.knownPresets();
            checkPreset(
                    "overworld",
                    OVERWORLD_TAG,
                    entries,
                    biomes,
                    vanillaPresets.get(MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD)
            );
            checkPreset(
                    "the_nether",
                    NETHER_TAG,
                    entries,
                    biomes,
                    vanillaPresets.get(MultiNoiseBiomeSourceParameterList.Preset.NETHER)
            );
        });
    }

    private static void checkPreset(
            final String label,
            final TagKey<BiomeEntry> tag,
            final Registry<BiomeEntry> entries,
            final Registry<Biome> biomes,
            final Climate.ParameterList<ResourceKey<Biome>> vanilla
    ) {
        Optional<HolderSet.Named<BiomeEntry>> tagSet = entries.get(tag);
        if (tagSet.isEmpty()) {
            ExtendedWorldGen.LOGGER.error("[parity] tag {} is empty or missing — cannot check {}", tag.location(), label);
            return;
        }
        Climate.ParameterList<Holder<Biome>> ours = BiomeEntryExpander.expand(tagSet.get());
        List<Divergence> divergences = new ArrayList<>();
        for (Pair<Climate.ParameterPoint, ResourceKey<Biome>> sample : vanilla.values()) {
            Climate.TargetPoint target = centroid(sample.getFirst());
            ResourceKey<Biome> vanillaBiome = vanilla.findValue(target);
            ResourceKey<Biome> ourBiome = ours.findValue(target).unwrapKey().orElse(null);
            if (ourBiome == null || !ourBiome.equals(vanillaBiome)) {
                divergences.add(new Divergence(target, vanillaBiome, ourBiome));
            }
        }
        if (divergences.isEmpty()) {
            ExtendedWorldGen.LOGGER.info("[parity] {}: OK ({} samples, {} ours / {} vanilla entries)",
                    label, vanilla.values().size(), ours.values().size(), vanilla.values().size());
            return;
        }
        ExtendedWorldGen.LOGGER.error("[parity] {}: {} divergences out of {} samples", label, divergences.size(), vanilla.values().size());
        int previewLimit = Math.min(divergences.size(), 20);
        for (int i = 0; i < previewLimit; i++) {
            Divergence d = divergences.get(i);
            ExtendedWorldGen.LOGGER.error("[parity]   at {} -> vanilla={} ours={}", d.target, d.vanilla, d.ours);
        }
        throw new IllegalStateException("biome_entry parity check failed for " + label + ": " + divergences.size() + " divergences");
    }

    private static Climate.TargetPoint centroid(final Climate.ParameterPoint point) {
        return new Climate.TargetPoint(
                midpoint(point.temperature()),
                midpoint(point.humidity()),
                midpoint(point.continentalness()),
                midpoint(point.erosion()),
                midpoint(point.depth()),
                midpoint(point.weirdness())
        );
    }

    private static long midpoint(final Climate.Parameter parameter) {
        return (parameter.min() + parameter.max()) / 2L;
    }

    private record Divergence(Climate.TargetPoint target, ResourceKey<Biome> vanilla, ResourceKey<Biome> ours) {}
}
