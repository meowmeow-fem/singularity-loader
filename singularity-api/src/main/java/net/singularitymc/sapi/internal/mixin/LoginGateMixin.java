package net.singularitymc.sapi.internal.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import net.minecraft.util.StringUtil;
import net.singularitymc.sapi.internal.mc.LoginHooks;
import net.singularitymc.sapi.login.LoginDecision;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The login gate ({@code sapi.login.LoginGate}). When a client has sent its name, the gate is asked; the client's
 * "hello" is put aside meanwhile and handled again once the answer is there. "Mojang" makes this one login behave
 * as on an online-mode server: {@code handleHello} is told the server uses authentication, so the game sends the
 * encryption request and checks the client with the session servers, all with its own code.
 */
@Mixin(ServerLoginPacketListenerImpl.class)
abstract class LoginGateMixin {
    @Shadow
    @Final
    Connection connection;

    @Shadow
    public abstract void handleHello(ServerboundHelloPacket packet);

    @Shadow
    public abstract void disconnect(Component reason);

    /** 0: not asked, 1: waiting for the gate, 2: offline, 3: must pass Mojang. */
    @Unique
    private volatile int sapi$gate;

    @Inject(method = "handleHello", at = @At("HEAD"), cancellable = true)
    private void sapi$ask(ServerboundHelloPacket packet, CallbackInfo ci) {
        if (sapi$gate >= 2 || !LoginHooks.gateActive(connection)) {
            return;
        }
        ci.cancel();
        if (sapi$gate == 1) {
            return; // a second hello while the first is being decided
        }
        if (!StringUtil.isValidPlayerName(packet.name())) {
            disconnect(Component.literal("Invalid name."));
            return;
        }
        sapi$gate = 1;
        LoginHooks.ask(packet.name(), connection).thenAccept(decision -> {
            if (!connection.isConnected()) {
                return;
            }
            try {
                if (decision.kind() == LoginDecision.Kind.DENY) {
                    disconnect(Component.literal(decision.message()));
                    return;
                }
                sapi$gate = decision.kind() == LoginDecision.Kind.MOJANG ? 3 : 2;
                handleHello(packet);
            } catch (RuntimeException e) {
                disconnect(Component.literal("Login failed. Please try again."));
            }
        });
    }

    @Redirect(method = "handleHello", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;usesAuthentication()Z"))
    private boolean sapi$authenticate(MinecraftServer server) {
        return server.usesAuthentication() || sapi$gate == 3;
    }

    /** Reached with a Mojang decision only after the session servers confirmed the client. */
    @Inject(method = "startClientVerification", at = @At("HEAD"))
    private void sapi$verified(GameProfile profile, CallbackInfo ci) {
        if (sapi$gate == 3) {
            LoginHooks.verified(connection);
        }
    }
}
