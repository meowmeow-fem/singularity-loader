package net.singularitymc.api.entrypoint;

/**
 * Entrypoint key {@code "preLaunch"}.
 *
 * <p>Runs after Mixin has been set up but <strong>before the game's main class is loaded</strong>.
 * Do not touch any {@code net.minecraft} class from here: loading one now would happen before
 * every mod has had its Mixins registered in a consistent order.
 */
@FunctionalInterface
public interface PreLaunchEntrypoint {
    void onPreLaunch();
}
