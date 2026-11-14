package net.singularitymc.loader.patches.longpos;

import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.singularitymc.loader.util.WidePacking;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * experimental_long_positions, 3.5.8: path nodes keep int coordinates (the pathfinder is not widened yet), and the
 * point a mob walks to is made from them directly ({@code node.x + 0.5}). Far out that is the node's low 32 bits: a
 * zombie at 35,000,000,000,000 checked a straight line to 311,504,826 and the server loaded every chunk on the way
 * (watchdog). The point is carried next to where the mob is.
 */
@Mixin({Path.class, Node.class})
abstract class PathNodeLongMixin {
    private static final String VEC3 = "Lnet/minecraft/world/phys/Vec3;<init>(DDD)V";

    @ModifyArg(method = {"getEntityPosAtNode", "asVec3"}, at = @At(value = "INVOKE", target = VEC3), index = 0, require = 1)
    private double Singularity$x(double x) {
        return WidePacking.carryInDouble(x);
    }

    @ModifyArg(method = {"getEntityPosAtNode", "asVec3"}, at = @At(value = "INVOKE", target = VEC3), index = 2, require = 1)
    private double Singularity$z(double z) {
        return WidePacking.carryInDouble(z);
    }
}
