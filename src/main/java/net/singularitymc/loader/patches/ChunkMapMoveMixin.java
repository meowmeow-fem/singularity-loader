package net.singularitymc.loader.patches;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.singularitymc.perf.PerfHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Entity tracking, 4.2: {@code ChunkMap.move} runs for every move packet (20 a second per walking player) and each
 * time passes over every tracked entity of the world, so its cost grows with players x entities. Per player the pass
 * now runs once every {@code entity_tracking_interval} ticks (default 8); the rest of {@code move} (which chunks the
 * player sees) is untouched, and {@code ChunkMap.tick} still updates everything that crosses a chunk-section border
 * in the same tick.
 */
@Mixin(ChunkMap.class)
abstract class ChunkMapMoveMixin {
    @Redirect(method = "move", require = 0, at = @At(value = "INVOKE",
        target = "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;values()Lit/unimi/dsi/fastutil/objects/ObjectCollection;"))
    private ObjectCollection<?> Singularity$trackedEntitiesForMove(Int2ObjectMap<?> entityMap, ServerPlayer player) {
        return PerfHooks.trackEntitiesOnMove(player) ? entityMap.values() : ObjectSets.emptySet();
    }
}
