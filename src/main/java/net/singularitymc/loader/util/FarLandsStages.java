package net.singularitymc.loader.util;

import java.util.HashMap;
import java.util.Map;

/**
 * The Far Lands stages of the virtual 64-bit world (docs/FARLANDS.md). Stages 1 and 2 are Beta's own overflow
 * ({@link ClassicFarLands}). Stages 3 to 6 are past anything Beta could reach, so they model the overflow that
 * would come next, as described for this project:
 * <ul>
 *   <li>3, Gridded Lands (2^33): the sample offset overflows and the noise coordinates fold into a mirrored period of
 *       234 blocks, so the Farther Lands architecture repeats as a perfect mirrored grid.</li>
 *   <li>4, Fartherer Lands (53,905,378,846,980): interpolation fails; the density is read once per 1,024-block cell
 *       and 16-block layer, leaving floating rectangular slabs over a void without water.</li>
 *   <li>5, Striped Fartherer Lands (2^57): the low coordinate bits are gone; 4,096-block stripes along the far
 *       axis drop out to void.</li>
 *   <li>6, Final Fringelands (9,223,372,036,854,000,000): nothing is generated at all.</li>
 * </ul>
 * Pure Java, like {@link ClassicFarLands}; block kinds are the same constants.
 */
public final class FarLandsStages {
    public static final long FAR = 12_550_821L;
    public static final long FARTHER = 1_004_065_811L;
    public static final long GRIDDED = 8_589_934_592L;
    public static final long FARTHERER = 53_905_378_846_980L;
    public static final long STRIPED = 144_115_188_075_855_872L;
    public static final long FRINGE = 9_223_372_036_854_000_000L;

    /** Period of the Gridded Lands mirror, in blocks. */
    static final int GRID_PERIOD = 234;
    /** Where the Gridded Lands (and the slabs) take their architecture from: just inside the Edge Far Lands. */
    static final long GRID_SOURCE = 12_551_040L;
    /** Fartherer Lands: horizontal cell and vertical layer of the uninterpolated density. */
    static final int SLAB_CELL = 1024;
    static final int SLAB_LAYER = 16;
    /** Striped Fartherer Lands: width of the void stripes. */
    static final int STRIPE = 4096;

    public enum Stage {
        NORMAL("Normal terrain"),
        FAR_LANDS("Far Lands"),
        FARTHER_LANDS("Farther Lands"),
        GRIDDED_LANDS("Gridded Lands"),
        FARTHERER_LANDS("Fartherer Lands"),
        STRIPED_FARTHERER_LANDS("Striped Fartherer Lands"),
        FINAL_FRINGELANDS("Final Fringelands");

        private final String title;

        Stage(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }

        /** From the Fartherer Lands on, the world below the slabs is void too (no deepslate, no bedrock). */
        public boolean voidBelow() {
            return ordinal() >= FARTHERER_LANDS.ordinal();
        }
    }

    private FarLandsStages() {}

    /** |v| without overflowing for Long.MIN_VALUE. */
    static long distance(long v) {
        return v == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(v);
    }

    static Stage stageOf(long v) {
        long d = distance(v);
        if (d >= FRINGE) {
            return Stage.FINAL_FRINGELANDS;
        }
        if (d >= STRIPED) {
            return Stage.STRIPED_FARTHERER_LANDS;
        }
        if (d >= FARTHERER) {
            return Stage.FARTHERER_LANDS;
        }
        if (d >= GRIDDED) {
            return Stage.GRIDDED_LANDS;
        }
        if (d >= FARTHER) {
            return Stage.FARTHER_LANDS;
        }
        if (d >= FAR) {
            return Stage.FAR_LANDS;
        }
        return Stage.NORMAL;
    }

