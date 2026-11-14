package net.singularitymc.loader.patches;

import net.singularitymc.loader.util.PatchSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Stage 1 of the extended world size (see docs/COORDINATES.md): replaces the hard-coded {@code 29,999,984 / 59,999,968}-block limits
 * in {@code WorldBorder.Settings} by the {@code max_coordinate} setting. With the default (30,000,000) nothing changes.
 * Every hook is optional ({@code require = 0}). The codec lambda is {@code lambda$static$0} in 26.x and
 * {@code method_74150} in 1.21.11 (Mojang's mappings do not name lambdas; the Wide Client's 1.21.11 build).
 */
@Mixin(targets = "net.minecraft.world.level.border.WorldBorder$Settings")
abstract class WorldBorderSettingsLimitMixin {
    @ModifyConstant(method = {"lambda$static$0", "method_74150"}, constant = @Constant(doubleValue = 29999984.0), require = 0)
    private static double Singularity$lambda_static_00(double original) {
        return PatchSettings.borderD();
    }

    @ModifyConstant(method = {"lambda$static$0", "method_74150"}, constant = @Constant(doubleValue = -29999984.0), require = 0)
    private static double Singularity$lambda_static_01(double original) {
        return -PatchSettings.borderD();
    }

    @ModifyConstant(method = "<clinit>", constant = @Constant(doubleValue = 59999968.0), require = 0)
    private static double Singularity$clinit0(double original) {
        return 2.0 * PatchSettings.borderD();
    }
}
