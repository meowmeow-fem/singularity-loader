package net.singularitymc.loader.util;

import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Lossless {@code BlockPos} / {@code SectionPos} packing for worlds bigger than 2^25 blocks (stage 2 of
 * docs/COORDINATES.md). Inside +-2^25 blocks (and the valid Y range) every value is bit-identical to vanilla; only
 * positions vanilla would alias get a different long. Used by the patches in {@code patches.wide}, which are only
 * registered when {@code max_coordinate} needs them.
 *
 * <p>BlockPos (vanilla: X 26 bits at 38, Z 26 bits at 12, Y 12 bits at 0). World Y is -2032..2031, so the 32 raw Y
 * values 2032..2063 (as 12-bit unsigned) never occur. They are escape codes: 5 bits that, together with the 52 X/Z
 * bits, carry a 57-bit payload {@code [region 13][y 12][x low 16][z low 16]}. The region is an index into a table of
 * 65,536-block squares, filled on first use and never emptied while the server runs, so these longs must never be
 * written to disk (the game does not).
 *
 * <p>SectionPos (vanilla: X 22 bits at 42, Z 22 bits at 20, Y 20 bits at 0). Section Y only needs -128..127, which
 * leaves 2^20 - 256 unused Y values: exactly enough to number the 4095 other columns sharing the same X/Z fields
 * (6 high bits per axis) times 256 Y values. So every section of the full int range has its own long and no table is
 * needed. Section Y outside -128..127 (entities far above the build limit) is clamped.
 */
public final class WidePacking {
    /** Positions with |x| and |z| below this are packed exactly like vanilla. */
    public static final int VANILLA_REACH = 1 << 25;

    private static final long BLOCK_XZ_MASK = (1L << 26) - 1;
    private static final int BLOCK_X_SHIFT = 38;
    private static final int BLOCK_Z_SHIFT = 12;
    private static final int MIN_WORLD_Y = -2032;
    private static final int WORLD_Y_COUNT = 4064;
    private static final int ESCAPE_FIRST = 2032;

    private static final int REGION_SHIFT = 16;
    private static final int REGION_BITS = 13;
    /** Distinct 65,536 x 65,536-block squares that can hold far positions during one server run. */
    public static final int MAX_REGIONS = 1 << REGION_BITS;
    private static final int TABLE_SIZE = MAX_REGIONS * 2;

    private static final long SECTION_XZ_MASK = (1L << 22) - 1;
    private static final int SECTION_Y_CODES = 1 << 20;
    private static final int SECTION_MIN_Y = -128;
    private static final int SECTION_MAX_Y = 127;

    // System.Logger, not the loader's Log: this class is also compiled into the Fabric client mod (wide-client-mod).
    private static final System.Logger LOG = System.getLogger("Singularity");

    // Region table: open addressing; a slot holds region index + 1 (0 = empty), published with a volatile store
    // after the region's coordinates are written.
    private static final AtomicIntegerArray TABLE_SLOTS = new AtomicIntegerArray(TABLE_SIZE);
    private static final long[] REGION_X = new long[MAX_REGIONS];
    private static final long[] REGION_Z = new long[MAX_REGIONS];
    private static final Object LOCK = new Object();
    private static volatile int regionCount; // read without the lock by the 64-bit key decoders
    private static boolean warnedFull;

    private static final ThreadLocal<int[]> NETWORK_REFERENCE = new ThreadLocal<>();

    private WidePacking() {}

    /**
     * Whether a world reaching {@code limit} blocks needs wide packing: chunks up to 256 past the edge still load, and
     * their positions must not alias.
     */
    public static boolean isNeeded(int limit) {
        return limit > VANILLA_REACH - 256 * 16;
    }

    // ---- BlockPos ----

    public static long blockAsLong(int x, int y, int z) {
        return blockAsLong((long) x, y, (long) z);
    }

    /** 64-bit X/Z (3.5.2, used by the widened BlockPos); inside +-2^25 the same value as vanilla. */
    public static long blockAsLong(long x, int y, long z) {
        if (inVanillaReach(x) && inVanillaReach(z) && Integer.compareUnsigned(y - MIN_WORLD_Y, WORLD_Y_COUNT) < 0) {
            return vanillaBlockAsLong((int) x, y, (int) z);
        }
        int region = region(x >> REGION_SHIFT, z >> REGION_SHIFT);
        if (region < 0) {
            // Table full: behave like vanilla (aliases), but never produce an escape code.
            return vanillaBlockAsLong((int) x, Math.clamp(y, MIN_WORLD_Y, MIN_WORLD_Y + WORLD_Y_COUNT - 1), (int) z);
        }
        long payload = (long) region << 44 | ((long) y & 0xFFF) << 32 | (x & 0xFFFF) << 16 | (z & 0xFFFF);
        long xz = payload & ((1L << 52) - 1);
        return (xz >>> 26) << BLOCK_X_SHIFT | (xz & BLOCK_XZ_MASK) << BLOCK_Z_SHIFT | (ESCAPE_FIRST + (payload >>> 52));
    }

    public static int blockX(long packed) {
        return narrow(blockX64(packed));
    }

    public static long blockX64(long packed) {
        int u = (int) (packed & 0xFFF);
        if (!isEscape(u)) {
            return vanillaBlockX(packed);
        }
        long p = payload(packed, u);
        return REGION_X[(int) (p >>> 44)] << REGION_SHIFT | (p >>> 16 & 0xFFFF);
    }

    public static int blockY(long packed) {
        int u = (int) (packed & 0xFFF);
        if (!isEscape(u)) {
            return vanillaBlockY(packed);
        }
        return (int) (payload(packed, u) >>> 32) << 20 >> 20;
    }

    public static int blockZ(long packed) {
        return narrow(blockZ64(packed));
    }

    public static long blockZ64(long packed) {
        int u = (int) (packed & 0xFFF);
        if (!isEscape(u)) {
            return vanillaBlockZ(packed);
        }
        long p = payload(packed, u);
        return REGION_Z[(int) (p >>> 44)] << REGION_SHIFT | (p & 0xFFFF);
    }

    /** {@code BlockPos.getFlatIndex}: the position with the low 4 bits of Y cleared (vanilla does {@code packed & -16}). */
    public static long blockFlatIndex(long packed) {
        if (!isEscape((int) (packed & 0xFFF))) {
            return packed & -16L;
        }
        return blockAsLong(blockX64(packed), blockY(packed) & ~15, blockZ64(packed));
    }
    private static boolean inVanillaReach(long v) {
        return v >= -VANILLA_REACH && v < VANILLA_REACH;
    }

