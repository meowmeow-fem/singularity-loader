package net.singularitymc.loader.patches;

import net.singularitymc.loader.util.PatchSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import net.minecraft.server.commands.WorldBorderCommand;

/**
 * Stage 1 of the extended world size (see docs/COORDINATES.md): replaces the hard-coded {@code 29,999,984 / 59,999,968}-block limits
 * in {@code WorldBorderCommand} by the {@code max_coordinate} setting. With the default (30,000,000) nothing changes.
 * Every hook is optional ({@code require = 0}).
 */
@Mixin(WorldBorderCommand.class)
abstract class WorldBorderCommandLimitMixin {
    @ModifyConstant(method = "register", constant = @Constant(doubleValue = 59999968.0), require = 0)
    private static double Singularity$register0(double original) {
        return 2.0 * PatchSettings.borderD();
    }

    // DoubleArgumentType.doubleArg(-5.9999968E7, 5.9999968E7) for "add" / "set": the minimum is a separate constant.
    @ModifyConstant(method = "register", constant = @Constant(doubleValue = -59999968.0), require = 0)
    private static double Singularity$register1(double original) {
        return -2.0 * PatchSettings.borderD();
    }

    @ModifyConstant(method = "setCenter", constant = @Constant(doubleValue = 29999984.0), require = 0)
    private static double Singularity$setCenter0(double original) {
        return PatchSettings.borderD();
    }

    @ModifyConstant(method = "setSize", constant = @Constant(doubleValue = 59999968.0), require = 0)
    private static double Singularity$setSize0(double original) {
        return 2.0 * PatchSettings.borderD();
    }

    @ModifyConstant(method = "<clinit>", constant = @Constant(doubleValue = 59999968.0), require = 0)
    private static double Singularity$clinit0(double original) {
        return 2.0 * PatchSettings.borderD();
    }

    @ModifyConstant(method = "<clinit>", constant = @Constant(doubleValue = 29999984.0), require = 0)
    private static double Singularity$clinit1(double original) {
        return PatchSettings.borderD();
    }
}
