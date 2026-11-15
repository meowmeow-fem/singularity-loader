package net.singularitymc.sapi.internal;

import net.singularitymc.sapi.entity.Player;
import net.singularitymc.sapi.gui.Form;

/** Where {@link Form} finds the game-side implementation (set by the Minecraft backend when it starts). */
public final class Forms {
    public interface Shower {
        void show(Form form, Player player);

        void close(Player player);
    }

    private static volatile Shower shower;

    private Forms() {}

    public static void setShower(Shower s) {
        shower = s;
    }

    public static void show(Form form, Player player) {
        Shower s = shower;
        if (s == null) {
            throw new IllegalStateException("Forms need a running server");
        }
        s.show(form, player);
    }

    public static void close(Player player) {
        Shower s = shower;
        if (s != null) {
            s.close(player);
        }
    }
}
