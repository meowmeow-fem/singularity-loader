package net.singularitymc.anticheat;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.singularitymc.sapi.command.CommandSender;
import net.singularitymc.sapi.entity.GameMode;
import net.singularitymc.sapi.entity.Player;
import net.singularitymc.sapi.event.entity.EntityDamageEvent;
import net.singularitymc.sapi.event.player.PlayerAttackEvent;
import net.singularitymc.sapi.event.player.PlayerMoveEvent;
import net.singularitymc.sapi.event.player.PlayerQuitEvent;
import net.singularitymc.sapi.event.server.ServerTickEvent;
import net.singularitymc.sapi.gui.Icon;
import net.singularitymc.sapi.gui.Menu;
import net.singularitymc.sapi.plugin.JavaPlugin;
import net.singularitymc.sapi.text.Text;
import net.singularitymc.sapi.world.BlockLocation;
import net.singularitymc.sapi.world.Location;
import net.singularitymc.sapi.world.World;

/**
 * Singularity AntiCheat: server-side checks written against the plugin API only. All of it runs on the server
 * thread (the API posts its events there), so the per-player state needs no locking.
 */
public final class AntiCheatPlugin extends JavaPlugin {

    enum Check { SPEED, FLY, TIMER, REACH, CLICKS }

    /** Packets without movement checks after a teleport, respawn, hit, elytra flight and the like. */
    private static final int GRACE = 40;
    /**
     * After the anti-cheat's own setback: none. The server ignores a client's movement until the client confirms
     * the teleport, so the first packet that counts again already starts where the player was put.
     */
    private static final int SHORT_GRACE = 0;
    private static final String ALERTS = "anticheat.alerts";
    private static final String BYPASS = "anticheat.bypass";

    private static final class Data {
        final Checks.Speed speed = new Checks.Speed();
        final Checks.Fly fly = new Checks.Fly();
        final Checks.Timer timer = new Checks.Timer();
        final Checks.Clicks clicks = new Checks.Clicks();
        final EnumMap<Check, Integer> violations = new EnumMap<>(Check.class);
        final EnumMap<Check, Long> lastAlert = new EnumMap<>(Check.class);
        /** Where the last movement packet left the player; a packet that starts elsewhere follows a teleport. */
        Location last;
        /** The last position with something under the player: where a setback goes. */
        Location safe;
        /** Where the running speed window started. */
        Location windowStart;
        int grace = GRACE;
        long iceUntilTick;
        boolean exempt;
        int exemptAge;
        int timerSurplus;
    }

    private final Map<UUID, Data> players = new HashMap<>();
    private final Set<UUID> alertsOff = new HashSet<>();
    private long tick;
    private long lastTickMs;
    /** Failed checks since the server started, by {@link Check#ordinal()}. */
    private final long[] totals = new long[Check.values().length];
    private Menu menu;

    // settings, read once per reload
    private boolean speedOn, flyOn, timerOn, reachOn, clicksOn, exemptOps;
    private double maxGround, maxJumping, iceFactor, maxAheadMs, reachTolerance, pingAllowance;
    private int maxClicks, alertAt, decayTicks;
    private final EnumMap<Check, Integer> setbackAt = new EnumMap<>(Check.class);
    private final EnumMap<Check, Integer> kickAt = new EnumMap<>(Check.class);

    @Override
    public void onEnable() {
        readSettings();
        command("anticheat").setExecutor(this::command);
        command("anticheat").setCompleter((s, l, a) -> a.length == 1 ? List.of("gui", "status", "reload", "alerts") : List.of());
        events().listen(PlayerMoveEvent.class, this::onMove);
        events().listen(PlayerAttackEvent.class, this::onAttack);
        events().listen(EntityDamageEvent.class, this::onDamage);
        events().listen(PlayerQuitEvent.class, e -> players.remove(e.player().uuid()));
        events().listen(ServerTickEvent.class, e -> onTick());
        logger().info("Enabled: {}", enabledChecks());
    }

