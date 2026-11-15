package net.singularitymc.auth;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Counts failed logins per key (a name, an address) and locks a key that fails too often. Each lock of the same
 * key is twice as long as the one before, up to an hour; a key that stays quiet for an hour starts over. This is
 * what keeps password guessing over the network slow, whatever the password hash costs.
 */
final class Throttle {
    private static final long FORGET_AFTER = 3_600_000;
    private static final long LONGEST_LOCK = 3_600_000;

    private static final class Entry {
        int failures;
        int locks;
        long lockedUntil;
        long lastSeen;
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private volatile int maxAttempts;
    private volatile long lockMillis;

    Throttle(int maxAttempts, long lockMillis) {
        configure(maxAttempts, lockMillis);
    }

    void configure(int attempts, long lock) {
        this.maxAttempts = Math.max(1, attempts);
        this.lockMillis = Math.max(1000, lock);
    }

    /** Milliseconds this key is still locked for; 0 if it is not. */
    long lockedFor(String key, long now) {
        Entry e = entries.get(key);
        if (e == null) {
            return 0;
        }
        synchronized (e) {
            return Math.max(0, e.lockedUntil - now);
        }
    }

    /** Records a failure. Returns {@code true} if this one locked the key. */
    boolean fail(String key, long now) {
        Entry e = entries.computeIfAbsent(key, k -> new Entry());
        synchronized (e) {
            if (now - e.lastSeen > FORGET_AFTER) {
                e.failures = 0;
                e.locks = 0;
            }
            e.lastSeen = now;
            if (++e.failures < maxAttempts) {
                return false;
            }
            e.failures = 0;
            long lock = Math.min(LONGEST_LOCK, lockMillis << Math.min(10, e.locks));
            e.locks++;
            e.lockedUntil = now + lock;
            return true;
        }
    }

    /** A correct login: the count starts over (a lock already earned still runs out by itself). */
    void success(String key) {
        Entry e = entries.get(key);
        if (e != null) {
            synchronized (e) {
                e.failures = 0;
            }
        }
    }

    /** Drops entries nobody has touched for an hour. */
    void prune(long now) {
        for (Iterator<Entry> it = entries.values().iterator(); it.hasNext();) {
            Entry e = it.next();
            synchronized (e) {
                if (now - e.lastSeen > FORGET_AFTER && e.lockedUntil < now) {
                    it.remove();
                }
            }
        }
    }
}
