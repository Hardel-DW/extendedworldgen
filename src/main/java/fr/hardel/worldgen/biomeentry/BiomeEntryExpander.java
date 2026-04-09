package fr.hardel.worldgen.biomeentry;

import com.google.common.collect.ImmutableList;
import com.mojang.datafixers.util.Pair;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;

/**
 * Expands a {@link HolderSet} of {@link BiomeEntry} into a flat {@link Climate.ParameterList} of
 * {@link Climate.ParameterPoint}s consumable by {@code MultiNoiseBiomeSource.createFromList}.
 * <p>
 * Two responsibilities and only two: filling missing axes with the full range, and duplicating
 * each rule with no explicit depth into a surface ({@code depth = 0.0}) and an underground
 * ({@code depth = 1.0}) entry — exactly mirroring how vanilla writes its overworld parameter list.
 */
public final class BiomeEntryExpander {
    private static final Climate.Parameter FULL_RANGE = Climate.Parameter.span(-1.0F, 1.0F);
    private static final Climate.Parameter DEPTH_SURFACE = Climate.Parameter.point(0.0F);
    private static final Climate.Parameter DEPTH_UNDERGROUND = Climate.Parameter.point(1.0F);

    private BiomeEntryExpander() {}

    public static Climate.ParameterList<Holder<Biome>> expand(final HolderSet<BiomeEntry> entries) {
        ImmutableList.Builder<Pair<Climate.ParameterPoint, Holder<Biome>>> builder = ImmutableList.builder();
        for (Holder<BiomeEntry> entryHolder : entries) {
            BiomeEntry entry = entryHolder.value();
            Holder<Biome> biome = entry.biome();
            for (BiomeEntry.Rule rule : entry.rules()) {
                expandRule(rule, biome, builder::add);
            }
        }
        return new Climate.ParameterList<>(builder.build());
    }

    private static void expandRule(
            final BiomeEntry.Rule rule,
            final Holder<Biome> biome,
            final Consumer<Pair<Climate.ParameterPoint, Holder<Biome>>> sink
    ) {
        Climate.Parameter temperature = rule.temperature().orElse(FULL_RANGE);
        Climate.Parameter humidity = rule.humidity().orElse(FULL_RANGE);
        Climate.Parameter continentalness = rule.continentalness().orElse(FULL_RANGE);
        Climate.Parameter erosion = rule.erosion().orElse(FULL_RANGE);
        Climate.Parameter weirdness = rule.weirdness().orElse(FULL_RANGE);
        long offset = Climate.quantizeCoord(rule.offset());

        if (rule.depth().isPresent()) {
            sink.accept(Pair.of(
                    new Climate.ParameterPoint(temperature, humidity, continentalness, erosion, rule.depth().get(), weirdness, offset),
                    biome
            ));
            return;
        }
        sink.accept(Pair.of(
                new Climate.ParameterPoint(temperature, humidity, continentalness, erosion, DEPTH_SURFACE, weirdness, offset),
                biome
        ));
        sink.accept(Pair.of(
                new Climate.ParameterPoint(temperature, humidity, continentalness, erosion, DEPTH_UNDERGROUND, weirdness, offset),
                biome
        ));
    }
}
