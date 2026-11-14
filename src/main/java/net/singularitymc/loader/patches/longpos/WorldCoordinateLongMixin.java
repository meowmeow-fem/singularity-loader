package net.singularitymc.loader.patches.longpos;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.commands.arguments.coordinates.WorldCoordinate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * experimental_long_positions, 3.5.4: absolute block coordinates in commands ({@code /setblock 3000000000 64 0},
 * {@code /forceload}, ...) are read as longs; vanilla reads an int and rejects anything past 2,147,483,647. The value
 * is kept as a double like vanilla does.
 */
@Mixin(WorldCoordinate.class)
abstract class WorldCoordinateLongMixin {
    @Shadow @Final public static SimpleCommandExceptionType ERROR_EXPECTED_INT;

    @Shadow
    public static boolean isRelative(StringReader reader) {
        throw new AssertionError();
    }

    /**
     * @author Singularity Loader
     * @reason {@code readLong} instead of {@code readInt}; otherwise as vanilla
     */
    @Overwrite
    public static WorldCoordinate parseInt(StringReader reader) throws CommandSyntaxException {
        if (reader.canRead() && reader.peek() == '^') {
            throw Vec3Argument.ERROR_MIXED_TYPE.createWithContext(reader);
        }
        if (!reader.canRead()) {
            throw ERROR_EXPECTED_INT.createWithContext(reader);
        }
        boolean relative = isRelative(reader);
        double value = reader.canRead() && reader.peek() != ' ' ? (relative ? reader.readDouble() : (double) reader.readLong()) : 0.0;
        return new WorldCoordinate(relative, value);
    }
}
