package net.singularitymc.loader.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WidePackingTest {
    private static final int[] EDGES = {
        Integer.MIN_VALUE, Integer.MIN_VALUE + 1, -(1 << 25) - 1, -(1 << 25), -(1 << 25) + 1, -65537, -65536, -1, 0, 1,
        65535, 65536, (1 << 25) - 1, 1 << 25, 30_000_000, 2_147_479_551, Integer.MAX_VALUE - 1, Integer.MAX_VALUE
    };

    @BeforeEach
    void emptyRegionTable() {
        WidePacking.resetRegions();
    }

    // ---- BlockPos ----

    @Test
    void blockPackingMatchesVanillaInsideVanillaReach() {
        SplittableRandom r = new SplittableRandom(1);
        for (int i = 0; i < 200_000; i++) {
            int x = r.nextInt(-(1 << 25), 1 << 25);
            int z = r.nextInt(-(1 << 25), 1 << 25);
            int y = r.nextInt(-2032, 2032);
            long packed = WidePacking.blockAsLong(x, y, z);
            assertEquals(WidePacking.vanillaBlockAsLong(x, y, z), packed);
            assertEquals(packed & -16L, WidePacking.blockFlatIndex(packed));
        }
        assertEquals(0, WidePacking.regionCount());
    }

    @Test
    void blockPackingRoundTripsEverywhere() {
        SplittableRandom r = new SplittableRandom(2);
        for (int i = 0; i < 200_000; i++) {
            int x = i < 1000 ? EDGES[i % EDGES.length] : r.nextInt();
            int z = i < 1000 ? EDGES[i / EDGES.length % EDGES.length] : r.nextInt();
            // Few regions per run: positions cluster around a handful of far places, like real players.
            if (i >= 1000) {
                x = (x & 0xFFFF) | (r.nextInt(64) << 20);
                z = (z & 0xFFFF) | (r.nextInt(64) << 20);
            }
            int y = r.nextInt(-2048, 2048);
            assertRoundTrip(x, y, z);
        }
    }

    @Test
    void blockPackingIsInjective() {
        SplittableRandom r = new SplittableRandom(3);
        Map<Long, long[]> seen = new HashMap<>();
        for (int i = 0; i < 100_000; i++) {
            int base = r.nextInt(4) * (1 << 26); // positions that alias each other in vanilla
            int x = base + r.nextInt(-40, 40);
            int z = r.nextInt(4) * (1 << 26) + r.nextInt(-40, 40);
            int y = r.nextInt(-2048, 2048);
            long[] previous = seen.putIfAbsent(WidePacking.blockAsLong(x, y, z), new long[] {x, y, z});
            if (previous != null) {
                assertEquals(previous[0], x);
                assertEquals(previous[1], y);
                assertEquals(previous[2], z);
            }
        }
    }

    @Test
    void blockPackingRoundTrips64BitPositions() {
        long[] xs = {0, 3_000_000_000L, -3_000_000_000L, 53_905_378_846_980L, 1L << 57, Long.MAX_VALUE, Long.MIN_VALUE, -1L << 40};
        for (long x : xs) {
            for (long z : new long[] {0, -7, x, -x, 8_589_934_592L}) {
                long packed = WidePacking.blockAsLong(x, 70, z);
                assertEquals(x, WidePacking.blockX64(packed), "x " + x + " z " + z);
                assertEquals(70, WidePacking.blockY(packed));
                assertEquals(z, WidePacking.blockZ64(packed), "x " + x + " z " + z);
            }
        }
        // the int API is the same packing
        assertEquals(WidePacking.blockAsLong(2_000_000_000, 64, -5), WidePacking.blockAsLong(2_000_000_000L, 64, -5L));
        assertEquals(WidePacking.vanillaBlockAsLong(100, 64, -100), WidePacking.blockAsLong(100L, 64, -100L));
    }

    @Test
    void flatIndexClearsLowYBitsOfFarPositions() {
        long packed = WidePacking.blockAsLong(2_000_000_000, 77, -2_000_000_000);
        long flat = WidePacking.blockFlatIndex(packed);
        assertEquals(2_000_000_000, WidePacking.blockX(flat));
        assertEquals(64, WidePacking.blockY(flat));
        assertEquals(-2_000_000_000, WidePacking.blockZ(flat));
    }

    @Test
    void fullRegionTableFallsBackWithoutEscapeCodes() {
        for (int i = 0; i < WidePacking.MAX_REGIONS; i++) {
            WidePacking.blockAsLong(1_000_000_000 + i * 65536, 0, 0);
        }
        assertEquals(WidePacking.MAX_REGIONS, WidePacking.regionCount());
        long packed = WidePacking.blockAsLong(-1_500_000_000, 2040, 5);
        assertEquals(WidePacking.vanillaBlockAsLong(-1_500_000_000, 2031, 5), packed);
        assertEquals(2031, WidePacking.blockY(packed));
        // Already-known regions keep working.
        assertRoundTrip(1_000_000_000 + 17, 3, 9);
    }

    private static void assertRoundTrip(int x, int y, int z) {
        long packed = WidePacking.blockAsLong(x, y, z);
        String at = x + " " + y + " " + z;
        assertEquals(x, WidePacking.blockX(packed), at);
        assertEquals(y, WidePacking.blockY(packed), at);
        assertEquals(z, WidePacking.blockZ(packed), at);
    }

    // ---- SectionPos ----

    @Test
    void sectionPackingMatchesVanillaInsideVanillaReach() {
        SplittableRandom r = new SplittableRandom(4);
        for (int i = 0; i < 200_000; i++) {
            int x = r.nextInt(-(1 << 21), 1 << 21);
            int z = r.nextInt(-(1 << 21), 1 << 21);
            int y = r.nextInt(-128, 128);
            long packed = WidePacking.sectionAsLong(x, y, z);
            assertEquals(WidePacking.vanillaSectionAsLong(x, y, z), packed);
            assertEquals(packed & -1048576L, WidePacking.sectionZeroNode(packed));
        }
    }

    @Test
    void sectionPackingRoundTripsOverTheWholeIntRange() {
        SplittableRandom r = new SplittableRandom(5);
        for (int i = 0; i < 400_000; i++) {
            int x = (i < 1000 ? EDGES[i % EDGES.length] : r.nextInt()) >> 4;
            int z = (i < 1000 ? EDGES[i / EDGES.length % EDGES.length] : r.nextInt()) >> 4;
            int y = r.nextInt(-128, 128);
            long packed = WidePacking.sectionAsLong(x, y, z);
            String at = x + " " + y + " " + z;
            assertEquals(x, WidePacking.sectionX(packed), at);
            assertEquals(y, WidePacking.sectionY(packed), at);
            assertEquals(z, WidePacking.sectionZ(packed), at);
            long zero = WidePacking.sectionZeroNode(packed);
            assertEquals(x, WidePacking.sectionX(zero), at);
            assertEquals(0, WidePacking.sectionY(zero), at);
            assertEquals(z, WidePacking.sectionZ(zero), at);
        }
    }

    @Test
    void sectionYIsClamped() {
        long packed = WidePacking.sectionAsLong(5, 4000, 7);
        assertEquals(127, WidePacking.sectionY(packed));
        assertEquals(-128, WidePacking.sectionY(WidePacking.sectionAsLong(100_000_000, -9000, 7)));
    }

    @Test
    void sectionKeysOfOneColumnStayInsideTheColumnAndSlabRanges() {
        int[][] columns = {{0, 0}, {-1, -1}, {2_000_000, -3}, {100_000_000, 100_000_000}, {-134_217_728, 134_217_727}};
        for (int[] c : columns) {
            long colStart = WidePacking.sectionColumnStart(c[0], c[1]);
            long colEnd = WidePacking.sectionColumnEnd(c[0], c[1]);
            long slabStart = WidePacking.sectionSlabStart(c[0]);
            long slabEnd = WidePacking.sectionSlabEnd(c[0]);
            for (int y = -128; y < 128; y++) {
                long key = WidePacking.sectionAsLong(c[0], y, c[1]);
                assertTrue(key >= colStart && key <= colEnd, "column " + c[0] + " " + c[1] + " y " + y);
                assertTrue(key >= slabStart && key <= slabEnd, "slab " + c[0] + " y " + y);
            }
        }
    }

    // ---- Network ----

    @Test
    void unaliasPicksTheValueNearestThePlayer() {
        int far = 1_500_000_123;
        int wrapped = WidePacking.vanillaBlockX(WidePacking.vanillaBlockAsLong(far, 0, 0));
        assertEquals(far, WidePacking.unalias(wrapped, 1_500_000_000, 26));
        assertEquals(-far, WidePacking.unalias(WidePacking.vanillaBlockX(WidePacking.vanillaBlockAsLong(-far, 0, 0)), -1_500_000_000, 26));
        assertEquals(123, WidePacking.unalias(123, 100, 26));
        assertEquals(Integer.MAX_VALUE, WidePacking.unalias(
            WidePacking.vanillaBlockX(WidePacking.vanillaBlockAsLong(Integer.MAX_VALUE, 0, 0)), Integer.MAX_VALUE, 26));
    }

    @Test
    void wirePositionsAreOnlyUnaliasedWhileAPlayerIsSet() {
        long wire = WidePacking.vanillaBlockAsLong(1_234_567_890, 64, -987_654_321);
        assertEquals(WidePacking.vanillaBlockX(wire), WidePacking.wireBlockX(wire));
        WidePacking.setNetworkReference(1_234_567_000, -987_654_000);
        try {
            assertEquals(1_234_567_890, WidePacking.wireBlockX(wire));
            assertEquals(-987_654_321, WidePacking.wireBlockZ(wire));
        } finally {
            WidePacking.clearNetworkReference();
        }
        assertEquals(WidePacking.vanillaBlockZ(wire), WidePacking.wireBlockZ(wire));
    }

    @Test
    void wireSectionsAreUnaliasedAroundThePlayer() {
        int sx = 1_000_000_000 >> 4, sz = -1_000_000_000 >> 4;
        long wire = WidePacking.vanillaSectionAsLong(sx, 5, sz);
        assertEquals(WidePacking.vanillaSectionX(wire), WidePacking.wireSectionX(wire));
        WidePacking.setNetworkReference(1_000_000_100, -1_000_000_100);
        try {
            assertEquals(sx, WidePacking.wireSectionX(wire));
            assertEquals(sz, WidePacking.wireSectionZ(wire));
            assertEquals(5, WidePacking.vanillaSectionY(wire));
        } finally {
            WidePacking.clearNetworkReference();
        }
    }

    // ---- 64-bit section and chunk keys (3.5.4) ----

    private static final long[] FAR = {
        0, 1, -1, 30_000_000, (1L << 27) * 16 - 1, 1L << 31, 3_000_000_000L, -3_000_000_000L, 1L << 35, 8_589_934_592L,
        53_905_378_846_980L, 1L << 52, -(1L << 52), Long.MAX_VALUE / 2
    };

    @Test
    void sectionKeysRoundTrip64Bit() {
        SplittableRandom r = new SplittableRandom(11);
        Map<Long, long[]> seen = new HashMap<>();
        for (int i = 0; i < 100_000; i++) {
            long bx = i < FAR.length * FAR.length ? FAR[i % FAR.length] : FAR[r.nextInt(FAR.length)] + r.nextInt(-100_000, 100_000);
            long bz = i < FAR.length * FAR.length ? FAR[i / FAR.length] : FAR[r.nextInt(FAR.length)] + r.nextInt(-100_000, 100_000);
            long x = bx >> 4;
            long z = bz >> 4;
            int y = r.nextInt(-127, 127);
            long key = WidePacking.sectionAsLong(x, y, z);
            assertEquals(x, WidePacking.sectionX64(key), "x of " + x + "," + y + "," + z);
            assertEquals(y, WidePacking.sectionY64(key));
            assertEquals(z, WidePacking.sectionZ64(key));
            assertEquals(WidePacking.sectionAsLong(x, 0, z), WidePacking.sectionZeroNode64(key));
            long[] prev = seen.put(key, new long[] {x, y, z});
            assertTrue(prev == null || prev[0] == x && prev[1] == y && prev[2] == z, "two sections share key " + key);
        }
    }

    @Test
    void sectionKeysOfTheIntRangeKeepTheIntLayout() {
        SplittableRandom r = new SplittableRandom(12);
        for (int i = 0; i < 100_000; i++) {
            int x = r.nextInt() >> 4;
            int z = r.nextInt() >> 4;
            int y = r.nextInt(-127, 127);
            assertEquals(WidePacking.sectionAsLong(x, y, z), WidePacking.sectionAsLong((long) x, y, (long) z));
        }
        assertEquals(0, WidePacking.regionCount());
        assertEquals(126, WidePacking.sectionY64(WidePacking.sectionAsLong(0L, 500, 0L)), "section Y is clamped to -127..126");
    }

    @Test
    void chunkKeysRoundTrip64Bit() {
        SplittableRandom r = new SplittableRandom(13);
        Map<Long, long[]> seen = new HashMap<>();
        for (int i = 0; i < 100_000; i++) {
            long x = (i < FAR.length * FAR.length ? FAR[i % FAR.length] : FAR[r.nextInt(FAR.length)] + r.nextInt(-100_000, 100_000)) >> 4;
            long z = (i < FAR.length * FAR.length ? FAR[i / FAR.length] : FAR[r.nextInt(FAR.length)] + r.nextInt(-100_000, 100_000)) >> 4;
            long key = WidePacking.chunkPack(x, z);
            assertEquals(x, WidePacking.chunkX64(key), "x of chunk " + x + "," + z);
            assertEquals(z, WidePacking.chunkZ64(key));
            long[] prev = seen.put(key, new long[] {x, z});
            assertTrue(prev == null || prev[0] == x && prev[1] == z, "two chunks share key " + key);
        }
    }

    @Test
    void chunkKeysMatchVanillaWithin2To29Chunks() {
        SplittableRandom r = new SplittableRandom(14);
        for (int i = 0; i < 100_000; i++) {
            int x = r.nextInt(-(1 << 29), 1 << 29);
            int z = r.nextInt(-(1 << 29), 1 << 29);
            long vanilla = (long) x & 0xFFFFFFFFL | ((long) z & 0xFFFFFFFFL) << 32;
            assertEquals(vanilla, WidePacking.chunkPack((long) x, (long) z));
            assertEquals(vanilla, WidePacking.chunkPack(x, z));
            assertEquals(x, WidePacking.chunkX64(vanilla));
            assertEquals(z, WidePacking.chunkZ64(vanilla));
        }
        assertEquals(0, WidePacking.regionCount());
        assertEquals(-1, WidePacking.chunkX64(Long.MAX_VALUE), "sentinels decode like vanilla");
    }

    @Test
    void carryRestoresTruncatedCoordinates() {
        WidePacking.resetCarry();
        assertEquals(5, WidePacking.carryIn(5));
        assertEquals(-1_294_967_296L, WidePacking.carryIn((int) 3_000_000_000L), "nothing narrowed yet: plain int");
        long x = 3_000_000_000L;
        long z = -7_000_000_123L;
        int ix = WidePacking.carryOut(x);
        int iz = WidePacking.carryOut(z);
        assertEquals((int) x, ix);
        assertEquals(x + 17, WidePacking.carryIn(ix + 17), "int code added an offset");
        assertEquals(z - 300_000, WidePacking.carryIn(iz - 300_000));
        assertEquals((x >> 4), WidePacking.carryIn((int) (x >> 4)), "chunk coordinates fit an int and stay");
        assertEquals(64, WidePacking.carryIn(64));
        assertEquals(-40_000, WidePacking.carryIn(-40_000), "a value near 0 stays near 0");
        assertEquals(123, WidePacking.carryOut(123L));
        WidePacking.carryOut(-3_000_000_000L);
        assertEquals(2_000_000_003L, WidePacking.carryIn(2_000_000_003), "a real int position far from every carried one stays");
        long b = 2_147_500_000L;
        int truncated = WidePacking.carryOut(b);
        assertEquals(b >> 2, WidePacking.carryIn(truncated >> 2), "int code took the quart (biome) coordinate of a narrowed block");
        assertEquals((b >> 4) + 1, WidePacking.carryIn((truncated >> 4) + 1), "and the chunk next to it");
        long c = 10_000_000_000L;
        int tc = WidePacking.carryOut(c);
        assertEquals(c >> 4, WidePacking.carryIn(tc >> 4), "chunk of a block past 2^33");
        assertEquals(c - 9, WidePacking.carryIn(tc - 9));
        assertEquals(c + 3, WidePacking.floorCarry(tc + 3.5), "int code turned the narrowed coordinate into a double");
        assertEquals(3_000_000_000L, WidePacking.floorCarry(3_000_000_000.25), "a real far double stays");
        assertEquals(-2, WidePacking.floorCarry(-1.5));
        assertEquals(-1, WidePacking.ceilCarry(-1.5));
        assertEquals(c + 3.5, WidePacking.carryInDouble(tc + 3.5));
        WidePacking.resetCarry();
        int edge = WidePacking.carryOut(2_147_483_646L); // fits, but int code is about to add to it
        assertEquals(2_147_483_648L, WidePacking.carryIn(edge + 2), "floor(x) + 2 at the int seam");
        assertEquals(2_147_483_645L, WidePacking.carryIn(edge - 1));
        assertEquals(Integer.MAX_VALUE - 1, WidePacking.carryOut(2_147_483_647L), "never the int extreme: `x <= max; x++` would not end");
        assertEquals(Integer.MIN_VALUE + 1, WidePacking.carryOut(-2_147_483_648L));
        assertEquals(Integer.MIN_VALUE + 1, WidePacking.carryOut(6_408_896_512L), "also where a far stretch starts");
        WidePacking.resetCarry();
    }

    @Test
    void chunkCoordinatesPast2To35AreCarriedAtChunkScale() {
        WidePacking.resetCarry();
        long block = 0x33333333333L; // 3,518,437,208,883: its chunk has the same low 32 bits
        long chunk = block >> 4;
        WidePacking.carryOut(block);
        int ic = WidePacking.carryOutChunk(chunk);
        assertEquals(WidePacking.narrowChunk(chunk), ic);
        assertEquals(chunk, WidePacking.carryInChunk(ic));
        assertEquals(chunk - 3, WidePacking.carryInChunk(ic - 3), "a neighbour chunk");
        assertEquals(chunk + 1, WidePacking.carryInChunk((WidePacking.narrow(block) >> 4) + 1), "int code's narrowed block >> 4");
        assertEquals(block + 5, WidePacking.carryIn(WidePacking.narrow(block) + 5), "block coordinates still carry");
        WidePacking.carryOut(chunk); // widened code handing the chunk to int code through a plain carry
        assertEquals(block + 31, WidePacking.carryIn(WidePacking.narrow(block) + 31), "the chunk is not taken for a block");
        assertEquals(chunk + 2, WidePacking.carryInChunk(ic + 2));
        WidePacking.resetCarry();
        long corner = 0x33333333320L; // the mineshaft box seen at 3.5e12: + 15 is also 3 chunks before corner >> 4
        WidePacking.carryOut(corner);
        assertEquals(corner + 15, WidePacking.carryIn(WidePacking.narrow(corner) + 15), "15 blocks on, not 3 chunks back (block distances)");
        WidePacking.resetCarry();
        WidePacking.carryOut(chunk); // the chunk first, then its block
        WidePacking.carryOut(block);
        assertEquals(block - 7, WidePacking.carryIn(WidePacking.narrow(block) - 7));
        WidePacking.resetCarry();
        long far = 0x512345678ABCL;
        int onlyChunk = WidePacking.carryOutChunk(far >> 4); // only the chunk was narrowed
        assertEquals((far >> 4) + 2, WidePacking.carryInChunk(onlyChunk + 2));
        assertEquals(far, WidePacking.carryIn(WidePacking.narrow(far)), "and its blocks come back too");
        WidePacking.resetCarry();
    }

    @Test
    void transitHandsArgumentsOverByPosition() {
        WidePacking.resetCarry();
        long block = 22_906_492_245L; // block, quart and chunk coordinates have the same low 32 bits here
        long quart = block >> 2;
        long chunk = block >> 4;
        WidePacking.transitBegin();
        int a = WidePacking.carryOutTransit(quart);
        int b = WidePacking.transitInt(64);
        int c = WidePacking.carryOutTransit(chunk);
        assertEquals(quart, WidePacking.carryInTransitAt(a, 0), "each argument at its own scale");
        assertEquals(64, WidePacking.carryInTransitAt(b, 1));
        assertEquals(chunk, WidePacking.carryInTransitChunkAt(c, 2));
        assertEquals(quart + 1, WidePacking.carryInReturn(a + 1), "a result computed from the arguments");
        WidePacking.transitBegin();
        assertEquals(-1_431_655_766L + 5, WidePacking.carryInTransitAt((int) -1_431_655_761L, 0), "int code called the lambda: carried");
        WidePacking.resetCarry();
    }

    @Test
    void aTransitFrameIsOnlyGoodForItsOwnCall() {
        WidePacking.resetCarry();
        long far = 1L << 47; // low 32 bits 0, like a local x of 0
        long[] call = WidePacking.transitFrameForCall();
        call[0] = far;
        call[WidePacking.TRANSIT_SLOTS] = 1;
        long[] taken = WidePacking.transitFrame();
        int seen = WidePacking.narrow(far);
        assertEquals(far, WidePacking.transitTake(taken, seen, 0), "the lambda the call reached takes it");
        assertEquals(far, WidePacking.transitTake(WidePacking.transitFrame(), seen, 0), "and a callback it hands the ints on to");

        WidePacking.transitEnd(call); // the call returned (also when an implementor outside the set never took it)
        assertEquals(0, WidePacking.transitTake(WidePacking.transitFrame(), 0, 0), "int code calling a lambda later: its local 0");
        WidePacking.resetCarry();
    }

    @Test
    void blockAndQuartCarriesKeepTheirScale() {
        WidePacking.resetCarry();
        long block = 22_906_492_245L;
        WidePacking.carryOut(block);
        int east = WidePacking.narrow(block) + 1; // also reads as the chunk next to block >> 4, and the quart next to block >> 2
        assertEquals(block + 1, WidePacking.carryInBlock(east));
        assertEquals((block >> 2) + 3, WidePacking.carryInQuart(WidePacking.narrowQuart(block >> 2) + 3));
        WidePacking.carryOutQuart(block >> 2);
        assertEquals(block - 9, WidePacking.carryInBlock(WidePacking.narrow(block) - 9), "a quart is remembered at block scale");
        WidePacking.resetCarry();
    }

    @Test
    void farCoordinatesNeverNarrowToSmallInts() {
        long[] far = {1L << 32, (1L << 32) + 5, -(1L << 32), 1L << 47, (1L << 47) - 3, 1L << 50, -(1L << 50),
            (1L << 52) - 1, -(1L << 52), 5L << 32, 4_278_190_080L, -4_278_190_081L, 0x33333333333L, 22_906_492_245L};
        for (long v : far) {
            assertTrue(Math.abs((long) WidePacking.narrow(v)) >= 1 << 24, v + " -> " + WidePacking.narrow(v));
            assertTrue(Math.abs((long) WidePacking.narrowQuart(v >> 2)) >= 1 << 22, "quart of " + v);
            assertTrue(Math.abs((long) WidePacking.narrowChunk(v >> 4)) >= 1 << 20, "chunk of " + v);
            assertTrue(Math.abs((long) (WidePacking.narrow(v) >> 4)) >= 1 << 20, "int code's chunk of " + v);
        }
        // the first stretch on each side is the low 32 bits, as before 3.5.8: saves there stay as they are
        long[] near = {2_147_483_648L, 3_000_000_000L, 4_278_190_079L, -2_147_483_649L, -3_000_000_000L, -4_278_190_080L, 1_000, -5};
        for (long v : near) {
            assertEquals((int) v, WidePacking.narrow(v), "block " + v);
            assertEquals((int) (v >> 4), WidePacking.narrowChunk(v >> 4), "chunk of " + v);
            assertEquals((int) (v >> 2), WidePacking.narrowQuart(v >> 2), "quart of " + v);
        }
    }

    @Test
    void theSeamAtMultiplesOf2To32Carries() {
        long[] bases = {1L << 32, 1L << 33, 1L << 47, 1L << 50, -(1L << 47), (1L << 52) - (1L << 32), 3L << 32};
        for (long base : bases) {
            WidePacking.resetCarry();
            long v = base + 3;
            int n = WidePacking.carryOut(v);
            assertEquals(v + 10, WidePacking.carryIn(n + 10), "east of " + base);
            assertEquals(v - 23, WidePacking.carryIn(n - 23), "across the multiple of 2^32 at " + base);
            assertEquals((v >> 4) + 1, WidePacking.carryInChunk((n >> 4) + 1), "int code's chunk at " + base);
            assertEquals(v >> 4, WidePacking.carryInChunk(WidePacking.carryOutChunk(v >> 4)), "a chunk narrowed directly at " + base);
            assertEquals((v >> 2) - 1, WidePacking.carryInQuart((n >> 2) - 1), "a quart at " + base);
            assertEquals(64, WidePacking.carryIn(64), "a height stays a height at " + base);
            assertEquals(-5, WidePacking.carryIn(-5), "an offset stays an offset at " + base);
            assertEquals(0, WidePacking.carryInBlock(0), "a local 0 stays 0 at " + base);
        }
        WidePacking.resetCarry();
    }

    @Test
    void stretchEndsCarryBothWays() {
        long end = 4_278_190_080L; // the first positive stretch ends here: int code sees it shifted from here on
        WidePacking.resetCarry();
        int before = WidePacking.carryOut(end - 10);
        assertEquals(end + 5, WidePacking.carryIn(before + 15), "int code adding past the end");
        assertEquals(end + 5, WidePacking.carryIn(WidePacking.narrow(end + 5)), "narrowed fresh on the other side");
        WidePacking.carryOut(end + 5); // replaces the remembered one (within 2^24)
        assertEquals(end - 10, WidePacking.carryIn(WidePacking.narrow(end - 10)), "and back");
        assertEquals((end >> 4) - 1, WidePacking.carryInChunk(WidePacking.narrowChunk((end >> 4) - 1)), "chunks on both sides");
        assertEquals((end >> 4) + 1, WidePacking.carryInChunk(WidePacking.narrowChunk((end >> 4) + 1)));
        assertTrue(WidePacking.narrow(end) < WidePacking.narrow(end - 1), "the next stretch starts lower: int loops across run empty");
        long negEnd = -4_278_190_081L; // the first negative stretch ends below here
        WidePacking.resetCarry();
        WidePacking.carryOut(negEnd + 7);
        assertEquals(negEnd - 3, WidePacking.carryIn(WidePacking.narrow(negEnd - 3)));
        assertEquals(negEnd - 3, WidePacking.carryIn(WidePacking.narrow(negEnd + 7) - 10));
        WidePacking.resetCarry();
    }

    @Test
    void theNetworkOriginReadsNarrowedInts() {
        long origin = 1L << 47;
        WidePacking.setNetworkOrigin(new long[] {origin, -origin});
        try {
            assertEquals(100, WidePacking.clientBlockX(WidePacking.narrow(origin + 100)));
            assertEquals(-200, WidePacking.clientBlockZ(WidePacking.narrow(-origin - 200)));
            assertEquals(3, WidePacking.clientChunkX(WidePacking.narrowChunk((origin >> 4) + 3)), "a chunk narrowed directly");
            assertEquals(3, WidePacking.clientChunkX(WidePacking.narrow(origin + 48) >> 4), "int code's chunk of a block");
        } finally {
            WidePacking.clearNetworkOrigin();
        }
        assertEquals(1234, WidePacking.clientBlockX(1234), "no origin: as it is");
    }

    @Test
    void splitMix64MatchesReferenceValue() {
        // First output of the reference SplitMix64 generator seeded with 0 (state += golden gamma, then finalize).
        assertEquals(0xE220A8397B1DCDAFL, WidePacking.splitMix64(0x9E3779B97F4A7C15L));
    }
}
