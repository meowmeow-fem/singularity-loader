package net.singularitymc.loader.patches;

import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.singularitymc.perf.PerfHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Pathfinding, 4.3 ({@code pathfinding_budget_ms}, default 8): once a tick has spent that long finding paths, further
 * requests in the same tick get "no path" at once. The mob asks again a little later, as it does whenever no path is
 * found, so a crowd of mobs that all want a path in the same tick is spread over several ticks instead of stalling
 * one. Normal play never reaches the budget.
 */
@Mixin(PathFinder.class)
abstract class PathFinderBudgetMixin {
    private static final String FIND_PATH = "findPath(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;"
        + "Ljava/util/Set;FIF)Lnet/minecraft/world/level/pathfinder/Path;";

    @Inject(method = FIND_PATH, at = @At("HEAD"), cancellable = true, require = 0)
    private void Singularity$budget(CallbackInfoReturnable<Path> cir) {
        if (PerfHooks.pathBudgetExhausted()) {
            cir.setReturnValue(null);
        }
    }

    @Inject(method = FIND_PATH, at = @At("RETURN"), require = 0)
    private void Singularity$spent(CallbackInfoReturnable<Path> cir) {
        PerfHooks.pathDone();
    }
}
