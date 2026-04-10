package fr.hardel.worldgen.parity;

import com.mojang.datafixers.util.Pair;
import fr.hardel.ExtendedWorldGen;
import fr.hardel.worldgen.biomeentry.BiomeEntry;
import fr.hardel.worldgen.biomeentry.BiomeEntryBiomeSource;
import fr.hardel.worldgen.biomeentry.BiomeEntryRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.biome.TheEndBiomeSource;

/**
 * Dev-only safety net comparing the datapack-driven biome selection against the untouched vanilla
 * reference implementations. Activated by passing {@code -Dextendedworldgen.parity=true} to the
 * JVM. On divergence the server startup aborts with the offending coordinates and biome ids.
 * <p>
 * Two different comparison strategies are used:
 * <ul>
 *   <li><b>Overworld / nether</b> — pure multi-noise. The check pulls the fitness
 *       {@link Climate.ParameterList} out of our {@link BiomeEntryBiomeSource} and samples the
 *       centroid of every vanilla {@link Climate.ParameterPoint}, comparing
 *       {@code findValue} results.</li>
 *   <li><b>End</b> — zone + density-function thresholds. The check instantiates a vanilla
 *       {@link TheEndBiomeSource} and our {@link BiomeEntryBiomeSource}, then iterates a block
 *       grid large enough to cover both the central island and the outer ring, comparing
 *       {@code getNoiseBiome} results using the real {@link Climate.Sampler} from the end
 *       dimension's chunk generator.</li>
 * </ul>
 */
public final class BiomeEntryParityCheck {
    private static final String SYSTEM_PROPERTY = "extendedworldgen.parity";
    private static final TagKey<BiomeEntry> OVERWORLD_TAG = TagKey.create(BiomeEntryRegistry.KEY, Identifier.withDefaultNamespace("overworld"));
    private static final TagKey<BiomeEntry> NETHER_TAG = TagKey.create(BiomeEntryRegistry.KEY, Identifier.withDefaultNamespace("the_nether"));
    private static final TagKey<BiomeEntry> END_TAG = TagKey.create(BiomeEntryRegistry.KEY, Identifier.withDefaultNamespace("the_end"));
    private static final int END_SAMPLE_QUART_RADIUS = 512;
    private static final int END_SAMPLE_QUART_STEP = 4;

    private BiomeEntryParityCheck() {}

    public static void registerIfEnabled() {
        if (!Boolean.getBoolean(SYSTEM_PROPERTY)) {
            return;
        }
        ServerLifecycleEvents.SERVER_STARTED.register(BiomeEntryParityCheck::runAll);
    }

    private static void runAll(final MinecraftServer server) {
        Registry<BiomeEntry> entries = server.registryAccess().lookupOrThrow(BiomeEntryRegistry.KEY);
        Map<MultiNoiseBiomeSourceParameterList.Preset, Climate.ParameterList<ResourceKey<Biome>>> vanillaPresets =
                MultiNoiseBiomeSourceParameterList.knownPresets();
        checkMultiNoise(
                "overworld",
                OVERWORLD_TAG,
                entries,
                vanillaPresets.get(MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD)
        );
        checkMultiNoise(
                "the_nether",
                NETHER_TAG,
                entries,
                vanillaPresets.get(MultiNoiseBiomeSourceParameterList.Preset.NETHER)
        );
        checkEnd(server, entries);
    }

