package net.singularitymc.sapi.internal.mc;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.singularitymc.api.SingularityLoader;
import net.singularitymc.api.entrypoint.ServerModInitializer;
import net.singularitymc.sapi.Singularity;
import net.singularitymc.sapi.internal.ApiRuntime;
import net.singularitymc.sapi.internal.BuiltinCommands;

/**
 * The API mod's entrypoint ({@code "server"}, after every mod's {@code main}): starts the API and loads the plugins
 * ({@code onLoad}). They are enabled when the server has started (see {@link ServerHooks}).
 */
public final class SingularityApiMod implements ServerModInitializer {
    /** Extra plugin jars or folders for development, separated like a class path ({@code ;} on Windows). */
    public static final String ADD_PLUGINS = "singularity.addPlugins";

    @Override
    public void onInitializeServer() {
        SingularityLoader loader = SingularityLoader.getInstance();
        Path gameDir = loader.getGameDirectory();
        List<Path> extra = new ArrayList<>();
        String add = System.getProperty(ADD_PLUGINS, "");
        for (String p : add.split(File.pathSeparator)) {
            if (!p.isBlank()) {
                extra.add(Path.of(p.trim()).toAbsolutePath());
            }
        }
        McBackend backend = new McBackend();
        ApiRuntime runtime = new ApiRuntime(backend, gameDir, gameDir.resolve("SingularityPlugins"), extra,
                SingularityApiMod.class.getClassLoader());
        ApiRuntime.install(runtime);
        ServerHooks.init(runtime, backend);
        EventBridge.install();
        BrigadierBridge.install(runtime, backend);
        BuiltinCommands.register(runtime, runtime.modContext(Singularity.MOD_ID));
        runtime.log().info("Singularity API {} ready", ApiRuntime.apiVersion());
        runtime.pluginManager().loadAll();
    }
}
