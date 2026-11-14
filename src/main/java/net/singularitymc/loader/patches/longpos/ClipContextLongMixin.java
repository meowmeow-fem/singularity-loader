package net.singularitymc.loader.patches.longpos;

import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * experimental_long_positions, 3.5.8: a safety net for every ray the game traces (line of sight, block picking, mob
 * goals). Tracing loads the chunks on the line, and a point that int code got wrong far out makes a line billions of
 * blocks long (an enderman's line to a block it wanted, a zombie's to its next path node: watchdog). No vanilla ray is
 * longer than a few hundred blocks; one longer than 4,096 is made empty and misses.
 */
@Mixin(ClipContext.class)
abstract class ClipContextLongMixin {
    private static final double MAX_RAY = 4096;

    @Shadow @Final private Vec3 from;
    @Shadow @Final @Mutable private Vec3 to;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void Singularity$notAcrossTheWorld(CallbackInfo ci) {
        if (this.from.distanceToSqr(this.to) > MAX_RAY * MAX_RAY) {
            this.to = this.from;
        }
    }
}
