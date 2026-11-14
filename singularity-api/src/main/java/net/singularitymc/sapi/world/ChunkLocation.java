package net.singularitymc.sapi.world;

/** A chunk (16 x 16 blocks) column. 64-bit like {@link BlockLocation}: chunk X/Z pass the int range past 2^35 blocks. */
public record ChunkLocation(long x, long z) {

    public static ChunkLocation ofBlock(long blockX, long blockZ) {
        return new ChunkLocation(blockX >> 4, blockZ >> 4);
    }

    public long minBlockX() {
        return x << 4;
    }

    public long minBlockZ() {
        return z << 4;
    }

    public long maxBlockX() {
        return (x << 4) + 15;
    }

    public long maxBlockZ() {
        return (z << 4) + 15;
    }

    public boolean contains(BlockLocation block) {
        return block.x() >> 4 == x && block.z() >> 4 == z;
    }

    @Override
    public String toString() {
        return x + ", " + z;
    }
}
