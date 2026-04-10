package fr.hardel.worldgen.biomeentry;

import com.google.common.collect.ImmutableList;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.MapCodec;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryCodecs;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

/**
 * Biome source backed by a tag of {@link BiomeEntry} items, unifying multi-noise (overworld,
 * nether) and geometric (end) biome selection in a single source type.
 * <p>
 * At construction time, the rules of every entry are partitioned into two groups:
 * <ul>
 *   <li><b>Fitness rules</b> — pure multi-noise rules with no geometric constraint — expanded into
 *       {@link Climate.ParameterPoint}s and placed in a {@link Climate.ParameterList}, so matching
 *       uses vanilla's R-tree fitness behaviour.</li>
 *   <li><b>Ordered rules</b> — any rule carrying a geometric constraint — kept in tag-then-rule
 *       order, evaluated first-match-wins before the fitness phase.</li>
 * </ul>
 * This two-phase dispatch is what lets one source handle the End's zone + erosion-threshold
 * logic and the overworld's axis-fitness logic without mixing the two evaluation engines.
 */
public class BiomeEntryBiomeSource extends BiomeSource {
    public static final MapCodec<BiomeEntryBiomeSource> CODEC = RegistryCodecs.homogeneousList(BiomeEntryRegistry.KEY)
            .fieldOf("entries")
            .xmap(BiomeEntryBiomeSource::new, BiomeEntryBiomeSource::entries);

    private static final Climate.Parameter FULL_RANGE = Climate.Parameter.span(-1.0F, 1.0F);
    private static final Climate.Parameter DEPTH_SURFACE = Climate.Parameter.point(0.0F);
    private static final Climate.Parameter DEPTH_UNDERGROUND = Climate.Parameter.point(1.0F);

    private final HolderSet<BiomeEntry> entries;
    private final List<OrderedRule> orderedRules;
    private final Optional<Climate.ParameterList<Holder<Biome>>> parameterList;

    public BiomeEntryBiomeSource(final HolderSet<BiomeEntry> entries) {
        this.entries = entries;
        ImmutableList.Builder<OrderedRule> ordered = ImmutableList.builder();
        ImmutableList.Builder<Pair<Climate.ParameterPoint, Holder<Biome>>> fitness = ImmutableList.builder();
        for (Holder<BiomeEntry> holder : entries) {
            BiomeEntry entry = holder.value();
            Holder<Biome> biome = entry.biome();
            for (BiomeEntry.Rule rule : entry.rules()) {
                if (rule.isGeometric()) {
                    ordered.add(new OrderedRule(biome, rule));
                } else {
                    expandFitnessRule(rule, biome, fitness);
                }
            }
        }
        this.orderedRules = ordered.build();
        ImmutableList<Pair<Climate.ParameterPoint, Holder<Biome>>> fitnessList = fitness.build();
        this.parameterList = fitnessList.isEmpty()
                ? Optional.empty()
                : Optional.of(new Climate.ParameterList<>(fitnessList));
    }

    public HolderSet<BiomeEntry> entries() {
        return this.entries;
    }

    /**
     * Exposed for the parity check. The returned list, if present, is the fitness phase that
     * the runtime would consult after the ordered rules; callers can compare it directly against
     * a vanilla {@link Climate.ParameterList} without going through {@link #getNoiseBiome}.
     */
    public Optional<Climate.ParameterList<Holder<Biome>>> fitnessList() {
        return this.parameterList;
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return this.entries.stream().map(Holder::value).map(BiomeEntry::biome).distinct();
    }

    @Override
    public Holder<Biome> getNoiseBiome(final int quartX, final int quartY, final int quartZ, final Climate.Sampler sampler) {
        for (OrderedRule rule : this.orderedRules) {
            if (rule.matches(quartX, quartY, quartZ, sampler)) {
                return rule.biome();
            }
        }
        if (this.parameterList.isPresent()) {
            return this.parameterList.get().findValue(sampler.sample(quartX, quartY, quartZ));
        }
        throw new IllegalStateException("BiomeEntryBiomeSource: no ordered rule matched and no fitness rules exist at quart ("
                + quartX + ", " + quartY + ", " + quartZ + "). Datapack rules must cover the full space.");
    }

    private static void expandFitnessRule(
            final BiomeEntry.Rule rule,
            final Holder<Biome> biome,
            final ImmutableList.Builder<Pair<Climate.ParameterPoint, Holder<Biome>>> sink
    ) {
        Climate.Parameter temperature = rule.temperature().orElse(FULL_RANGE);
        Climate.Parameter humidity = rule.humidity().orElse(FULL_RANGE);
        Climate.Parameter continentalness = rule.continentalness().orElse(FULL_RANGE);
        Climate.Parameter erosion = rule.erosion().orElse(FULL_RANGE);
        Climate.Parameter weirdness = rule.weirdness().orElse(FULL_RANGE);
        long offset = Climate.quantizeCoord(rule.offset());
        if (rule.depth().isPresent()) {
            sink.add(Pair.of(
                    new Climate.ParameterPoint(temperature, humidity, continentalness, erosion, rule.depth().get(), weirdness, offset),
                    biome
            ));
            return;
        }
        sink.add(Pair.of(
                new Climate.ParameterPoint(temperature, humidity, continentalness, erosion, DEPTH_SURFACE, weirdness, offset),
                biome
        ));
        sink.add(Pair.of(
                new Climate.ParameterPoint(temperature, humidity, continentalness, erosion, DEPTH_UNDERGROUND, weirdness, offset),
                biome
        ));
    }

    /**
     * One geometric rule bound to its biome, with a self-contained matcher.
     */
    private record OrderedRule(Holder<Biome> biome, BiomeEntry.Rule rule) {
        boolean matches(final int quartX, final int quartY, final int quartZ, final Climate.Sampler sampler) {
            int blockX = QuartPos.toBlock(quartX);
            int blockY = QuartPos.toBlock(quartY);
            int blockZ = QuartPos.toBlock(quartZ);
            if (this.rule.withinChunkRadius().isPresent()
                    && !this.rule.withinChunkRadius().get().matches(blockX >> 4, blockZ >> 4)) {
                return false;
            }
            if (this.rule.densityFunction().isPresent()
                    && !this.rule.densityFunction().get().matches(sampler, blockX, blockY, blockZ)) {
                return false;
            }
            if (hasMultiNoiseConstraint(this.rule)) {
                Climate.TargetPoint target = sampler.sample(quartX, quartY, quartZ);
                if (!axisContains(this.rule.temperature(), target.temperature())) return false;
                if (!axisContains(this.rule.humidity(), target.humidity())) return false;
                if (!axisContains(this.rule.continentalness(), target.continentalness())) return false;
                if (!axisContains(this.rule.erosion(), target.erosion())) return false;
                if (!axisContains(this.rule.weirdness(), target.weirdness())) return false;
                if (!axisContains(this.rule.depth(), target.depth())) return false;
            }
            return true;
        }

        private static boolean hasMultiNoiseConstraint(final BiomeEntry.Rule rule) {
            return rule.temperature().isPresent()
                    || rule.humidity().isPresent()
                    || rule.continentalness().isPresent()
                    || rule.erosion().isPresent()
                    || rule.weirdness().isPresent()
                    || rule.depth().isPresent();
        }

        private static boolean axisContains(final Optional<Climate.Parameter> axis, final long quantized) {
            if (axis.isEmpty()) return true;
            Climate.Parameter p = axis.get();
            return quantized >= p.min() && quantized <= p.max();
        }
    }
}
