package fr.hardel.worldgen.biomeentry;

import net.fabricmc.fabric.api.event.registry.DynamicRegistries;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

/**
 * Owns the {@link ResourceKey} of the {@code minecraft:worldgen/biome_entry} dynamic registry
 * and registers the {@code minecraft:biome_entry} biome source type. Both registrations run once
 * from the mod initializer.
 * <p>
 * Everything is placed under the {@code minecraft} namespace because this project is a vanilla
 * prototype: the code is meant to represent what Minecraft would ship if Mojang adopted the
 * proposal. Fabric's {@code RegistriesMixin} only prepends the registry namespace to data paths
 * for non-vanilla registries, so using {@code minecraft:} gives us the clean vanilla-style layout
 * {@code data/<entry-namespace>/worldgen/biome_entry/} and tags at
 * {@code data/<entry-namespace>/tags/worldgen/biome_entry/}.
 */
public final class BiomeEntryRegistry {
    public static final ResourceKey<Registry<BiomeEntry>> KEY = ResourceKey.createRegistryKey(
            Identifier.withDefaultNamespace("worldgen/biome_entry")
    );

    private static final Identifier BIOME_SOURCE_ID = Identifier.withDefaultNamespace("biome_entry");

    private BiomeEntryRegistry() {}

    public static void bootstrap() {
        DynamicRegistries.register(KEY, BiomeEntry.CODEC);
        Registry.register(BuiltInRegistries.BIOME_SOURCE, BIOME_SOURCE_ID, BiomeEntryBiomeSource.CODEC);
    }
}