    private void readSettings() {
        var c = config();
        speedOn = c.getBoolean("speed.enabled", true);
        flyOn = c.getBoolean("fly.enabled", true);
        timerOn = c.getBoolean("timer.enabled", true);
        reachOn = c.getBoolean("reach.enabled", true);
        clicksOn = c.getBoolean("clicks.enabled", true);
        exemptOps = c.getBoolean("exempt-operators", false);
        maxGround = c.getDouble("speed.max-on-ground", 0.37);
        maxJumping = c.getDouble("speed.max-jumping", 0.62);
        iceFactor = c.getDouble("speed.ice-factor", 2.0);
        maxAheadMs = c.getDouble("timer.max-ahead-ms", 750);
        reachTolerance = c.getDouble("reach.tolerance", 0.5);
        pingAllowance = c.getDouble("reach.ping-allowance", 1.5);
        maxClicks = c.getInt("clicks.max-per-second", 20);
        alertAt = Math.max(1, c.getInt("alert-at", 2));
        decayTicks = Math.max(1, c.getInt("violation-decay-seconds", 15)) * 20;
        for (Check check : Check.values()) {
            String section = check.name().toLowerCase(Locale.ROOT);
            setbackAt.put(check, Math.max(1, c.getInt(section + ".setback-at", 2)));
            kickAt.put(check, c.getInt(section + ".kick-at", 0));
        }
    }

    private String enabledChecks() {
        StringBuilder out = new StringBuilder();
        boolean[] on = {speedOn, flyOn, timerOn, reachOn, clicksOn};
        for (Check check : Check.values()) {
            if (on[check.ordinal()]) {
                out.append(out.length() == 0 ? "" : ", ").append(check.name().toLowerCase(Locale.ROOT));
            }
        }
        return out.length() == 0 ? "no checks" : out.toString();
    }

    // ------------------------------------------------------------------------------------------------- ticks

    private void onTick() {
        tick++;
        long now = System.currentTimeMillis();
        long gap = lastTickMs == 0 ? 0 : now - lastTickMs;
        lastTickMs = now;
        if (gap > 250) {
            // the server stood still: every client's packets of that time are handled in one go
            for (Data d : players.values()) {
                d.timer.serverStalled(now, gap);
            }
        }
        if (tick % 20 == 0 && menu != null && menu.hasViewers()) {
            drawDashboard();
        }
        if (tick % decayTicks == 0) {
            for (Data d : players.values()) {
                d.violations.replaceAll((check, vl) -> Math.max(0, vl - 1));
            }
        }
    }

    // --------------------------------------------------------------------------------------------- movement

    private void onMove(PlayerMoveEvent event) {
        Player player = event.player();
        Data d = players.computeIfAbsent(player.uuid(), id -> new Data());
        Location from = event.from();
        Location to = event.to();

        // every packet counts for the timer, also one that only turns the head
        boolean exempt = isExempt(player, d);
        if (timerOn && !exempt && d.timer.packet(System.currentTimeMillis(), maxAheadMs)) {
            // one violation per second's worth of surplus packets; every surplus packet is dropped once acted on
            boolean act = d.timerSurplus++ % 20 == 0
                    ? flag(player, d, Check.TIMER, "more than " + Math.round(maxAheadMs) + " ms of packets ahead of real time")
                    : d.violations.getOrDefault(Check.TIMER, 0) >= setbackAt.getOrDefault(Check.TIMER, 2);
            if (act) {
                event.setCancelled(true);
                resetMovement(d, from, 0);
                return;
            }
        }

        if (d.last == null || !samePlace(d.last, from)) {
            // the server moved the player between two packets: teleport, respawn, leaving a vehicle
            resetMovement(d, to, GRACE);
            return;
        }
        d.last = to;
        if (exempt || movesFreely(player)) {
            resetMovement(d, to, GRACE);
            return;
        }
        if (d.grace > 0) {
            d.grace--;
            d.speed.reset();
            d.fly.reset();
            d.windowStart = to;
            if (player.isSupported()) {
                d.safe = to;
            }
            return;
        }

        double dx = to.x() - from.x();
        double dy = to.y() - from.y();
        double dz = to.z() - from.z();
        boolean liquid = player.isInLiquid();
        boolean held = liquid || player.isClimbing() || player.isSupported();

        if (flyOn) {
            if (held) {
                d.fly.reset();
            } else if (d.fly.air(dy) && !floatsLegitimately(player, to)) {
                if (flag(player, d, Check.FLY, "not falling after " + Checks.Fly.MIN_AIR + " packets in the air (vertical "
                        + three(dy) + " blocks/tick)")) {
                    setBack(event, player, d, d.safe);
                    return;
                }
            }
        }
        if (held) {
            d.safe = to;
        }

        if (speedOn && event.hasMoved()) {
            if (d.windowStart == null) {
                d.windowStart = from;
            }
            // water currents, bubble columns and swimming go by the "jumping" limit
            if (d.speed.add(Math.sqrt(dx * dx + dz * dz), liquid ? 1 : dy)) {
                if (onIce(to)) {
                    d.iceUntilTick = tick + 60;
                }
                double limit = Checks.speedLimit(maxGround, maxJumping, d.speed.wasVertical, player.movementSpeed(),
                        tick <= d.iceUntilTick ? iceFactor : 1.0);
                Location start = d.windowStart;
                d.windowStart = to;
                if (d.speed.average > limit && flag(player, d, Check.SPEED, three(d.speed.average) + " blocks/tick, limit "
                        + three(limit))) {
                    setBack(event, player, d, start);
                }
            }
        }
    }

