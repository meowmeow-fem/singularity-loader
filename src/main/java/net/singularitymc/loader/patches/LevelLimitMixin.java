package net.singularitymc.loader.patches;

import net.singularitymc.loader.util.PatchSettings;
import net.singularitymc.loader.util.ServerState;
import net.singularitymc.loader.util.WidePacking;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Stage 1 of the extended world size (see docs/COORDINATES.md): replaces the hard-coded {@code 30,000,000}-block limits
 * in {@code Level} by the {@code max_coordinate} setting. With the default (30,000,000) nothing changes.
 * Every hook is optional ({@code require = 0}).
 */
@Mixin(Level.class)
abstract class LevelLimitMixin {
    @ModifyConstant(method = "isInWorldBoundsHorizontal", constant = @Constant(intValue = 30000000), require = 0)
    private static int Singularity$isInWorldBoundsHorizontal0(int original) {
        return PatchSettings.limit();
    }

    @ModifyConstant(method = "isInWorldBoundsHorizontal", constant = @Constant(intValue = -30000000), require = 0)
    private static int Singularity$isInWorldBoundsHorizontal1(int original) {
        return -PatchSettings.limit();
    }

    @ModifyConstant(method = "getHeight", constant = @Constant(intValue = 30000000), require = 0)
    private int Singularity$getHeight0(int original) {
        return PatchSettings.limit();
    }

    @ModifyConstant(method = "getHeight", constant = @Constant(intValue = -30000000), require = 0)
    private int Singularity$getHeight1(int original) {
        return -PatchSettings.limit();
    }

    /**
     * Overrides the {@code LevelReader} default method, which has the same 30,000,000 check (an injector in an
     * interface mixin is avoided on purpose). Beyond the limit the default reports full brightness (15), which
     * would stop monsters from spawning there.
     */
    public int getMaxLocalRawBrightness(BlockPos pos, int skyDarken) {
        if (ServerState.longPositions()) {
            // 64-bit X/Z: the ints of getX() are narrowed (WidePacking.narrow), so the true ones come from the packing
            long limit = PatchSettings.limit64();
            long packed = pos.asLong();
            long x = WidePacking.blockX64(packed);
            long z = WidePacking.blockZ64(packed);
            if (x < -limit || z < -limit || x >= limit || z >= limit) {
                return 15;
            }
            return ((Level) (Object) this).getRawBrightness(pos, skyDarken);
        }
        int limit = PatchSettings.limit();
        int x = pos.getX();
        int z = pos.getZ();
        if (x < -limit || z < -limit || x >= limit || z >= limit) {
            return 15;
        }
        return ((Level) (Object) this).getRawBrightness(pos, skyDarken);
    }
}
