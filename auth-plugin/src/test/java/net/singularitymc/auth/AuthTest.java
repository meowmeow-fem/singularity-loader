package net.singularitymc.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.singularitymc.auth.AccountStore.Account;
import net.singularitymc.auth.PremiumLookup.Result;
import net.singularitymc.sapi.login.LoginDecision;
import net.singularitymc.sapi.login.LoginDecision.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuthTest {
    /** Few rounds: the tests check behaviour, not strength. */
    private static final int ROUNDS = 1000;

    // ---- passwords ----

    @Test
    void rightPasswordVerifiesWrongOneDoesNot() {
        String stored = Passwords.hash("correct horse battery", ROUNDS);
        assertTrue(Passwords.verify("correct horse battery", stored));
        assertFalse(Passwords.verify("correct horse batterz", stored));
        assertFalse(Passwords.verify("", stored));
    }

    @Test
    void hashesAreSaltedAndHoldNoPassword() {
        String a = Passwords.hash("hunter2hunter2", ROUNDS);
        String b = Passwords.hash("hunter2hunter2", ROUNDS);
        assertNotEquals(a, b, "the same password twice gives different hashes");
        assertFalse(a.contains("hunter2"));
        assertTrue(a.startsWith("pbkdf2-sha256$" + ROUNDS + "$"));
    }

    @Test
    void damagedOrForeignHashesMatchNothing() {
        assertFalse(Passwords.verify("x", ""));
        assertFalse(Passwords.verify("x", null));
        assertFalse(Passwords.verify("x", "plain$x"));
        assertFalse(Passwords.verify("x", "pbkdf2-sha256$abc$AAAA$AAAA"));
        assertFalse(Passwords.verify("x", "pbkdf2-sha256$1000$!!!$AAAA"));
        assertFalse(Passwords.verify("x", "pbkdf2-sha256$999999999999$AAAA$AAAA"));
    }

    @Test
    void olderHashesAreFoundForUpgrading() {
        String stored = Passwords.hash("some password", ROUNDS);
        assertFalse(Passwords.outdated(stored, ROUNDS));
        assertTrue(Passwords.outdated(stored, ROUNDS * 2));
    }

    @Test
    void weakPasswordsAreRefused() {
        assertNotNull(Passwords.problem("short", "Steve", 8));
        assertNotNull(Passwords.problem("password123", "Steve", 8));
        assertNotNull(Passwords.problem("aaaaaaaaaa", "Steve", 8));
        assertNotNull(Passwords.problem("xxSTEVExx99", "Steve", 8), "contains the name");
        assertNotNull(Passwords.problem("x".repeat(65), "Steve", 8));
        assertNull(Passwords.problem("river-Lamp-42", "Steve", 8));
    }

    // ---- accounts ----

    private static Account cracked(String name) {
        return new Account(name, false, "", Passwords.hash("river-Lamp-42", ROUNDS), 1000, 2000, "aa", "bb");
    }

    @Test
    void accountsSurviveARestart(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("accounts.tsv");
        AccountStore store = new AccountStore(file);
        store.load();
        store.put(cracked("Steve"));
        store.put(new Account("Alex", true, "069a79f4-44e9-4726-a5be-fca90e38aaf5", "", 5, 6, "cc", "dd"));

        AccountStore again = new AccountStore(file);
        again.load();
        assertEquals(2, again.size());
        assertEquals(1, again.premiumCount());
        assertEquals(store.get("Steve"), again.get("Steve"));
        assertEquals(store.get("Alex"), again.get("Alex"));
        assertTrue(Passwords.verify("river-Lamp-42", again.get("Steve").hash()));
    }

    @Test
    void namesAreOneAccountWhateverTheCase(@TempDir Path dir) throws IOException {
        AccountStore store = new AccountStore(dir.resolve("accounts.tsv"));
        store.put(cracked("Steve"));
        assertEquals("Steve", store.get("sTEVE").name());
        store.put(cracked("STEVE"));
        assertEquals(1, store.size());
        assertTrue(store.remove("steve"));
        assertNull(store.get("Steve"));
    }

    @Test
    void thePreviousFileIsKeptAndADamagedOneIsNotSilentlyEmptied(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("accounts.tsv");
        AccountStore store = new AccountStore(file);
        store.put(cracked("Steve"));
        store.put(cracked("Alex"));
        assertTrue(Files.isRegularFile(dir.resolve("accounts.tsv.bak")));
        assertFalse(Files.exists(dir.resolve("accounts.tsv.tmp")));

        Files.writeString(file, "Steve\tC\tbroken\n");
        assertThrows(IOException.class, () -> new AccountStore(file).load(),
                "a damaged file must stop the plugin, not make every name free to register");
    }

    @Test
    void registrationsAreCountedPerAddress(@TempDir Path dir) throws IOException {
        AccountStore store = new AccountStore(dir.resolve("accounts.tsv"));
        store.put(cracked("One"));
        store.put(cracked("Two"));
        store.put(new Account("Paid", true, "u", "", 1, 1, "bb", "bb"));
        assertEquals(2, store.registeredFrom("bb"), "premium accounts do not count");
        assertEquals(0, store.registeredFrom("zz"));
    }

    // ---- lockouts ----

    @Test
    void theFifthWrongPasswordLocksAndLocksGrow() {
        Throttle throttle = new Throttle(5, 300_000);
        long now = 1_000_000;
        for (int i = 0; i < 4; i++) {
            assertFalse(throttle.fail("name:steve", now));
        }
        assertEquals(0, throttle.lockedFor("name:steve", now));
        assertTrue(throttle.fail("name:steve", now));
        assertEquals(300_000, throttle.lockedFor("name:steve", now));
        assertEquals(0, throttle.lockedFor("name:alex", now), "other names are not affected");
        assertEquals(0, throttle.lockedFor("name:steve", now + 300_000), "it runs out");

        now += 400_000;
        for (int i = 0; i < 5; i++) {
            throttle.fail("name:steve", now);
        }
        assertEquals(600_000, throttle.lockedFor("name:steve", now), "the second lock is twice as long");
    }

    @Test
    void aRightPasswordResetsTheCountButLocksNeverExceedAnHour() {
        Throttle throttle = new Throttle(3, 300_000);
        long now = 1_000_000;
        throttle.fail("k", now);
        throttle.fail("k", now);
        throttle.success("k");
        assertFalse(throttle.fail("k", now));
        assertFalse(throttle.fail("k", now));

        Throttle hard = new Throttle(1, 300_000);
        for (int i = 0; i < 12; i++) {
            hard.fail("k", now);
            now += 10;
        }
        assertTrue(hard.lockedFor("k", now) <= 3_600_000);
    }

    // ---- the gate ----

    private static Kind kind(LoginDecision d) {
        return d == null ? null : d.kind();
    }

    @Test
    void gateRules() {
        Account steve = cracked("Steve");
        Account paid = new Account("Alex", true, "u", "", 1, 1, "", "");

        assertEquals(Kind.DENY, kind(Gate.beforeLookup("a b", null, false, 0, 0, true)), "not a name");
        assertEquals(Kind.DENY, kind(Gate.beforeLookup("ab", null, false, 0, 0, true)), "too short");
        assertEquals(Kind.DENY, kind(Gate.beforeLookup("Steve", steve, false, 60_000, 0, true)), "name locked");
        assertEquals(Kind.DENY, kind(Gate.beforeLookup("Steve", steve, false, 0, 60_000, true)), "address locked");
        assertEquals(Kind.DENY, kind(Gate.beforeLookup("Steve", steve, true, 0, 0, true)), "already online");
        assertEquals(Kind.DENY, kind(Gate.beforeLookup("steve", steve, false, 0, 0, true)), "wrong spelling");
        assertEquals(Kind.OFFLINE, kind(Gate.beforeLookup("Steve", steve, false, 0, 0, true)), "password account");
        assertEquals(Kind.MOJANG, kind(Gate.beforeLookup("Alex", paid, false, 0, 0, true)), "premium account");
        assertEquals(Kind.MOJANG, kind(Gate.beforeLookup("Alex", paid, false, 0, 0, false)), "premium stays premium");
        assertNull(Gate.beforeLookup("Newcomer", null, false, 0, 0, true), "unknown name: ask Mojang");
        assertEquals(Kind.OFFLINE, kind(Gate.beforeLookup("Newcomer", null, false, 0, 0, false)), "protection off");
    }

    @Test
    void whatMojangSaysDecidesForUnknownNames() {
        assertEquals(Kind.MOJANG, Gate.afterLookup(Result.PAID).kind());
        assertEquals(Kind.OFFLINE, Gate.afterLookup(Result.FREE).kind());
        assertEquals(Kind.DENY, Gate.afterLookup(Result.UNKNOWN).kind(), "when Mojang cannot be asked, nobody slips in");

        assertEquals(Result.PAID, PremiumLookup.fromStatus(200));
        assertEquals(Result.FREE, PremiumLookup.fromStatus(404));
        assertEquals(Result.FREE, PremiumLookup.fromStatus(204));
        assertEquals(Result.UNKNOWN, PremiumLookup.fromStatus(429));
        assertEquals(Result.UNKNOWN, PremiumLookup.fromStatus(503));
    }
}
