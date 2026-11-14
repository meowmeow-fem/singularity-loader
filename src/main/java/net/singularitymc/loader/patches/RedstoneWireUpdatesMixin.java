package net.singularitymc.loader.patches;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.DefaultRedstoneWireEvaluator;
import net.minecraft.world.level.redstone.Orientation;
import net.singularitymc.loader.util.Perf;
import net.singularitymc.perf.FastWire;
import net.singularitymc.perf.PerfHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Redstone dust, 4.3 ({@code redstone_dedup_wire_updates}): when a dust changes its power, vanilla calls
 * {@code updateNeighborsAt} for the dust and for its six neighbours, which notifies 42 blocks: 25 different ones, most
 * of them several times, the dust itself six times. Every notified dust recomputes its power. Here each block is
 * notified once, at the place in vanilla's order where it was notified first. In a profile of 400 dust lines on a
 * clock, dust re-evaluation was 60 % of the tick.
 *
 * <p>The algorithm itself is vanilla's (also its step-by-step power-down); this only drops repeats. Addressed without
 * naming the dust block's class, which is spelled differently in 26.1.
 */
@Mixin(DefaultRedstoneWireEvaluator.class)
abstract class RedstoneWireUpdatesMixin {
    @Inject(method = "updatePowerStrength", at = @At("HEAD"), cancellable = true, require = 0)
    private void Singularity$beginUpdates(Level level, BlockPos pos, BlockState state, Orientation orientation, boolean added, CallbackInfo ci) {
        // 4.4, opt-in: the whole affected piece of the dust network at once (see FastWire)
        if (Perf.fastWire() && FastWire.update(this, level, pos, state)) {
            ci.cancel();
            return;
        }
        PerfHooks.wireBegin();
    }

    @Inject(method = "updatePowerStrength", at = @At("RETURN"), require = 0)
    private void Singularity$endUpdates(CallbackInfo ci) {
        PerfHooks.wireEnd();
    }

    @Redirect(method = "updatePowerStrength", require = 0, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/Level;updateNeighborsAt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;)V"))
    private void Singularity$notifyOnce(Level level, BlockPos pos, Block wire) {
        PerfHooks.wireNeighbors(level, pos, wire);
    }
}
