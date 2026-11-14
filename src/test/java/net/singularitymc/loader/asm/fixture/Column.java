package net.singularitymc.loader.asm.fixture;

/** Test fixture: a record (ObjectMethods bootstrap, record components). */
public record Column(int x, int z) {
    public int distance(Column o) {
        return Math.abs(x - o.x) + Math.abs(z - o.z);
    }
}
