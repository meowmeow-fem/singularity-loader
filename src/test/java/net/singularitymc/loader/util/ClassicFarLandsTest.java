package net.singularitymc.loader.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ClassicFarLandsTest {
    private static final long SEED = 1234L;

    @Test
    void onlyChunksPastTheOnsetAreFarLands() {
        assertFalse(ClassicFarLands.isFarLandsChunk(0, 0));
        assertFalse(ClassicFarLands.isFarLandsChunk(784_425, 0)); // ends at block 12,550,815
        assertTrue(ClassicFarLands.isFarLandsChunk(784_426, 0)); // starts at block 12,550,816
        assertFalse(ClassicFarLands.isFarLandsChunk(-784_426, 0));
        assertTrue(ClassicFarLands.isFarLandsChunk(-784_427, 0));
        assertTrue(ClassicFarLands.isFarLandsChunk(5, 784_426));
        assertTrue(ClassicFarLands.isFarLandsChunk(5, -784_427));
    }

    @Test
    void sameSeedGivesTheSameTerrain() {
        assertArrayEquals(new ClassicFarLands(SEED).densityField(784_500, 3), new ClassicFarLands(SEED).densityField(784_500, 3));
    }

    @Test
    void theTerrainNoiseOverflowsOnlyPastTheOnset() {
        ClassicFarLands gen = new ClassicFarLands(SEED);
        double before = maxAbs(gen.densityField(784_400, 0)); // ~400 blocks before the edge: ordinary Beta terrain
        double after = maxAbs(gen.densityField(784_500, 0)); // ~1,200 blocks past it
        assertTrue(before < 1_000, "ordinary terrain density stays small: " + before);
        assertTrue(after > 1_000_000, "Far Lands density explodes: " + after);
    }

    @Test
    void farLandsHaveTheWallsAndGaps() {
        // A Far Lands chunk is not just a solid block or an ocean: columns mix stone with gaps of air and water,
        // and the stone reaches up towards Y 128.
        int[] counts = new int[3];
        int[] highestStone = {-1};
        new ClassicFarLands(SEED).fill(784_500, 0, 63, (x, y, z, kind) -> {
            counts[kind]++;
            if (kind == ClassicFarLands.STONE && y > highestStone[0]) {
                highestStone[0] = y;
            }
        });
        assertEquals(16 * 16 * 128, counts[0] + counts[1] + counts[2]);
        assertTrue(counts[ClassicFarLands.STONE] > 16 * 16 * 128 / 10, "enough stone: " + counts[ClassicFarLands.STONE]);
        assertTrue(counts[ClassicFarLands.AIR] + counts[ClassicFarLands.WATER] > 16 * 16 * 128 / 10, "enough gaps");
        assertTrue(highestStone[0] > 100, "walls reach high: " + highestStone[0]);
    }

    @Test
    void waterOnlyBelowSeaLevel() {
        new ClassicFarLands(SEED).fill(784_500, 7, 63, (x, y, z, kind) -> {
            if (kind == ClassicFarLands.WATER) {
                assertTrue(y < 63, "water at " + y);
            }
        });
    }

    private static double maxAbs(double[] values) {
        double m = 0;
        for (double v : values) {
            m = Math.max(m, Math.abs(v));
        }
        return m;
    }
}
