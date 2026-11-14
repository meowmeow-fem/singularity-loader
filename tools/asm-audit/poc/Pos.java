package poc;

/** Shaped like Minecraft's Vec3i/BlockPos: int X/Y/Z, offsets, chunk math. The widener makes X/Z long. */
public class Pos {
    private final int x;
    private final int y;
    private final int z;

    public Pos(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    public Pos offset(int dx, int dy, int dz) {
        return new Pos(x + dx, y + dy, z + dz);
    }

    public int chunkX() {
        return x >> 4;
    }

    public int chunkZ() {
        return z >> 4;
    }

    public int localX() {
        return x & 15;
    }

    public int distManhattan(Pos o) {
        return Math.abs(x - o.x) + Math.abs(z - o.z);
    }

    public boolean isFartherThan(int d) {
        return Math.max(Math.abs(x), Math.abs(z)) >= d;
    }

    @Override
    public String toString() {
        return "Pos{" + x + ", " + y + ", " + z + "}";
    }
}
