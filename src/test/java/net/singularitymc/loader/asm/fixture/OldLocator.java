package net.singularitymc.loader.asm.fixture;

/** Not widened: only has the int method, and does int math on what it gets. */
public class OldLocator implements Locator {
    @Override
    public int locate(int x) {
        return x + 1;
    }
}
