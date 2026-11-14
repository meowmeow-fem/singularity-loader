package net.singularitymc.perf;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.redstone.NeighborUpdater;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.chunk.LevelChunk;
import net.singularitymc.loader.util.ActivationAware;
import net.singularitymc.loader.util.Log;
import net.singularitymc.loader.util.Perf;
import net.singularitymc.loader.util.PerfGovernor;
import net.singularitymc.loader.util.TrackingAware;

/**
 * The Minecraft side of the many-players optimisations (4.2, docs/PERFORMANCE.md). The patches in
 * {@code net.singularitymc.loader.patches} call in here; the settings and the governor's logic are in
 * {@link Perf} and {@link PerfGovernor}.
 */
public final class PerfHooks {
    private PerfHooks() {}

    private static final Log LOG = Log.get("Singularity");

    private static long tickStart;
    private static PerfGovernor governor;
    private static int serverTick;

    // ---- adaptive governor ----

    // ---- 4.3: redstone dust, ticking-range lookups, POI scans, pathfinding budget ----

    private static Thread serverThread;
    private static final long[][] WIRE_SEEN = new long[64][];
    private static final int[] WIRE_COUNT = new int[64];
    private static int wireDepth = -1;
    private static long pathSpent;
    private static long pathStart;
    private static int pathDeferred;

    /** The server tick counter the caches are stamped with (never 0 once the server ticks). */
    public static int tick() {
        return serverTick;
    }

    public static boolean onServerThread() {
        return Thread.currentThread() == serverThread;
    }

    /** A redstone dust starts telling its surroundings about a power change. */
    public static void wireBegin() {
        if (onServerThread() && ++wireDepth < 64) {
            if (WIRE_SEEN[wireDepth] == null) {
                WIRE_SEEN[wireDepth] = new long[48];
            }
            WIRE_COUNT[wireDepth] = 0;
        }
    }

    public static void wireEnd() {
        if (onServerThread() && wireDepth >= 0) {
            wireDepth--;
        }
    }

    /**
     * Vanilla's {@code level.updateNeighborsAt(p, wire)} for one of the seven positions (the dust and its six
     * neighbours): notifies the six blocks around {@code p}, in vanilla's order, but skips a block this power change
     * has already notified. The seven calls reach 25 different blocks with 42 notifications; each notified dust
     * recomputes its power from about 30 block lookups.
     */
    public static void wireNeighbors(Level level, BlockPos p, Block wire) {
        int depth = wireDepth;
        if (!Perf.wireDedup() || depth < 0 || depth >= 64 || !onServerThread()) {
            level.updateNeighborsAt(p, wire);
            return;
        }
        long[] seen = WIRE_SEEN[depth];
        for (Direction direction : NeighborUpdater.UPDATE_ORDER) {
            BlockPos target = p.relative(direction);
            long key = target.asLong();
            int n = WIRE_COUNT[depth];
            boolean duplicate = false;
            for (int i = 0; i < n; i++) {
                if (seen[i] == key) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                seen[n] = key;
                WIRE_COUNT[depth] = n + 1; // before the call: it may run a whole cascade, with deeper batches of its own
                level.neighborChanged(target, wire, null);
            }
        }
    }

    /**
     * A direct-mapped cache of "is this chunk in ticking range" for the current tick: 0 = false, 1 = true,
     * -1 = not cached. The game asks once per entity and per block entity, each time with a hash lookup.
     */
    public static int tickRangeGet(long[] keys, byte[] values, int[] stamps, long chunk) {
        if (!Perf.tickRangeCache() || !onServerThread()) {
            return -1;
        }
        int i = slot(chunk);
        return stamps[i] == serverTick && keys[i] == chunk ? values[i] : -1;
    }

    public static void tickRangePut(long[] keys, byte[] values, int[] stamps, long chunk, boolean inRange) {
        if (!Perf.tickRangeCache() || !onServerThread()) {
            return;
        }
        int i = slot(chunk);
        keys[i] = chunk;
        values[i] = (byte) (inRange ? 1 : 0);
        stamps[i] = serverTick;
    }

    private static int slot(long chunk) {
        return ((int) (chunk ^ (chunk >>> 32)) * 0x9E3779B9) >>> 22; // 1024 slots
    }

    /** True if this tick has no pathfinding time left: the request is put off (the mob asks again later). */
    public static boolean pathBudgetExhausted() {
        long budget = Perf.pathBudgetNanos();
        if (budget <= 0 || !onServerThread()) {
            return false;
        }
        if (pathSpent >= budget) {
            pathDeferred++;
            return true;
        }
        pathStart = System.nanoTime();
        return false;
    }

