package net.singularitymc.loader.mixin;

import java.lang.reflect.Method;
import java.util.List;
import net.singularitymc.api.Environment;
import net.singularitymc.loader.LoaderImpl;
import net.singularitymc.loader.launch.LaunchException;
import net.singularitymc.loader.mod.MixinConfigRef;
import net.singularitymc.loader.mod.ModContainerImpl;
import net.singularitymc.loader.util.Log;
import net.singularitymc.loader.util.ServerState;
import net.singularitymc.loader.util.WidePacking;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

/** Starts Mixin and registers the Mixin configs of the loader and of every mod. */
public final class MixinBootstrapper {
    /** Mixin config shipped inside the loader (server branding). */
    public static final String BUILTIN_CONFIG = "singularity-loader.mixins.json";
    /** Lossless position packing for worlds past 2^25 blocks (docs/COORDINATES.md, stage 2). */
    public static final String WIDE_CONFIG = "singularity-loader.wide.mixins.json";
    /** experimental_long_positions only (3.5.4): 64-bit chunk keys, wrapping floor/ceil, no saving past 2^31. */
    public static final String LONG_CONFIG = "singularity-loader.long.mixins.json";

    private static final Log LOG = Log.get("Singularity");

    private MixinBootstrapper() {}

    /**
     * Initialises Mixin. After this call every class defined by the game class loader can be
     * transformed through the returned transformer.
     *
     * @param mods the mods in load order
     */
    public static IMixinTransformer start(LoaderImpl loader, List<ModContainerImpl> mods) throws LaunchException {
        try {
            MixinBootstrap.init();
            startMixinExtras();

            MixinEnvironment env = MixinEnvironment.getDefaultEnvironment();
            env.setSide(loader.getEnvironment() == Environment.SERVER ? MixinEnvironment.Side.SERVER : MixinEnvironment.Side.CLIENT);
            if (loader.isDebug()) {
                env.setOption(MixinEnvironment.Option.DEBUG_VERBOSE, true);
            }

            register(loader, BUILTIN_CONFIG, "Singularity Loader");
            int configs = 1;
            // Long positions pack 64-bit X/Z through the wide packing, so they need it too.
            if (WidePacking.isNeeded(ServerState.worldLimit()) || ServerState.longPositions()) {
                // Changes how the whole game packs positions, so it is only applied when max_coordinate asks for it.
                register(loader, WIDE_CONFIG, "Singularity Loader");
                configs++;
                LOG.info("Wide position packing is on (max_coordinate {}).", ServerState.worldLimit());
            }
            if (ServerState.longPositions()) {
                register(loader, LONG_CONFIG, "Singularity Loader");
                configs++;
            }
            for (ModContainerImpl mod : mods) {
                for (MixinConfigRef ref : mod.getMetadataImpl().getMixinConfigs()) {
                    if (!ref.environment().matches(loader.getEnvironment())) {
                        LOG.debug("Skipping Mixin config {} of '{}' (wrong environment)", ref.config(), mod.getId());
                        continue;
                    }
                    register(loader, ref.config(), "mod '" + mod.getId() + "'");
                    configs++;
                }
            }

            // Move Mixin through its phases so that every config is selected and ready.
            Method gotoPhase = MixinEnvironment.class.getDeclaredMethod("gotoPhase", MixinEnvironment.Phase.class);
            gotoPhase.setAccessible(true);
            gotoPhase.invoke(null, MixinEnvironment.Phase.INIT);
            gotoPhase.invoke(null, MixinEnvironment.Phase.DEFAULT);

            IMixinTransformer transformer = SingularityMixinService.getTransformer();
            if (transformer == null) {
                Object active = MixinEnvironment.getCurrentEnvironment().getActiveTransformer();
                if (active instanceof IMixinTransformer t) {
                    transformer = t;
                }
            }
            if (transformer == null) {
                throw new LaunchException("Mixin started but did not provide a class transformer. This is a bug in Singularity Loader.");
            }
            LOG.info("Mixin is ready ({} config{} registered)", configs, configs == 1 ? "" : "s");
            return transformer;
        } catch (LaunchException e) {
            throw e;
        } catch (Throwable t) {
            throw new LaunchException("Mixin could not be started: " + t, t);
        }
    }

    private static void startMixinExtras() {
        try {
            com.llamalad7.mixinextras.MixinExtrasBootstrap.init();
        } catch (Throwable t) {
            // MixinExtras is a convenience for mod authors; the game can run without it.
            LOG.warn("MixinExtras could not be started; mods that use it will fail to load: {}", t);
        }
    }

    private static void register(LoaderImpl loader, String config, String owner) throws LaunchException {
        if (loader.getGameClassLoader().getResource(config) == null) {
            throw new LaunchException("The " + owner + " declares the Mixin config '" + config + "', but no such file exists in its jar.");
        }
        Mixins.addConfiguration(config);
        LOG.debug("Registered Mixin config {} ({})", config, owner);
    }
}
