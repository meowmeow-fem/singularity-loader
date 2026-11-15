package net.singularitymc.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The account database: one text file, {@code accounts.tsv}, one account per line, kept in memory and rewritten
 * whole on every change. A rewrite goes to a temporary file first and is then moved over the old one, so a crash
 * or a full disk leaves either the old file or the new one, never half of each; the previous version is kept as
 * {@code accounts.tsv.bak}. Names are compared without regard to case: "Steve" and "steve" are one account.
 *
 * <p>Thread-safe: the login gate reads it from network threads while commands change it on other threads.
 */
final class AccountStore {
    /**
     * @param name the name exactly as registered
     * @param premium whether logins under this name go through Mojang
     * @param uuid the Mojang UUID of a premium account, else {@code ""}
     * @param hash the password hash ({@link Passwords}), {@code ""} for an account that never had one
     * @param registered when the account was created (milliseconds)
     * @param lastLogin when it last logged in
     * @param lastAddress keyed hash of the address of the last login (never the address itself)
     * @param registeredAddress keyed hash of the address it was registered from
     */
    record Account(String name, boolean premium, String uuid, String hash, long registered, long lastLogin,
            String lastAddress, String registeredAddress) {
        Account withLogin(long when, String address) {
            return new Account(name, premium, uuid, hash, registered, when, address, registeredAddress);
        }

        Account withHash(String newHash) {
            return new Account(name, premium, uuid, newHash, registered, lastLogin, lastAddress, registeredAddress);
        }

        Account withPremium(boolean nowPremium, String nowUuid) {
            return new Account(name, nowPremium, nowUuid, hash, registered, lastLogin, lastAddress, registeredAddress);
        }
    }

    private final Path file;
    private final Map<String, Account> accounts = new HashMap<>();

    AccountStore(Path file) {
        this.file = file;
    }

    static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    synchronized void load() throws IOException {
        accounts.clear();
        if (!Files.isRegularFile(file)) {
            return;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t", -1);
            if (f.length < 8) {
                throw new IOException("Damaged line in " + file + ": " + line.substring(0, Math.min(40, line.length())));
            }
            try {
                Account a = new Account(f[0], f[1].equals("P"), f[2], f[3], Long.parseLong(f[4]), Long.parseLong(f[5]), f[6], f[7]);
                accounts.put(key(a.name()), a);
            } catch (NumberFormatException e) {
                throw new IOException("Damaged line in " + file + " (account " + f[0] + ")");
            }
        }
    }

    synchronized Account get(String name) {
        return accounts.get(key(name));
    }

    synchronized int size() {
        return accounts.size();
    }

    synchronized int premiumCount() {
        int n = 0;
        for (Account a : accounts.values()) {
            if (a.premium()) {
                n++;
            }
        }
        return n;
    }

    /** How many password accounts were registered from this (hashed) address. */
    synchronized int registeredFrom(String addressHash) {
        int n = 0;
        for (Account a : accounts.values()) {
            if (!a.premium() && a.registeredAddress().equals(addressHash)) {
                n++;
            }
        }
        return n;
    }

    /** Adds or replaces an account and writes the file. */
    synchronized void put(Account account) throws IOException {
        Account before = accounts.put(key(account.name()), account);
        try {
            save();
        } catch (IOException e) {
            // memory must not run ahead of the file: what is not saved did not happen
            if (before == null) {
                accounts.remove(key(account.name()));
            } else {
                accounts.put(key(account.name()), before);
            }
            throw e;
        }
    }

    synchronized boolean remove(String name) throws IOException {
        Account before = accounts.remove(key(name));
        if (before == null) {
            return false;
        }
        try {
            save();
        } catch (IOException e) {
            accounts.put(key(name), before);
            throw e;
        }
        return true;
    }

    private void save() throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("# Singularity Auth accounts. name, P(remium)/C(racked), uuid, password hash, registered, last login,"
                + " last address (hashed), registered address (hashed). Tab-separated; do not edit while the server runs.");
        for (Account a : accounts.values()) {
            lines.add(String.join("\t", a.name(), a.premium() ? "P" : "C", a.uuid(), a.hash(), Long.toString(a.registered()),
                    Long.toString(a.lastLogin()), a.lastAddress(), a.registeredAddress()));
        }
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temp, lines, StandardCharsets.UTF_8);
        if (Files.isRegularFile(file)) {
            Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
