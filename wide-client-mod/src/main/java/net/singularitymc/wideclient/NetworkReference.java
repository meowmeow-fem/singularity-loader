package net.singularitymc.wideclient;

/**
 * Where the local player is, as seen from the network thread. Block and section positions arrive in vanilla's wrapped
 * format and are moved next to this point. After a teleport the server sends the new area right behind the
 * teleport packet, before the main thread has moved the player, so the last decoded teleport target is used until
 * the player is actually near it.
 */
public final class NetworkReference {
    /** Farther than any real movement between two packets, much nearer than the 2^25-block alias distance. */
    private static final double CAUGHT_UP = 1 << 20;

    private static volatile boolean teleportPending;
    private static volatile double teleportX;
    private static volatile double teleportZ;

    private NetworkReference() {}

    public static void onTeleport(double x, double z) {
        teleportX = x;
        teleportZ = z;
        teleportPending = true;
    }

    /**
     * The reference X/Z for decoding, or {@code null} if nothing is known yet.
     *
     * @param hasPlayer whether the local player exists
     */
    public static double[] get(boolean hasPlayer, double playerX, double playerZ) {
        if (teleportPending) {
            double tx = teleportX;
            double tz = teleportZ;
            if (!hasPlayer || Math.abs(playerX - tx) > CAUGHT_UP || Math.abs(playerZ - tz) > CAUGHT_UP) {
                return new double[] {tx, tz};
            }
            teleportPending = false;
        }
        return hasPlayer ? new double[] {playerX, playerZ} : null;
    }
}
