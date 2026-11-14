package net.singularitymc.loader.asm.fixture;

public class Spot implements Locator {
    public int x;

    public Spot(int x) {
        this.x = x;
    }

    @Override
    public int locate(int dx) {
        return x + dx;
    }

    /** Asks any locator about this spot's x. */
    public int through(Locator l) {
        return l.locate(x);
    }
}
