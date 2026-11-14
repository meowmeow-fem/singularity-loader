package net.singularitymc.loader.classloading;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.CodeSource;
import java.security.SecureClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The class loader that runs Minecraft and every mod.
 *
 * <p>It is <em>child first</em>: classes and resources are looked up in the game and mod jars
 * before the parent (the loader's own class path) is consulted, with two exceptions that always go
 * to the parent so that both sides see the same classes:
 * <ul>
 *   <li>the JDK ({@code java.}, {@code javax.}, {@code jdk.}, ...)</li>
 *   <li>the loader, its API, Mixin, MixinExtras and ASM</li>
 * </ul>
 * Every class read from a jar passes through the installed {@link ClassTransformer} (Mixin) before
 * it is defined.
 */
public final class SingularityClassLoader extends SecureClassLoader {
    static {
        registerAsParallelCapable();
    }

    private static final String[] PARENT_FIRST = {
        "java.", "javax.", "jdk.", "sun.", "com.sun.",
        "net.singularitymc.api.", "net.singularitymc.loader.",
        "org.spongepowered.asm.", "org.spongepowered.include.", "org.objectweb.asm.",
        "com.llamalad7.mixinextras."
    };

    private final List<ClassSource> sources;
    private final Map<ClassSource, CodeSource> codeSources = new ConcurrentHashMap<>();
    private final Set<String> invalidClasses = ConcurrentHashMap.newKeySet();
    private volatile ClassTransformer transformer = ClassTransformer.NONE;

    public SingularityClassLoader(ClassLoader parent, List<ClassSource> sources) {
        super("singularity", parent);
        this.sources = List.copyOf(sources);
    }

    public void setTransformer(ClassTransformer transformer) {
        this.transformer = transformer == null ? ClassTransformer.NONE : transformer;
    }

    public List<ClassSource> getSources() {
        return sources;
    }

    /** Marks a class (a Mixin class, for example) as one that must never be loaded directly. */
    public void registerInvalidClass(String name) {
        invalidClasses.add(name.replace('/', '.'));
    }

    /** Whether this loader has already defined {@code name}. */
    public boolean isClassLoaded(String name) {
        return findLoadedClass(name.replace('/', '.')) != null;
    }

    private static boolean isParentFirst(String name) {
        for (String prefix : PARENT_FIRST) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (isParentFirst(name)) {
            return super.loadClass(name, resolve);
        }
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                if (invalidClasses.contains(name)) {
                    throw new ClassNotFoundException(name + " is a Mixin class and cannot be loaded directly");
                }
                try {
                    c = findClass(name);
                } catch (ClassNotFoundException notOurs) {
                    c = getParent().loadClass(name);
                }
            }
            if (resolve) {
                resolveClass(c);
            }
            return c;
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        String path = name.replace('.', '/') + ".class";
        for (ClassSource source : sources) {
            byte[] bytes;
            try {
                bytes = source.read(path);
            } catch (IOException e) {
                throw new ClassNotFoundException("Could not read " + name + " from " + source, e);
            }
            if (bytes != null) {
                return define(name, bytes, source);
            }
        }
        throw new ClassNotFoundException(name);
    }

    private Class<?> define(String name, byte[] raw, ClassSource source) {
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            String pkg = name.substring(0, dot);
            if (getDefinedPackage(pkg) == null) {
                try {
                    definePackage(pkg, null, null, null, null, null, null, null);
                } catch (IllegalArgumentException alreadyDefined) {
                    // another thread won the race
                }
            }
        }
        byte[] bytes;
        try {
            bytes = transformer.transform(name, raw);
        } catch (RuntimeException e) {
            throw new LinkageError("Failed to transform class " + name, e);
        }
        return defineClass(name, bytes, 0, bytes.length, codeSource(source));
    }

    private CodeSource codeSource(ClassSource source) {
        return codeSources.computeIfAbsent(source, s -> {
            try {
                return new CodeSource(s.path().toUri().toURL(), (java.security.cert.Certificate[]) null);
            } catch (java.net.MalformedURLException e) {
                return null;
            }
        });
    }

    // ---- resources: child first, like classes -------------------------------------------------

    @Override
    public URL findResource(String name) {
        for (ClassSource source : sources) {
            URL url = source.findResource(name);
            if (url != null) {
                return url;
            }
        }
        return null;
    }

    @Override
    public Enumeration<URL> findResources(String name) {
        List<URL> urls = new ArrayList<>();
        for (ClassSource source : sources) {
            URL url = source.findResource(name);
            if (url != null) {
                urls.add(url);
            }
        }
        return Collections.enumeration(urls);
    }

    @Override
    public URL getResource(String name) {
        URL own = findResource(name);
        return own != null ? own : getParent().getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        List<URL> urls = new ArrayList<>(Collections.list(findResources(name)));
        urls.addAll(Collections.list(getParent().getResources(name)));
        return Collections.enumeration(urls);
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        for (ClassSource source : sources) {
            try {
                InputStream in = source.openStream(name);
                if (in != null) {
                    return in;
                }
            } catch (IOException ignored) {
                // fall through to the next source
            }
        }
        return getParent().getResourceAsStream(name);
    }

    @Override
    public String toString() {
        return "SingularityClassLoader(" + sources.size() + " sources)";
    }
}
