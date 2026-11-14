package net.singularitymc.sapi.entity;

import java.util.Locale;

public enum GameMode {
    SURVIVAL, CREATIVE, ADVENTURE, SPECTATOR;

    /** {@code survival}, {@code creative}, ... (case-insensitive), or {@code null} if it is none of them. */
    public static GameMode parse(String name) {
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
