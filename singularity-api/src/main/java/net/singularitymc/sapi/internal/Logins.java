package net.singularitymc.sapi.internal;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.singularitymc.sapi.login.LoginDecision;
import net.singularitymc.sapi.login.LoginGate;

/**
 * Login state shared between the game-side hooks and the API: the gate, who proved their account to Mojang, and
 * who is restricted. Everything here is read on network threads, so it is all concurrent and lock-free.
 */
public final class Logins {
    private static volatile LoginGate gate;
    /** Players of this session whose login went through Mojang's session servers. */
    private static final Set<UUID> VERIFIED = ConcurrentHashMap.newKeySet();
    /** Restricted players and the commands each may still run (lower case, without the slash). */
    private static final ConcurrentHashMap<UUID, Set<String>> RESTRICTED = new ConcurrentHashMap<>();

    private Logins() {}

    public static void setGate(LoginGate g) {
        gate = g;
    }

    public static boolean hasGate() {
        return gate != null;
    }

    /** Asks the gate; never throws and never completes exceptionally. */
    public static CompletableFuture<LoginDecision> ask(String name, String address) {
        LoginGate g = gate;
        if (g == null) {
            return CompletableFuture.completedFuture(LoginDecision.offline());
        }
        CompletableFuture<LoginDecision> asked;
        try {
            asked = g.check(name, address);
        } catch (RuntimeException e) {
            asked = null;
        }
        if (asked == null) {
            return CompletableFuture.completedFuture(LoginDecision.deny("Login check failed. Please try again."));
        }
        return asked.handle((decision, error) ->
                decision == null || error != null ? LoginDecision.deny("Login check failed. Please try again.") : decision);
    }

    public static void markVerified(UUID player) {
        VERIFIED.add(player);
    }

    public static boolean isVerified(UUID player) {
        return VERIFIED.contains(player);
    }

    public static void restrict(UUID player, Set<String> allowedCommands) {
        RESTRICTED.put(player, Set.copyOf(allowedCommands));
    }

    public static void unrestrict(UUID player) {
        RESTRICTED.remove(player);
    }

    public static boolean isRestricted(UUID player) {
        return !RESTRICTED.isEmpty() && RESTRICTED.containsKey(player);
    }

    public static boolean anyRestricted() {
        return !RESTRICTED.isEmpty();
    }

    /** Whether a restricted player may run this command line (as typed, without the slash). */
    public static boolean commandAllowed(UUID player, String commandLine) {
        Set<String> allowed = RESTRICTED.get(player);
        if (allowed == null) {
            return true;
        }
        String root = commandLine.trim();
        int space = root.indexOf(' ');
        if (space >= 0) {
            root = root.substring(0, space);
        }
        return allowed.contains(root.toLowerCase(java.util.Locale.ROOT));
    }

    /** The player left: forget them. */
    public static void forget(UUID player) {
        VERIFIED.remove(player);
        RESTRICTED.remove(player);
    }
}
