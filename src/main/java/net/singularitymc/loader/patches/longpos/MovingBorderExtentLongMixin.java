package net.singularitymc.loader.patches.longpos;

import net.minecraft.util.Mth;
import net.singularitymc.loader.util.PatchSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** experimental_long_positions, 3.5.7: as {@link BorderExtentLongMixin}, for a border that is moving. */
@Mixin(targets = "net.minecraft.world.level.border.WorldBorder$MovingBorderExtent")
abstract class MovingBorderExtentLongMixin {
    @Redirect(method = {"getMinX", "getMinZ", "getMaxX", "getMaxZ"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;clamp(DDD)D"))
    private double Singularity$clamp(double value, double min, double max) {
        return Mth.clamp(value, -PatchSettings.borderD(), PatchSettings.borderD());
    }
}
