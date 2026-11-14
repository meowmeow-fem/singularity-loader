package net.singularitymc.loader.asm.fixture;

/** Test fixture: overrides x() and calls super. */
public class Moved extends Point {
    public Moved(int x, int z) {
        super(x, z);
    }

    @Override
    public int x() {
        return super.x() + 0;
    }

    public Moved shift(int dx) {
        return new Moved(x() + dx, z());
    }
}
