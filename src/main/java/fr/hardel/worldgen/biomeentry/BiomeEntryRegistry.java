package fr.hardel.worldgen.biomeentry;

import net.fabricmc.fabric.api.event.registry.DynamicRegistries;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

/**
 * Owns the {@link ResourceKey} of the {@code minecraft:worldgen/biome_entry} dynamic registry and
 * exposes a one-shot bootstrap method called from the mod initializer.
 * <p>
 * The registry is intentionally placed in the {@code minecraft} namespace so that its datapack
 * files live at the clean vanilla-style path {@code data/<entry-namespace>/worldgen/biome_entry/}
 * (and tags at {@code data/<entry-namespace>/tags/worldgen/biome_entry/}). Fabric's
 * {@code RegistriesMixin} prepends the registry namespace to those paths only when the registry
 * is non-vanilla, so a {@code minecraft:}-namespaced registry skips that prefix entirely. This is
 * deliberate: this mod prototypes what the format should look like inside vanilla, not what a
 * third-party mod following Fabric conventions would ship.
 */
public final class BiomeEntryRegistry {
    public static final ResourceKey<Registry<BiomeEntry>> KEY = ResourceKey.createRegistryKey(
            Identifier.withDefaultNamespace("worldgen/biome_entry")
    );

    private BiomeEntryRegistry() {}

    public static void bootstrap() {
        DynamicRegistries.register(KEY, BiomeEntry.CODEC);
    }
}
