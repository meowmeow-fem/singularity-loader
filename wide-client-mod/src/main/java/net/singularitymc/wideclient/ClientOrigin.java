package net.singularitymc.wideclient;

import net.minecraft.resources.Identifier;

/**
 * The shift of this client's frame on a Singularity server with experimental_long_positions (3.5.6 floating origin):
 * true position = what the client sees + this. The server sends it as a custom payload whose id carries the numbers,
 * {@code singularity:origin/<x>/<z>} (vanilla clients drop it unread).
 */
public final class ClientOrigin {
    private static volatile long x;
    private static volatile long z;

    private ClientOrigin() {}

    public static long x() {
        return x;
    }

    public static long z() {
        return z;
    }

    /** Takes the origin from a payload id; false if the id is not one. */
    public static boolean accept(Identifier id) {
        if (!id.getNamespace().equals("singularity") || !id.getPath().startsWith("origin/")) {
            return false;
        }
        String[] parts = id.getPath().split("/");
        if (parts.length == 3) {
            try {
                long nx = Long.parseLong(parts[1]);
                long nz = Long.parseLong(parts[2]);
                x = nx;
                z = nz;
            } catch (NumberFormatException e) {
                // not ours after all
            }
        }
        return true;
    }

    /** A coordinate as typed into a command: numbers get the origin added, relative ones ({@code ~}, {@code ^}) stay. */
    public static String trueCoordinate(String s, long origin) {
        if (origin == 0 || s.isEmpty() || s.startsWith("~") || s.startsWith("^")) {
            return s;
        }
        try {
            if (s.contains(".")) {
                return new java.math.BigDecimal(s).add(java.math.BigDecimal.valueOf(origin)).toPlainString();
            }
            return Long.toString(Long.parseLong(s) + origin);
        } catch (NumberFormatException e) {
            return s;
        }
    }
}
