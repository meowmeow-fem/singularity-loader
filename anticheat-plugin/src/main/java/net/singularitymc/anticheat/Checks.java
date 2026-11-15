package net.singularitymc.anticheat;

/**
 * The checks' arithmetic, free of any server type so it can be unit-tested. Everything is counted per movement
 * packet, not per server tick: a client sends one packet per client tick, so the distance in one packet is what the
 * player moved in one tick of its own, however late or bunched the packets arrive.
 */
final class Checks {
    private Checks() {}

    /** Horizontal speed: the average distance per packet over {@link #WINDOW} packets. */
    static final class Speed {
        static final int WINDOW = 10;

        private double distance;
        private int packets;
        private boolean vertical;
        /** Set by {@link #add} when it returns {@code true}. */
        double average;
        boolean wasVertical;

        /**
         * Adds one packet that changed the position. Returns {@code true} when a window of {@link #WINDOW} packets is
         * complete; {@link #average} and {@link #wasVertical} (any packet of the window went up or down) describe it.
         */
        boolean add(double horizontal, double dy) {
            distance += horizontal;
            vertical |= Math.abs(dy) > 1.0e-4;
            if (++packets < WINDOW) {
                return false;
            }
            average = distance / WINDOW;
            wasVertical = vertical;
            reset();
            return true;
        }

        void reset() {
            distance = 0;
            packets = 0;
            vertical = false;
        }
    }

    /**
     * Flying: a player with nothing under them is in free fall, and in free fall each tick's vertical speed follows
     * from the one before: {@code (previous - 0.08) * 0.98}. A packet whose vertical speed is clearly above that
     * "defies gravity". One such packet is normal: it is what a jump, a wind charge, a mace's wind burst, a slime
     * bounce, an explosion or a hit looks like, and after it the fall goes on by the same rule. Flying is many of
     * them: hovering, climbing and gliding down at a steady rate defy gravity in every packet. So the check counts
     * them over the last {@link #WINDOW} packets in the air and fails at {@link #LIMIT}; launching oneself again
     * and again (a mace on a row of mobs, wind charges every half second) stays far below that.
     */
    static final class Fly {
        static final int WINDOW = 20;
        static final int LIMIT = 8;
        /** Rounding and the game's own small corrections stay well under this. */
        static final double SLACK = 0.05;

        private final boolean[] defied = new boolean[WINDOW];
        private int airPackets;
        private int count;
        private double previous;

        /** Adds one packet in the air; {@code true} if the movement of the last packets cannot be falling. */
        boolean air(double dy) {
            int slot = airPackets % WINDOW;
            // the first packet in the air has no fall before it to compare with
            boolean now = airPackets > 0 && dy > (previous - 0.08) * 0.98 + SLACK;
            previous = dy;
            airPackets++;
            if (defied[slot]) {
                count--;
            }
            defied[slot] = now;
            if (now) {
                count++;
            }
            if (count < LIMIT) {
                return false;
            }
            reset(); // judge the next packets on their own
            return true;
        }

        void reset() {
            airPackets = 0;
            count = 0;
            java.util.Arrays.fill(defied, false);
        }

        int airPackets() {
            return airPackets;
        }
    }

    /**
     * Timer: every packet is worth 50 ms of game time. The balance is how far the packets are ahead of the real
     * time that passed; it cannot fall below {@link #FLOOR}, which is what a connection may "save up" while it
     * stalls, so that the burst after a lag spike is not counted.
     */
    static final class Timer {
        static final double FLOOR = -3000;
        private long last = Long.MIN_VALUE;
        private double balance = FLOOR;

        /**
         * Adds one packet received at {@code nowMs}. Returns {@code true} if it would put the client more than
         * {@code maxAhead} ms ahead of real time; such a packet is not counted (the caller is expected to drop it),
         * so a fast client is held to real time once its allowance is used up.
         */
        boolean packet(long nowMs, double maxAhead) {
            long elapsed = last == Long.MIN_VALUE ? 50 : nowMs - last;
            last = nowMs;
            double next = balance + 50 - elapsed;
            if (next > maxAhead) {
                balance -= elapsed;
                return true;
            }
            // the floor stops the balance from sinking, it never lifts one that a server stall put below it
            balance = next >= FLOOR ? next : Math.max(next, Math.min(balance, FLOOR));
            return false;
        }

        /** The server itself stood still for {@code millis}: the packets of that time arrive at once and are fine. */
        void serverStalled(long nowMs, long millis) {
            balance = Math.min(balance, 0) - millis;
            last = nowMs;
        }

        double balance() {
            return balance;
        }
    }

    /** Click rate: hits within the last second. */
    static final class Clicks {
        private final long[] times = new long[64];
        private int count;

        /** Adds a hit at {@code nowMs}; returns how many hits (this one included) fell into the last 1000 ms. */
        int hit(long nowMs) {
            times[count++ % times.length] = nowMs;
            int recent = 0;
            for (int i = 0; i < Math.min(count, times.length); i++) {
                if (nowMs - times[i] < 1000) {
                    recent++;
                }
            }
            return recent;
        }
    }

    /** The horizontal speed limit for a window. {@code speedAttribute} is 0.1 walking, 0.13 sprinting, more with Speed. */
    static double speedLimit(double maxOnGround, double maxJumping, boolean vertical, double speedAttribute, double iceFactor) {
        double base = vertical ? maxJumping : maxOnGround;
        return base * Math.max(1.0, speedAttribute / 0.13) * Math.max(1.0, iceFactor);
    }

    /** The farthest a hit may come from. */
    static double reachLimit(double reach, double tolerance, double pingAllowance, int pingMs) {
        // a target running at 8 blocks per second is 0.008 blocks per millisecond of ping from where the client saw it
        return reach + tolerance + Math.min(pingAllowance, Math.max(0, pingMs) * 0.008);
    }
}