    public static void pathDone() {
        if (pathStart != 0 && onServerThread()) {
            pathSpent += System.nanoTime() - pathStart;
            pathStart = 0;
        }
    }

    public static void tickStart() {
        serverThread = Thread.currentThread();
        wireDepth = -1;
        pathSpent = 0;
        pathStart = 0;
        tickStart = System.nanoTime();
        if (serverTick++ == 0 && Perf.chunkBudgetNanos() > 0) {
            // On few cores the tick competes with chunk generation and loading for CPU; let the tick win. (Windows and
            // macOS honour thread priorities; Linux ignores them unless the JVM is told otherwise.)
            try {
                Thread.currentThread().setPriority(Thread.NORM_PRIORITY + 2);
            } catch (RuntimeException e) {
                // not allowed: stay as we are
            }
        }
    }

    public static void tickEnd(MinecraftServer server) {
        long tickNanos = System.nanoTime() - tickStart;
        runChunkTasks(server);
        governorTick(server, tickNanos);
    }

    // ---- chunk work on an overloaded server ----

    private static long unloadDeadline;
    private static int unloadDeadlineTick = -1;

    /**
     * Vanilla runs the chunk system's main-thread tasks (tickets handed out and taken back, loaded chunks made
     * usable) only in the time left before the next tick. A server that is behind has none, so chunks come in slowly
     * and, worse, are never let go: lowering the view distance changes nothing and the server cannot recover. They
     * get a few milliseconds after every tick here, whatever the schedule says.
     */
    private static void runChunkTasks(MinecraftServer server) {
        long budget = Perf.chunkBudgetNanos();
        if (budget <= 0) {
            return;
        }
        long deadline = System.nanoTime() + budget;
        boolean ran = true;
        while (ran && System.nanoTime() < deadline) {
            ran = false;
            for (ServerLevel level : server.getAllLevels()) {
                ran |= level.getChunkSource().pollTask();
            }
        }
    }

    /**
     * The same for chunks waiting to be unloaded: vanilla takes them only with time to spare (or when more than
     * 2,000 wait). True while this tick's unload budget lasts.
     */
    public static boolean unloadBudgetLeft() {
        long budget = Perf.chunkBudgetNanos();
        if (budget <= 0) {
            return false;
        }
        long now = System.nanoTime();
        if (unloadDeadlineTick != serverTick) {
            unloadDeadlineTick = serverTick;
            unloadDeadline = now + budget;
        }
        return now < unloadDeadline;
    }

    private static void governorTick(MinecraftServer server, long tickNanos) {
        if (!Perf.adaptive()) {
            return;
        }
        PlayerList players = server.getPlayerList();
        if (players == null) {
            return;
        }
        if (governor == null) {
            governor = new PerfGovernor(Perf.targetMspt(), players.getSimulationDistance(), players.getViewDistance(),
                Perf.minSimulation(), Perf.minView());
        }
        if (server.tickRateManager().isSprinting() || !server.tickRateManager().runsNormally()) {
            return; // /tick sprint and /tick freeze are not load
        }
        Runtime rt = Runtime.getRuntime();
        if (!governor.tick(tickNanos, rt.totalMemory() - rt.freeMemory(), rt.maxMemory())) {
            return;
        }
        Perf.apply(governor);
        int sim = governor.simulationDistance(), view = governor.viewDistance();
        if (players.getSimulationDistance() != sim) {
            players.setSimulationDistance(sim);
        }
        if (players.getViewDistance() != view) {
            players.setViewDistance(view);
        }
        LOG.info("[Perf] load level {} of {} (ticks took {} ms, target {}; heap {}% full after collection{}): simulation distance {},"
                + " view distance {}, mob spawning every {} tick(s), far mobs beyond {} blocks tick 1 in {}.",
            governor.level(), governor.maxLevel(), String.format(java.util.Locale.ROOT, "%.1f", governor.lastMeanMspt()),
            (int) Perf.targetMspt(), governor.lastHeapPercent(), governor.memoryPressure() ? ", too full" : "",
            sim, view, Perf.spawnInterval(), Perf.activationRange(), Perf.inactiveTickInterval());
    }

    // ---- natural spawning ----

    /** False in the ticks natural mob spawning sits out. */
    public static boolean spawnThisTick() {
        int interval = Perf.spawnInterval();
        return interval <= 1 || serverTick % interval == 0;
    }

    // ---- random ticks ----

    /**
     * The random tick speed for this chunk tick: unchanged, or 0 in the ticks this chunk sits out under heavy load
     * (from level 5). Crops, saplings and the like then grow at a half or a third of their speed until the load is gone.
     */
    public static int randomTickSpeed(LevelChunk chunk, int speed) {
        int divisor = Perf.randomTickDivisor();
        if (divisor <= 1 || speed <= 0) {
            return speed;
        }
        return Math.floorMod(serverTick + chunk.getPos().hashCode(), divisor) == 0 ? speed : 0;
    }

