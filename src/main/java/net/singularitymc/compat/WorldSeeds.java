package net.singularitymc.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * The world seed a {@link RandomState} was made from. 26.3 has {@code RandomState.seed()}; 26.1 and 26.2 only pass the
 * seed to its constructor, where {@code RandomStateSeedMixin} keeps it ({@link Carrier}).
 */
public final class WorldSeeds {
    private WorldSeeds() {}

    /** Implemented by {@code RandomState} through {@code RandomStateSeedMixin}. */
    public interface Carrier {
        long singularity$seed();
    }

    /** {@code RandomState.seed()} where the game has it (26.3+), otherwise null. */
    private static final MethodHandle SEED;

    static {
        MethodHandle h;
        try {
            h = MethodHandles.publicLookup().findVirtual(RandomState.class, "seed", MethodType.methodType(long.class));
        } catch (ReflectiveOperationException e) {
            h = null;
        }
        SEED = h;
    }

    public static long of(RandomState random) {
        if (SEED != null) {
            try {
                return (long) SEED.invokeExact(random);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }
        return ((Carrier) (Object) random).singularity$seed();
    }
}
