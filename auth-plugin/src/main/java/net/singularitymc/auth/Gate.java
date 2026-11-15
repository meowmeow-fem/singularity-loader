package net.singularitymc.auth;

import java.util.regex.Pattern;
import net.singularitymc.auth.AccountStore.Account;
import net.singularitymc.auth.PremiumLookup.Result;
import net.singularitymc.sapi.login.LoginDecision;

/**
 * The rules of the login gate, as plain functions of what is known (so they can be tested one by one).
 *
 * <ol>
 * <li>A name must look like a Minecraft name.</li>
 * <li>An address or name that is locked after too many wrong passwords is turned away.</li>
 * <li>A name that is online is not let in a second time: in offline mode the game would otherwise throw the
 *     player who is already there off the server, so anyone could kick anyone by joining under their name.</li>
 * <li>An account's name must be spelt as it was registered ("Steve" cannot come back as "steve").</li>
 * <li>A premium account always goes through Mojang.</li>
 * <li>A name with a password account stays a password account: whoever registered it first keeps it.</li>
 * <li>A name without an account that Mojang knows as paid goes through Mojang: only its owner can take it.</li>
 * <li>If Mojang cannot be asked about a name without an account, the client is asked to try again: letting it
 *     in would let it register a password for a name that may belong to somebody.</li>
 * </ol>
 */
final class Gate {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private Gate() {}

    /** Everything decidable without asking Mojang; {@code null} means "ask Mojang, then {@link #afterLookup}". */
    static LoginDecision beforeLookup(String name, Account account, boolean online, long nameLockedMillis,
            long addressLockedMillis, boolean protectPaidNames) {
        if (!NAME.matcher(name).matches()) {
            return LoginDecision.deny("Names have 3 to 16 letters, digits or underscores.");
        }
        long locked = Math.max(nameLockedMillis, addressLockedMillis);
        if (locked > 0) {
            return LoginDecision.deny("Too many wrong passwords. Try again in " + minutes(locked) + ".");
        }
        if (online) {
            return LoginDecision.deny("A player with this name is already online.");
        }
        if (account != null && !account.name().equals(name)) {
            return LoginDecision.deny("This name is registered as " + account.name() + ". Join with exactly that spelling.");
        }
        if (account != null) {
            return account.premium() ? LoginDecision.mojang() : LoginDecision.offline();
        }
        return protectPaidNames ? null : LoginDecision.offline();
    }

    static LoginDecision afterLookup(Result result) {
        return switch (result) {
            case PAID -> LoginDecision.mojang();
            case FREE -> LoginDecision.offline();
            case UNKNOWN -> LoginDecision.deny("Could not check this name with Mojang right now. Please try again in a moment.");
        };
    }

    static String minutes(long millis) {
        long seconds = (millis + 999) / 1000;
        return seconds < 90 ? seconds + " seconds" : (seconds + 59) / 60 + " minutes";
    }
}