    /** States in which the game itself moves the player in ways these checks do not model. */
    private static boolean movesFreely(Player player) {
        GameMode mode = player.gameMode();
        return mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR || player.canFly() || player.isFlying()
                || player.isGliding() || player.isRiptiding() || player.isInVehicle();
    }

    /** Looked at only when the fly check is about to fail: the rarer, costlier reasons to hang in the air. */
    private static boolean floatsLegitimately(Player player, Location at) {
        if (player.effectLevel("minecraft:levitation") > 0 || player.effectLevel("minecraft:slow_falling") > 0) {
            return true;
        }
        World world = at.world();
        BlockLocation feet = at.block();
        if (!world.isLoaded(feet)) {
            return true; // a client in an unloaded chunk sinks at a fixed slow rate
        }
        // cobwebs, powder snow, berry bushes, vines: anything but air around the body slows a fall
        return !isAir(world.getBlock(feet)) || !isAir(world.getBlock(feet.offset(0, 1, 0)));
    }

    private static boolean isAir(String block) {
        return block.endsWith("air");
    }

    private static boolean onIce(Location at) {
        World world = at.world();
        BlockLocation feet = at.block();
        for (int down = 1; down <= 2; down++) {
            String block = world.getBlock(feet.offset(0, -down, 0));
            if (block.contains("ice") || block.contains("slime")) {
                return true;
            }
        }
        return false;
    }

    private void setBack(PlayerMoveEvent event, Player player, Data d, Location target) {
        if (target != null && target.world().id().equals(event.to().world().id())) {
            player.teleport(target);
            resetMovement(d, target, SHORT_GRACE);
        } else {
            event.setCancelled(true);
            resetMovement(d, event.from(), SHORT_GRACE);
        }
    }

    /** Starts the movement checks over at {@code at}, after {@code grace} unchecked packets. */
    private static void resetMovement(Data d, Location at, int grace) {
        d.last = at;
        d.windowStart = at;
        d.grace = grace;
        d.speed.reset();
        d.fly.reset();
    }

    private static boolean samePlace(Location a, Location b) {
        return a.x() == b.x() && a.y() == b.y() && a.z() == b.z() && a.world().id().equals(b.world().id());
    }

    // ----------------------------------------------------------------------------------------------- combat

    private void onAttack(PlayerAttackEvent event) {
        Player player = event.player();
        Data d = players.computeIfAbsent(player.uuid(), id -> new Data());
        if (isExempt(player, d)) {
            return;
        }
        if (clicksOn) {
            int hits = d.clicks.hit(System.currentTimeMillis());
            if (hits > maxClicks && flag(player, d, Check.CLICKS, hits + " hits in a second, limit " + maxClicks)) {
                event.setCancelled(true);
                return;
            }
        }
        if (reachOn) {
            double limit = Checks.reachLimit(event.reach(), reachTolerance, pingAllowance, player.ping());
            if (event.distance() > limit && flag(player, d, Check.REACH, "hit from " + three(event.distance())
                    + " blocks, limit " + three(limit))) {
                event.setCancelled(true);
            }
        }
    }

    /** Knockback and explosions throw a player around: no movement checks for a moment after any damage. */
    private void onDamage(EntityDamageEvent event) {
        event.entity().asPlayer().ifPresent(p -> {
            Data d = players.get(p.uuid());
            if (d != null) {
                d.grace = GRACE;
            }
        });
    }

    // ------------------------------------------------------------------------------------------- violations

    /** Permissions cost a few lookups, so the answer is kept for 100 packets. */
    private boolean isExempt(Player player, Data d) {
        if (d.exemptAge-- <= 0) {
            d.exemptAge = 100;
            d.exempt = player.hasPermission(BYPASS) || (exemptOps && player.isOp());
        }
        return d.exempt;
    }

