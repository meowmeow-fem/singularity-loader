package net.singularitymc.sapi.internal;

import java.util.function.BiFunction;
import net.singularitymc.sapi.gui.Menu;

/** Where {@link Menu#create} finds the game-side implementation (set by the Minecraft backend when it starts). */
public final class Menus {
    private static volatile BiFunction<String, Integer, Menu> factory;

    private Menus() {}

    public static void setFactory(BiFunction<String, Integer, Menu> f) {
        factory = f;
    }

    public static Menu create(String title, int rows) {
        if (rows < 1 || rows > 6) {
            throw new IllegalArgumentException("A menu has 1 to 6 rows, not " + rows);
        }
        BiFunction<String, Integer, Menu> f = factory;
        if (f == null) {
            throw new IllegalStateException("Menus need a running server");
        }
        return f.apply(title == null ? "" : title, rows);
    }
}
