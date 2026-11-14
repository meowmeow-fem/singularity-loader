package net.singularitymc.loader.patches;

import net.minecraft.world.entity.item.ItemEntity;
import net.singularitymc.loader.util.PatchSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optimisation: an item lying still on the ground searches its surroundings for other items to
 * merge with every 40 ticks. Large item piles (farms, mob drops) make this one of the more
 * expensive per-tick entity jobs, so for resting items the search is run less often
 * ({@code singularity.opt.itemMergeRest}, default every 80 ticks). Moving items are untouched, and
 * items still merge - only a little later.
 *
 * <p>{@code require = 0}: if the method changes in a future game version the patch is skipped.
 */
@Mixin(ItemEntity.class)
abstract class ItemEntityMixin {
    @Inject(method = "mergeWithNeighbours", at = @At("HEAD"), cancellable = true, require = 0)
    private void Singularity$throttleRestingMerge(CallbackInfo ci) {
        int mul = PatchSettings.ITEM_MERGE_REST;
        if (mul <= 1) {
            return;
        }
        ItemEntity self = (ItemEntity) (Object) this;
        if (PatchSettings.isResting(self.onGround(), self.getDeltaMovement().horizontalDistanceSqr())
                && self.tickCount % (40 * mul) != 0) {
            ci.cancel();
        }
    }
}
