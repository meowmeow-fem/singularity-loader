package net.singularitymc.loader.mixin;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.singularitymc.loader.LoaderImpl;
import net.singularitymc.loader.classloading.ClassSource;
import net.singularitymc.loader.classloading.SingularityClassLoader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.launch.platform.container.ContainerHandleVirtual;
import org.spongepowered.asm.launch.platform.container.IContainerHandle;
import org.spongepowered.asm.logging.ILogger;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.MixinEnvironment.CompatibilityLevel;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.IMixinTransformerFactory;
import org.spongepowered.asm.service.IAdviceProvider;
import org.spongepowered.asm.service.IClassBytecodeProvider;
import org.spongepowered.asm.service.IClassProvider;
import org.spongepowered.asm.service.IClassTracker;
import org.spongepowered.asm.service.IFeatureValidator;
import org.spongepowered.asm.service.IMixinAuditTrail;
import org.spongepowered.asm.service.IMixinInternal;
import org.spongepowered.asm.service.ITransformer;
import org.spongepowered.asm.service.ITransformerProvider;
import org.spongepowered.asm.service.MixinServiceAbstract;

/**
 * Connects Mixin to Singularity's {@link SingularityClassLoader}: it tells Mixin how to read class
 * bytes, how to load classes, and which classes may never be loaded directly.
 *
 * <p>Mixin discovers this class through {@code META-INF/services}; the instance is created by
 * Mixin, so the class loader is looked up through {@link LoaderImpl}.
 */
public final class SingularityMixinService extends MixinServiceAbstract
        implements IClassProvider, IClassBytecodeProvider, ITransformerProvider, IClassTracker {

    static final String NAME = "Singularity";

    private static volatile IMixinTransformer transformer;

    private final IContainerHandle primaryContainer = new ContainerHandleVirtual(NAME);

    /** The Mixin transformer, available once Mixin has been initialised; {@code null} before that. */
    public static IMixinTransformer getTransformer() {
        return transformer;
    }

    private static SingularityClassLoader loader() {
        return LoaderImpl.get().getGameClassLoader();
    }

    // ---- IMixinService -----------------------------------------------------------------------

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean isValid() {
        return true;
    }

    @Override
    public void offer(IMixinInternal internal) {
        if (internal instanceof IMixinTransformerFactory factory) {
            transformer = factory.createTransformer();
        }
        super.offer(internal);
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        return loader().getResourceAsStream(name);
    }

    @Override
    public Collection<String> getPlatformAgents() {
        return List.of("org.spongepowered.asm.launch.platform.MixinPlatformAgentDefault");
    }

    @Override
    public IContainerHandle getPrimaryContainer() {
        return primaryContainer;
    }

    @Override
    public CompatibilityLevel getMaxCompatibilityLevel() {
        // The newest level Mixin knows that this JVM can actually run.
        int feature = Runtime.version().feature();
        CompatibilityLevel best = CompatibilityLevel.JAVA_8;
        int bestVersion = 8;
        for (CompatibilityLevel level : CompatibilityLevel.values()) {
            String name = level.name();
            if (!name.startsWith("JAVA_")) {
                continue;
            }
            try {
                int version = Integer.parseInt(name.substring("JAVA_".length()));
                if (version <= feature && version > bestVersion) {
                    best = level;
                    bestVersion = version;
                }
            } catch (NumberFormatException ignored) {
                // not a versioned level
            }
        }
        return best;
    }

    @Override
    public IClassProvider getClassProvider() {
        return this;
    }

    @Override
    public IClassBytecodeProvider getBytecodeProvider() {
        return this;
    }

    @Override
    public ITransformerProvider getTransformerProvider() {
        return this;
    }

    @Override
    public IClassTracker getClassTracker() {
        return this;
    }

    @Override
    public IMixinAuditTrail getAuditTrail() {
        return null;
    }

    @Override
    public IAdviceProvider getAdviceProvider() {
        return IAdviceProvider.GENERIC;
    }

    @Override
    public IFeatureValidator getFeatureValidator() {
        return IFeatureValidator.ALLOW_ALL;
    }

    @Override
    protected ILogger createLogger(String name) {
        return new SingularityLogger(name);
    }

    // ---- IClassProvider ----------------------------------------------------------------------

    @Override
    @Deprecated
    public URL[] getClassPath() {
        List<URL> urls = new ArrayList<>();
        for (ClassSource source : loader().getSources()) {
            try {
                urls.add(source.path().toUri().toURL());
            } catch (MalformedURLException ignored) {
                // skip unusable entry
            }
        }
        return urls.toArray(new URL[0]);
    }

    @Override
    public Class<?> findClass(String name) throws ClassNotFoundException {
        return Class.forName(name, true, loader());
    }

    @Override
    public Class<?> findClass(String name, boolean initialize) throws ClassNotFoundException {
        return Class.forName(name, initialize, loader());
    }

    @Override
    public Class<?> findAgentClass(String name, boolean initialize) throws ClassNotFoundException {
        return Class.forName(name, initialize, SingularityMixinService.class.getClassLoader());
    }

    // ---- IClassBytecodeProvider --------------------------------------------------------------

    @Override
    public ClassNode getClassNode(String name) throws ClassNotFoundException, IOException {
        return getClassNode(name, true);
    }

    @Override
    public ClassNode getClassNode(String name, boolean runTransformers) throws ClassNotFoundException, IOException {
        return getClassNode(name, runTransformers, 0);
    }

    @Override
    public ClassNode getClassNode(String name, boolean runTransformers, int readerFlags) throws ClassNotFoundException, IOException {
        // There are no other bytecode transformers, so the bytes on the class path are what Mixin needs.
        String binaryName = name.replace('/', '.');
        try (InputStream in = loader().getResourceAsStream(binaryName.replace('.', '/') + ".class")) {
            if (in == null) {
                throw new ClassNotFoundException(binaryName);
            }
            ClassNode node = new ClassNode();
            new ClassReader(in.readAllBytes()).accept(node, readerFlags);
            return node;
        }
    }

    // ---- ITransformerProvider ----------------------------------------------------------------

    @Override
    public Collection<ITransformer> getTransformers() {
        return List.of();
    }

    @Override
    public Collection<ITransformer> getDelegatedTransformers() {
        return List.of();
    }

    @Override
    public void addTransformerExclusion(String name) {
        // Mixin only applies to classes loaded through our class loader; nothing to exclude.
    }

    // ---- IClassTracker -----------------------------------------------------------------------

    @Override
    public void registerInvalidClass(String className) {
        loader().registerInvalidClass(className);
    }

    @Override
    public boolean isClassLoaded(String className) {
        return loader().isClassLoaded(className);
    }

    @Override
    public String getClassRestrictions(String className) {
        return "";
    }
}
