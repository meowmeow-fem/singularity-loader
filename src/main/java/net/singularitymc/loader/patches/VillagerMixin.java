package net.singularitymc.loader.patches;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.npc.villager.Villager;
import net.singularitymc.loader.util.CrowdAware;
import net.singularitymc.loader.util.PatchSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Optimisation: villager brains are one of the most expensive things in vanilla, and trading halls
 * and villages put dozens of them in a small space. A villager that stands in a crowd now ticks its
 * brain every {@code singularity.opt.crowdBrainRate}th tick (default every 2nd, staggered per
 * villager). Walking itself is driven by the movement controller every tick, so villagers keep
 * moving; only their decision making is a tick slower. A villager that was just hurt is never throttled.
 */
@Mixin(Villager.class)
abstract class VillagerMixin {
    @SuppressWarnings({"rawtypes", "unchecked"})
    @Redirect(method = "customServerAiStep", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ai/Brain;tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;)V"))
    private void Singularity$throttleBrain(Brain brain, ServerLevel level, LivingEntity entity) {
        int rate = PatchSettings.CROWD_BRAIN_RATE;
        Villager self = (Villager) (Object) this;
        if (rate > 1
                && ((CrowdAware) (Object) this).singularity$getCrowd() >= PatchSettings.CROWD
                && self.hurtTime <= 0
                && (self.tickCount + self.getId()) % rate != 0) {
            return;
        }
        brain.tick(level, entity);
    }
}
