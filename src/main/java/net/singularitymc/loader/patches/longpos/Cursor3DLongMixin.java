package net.singularitymc.loader.patches.longpos;

import net.minecraft.core.Cursor3D;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * experimental_long_positions, 3.5.7: a box whose far corner wrapped in int code (for example block collisions at
 * x = 2,147,483,647: {@code floor(maxX) + 1}) is empty instead of a 4-billion-block range that never ends (a crash
 * report "Watching Server" in {@code BlockCollisions}). The carry normally repairs the corner; this is the safety net.
 */
@Mixin(Cursor3D.class)
abstract class Cursor3DLongMixin {
    @Shadow @Final private int width;
    @Shadow @Final private int height;
    @Shadow @Final private int depth;
    @Shadow @Final @Mutable private int end;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void Singularity$emptyIfWrapped(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, CallbackInfo ci) {
        if (this.width <= 0 || this.height <= 0 || this.depth <= 0 || this.end < 0) {
            this.end = 0;
        }
    }
}
