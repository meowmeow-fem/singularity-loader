package net.singularitymc.anticheat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ChecksTest {

    /** Vertical speed per tick of the game's free fall, starting at {@code v}: (v - 0.08) * 0.98 every tick. */
    private static double[] freeFall(double v, int ticks) {
        double[] dys = new double[ticks];
        for (int i = 0; i < ticks; i++) {
            dys[i] = v;
            v = (v - 0.08) * 0.98;
        }
        return dys;
    }

    private static int flyFlags(double[] dys) {
        Checks.Fly fly = new Checks.Fly();
        int flags = 0;
        for (double dy : dys) {
            if (fly.air(dy)) {
                flags++;
            }
        }
        return flags;
    }

    @Test
    void jumpAndLongFallAreNotFlying() {
        assertEquals(0, flyFlags(freeFall(0.42, 12)), "a jump");
        assertEquals(0, flyFlags(freeFall(0.0, 400)), "walking off a cliff, down to terminal speed");
        assertEquals(0, flyFlags(freeFall(1.5, 200)), "thrown up by a wind charge");
        assertEquals(0, flyFlags(freeFall(3.0, 300)), "a slime bounce after a long fall");
    }

    @Test
    void hoveringClimbingAndGlidingAreFlying() {
        double[] hover = new double[60];
        assertEquals(5, flyFlags(hover), "hovering: one flag per 12 packets");

        double[] up = new double[24];
        java.util.Arrays.fill(up, 0.3);
        assertEquals(2, flyFlags(up), "flying up");

        double[] glide = new double[24];
        java.util.Arrays.fill(glide, -0.1);
        assertEquals(2, flyFlags(glide), "sinking slowly at a steady rate");
    }

    @Test
    void oneImpulseInTheAirFlagsAtMostOnce() {
        // falling for 15 ticks, then thrown up again (wind charge in mid-air)
        double[] fall = freeFall(0.0, 15);
        double[] again = freeFall(1.2, 80);
        double[] all = new double[fall.length + again.length];
        System.arraycopy(fall, 0, all, 0, fall.length);
        System.arraycopy(again, 0, all, fall.length, again.length);
        assertTrue(flyFlags(all) <= 1);
    }

    @Test
    void resetStartsTheAirTimeOver() {
        Checks.Fly fly = new Checks.Fly();
        for (int i = 0; i < 11; i++) {
            assertFalse(fly.air(0));
        }
        fly.reset(); // touched the ground
        for (int i = 0; i < 11; i++) {
            assertFalse(fly.air(0));
        }
        assertTrue(fly.air(0));
    }

    @Test
    void speedWindowAverages() {
        Checks.Speed speed = new Checks.Speed();
        for (int i = 0; i < 9; i++) {
            assertFalse(speed.add(0.28, 0));
        }
        assertTrue(speed.add(0.28, 0));
        assertEquals(0.28, speed.average, 1e-9);
        assertFalse(speed.wasVertical);

        for (int i = 0; i < 9; i++) {
            speed.add(0.9, i == 3 ? 0.42 : 0);
        }
        assertTrue(speed.add(0.9, 0));
        assertEquals(0.9, speed.average, 1e-9);
        assertTrue(speed.wasVertical, "one packet of the window went up");
    }

    @Test
    void speedLimits() {
        // sprinting on flat ground, sprint-jumping, and the same with Speed II (attribute 0.13 * 1.4)
        assertTrue(0.281 < Checks.speedLimit(0.37, 0.62, false, 0.13, 1));
        assertTrue(0.36 < Checks.speedLimit(0.37, 0.62, true, 0.13, 1));
        assertTrue(0.281 * 1.4 < Checks.speedLimit(0.37, 0.62, false, 0.182, 1));
        // walking (attribute 0.1) does not lower the limit: the player may have stopped sprinting a moment ago
        assertEquals(0.37, Checks.speedLimit(0.37, 0.62, false, 0.1, 1), 1e-9);
        assertEquals(0.74, Checks.speedLimit(0.37, 0.62, false, 0.13, 2.0), 1e-9);
        // a speed hack at twice sprinting speed is over the ground limit
        assertTrue(0.56 > Checks.speedLimit(0.37, 0.62, false, 0.13, 1));
    }

    @Test
    void timerAllowsRealTimeAndBursts() {
        Checks.Timer timer = new Checks.Timer();
        long now = 1_000_000;
        for (int i = 0; i < 2000; i++) { // 100 seconds at exactly 20 packets a second
            assertFalse(timer.packet(now += 50, 750));
        }
        // the connection stalls for 2 seconds, then the 40 packets of that time arrive at once
        now += 2000;
        for (int i = 0; i < 40; i++) {
            assertFalse(timer.packet(now, 750), "burst packet " + i);
        }
    }

    @Test
    void timerCatchesAFastClient() {
        Checks.Timer timer = new Checks.Timer();
        long now = 1_000_000;
        for (int i = 0; i < 200; i++) { // ten seconds of normal play
            timer.packet(now += 50, 750);
        }
        int flags = 0;
        for (int i = 0; i < 600; i++) { // 30 packets a second (timer 1.5) for 20 seconds
            if (timer.packet(now += 33, 750)) {
                flags++;
            }
        }
        // 600 packets in 19.8 s of real time: all beyond real time plus the allowance are refused
        assertTrue(flags >= 100 && flags <= 210, "refused: " + flags);
    }

    @Test
    void timerForgivesAServerStall() {
        Checks.Timer timer = new Checks.Timer();
        long now = 1_000_000;
        for (int i = 0; i < 200; i++) {
            timer.packet(now += 50, 750);
        }
        // the server hangs for 4 seconds; afterwards it handles the 80 queued packets in one tick
        now += 4000;
        timer.serverStalled(now, 4000);
        for (int i = 0; i < 80; i++) {
            assertFalse(timer.packet(now, 750), "queued packet " + i);
        }
    }

    @Test
    void clicksCountTheLastSecond() {
        Checks.Clicks clicks = new Checks.Clicks();
        long now = 5_000;
        int hits = 0;
        for (int i = 0; i < 12; i++) { // 12 a second
            hits = clicks.hit(now += 83);
        }
        assertTrue(hits <= 13, "hits: " + hits);
        for (int i = 0; i < 40; i++) { // 40 in the same instant
            hits = clicks.hit(now);
        }
        assertTrue(hits > 20, "hits: " + hits);
        assertEquals(1, clicks.hit(now + 5_000));
    }

    @Test
    void reachGrowsWithPingUpToTheAllowance() {
        assertEquals(3.5, Checks.reachLimit(3.0, 0.5, 1.5, 0), 1e-9);
        assertEquals(4.3, Checks.reachLimit(3.0, 0.5, 1.5, 100), 1e-9);
        assertEquals(5.0, Checks.reachLimit(3.0, 0.5, 1.5, 2000), 1e-9);
    }
}