    private static boolean isEscape(int rawY) {
        return (rawY - ESCAPE_FIRST) >>> 5 == 0;
    }

    private static long payload(long packed, int rawY) {
        long xz = (packed >>> BLOCK_X_SHIFT) << 26 | (packed >>> BLOCK_Z_SHIFT & BLOCK_XZ_MASK);
        return (long) (rawY - ESCAPE_FIRST) << 52 | xz;
    }

    // ---- SectionPos ----

    public static long sectionAsLong(int x, int y, int z) {
        int cy = clampSectionY(y);
        // High 6 bits of x / z relative to what vanilla's sign extension of the 22-bit field would give.
        int dx = (x >> 22) - (x << 10 >> 31) & 63;
        int dz = (z >> 22) - (z << 10 >> 31) & 63;
        long yField;
        if ((dx | dz) == 0) {
            yField = cy & (SECTION_Y_CODES - 1);
        } else {
            int column = (dx << 6 | dz) - 1; // 0..4094
            yField = (long) column * 256 + (cy - SECTION_MIN_Y) + 128;
        }
        return ((long) x & SECTION_XZ_MASK) << 42 | ((long) z & SECTION_XZ_MASK) << 20 | yField;
    }

    public static int sectionX(long packed) {
        int vx = (int) (packed >>> 42) << 10 >> 10;
        int u = (int) (packed & (SECTION_Y_CODES - 1));
        if (isVanillaSectionY(u)) {
            return vx;
        }
        int dx = ((u - 128 >> 8) + 1) >> 6;
        return vx + (dx << 22) << 4 >> 4;
    }

    public static int sectionY(long packed) {
        int u = (int) (packed & (SECTION_Y_CODES - 1));
        if (isVanillaSectionY(u)) {
            return u << 12 >> 12;
        }
        return (u - 128 & 255) + SECTION_MIN_Y;
    }

    public static int sectionZ(long packed) {
        int vz = (int) (packed >>> 20 & SECTION_XZ_MASK) << 10 >> 10;
        int u = (int) (packed & (SECTION_Y_CODES - 1));
        if (isVanillaSectionY(u)) {
            return vz;
        }
        int dz = (u - 128 >> 8) + 1 & 63;
        return vz + (dz << 22) << 4 >> 4;
    }

    /** {@code SectionPos.getZeroNode}: the same column at section Y 0 (vanilla does {@code packed & -1048576}). */
    public static long sectionZeroNode(long packed) {
        return sectionAsLong(sectionX(packed), 0, sectionZ(packed));
    }

    public static int clampSectionY(int y) {
        return Math.clamp(y, SECTION_MIN_Y, SECTION_MAX_Y);
    }

    /** First key of every section with this X field (all Z, all Y), in the signed order of a {@code LongSortedSet}. */
    public static long sectionSlabStart(int x) {
        return ((long) x & SECTION_XZ_MASK) << 42;
    }

    public static long sectionSlabEnd(int x) {
        return sectionSlabStart(x) | (1L << 42) - 1;
    }

    /** First key of every section with these X and Z fields (all Y); includes the 4095 columns that share them. */
    public static long sectionColumnStart(int x, int z) {
        return sectionSlabStart(x) | ((long) z & SECTION_XZ_MASK) << 20;
    }

    public static long sectionColumnEnd(int x, int z) {
        return sectionColumnStart(x, z) | SECTION_Y_CODES - 1;
    }

    private static boolean isVanillaSectionY(int u) {
        return u < 128 || u >= SECTION_Y_CODES - 128;
    }

    // ---- SectionPos, 64-bit X/Z (3.5.4, experimental_long_positions) ----
    //
    // Sections of the int block range keep the layout above, but with section Y clamped to -127..126 (the 4064-block
    // world needs no more). That frees the two Y codes -128 and 127 in each of the 4096 columns sharing an X/Z field:
    // 8192 escape codes which, with the 44 X/Z bits, carry [region 13][y 8][x low 12][z low 12] for every other
    // section. Regions are the same 65,536-block squares as for block positions. The int API above never escapes.

    private static final long SECTION_INT_REACH = 1L << 27;
    private static final int SECTION_COLUMNS = 4096;

    public static long sectionAsLong(long x, int y, long z) {
        int cy = clampSectionY64(y);
        if (x >= -SECTION_INT_REACH && x < SECTION_INT_REACH && z >= -SECTION_INT_REACH && z < SECTION_INT_REACH) {
            return sectionAsLong((int) x, cy, (int) z);
        }
        int region = region(x >> 12, z >> 12);
        if (region < 0) {
            return sectionAsLong((int) x, cy, (int) z); // table full: aliases
        }
        long payload = (long) region << 32 | (long) (cy + 128) << 24 | (x & 0xFFF) << 12 | (z & 0xFFF);
        int e = (int) (payload >>> 44);
        int column = e >> 1;
        boolean top = (e & 1) != 0;
        long yField = column == SECTION_COLUMNS - 1 ? (top ? 127 : SECTION_Y_CODES - 128) : (long) column * 256 + 128 + (top ? 255 : 0);
        return (payload >>> 22 & SECTION_XZ_MASK) << 42 | (payload & SECTION_XZ_MASK) << 20 | yField;
    }

    public static long sectionX64(long packed) {
        long p = sectionPayload(packed);
        return p < 0 ? sectionX(packed) : REGION_X[(int) (p >>> 32)] << 12 | (p >>> 12 & 0xFFF);
    }

    public static int sectionY64(long packed) {
        long p = sectionPayload(packed);
        return p < 0 ? sectionY(packed) : (int) (p >>> 24 & 0xFF) - 128;
    }

    public static long sectionZ64(long packed) {
        long p = sectionPayload(packed);
        return p < 0 ? sectionZ(packed) : REGION_Z[(int) (p >>> 32)] << 12 | (p & 0xFFF);
    }

    public static long sectionZeroNode64(long packed) {
        return sectionAsLong(sectionX64(packed), 0, sectionZ64(packed));
    }

    public static int clampSectionY64(int y) {
        return Math.clamp(y, SECTION_MIN_Y + 1, SECTION_MAX_Y - 1);
    }

