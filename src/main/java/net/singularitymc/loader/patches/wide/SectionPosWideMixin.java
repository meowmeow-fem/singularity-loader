package net.singularitymc.loader.patches.wide;

import java.util.function.Function;
import net.minecraft.core.SectionPos;
import net.singularitymc.loader.util.WidePacking;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Stage 2 of the extended world size (see docs/COORDINATES.md): lossless {@code SectionPos} packing. Only registered when
 * {@code max_coordinate} reaches past 2^25; inside +-2^25 blocks (and section Y -128..127) the values are the same as
 * vanilla. The network codec keeps the vanilla layout for vanilla clients.
 */
@Mixin(SectionPos.class)
abstract class SectionPosWideMixin {
    /**
     * @author Singularity Loader
     * @reason 22-bit X/Z fields alias beyond 2^25 blocks
     */
    @Overwrite
    public static long asLong(int x, int y, int z) {
        return WidePacking.sectionAsLong(x, y, z);
    }

    /**
     * @author Singularity Loader
     * @reason see {@link #asLong(int, int, int)}
     */
    @Overwrite
    public static int x(long packed) {
        return WidePacking.sectionX(packed);
    }

    /**
     * @author Singularity Loader
     * @reason see {@link #asLong(int, int, int)}
     */
    @Overwrite
    public static int y(long packed) {
        return WidePacking.sectionY(packed);
    }

    /**
     * @author Singularity Loader
     * @reason see {@link #asLong(int, int, int)}
     */
    @Overwrite
    public static int z(long packed) {
        return WidePacking.sectionZ(packed);
    }

    /**
     * @author Singularity Loader
     * @reason vanilla clears the Y field with {@code packed & -1048576}, which assumes the vanilla layout
     */
    @Overwrite
    public static long getZeroNode(long packed) {
        return WidePacking.sectionZeroNode(packed);
    }

    // STREAM_CODEC = ByteBufCodecs.LONG.map(SectionPos::of, SectionPos::asLong): decode is argument 0, encode argument 1.
    @ModifyArg(method = "<clinit>", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/codec/StreamCodec;map(Ljava/util/function/Function;Ljava/util/function/Function;)Lnet/minecraft/network/codec/StreamCodec;"),
        index = 0)
    private static Function<Long, SectionPos> Singularity$wireDecode(Function<Long, SectionPos> original) {
        return packed -> {
            if (WidePacking.hasNetworkOrigin()) { // experimental_long_positions: the client's shifted frame (3.5.6)
                return SectionPos.of(WidePacking.sectionAsLong(WidePacking.wireSectionX(packed) + (WidePacking.networkOriginX() >> 4),
                    WidePacking.vanillaSectionY(packed), WidePacking.wireSectionZ(packed) + (WidePacking.networkOriginZ() >> 4)));
            }
            return SectionPos.of(WidePacking.wireSectionX(packed), WidePacking.vanillaSectionY(packed), WidePacking.wireSectionZ(packed));
        };
    }

    @ModifyArg(method = "<clinit>", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/codec/StreamCodec;map(Ljava/util/function/Function;Ljava/util/function/Function;)Lnet/minecraft/network/codec/StreamCodec;"),
        index = 1)
    private static Function<SectionPos, Long> Singularity$wireEncode(Function<SectionPos, Long> original) {
        return pos -> WidePacking.vanillaSectionAsLong(WidePacking.clientChunkX(pos.x()), pos.y(), WidePacking.clientChunkZ(pos.z()));
    }
}
