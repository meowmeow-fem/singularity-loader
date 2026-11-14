package net.singularitymc.sapi.internal.mc;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.singularitymc.sapi.gui.Icon;
import net.singularitymc.sapi.gui.Menu;
import net.singularitymc.sapi.internal.ApiRuntime;

/**
 * A {@link Menu} as a chest window: the icons are item stacks in a container no hopper or player can change, shown
 * through a {@link ChestMenu} whose clicks never move an item.
 */
public final class MenuImpl implements Menu {
    private static final List<MenuType<ChestMenu>> TYPES = List.of(MenuType.GENERIC_9x1, MenuType.GENERIC_9x2,
            MenuType.GENERIC_9x3, MenuType.GENERIC_9x4, MenuType.GENERIC_9x5, MenuType.GENERIC_9x6);

    private final String title;
    private final int rows;
    private final SimpleContainer container;
    private final Consumer<Click>[] handlers;
    private final List<ServerPlayer> viewers = new ArrayList<>();

    @SuppressWarnings("unchecked")
    MenuImpl(String title, int rows) {
        this.title = title;
        this.rows = rows;
        this.container = new SimpleContainer(rows * 9);
        this.handlers = new Consumer[rows * 9];
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public int rows() {
        return rows;
    }

    @Override
    public Menu set(int slot, Icon icon) {
        return set(slot, icon, null);
    }

    @Override
    public Menu set(int slot, Icon icon, Consumer<Click> onClick) {
        check(slot);
        container.setItem(slot, stack(icon));
        handlers[slot] = onClick;
        return this;
    }

    @Override
    public Menu clear(int slot) {
        check(slot);
        container.setItem(slot, ItemStack.EMPTY);
        handlers[slot] = null;
        return this;
    }

    @Override
    public Menu clear() {
        for (int i = 0; i < handlers.length; i++) {
            clear(i);
        }
        return this;
    }

    @Override
    public void open(net.singularitymc.sapi.entity.Player player) {
        ServerPlayer p = ApiRuntime.get().isServerRunning() ? PlayerImpl.handle(player) : null;
        if (p == null) {
            return;
        }
        p.openMenu(new SimpleMenuProvider((id, inventory, who) -> new View(id, inventory), Component.literal(title)));
    }

    @Override
    public void close() {
        for (ServerPlayer p : List.copyOf(viewers)) {
            p.closeContainer();
        }
    }

    @Override
    public boolean hasViewers() {
        viewers.removeIf(p -> p.hasDisconnected() || p.isRemoved());
        return !viewers.isEmpty();
    }

    private void check(int slot) {
        if (slot < 0 || slot >= handlers.length) {
            throw new IndexOutOfBoundsException("Slot " + slot + " of a menu with " + handlers.length + " slots");
        }
    }

    private static ItemStack stack(Icon icon) {
        Identifier id = Identifier.tryParse(icon.item());
        Item item = id == null ? Items.AIR : BuiltInRegistries.ITEM.getValue(id);
        if (item == Items.AIR) {
            item = Items.PAPER; // an unknown id still shows the text
        }
        ItemStack stack = new ItemStack(item, icon.count());
        // custom names and lore are drawn in italics unless told otherwise
        Style plain = Style.EMPTY.withItalic(false);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(icon.name()).setStyle(plain));
        if (!icon.lore().isEmpty()) {
            List<Component> lines = new ArrayList<>();
            for (String line : icon.lore()) {
                lines.add(Component.literal(line).setStyle(plain));
            }
            stack.set(DataComponents.LORE, new ItemLore(lines));
        }
        if (icon.glow()) {
            stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        return stack;
    }

    /** One player's open window. */
    private final class View extends ChestMenu {
        private final ServerPlayer viewer;

        View(int id, Inventory inventory) {
            super(TYPES.get(rows - 1), id, inventory, container, rows);
            this.viewer = (ServerPlayer) inventory.player;
            viewers.add(viewer);
        }

        @Override
        public void clicked(int slot, int button, ContainerInput input, net.minecraft.world.entity.player.Player player) {
            // never the game's own handling: no item moves. The client guessed otherwise, so it is sent the truth.
            setCarried(ItemStack.EMPTY);
            sendAllDataToRemote();
            if (slot < 0 || slot >= handlers.length || handlers[slot] == null || input == ContainerInput.QUICK_CRAFT) {
                return;
            }
            Consumer<Click> handler = handlers[slot];
            try {
                handler.accept(new Click(MenuImpl.this, PlayerImpl.of(viewer), slot, button == 1,
                        input == ContainerInput.QUICK_MOVE));
            } catch (RuntimeException e) {
                ApiRuntime.get().log().error("A menu click handler failed (menu '" + title + "', slot " + slot + ")", e);
            }
        }

        @Override
        public ItemStack quickMoveStack(net.minecraft.world.entity.player.Player player, int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean stillValid(net.minecraft.world.entity.player.Player player) {
            return true;
        }

        @Override
        public void removed(net.minecraft.world.entity.player.Player player) {
            viewers.remove(viewer);
            // not super.removed: it would drop the "carried" item and tell the container it was closed
        }
    }
}