    /** The stage at a block position: the farther of the two axes (corners count as the farther stage). */
    public static Stage stage(long x, long z) {
        Stage a = stageOf(x);
        Stage b = stageOf(z);
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    /** "X 8,589,934,600  Z 0  (Gridded Lands)", for /vtp, /vpos and the action bar. */
    public static String describe(long x, long z) {
        return String.format("X %,d  Z %,d  (%s)", x, z, stage(x, z).title());
    }

    /** Generates one chunk of the virtual world, Y 0 to 127, like {@link ClassicFarLands#fill}. */
    public static void fill(ClassicFarLands gen, long chunkX, long chunkZ, int seaLevel, ClassicFarLands.BlockSink sink) {
        long blockX = chunkX << 4;
        long blockZ = chunkZ << 4;
        switch (stage(blockX, blockZ)) {
            case NORMAL, FAR_LANDS, FARTHER_LANDS -> gen.fill(chunkX, chunkZ, seaLevel, sink);
            case GRIDDED_LANDS -> gridded(gen, blockX, blockZ, seaLevel, sink);
            case FARTHERER_LANDS -> slabs(gen, blockX, blockZ, false, sink);
            case STRIPED_FARTHERER_LANDS -> slabs(gen, blockX, blockZ, true, sink);
            case FINAL_FRINGELANDS -> fillAll(sink, ClassicFarLands.AIR);
        }
    }

    /** Mirrored fold: 0, 1, ..., P-1, P-1, ..., 1, 0, 0, 1, ... */
    static int fold(long v) {
        int t = (int) Math.floorMod(v, 2L * GRID_PERIOD);
        return t < GRID_PERIOD ? t : 2 * GRID_PERIOD - 1 - t;
    }

    /**
     * The Edge Far Lands, sampled twice through the mirrored fold: once as the X edge (walls and tunnels along X) and
     * once rotated as the Z edge. Stone stays only where both have it, so the two sets of tunnels cut a lattice.
     */
    private static void gridded(ClassicFarLands gen, long blockX, long blockZ, int seaLevel, ClassicFarLands.BlockSink sink) {
        Map<Long, int[]> sources = new HashMap<>();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int fx = fold(blockX + x);
                int fz = fold(blockZ + z);
                int[] alongX = sourceColumn(gen, sources, GRID_SOURCE + fx, fz, seaLevel);
                int[] alongZ = sourceColumn(gen, sources, fz, GRID_SOURCE + fx, seaLevel);
                for (int y = 0; y < ClassicFarLands.TOP; y++) {
                    boolean stone = alongX[y] == ClassicFarLands.STONE && alongZ[y] == ClassicFarLands.STONE;
                    sink.accept(x, y, z, stone ? ClassicFarLands.STONE : y < seaLevel ? ClassicFarLands.WATER : ClassicFarLands.AIR);
                }
            }
        }
    }

    private static int[] sourceColumn(ClassicFarLands gen, Map<Long, int[]> cache, long x, long z, int seaLevel) {
        int[] chunk = cache.computeIfAbsent(x >> 4 << 32 ^ (z >> 4) & 0xFFFFFFFFL, k -> generate(gen, x >> 4, z >> 4, seaLevel));
        int[] column = new int[ClassicFarLands.TOP];
        for (int y = 0; y < ClassicFarLands.TOP; y++) {
            column[y] = chunk[index((int) (x & 15), y, (int) (z & 15))];
        }
        return column;
    }

    private static void slabs(ClassicFarLands gen, long blockX, long blockZ, boolean striped, ClassicFarLands.BlockSink sink) {
        // A chunk never spans two cells (1,024 is a multiple of 16), so the whole chunk reads one cell.
        long cellX = Math.floorDiv(blockX, SLAB_CELL);
        long cellZ = Math.floorDiv(blockZ, SLAB_CELL);
        boolean stripeOut = striped && (distance(blockX) >= STRIPED && (blockX / STRIPE & 1) != 0
            || distance(blockZ) >= STRIPED && (blockZ / STRIPE & 1) != 0);
        if (stripeOut) {
            fillAll(sink, ClassicFarLands.AIR);
            return;
        }
        // Nearest-neighbour instead of lerp: one Far Lands column decides every layer of the cell.
        long h = WidePacking.splitMix64(cellX * 0x9E3779B97F4A7C15L ^ cellZ);
        long sourceChunkX = (GRID_SOURCE >> 4) + (h & 1023);
        long sourceChunkZ = (h >>> 10 & 1023) - 512;
        int[] chunk = generate(gen, sourceChunkX, sourceChunkZ, 63);
        boolean[] solid = new boolean[ClassicFarLands.TOP / SLAB_LAYER];
        for (int layer = 0; layer < solid.length; layer++) {
            solid[layer] = chunk[index(8, layer * SLAB_LAYER + SLAB_LAYER / 2, 8)] == ClassicFarLands.STONE;
        }
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 0; y < ClassicFarLands.TOP; y++) {
                    sink.accept(x, y, z, solid[y / SLAB_LAYER] ? ClassicFarLands.STONE : ClassicFarLands.AIR);
                }
            }
        }
    }

    private static void fillAll(ClassicFarLands.BlockSink sink, int kind) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 0; y < ClassicFarLands.TOP; y++) {
                    sink.accept(x, y, z, kind);
                }
            }
        }
    }

    private static int[] generate(ClassicFarLands gen, long chunkX, long chunkZ, int seaLevel) {
        int[] kinds = new int[16 * ClassicFarLands.TOP * 16];
        gen.fill(chunkX, chunkZ, seaLevel, (x, y, z, kind) -> kinds[index(x, y, z)] = kind);
        return kinds;
    }

    private static int index(int x, int y, int z) {
        return (x * ClassicFarLands.TOP + y) * 16 + z;
    }
}
