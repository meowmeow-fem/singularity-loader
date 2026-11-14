package net.singularitymc.example;

import net.singularitymc.api.SingularityLoader;
import net.singularitymc.api.entrypoint.ModInitializer;
import net.singularitymc.api.entrypoint.ServerModInitializer;

/**
 * The entrypoint class named in {@code singularity.mod.json}. It needs a public constructor
 * without parameters; the loader creates one instance and calls every interface it implements
 * that matches an entrypoint key.
 */
public final class ExampleMod implements ModInitializer, ServerModInitializer {
    public static final String MOD_ID = "examplemod";

    @Override
    public void onInitialize() {
        // Runs right before Minecraft's main(), on every environment.
        SingularityLoader loader = SingularityLoader.getInstance();
        log("Hello from Example Mod! Running on Minecraft " + loader.getGameVersion()
                + " with Singularity Loader " + loader.getLoaderVersion());
    }

    @Override
    public void onInitializeServer() {
        // Runs after every "main" entrypoint, on a dedicated server only.
        log("Dedicated server detected; " + SingularityLoader.getInstance().getAllMods().size() + " mods/components known.");
    }

    public static void log(String message) {
        System.out.println("[" + MOD_ID + "] " + message);
    }
}
