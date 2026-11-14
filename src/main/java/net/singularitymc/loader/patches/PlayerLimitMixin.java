package net.singularitymc.loader.patches;

import net.singularitymc.loader.util.PatchSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import net.minecraft.world.entity.player.Player;

/**
 * Stage 1 of the extended world size (see docs/COORDINATES.md): replaces the hard-coded {@code 29,999,999}-block limits
 * in {@code Player} by the {@code max_coordinate} setting. With the default (30,000,000) nothing changes.
 * Every hook is optional ({@code require = 0}).
 */
@Mixin(Player.class)
abstract class PlayerLimitMixin {
    @ModifyConstant(method = "tick", constant = @Constant(intValue = 29999999), require = 0)
    private int Singularity$tick0(int original) {
        return PatchSettings.limit() - 1;
    }

    @ModifyConstant(method = "tick", constant = @Constant(doubleValue = 29999999.0), require = 0)
    private double Singularity$tick1(double original) {
        return (PatchSettings.limitD() - 1);
    }

    // Mth.clamp(getX(), -2.9999999E7, 2.9999999E7): the lower bound is its own ldc2_w constant.
    @ModifyConstant(method = "tick", constant = @Constant(doubleValue = -29999999.0), require = 0)
    private double Singularity$tick2(double original) {
        return -(PatchSettings.limitD() - 1);
    }
}
