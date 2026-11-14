package net.singularitymc.sapi.gui;

import java.util.function.Consumer;
import net.singularitymc.sapi.entity.Player;
import net.singularitymc.sapi.internal.Menus;

/**
 * A chest-style window of icons that a player can click (since API 4.3). It is drawn by the game's own chest screen,
 * so it works with every client, modded or not. Nothing can be taken out or put in: a click only runs the icon's
 * handler. Use it on the server thread.
 *
 * <pre>{@code
 * Menu menu = Menu.create("§8Server", 3);
 * menu.set(13, Icon.of("minecraft:clock", "§eTPS", "§720.0"), click -> click.player().sendMessage("clicked"));
 * menu.open(player);
 * }</pre>
 *
 * Changing an icon while the menu is open shows at once, so a menu can be refreshed in place.
 */
public interface Menu {

    /** A new, empty menu of {@code rows} rows of nine slots (1 to 6). Needs a running server. */
    static Menu create(String title, int rows) {
        return Menus.create(title, rows);
    }

    String title();

    int rows();

    /** Number of slots: {@code rows() * 9}; slot 0 is top left, 8 top right. */
    default int size() {
        return rows() * 9;
    }

    /** Puts a picture into a slot; clicking it does nothing. */
    Menu set(int slot, Icon icon);

    /** Puts a picture into a slot and runs {@code onClick} when a player clicks it. */
    Menu set(int slot, Icon icon, Consumer<Click> onClick);

    /** Empties one slot. */
    Menu clear(int slot);

    /** Empties every slot. */
    Menu clear();

    /** Shows the menu to a player (closing whatever window the player had open). */
    void open(Player player);

    /** Closes the menu for everyone who has it open. */
    void close();

    /** Whether at least one player has the menu open. */
    boolean hasViewers();

    /** One click on an icon. */
    record Click(Menu menu, Player player, int slot, boolean rightClick, boolean shift) {}
}
