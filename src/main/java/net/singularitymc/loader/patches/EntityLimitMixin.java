package net.singularitymc.loader.patches;

import net.singularitymc.loader.util.PatchSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import net.minecraft.world.entity.Entity;

/**
 * Stage 1 of the extended world size (see docs/COORDINATES.md): replaces the hard-coded {@code 30,000,000}-block limits
 * in {@code Entity} by the {@code max_coordinate} setting. With the default (30,000,000) nothing changes.
 * Every hook is optional ({@code require = 0}). The targets name their descriptor: {@code absSnapTo} has an overload
 * without the clamp, and a bare name selects only one of them.
 */
@Mixin(Entity.class)
abstract class EntityLimitMixin {
    @ModifyConstant(method = "absSnapTo(DDD)V", constant = @Constant(doubleValue = 30000000.0), require = 0)
    private double Singularity$absSnapTo0(double original) {
        return PatchSettings.limitD();
    }

    @ModifyConstant(method = "absSnapTo(DDD)V", constant = @Constant(doubleValue = -30000000.0), require = 0)
    private double Singularity$absSnapTo1(double original) {
        return -PatchSettings.limitD();
    }

    // Loading a saved entity (players too) clamps its position to +-30,000,512 (the limit plus 512).
    @ModifyConstant(method = "load(Lnet/minecraft/world/level/storage/ValueInput;)V", constant = @Constant(doubleValue = 30000512.0), require = 0)
    private double Singularity$load0(double original) {
        return PatchSettings.limitD() + 512.0;
    }

    @ModifyConstant(method = "load(Lnet/minecraft/world/level/storage/ValueInput;)V", constant = @Constant(doubleValue = -30000512.0), require = 0)
    private double Singularity$load1(double original) {
        return -(PatchSettings.limitD() + 512.0);
    }
}
