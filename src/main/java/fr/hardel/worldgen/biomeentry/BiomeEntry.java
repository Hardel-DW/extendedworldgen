package fr.hardel.worldgen.biomeentry;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.core.Holder;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunction.SinglePointContext;

/**
 * Datapack-driven biome entry: a biome plus a set of rules describing where it spawns.
 * <p>
 * A rule is a conjunction of optional constraints. Two families of constraints coexist:
 * <ul>
 *   <li><b>Multi-noise constraints</b> — the seven climate axes, matched by fitness through a
 *       {@link Climate.ParameterList} R-tree exactly as vanilla does for the overworld.</li>
 *   <li><b>Geometric constraints</b> — {@code within_chunk_radius} and {@code density_function}
 *       checks, used by the End to replicate its center-island zone plus erosion-threshold
 *       selection. Rules containing any geometric constraint are evaluated in ordered (tag then
 *       rule order) first-match-wins fashion, before the fitness phase.</li>
 * </ul>
 * Omitted multi-noise axes default to {@code [-1.0, 1.0]}, an omitted {@code depth} is duplicated
 * into surface/underground entries, and an omitted {@code offset} defaults to {@code 0.0}. These
 * conventions only apply when the rule has no geometric constraint — a geometric rule is taken
 * literally with no implicit axis filling.
 */
