package net.singularitymc.loader.patches;

import net.singularitymc.loader.util.PatchSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import net.minecraft.server.dedicated.DedicatedServerProperties;

/**
 * Stage 1 of the extended world size (see docs/COORDINATES.md): replaces the hard-coded {@code 29,999,984}-block limits
 * in {@code DedicatedServerProperties} by the {@code max_coordinate} setting. With the default (30,000,000) nothing changes.
 * Every hook is optional ({@code require = 0}).
 */
@Mixin(DedicatedServerProperties.class)
abstract class DedicatedPropsLimitMixin {
    @ModifyConstant(method = "<init>", constant = @Constant(intValue = 29999984), require = 0)
    private int Singularity$ctor0(int original) {
        return PatchSettings.border();
    }

    @ModifyConstant(method = "lambda$new$0", constant = @Constant(intValue = 29999984), require = 0)
    private static int Singularity$lambda_new_00(int original) {
        return PatchSettings.border();
    }
}
