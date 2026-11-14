package net.singularitymc.loader.asm.fixture;

/** Test fixture: assignments used as values (DUP_X1 over a widened value). */
public class Mutable {
    private int x;

    public int setX(int v) {
        return this.x = v;
    }

    public int addX(int d) {
        return x += d;
    }

    public int getX() {
        return x;
    }
}
