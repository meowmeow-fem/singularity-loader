package net.singularitymc.loader.patches;

import java.util.EnumSet;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.singularitymc.compat.WorldSeeds;
import net.singularitymc.loader.util.ClassicFarLands;
import net.singularitymc.loader.util.FarLandsStages;
import net.singularitymc.loader.util.PatchSettings;
import net.singularitymc.loader.util.VirtualWindows;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The classic Far Lands (docs/FARLANDS.md, {@code farlands_style = "classic"}): right after the noise step has filled a
 * chunk with stone, air and water, and before the surface (grass, sand), carvers, ores and trees, chunks from
 * 12,550,816 blocks out are refilled by {@link ClassicFarLands} (Beta 1.7.3's terrain, overflow included) between
 * Y 0 and 127. Above that is air; below Y 0 the modern deepslate layer stays. Only overworld-like dimensions (water
 * as the default fluid).
 *
 * <p>One hook per noise step: 26.3's {@code buildTerrain} and 26.1-26.2's {@code fillFromNoise}; the one the running
 * game does not have is skipped ({@code require = 0}).
 */
@Mixin(NoiseBasedChunkGenerator.class)
abstract class ClassicFarLandsMixin {
    @Shadow @Final private Holder<NoiseGeneratorSettings> settings;

    /** 26.3: the noise step is {@code buildTerrain}, which fills the chunk with {@code doFill(NoiseChunk, ChunkAccess)}. */
    @Inject(method = "lambda$buildTerrain$0", require = 0, at = @At(value = "INVOKE", shift = At.Shift.AFTER,
        target = "Lnet/minecraft/world/level/levelgen/NoiseBasedChunkGenerator;doFill(Lnet/minecraft/world/level/levelgen/NoiseChunk;Lnet/minecraft/world/level/chunk/ChunkAccess;)V"))
    private void Singularity$classicFarLands(ChunkAccess chunk, StructureManager structures, Blender blender, RandomState random,
                                             NoiseSettings noise, BiomeManager biomes, Set<?> sections, WorldGenRegion region,
                                             CallbackInfoReturnable<ChunkAccess> cir) {
        Singularity$refill(chunk, random);
    }

    /**
     * 26.1 and 26.2: the noise step is {@code fillFromNoise}, whose {@code doFill(Blender, StructureManager, RandomState,
     * ChunkAccess, int, int)} fills the chunk (with its sections locked) and returns it.
     */
    @Inject(method = "doFill(Lnet/minecraft/world/level/levelgen/blending/Blender;Lnet/minecraft/world/level/StructureManager;"
        + "Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/chunk/ChunkAccess;II)"
        + "Lnet/minecraft/world/level/chunk/ChunkAccess;", require = 0, at = @At("RETURN"))
    private void Singularity$classicFarLandsOld(Blender blender, StructureManager structures, RandomState random, ChunkAccess chunk,
                                                int minCellY, int cellCountY, CallbackInfoReturnable<ChunkAccess> cir) {
        Singularity$refill(cir.getReturnValue(), random);
    }

    @Unique
    private void Singularity$refill(ChunkAccess chunk, RandomState random) {
        ChunkPos pos = chunk.getPos();
        // A window of the virtual 64-bit world (/vtp) is generated from its virtual position, whatever the settings.
        VirtualWindows.Window window = VirtualWindows.atReal(pos.getMinBlockX(), pos.getMinBlockZ());
        if (window == null && (!PatchSettings.classicFarLands() || !ClassicFarLands.isFarLandsChunk(pos.x(), pos.z()))) {
            return;
        }
        NoiseGeneratorSettings s = this.settings.value();
        if (!s.defaultFluid().is(Blocks.WATER)) {
            return;
        }
        BlockState stone = s.defaultBlock();
        BlockState water = s.defaultFluid();
        BlockState air = Blocks.AIR.defaultBlockState();
        int maxY = chunk.getMinY() + chunk.getHeight() - 1;
        // the world seed: RandomState.seed() in 26.3, kept from the constructor in 26.1-26.2 (WorldSeeds)
        ClassicFarLands gen = ClassicFarLands.forSeed(WorldSeeds.of(random));
        boolean voidBelow = false;
        ClassicFarLands.BlockSink sink = (x, y, z, kind) -> {
            if (y >= chunk.getMinY() && y <= maxY) {
                BlockState state = kind == ClassicFarLands.STONE ? stone : kind == ClassicFarLands.WATER ? water : air;
                chunk.getSection(chunk.getSectionIndex(y)).setBlockState(x, y & 15, z, state, false);
            }
        };
        if (window != null) {
            long virtualChunkX = window.virtualX(pos.getMinBlockX()) >> 4;
            long virtualChunkZ = window.virtualZ(pos.getMinBlockZ()) >> 4;
            voidBelow = FarLandsStages.stage(virtualChunkX << 4, virtualChunkZ << 4).voidBelow();
            FarLandsStages.fill(gen, virtualChunkX, virtualChunkZ, s.seaLevel(), sink);
        } else {
            gen.fill(pos.x(), pos.z(), s.seaLevel(), sink);
        }
        // From the Fartherer Lands on, nothing is left below Y 0 either.
        for (int y = chunk.getMinY(); voidBelow && y < 0; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    sink.accept(x, y, z, ClassicFarLands.AIR);
                }
            }
        }
        // Beta's world ended at Y 128: clear the modern terrain above it.
        for (int y = Math.max(ClassicFarLands.TOP, chunk.getMinY()); y <= maxY; y++) {
            LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
            if (section.hasOnlyAir()) {
                y |= 15; // skip the rest of this empty section
                continue;
            }
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    section.setBlockState(x, y & 15, z, air, false);
                }
            }
        }
        Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG));
    }
}
