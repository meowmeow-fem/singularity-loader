package net.singularitymc.servermanager;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.singularitymc.sapi.command.CommandException;
import net.singularitymc.sapi.command.CommandSender;
import net.singularitymc.sapi.entity.GameMode;
import net.singularitymc.sapi.entity.Player;
import net.singularitymc.sapi.event.player.PlayerChatEvent;
import net.singularitymc.sapi.event.player.PlayerJoinEvent;
import net.singularitymc.sapi.event.server.ServerTickEvent;
import net.singularitymc.sapi.plugin.JavaPlugin;
import net.singularitymc.sapi.scheduler.Task;
import net.singularitymc.sapi.text.Text;

/**
 * Server Manager: the everyday tools of a server owner, written against the Singularity plugin API only (no
 * Minecraft classes), so it runs on every game version the API mod supports.
 */
public final class ServerManagerPlugin extends JavaPlugin {

    private static final String STAFF = "servermanager.staffchat";
    private static final String BYPASS = "servermanager.maintenance.bypass";
    private static final long FOREVER = Long.MAX_VALUE;

    private final TickClock clock = new TickClock();
    private final Dashboard dashboard = new Dashboard(this);
    /** Player UUID to the wall-clock millisecond the mute ends ({@link #FOREVER} = until /unmute). */
    private final Map<UUID, Long> mutes = new ConcurrentHashMap<>();
    private Task announcer;
    private Task countdown;
    private int nextAnnouncement;
    private long lastLagAlert;

    @Override
    public void onEnable() {
        loadMutes();

        command("tps").setExecutor(this::tps);
        command("online").setExecutor(this::online);
        command("broadcast").setExecutor(this::broadcast);
        command("staffchat").setExecutor(this::staffChat);
        command("maintenance").setExecutor(this::maintenance);
        command("maintenance").setCompleter((s, l, a) -> a.length == 1 ? List.of("on", "off") : List.of());
        command("mute").setExecutor(this::mute);
        command("mute").setCompleter(this::playerNames);
        command("unmute").setExecutor(this::unmute);
        command("unmute").setCompleter(this::playerNames);
        command("kickall").setExecutor(this::kickAll);
        command("shutdown").setExecutor(this::shutdown);
        command("tpto").setExecutor(this::tpTo);
        command("tpto").setCompleter(this::playerNames);
        command("tphere").setExecutor(this::tpHere);
        command("tphere").setCompleter(this::playerNames);
        command("gm").setExecutor(this::gameMode);
        command("gm").setCompleter((s, l, a) -> a.length == 1
                ? List.of("survival", "creative", "adventure", "spectator") : playerNames(s, l, a));
        command("servermanager").setExecutor(this::admin);
        command("servermanager").setCompleter((s, l, a) -> a.length == 1 ? List.of("reload") : List.of());
        command("dashboard").setExecutor((sender, label, args) -> {
            dashboard.open(sender.asPlayer().orElseThrow(() -> new CommandException(
                    "The dashboard is a window in the game; from the console use /tps and /online.")));
            return true;
        });
        command("singularity").setExecutor(this::about);
        scheduler().runTimer(20, 20, dashboard::refresh);

        events().listen(ServerTickEvent.class, e -> onTick());
        events().listen(PlayerJoinEvent.class, e -> onJoin(e.player()));
        events().listen(PlayerChatEvent.class, this::onChat);

        startAnnouncer();
        logger().info("Enabled{}", maintenanceOn() ? " (maintenance mode is ON: only staff can join)" : "");
    }

    @Override
    public void onDisable() {
        dashboard.close();
        saveMutes();
    }

    // ------------------------------------------------------------------------------------------------ events

    private void onTick() {
        clock.tick(System.nanoTime());
        double limit = config().getDouble("lag-alert-below-tps", 15.0);
        if (limit <= 0 || clock.ticks() < 400 || clock.ticks() % 100 != 0) {
            return;
        }
        double tps = clock.tps(10);
        long now = System.currentTimeMillis();
        if (tps < limit && now - lastLagAlert >= 60_000) {
            lastLagAlert = now;
            toStaff(Text.colorize("&c[Lag] " + one(tps) + " TPS over the last 10 seconds" + loadLevelText() + "."));
        }
    }

