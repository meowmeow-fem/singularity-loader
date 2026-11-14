package net.singularitymc.loader.patches.longpos;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.singularitymc.loader.util.WidePacking;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * experimental_long_positions, 3.5.5: chunks past 2^31 are saved. Block positions inside the chunk data (scheduled
 * ticks, structure boxes) are ints in the save format; while a chunk is read its position is remembered, so they are
 * carried back next to it. Structure references are 64-bit chunk keys in memory and are saved in the vanilla int-pair
 * form, which {@code ChunkPos.unpack} reads back.
 */
@Mixin(SerializableChunkData.class)
abstract class SerializableChunkDataLongMixin {
    /**
     * 3.5.8: past +-4,278,190,080 blocks int code sees coordinates narrowed differently than before
     * ({@code WidePacking.narrow}), so the ints in a chunk saved there before hold the old view. Chunks saved from now
     * on carry this key; one without it is read the old way, and its block entities' x / z are rewritten.
     */
    private static final String NARROWING_KEY = "singularity_narrowing";
    private static final Set<Long> OLD_CHUNKS = ConcurrentHashMap.newKeySet();

    private static boolean oldNarrowing(CompoundTag tag) {
        if (tag.contains(NARROWING_KEY)) return false;
        Optional<Integer> x = tag.getInt("xPos");
        Optional<Integer> z = tag.getInt("zPos");
        return x.isPresent() && z.isPresent()
            && (WidePacking.narrowedDifferentlyBefore((long) x.get() << 4) || WidePacking.narrowedDifferentlyBefore((long) z.get() << 4));
    }

    @WrapMethod(method = "parse")
    private static SerializableChunkData Singularity$parseOld(LevelHeightAccessor level, PalettedContainerFactory containers, CompoundTag tag,
                                                             Operation<SerializableChunkData> original) {
        if (!oldNarrowing(tag)) return original.call(level, containers, tag);
        WidePacking.beginOldNarrowing();
        try {
            SerializableChunkData data = original.call(level, containers, tag);
            if (data != null) OLD_CHUNKS.add(data.chunkPos().pack());
            return data;
        } finally {
            WidePacking.endOldNarrowing();
        }
    }

    @WrapMethod(method = "read")
    private ProtoChunk Singularity$readOld(ServerLevel level, PoiManager poi, RegionStorageInfo info, ChunkPos pos, Operation<ProtoChunk> original) {
        if (!OLD_CHUNKS.remove(pos.pack())) return original.call(level, poi, info, pos);
        List<CompoundTag> blockEntities = ((SerializableChunkData) (Object) this).blockEntities();
        long[] xz = new long[blockEntities.size() * 2];
        ProtoChunk chunk;
        WidePacking.beginOldNarrowing();
        try {
            for (int i = 0; i < blockEntities.size(); i++) {
                long packed = BlockEntity.getPosFromTag(pos, blockEntities.get(i)).asLong();
                xz[2 * i] = WidePacking.blockX64(packed);
                xz[2 * i + 1] = WidePacking.blockZ64(packed);
            }
            chunk = original.call(level, poi, info, pos);
        } finally {
            WidePacking.endOldNarrowing();
        }
        // the block entities are made of these tags later: in the narrowing int code uses from now on
        for (int i = 0; i < blockEntities.size(); i++) {
            blockEntities.get(i).putInt("x", WidePacking.narrow(xz[2 * i]));
            blockEntities.get(i).putInt("z", WidePacking.narrow(xz[2 * i + 1]));
        }
        return chunk;
    }

    @ModifyReturnValue(method = "write", at = @At("RETURN"))
    private CompoundTag Singularity$markNarrowing(CompoundTag tag) {
        ChunkPos pos = ((SerializableChunkData) (Object) this).chunkPos();
        long key = pos.pack();
        long x = WidePacking.chunkX64(key);
        long z = WidePacking.chunkZ64(key);
        if (WidePacking.narrowedDifferentlyBefore(x << 4) || WidePacking.narrowedDifferentlyBefore(z << 4)) {
            tag.putInt(NARROWING_KEY, 2);
            // the chunk's own position as it is (it fits an int wherever chunks are saved): read back before anything
            // tells the carry which chunk this is, the narrowed one would be taken as it is
            tag.putInt("xPos", (int) x);
            tag.putInt("zPos", (int) z);
        }
        return tag;
    }
    @ModifyVariable(method = "parse", at = @At("STORE"), ordinal = 0)
    private static ChunkPos Singularity$carryWhileParsing(ChunkPos pos) {
        WidePacking.carryChunkKey(pos.pack());
        return pos;
    }

    @Inject(method = "read", at = @At("HEAD"))
    private void Singularity$carryWhileReading(ServerLevel level, PoiManager poi, RegionStorageInfo info, ChunkPos pos, CallbackInfoReturnable<ProtoChunk> cir) {
        WidePacking.carryChunkKey(pos.pack());
    }

    @Inject(method = "unpackStructureReferences", at = @At("HEAD"))
    private static void Singularity$carryReferences(RegistryAccess access, ChunkPos pos, CompoundTag tag, CallbackInfoReturnable<?> cir) {
        WidePacking.carryChunkKey(pos.pack());
    }

    @Redirect(method = "packStructureData", at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/longs/LongSet;toLongArray()[J"))
    private static long[] Singularity$savedReferences(LongSet keys) {
        long[] out = keys.toLongArray();
        for (int i = 0; i < out.length; i++) {
            out[i] = WidePacking.chunkKeyForSave(out[i]);
        }
        return out;
    }
}