    /** Whether the sections of this X column use the int layout (then key ranges select them, see EntitySectionStorage). */
    public static boolean sectionRanges(int x) {
        return true;
    }

    public static boolean sectionRanges64(long x) {
        return x >= -SECTION_INT_REACH && x < SECTION_INT_REACH;
    }

    /** The escape payload of a 64-bit section key, or -1 for a key of the int layout. */
    private static long sectionPayload(long packed) {
        int u = (int) (packed & (SECTION_Y_CODES - 1));
        int e;
        if (u == 127) {
            e = (SECTION_COLUMNS - 1) << 1 | 1;
        } else if (u == SECTION_Y_CODES - 128) {
            e = (SECTION_COLUMNS - 1) << 1;
        } else if (u >= 128 && u < SECTION_Y_CODES - 128 && ((u - 128 & 255) == 0 || (u - 128 & 255) == 255)) {
            e = (u - 128 >> 8) << 1 | ((u - 128 & 255) == 255 ? 1 : 0);
        } else {
            return -1;
        }
        long p = (long) e << 44 | (packed >>> 42) << 22 | (packed >>> 20 & SECTION_XZ_MASK);
        return (p >>> 32) < regionCount ? p : -1;
    }

    // ---- ChunkPos keys (3.5.4) ----
    //
    // Vanilla: X in the low 32 bits, Z in the high 32 bits. Chunks within +-2^29 (blocks within +-2^33) keep exactly
    // that. Farther chunks use X field values 2^30..2^31-1, which no such chunk has: 30 + 32 bits carrying
    // [region 13][x low 12][z low 12], regions being the same 65,536-block (4096-chunk) squares. The int API is vanilla.

    private static final long CHUNK_VANILLA_REACH = 1L << 29;

    public static long chunkPack(int x, int z) {
        return (long) x & 0xFFFFFFFFL | ((long) z & 0xFFFFFFFFL) << 32;
    }

    public static int chunkX(long key) {
        return (int) key;
    }

    public static int chunkZ(long key) {
        return (int) (key >>> 32);
    }

    public static long chunkPack(long x, long z) {
        if (x >= -CHUNK_VANILLA_REACH && x < CHUNK_VANILLA_REACH && z >= -CHUNK_VANILLA_REACH && z < CHUNK_VANILLA_REACH) {
            return chunkPack((int) x, (int) z);
        }
        int region = region(x >> 12, z >> 12);
        if (region < 0) {
            return chunkPack((int) x, (int) z); // table full: aliases
        }
        long payload = (long) region << 24 | (x & 0xFFF) << 12 | (z & 0xFFF);
        return (1L << 30 | payload & (1L << 30) - 1) | (payload >>> 30) << 32;
    }

    public static long chunkX64(long key) {
        long p = chunkPayload(key);
        return p < 0 ? chunkX(key) : REGION_X[(int) (p >>> 24)] << 12 | (p >>> 12 & 0xFFF);
    }

    public static long chunkZ64(long key) {
        long p = chunkPayload(key);
        return p < 0 ? chunkZ(key) : REGION_Z[(int) (p >>> 24)] << 12 | (p & 0xFFF);
    }

    /**
     * Whether a chunk can be saved (3.5.5): its coordinates must fit the save format's ints, and stay below 2^30 so
     * its key in the vanilla int-pair form (structure references) cannot be taken for a 64-bit one. That is blocks up
     * to +-2^34 (17,179,869,184); chunks beyond are neither saved nor loaded and regenerate after a restart.
     */
    public static boolean chunkSavable(int x, int z) {
        return true;
    }

    public static boolean chunkSavable64(long x, long z) {
        long reach = 1L << 30;
        return x >= -reach && x < reach && z >= -reach && z < reach;
    }

    /** A chunk key as saved (structure references): the vanilla int-pair form, also for 64-bit keys. */
    public static long chunkKeyForSave(long key) {
        return chunkPack((int) chunkX64(key), (int) chunkZ64(key));
    }

    private static long chunkPayload(long key) {
        int x = (int) key;
        if (x >>> 30 != 1) {
            return -1;
        }
        long p = (key >>> 32) << 30 | x & (1L << 30) - 1;
        return (p >>> 24) < regionCount ? p : -1;
    }

    // ---- Narrowing (3.5.8) ----
    //
    // What int code sees of a 64-bit coordinate. Inside the int range: the coordinate itself. Beyond it the world is
    // cut into stretches of 2^31 - 2^24 blocks, and each is seen shifted, as one translation: positive ones as ints in
    // [-2^31, -2^24), negative ones in [2^24, 2^31). So int code never sees a far coordinate as a small int, which it
    // could not tell from an offset, a height or an index (until 3.5.8 int code saw the low 32 bits, and everything
    // within 4,096 blocks of a multiple of 2^32 (2^32, 2^47, 2^50, ...) was generated next to 0: crashes). Within a
    // stretch int arithmetic stays exact; at the end of one the next starts lower (int loops across it run empty, as
    // at the int range's own end). The first stretch on each side is the low 32 bits, as before, so everything within
    // +-4,278,190,080 blocks (saves included) is unchanged. Quart and chunk coordinates are seen as the block one
    // shifted right, or that plus or minus 2^30 / 2^28 when narrowed directly, which keeps chunk coordinates within
    // that range as they are (region files, saved chunk positions).

    private static final long SAW_GUARD = 1L << 24;
    /** Blocks per stretch: the ints [-2^31, -2^24) or [2^24, 2^31). */
    private static final long SAW_LENGTH = (1L << 31) - SAW_GUARD;
    private static final long INT_RANGE = 1L << 31;

    /** The translation of v's stretch: int code sees {@code (int) (v + sawShift(v))}. 0 inside the int range. */
    static long sawShift(long v) {
        if (legacyThreads.get() != 0 && LEGACY.get()[0] != 0) {
            return (int) v - v; // reading a save made before 3.5.8: int code saw the low 32 bits
        }
        if (v >= INT_RANGE) {
            long start = INT_RANGE + (v - INT_RANGE) / SAW_LENGTH * SAW_LENGTH;
            return Integer.MIN_VALUE - start;
        }
        if (v < -INT_RANGE) {
            long end = -INT_RANGE - 1 - (-INT_RANGE - 1 - v) / SAW_LENGTH * SAW_LENGTH;
            return Integer.MAX_VALUE - end;
        }
        return 0;
    }

