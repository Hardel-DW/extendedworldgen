package fr.hardel.worldgen.biomeentry;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;

/**
 * Datapack-driven biome entry: a biome plus a set of noise-space rules describing where it spawns.
 * <p>
 * Each {@link Rule} is a partial constraint on the seven multi-noise axes. Any axis omitted in the
 * JSON is treated as the full range {@code [-1.0, 1.0]}, and an omitted {@code depth} is expanded
 * into both surface ({@code 0.0}) and underground ({@code 1.0}) entries to mirror vanilla's
 * automatic depth duplication. The {@code offset} field defaults to {@code 0.0}.
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
            float offset
    ) {
        public static final Codec<Rule> CODEC = RecordCodecBuilder.create(i -> i.group(
                Climate.Parameter.CODEC.optionalFieldOf("temperature").forGetter(Rule::temperature),
                Climate.Parameter.CODEC.optionalFieldOf("humidity").forGetter(Rule::humidity),
                Climate.Parameter.CODEC.optionalFieldOf("continentalness").forGetter(Rule::continentalness),
                Climate.Parameter.CODEC.optionalFieldOf("erosion").forGetter(Rule::erosion),
                Climate.Parameter.CODEC.optionalFieldOf("weirdness").forGetter(Rule::weirdness),
                Climate.Parameter.CODEC.optionalFieldOf("depth").forGetter(Rule::depth),
                Codec.floatRange(0.0F, 1.0F).optionalFieldOf("offset", 0.0F).forGetter(Rule::offset)
        ).apply(i, Rule::new));
    }
}
