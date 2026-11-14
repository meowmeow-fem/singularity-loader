package net.singularitymc.loader.asm.fixture;

/** Test fixture: implements a JDK interface and overrides JDK methods (their descriptors must stay). */
public class Point implements Positioned, Comparable<Point> {
    private final int x;
    private final int z;

    public Point(int x, int z) {
        this.x = x;
        this.z = z;
    }

    @Override
    public int x() {
        return x;
    }

    @Override
    public int z() {
        return z;
    }

    public Point offset(int dx, int dz) {
        return new Point(x + dx, z + dz);
    }

    @Override
    public int compareTo(Point o) {
        return Integer.compare(x, o.x);
    }

    @Override
    public int hashCode() {
        return x * 31 + z;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Point p && p.x == x && p.z == z;
    }

    @Override
    public String toString() {
        return "(" + x + ", " + z + ")";
    }
}
