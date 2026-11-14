package net.singularitymc.loader.patches;

import net.singularitymc.loader.util.PatchSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import net.minecraft.server.commands.ForceLoadCommand;

/**
 * Stage 1 of the extended world size (see docs/COORDINATES.md): replaces the hard-coded {@code 30,000,000}-block limits
 * in {@code ForceLoadCommand} by the {@code max_coordinate} setting. With the default (30,000,000) nothing changes.
 * Every hook is optional ({@code require = 0}).
 */
@Mixin(ForceLoadCommand.class)
abstract class ForceLoadLimitMixin {
    @ModifyConstant(method = "changeForceLoad", constant = @Constant(intValue = 30000000), require = 0)
    private static int Singularity$changeForceLoad0(int original) {
        return PatchSettings.limit();
    }

    @ModifyConstant(method = "changeForceLoad", constant = @Constant(intValue = -30000000), require = 0)
    private static int Singularity$changeForceLoad1(int original) {
        return -PatchSettings.limit();
    }
}
