package net.singularitymc.servermanager;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.singularitymc.sapi.entity.Player;
import net.singularitymc.sapi.gui.Icon;
import net.singularitymc.sapi.gui.Menu;
import net.singularitymc.sapi.text.Text;

/**
 * The server dashboard: one chest window shared by everyone who has it open, redrawn once a second while somebody
 * looks at it. Top: the server's numbers. Middle: actions. Bottom: who is online (click to teleport, right-click to
 * mute or unmute).
 */
final class Dashboard {
    private static final int PLAYER_SLOTS_FROM = 27;
    private static final int PLAYER_SLOTS = 18;

    private final ServerManagerPlugin plugin;
    private Menu menu;

    Dashboard(ServerManagerPlugin plugin) {
        this.plugin = plugin;
    }

    void open(Player player) {
        if (menu == null) {
            menu = Menu.create(Text.colorize("&8Server dashboard"), 6);
        }
        draw();
        menu.open(player);
    }

    /** Called every second. */
    void refresh() {
        if (menu != null && menu.hasViewers()) {
            draw();
        }
    }

    void close() {
        if (menu != null) {
            menu.close();
        }
    }

    private void draw() {
        Icon pane = Icon.of("minecraft:gray_stained_glass_pane", " ");
        for (int i = 0; i < 9; i++) {
            menu.set(i, pane);
            menu.set(45 + i, pane);
        }

        TickClock clock = plugin.clock();
        double tps = clock.tps(10);
        menu.set(10, Icon.of("minecraft:clock", c("&6Tick rate"),
                c("&7Last 10 s: " + ServerManagerPlugin.tpsColour(tps) + " TPS"),
                c("&7Last minute: " + ServerManagerPlugin.tpsColour(clock.tps(60)) + " TPS"),
                c("&7Last 5 min: " + ServerManagerPlugin.tpsColour(clock.tps(300)) + " TPS"),
                c("&7Slowest tick gap (1 min): &f" + ServerManagerPlugin.one(clock.worstGapMillis(60)) + " ms"))
                .glow(tps < 15));

        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) >> 20;
        long max = rt.maxMemory() >> 20;
        long percent = used * 100 / Math.max(1, max);
        menu.set(12, Icon.of("minecraft:ender_chest", c("&6Memory"),
                c("&f" + used + " MB &7of " + max + " MB"), c(bar(percent) + " &7" + percent + " %")));

        int online = plugin.server().players().size();
        menu.set(14, Icon.of("minecraft:player_head", c("&6Players"), c("&f" + online + " &7online"))
                .count(Math.max(1, online)));

        String level = ServerManagerPlugin.loadLevelText();
        menu.set(16, Icon.of("minecraft:compass", c("&6Server"),
                c("&7Minecraft &f" + plugin.server().minecraftVersion()),
                c("&7Singularity Loader &f" + plugin.server().loaderVersion()),
                c("&7Uptime &f" + ServerManagerPlugin.duration(ManagementFactory.getRuntimeMXBean().getUptime() / 1000)),
                c(level.isEmpty() ? "&7Load level &a0 &7(not under load)" : "&7Load level&c" + level.substring(", load level".length()))));

        boolean maintenance = plugin.maintenanceOn();
        menu.set(19, Icon.of(maintenance ? "minecraft:redstone_torch" : "minecraft:lever",
                c("&6Maintenance mode: " + (maintenance ? "&con" : "&aoff")),
                c("&7While on, only staff can join."), c("&eClick to switch " + (maintenance ? "off" : "on"))).glow(maintenance),
                click -> run(click, "maintenance " + (plugin.maintenanceOn() ? "off" : "on")));

        menu.set(21, Icon.of("minecraft:bell", c("&6Broadcast"), c("&7/broadcast <message>"),
                c("&7Staff only: /staffchat <message>")));

        menu.set(23, Icon.of("minecraft:writable_book", c("&6Reload settings"), c("&eClick to re-read config.toml")),
                click -> run(click, "servermanager reload"));

        boolean stopping = plugin.shutdownRunning();
        menu.set(25, Icon.of(stopping ? "minecraft:barrier" : "minecraft:tnt",
                c(stopping ? "&cShutdown is running" : "&6Stop the server"),
                c(stopping ? "&eClick to cancel it" : "&eShift-click: stop in 60 seconds"),
                c("&7Or: /shutdown <seconds> [reason]")).glow(stopping),
                click -> {
                    if (plugin.shutdownRunning()) {
                        run(click, "shutdown cancel");
                    } else if (click.shift()) {
                        run(click, "shutdown 60 Stopped from the dashboard");
                    }
                });

        List<Player> players = new ArrayList<>(plugin.server().players());
        for (int i = 0; i < PLAYER_SLOTS; i++) {
            int slot = PLAYER_SLOTS_FROM + i;
            if (i >= players.size()) {
                menu.clear(slot);
                continue;
            }
            Player p = players.get(i);
            boolean muted = plugin.isMuted(p);
            menu.set(slot, Icon.of("minecraft:player_head", c("&f" + p.name() + (muted ? " &c(muted)" : "")),
                    c("&7" + p.world().id() + " at &f" + p.blockLocation()),
                    c("&7" + p.gameMode().name().toLowerCase(Locale.ROOT) + ", ping &f" + p.ping() + " ms"),
                    c("&eClick: teleport to"), c("&eRight-click: " + (muted ? "unmute" : "mute"))),
                    click -> {
                        if (!p.isOnline()) {
                            return;
                        }
                        if (click.rightClick()) {
                            run(click, (plugin.isMuted(p) ? "unmute " : "mute ") + p.name());
                        } else if (!click.player().equals(p)) {
                            click.player().teleport(p.location());
                        }
                    });
        }
        if (players.size() > PLAYER_SLOTS) {
            menu.set(PLAYER_SLOTS_FROM + PLAYER_SLOTS - 1, Icon.of("minecraft:paper",
                    c("&7... and " + (players.size() - PLAYER_SLOTS + 1) + " more"), c("&7/online lists everyone")));
        }

        menu.set(49, Icon.of("minecraft:barrier", c("&cClose")), click -> menu.close());
    }

    /** Runs one of the plugin's own commands for the clicking player and redraws. */
    private void run(Menu.Click click, String command) {
        plugin.logger().info("{} used the dashboard: /{}", click.player().name(), command);
        plugin.server().dispatchCommand(command);
        // the command runs with the server's next batch of commands: redraw once it has
        plugin.scheduler().runLater(2, this::refresh);
    }

    private static String bar(long percent) {
        int filled = (int) Math.min(10, percent / 10);
        String colour = percent >= 90 ? "&c" : percent >= 75 ? "&e" : "&a";
        return colour + "|".repeat(filled) + "&8" + "|".repeat(10 - filled);
    }

    private static String c(String text) {
        return Text.colorize(text);
    }
}
