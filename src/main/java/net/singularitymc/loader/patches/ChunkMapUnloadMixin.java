package net.singularitymc.loader.patches;

import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ChunkMap;
import net.singularitymc.perf.PerfHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 4.2: chunks waiting to be unloaded are taken only "while there is time" (or when more than 2,000 wait). A server
 * that is behind never has time, so it keeps every chunk it ever loaded in memory and keeps ticking what is in them.
 * A few milliseconds per tick ({@code overload_chunk_budget_ms}) are always spent on them.
 */
@Mixin(ChunkMap.class)
abstract class ChunkMapUnloadMixin {
    @Redirect(method = "processUnloads", require = 0, at = @At(value = "INVOKE", ordinal = 0,
        target = "Ljava/util/function/BooleanSupplier;getAsBoolean()Z"))
    private boolean Singularity$unloadBudget(BooleanSupplier haveTime) {
        return haveTime.getAsBoolean() || PerfHooks.unloadBudgetLeft();
    }
}