    // Saves made before this narrowing hold the ints int code saw then (the low 32 bits) for chunks past
    // +-4,278,190,080 blocks (they are saved up to 2^34). While one of those is read, this thread narrows the old way.
    private static final java.util.concurrent.atomic.AtomicInteger legacyThreads = new java.util.concurrent.atomic.AtomicInteger();
    private static final ThreadLocal<int[]> LEGACY = ThreadLocal.withInitial(() -> new int[1]);

    /** From here until {@link #endOldNarrowing}, this thread reads ints as int code saw them before 3.5.8. */
    public static void beginOldNarrowing() {
        int[] l = LEGACY.get();
        if (l[0]++ == 0) {
            legacyThreads.incrementAndGet();
            resetCarry(); // remembered coordinates hold the translation of the other narrowing
        }
    }

    public static void endOldNarrowing() {
        int[] l = LEGACY.get();
        if (l[0] > 0 && --l[0] == 0) {
            legacyThreads.decrementAndGet();
            resetCarry();
        }
    }

    /** Whether int code saw block {@code v} differently before 3.5.8 (past the first stretch on each side). */
    public static boolean narrowedDifferentlyBefore(long v) {
        return narrow(v) != (int) v;
    }

    /** A block coordinate as int code sees it. */
    public static int narrow(long v) {
        return (int) (v + sawShift(v));
    }

    /** A coordinate at scale {@code shift} (2: quart, 4: chunk / section) as int code gets it narrowed directly. */
    public static int narrowScaled(long w, int shift) {
        if (shift == 0) return narrow(w);
        long t = sawShift(w << shift);
        return (int) ((w << shift) + t >> shift) - Long.signum(t) * (1 << 32 - shift);
    }

    public static int narrowChunk(long c) {
        return narrowScaled(c, 4);
    }

    public static int narrowQuart(long q) {
        return narrowScaled(q, 2);
    }

    /**
     * The value at scale {@code shift} that int code may have made {@code v} of, starting from block {@code ref}: by
     * int arithmetic on its narrowed form (the translation of ref's stretch, carried on past its end), or narrowed
     * fresh in the stretch before or after (when ref is within {@code near} blocks of an end). The one nearest to ref,
     * or {@link Long#MIN_VALUE} if none is within {@code near} blocks. {@code dist[0]} gets the distance in blocks.
     */
    static long nearestScaled(int v, long ref, int shift, long near, long[] dist) {
        long scaled = ref >> shift;
        int unused = 32 + shift;
        long t = sawShift(ref);
        long delta = v - (ref + t >> shift) << unused >> unused;
        long best = Long.MIN_VALUE;
        long bestDistance = near;
        if (Math.abs(delta) << shift < bestDistance) {
            best = scaled + delta;
            bestDistance = Math.abs(delta) << shift;
        }
        if (t != 0 || Math.abs(ref) > INT_RANGE - near) {
            for (long edge = -1; edge <= 1; edge += 2) {
                long tn = sawShift(ref + edge * near);
                if (tn == t || (int) tn == (int) t && (tn == 0 || t == 0)) continue; // same stretch, or the int range's own end
                long d = v - (ref + tn >> shift) << unused >> unused;
                long c = scaled + d;
                if (Math.abs(d) << shift < bestDistance && sawShift(c << shift) == tn) {
                    best = c;
                    bestDistance = Math.abs(d) << shift;
                }
            }
        }
        if (dist != null) dist[0] = bestDistance;
        return best;
    }

    /**
     * The block (shift 0), quart or chunk coordinate nearest to block {@code nearBlock} that int code sees as
     * {@code v}: for ints made by int code for a position near a known one (a player's network origin).
     */
    public static long unnarrowNear(int v, long nearBlock, int shift) {
        long c = nearestScaled(v, nearBlock, shift, 1L << 30, null);
        return c != Long.MIN_VALUE ? c : v;
    }

    // ---- Carry (3.5.4) ----
    //
    // Code that is not widened yet sees a 64-bit coordinate narrowed (see above). Every narrowing of a far coordinate
    // (widened code handing it to int code) remembers it on the thread; every int coming back into widened code is
    // moved to the value int code would have made it of, next to one of those, or taken as it is. So int code doing
    // local math on a far position (an offset, a neighbour, a chunk of it) hands back the true 64-bit value, as long as
    // it stays within 2^24 blocks of it. Small ints (|v| < 4096, offsets and heights) are taken as they are; no far
    // coordinate narrows to one.

    private static final int CARRY_SMALL = 4096;
    /** Int code works locally: a value is only moved next to a remembered coordinate within this distance of it. */
    private static final long CARRY_NEAR = 1L << 24;
    /** Blocks, quarts (biomes, block >> 2) and sections / chunks (block >> 4) derived from a carried block coordinate. */
    private static final int[] CARRY_SHIFTS = {0, 2, 4};
    /**
     * Far areas a thread can work in at once (3.5.8: 8; with 4, the server thread ticking entities in five forceloaded
     * areas forgot one, and its falling blocks, whose positions int code made from narrowed ints, lost their place).
     */
    private static final int CARRY_SLOTS = 8;
    private static volatile boolean carried;
    /**
     * Per thread: the remembered coordinates (most recent first), then the translation of each one's stretch
     * ({@link #sawShift}), then whether each lies within {@link #CARRY_NEAR} of its stretch's end (1) or not (0).
     */
    private static final ThreadLocal<long[]> CARRY = ThreadLocal.withInitial(() -> new long[CARRY_SLOTS * 3]);

    /**
     * A long coordinate narrowed for int code ({@link #narrow}), remembered when that loses information, or when int
     * code is close to overflowing it (within {@link #CARRY_SEAM} of 2^31: {@code floor(x) + 1} at 2,147,483,647).
     */
    public static int carryOut(long v) {
        int i = narrow(v);
        if (i != v || (i > CARRY_SEAM || i < -CARRY_SEAM) && !acrossTheSeam(v)) {
            remember(v);
        }
        return loopSafe(i);
    }