    /**
     * Counts a failed check. Returns whether to act on it (set the player back, drop the hit): from the check's
     * {@code setback-at} level on.
     */
    private boolean flag(Player player, Data d, Check check, String detail) {
        int vl = d.violations.merge(check, 1, Integer::sum);
        totals[check.ordinal()]++;
        String name = check.name().toLowerCase(Locale.ROOT);
        if (vl >= alertAt) {
            long last = d.lastAlert.getOrDefault(check, -1000L);
            if (tick - last >= 60) { // at most one line per player and check in 3 seconds
                d.lastAlert.put(check, tick);
                alert("&c[AC] &f" + player.name() + " &7failed &f" + name + " &7(" + detail + "), VL " + vl);
            }
        }
        int kick = kickAt.getOrDefault(check, 0);
        if (kick > 0 && vl >= kick) {
            d.violations.put(check, 0);
            alert("&c[AC] &f" + player.name() + " &7was kicked for &f" + name);
            // not from inside the event: the player is in the middle of a packet
            scheduler().runTask(() -> {
                if (player.isOnline()) {
                    player.kick("Kicked by the anti-cheat (" + name + ").");
                }
            });
        }
        return vl >= setbackAt.getOrDefault(check, 2);
    }

    private void alert(String message) {
        String text = Text.colorize(message);
        for (Player p : server().players()) {
            if (!alertsOff.contains(p.uuid()) && p.hasPermission(ALERTS)) {
                p.sendMessage(text);
            }
        }
        logger().info("{}", Text.strip(text));
    }

    // -------------------------------------------------------------------------------------------- dashboard

    private static final String[] CHECK_ICONS = {"minecraft:sugar", "minecraft:feather", "minecraft:clock",
        "minecraft:iron_sword", "minecraft:stone_button"};
    private static final String[] CHECK_TEXT = {
        "Moving faster than sprinting allows", "Hovering, flying up, gliding down slowly",
        "A client that runs faster than 20 ticks a second", "Hits from too far away", "Too many hits a second"};

    private boolean isOn(Check check) {
        return switch (check) {
            case SPEED -> speedOn;
            case FLY -> flyOn;
            case TIMER -> timerOn;
            case REACH -> reachOn;
            case CLICKS -> clicksOn;
        };
    }

    private void openDashboard(Player viewer) {
        if (menu == null) {
            menu = Menu.create(Text.colorize("&8AntiCheat dashboard"), 6);
        }
        drawDashboard();
        menu.open(viewer);
    }

    /** One window shared by everyone who has it open; redrawn once a second while somebody looks at it. */
    private void drawDashboard() {
        Icon pane = Icon.of("minecraft:gray_stained_glass_pane", " ");
        for (int i = 0; i < 9; i++) {
            menu.set(i, pane);
            menu.set(45 + i, pane);
        }
        for (Check check : Check.values()) {
            String name = check.name().toLowerCase(Locale.ROOT);
            boolean on = isOn(check);
            menu.set(10 + check.ordinal(), Icon.of(CHECK_ICONS[check.ordinal()],
                    c("&6" + name + ": " + (on ? "&aon" : "&coff")),
                    c("&7" + CHECK_TEXT[check.ordinal()]),
                    c("&7Failed &f" + totals[check.ordinal()] + " &7time(s) since the server started"),
                    c("&7Acts from VL &f" + setbackAt.get(check) + "&7, kicks " + (kickAt.get(check) > 0
                            ? "at VL &f" + kickAt.get(check) : "&fnever")),
                    c("&eClick to switch " + (on ? "off" : "on"))).glow(on),
                    click -> {
                        config().set(name + ".enabled", !isOn(check));
                        config().save();
                        readSettings();
                        logger().info("{} switched the {} check {}", click.player().name(), name, isOn(check) ? "on" : "off");
                        drawDashboard();
                    });
        }
        menu.set(16, Icon.of("minecraft:bell", c("&6Your alerts"),
                c("&7Staff are told in chat when a player fails a check."), c("&eClick to switch them on or off for you")),
                click -> {
                    Player p = click.player();
                    boolean nowOn = alertsOff.remove(p.uuid());
                    if (!nowOn) {
                        alertsOff.add(p.uuid());
                    }
                    p.sendMessage("AntiCheat alerts are " + (nowOn ? "on" : "off") + " for you.");
                });

        menu.set(19, Icon.of("minecraft:golden_helmet", c("&6Operators: " + (exemptOps ? "&anot checked" : "&cchecked")),
                c("&7Whether operators are exempt from all checks."), c("&eClick to switch")).glow(exemptOps),
                click -> {
                    config().set("exempt-operators", !exemptOps);
                    config().save();
                    readSettings();
                    players.values().forEach(d -> d.exemptAge = 0);
                    drawDashboard();
                });
        menu.set(21, Icon.of("minecraft:writable_book", c("&6Reload settings"), c("&eClick to re-read config.toml")),
                click -> {
                    config().reload();
                    readSettings();
                    players.values().forEach(d -> d.exemptAge = 0);
                    drawDashboard();
                });
        menu.set(23, Icon.of("minecraft:water_bucket", c("&6Clear all violations"),
                c("&eShift-click to set every player's levels to 0")),
                click -> {
                    if (click.shift()) {
                        players.values().forEach(d -> d.violations.clear());
                        drawDashboard();
                    }
                });
        int flagged = 0;
        for (Data d : players.values()) {
            if (total(d) > 0) {
                flagged++;
            }
        }
        menu.set(25, Icon.of("minecraft:paper", c("&6Now"), c("&f" + server().players().size() + " &7player(s) online"),
                c("&f" + flagged + " &7with a violation level"),
                c("&7Levels drop by 1 every " + decayTicks / 20 + " s")));

        // players with violations first, highest total first
        List<Player> list = new ArrayList<>(server().players());
        list.sort((a, b) -> Integer.compare(total(players.get(b.uuid())), total(players.get(a.uuid()))));
        for (int i = 0; i < 18; i++) {
            int slot = 27 + i;
            if (i >= list.size()) {
                menu.clear(slot);
                continue;
            }
            Player p = list.get(i);
            Data d = players.get(p.uuid());
            int sum = total(d);
            List<String> lore = new ArrayList<>();
            for (Check check : Check.values()) {
                int vl = d == null ? 0 : d.violations.getOrDefault(check, 0);
                lore.add(c((vl > 0 ? "&c" : "&8") + check.name().toLowerCase(Locale.ROOT) + " &7VL &f" + vl));
            }
            lore.add(c("&7Ping &f" + p.ping() + " ms" + (d != null && d.exempt ? " &a(exempt)" : "")));
            lore.add(c("&eClick: teleport to"));
            lore.add(c("&eRight-click: clear this player's levels"));
            menu.set(slot, Icon.of(sum > 0 ? "minecraft:skeleton_skull" : "minecraft:player_head",
                    c((sum > 0 ? "&c" : "&f") + p.name() + (sum > 0 ? " &7(VL " + sum + ")" : "")), lore)
                    .count(Math.max(1, Math.min(64, sum))).glow(sum >= 10),
                    click -> {
                        if (!p.isOnline()) {
                            return;
                        }
                        if (click.rightClick()) {
                            Data data = players.get(p.uuid());
                            if (data != null) {
                                data.violations.clear();
                            }
                            drawDashboard();
                        } else if (!click.player().equals(p)) {
                            click.player().teleport(p.location());
                        }
                    });
        }
        menu.set(49, Icon.of("minecraft:barrier", c("&cClose")), click -> menu.close());
    }

