package net.singularitymc.sapi.login;

import java.util.concurrent.CompletableFuture;

/**
 * Decides, before a connecting client becomes a player, how it must prove who it is (since API 4.4). Set with
 * {@link net.singularitymc.sapi.Singularity#setLoginGate}; there is one gate per server.
 *
 * <p>{@link #check} is called on a network thread right after the client sent its name, so it must not block: do
 * slow work (a web request, a database) elsewhere and complete the future when done. The client waits meanwhile.
 * A future that fails, or completes with {@code null}, counts as {@link LoginDecision#deny}.
 */
@FunctionalInterface
public interface LoginGate {
    /**
     * @param name the name the client asks for (already a valid Minecraft name: 1 to 16 printable characters)
     * @param address the client's IP address as text
     */
    CompletableFuture<LoginDecision> check(String name, String address);
}