    /**
     * Int code never gets the int extremes as coordinates (3.5.8): it loops {@code for (x = min; x <= max; x++)} over
     * boxes, and with {@code max == Integer.MAX_VALUE} {@code x++} wraps and the loop never ends (a fish whose box
     * ended at x = 2,147,483,647: the carry turned the wrapped x into positions ever further east, and the server
     * generated chunk after chunk for it until the watchdog stopped it). The one column at exactly +-2^31 reads as its
     * neighbour in int code; the same for chunk and quart coordinates at their ends.
     */
    private static int loopSafe(int i) {
        return i == Integer.MAX_VALUE ? Integer.MAX_VALUE - 1 : i == Integer.MIN_VALUE ? Integer.MIN_VALUE + 1 : i;
    }

    /**
     * Whether an int near +-2^31 is already remembered as the value int code sees the same way on the other side of
     * the seam (3.5.8). Int code wraps at 2^31 (a fish next to x = 2,147,483,647 swims to -2,147,483,648 as far as the
     * pathfinder knows); remembering that int as it is put the other side of the world next to where the thread
     * works, and int code there then loaded chunks 4 billion blocks away (watchdog).
     */
    private static boolean acrossTheSeam(long v) {
        long[] refs = CARRY.get();
        for (int i = 0; i < CARRY_SLOTS; i++) {
            long ref = refs[i];
            if (ref != 0 && Math.abs(ref - v) > 1L << 31 && Math.abs((long) (int) (v - ref - refs[CARRY_SLOTS + i])) < CARRY_NEAR) {
                return true;
            }
        }
        return false;
    }

    /** Ints beyond this are remembered even though they fit: int code adding to them may wrap. */
    private static final int CARRY_SEAM = Integer.MAX_VALUE - (1 << 24);

    // ---- Transit (3.5.8) ----
    //
    // Widened code calling a widened interface that a lambda implements: the lambda keeps the interface's int
    // signature, so the coordinates are narrowed on one side and widened on the other with nothing but a call in
    // between. The caller hands its int arguments over here in order ({@link #transitBegin}, then one call per
    // argument); the lambda takes each back by its position when it starts. So they arrive exactly, at whatever
    // scale they are (block, quart and chunk coordinates can have the same low 32 bits), and stay out of the carry's
    // remembered coordinates. An int that does not match its slot (int code called the lambda) is carried.

    private static volatile boolean transited;
    /**
     * The number of argument slots of a frame. The slot after them holds the count of the call, the next one the
     * count its receiver saw. A frame lasts until its call returns (3.5.8): left behind, a callback that int code
     * called later took the old arguments whenever their low 32 bits matched, so at exact multiples of 2^32 a carving
     * lambda read its local x = 0 as the far coordinate and the aquifer indexed 2^32 blocks off. While the call runs
     * it stays, because a lambda that hands its ints straight on to another callback (StaticCache2D.map) relies on it.
     */
    public static final int TRANSIT_SLOTS = 8;
    private static final int TRANSIT_TAKEN = TRANSIT_SLOTS + 1;
    private static final ThreadLocal<long[]> TRANSIT = ThreadLocal.withInitial(() -> new long[TRANSIT_SLOTS + 2]);

    /**
     * The calling side of a hop, in one thread-local lookup (3.5.8: generation calls surface rules and density
     * functions millions of times per chunk): the frame, emptied, which the caller fills with its int arguments in
     * order ({@code frame[k] = argument}, then {@code frame[TRANSIT_SLOTS] = count}).
     */
    public static long[] transitFrameForCall() {
        long[] t = TRANSIT.get();
        t[TRANSIT_SLOTS] = 0;
        if (!transited) {
            transited = true;
        }
        return t;
    }

    /**
     * The receiving side (one lookup when a lambda starts): the frame of the call that filled it. The receiver takes
     * all its arguments right after this, before any call of its own.
     */
    public static long[] transitFrame() {
        long[] t = TRANSIT.get();
        t[TRANSIT_TAKEN] = t[TRANSIT_SLOTS];
        return t;
    }

    /** The int argument at {@code position} of the call that left {@code frame}, or the carried int. */
    public static long transitTake(long[] frame, int v, int position) {
        return position < frame[TRANSIT_TAKEN] && narrowsTo(frame[position], v) ? frame[position] : carryIn(v);
    }

    public static long transitTakeChunk(long[] frame, int v, int position) {
        return position < frame[TRANSIT_TAKEN] && narrowsToChunk(frame[position], v) ? frame[position] : carryInChunk(v);
    }

    /**
     * Whether int code got {@code v} for {@code f}, narrowed at any scale: the side that narrowed it and the one taking
     * it back do not always agree on the scale (a lambda handing its ints straight on to another callback).
     */
    private static boolean narrowsTo(long f, int v) {
        if (f >= -(1L << 27) && f < 1L << 27) return (int) f == v;
        return narrow(f) == v || narrowChunk(f) == v || narrowQuart(f) == v;
    }

    private static boolean narrowsToChunk(long f, int v) {
        return narrowsTo(f, v);
    }

    /** The calling side, after the call returned: the frame is spent, whether or not a receiver took it. */
    public static void transitEnd(long[] frame) {
        frame[TRANSIT_SLOTS] = 0;
        frame[TRANSIT_TAKEN] = 0;
    }

    public static void transitBegin() {
        long[] t = TRANSIT.get();
        t[TRANSIT_SLOTS] = 0;
        t[TRANSIT_TAKEN] = 0;
    }

    /** A coordinate argument, narrowed. */
    public static int carryOutTransit(long v) {
        long[] t = TRANSIT.get();
        int n = (int) t[TRANSIT_SLOTS];
        if (n < TRANSIT_SLOTS) {
            t[n] = v;
            t[TRANSIT_SLOTS] = n + 1;
        }
        transited = true;
        return narrow(v);
    }

    /** Another int argument (it keeps the positions of the coordinates). */
    public static int transitInt(int v) {
        return carryOutTransit(v);
    }

    /** The int argument at {@code position} (among the call's int arguments). */
    public static long carryInTransitAt(int v, int position) {
        long t = transitAt(v, position);
        return t != Long.MIN_VALUE ? t : carryIn(v);
    }

    public static long carryInTransitChunkAt(int v, int position) {
        long t = transitAt(v, position);
        return t != Long.MIN_VALUE ? t : carryInChunk(v);
    }

