package net.singularitymc.loader.util;

import java.util.function.BiFunction;

/**
 * Settings and live state of the many-players optimisations (4.2, docs/PERFORMANCE.md): the {@code [performance]}
 * section of ServerConfig.toml, and what the adaptive governor ({@link PerfGovernor}) currently asks for. Read by the
 * patches on every tick, so everything is a plain static field.
 *
 * <p>{@code -Dsingularity.opt.disable=true} turns all of it off.
 */
public final class Perf {
    private Perf() {}

    private static final boolean DISABLED = Boolean.getBoolean("singularity.opt.disable");

    // ---- configuration (ServerConfig.toml) ----
    private static boolean adaptive = !DISABLED;
    private static double targetMspt = 45;
    private static int minSimulation = 3;
    private static int minView = 4;
    /** Blocks; 0 = off. Mobs farther than this from every player tick only now and then. */
    private static int activationRange = DISABLED ? 0 : 48;
    /** A player's moves re-check every tracked entity at most once per this many ticks (1 = vanilla). */
    private static int trackingInterval = DISABLED ? 1 : 8;
    private static int spawnInterval = 1;
    /** Nanoseconds of chunk tasks and of chunk unloads a tick always gets, even with no time to spare; 0 = vanilla. */
    private static long chunkBudgetNanos = DISABLED ? 0 : 3_000_000L;

    /** Redstone dust: a power change notifies each block around it once instead of up to six times. */
    private static boolean wireDedup = !DISABLED;
    /** Villagers' bed / workstation searches skip chunk columns known to hold nothing of interest. */
    private static boolean poiCache = !DISABLED;
    /** Nanoseconds of pathfinding a tick may spend before further requests are put off; 0 = unlimited. */
    private static long pathBudgetNanos = DISABLED ? 0 : 8_000_000L;
    /** "Is this chunk ticking" is looked up once per chunk and tick instead of once per entity and block entity. */
    private static boolean tickRangeCache = !DISABLED;

    // ---- live state, set by the governor ----
    private static volatile int level;
    private static volatile int liveSpawnInterval = 1;
    private static volatile int liveRandomTickDivisor = 1;

    /** Reads the {@code [performance]} keys; {@code get} is {@code ServerConfig.get(key, fallback)}. */
    public static void configure(BiFunction<String, String, String> get) {
        if (DISABLED) {
            return;
        }
        adaptive = get.apply("adaptive_performance", "true").equalsIgnoreCase("true");
        targetMspt = clamp(number(get, "target_tick_ms", 45), 10, 200);
        minSimulation = (int) clamp(number(get, "min_simulation_distance", 3), 2, 32);
        minView = (int) clamp(number(get, "min_view_distance", 4), 2, 32);
        activationRange = (int) clamp(number(get, "entity_activation_range", 48), 0, 512);
        trackingInterval = (int) clamp(number(get, "entity_tracking_interval", 8), 1, 20);
        spawnInterval = (int) clamp(number(get, "mob_spawn_interval", 1), 1, 400);
        liveSpawnInterval = spawnInterval;
        chunkBudgetNanos = (long) (clamp(number(get, "overload_chunk_budget_ms", 3), 0, 25) * 1_000_000);
        wireDedup = get.apply("redstone_dedup_wire_updates", "true").equalsIgnoreCase("true");
        fastWire = get.apply("redstone_wire_algorithm", "vanilla").strip().equalsIgnoreCase("fast");
        poiCache = get.apply("poi_scan_cache", "true").equalsIgnoreCase("true");
        pathBudgetNanos = (long) (clamp(number(get, "pathfinding_budget_ms", 8), 0, 50) * 1_000_000);
    }

    /** {@code redstone_wire_algorithm = "fast"}: dust networks are worked out as a whole (opt-in, see FastWire). */
    private static boolean fastWire;

    public static boolean fastWire() {
        return fastWire;
    }

    public static boolean wireDedup() {
        return wireDedup;
    }

    public static boolean poiCache() {
        return poiCache;
    }

    public static boolean tickRangeCache() {
        return tickRangeCache;
    }

    /** The pathfinding budget of a tick; halved from load level 3. */
    public static long pathBudgetNanos() {
        long b = pathBudgetNanos;
        return b > 0 && level >= 3 ? b / 2 : b;
    }

    /** See {@link #chunkBudgetNanos}. */
    public static long chunkBudgetNanos() {
        return chunkBudgetNanos;
    }

    private static double number(BiFunction<String, String, String> get, String key, double fallback) {
        try {
            return Double.parseDouble(get.apply(key, Double.toString(fallback)).strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    public static boolean adaptive() {
        return adaptive;
    }

    public static double targetMspt() {
        return targetMspt;
    }

    public static int minSimulation() {
        return minSimulation;
    }

    public static int minView() {
        return minView;
    }

    /** The governor's current level; 0 = the server as configured. */
    public static int level() {
        return level;
    }

    /** Called by the governor's hook when the level changes. */
    public static void apply(PerfGovernor g) {
        level = g.level();
        liveSpawnInterval = Math.max(spawnInterval, g.spawnInterval());
        liveRandomTickDivisor = g.randomTickDivisor();
    }

    /** Natural mob spawning runs every this many ticks. */
    public static int spawnInterval() {
        return liveSpawnInterval;
    }

    /** Random ticks run in one of this many chunk ticks. */
    public static int randomTickDivisor() {
        return liveRandomTickDivisor;
    }

    /**
     * Blocks from the nearest player within which a mob is always active; 0 = every mob is. Shrinks under load:
     * the configured range, three quarters of it from level 2, half from level 5 (never below 16).
     */
    public static int activationRange() {
        int r = activationRange;
        if (r <= 0) {
            return 0;
        }
        int l = level;
        return l >= 5 ? Math.max(16, r / 2) : l >= 2 ? Math.max(16, r * 3 / 4) : r;
    }

    /** An inactive mob still ticks once in this many ticks: 4, 10 from level 2, 20 from level 5. */
    public static int inactiveTickInterval() {
        int l = level;
        return l >= 5 ? 20 : l >= 2 ? 10 : 4;
    }

    /** See {@link #trackingInterval}; doubles from level 3. */
    public static int trackingInterval() {
        int i = trackingInterval;
        return i > 1 && level >= 3 ? i * 2 : i;
    }
}
