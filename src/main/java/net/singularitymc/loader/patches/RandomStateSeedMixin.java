package net.singularitymc.loader.patches;

import net.minecraft.core.HolderGetter;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.singularitymc.compat.WorldSeeds;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the world seed a {@code RandomState} is made from, for {@link WorldSeeds#of} on 26.1 and 26.2 (26.3 has
 * {@code RandomState.seed()} itself and its constructor has another shape, so the hook is optional). The classic Far
 * Lands need the seed.
 */
@Mixin(RandomState.class)
abstract class RandomStateSeedMixin implements WorldSeeds.Carrier {
    @Unique
    private long singularity$seed;

    @Inject(method = "<init>(Lnet/minecraft/world/level/levelgen/NoiseGeneratorSettings;Lnet/minecraft/core/HolderGetter;J)V",
        at = @At("RETURN"), require = 0)
    private void Singularity$keepSeed(NoiseGeneratorSettings settings, HolderGetter<?> noises, long seed, CallbackInfo ci) {
        this.singularity$seed = seed;
    }

    @Override
    public long singularity$seed() {
        return singularity$seed;
    }
}
