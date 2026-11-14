package net.singularitymc.wideclient.mixin;

import java.util.Locale;
import net.minecraft.client.gui.components.debug.DebugEntryPosition;
import net.singularitymc.wideclient.ClientOrigin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** F3 shows the true position on a server that shifts this client's frame ({@link ClientOrigin}). */
@Mixin(DebugEntryPosition.class)
abstract class DebugPositionMixin {
    @Redirect(method = "display", at = @At(value = "INVOKE",
        target = "Ljava/lang/String;format(Ljava/util/Locale;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;"))
    private String SingularityClient$truePosition(Locale locale, String format, Object[] args) {
        long ox = ClientOrigin.x();
        long oz = ClientOrigin.z();
        if (ox != 0 || oz != 0) {
            if (format.startsWith("XYZ:") && args.length >= 3) {
                args[0] = ((Number) args[0]).doubleValue() + ox;
                args[2] = ((Number) args[2]).doubleValue() + oz;
            } else if (format.startsWith("Block:") && args.length >= 3) {
                args[0] = ((Number) args[0]).longValue() + ox;
                args[2] = ((Number) args[2]).longValue() + oz;
            } else if (format.startsWith("Chunk:") && args.length >= 7) {
                args[0] = ((Number) args[0]).longValue() + (ox >> 4);
                args[2] = ((Number) args[2]).longValue() + (oz >> 4);
                args[5] = ((Number) args[5]).longValue() + (ox >> 9);
                args[6] = ((Number) args[6]).longValue() + (oz >> 9);
            }
        }
        return String.format(locale, format, args);
    }
}