    /**
     * An int returned over the same hop: an implementor with only the int signature (outside the set) computed it,
     * mostly from the arguments it was handed, so it is carried next to them first.
     */
    public static long carryInReturn(int v) {
        if (transited) {
            long[] t = TRANSIT.get();
            long best = Long.MIN_VALUE;
            long bestDistance = CARRY_NEAR;
            long n = Math.max(t[TRANSIT_SLOTS], t[TRANSIT_TAKEN]); // taken by the receiver, or not
            for (int k = 0; k < n; k++) {
                if (narrow(t[k]) == t[k]) continue; // int code has it exactly
                long c = nearestScaled(v, t[k], 0, bestDistance, null);
                if (c != Long.MIN_VALUE) {
                    best = c;
                    bestDistance = Math.abs(c - t[k]);
                }
            }
            if (best != Long.MIN_VALUE) {
                return best;
            }
        }
        return carryIn(v);
    }

    private static long transitAt(int v, int position) {
        if (transited) {
            long[] t = TRANSIT.get();
            if (position < Math.max(t[TRANSIT_SLOTS], t[TRANSIT_TAKEN]) && (narrowsTo(t[position], v) || narrowsToChunk(t[position], v))) {
                return t[position];
            }
        }
        return Long.MIN_VALUE;
    }

    /**
     * {@link #carryIn} for an int that is known to be a block coordinate (3.5.8): the argument of an int signature of
     * the widened set that int code calls (they take block coordinates unless they are listed as chunk or quart ones).
     * Only compared at block scale: where a block coordinate and its quart or chunk coordinate have nearly the same low
     * 32 bits, an int one block away from where int code started reads as either.
     */
    public static long carryInBlock(int v) {
        return carryInAt(v, 0);
    }

    /** The same for a quart (biome, block >> 2) coordinate. */
    public static long carryInQuart(int v) {
        return carryInAt(v, 2);
    }

    /** {@link #carryOut} for a quart coordinate: remembered at block scale. */
    public static int carryOutQuart(long v) {
        int i = narrowQuart(v);
        if (i != v || i > CARRY_SEAM || i < -CARRY_SEAM) {
            remember((v << 2) + 2);
        }
        return loopSafe(i);
    }

    /**
     * The value at scale {@code shift} next to remembered coordinate {@code i} that int code may have made {@code v}
     * of, if it is within {@code limit} blocks of it, else {@link Long#MIN_VALUE}. Int code shifting a narrowed block
     * coordinate right by s is off by a multiple of 2^(32 - s) from the coordinate narrowed directly at that scale.
     */
    private static long carriedFrom(long[] refs, int i, int v, int shift, long limit) {
        long ref = refs[i];
        if (refs[CARRY_SLOTS * 2 + i] != 0) {
            return nearestScaled(v, ref, shift, limit, null); // the stretch ends nearby: the next one counts too
        }
        int unused = 32 + shift; // the bits above 2^(32 - s) of a 64-bit value
        long delta = v - (ref + refs[CARRY_SLOTS + i] >> shift) << unused >> unused;
        return Math.abs(delta) << shift < limit ? (ref >> shift) + delta : Long.MIN_VALUE;
    }

    private static long carryInAt(int v, int shift) {
        if (!carried || v > -CARRY_SMALL && v < CARRY_SMALL) {
            return v;
        }
        long best = v;
        long bestDistance = Math.min(Math.abs((long) v) << shift, CARRY_NEAR); // in blocks
        long[] refs = CARRY.get();
        for (int i = 0; i < CARRY_SLOTS; i++) {
            if (refs[i] == 0) {
                continue;
            }
            long c = carriedFrom(refs, i, v, shift, bestDistance);
            if (c != Long.MIN_VALUE) {
                best = c;
                bestDistance = Math.abs(c - (refs[i] >> shift)) << shift;
            }
        }
        return best;
    }

    /** An int from int code entering widened code; see the section comment. */
    public static long carryIn(int v) {
        if (!carried || v > -CARRY_SMALL && v < CARRY_SMALL) {
            return v;
        }
        long best = v;
        long bestDistance = Math.min(Math.abs((long) v), CARRY_NEAR);
        long[] refs = CARRY.get();
        for (int i = 0; i < CARRY_SLOTS; i++) {
            if (refs[i] == 0) {
                continue;
            }
            for (int shift : CARRY_SHIFTS) {
                // distances in blocks (3.5.7): 15 blocks from the reference is nearer than 3 chunks from it
                long c = carriedFrom(refs, i, v, shift, bestDistance);
                if (c != Long.MIN_VALUE) {
                    best = c;
                    bestDistance = Math.abs(c - (refs[i] >> shift)) << shift;
                }
            }
        }
        return best;
    }

    /**
     * {@link #carryOut} for a chunk (or section) coordinate, at the int signatures that are known to hand out one
     * (3.5.7): it is remembered at block scale, the scale every remembered coordinate has. Past 2^35 blocks the chunk
     * coordinates no longer fit an int either.
     */
    public static int carryOutChunk(long v) {
        int i = narrowChunk(v);
        if (i != v || i > CARRY_SEAM || i < -CARRY_SEAM) {
            remember((v << 4) + 8);
        }
        return loopSafe(i);
    }

    /**
     * {@link #carryIn} for an int that is known to be a chunk (or section) coordinate (3.5.7): only compared at chunk
     * scale. Without that knowledge a chunk coordinate can be taken for a block coordinate whose low 32 bits are close
     * to it (about 1 in 128 places; every time at x = 0x33333333333, whose chunk has the same low 32 bits).
     */
    public static long carryInChunk(int v) {
        // off by a multiple of 2^28 between a chunk coordinate narrowed directly and int code's narrowed block >> 4
        return carryInAt(v, 4);
    }

    /**
     * {@code Mth.floor} / {@code ceil} for widened code: int code often turns a narrowed coordinate into a double
     * before handing it over (entities placed by structures, spawning), so a result in the int range is carried like
     * an int, at block scale (3.5.8: the floor of a position is a block coordinate; read as a quart or chunk
     * coordinate, an entity's box near a coincidence spanned billions of sections). A real position exactly 2^32
     * blocks (+-16M) from where the thread just worked far out is mistaken for it.
     */
    public static long floorCarry(double d) {
        long v = (long) d;
        if (d < v) {
            v--;
        }
        return v == (int) v ? carryInBlock((int) v) : v;
    }

    public static long ceilCarry(double d) {
        long v = (long) d;
        if (d > v) {
            v++;
        }
        return v == (int) v ? carryInBlock((int) v) : v;
    }

