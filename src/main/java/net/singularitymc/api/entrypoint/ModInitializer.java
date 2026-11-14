package net.singularitymc.api.entrypoint;

/**
 * Entrypoint key {@code "main"}: common initialization, called on every environment.
 *
 * <p>Runs right before the game's {@code main} method, in dependency order. Game classes may be
 * loaded from here; registries have not been bootstrapped yet, so this is the place to register
 * things.
 */
@FunctionalInterface
public interface ModInitializer {
    void onInitialize();
}
