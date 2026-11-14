package net.singularitymc.loader.patches.longpos;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.singularitymc.loader.util.WidePacking;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * experimental_long_positions, 3.5.8: entities are not widened yet, and int code places new ones at a block position
 * it got narrowed ({@code pos.getX() + 0.5}: falling blocks, dropped items, TNT, XP, spawners). Just past 2^31 that
 * is the other side of the world: falling sand next to x = 2,147,483,647 appeared at -2,147,483,579.5, and from there
 * int code near it read the seam the wrong way and loaded chunks 4 billion blocks away (watchdog). A position set
 * before the entity is in a level is carried next to where the thread works. Positions of entities in a level
 * (movement, teleports, loading, which has its own carry) are left as they are.
 */
@Mixin(Entity.class)
abstract class NewEntityPositionLongMixin {
    @Shadow private EntityInLevelCallback levelCallback;

    @ModifyVariable(method = "setPosRaw", at = @At("HEAD"), ordinal = 0, argsOnly = true)
    private double Singularity$x(double x) {
        return this.levelCallback == EntityInLevelCallback.NULL ? WidePacking.carryInDouble(x) : x;
    }

    @ModifyVariable(method = "setPosRaw", at = @At("HEAD"), ordinal = 2, argsOnly = true)
    private double Singularity$z(double z) {
        return this.levelCallback == EntityInLevelCallback.NULL ? WidePacking.carryInDouble(z) : z;
    }
}