    // ---- entity tracking ----

    /**
     * True if this move of the player should re-check every tracked entity against it. Vanilla does that on every
     * move packet (20 a second per walking player, each one a pass over every entity of the world). Whenever a player
     * or an entity crosses a chunk-section border {@code ChunkMap.tick} updates them anyway, so between those a
     * re-check every few ticks only delays entities at the very edge of their tracking range by a fraction of a second.
     */
    public static boolean trackEntitiesOnMove(ServerPlayer player) {
        int interval = Perf.trackingInterval();
        if (interval <= 1) {
            return true;
        }
        TrackingAware t = (TrackingAware) player;
        if (serverTick - t.singularity$lastTrackingTick() < interval) {
            return false;
        }
        t.singularity$setLastTrackingTick(serverTick + (player.getId() & 1)); // spread players over the ticks
        return true;
    }

    // ---- entity activation range ----

    /**
     * True if this entity's tick is skipped: a mob with no player within the activation range, standing on the
     * with nothing going on, ticks only once in a while. Anything that needs every tick keeps it: burning, hurt,
     * fighting, riding or ridden mobs, land mobs that fall or are in water, bosses.
     */
    public static boolean skipTick(ServerLevel level, Entity entity) {
        int range = Perf.activationRange();
        if (range <= 0 || !(entity instanceof Mob mob)) {
            return false;
        }
        long now = level.getGameTime();
        if (near(level, mob, range, now)) {
            return false;
        }
        if (mob.isInLava() || mob.isOnFire() || mob.hurtTime > 0 || mob.deathTime > 0
            || mob.isPassenger() || mob.isVehicle() || mob.getTarget() != null) {
            return false;
        }
        // A land mob in water (currents, drowning) or falling keeps every tick. Fish, squid and the like live in water,
        // and bats and other fliers are never on the ground: for them neither means anything is going on.
        MobCategory category = mob.getType().getCategory();
        boolean aquatic = category == MobCategory.WATER_CREATURE || category == MobCategory.WATER_AMBIENT
            || category == MobCategory.UNDERGROUND_WATER_CREATURE || category == MobCategory.AXOLOTLS;
        if (!aquatic && (mob.isInWater() || (!mob.onGround() && mob.getDeltaMovement().y < -0.1))) {
            return false;
        }
        if ((now + mob.getId()) % Perf.inactiveTickInterval() == 0) {
            return false;
        }
        // what must not stand still: growing up and breeding cooldowns
        if (mob instanceof AgeableMob ageable) {
            int age = ageable.getAge();
            if (age < 0) {
                ageable.setAge(age + 1);
            } else if (age > 0) {
                ageable.setAge(age - 1);
            }
        }
        return true;
    }

    /** Whether a player is within the activation range of this mob; looked up about every 12 ticks, remembered between. */
    private static boolean near(ServerLevel level, Mob mob, int range, long now) {
        ActivationAware a = (ActivationAware) mob;
        if (now >= a.singularity$nextNearCheck()) {
            // bosses (ender dragon, wither) count as always near; by package, since their classes moved between versions
            boolean near = mob.getClass().getName().startsWith("net.minecraft.world.entity.boss.")
                || playerWithin(level.players(), mob, range);
            a.singularity$setNear(near, now + 8 + (mob.getId() & 7));
        }
        return a.singularity$near();
    }

    /**
     * True if this mob's despawn check sits this tick out. Vanilla searches the nearest player for every mob in every
     * tick, also for the thousands that are loaded but too far from anyone to be ticked. A mob with no player within
     * the activation range is checked every 4th tick: it despawns at most 4 ticks later when everyone has left, and
     * its random despawning between 32 and 128 blocks is 4 times slower (the mob caps are unchanged).
     */
    public static boolean skipDespawnCheck(Mob mob) {
        int range = Perf.activationRange();
        if (range <= 0 || !(mob.level() instanceof ServerLevel level)) {
            return false;
        }
        long now = level.getGameTime();
        return !near(level, mob, range, now) && (now + mob.getId()) % 4 != 0;
    }

    private static boolean playerWithin(List<ServerPlayer> players, Mob mob, int range) {
        double x = mob.getX(), y = mob.getY(), z = mob.getZ();
        double r2 = (double) range * range;
        for (int i = 0, n = players.size(); i < n; i++) {
            ServerPlayer p = players.get(i);
            double dx = p.getX() - x, dy = p.getY() - y, dz = p.getZ() - z;
            if (dx * dx + dy * dy + dz * dz <= r2 && !p.isSpectator()) {
                return true;
            }
        }
        return false;
    }
}
