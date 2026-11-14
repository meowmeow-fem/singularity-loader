package net.singularitymc.api.entrypoint;

/** Entrypoint key {@code "server"}: like {@link ModInitializer}, but only on a dedicated server. Runs after all {@code main} entrypoints. */
@FunctionalInterface
public interface ServerModInitializer {
    void onInitializeServer();
}
