package fr.hardel.worldgen.biomeentry;

import fr.hardel.ExtendedWorldGen;
import net.fabricmc.fabric.api.event.registry.DynamicRegistries;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

/**
 * Owns the {@link ResourceKey} of the {@code extendedworldgen:worldgen/biome_entry} dynamic
 * registry and exposes a one-shot bootstrap method called from the mod initializer.
 * <p>
 * Per the Fabric convention, datapack files for this registry live at
 * {@code data/<entry-namespace>/extendedworldgen/worldgen/biome_entry/<entry-path>.json}, and
 * tags at {@code data/<entry-namespace>/tags/extendedworldgen/worldgen/biome_entry/<tag-path>.json}.
 */
public final class BiomeEntryRegistry {
    public static final ResourceKey<Registry<BiomeEntry>> KEY = ResourceKey.createRegistryKey(
            Identifier.of(ExtendedWorldGen.MOD_ID, "worldgen/biome_entry")
    );

    private BiomeEntryRegistry() {}

    public static void bootstrap() {
        DynamicRegistries.register(KEY, BiomeEntry.CODEC);
    }
}