    private static void checkMultiNoise(
            final String label,
            final TagKey<BiomeEntry> tag,
            final Registry<BiomeEntry> entries,
            final Climate.ParameterList<ResourceKey<Biome>> vanilla
    ) {
        Optional<HolderSet.Named<BiomeEntry>> tagSet = entries.get(tag);
        if (tagSet.isEmpty()) {
            ExtendedWorldGen.LOGGER.error("[parity] tag {} is empty or missing — cannot check {}", tag.location(), label);
            return;
        }
        BiomeEntryBiomeSource source = new BiomeEntryBiomeSource(tagSet.get());
        Optional<Climate.ParameterList<Holder<Biome>>> ours = source.fitnessList();
        if (ours.isEmpty()) {
            ExtendedWorldGen.LOGGER.error("[parity] {}: fitness list is empty — expected multi-noise rules in the tag", label);
            return;
        }
        Climate.ParameterList<Holder<Biome>> oursList = ours.get();
        List<MultiNoiseDivergence> divergences = new ArrayList<>();
        for (Pair<Climate.ParameterPoint, ResourceKey<Biome>> sample : vanilla.values()) {
            Climate.TargetPoint target = centroid(sample.getFirst());
            ResourceKey<Biome> vanillaBiome = vanilla.findValue(target);
            ResourceKey<Biome> ourBiome = oursList.findValue(target).unwrapKey().orElse(null);
            if (ourBiome == null || !ourBiome.equals(vanillaBiome)) {
                divergences.add(new MultiNoiseDivergence(target, vanillaBiome, ourBiome));
            }
        }
        if (divergences.isEmpty()) {
            ExtendedWorldGen.LOGGER.info("[parity] {}: OK ({} samples, {} ours / {} vanilla entries)",
                    label, vanilla.values().size(), oursList.values().size(), vanilla.values().size());
            return;
        }
        ExtendedWorldGen.LOGGER.error("[parity] {}: {} divergences out of {} samples", label, divergences.size(), vanilla.values().size());
        int previewLimit = Math.min(divergences.size(), 20);
        for (int i = 0; i < previewLimit; i++) {
            MultiNoiseDivergence d = divergences.get(i);
            ExtendedWorldGen.LOGGER.error("[parity]   at {} -> vanilla={} ours={}", d.target, d.vanilla, d.ours);
        }
        throw new IllegalStateException("biome_entry parity check failed for " + label + ": " + divergences.size() + " divergences");
    }

    private static void checkEnd(final MinecraftServer server, final Registry<BiomeEntry> entries) {
        Optional<HolderSet.Named<BiomeEntry>> tagSet = entries.get(END_TAG);
        if (tagSet.isEmpty()) {
            ExtendedWorldGen.LOGGER.error("[parity] tag {} is empty or missing — cannot check end", END_TAG.location());
            return;
        }
        ServerLevel endLevel = server.getLevel(Level.END);
        if (endLevel == null) {
            ExtendedWorldGen.LOGGER.error("[parity] end level not loaded — cannot check end");
            return;
        }
        Climate.Sampler sampler = endLevel.getChunkSource().randomState().sampler();
        HolderGetter<Biome> biomes = server.registryAccess().lookupOrThrow(Registries.BIOME);
        TheEndBiomeSource vanilla = TheEndBiomeSource.create(biomes);
        BiomeEntryBiomeSource ours = new BiomeEntryBiomeSource(tagSet.get());

        List<EndDivergence> divergences = new ArrayList<>();
        int totalSamples = 0;
        for (int qx = -END_SAMPLE_QUART_RADIUS; qx <= END_SAMPLE_QUART_RADIUS; qx += END_SAMPLE_QUART_STEP) {
            for (int qz = -END_SAMPLE_QUART_RADIUS; qz <= END_SAMPLE_QUART_RADIUS; qz += END_SAMPLE_QUART_STEP) {
                totalSamples++;
                ResourceKey<Biome> vanillaBiome = vanilla.getNoiseBiome(qx, 0, qz, sampler).unwrapKey().orElse(null);
                ResourceKey<Biome> ourBiome = ours.getNoiseBiome(qx, 0, qz, sampler).unwrapKey().orElse(null);
                if (vanillaBiome == null || !vanillaBiome.equals(ourBiome)) {
                    divergences.add(new EndDivergence(qx, qz, vanillaBiome, ourBiome));
                }
            }
        }
        if (divergences.isEmpty()) {
            ExtendedWorldGen.LOGGER.info("[parity] the_end: OK ({} samples on a {}x{} quart grid)",
                    totalSamples, 2 * END_SAMPLE_QUART_RADIUS / END_SAMPLE_QUART_STEP + 1,
                    2 * END_SAMPLE_QUART_RADIUS / END_SAMPLE_QUART_STEP + 1);
            return;
        }
        ExtendedWorldGen.LOGGER.error("[parity] the_end: {} divergences out of {} samples", divergences.size(), totalSamples);
        int previewLimit = Math.min(divergences.size(), 20);
        for (int i = 0; i < previewLimit; i++) {
            EndDivergence d = divergences.get(i);
            ExtendedWorldGen.LOGGER.error("[parity]   at quart ({}, 0, {}) -> vanilla={} ours={}", d.quartX, d.quartZ, d.vanilla, d.ours);
        }
        throw new IllegalStateException("biome_entry parity check failed for the_end: " + divergences.size() + " divergences");
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

    private record MultiNoiseDivergence(Climate.TargetPoint target, ResourceKey<Biome> vanilla, ResourceKey<Biome> ours) {}
    private record EndDivergence(int quartX, int quartZ, ResourceKey<Biome> vanilla, ResourceKey<Biome> ours) {}
}
