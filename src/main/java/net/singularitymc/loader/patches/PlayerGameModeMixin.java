package net.singularitymc.loader.patches;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.singularitymc.api.event.EventBus;
import net.singularitymc.event.BlockBreakEvent;
import net.singularitymc.event.ItemUseEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Events: {@link BlockBreakEvent} and {@link ItemUseEvent}. */
@Mixin(ServerPlayerGameMode.class)
abstract class PlayerGameModeMixin {
    @Shadow
    protected ServerLevel level;
    @Shadow
    @org.spongepowered.asm.mixin.Final
    protected ServerPlayer player;

    @Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true, require = 0)
    private void Singularity$blockBreakEvent(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        BlockBreakEvent event = EventBus.post(new BlockBreakEvent(player, level, pos, level.getBlockState(pos)));
        if (event.isCancelled()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "useItem", at = @At("HEAD"), cancellable = true, require = 0)
    private void Singularity$itemUseEvent(ServerPlayer user, Level world, ItemStack stack, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        if (EventBus.post(new ItemUseEvent(user, stack, hand)).isCancelled()) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }
}
