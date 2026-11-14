package net.singularitymc.loader.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.singularitymc.loader.util.FarLandsStages.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FarLandsStagesTest {
    private static final ClassicFarLands GEN = new ClassicFarLands(1234L);

    @AfterEach
    void forgetWindows() {
        VirtualWindows.reset();
    }

    @Test
    void stagesStartAtTheirCoordinates() {
        assertEquals(Stage.NORMAL, FarLandsStages.stage(12_550_820, 0));
        assertEquals(Stage.FAR_LANDS, FarLandsStages.stage(12_550_821, 0));
        assertEquals(Stage.FARTHER_LANDS, FarLandsStages.stage(0, -1_004_065_811));
        assertEquals(Stage.GRIDDED_LANDS, FarLandsStages.stage(8_589_934_592L, 0));
        assertEquals(Stage.FARTHER_LANDS, FarLandsStages.stage(8_589_934_591L, 0));
        assertEquals(Stage.FARTHERER_LANDS, FarLandsStages.stage(0, 53_905_378_846_980L));
        assertEquals(Stage.STRIPED_FARTHERER_LANDS, FarLandsStages.stage(-144_115_188_075_855_872L, 0));
        assertEquals(Stage.FINAL_FRINGELANDS, FarLandsStages.stage(9_223_372_036_854_000_000L, 0));
        assertEquals(Stage.FINAL_FRINGELANDS, FarLandsStages.stage(Long.MIN_VALUE, Long.MAX_VALUE));
        assertEquals(Stage.GRIDDED_LANDS, FarLandsStages.stage(12_550_821, 8_589_934_592L), "corners take the farther stage");
    }

    @Test
    void griddedLandsRepeatAsAMirroredGrid() {
        long x = 8_600_000_000L;
        int[][] a = column(x, 0);
        assertArrayEquals(a[0], column(x + 2 * 234, 0)[0], "repeats every 468 blocks (234 forwards, 234 mirrored)");
        long base = x - Math.floorMod(x, 468L);
        assertEquals(FarLandsStages.fold(base + 5), FarLandsStages.fold(base + 467 - 5), "the second half mirrors the first");
        assertTrue(java.util.Arrays.stream(a[0]).anyMatch(k -> k == ClassicFarLands.STONE), "has terrain");
    }

    @Test
    void farthererLandsAreFloatingSlabsWithoutWater() {
        int[] counts = count(FarLandsStages.FARTHERER + 4096, 0);
        assertEquals(0, counts[ClassicFarLands.WATER]);
        // Every 16-block layer is all stone or all air: rectangular slabs.
        int[][] col = column(FarLandsStages.FARTHERER + 4096, 0);
        for (int layer = 0; layer < 8; layer++) {
            for (int y = layer * 16; y < layer * 16 + 16; y++) {
                assertEquals(col[0][layer * 16], col[0][y], "layer " + layer);
            }
        }
    }

    @Test
    void stripedLandsHaveVoidStripes() {
        long stripe = FarLandsStages.STRIPED + 4096; // (x / 4096) odd: a void stripe
        assertEquals(16 * 16 * 128, count((stripe / 4096 & 1) == 1 ? stripe : stripe + 4096, 0)[ClassicFarLands.AIR]);
    }

    @Test
    void fringelandsAreVoid() {
        assertEquals(16 * 16 * 128, count(FarLandsStages.FRINGE, 0)[ClassicFarLands.AIR]);
        assertEquals(16 * 16 * 128, count(Long.MAX_VALUE - 15, Long.MIN_VALUE)[ClassicFarLands.AIR]);
    }

    @Test
    void windowsMapVirtualToRealAndBack() {
        int limit = 1_073_737_728;
        VirtualWindows.Window w = VirtualWindows.windowFor(9_000_000_123L, -77L, limit);
        assertNotNull(w);
        assertEquals(0, w.virtualX() % VirtualWindows.SIZE);
        assertTrue(w.realX() >= -limit && w.realX() + VirtualWindows.SIZE <= -2 * VirtualWindows.SIZE, "real square " + w.realX());
        int realX = (int) (w.realX() + (9_000_000_123L - w.virtualX()));
        int realZ = (int) (w.realZ() + (-77L - w.virtualZ()));
        assertEquals(w, VirtualWindows.atReal(realX, realZ));
        assertEquals(9_000_000_123L, w.virtualX(realX));
        assertEquals(-77L, w.virtualZ(realZ));
        assertEquals(w, VirtualWindows.windowFor(9_000_000_000L, -1L, limit), "same window for the same square");
        assertNull(VirtualWindows.atReal(0, 0));
    }

    @Test
    void theLastWindowEndsExactlyAtLongMax() {
        VirtualWindows.Window w = VirtualWindows.windowFor(Long.MAX_VALUE, Long.MIN_VALUE, 1_073_737_728);
        assertEquals(Long.MAX_VALUE, w.virtualX(w.realX() + VirtualWindows.SIZE - 1));
        assertEquals(Long.MIN_VALUE, w.virtualZ(w.realZ()));
    }

    @Test
    void smallWorldsHaveNoRoomForWindows() {
        assertNull(VirtualWindows.windowFor(9_000_000_000L, 0, 30_000_000));
    }

    private static int[][] column(long blockX, long blockZ) {
        int[][] out = new int[16][128];
        FarLandsStages.fill(GEN, blockX >> 4, blockZ >> 4, 63, (x, y, z, k) -> {
            if (z == 0) {
                out[x][y] = k;
            }
        });
        int lx = (int) (blockX & 15);
        return new int[][] {out[lx]};
    }

    private static int[] count(long blockX, long blockZ) {
        int[] counts = new int[3];
        FarLandsStages.fill(GEN, blockX >> 4, blockZ >> 4, 63, (x, y, z, k) -> counts[k]++);
        return counts;
    }
}
