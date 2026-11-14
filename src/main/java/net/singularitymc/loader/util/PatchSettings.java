package net.singularitymc.loader.util;

/**
 * Switches for the optimisation patches shipped with the loader. Everything is controlled with JVM
 * system properties so that a server owner can turn a patch off without editing the loader:
 *
 * <pre>
 *   -Dsingularity.opt.disable=true             disables every optimisation patch
 *   -Dsingularity.opt.itemMergeRest=2          item merge scan for resting items runs every N times less often (1 = vanilla)
 *   -Dsingularity.opt.xpMergeRest=2            same for experience orbs
 *   -Dsingularity.opt.pushCap=8                a living entity pushes at most N neighbours per tick (0 = unlimited / vanilla)
 *   -Dsingularity.opt.crowd=10                 an entity with at least N pushable neighbours counts as "crowded"
 *   -Dsingularity.opt.crowdAiRate=2            crowded mobs without a target run their goal selection every 2*N ticks (1 = vanilla)
 *   -Dsingularity.opt.crowdBrainRate=2         crowded villagers tick their brain every N ticks (1 = vanilla)
 * </pre>
 *
 * The values are read once, when the patched class is first used.
 */
public final class PatchSettings {
    private static final boolean DISABLED = Boolean.getBoolean("singularity.opt.disable");

    /** Multiplier applied to the merge-scan interval of items lying still on the ground. */
    public static final int ITEM_MERGE_REST = read("singularity.opt.itemMergeRest", 2);
    /** Multiplier applied to the merge-scan interval of experience orbs lying still on the ground. */
    public static final int XP_MERGE_REST = read("singularity.opt.xpMergeRest", 2);

    /** Max neighbours one entity pushes per tick; 0 disables the cap. */
    public static final int PUSH_CAP = DISABLED ? 0 : Math.max(0, Integer.getInteger("singularity.opt.pushCap", 8));
    /** Neighbour count from which an entity is treated as crowded. */
    public static final int CROWD = Math.max(2, Integer.getInteger("singularity.opt.crowd", 10));
    public static final int CROWD_AI_RATE = read("singularity.opt.crowdAiRate", 2);
    public static final int CROWD_BRAIN_RATE = read("singularity.opt.crowdBrainRate", 2);

    private PatchSettings() {
    }

    private static int read(String key, int def) {
        if (DISABLED) {
            return 1;
        }
        return Math.max(1, Integer.getInteger(key, def));
    }

    /** Resting = on the ground and practically not moving sideways. */
    public static boolean isResting(boolean onGround, double horizontalSpeedSqr) {
        return onGround && horizontalSpeedSqr < 1.0E-5;
    }

    /** Whether border packets carry the real warning distance / time (see {@code world_border_warning}). */
    public static boolean borderWarning() {
        return ServerState.borderWarning();
    }

    /** Whether the classic Far Lands generator is on (see {@code farlands_style}). */
    public static boolean classicFarLands() {
        return ServerState.classicFarLands();
    }

    /** The world's block coordinate limit; vanilla is 30,000,000 (see {@code max_coordinate}). */
    public static int limit() {
        return ServerState.worldLimit();
    }

    /** The world border's largest centre / half size: the limit minus 16 (vanilla: 29,999,984). */
    public static int border() {
        return ServerState.worldLimit() - 16;
    }

    /** {@link #limit()} for widened code (the long twin, see {@code asm/PositionWidening}): past the int range in 3.5.4. */
    public static long limit64() {
        return ServerState.worldLimit64();
    }

    public static long border64() {
        return ServerState.worldLimit64() - 16;
    }

    /** The limit for double positions (entities, the border): the 64-bit limit, the same as {@link #limit()} below 2^31. */
    public static double limitD() {
        return ServerState.worldLimit64();
    }

    public static double borderD() {
        return ServerState.worldLimit64() - 16;
    }
}
