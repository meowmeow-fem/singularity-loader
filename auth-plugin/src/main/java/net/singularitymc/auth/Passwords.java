package net.singularitymc.auth;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Password hashing. Passwords are never stored: what is stored is
 * {@code pbkdf2-sha256$<rounds>$<salt>$<hash>}, PBKDF2 with HMAC-SHA-256, a random 16-byte salt per password and a
 * 32-byte result. PBKDF2 is the strongest password hash that ships with Java itself (no library to trust or to
 * keep updated); the rounds make every guess against a stolen account file cost a quarter of a second.
 */
final class Passwords {
    static final String SCHEME = "pbkdf2-sha256";
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();
    /** What people type first; refused whatever the length rule says. */
    private static final Set<String> COMMON = Set.of("password", "password1", "password123", "12345678", "123456789",
            "1234567890", "qwertyui", "qwerty123", "qwertyuiop", "11111111", "00000000", "iloveyou", "minecraft",
            "minecraft1", "minecraft123", "letmein123", "abcdefgh", "abcd1234", "1q2w3e4r", "asdfghjk", "88888888",
            "87654321", "passw0rd", "football", "dragon123", "admin123", "changeme");

    private Passwords() {}

    static String hash(String password, int rounds) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        Base64.Encoder b64 = Base64.getEncoder().withoutPadding();
        return SCHEME + "$" + rounds + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(derive(password, salt, rounds));
    }

    /** Whether {@code password} is the one {@code stored} was made from. Takes the same time right or wrong. */
    static boolean verify(String password, String stored) {
        String[] parts = stored == null ? new String[0] : stored.split("\\$");
        if (parts.length != 4 || !parts[0].equals(SCHEME)) {
            return false;
        }
        try {
            int rounds = Integer.parseInt(parts[1]);
            if (rounds < 1 || rounds > 10_000_000) {
                return false;
            }
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            return MessageDigest.isEqual(expected, derive(password, salt, rounds));
        } catch (IllegalArgumentException e) {
            return false; // a damaged entry matches nothing
        }
    }

    /** Whether a stored hash was made with fewer rounds than are wanted now (then it is redone at the next login). */
    static boolean outdated(String stored, int rounds) {
        String[] parts = stored.split("\\$");
        try {
            return parts.length != 4 || Integer.parseInt(parts[1]) < rounds;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private static byte[] derive(String password, byte[] salt, int rounds) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, rounds, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("This Java has no PBKDF2WithHmacSHA256", e);
        } finally {
            spec.clearPassword();
        }
    }

    /** Why a new password is not acceptable, or {@code null} if it is. */
    static String problem(String password, String playerName, int minLength) {
        if (password.length() < minLength) {
            return "The password needs at least " + minLength + " characters.";
        }
        if (password.length() > 64) {
            return "The password may have at most 64 characters.";
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (lower.contains(playerName.toLowerCase(Locale.ROOT))) {
            return "The password must not contain your name.";
        }
        if (COMMON.contains(lower) || lower.chars().distinct().count() < 4) {
            return "That password is too easy to guess.";
        }
        return null;
    }
}
