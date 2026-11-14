package net.singularitymc.loader;

import net.singularitymc.loader.launch.SingularityLauncher;

/** {@code java -jar singularity-loader.jar}: starts a Minecraft server with mods. */
public final class Main {
    private static final String[] REQUIRED = {
        "com.google.gson.JsonParser",
        "org.spongepowered.asm.launch.MixinBootstrap",
        "org.objectweb.asm.ClassReader",
        "com.llamalad7.mixinextras.MixinExtrasBootstrap"
    };

    private Main() {}

    public static void main(String[] args) {
        for (String className : REQUIRED) {
            try {
                Class.forName(className, false, Main.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                System.err.println("Singularity Loader cannot start: its library " + className + " was not found.");
                System.err.println("singularity-loader.jar needs the 'lib' folder that comes with it. Keep the files together:");
                System.err.println("    singularity-loader.jar");
                System.err.println("    lib/   (must be in the same folder as the jar)");
                System.err.println("Do not copy the jar alone; start it from the unpacked release folder (or use start.bat / start.sh).");
                System.exit(1);
                return;
            }
        }
        SingularityLauncher.run(args);
    }
}
