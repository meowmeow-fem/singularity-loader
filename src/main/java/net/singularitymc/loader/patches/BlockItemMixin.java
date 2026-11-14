package net.singularitymc.loader.patches;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.singularitymc.api.event.EventBus;
import net.singularitymc.event.BlockPlaceEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Event: {@link BlockPlaceEvent}. */
@Mixin(BlockItem.class)
abstract class BlockItemMixin {
    @Inject(method = "place", at = @At("HEAD"), cancellable = true, require = 0)
    private void Singularity$blockPlaceEvent(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        if (!(context.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPlaceEvent event = EventBus.post(new BlockPlaceEvent(context.getPlayer(), level, context.getClickedPos(), context.getItemInHand()));
        if (event.isCancelled()) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }
}
