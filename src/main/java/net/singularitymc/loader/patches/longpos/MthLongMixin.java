package net.singularitymc.loader.patches.longpos;

import net.minecraft.util.Mth;
import net.singularitymc.loader.util.WidePacking;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * experimental_long_positions, 3.5.4: {@code floor} / {@code ceil} of a double past the int range wrap (and the value
 * is carried, see {@link WidePacking#carryOut}) instead of sticking at {@code Integer.MAX_VALUE}, so int code that
 * floors an entity's position at 3 billion hands the right block back to widened code. Inside the int range the
 * result is the same as vanilla. Widened code calls {@code lfloor} / {@code ceilLong} directly.
 */
@Mixin(Mth.class)
abstract class MthLongMixin {
    @Shadow
    public static long lfloor(double value) {
        throw new AssertionError();
    }

    @Shadow
    public static long ceilLong(double value) {
        throw new AssertionError();
    }

    /**
     * @author Singularity Loader
     * @reason wrap and carry instead of saturating past the int range
     */
    @Overwrite
    public static int floor(double value) {
        return WidePacking.carryOut(lfloor(value));
    }

    /**
     * @author Singularity Loader
     * @reason see {@link #floor(double)}
     */
    @Overwrite
    public static int ceil(double value) {
        return WidePacking.carryOut(ceilLong(value));
    }
}
