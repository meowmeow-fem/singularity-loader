package net.singularitymc.loader.util;

import java.util.Properties;

/**
 * Values the launcher prepares and the patched game classes read: whether the EULA was accepted and
 * the settings from {@code ServerConfig.toml}. Lives in the loader's own package, so the game class
 * loader and the launcher see one single copy.
 */
public final class ServerState {
    private static volatile boolean eulaAccepted;
    private static volatile Properties serverProperties;

    // The Fabric client mod (wide-client-mod) has no launcher; it sets this property before any game class loads.
    private static volatile int worldLimit = Integer.getInteger("singularity.worldLimit", 30_000_000);

    private ServerState() {}

    private static volatile boolean longPositions;

    /** {@code experimental_long_positions}: the position types get 64-bit X/Z at load (docs/ROADMAP_64BIT.md, 3.5.2). */
    public static boolean longPositions() {
        return longPositions;
    }

    public static void setLongPositions(boolean on) {
        longPositions = on;
    }

    private static volatile boolean borderWarning;

    /** Whether clients are told the border's warning distance / time ({@code world_border_warning}); off: no red tint. */
    public static boolean borderWarning() {
        return borderWarning;
    }

    public static void setBorderWarning(boolean on) {
        borderWarning = on;
    }

    private static volatile boolean classicFarLands;

    /** Whether chunks past 12,550,816 blocks get the classic (Beta) Far Lands ({@code farlands_style = "classic"}). */
    public static boolean classicFarLands() {
        return classicFarLands;
    }

    public static void setClassicFarLands(boolean on) {
        classicFarLands = on;
    }

    /** Block coordinate limit of the world (vanilla: 30,000,000), from {@code max_coordinate} in ServerConfig.toml. */
    public static int worldLimit() {
        return worldLimit;
    }

    public static void setWorldLimit(int limit) {
        worldLimit = limit;
        worldLimit64 = limit;
    }

    private static volatile long worldLimit64 = worldLimit;

    /**
     * The block coordinate limit as a long: past the int range with {@code experimental_long_positions} (3.5.4), where
     * {@link #worldLimit()} stays at the int maximum for code that is not widened.
     */
    public static long worldLimit64() {
        return worldLimit64;
    }

    /**
     * Past the int range, code that is not widened only sees the low 32 bits of a coordinate, so its int limit checks
     * must never refuse anything ({@code /forceload} at 2,147,483,600 was "out of this world"): the int limit is then
     * {@code Integer.MAX_VALUE}, and widened code checks the 64-bit one.
     */
    public static void setWorldLimit64(long limit) {
        worldLimit = limit > Integer.MAX_VALUE - 256 * 16 + 1 ? Integer.MAX_VALUE : (int) limit;
        worldLimit64 = limit;
    }

    public static boolean isEulaAccepted() {
        return eulaAccepted;
    }

    public static void setEulaAccepted(boolean accepted) {
        eulaAccepted = accepted;
    }

    /** The settings in the form Minecraft's {@code server.properties} loader expects, or {@code null} before they are loaded. */
    public static Properties serverProperties() {
        Properties p = serverProperties;
        return p == null ? null : (Properties) p.clone();
    }

    public static void setServerProperties(Properties properties) {
        serverProperties = properties == null ? null : (Properties) properties.clone();
    }
}
