package net.singularitymc.loader.patches;

import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.singularitymc.loader.util.CrowdAware;
import net.singularitymc.loader.util.PatchSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Optimisation for crowded places (mob farms, pens, villager halls, groups of players).
 *
 * <p>Every living entity looks up all pushable neighbours each tick and pushes every one of them,
 * so a crowd of n entities costs about n&sup2; work. Two changes:
 * <ul>
 *   <li>the number of neighbours one entity actually pushes per tick is capped
 *       ({@code singularity.opt.pushCap}, default 8). The neighbour list itself is untouched, so the
 *       vanilla entity-cramming rule still sees the whole crowd;</li>
 *   <li>the neighbour count is remembered ({@link CrowdAware}) for the AI throttling patches.</li>
 * </ul>
 */
@Mixin(LivingEntity.class)
abstract class LivingEntityMixin implements CrowdAware {
    @Unique
    private int singularity$crowd;
    @Unique
    private int singularity$pushed;

    @Shadow
    protected abstract void doPush(Entity other);

    @Redirect(method = "pushEntities", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getPushableEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"))
    private List<Entity> Singularity$countNeighbours(Level level, Entity self, AABB box) {
        List<Entity> list = level.getPushableEntities(self, box);
        this.singularity$crowd = list.size();
        this.singularity$pushed = 0;
        return list;
    }

    @Redirect(method = "pushEntities", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;doPush(Lnet/minecraft/world/entity/Entity;)V"))
    private void Singularity$cappedPush(LivingEntity self, Entity other) {
        int cap = PatchSettings.PUSH_CAP;
        if (cap <= 0 || this.singularity$pushed++ < cap) {
            this.doPush(other);
        }
    }

    @Override
    public int singularity$getCrowd() {
        return this.singularity$crowd;
    }
}
