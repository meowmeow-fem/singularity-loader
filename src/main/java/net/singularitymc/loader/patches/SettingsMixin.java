package net.singularitymc.loader.patches;

import java.nio.file.Path;
import java.util.Properties;
import net.minecraft.server.dedicated.Settings;
import net.singularitymc.loader.util.ServerState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Replaces {@code server.properties} by {@code ServerConfig.toml}: the game reads its settings from the values the
 * loader parsed, and never writes a {@code server.properties}.
 */
@Mixin(Settings.class)
public abstract class SettingsMixin {
    @Inject(method = "loadFromFile", at = @At("HEAD"), cancellable = true, require = 0)
    private static void singularity$fromToml(Path file, CallbackInfoReturnable<Properties> cir) {
        Properties p = ServerState.serverProperties();
        if (p != null) {
            cir.setReturnValue(p);
        }
    }

    @Inject(method = "store", at = @At("HEAD"), cancellable = true, require = 0)
    private void singularity$noPropertiesFile(Path file, CallbackInfo ci) {
        if (ServerState.serverProperties() != null) {
            ci.cancel();
        }
    }
}
