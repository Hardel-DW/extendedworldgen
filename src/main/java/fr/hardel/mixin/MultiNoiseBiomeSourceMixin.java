package fr.hardel.mixin;

import com.mojang.serialization.MapCodec;
import fr.hardel.worldgen.biomeentry.BiomeEntryMultiNoiseCodec;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces {@link MultiNoiseBiomeSource#CODEC} at the end of the class initializer with a wrapped
 * version that also accepts the {@code biome_entries} tag-based format. The original {@code CODEC}
 * is captured and delegated to for the legacy {@code biomes}/{@code preset} formats and for all
 * encoding paths, so this is purely additive at the JSON layer.
 */
@Mixin(MultiNoiseBiomeSource.class)
public abstract class MultiNoiseBiomeSourceMixin {
    @Mutable
    @Shadow
    @Final
    public static MapCodec<MultiNoiseBiomeSource> CODEC;

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void extendedworldgen$wrapCodec(final CallbackInfo ci) {
        CODEC = BiomeEntryMultiNoiseCodec.wrap(CODEC);
    }
}
