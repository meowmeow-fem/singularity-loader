package net.singularitymc.keys.mixin;

import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.singularitymc.keys.DashboardKeys;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every key the window receives passes through {@code KeyboardHandler.keyPress}; action 1 is "pressed". */
@Mixin(KeyboardHandler.class)
abstract class KeyboardHandlerMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    @Inject(method = "keyPress", at = @At("HEAD"))
    private void singularityKeys$pressed(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (action == 1) {
            DashboardKeys.pressed(minecraft, event.key());
        }
    }
}
