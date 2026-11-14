package net.singularitymc.loader.patches.longpos;

import net.minecraft.util.Mth;
import net.singularitymc.loader.util.PatchSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * experimental_long_positions, 3.5.7: the world border's edges are clamped to its "absolute max size", an int from
 * {@code max_world_size} (at most 2,147,479,536), so past that every player was outside the border (border damage in
 * survival, no block breaking). In long mode the clamp is the 64-bit limit ({@code max_coordinate} - 16).
 */
@Mixin(targets = "net.minecraft.world.level.border.WorldBorder$StaticBorderExtent")
abstract class BorderExtentLongMixin {
    @Redirect(method = "updateBox", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;clamp(DDD)D"))
    private double Singularity$clamp(double value, double min, double max) {
        return Mth.clamp(value, -PatchSettings.borderD(), PatchSettings.borderD());
    }
}