    private void onJoin(Player player) {
        if (maintenanceOn() && !player.hasPermission(BYPASS)) {
            String reason = config().getString("maintenance.kick-message", "The server is under maintenance.");
            // one tick later: the player is still being placed in the world while this event runs
            scheduler().runLater(1, () -> {
                if (player.isOnline()) {
                    player.kick(reason);
                }
            });
            return;
        }
        for (String line : config().getStringList("welcome")) {
            player.sendMessage(Text.colorize(line.replace("{player}", player.name())
                    .replace("{online}", Integer.toString(server().players().size()))
                    .replace("{tps}", one(clock.tps(10)))
                    .replace("{version}", server().minecraftVersion())));
        }
    }

    /** /singularity: the black hole and what runs here. */
    private boolean about(CommandSender sender, String label, String[] args) {
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) >> 20;
        List<String> beside = List.of(
                "",
                Text.colorize("&6&lSingularity &7Loader &f" + server().loaderVersion()),
                Text.colorize("&7Minecraft &f" + server().minecraftVersion()),
                Text.colorize("&7TPS " + tpsColour(clock.tps(10)) + " &8| &7Players &f" + server().players().size()),
                Text.colorize("&7Memory &f" + used + " MB &8| &7Up &f"
                        + duration(ManagementFactory.getRuntimeMXBean().getUptime() / 1000)),
                Text.colorize("&7World edge &f" + String.format(Locale.ROOT, "%,d", server().maxCoordinate()) + " &7blocks"));
        for (String line : Banner.lines(beside)) {
            sender.sendMessage(line);
        }
        return true;
    }

    TickClock clock() {
        return clock;
    }

    boolean isMuted(Player player) {
        Long until = mutes.get(player.uuid());
        return until != null && (until == FOREVER || until > System.currentTimeMillis());
    }

    boolean shutdownRunning() {
        return countdown != null;
    }

    private void onChat(PlayerChatEvent event) {
        Player player = event.player();
        Long until = mutes.get(player.uuid());
        if (until == null) {
            return;
        }
        if (until != FOREVER && until <= System.currentTimeMillis()) {
            mutes.remove(player.uuid());
            return;
        }
        event.setCancelled(true);
        String left = until == FOREVER ? "" : " &7(" + duration((until - System.currentTimeMillis()) / 1000) + " left)";
        player.sendMessage(Text.colorize(config().getString("mute.message", "&cYou are muted.") + left));
    }

    // ---------------------------------------------------------------------------------------------- commands

    private boolean tps(CommandSender sender, String label, String[] args) {
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) >> 20;
        long max = rt.maxMemory() >> 20;
        sender.sendMessage(Text.colorize("&6TPS &7(10 s, 1 min, 5 min): " + tpsColour(clock.tps(10)) + ", "
                + tpsColour(clock.tps(60)) + ", " + tpsColour(clock.tps(300))));
        sender.sendMessage(Text.colorize("&6Slowest tick gap &7(last minute): &f" + one(clock.worstGapMillis(60)) + " ms"));
        sender.sendMessage(Text.colorize("&6Memory: &f" + used + " MB &7of " + max + " MB (" + used * 100 / Math.max(1, max)
                + " %)"));
        sender.sendMessage(Text.colorize("&6Players: &f" + server().players().size() + "&7, uptime &f"
                + duration(ManagementFactory.getRuntimeMXBean().getUptime() / 1000) + "&7" + loadLevelText()));
        return true;
    }

    private boolean online(CommandSender sender, String label, String[] args) {
        var players = server().players();
        sender.sendMessage(Text.colorize("&6" + players.size() + " player(s) online"));
        for (Player p : players) {
            String muted = mutes.containsKey(p.uuid()) ? " &c(muted)" : "";
            sender.sendMessage(Text.colorize("&f" + p.name() + " &7" + p.gameMode().name().toLowerCase(Locale.ROOT)
                    + ", " + p.world().id() + " at " + p.blockLocation() + muted));
        }
        return true;
    }

    private boolean broadcast(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            return false;
        }
        server().broadcast(Text.colorize(config().getString("broadcast-prefix", "&6[Server]&r ") + String.join(" ", args)));
        return true;
    }

    private boolean staffChat(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            return false;
        }
        String prefix = config().getString("staffchat-format", "&d[Staff] {player}:&r ").replace("{player}", sender.name());
        toStaff(Text.colorize(prefix) + String.join(" ", args));
        return true;
    }

    private boolean maintenance(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Maintenance mode is " + (maintenanceOn() ? "on" : "off") + ".");
            return true;
        }
        boolean on;
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "on" -> on = true;
            case "off" -> on = false;
            default -> {
                return false;
            }
        }
        config().set("maintenance.enabled", on);
        config().save();
        int kicked = on ? kickNonStaff(config().getString("maintenance.kick-message", "The server is under maintenance.")) : 0;
        String text = "Maintenance mode is now " + (on ? "on" : "off") + (on ? " (" + kicked + " player(s) kicked)." : ".");
        sender.sendMessage(text);
        logger().info("{} by {}", text, sender.name());
        return true;
    }

    private boolean mute(CommandSender sender, String label, String[] args) {
        if (args.length < 1 || args.length > 2) {
            return false;
        }
        Player target = find(args[0]);
        long until = FOREVER;
        if (args.length == 2) {
            long minutes = number(args[1], 1, 525_600);
            until = System.currentTimeMillis() + minutes * 60_000;
        }
        mutes.put(target.uuid(), until);
        saveMutes();
        String forHow = until == FOREVER ? "until unmuted" : "for " + args[1] + " minute(s)";
        sender.sendMessage("Muted " + target.name() + " " + forHow + ".");
        target.sendMessage(Text.colorize("&cYou have been muted " + forHow + "."));
        logger().info("{} muted {} {}", sender.name(), target.name(), forHow);
        return true;
    }

    private boolean unmute(CommandSender sender, String label, String[] args) {
        if (args.length != 1) {
            return false;
        }
        Player target = find(args[0]);
        if (mutes.remove(target.uuid()) == null) {
            throw new CommandException(target.name() + " is not muted.");
        }
        saveMutes();
        sender.sendMessage("Unmuted " + target.name() + ".");
        target.sendMessage(Text.colorize("&aYou can chat again."));
        return true;
    }

    private boolean kickAll(CommandSender sender, String label, String[] args) {
        String reason = args.length == 0 ? "Kicked by an operator." : String.join(" ", args);
        int kicked = kickNonStaff(reason);
        sender.sendMessage("Kicked " + kicked + " player(s).");
        logger().info("{} kicked {} player(s): {}", sender.name(), kicked, reason);
        return true;
    }

    private boolean shutdown(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            return false;
        }
        if (args[0].equalsIgnoreCase("cancel")) {
            if (countdown == null) {
                throw new CommandException("No shutdown is running.");
            }
            countdown.cancel();
            countdown = null;
            announce("&aThe shutdown was cancelled.");
            return true;
        }
        if (countdown != null) {
            throw new CommandException("A shutdown is already running; \"/shutdown cancel\" stops it.");
        }
        int[] left = {(int) number(args[0], 0, 86_400)};
        String reason = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : "";
        logger().info("{} started a shutdown in {} second(s){}", sender.name(), left[0], reason.isEmpty() ? "" : ": " + reason);
        countdown = scheduler().runTimer(0, 20, () -> {
            int s = left[0]--;
            if (s <= 0) {
                countdown.cancel();
                countdown = null;
                String kick = reason.isEmpty() ? "The server is restarting." : reason;
                for (Player p : List.copyOf(server().players())) {
                    p.kick(kick);
                }
                server().dispatchCommand("stop");
            } else if (s <= 5 || s == 10 || s == 30 || s % 60 == 0) {
                announce("&cThe server stops in " + duration(s) + (reason.isEmpty() ? "." : ": " + reason));
            }
        });
        return true;
    }

    private boolean tpTo(CommandSender sender, String label, String[] args) {
        if (args.length != 1) {
            return false;
        }
        Player self = sender.asPlayer().orElseThrow(() -> new CommandException("Only players can teleport."));
        Player target = find(args[0]);
        if (!self.teleport(target.location())) {
            throw new CommandException("Could not teleport to " + target.name() + ".");
        }
        sender.sendMessage("Teleported to " + target.name() + ".");
        return true;
    }

    private boolean tpHere(CommandSender sender, String label, String[] args) {
        if (args.length != 1) {
            return false;
        }
        Player self = sender.asPlayer().orElseThrow(() -> new CommandException("Only players can teleport others to them."));
        Player target = find(args[0]);
        if (!target.teleport(self.location())) {
            throw new CommandException("Could not teleport " + target.name() + ".");
        }
        sender.sendMessage("Teleported " + target.name() + " to you.");
        return true;
    }

    private boolean gameMode(CommandSender sender, String label, String[] args) {
        if (args.length < 1 || args.length > 2) {
            return false;
        }
        GameMode mode = switch (args[0].toLowerCase(Locale.ROOT)) {
            case "s", "0" -> GameMode.SURVIVAL;
            case "c", "1" -> GameMode.CREATIVE;
            case "a", "2" -> GameMode.ADVENTURE;
            case "sp", "3" -> GameMode.SPECTATOR;
            default -> GameMode.parse(args[0]);
        };
        if (mode == null) {
            return false;
        }
        Player target = args.length == 2 ? find(args[1])
                : sender.asPlayer().orElseThrow(() -> new CommandException("Name a player: /gm <mode> <player>"));
        target.setGameMode(mode);
        sender.sendMessage(target.name() + " is now in " + mode.name().toLowerCase(Locale.ROOT) + " mode.");
        return true;
    }

    private boolean admin(CommandSender sender, String label, String[] args) {
        if (args.length != 1 || !args[0].equalsIgnoreCase("reload")) {
            return false;
        }
        config().reload();
        startAnnouncer();
        sender.sendMessage("Server Manager settings reloaded.");
        return true;
    }

    // ----------------------------------------------------------------------------------------------- helpers

    private void startAnnouncer() {
        if (announcer != null) {
            announcer.cancel();
            announcer = null;
        }
        long minutes = config().getLong("announce-every-minutes", 0);
        if (minutes <= 0 || config().getStringList("announcements").isEmpty()) {
            return;
        }
        long period = minutes * 60 * 20;
        announcer = scheduler().runTimer(period, period, () -> {
            List<String> lines = config().getStringList("announcements");
            if (!lines.isEmpty() && !server().players().isEmpty()) {
                announce(lines.get(nextAnnouncement++ % lines.size()));
            }
        });
    }

    private void announce(String message) {
        server().broadcast(Text.colorize(config().getString("broadcast-prefix", "&6[Server]&r ") + message));
    }

    private void toStaff(String message) {
        for (Player p : server().players()) {
            if (p.hasPermission(STAFF)) {
                p.sendMessage(message);
            }
        }
        logger().info("{}", Text.strip(message));
    }

    private int kickNonStaff(String reason) {
        int kicked = 0;
        for (Player p : List.copyOf(server().players())) {
            if (!p.hasPermission(BYPASS)) {
                p.kick(reason);
                kicked++;
            }
        }
        return kicked;
    }

    boolean maintenanceOn() {
        return config().getBoolean("maintenance.enabled", false);
    }

    private Player find(String name) {
        return server().player(name).orElseThrow(() -> new CommandException("No player named '" + name + "' is online."));
    }

    private List<String> playerNames(CommandSender sender, String label, String[] args) {
        return args.length == 1 ? server().players().stream().map(Player::name).toList() : List.of();
    }

    private static long number(String text, long min, long max) {
        try {
            long n = Long.parseLong(text);
            if (n >= min && n <= max) {
                return n;
            }
        } catch (NumberFormatException ignored) {
            // reported below
        }
        throw new CommandException("'" + text + "' must be a whole number from " + min + " to " + max + ".");
    }

    /** The loader's adaptive load level, when the loader has one (4.2 and later). */
    static String loadLevelText() {
        try {
            int level = net.singularitymc.loader.util.Perf.level();
            return level > 0 ? ", load level " + level : "";
        } catch (Throwable olderLoader) {
            return "";
        }
    }

    static String tpsColour(double tps) {
        return (tps >= 19 ? "&a" : tps >= 15 ? "&e" : "&c") + one(tps) + "&7";
    }

    static String one(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    static String duration(long seconds) {
        if (seconds < 60) {
            return seconds + " s";
        }
        if (seconds < 3600) {
            return seconds / 60 + " min" + (seconds % 60 == 0 ? "" : " " + seconds % 60 + " s");
        }
        if (seconds < 86_400) {
            return seconds / 3600 + " h " + seconds % 3600 / 60 + " min";
        }
        return seconds / 86_400 + " d " + seconds % 86_400 / 3600 + " h";
    }

    // mutes.txt: one "uuid endMillis" per line, so mutes outlive a restart
    private Path mutesFile() {
        return dataFolder().resolve("mutes.txt");
    }

    private void loadMutes() {
        Path file = mutesFile();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length == 2) {
                    long until = Long.parseLong(parts[1]);
                    if (until > now) {
                        mutes.put(UUID.fromString(parts[0]), until);
                    }
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            logger().warn("Could not read {}: {}", file, e.toString());
        }
    }

    private void saveMutes() {
        List<String> lines = new ArrayList<>();
        mutes.forEach((uuid, until) -> lines.add(uuid + " " + until));
        try {
            Files.createDirectories(dataFolder());
            Files.write(mutesFile(), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger().warn("Could not write {}: {}", mutesFile(), e.toString());
        }
    }
}