    /**
     * A coordinate int code made into a double from a narrowed int ({@code (double) x + 0.5}): the whole part is
     * carried, the fraction kept. For places known to do that and then use the double itself (natural spawning places
     * the mob there).
     */
    public static double carryInDouble(double d) {
        double whole = Math.floor(d);
        if (whole < Integer.MIN_VALUE || whole > Integer.MAX_VALUE) {
            return d;
        }
        return carryInBlock((int) whole) + (d - whole);
    }

    /**
     * Data read for a chunk (block entities, scheduled ticks, structure boxes, POIs, 3.5.5) holds its block positions
     * as ints; remembering the chunk lets them be carried back to it.
     */
    public static void carryChunk(int chunkX, int chunkZ) {
        carryAt((carryInChunk(chunkX) << 4) + 8, (carryInChunk(chunkZ) << 4) + 8);
    }

    /**
     * {@link #carryChunk} from the chunk's key ({@code ChunkPos.pack()}, 64-bit with experimental_long_positions):
     * exact, where the int coordinates of a chunk at a multiple of 2^32 blocks read as chunks next to 0.
     */
    public static void carryChunkKey(long key) {
        carryAt((chunkX64(key) << 4) + 8, (chunkZ64(key) << 4) + 8);
    }

    /** The same for an entity whose position was just read: the block positions it saved are near it. */
    public static void carryAt(double x, double z) {
        carryAt((long) Math.floor(x), (long) Math.floor(z));
    }

    private static void carryAt(long x, long z) {
        carryOut(z);
        carryOut(x); // most recent first: X is the more common far axis
    }

    /**
     * Remembers a narrowed coordinate, most recent first. Every entry is at block scale: a chunk coordinate narrowed
     * through a plain {@link #carryOut} (widened code handing it to int code, 3.5.7) is recognised as the chunk of a
     * remembered block and keeps that block, and a block replaces its chunk. Otherwise a chunk coordinate whose low 32
     * bits are close to the block's (always at x = 0x33333333333) would carry block ints to the chunk's value.
     */
    private static void remember(long v) {
        long[] refs = CARRY.get();
        int slot = CARRY_SLOTS - 1;
        long chunkNear = CARRY_NEAR >> 4;
        for (int i = 0; i < CARRY_SLOTS; i++) {
            long r = refs[i];
            if (Math.abs(r - v) < 1L << 24 || r != 0 && Math.abs((v >> 4) - r) < chunkNear) {
                slot = i;
                break;
            }
            if (r != 0 && Math.abs(v - (r >> 4)) < chunkNear) {
                v = r;
                slot = i;
                break;
            }
        }
        if (slot == 0 && refs[0] == v) {
            return;
        }
        if (slot > 0) { // most recent first, in all three parts
            System.arraycopy(refs, 0, refs, 1, slot);
            System.arraycopy(refs, CARRY_SLOTS, refs, CARRY_SLOTS + 1, slot);
            System.arraycopy(refs, CARRY_SLOTS * 2, refs, CARRY_SLOTS * 2 + 1, slot);
        }
        long t = sawShift(v);
        refs[0] = v;
        refs[CARRY_SLOTS] = t;
        refs[CARRY_SLOTS * 2] = t != 0 && (sawShift(v - CARRY_NEAR) != t || sawShift(v + CARRY_NEAR) != t) ? 1 : 0;
        carried = true;
    }

    /** Tests only: forgets this thread's carried coordinates. */
    static void resetCarry() {
        java.util.Arrays.fill(CARRY.get(), 0);
    }

    // ---- Vanilla layouts (network format) ----

    public static long vanillaBlockAsLong(int x, int y, int z) {
        return ((long) x & BLOCK_XZ_MASK) << BLOCK_X_SHIFT | ((long) z & BLOCK_XZ_MASK) << BLOCK_Z_SHIFT | (long) y & 0xFFF;
    }

    public static int vanillaBlockX(long packed) {
        return (int) (packed >> BLOCK_X_SHIFT);
    }

    public static int vanillaBlockY(long packed) {
        return (int) (packed << 52 >> 52);
    }

    public static int vanillaBlockZ(long packed) {
        return (int) (packed << 26 >> 38);
    }

    public static long vanillaSectionAsLong(int x, int y, int z) {
        return ((long) x & SECTION_XZ_MASK) << 42 | ((long) z & SECTION_XZ_MASK) << 20 | (long) y & (SECTION_Y_CODES - 1);
    }

    public static int vanillaSectionX(long packed) {
        return (int) (packed >> 42);
    }

    public static int vanillaSectionY(long packed) {
        return (int) (packed << 44 >> 44);
    }

    public static int vanillaSectionZ(long packed) {
        return (int) (packed << 22 >> 42);
    }

    // ---- Positions read from the network ----

    /**
     * The player the packets decoded on this thread are about (the sender on a server, the local player on a client);
     * block and section positions are un-aliased around them.
     */
    public static void setNetworkReference(int x, int z) {
        NETWORK_REFERENCE.set(new int[] {x, z});
    }

    public static void clearNetworkReference() {
        NETWORK_REFERENCE.remove();
    }

    // ---- Floating origin (3.5.6, experimental_long_positions) ----
    //
    // Each player's client sees the world shifted by an origin near it (net.singularitymc.network.FloatingOrigin), so
    // its coordinates stay small. While a packet of that player is encoded or decoded, the origin is set here.

    private static final ThreadLocal<long[]> NETWORK_ORIGIN = new ThreadLocal<>();

    public static void setNetworkOrigin(long[] origin) {
        if (origin == null || origin[0] == 0 && origin[1] == 0) {
            NETWORK_ORIGIN.remove();
        } else {
            NETWORK_ORIGIN.set(origin);
        }
    }

    public static void clearNetworkOrigin() {
        NETWORK_ORIGIN.remove();
    }

    /** Whether the packet being encoded or decoded is in a shifted frame. */
    public static boolean hasNetworkOrigin() {
        return NETWORK_ORIGIN.get() != null;
    }

    public static long networkOriginX() {
        long[] o = NETWORK_ORIGIN.get();
        return o == null ? 0 : o[0];
    }

    public static long networkOriginZ() {
        long[] o = NETWORK_ORIGIN.get();
        return o == null ? 0 : o[1];
    }

    /**
     * A block X as the client sees it, from the int int code has of it ({@link #narrow}): the position it stands for
     * next to the player's origin, minus the origin.
     */
    public static int clientBlockX(int x) {
        long o = networkOriginX();
        return (int) (unnarrowNear(x, o, 0) - o);
    }

