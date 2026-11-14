package net.singularitymc.loader.patches;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.singularitymc.loader.util.CrowdAware;
import net.singularitymc.loader.util.PatchSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Optimisation: goal / target <em>selection</em> (the expensive {@code canUse} checks, which scan
 * for players, partners, food, paths...) is already run on every second tick by vanilla. For mobs
 * that stand in a crowd and have no attack target it now runs on every {@code 2 * crowdAiRate}th
 * tick (default 4th). Goals that are already running are still ticked every tick, and a mob with a
 * target is never throttled, so combat and movement stay responsive.
 */
@Mixin(Mob.class)
abstract class MobMixin {
    @Redirect(method = "serverAiStep", require = 0, at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/world/entity/ai/goal/GoalSelector;tick()V"))
    private void Singularity$throttleTargetSelection(GoalSelector selector) {
        if (Singularity$shouldSelect()) {
            selector.tick();
        }
    }

    @Redirect(method = "serverAiStep", require = 0, at = @At(value = "INVOKE", ordinal = 1,
            target = "Lnet/minecraft/world/entity/ai/goal/GoalSelector;tick()V"))
    private void Singularity$throttleGoalSelection(GoalSelector selector) {
        if (Singularity$shouldSelect()) {
            selector.tick();
        }
    }

    @Unique
    private boolean Singularity$shouldSelect() {
        int rate = PatchSettings.CROWD_AI_RATE;
        if (rate <= 1) {
            return true;
        }
        Mob self = (Mob) (Object) this;
        if (((CrowdAware) (Object) this).singularity$getCrowd() < PatchSettings.CROWD || self.getTarget() != null) {
            return true;
        }
        return (self.tickCount + self.getId()) % (2 * rate) == 0;
    }
}
