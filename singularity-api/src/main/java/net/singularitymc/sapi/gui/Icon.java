package net.singularitymc.sapi.gui;

import java.util.List;
import java.util.Objects;

/**
 * What one slot of a {@link Menu} shows: an item used as a picture, with a name and lines of text under it.
 * {@code item} is an item id such as {@code "minecraft:clock"}; name and lore take {@code §} colour codes
 * ({@link net.singularitymc.sapi.text.Text#colorize}).
 */
public record Icon(String item, String name, List<String> lore, int count, boolean glow) {
    public Icon {
        Objects.requireNonNull(item, "item");
        name = name == null ? "" : name;
        lore = lore == null ? List.of() : List.copyOf(lore);
        count = Math.max(1, Math.min(99, count));
    }

    public static Icon of(String item, String name, String... lore) {
        return new Icon(item, name, List.of(lore), 1, false);
    }

    public static Icon of(String item, String name, List<String> lore) {
        return new Icon(item, name, lore, 1, false);
    }

    /** The number shown on the item (1 to 99). */
    public Icon count(int count) {
        return new Icon(item, name, lore, count, glow);
    }

    /** With the shimmer of an enchanted item: for "on" and "selected". */
    public Icon glow(boolean glow) {
        return new Icon(item, name, lore, count, glow);
    }
}
