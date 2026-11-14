package net.singularitymc.sapi.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BlockLocationTest {

    @Test
    void positionsPastTheIntRangeStayExact() {
        long far = 35_000_000_000_000L;
        BlockLocation b = new BlockLocation(far, 64, -far);
        assertEquals(far + 1, b.offset(1, 0, 0).x());
        assertEquals(new ChunkLocation(far >> 4, -far >> 4), b.chunk());
        assertTrue(b.chunk().contains(b));
        // the seam at 2^31 is not special
        BlockLocation seam = new BlockLocation(Integer.MAX_VALUE, 0, 0).offset(1, 0, 0);
        assertEquals(2_147_483_648L, seam.x());
    }

    @Test
    void containingFloors() {
        assertEquals(new BlockLocation(-1, -1, 0), BlockLocation.containing(-0.5, -0.01, 0.99));
        assertEquals(new BlockLocation(3_518_437_208_883L, 70, 0), BlockLocation.containing(3_518_437_208_883.75, 70.2, 0.1));
    }

    @Test
    void chunks() {
        assertEquals(new ChunkLocation(-1, 0), ChunkLocation.ofBlock(-1, 15));
        ChunkLocation c = ChunkLocation.ofBlock(2_147_483_648L, 0);
        assertEquals(134_217_728L, c.x());
        assertEquals(2_147_483_648L, c.minBlockX());
        assertEquals(2_147_483_663L, c.maxBlockX());
    }

    @Test
    void distanceDoesNotOverflow() {
        BlockLocation a = new BlockLocation(-(1L << 45), 0, 0);
        BlockLocation b = new BlockLocation(1L << 45, 0, 0);
        assertEquals(Math.pow(2, 92), a.distanceSquared(b));
        assertThrows(ArithmeticException.class, () -> new BlockLocation(Long.MAX_VALUE, 0, 0).offset(1, 0, 0));
    }
}
