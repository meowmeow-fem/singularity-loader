package net.singularitymc.wideclient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import net.singularitymc.loader.classloading.MidpointRewriter;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Runs before any game class is transformed. The shared limit patches read {@code ServerState.worldLimit()}, which the
 * Singularity launcher fills from {@code max_coordinate}; a client has no launcher, so the limit comes from
 * {@code config/singularity-wide-client.properties} (or the {@code singularity.worldLimit} system property). The
 * server still enforces its own {@code max_coordinate}.
 */
public final class WideClientMixinPlugin implements IMixinConfigPlugin {
    /** Same as the launcher's highest accepted {@code max_coordinate}: 2^30 minus 256 chunks. */
    public static final int CLIENT_LIMIT = (1 << 30) - 256 * 16;
    /** With {@code full_int_range = true} (experimental): 2^31 minus 256 chunks. */
    public static final int CLIENT_LIMIT_FULL_RANGE = Integer.MAX_VALUE - 256 * 16 + 1;

    private static final Path CONFIG = Path.of("config", "singularity-wide-client.properties");
    private static final String DEFAULT_CONFIG = """
        # Singularity Wide Client
        # Biggest block coordinate this client allows. The server still enforces its own max_coordinate,
        # so set this at least as high as the servers you play on. Up to 1073737728.
        max_coordinate=1073737728
        # EXPERIMENTAL: lets max_coordinate go up to 2147479552 (for servers with full_int_range = true).
        # Also rewrites the game's coordinate midpoints for singleplayer worlds out there.
        full_int_range=false
        """;

    @Override
    public void onLoad(String mixinPackage) {
        if (System.getProperty("singularity.worldLimit") == null) {
            System.setProperty("singularity.worldLimit", Integer.toString(readLimit()));
        }
    }

    private static int readLimit() {
        Properties p = new Properties();
        try {
            if (!Files.exists(CONFIG)) {
                Files.createDirectories(CONFIG.getParent());
                Files.writeString(CONFIG, DEFAULT_CONFIG);
            }
            try (InputStream in = Files.newInputStream(CONFIG)) {
                p.load(in);
            }
        } catch (IOException e) {
            return CLIENT_LIMIT; // read-only folder or similar: the safe default
        }
        boolean fullRange = p.getProperty("full_int_range", "false").strip().equalsIgnoreCase("true");
        long wanted;
        try {
            wanted = Long.parseLong(p.getProperty("max_coordinate", Integer.toString(CLIENT_LIMIT)).strip());
        } catch (NumberFormatException e) {
            wanted = CLIENT_LIMIT;
        }
        return (int) Math.max(30_000_000L, Math.min(fullRange ? CLIENT_LIMIT_FULL_RANGE : CLIENT_LIMIT, wanted));
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        if (mixinClassName.endsWith(".MidpointTargetsMixin") && Integer.getInteger("singularity.worldLimit", CLIENT_LIMIT) > CLIENT_LIMIT) {
            MidpointRewriter.rewrite(targetClass);
        }
    }
}