public record BiomeEntry(Holder<Biome> biome, List<Rule> rules) {
    public static final Codec<BiomeEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
            Biome.CODEC.fieldOf("biome").forGetter(BiomeEntry::biome),
            Rule.CODEC.listOf().fieldOf("rules").forGetter(BiomeEntry::rules)
    ).apply(i, BiomeEntry::new));

    public record Rule(
            Optional<Climate.Parameter> temperature,
            Optional<Climate.Parameter> humidity,
            Optional<Climate.Parameter> continentalness,
            Optional<Climate.Parameter> erosion,
            Optional<Climate.Parameter> weirdness,
            Optional<Climate.Parameter> depth,
            float offset,
            Optional<WithinChunkRadius> withinChunkRadius,
            Optional<DensityFunctionConstraint> densityFunction
    ) {
        public static final Codec<Rule> CODEC = RecordCodecBuilder.create(i -> i.group(
                Climate.Parameter.CODEC.optionalFieldOf("temperature").forGetter(Rule::temperature),
                Climate.Parameter.CODEC.optionalFieldOf("humidity").forGetter(Rule::humidity),
                Climate.Parameter.CODEC.optionalFieldOf("continentalness").forGetter(Rule::continentalness),
                Climate.Parameter.CODEC.optionalFieldOf("erosion").forGetter(Rule::erosion),
                Climate.Parameter.CODEC.optionalFieldOf("weirdness").forGetter(Rule::weirdness),
                Climate.Parameter.CODEC.optionalFieldOf("depth").forGetter(Rule::depth),
                Codec.floatRange(0.0F, 1.0F).optionalFieldOf("offset", 0.0F).forGetter(Rule::offset),
                WithinChunkRadius.CODEC.optionalFieldOf("within_chunk_radius").forGetter(Rule::withinChunkRadius),
                DensityFunctionConstraint.CODEC.optionalFieldOf("density_function").forGetter(Rule::densityFunction)
        ).apply(i, Rule::new));

        /**
         * A rule is <em>ordered</em> (evaluated first-match-wins in the geometric phase) if it
         * carries any geometric constraint. Rules without any geometric constraint participate in
         * the fitness-based {@link Climate.ParameterList} exactly like vanilla multi-noise.
         */
        public boolean isGeometric() {
            return this.withinChunkRadius.isPresent() || this.densityFunction.isPresent();
        }
    }

    /**
     * A horizontal zone constraint in chunk space: matches when the current chunk position is
     * within {@code radius} chunks (Euclidean distance) of the center {@code (centerX, centerZ)}.
     * Y is not involved — the zone is an infinite vertical cylinder, matching vanilla's End
     * central island check.
     * <p>
     * Two JSON forms are accepted:
     * <ul>
     *   <li>Shorthand — a single integer, interpreted as the radius with the center at the world
     *       origin {@code (0, 0)}: {@code "within_chunk_radius": 64}</li>
     *   <li>Object — explicit center coordinates, useful when you want a zone anywhere other than
     *       the origin: {@code "within_chunk_radius": \{ "x": 500, "z": -300, "radius": 64 \}}.
     *       Both {@code x} and {@code z} default to 0 when omitted.</li>
     * </ul>
     */
    public record WithinChunkRadius(int centerX, int centerZ, int radius) {
        private static final Codec<WithinChunkRadius> OBJECT_CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.optionalFieldOf("x", 0).forGetter(WithinChunkRadius::centerX),
                Codec.INT.optionalFieldOf("z", 0).forGetter(WithinChunkRadius::centerZ),
                Codec.intRange(0, 1_875_000).fieldOf("radius").forGetter(WithinChunkRadius::radius)
        ).apply(i, WithinChunkRadius::new));

        public static final Codec<WithinChunkRadius> CODEC = Codec.either(Codec.intRange(0, 1_875_000), OBJECT_CODEC)
                .xmap(
                        either -> either.map(r -> new WithinChunkRadius(0, 0, r), Function.identity()),
                        zone -> zone.centerX == 0 && zone.centerZ == 0 ? Either.left(zone.radius) : Either.right(zone)
                );

        public boolean matches(final int chunkX, final int chunkZ) {
            long dx = (long) chunkX - this.centerX;
            long dz = (long) chunkZ - this.centerZ;
            long r = this.radius;
            return dx * dx + dz * dz <= r * r;
        }
    }

    /**
     * A constraint on a {@link Climate.Sampler} channel value sampled at the query position. Any
     * combination of {@code above}, {@code atLeast}, {@code below}, {@code atMost} may be
     * provided; they are ANDed together, giving both open and closed bounds without a new
     * interval type.
     * <p>
     * We route through {@link Climate.Sampler} rather than looking up a raw
     * {@link DensityFunction} by id because the raw version from the registry is unbound — it
     * needs to be resolved against a {@code RandomState} before it can be computed. The sampler
     * is constructed per chunk generator and carries the already-bound density functions for the
     * six climate channels, which is exactly what vanilla's {@code TheEndBiomeSource} does.
     */
    public record DensityFunctionConstraint(
            SamplerChannel channel,
            Optional<Double> above,
            Optional<Double> atLeast,
            Optional<Double> below,
            Optional<Double> atMost,
            SamplePosition samplePosition
    ) {
        public static final Codec<DensityFunctionConstraint> CODEC = RecordCodecBuilder.create(i -> i.group(
                SamplerChannel.CODEC.fieldOf("channel").forGetter(DensityFunctionConstraint::channel),
                Codec.DOUBLE.optionalFieldOf("above").forGetter(DensityFunctionConstraint::above),
                Codec.DOUBLE.optionalFieldOf("at_least").forGetter(DensityFunctionConstraint::atLeast),
                Codec.DOUBLE.optionalFieldOf("below").forGetter(DensityFunctionConstraint::below),
                Codec.DOUBLE.optionalFieldOf("at_most").forGetter(DensityFunctionConstraint::atMost),
                SamplePosition.CODEC.optionalFieldOf("sample_position", SamplePosition.BLOCK).forGetter(DensityFunctionConstraint::samplePosition)
        ).apply(i, DensityFunctionConstraint::new));

        public boolean matches(final Climate.Sampler sampler, final int blockX, final int blockY, final int blockZ) {
            int sampleX = this.samplePosition == SamplePosition.SECTION_CENTER ? (blockX >> 4) * 16 + 8 : blockX;
            int sampleZ = this.samplePosition == SamplePosition.SECTION_CENTER ? (blockZ >> 4) * 16 + 8 : blockZ;
            double value = this.channel.sample(sampler).compute(new SinglePointContext(sampleX, blockY, sampleZ));
            if (this.above.isPresent() && !(value > this.above.get())) return false;
            if (this.atLeast.isPresent() && !(value >= this.atLeast.get())) return false;
            if (this.below.isPresent() && !(value < this.below.get())) return false;
            if (this.atMost.isPresent() && !(value <= this.atMost.get())) return false;
            return true;
        }
    }

    /**
     * One of the six channels of {@link Climate.Sampler}, used to dispatch the density function
     * lookup at rule evaluation time.
     */
    public enum SamplerChannel implements StringRepresentable {
        TEMPERATURE("temperature") {
            @Override public DensityFunction sample(final Climate.Sampler sampler) { return sampler.temperature(); }
        },
        HUMIDITY("humidity") {
            @Override public DensityFunction sample(final Climate.Sampler sampler) { return sampler.humidity(); }
        },
        CONTINENTALNESS("continentalness") {
            @Override public DensityFunction sample(final Climate.Sampler sampler) { return sampler.continentalness(); }
        },
        EROSION("erosion") {
            @Override public DensityFunction sample(final Climate.Sampler sampler) { return sampler.erosion(); }
        },
        DEPTH("depth") {
            @Override public DensityFunction sample(final Climate.Sampler sampler) { return sampler.depth(); }
        },
        WEIRDNESS("weirdness") {
            @Override public DensityFunction sample(final Climate.Sampler sampler) { return sampler.weirdness(); }
        };

        public static final Codec<SamplerChannel> CODEC = StringRepresentable.fromEnum(SamplerChannel::values);

        private final String name;

        SamplerChannel(final String name) {
            this.name = name;
        }

        public abstract DensityFunction sample(Climate.Sampler sampler);

        @Override
        public String getSerializedName() {
            return this.name;
        }
    }

    /**
     * Where in the local 16-block section the density function should be sampled.
     * <ul>
     *   <li>{@link #BLOCK} — the raw query block coordinate (quart × 4).</li>
     *   <li>{@link #SECTION_CENTER} — the block at the center of the 16×16 section, matching
     *       vanilla's End sampling: {@code (chunkX * 16 + 8, y, chunkZ * 16 + 8)}.</li>
     * </ul>
     */
    public enum SamplePosition implements StringRepresentable {
        BLOCK("block"),
        SECTION_CENTER("section_center");

        public static final Codec<SamplePosition> CODEC = StringRepresentable.fromEnum(SamplePosition::values);

        private final String name;

        SamplePosition(final String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return this.name;
        }
    }
}
