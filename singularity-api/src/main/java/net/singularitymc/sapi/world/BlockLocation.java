package net.singularitymc.sapi.world;

/**
 * A block position with Singularity's 64-bit X and Z ({@code experimental_long_positions}: worlds reach far past
 * 2,147,483,647). Y stays an int, as in Minecraft. This is exact at every distance, unlike {@link Location}'s doubles.
 *
 * <p>Never squeeze X or Z into an {@code int}: past 2^31 that silently gives another place. This is the trap in
 * plain Minecraft code too: unwidened code (any mod or plugin) reading {@code BlockPos.getX()} gets only the low 32
 * bits; the API converts with the true values.
 */
public record BlockLocation(long x, int y, long z) {

    /** The block containing a point (floors, so -0.5 is block -1). */
    public static BlockLocation containing(double x, double y, double z) {
        return new BlockLocation((long) Math.floor(x), (int) Math.floor(y), (long) Math.floor(z));
    }

    public BlockLocation offset(long dx, int dy, long dz) {
        return new BlockLocation(Math.addExact(x, dx), y + dy, Math.addExact(z, dz));
    }

    public BlockLocation above() {
        return offset(0, 1, 0);
    }

    public BlockLocation below() {
        return offset(0, -1, 0);
    }

    /** The chunk this block is in. */
    public ChunkLocation chunk() {
        return ChunkLocation.ofBlock(x, z);
    }

    /** The centre of the block's bottom face, in {@code world}. */
    public Location toLocation(World world) {
        return new Location(world, x + 0.5, y, z + 0.5, 0f, 0f);
    }

    /**
     * Squared distance to another block, as a double (exact up to 2^53; two blocks 2^45 apart still give a correct
     * double). Overflow-free: the differences are computed as doubles.
     */
    public double distanceSquared(BlockLocation other) {
        double dx = (double) x - (double) other.x;
        double dy = (double) y - (double) other.y;
        double dz = (double) z - (double) other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    @Override
    public String toString() {
        return x + ", " + y + ", " + z;
    }
}