    private static int total(Data d) {
        int sum = 0;
        if (d != null) {
            for (int vl : d.violations.values()) {
                sum += vl;
            }
        }
        return sum;
    }

    private static String c(String text) {
        return Text.colorize(text);
    }

    // ---------------------------------------------------------------------------------------------- command

    private boolean command(CommandSender sender, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "gui", "dashboard" -> {
                Player p = sender.asPlayer().orElse(null);
                if (p == null) {
                    sender.sendMessage("The dashboard is a window in the game; from the console use /anticheat status.");
                } else {
                    openDashboard(p);
                }
            }
            case "status" -> {
                sender.sendMessage(Text.colorize("&6AntiCheat &7checks: &f" + enabledChecks()));
                int listed = 0;
                for (Player p : server().players()) {
                    Data d = players.get(p.uuid());
                    if (d == null) {
                        continue;
                    }
                    StringBuilder line = new StringBuilder();
                    d.violations.forEach((check, vl) -> {
                        if (vl > 0) {
                            line.append(' ').append(check.name().toLowerCase(Locale.ROOT)).append(' ').append(vl);
                        }
                    });
                    if (line.length() > 0) {
                        sender.sendMessage(Text.colorize("&f" + p.name() + "&7:" + line + (d.exempt ? " (exempt)" : "")));
                        listed++;
                    }
                }
                if (listed == 0) {
                    sender.sendMessage("No player has a violation level right now.");
                }
            }
            case "reload" -> {
                config().reload();
                readSettings();
                players.values().forEach(d -> d.exemptAge = 0);
                sender.sendMessage("AntiCheat settings reloaded: " + enabledChecks() + ".");
            }
            case "alerts" -> {
                Player p = sender.asPlayer().orElse(null);
                if (p == null) {
                    sender.sendMessage("The console always gets the alerts (in the log).");
                } else if (alertsOff.remove(p.uuid())) {
                    sender.sendMessage("AntiCheat alerts are on for you.");
                } else {
                    alertsOff.add(p.uuid());
                    sender.sendMessage("AntiCheat alerts are off for you.");
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private static String three(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
