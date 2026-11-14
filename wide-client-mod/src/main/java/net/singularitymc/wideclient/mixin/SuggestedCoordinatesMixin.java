package net.singularitymc.wideclient.mixin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.commands.SharedSuggestionProvider;
import net.singularitymc.wideclient.ClientOrigin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Coordinates suggested while typing a command (the block you look at) are the true ones on a server that shifts
 * this client's frame ({@link ClientOrigin}), since the server reads commands in true coordinates.
 */
@Mixin(ClientSuggestionProvider.class)
abstract class SuggestedCoordinatesMixin {
    @Inject(method = {"getRelevantCoordinates", "getAbsoluteCoordinates"}, at = @At("RETURN"), cancellable = true)
    private void SingularityClient$trueCoordinates(CallbackInfoReturnable<Collection<SharedSuggestionProvider.TextCoordinates>> cir) {
        long ox = ClientOrigin.x();
        long oz = ClientOrigin.z();
        if (ox == 0 && oz == 0) {
            return;
        }
        List<SharedSuggestionProvider.TextCoordinates> out = new ArrayList<>();
        for (SharedSuggestionProvider.TextCoordinates c : cir.getReturnValue()) {
            out.add(new SharedSuggestionProvider.TextCoordinates(ClientOrigin.trueCoordinate(c.x, ox), c.y, ClientOrigin.trueCoordinate(c.z, oz)));
        }
        cir.setReturnValue(out);
    }
}
