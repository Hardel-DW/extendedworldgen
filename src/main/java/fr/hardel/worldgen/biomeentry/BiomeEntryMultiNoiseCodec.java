package fr.hardel.worldgen.biomeentry;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import java.util.stream.Stream;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryCodecs;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;

/**
 * Decorates the vanilla {@code MultiNoiseBiomeSource} {@link MapCodec} so that it understands a
 * third format on top of {@code biomes}/{@code preset}: {@code biome_entries: "#namespace:tag"}.
 * <p>
 * On decode, the wrapper checks for a {@code biome_entries} field first; if found, it parses the
 * value as a {@link HolderSet} of {@link BiomeEntry}, expands it via {@link BiomeEntryExpander},
 * and constructs the source from the resulting parameter list. If absent, it transparently falls
 * back to the vanilla codec, preserving compatibility with mods that still use the legacy formats.
 * <p>
 * On encode, the wrapper always delegates to the vanilla codec. We deliberately do not try to
 * round-trip back to the {@code biome_entries} form: once decoded, the tag identity is lost, and
 * vanilla's expanded {@code biomes: [...]} representation is the only authoritative shape.
 */
public final class BiomeEntryMultiNoiseCodec {
    private static final String BIOME_ENTRIES_FIELD = "biome_entries";
    private static final Codec<HolderSet<BiomeEntry>> ENTRIES_CODEC = RegistryCodecs.homogeneousList(BiomeEntryRegistry.KEY);

    private BiomeEntryMultiNoiseCodec() {}

    public static MapCodec<MultiNoiseBiomeSource> wrap(final MapCodec<MultiNoiseBiomeSource> vanilla) {
        return new MapCodec<MultiNoiseBiomeSource>() {
            @Override
            public <T> DataResult<MultiNoiseBiomeSource> decode(final DynamicOps<T> ops, final MapLike<T> input) {
                T entriesValue = input.get(BIOME_ENTRIES_FIELD);
                if (entriesValue == null) {
                    return vanilla.decode(ops, input);
                }
                return ENTRIES_CODEC.parse(ops, entriesValue)
                        .map(set -> MultiNoiseBiomeSource.createFromList(BiomeEntryExpander.expand(set)));
            }

            @Override
            public <T> RecordBuilder<T> encode(final MultiNoiseBiomeSource source, final DynamicOps<T> ops, final RecordBuilder<T> prefix) {
                return vanilla.encode(source, ops, prefix);
            }

            @Override
            public <T> Stream<T> keys(final DynamicOps<T> ops) {
                return Stream.concat(
                        Stream.of(ops.createString(BIOME_ENTRIES_FIELD)),
                        vanilla.keys(ops)
                );
            }
        };
    }
}
