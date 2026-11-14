package net.singularitymc.loader.patches;

import net.singularitymc.loader.util.PatchSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import net.minecraft.world.level.border.WorldBorder;

/**
 * Stage 1 of the extended world size (see docs/COORDINATES.md): replaces the hard-coded {@code 29,999,984 / 59,999,968}-block limits
 * in {@code WorldBorder} by the {@code max_coordinate} setting. With the default (30,000,000) nothing changes.
 * Every hook is optional ({@code require = 0}).
 */
@Mixin(WorldBorder.class)
abstract class WorldBorderLimitMixin {
    // WorldBorder() comes first and has no constants; a bare "<init>" would select only that one.
    @ModifyConstant(method = "<init>(Lnet/minecraft/world/level/border/WorldBorder$Settings;)V", constant = @Constant(intValue = 29999984), require = 0)
    private int Singularity$ctor0(int original) {
        return PatchSettings.border();
    }

    @ModifyConstant(method = "<init>(Lnet/minecraft/world/level/border/WorldBorder$Settings;)V", constant = @Constant(doubleValue = 59999968.0), require = 0)
    private double Singularity$ctor1(double original) {
        return 2.0 * PatchSettings.borderD();
    }

    /**
     * A border saved at vanilla's default size (59,999,968, i.e. the world was made before {@code max_coordinate} was
     * raised) grows to the full size when it is loaded; otherwise players past 30M would be outside it: red screen,
     * border damage, and no block breaking ({@code ServerLevel.mayInteract} checks the border). A border someone set
     * to another size with /worldborder is kept.
     */
    @ModifyVariable(method = "<init>(Lnet/minecraft/world/level/border/WorldBorder$Settings;)V", at = @At("HEAD"), argsOnly = true, require = 0)
    private static WorldBorder.Settings Singularity$growDefaultBorder(WorldBorder.Settings s) {
        double full = 2.0 * PatchSettings.borderD();
        if (s.size() == 59999968.0 && s.lerpTarget() == 59999968.0 && full > s.size()) {
            return new WorldBorder.Settings(s.centerX(), s.centerZ(), s.damagePerBlock(), s.safeZone(), s.warningBlocks(),
                s.warningTime(), full, s.lerpTime(), full);
        }
        return s;
    }
}
