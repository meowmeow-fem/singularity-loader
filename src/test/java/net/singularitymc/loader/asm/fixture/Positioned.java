package net.singularitymc.loader.asm.fixture;

/** Test fixture for CoordinateWidenerTest: an interface with a default method. */
public interface Positioned {
    int x();

    int z();

    default int chunkX() {
        return x() >> 4;
    }
}