    public static int clientBlockZ(int z) {
        long o = networkOriginZ();
        return (int) (unnarrowNear(z, o, 0) - o);
    }

    public static double clientX(double x) {
        return x - networkOriginX();
    }

    public static double clientZ(double z) {
        return z - networkOriginZ();
    }

    /**
     * A world border centre for the client: in its frame, or at its 0 when that would be farther than a vanilla client
     * accepts (the border is then far bigger than the client's world anyway).
     */
    public static double clientBorderCenterX(double x) {
        double c = x - networkOriginX();
        return Math.abs(c) > 2.0E7 ? 0.0 : c;
    }

    public static double clientBorderCenterZ(double z) {
        double c = z - networkOriginZ();
        return Math.abs(c) > 2.0E7 ? 0.0 : c;
    }

    /** Sound positions are sent as int eighths of a block: {@link #wrapSoundCoord} keeps them right modulo 2^32. */
    public static int clientSoundX(int x8) {
        return x8 - (int) (networkOriginX() * 8);
    }

    public static int clientSoundZ(int z8) {
        return z8 - (int) (networkOriginZ() * 8);
    }

    /**
     * A sound coordinate before vanilla's {@code (int) (x * 8.0)}, which sticks at 2^31 - 1 past 2^28 blocks: moved
     * by a multiple of 2^29 into +-2^28, so eight times it wraps like the true value would (unchanged within 2^28).
     */
    public static double wrapSoundCoord(double v) {
        double period = 1L << 29;
        return v - Math.floor((v + period / 2) / period) * period;
    }

    public static int clientChunkX(int x) {
        long o = networkOriginX();
        return (int) (unnarrowNear(x, o, 4) - (o >> 4));
    }

    public static int clientChunkZ(int z) {
        long o = networkOriginZ();
        return (int) (unnarrowNear(z, o, 4) - (o >> 4));
    }

    /** X of a block position read from the wire (its 26-bit field wraps), moved next to the reference player. */
    public static int wireBlockX(long packed) {
        int[] ref = NETWORK_REFERENCE.get();
        int x = vanillaBlockX(packed);
        return ref == null ? x : unalias(x, ref[0], 26);
    }

    public static int wireBlockZ(long packed) {
        int[] ref = NETWORK_REFERENCE.get();
        int z = vanillaBlockZ(packed);
        return ref == null ? z : unalias(z, ref[1], 26);
    }

    /** X of a section position read from the wire (its 22-bit field wraps), moved next to the reference player. */
    public static int wireSectionX(long packed) {
        int[] ref = NETWORK_REFERENCE.get();
        int x = vanillaSectionX(packed);
        return ref == null ? x : unalias(x, ref[0] >> 4, 22);
    }

    public static int wireSectionZ(long packed) {
        int[] ref = NETWORK_REFERENCE.get();
        int z = vanillaSectionZ(packed);
        return ref == null ? z : unalias(z, ref[1] >> 4, 22);
    }

    /** The value congruent to {@code wrapped} modulo 2^bits that is nearest to {@code reference} (and fits an int). */
    public static int unalias(int wrapped, int reference, int bits) {
        long period = 1L << bits;
        long k = Math.floorDiv((long) reference - wrapped + period / 2, period);
        long v = wrapped + k * period;
        if (v > Integer.MAX_VALUE) {
            v -= period;
        } else if (v < Integer.MIN_VALUE) {
            v += period;
        }
        return (int) v;
    }

    // ---- Region table ----

    /**
     * Index of the 65,536-block square (rx, rz), added on first use; -1 when the table is full. Region coordinates are
     * 64-bit (3.5.2: positions past the int range, docs/ROADMAP_64BIT.md).
     */
    static int region(long rx, long rz) {
        int slot = slotOf(rx, rz);
        for (int probes = 0; probes < TABLE_SIZE; probes++) {
            int s = TABLE_SLOTS.get(slot);
            if (s == 0) {
                return addRegion(rx, rz);
            }
            if (REGION_X[s - 1] == rx && REGION_Z[s - 1] == rz) {
                return s - 1;
            }
            slot = slot + 1 & TABLE_SIZE - 1;
        }
        return -1; // every slot taken (cannot happen while the table is larger than MAX_REGIONS)
    }

    private static int slotOf(long rx, long rz) {
        return (int) splitMix64(rx * 0x9E3779B97F4A7C15L ^ rz) & TABLE_SIZE - 1;
    }

    private static int addRegion(long rx, long rz) {
        synchronized (LOCK) {
            int slot = slotOf(rx, rz);
            while (true) {
                int s = TABLE_SLOTS.get(slot);
                if (s == 0) {
                    break;
                }
                if (REGION_X[s - 1] == rx && REGION_Z[s - 1] == rz) {
                    return s - 1;
                }
                slot = slot + 1 & TABLE_SIZE - 1;
            }
            if (regionCount == MAX_REGIONS) {
                if (!warnedFull) {
                    warnedFull = true;
                    LOG.log(System.Logger.Level.ERROR, "All " + MAX_REGIONS
                        + " far-coordinate regions are in use; new far positions alias like vanilla until the server restarts.");
                }
                return -1;
            }
            int index = regionCount++;
            REGION_X[index] = rx;
            REGION_Z[index] = rz;
            TABLE_SLOTS.set(slot, index + 1); // volatile store: publishes the writes above
            if (regionCount == MAX_REGIONS * 3 / 4) {
                LOG.log(System.Logger.Level.WARNING, regionCount + " of " + MAX_REGIONS + " far-coordinate regions are in use (they are freed on restart).");
            }
            return index;
        }
    }
    /** SplitMix64 finalizer (Steele, Lea, Flood 2014): spreads the region keys over the table. */
    static long splitMix64(long z) {
        z = (z ^ z >>> 30) * 0xBF58476D1CE4E5B9L;
        z = (z ^ z >>> 27) * 0x94D049BB133111EBL;
        return z ^ z >>> 31;
    }

    public static int regionCount() {
        synchronized (LOCK) {
            return regionCount;
        }
    }

    /** Tests only: empties the region table. */
    static void resetRegions() {
        synchronized (LOCK) {
            for (int i = 0; i < TABLE_SIZE; i++) {
                TABLE_SLOTS.set(i, 0);
            }
            regionCount = 0;
            warnedFull = false;
        }
    }
}
