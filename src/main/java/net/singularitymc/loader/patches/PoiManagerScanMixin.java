package net.singularitymc.loader.patches;

import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.ChunkPos;
import net.singularitymc.loader.util.Perf;
import net.singularitymc.perf.PerfHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Points of interest, 4.3 ({@code poi_scan_cache}): a villager without a bed, a workstation or a meeting point looks
 * for one every second or two, over 7 x 7 chunk columns of 24 sections each, and bees, raids, portals and iron golems
 * search the same way. Nearly all of those columns hold nothing. A column found empty is remembered as empty for 10
 * seconds, or until anything is added anywhere ({@code add} forgets everything), and is then skipped with one lookup.
 * In a profile of 400 villagers with nothing to find, these scans were a quarter of the tick.
 */
@Mixin(PoiManager.class)
abstract class PoiManagerScanMixin {
    /** Column -> tick until which it is known to be empty (positive) or to hold something (negative). */
    @Unique private final Object2LongOpenHashMap<ChunkPos> singularity$columns = new Object2LongOpenHashMap<>();
    @Unique private boolean singularity$probing;

    @Inject(method = "getInChunk", at = @At("HEAD"), cancellable = true, require = 0)
    private void Singularity$skipEmptyColumn(Predicate<Holder<PoiType>> type, ChunkPos pos, PoiManager.Occupancy occupancy,
                                             CallbackInfoReturnable<Stream<PoiRecord>> cir) {
        if (singularity$probing || !Perf.poiCache() || !PerfHooks.onServerThread()) {
            return;
        }
        long now = PerfHooks.tick();
        long until = singularity$columns.getLong(pos);
        if (until > now) {
            cir.setReturnValue(Stream.empty());
            return;
        }
        if (until < 0 && -until > now) {
            return; // holds something: the normal scan
        }
        boolean empty;
        singularity$probing = true;
        try {
            empty = ((PoiManager) (Object) this).getInChunk(t -> true, pos, PoiManager.Occupancy.ANY).findAny().isEmpty();
        } finally {
            singularity$probing = false;
        }
        if (singularity$columns.size() > 200_000) {
            singularity$columns.clear();
        }
        singularity$columns.put(pos, empty ? now + 200 : -(now + 200));
        if (empty) {
            cir.setReturnValue(Stream.empty());
        }
    }

    @Inject(method = "add", at = @At("HEAD"), require = 0)
    private void Singularity$forgetOnAdd(CallbackInfoReturnable<PoiRecord> cir) {
        if (!singularity$columns.isEmpty()) {
            singularity$columns.clear();
        }
    }
}
