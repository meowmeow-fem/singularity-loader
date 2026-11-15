package net.singularitymc.sapi;

import net.singularitymc.api.version.Version;
import net.singularitymc.sapi.internal.ApiRuntime;
import net.singularitymc.sapi.permission.Permissions;
import net.singularitymc.sapi.plugin.PluginManager;
import net.singularitymc.sapi.server.Server;

/**
 * Entry point of the Singularity API, the same for mods and plugins.
 *
 * <pre>{@code
 * // a mod (in its "server" entrypoint)
 * ApiContext api = Singularity.forMod("examplemod");
 * api.events().listen(PlayerJoinEvent.class, e -> e.player().sendMessage("Welcome!"));
 *
 * // a plugin gets its context from JavaPlugin: context(), events(), commands(), scheduler(), config()
 * }</pre>
 *
 * <p>Nothing here has Minecraft types: plugins compile against {@code singularity-api-<version>-api.jar} and the
 * loader jar, without Minecraft or Mixin. Coordinates are 64-bit ({@link net.singularitymc.sapi.world.BlockLocation}).
 */
public final class Singularity {
    /** Mod id of the Singularity API mod (for {@code "depends"} in singularity.mod.json). */
    public static final String MOD_ID = "singularity-api";

    private Singularity() {}

    /** Version of this API (what {@code api = "..."} in plugin.toml is checked against). */
    public static Version apiVersion() {
        return ApiRuntime.apiVersion();
    }

    /** Whether the server has started (worlds are loaded) and not begun stopping. */
    public static boolean isServerRunning() {
        return ApiRuntime.get().isServerRunning();
    }

    /**
     * The running server.
     *
     * @throws IllegalStateException before the server has started (in {@code onLoad} or a mod's entrypoint); use
     *     {@code ServerStartedEvent} or a plugin's {@code onEnable}
     */
    public static Server server() {
        return ApiRuntime.get().server();
    }

    /**
     * The API context of a mod: its own events, commands, scheduler tasks, config ({@code config/<modId>/config.toml})
     * and logger. Calling it again with the same id returns the same context.
     */
    public static ApiContext forMod(String modId) {
        return ApiRuntime.get().modContext(modId);
    }

    /** The loaded plugins. */
    public static PluginManager plugins() {
        return ApiRuntime.get().plugins();
    }

    /**
     * Sets the server's login gate (since API 4.4), or removes it with {@code null}. With a gate, every client
     * that connects is held until the gate has decided how it must prove who it is; see
     * {@link net.singularitymc.sapi.login.LoginGate}. A plugin that sets one should remove it in {@code onDisable}.
     */
    public static void setLoginGate(net.singularitymc.sapi.login.LoginGate gate) {
        net.singularitymc.sapi.internal.Logins.setGate(gate);
    }

    /** Permission defaults and the permission provider. */
    public static Permissions permissions() {
        return ApiRuntime.get().permissions();
    }
}
