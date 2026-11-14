package net.singularitymc.loader.game;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import net.singularitymc.api.version.Version;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Which Minecraft versions the loader knows, and whether the two patches that every server needs
 * (EULA and settings) have their targets in the game jar that is about to run.
 *
 * <p>Supported: {@value #OLDEST} up to {@value #NEWEST}. The optimisation patches never matter for compatibility:
 * they are optional ({@code require = 0}) and are simply skipped if a method changed.
 */
public final class GameCompat {
    /** 26.1 is the first Minecraft that ships unobfuscated (Mojang's own names, no mappings); nothing older is supported. */
    public static final String OLDEST = "26.1";
    public static final String NEWEST = "26.3";

    private GameCompat() {}

    /** @return {@code null} if the version is supported, otherwise a warning text */
    public static String versionWarning(String versionId) {
        Version v;
        try {
            v = Version.parseLenient(versionId);
        } catch (RuntimeException e) {
            return "Minecraft version '" + versionId + "' is not recognised.";
        }
        if (v.compareTo(Version.parseLenient(OLDEST)) < 0) {
            return "Minecraft " + versionId + " is older than " + OLDEST + ", which is the oldest version Singularity supports.";
        }
        if (v.compareTo(Version.parseLenient(NEWEST)) > 0) {
            return "Minecraft " + versionId + " is newer than " + NEWEST + ", the newest version Singularity was tested with.";
        }
        return null;
    }

    /**
     * True if the game jar still has {@code Eula.readFile/saveDefaults} and {@code Settings.loadFromFile/store}, the
     * methods {@code EulaMixin} and {@code SettingsMixin} replace. If not, the loader falls back to writing the files
     * Minecraft itself reads ({@code eula.txt}, {@code server.properties}).
     */
    public static boolean serverFilePatchesApply(Path gameJar) {
        try (JarFile jar = new JarFile(gameJar.toFile())) {
            return hasMethods(jar, "net/minecraft/server/Eula", Set.of("readFile", "saveDefaults"))
                    && hasMethods(jar, "net/minecraft/server/dedicated/Settings", Set.of("loadFromFile", "store"));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * True if the game has Mojang's built-in Far Lands switch ({@code SharedConstants.DEBUG_ENABLE_FARLANDS}, added in
     * 26.3 Pre-Release 1). It is turned on with the Java properties {@code MC_DEBUG_ENABLED} and {@code MC_DEBUG_ENABLE_FARLANDS}.
     */
    public static boolean hasFarlandsSwitch(Path gameJar) {
        try (JarFile jar = new JarFile(gameJar.toFile())) {
            ZipEntry entry = jar.getEntry("net/minecraft/SharedConstants.class");
            if (entry == null) {
                return false;
            }
            try (InputStream in = jar.getInputStream(entry)) {
                // the field name is stored as plain UTF-8 in the class file's constant pool
                return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1).contains("DEBUG_ENABLE_FARLANDS");
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static boolean hasMethods(JarFile jar, String className, Set<String> wanted) throws IOException {
        ZipEntry entry = jar.getEntry(className + ".class");
        if (entry == null) {
            return false;
        }
        Set<String> found = new java.util.HashSet<>();
        byte[] bytes;
        try (InputStream in = jar.getInputStream(entry)) {
            bytes = in.readAllBytes();
        }
        // Only the method names are read, which does not depend on the class file version; claim an older one so any ASM accepts it.
        if (bytes.length > 7 && (((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF)) > 68) {
            bytes[6] = 0;
            bytes[7] = 68;
        }
        {
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    found.add(name);
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return found.containsAll(wanted);
    }
}
