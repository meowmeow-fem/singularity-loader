package net.singularitymc.stress;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.singularitymc.api.entrypoint.ServerModInitializer;
import net.singularitymc.api.event.EventBus;
import net.singularitymc.event.CommandRegistrationEvent;

/**
 * Load-test tool. {@code /stress spawn <count> [spread] [explorers%] [viewDistance]} adds fake players: real
 * {@link ServerPlayer}s on a connection that drops every packet. They load and tick chunks, are tracked, count for mob
 * spawning, are targeted by mobs (damage is ignored) and walk: most wander within 48 blocks of their home, "explorers"
 * walk straight on and keep generating terrain. {@code /stress mark} starts a measurement, {@code /stress report}
 * prints tick times since the mark, {@code /stress clear} removes the players.
 *
 * <p>Not measured: packet encoding, compression and the network itself (packets are dropped as objects).
 */
public final class StressMod implements ServerModInitializer {
    private static final List<Fake> FAKES = new ArrayList<>();
    private static final Random RANDOM = new Random(42);
    private static MinecraftServer server;

    private static long[] samples = new long[1 << 16];
    private static int sampleCount;
    private static long tickStart;
    private static long markTime = System.nanoTime();
    private static long markGcMillis;

    private static long gcMillis() {
        long total = 0;
        for (java.lang.management.GarbageCollectorMXBean gc : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            total += Math.max(0, gc.getCollectionTime());
        }
        return total;
    }

    private static final class Fake {
        final ServerPlayer player;
        final double homeX, homeZ;
        final boolean explorer;
        double dirX, dirZ;
        int rest;
        // "/stress cheat": this bot sends movement packets like a client, in the given mode, for cheatTicks ticks
        String cheat;
        int cheatTicks, cheatAge;
        double cx, cy, cz, groundY, vy;

        Fake(ServerPlayer player, double homeX, double homeZ, boolean explorer) {
            this.player = player;
            this.homeX = homeX;
            this.homeZ = homeZ;
            this.explorer = explorer;
            turn();
        }

        void turn() {
            double a = RANDOM.nextDouble() * Math.PI * 2;
            dirX = Math.cos(a);
            dirZ = Math.sin(a);
        }
    }

    @Override
    public void onInitializeServer() {
        EventBus.register(CommandRegistrationEvent.class, e -> e.getDispatcher().register(Commands.literal("stress")
            .then(Commands.literal("spawn").then(Commands.argument("count", IntegerArgumentType.integer(1, 2000))
                .executes(c -> spawn(c.getSource(), IntegerArgumentType.getInteger(c, "count"), 1000, 10, 10))
                .then(Commands.argument("spread", IntegerArgumentType.integer(0, 1_000_000))
                    .executes(c -> spawn(c.getSource(), IntegerArgumentType.getInteger(c, "count"),
                        IntegerArgumentType.getInteger(c, "spread"), 10, 10))
                    .then(Commands.argument("explorers", IntegerArgumentType.integer(0, 100))
                        .executes(c -> spawn(c.getSource(), IntegerArgumentType.getInteger(c, "count"),
                            IntegerArgumentType.getInteger(c, "spread"), IntegerArgumentType.getInteger(c, "explorers"), 10))
                        .then(Commands.argument("view", IntegerArgumentType.integer(2, 32))
                            .executes(c -> spawn(c.getSource(), IntegerArgumentType.getInteger(c, "count"),
                                IntegerArgumentType.getInteger(c, "spread"), IntegerArgumentType.getInteger(c, "explorers"),
                                IntegerArgumentType.getInteger(c, "view"))))))))
            .then(Commands.literal("view").then(Commands.argument("view", IntegerArgumentType.integer(2, 32))
                .then(Commands.argument("simulation", IntegerArgumentType.integer(2, 32)).executes(c -> {
                    c.getSource().getServer().getPlayerList().setViewDistance(IntegerArgumentType.getInteger(c, "view"));
                    c.getSource().getServer().getPlayerList().setSimulationDistance(IntegerArgumentType.getInteger(c, "simulation"));
                    c.getSource().sendSuccess(() -> Component.literal("Stress: view / simulation distance set"), false);
                    return 1;
                }))))
            .then(Commands.literal("machines").then(Commands.argument("count", IntegerArgumentType.integer(1, 4000))
                .executes(c -> machines(c.getSource(), IntegerArgumentType.getInteger(c, "count"), "rhm"))
                .then(Commands.argument("kinds", com.mojang.brigadier.arguments.StringArgumentType.word())
                    .executes(c -> machines(c.getSource(), IntegerArgumentType.getInteger(c, "count"),
                        com.mojang.brigadier.arguments.StringArgumentType.getString(c, "kinds"))))))
            .then(Commands.literal("dust").then(Commands.argument("x", IntegerArgumentType.integer())
                .then(Commands.argument("y", IntegerArgumentType.integer()).then(Commands.argument("z", IntegerArgumentType.integer())
                    .then(Commands.argument("count", IntegerArgumentType.integer(1, 64)).executes(c -> {
                        StringBuilder sb = new StringBuilder("Stress dust:");
                        for (int i = 0; i < IntegerArgumentType.getInteger(c, "count"); i++) {
                            net.minecraft.world.level.block.state.BlockState st = c.getSource().getServer().overworld().getBlockState(
                                new BlockPos(IntegerArgumentType.getInteger(c, "x") + i, IntegerArgumentType.getInteger(c, "y"), IntegerArgumentType.getInteger(c, "z")));
                            sb.append(' ').append(st.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWER)
                                ? String.valueOf(st.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWER))
                                : st.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT)
                                    ? (st.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT) ? "LIT" : "unlit") : "-");
                        }
                        String msg = sb.toString();
                        c.getSource().sendSuccess(() -> Component.literal(msg), false);
                        return 1;
                    }))))))
            .then(Commands.literal("projectiles").then(Commands.argument("count", IntegerArgumentType.integer(1, 20000))
                .then(Commands.argument("type", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(c -> {
                    // hovering projectiles over the spawn chunks: what one costs per tick while it flies
                    MinecraftServer s = c.getSource().getServer();
                    CommandSourceStack quiet = s.createCommandSourceStack().withSuppressedOutput();
                    BlockPos spawn = s.overworld().getRespawnData().pos();
                    int n = IntegerArgumentType.getInteger(c, "count");
                    String type = com.mojang.brigadier.arguments.StringArgumentType.getString(c, "type");
                    run(s, quiet, "forceload add " + spawn.getX() + " " + spawn.getZ() + " " + (spawn.getX() + 63) + " " + (spawn.getZ() + 63));
                    for (int i = 0; i < n; i++) {
                        run(s, quiet, "summon minecraft:" + type + " " + (spawn.getX() + i % 60 + 0.5) + " " + (150 + i / 3600 * 3) + " "
                            + (spawn.getZ() + i / 60 % 60 + 0.5) + " {NoGravity:1b}");
                    }
                    c.getSource().sendSuccess(() -> Component.literal("Stress: summoned " + n + " " + type), false);
                    return n;
                }))))
            .then(Commands.literal("cheat").then(Commands.argument("bot", IntegerArgumentType.integer(1, 2000))
                .then(Commands.argument("mode", com.mojang.brigadier.arguments.StringArgumentType.word())
                    .executes(c -> cheat(c.getSource(), IntegerArgumentType.getInteger(c, "bot"),
                        com.mojang.brigadier.arguments.StringArgumentType.getString(c, "mode"), 100))
                    .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 6000))
                        .executes(c -> cheat(c.getSource(), IntegerArgumentType.getInteger(c, "bot"),
                            com.mojang.brigadier.arguments.StringArgumentType.getString(c, "mode"),
                            IntegerArgumentType.getInteger(c, "ticks")))))))
            // test driver for chest-window GUIs: what window bot N has open, and a click into it
            .then(Commands.literal("menu").then(Commands.argument("bot", IntegerArgumentType.integer(1, 2000))
                .executes(c -> menu(c.getSource(), IntegerArgumentType.getInteger(c, "bot"), -1, "left"))
                .then(Commands.argument("slot", IntegerArgumentType.integer(0, 53))
                    .executes(c -> menu(c.getSource(), IntegerArgumentType.getInteger(c, "bot"),
                        IntegerArgumentType.getInteger(c, "slot"), "left"))
                    .then(Commands.argument("how", com.mojang.brigadier.arguments.StringArgumentType.word())
                        .executes(c -> menu(c.getSource(), IntegerArgumentType.getInteger(c, "bot"),
                            IntegerArgumentType.getInteger(c, "slot"),
                            com.mojang.brigadier.arguments.StringArgumentType.getString(c, "how")))))))
            // test drivers for login plugins: what a bot's client would send, through the real packet path
            .then(Commands.literal("type").then(Commands.argument("bot", IntegerArgumentType.integer(1, 2000))
                .then(Commands.argument("command", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                    .executes(c -> type(c.getSource(), IntegerArgumentType.getInteger(c, "bot"),
                        com.mojang.brigadier.arguments.StringArgumentType.getString(c, "command"))))))
            .then(Commands.literal("slot").then(Commands.argument("bot", IntegerArgumentType.integer(1, 2000))
                .then(Commands.argument("slot", IntegerArgumentType.integer(0, 8))
                    .executes(c -> slot(c.getSource(), IntegerArgumentType.getInteger(c, "bot"),
                        IntegerArgumentType.getInteger(c, "slot"))))))
            .then(Commands.literal("form").then(Commands.argument("bot", IntegerArgumentType.integer(1, 2000))
                .then(Commands.argument("answer", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                    .executes(c -> form(c.getSource(), IntegerArgumentType.getInteger(c, "bot"),
                        com.mojang.brigadier.arguments.StringArgumentType.getString(c, "answer"))))))
            .then(Commands.literal("chunks").executes(c -> chunks(c.getSource())))
            .then(Commands.literal("clear").executes(c -> clear(c.getSource())))
            .then(Commands.literal("mark").executes(c -> mark(c.getSource())))
            .then(Commands.literal("report").executes(c -> report(c.getSource())))));
    }

    private static int spawn(CommandSourceStack source, int count, int spread, int explorersPercent, int view) {
        MinecraftServer s = source.getServer();
        ServerLevel level = s.overworld();
        BlockPos spawn = level.getRespawnData().pos();
        int start = FAKES.size();
        for (int i = 0; i < count; i++) {
            String name = "Bot" + (start + i + 1);
            GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)), name);
            ClientInformation info = new ClientInformation("en_us", view, ChatVisiblity.FULL, true, 0x7F, HumanoidArm.RIGHT, false, true, ParticleStatus.ALL);
            ServerPlayer player = new ServerPlayer(s, level, profile, info);
            s.getPlayerList().placeNewPlayer(new FakeConnection(), player, new CommonListenerCookie(profile, 0, info, false));
            player.setGameMode(GameType.SURVIVAL);
            player.getAbilities().invulnerable = true;
            double x = spawn.getX() + (RANDOM.nextDouble() * 2 - 1) * spread;
            double z = spawn.getZ() + (RANDOM.nextDouble() * 2 - 1) * spread;
            Fake fake = new Fake(player, x, z, RANDOM.nextInt(100) < explorersPercent);
            FAKES.add(fake);
            place(fake, x, z);
        }
        String msg = "Stress: " + count + " fake players added (" + FAKES.size() + " in all), spread " + spread
            + ", explorers " + explorersPercent + "%, client view distance " + view;
        source.sendSuccess(() -> Component.literal(msg), false);
        return count;
    }

    private static int clear(CommandSourceStack source) {
        int n = FAKES.size();
        for (Fake f : FAKES) {
            source.getServer().getPlayerList().remove(f.player);
        }
        FAKES.clear();
        source.sendSuccess(() -> Component.literal("Stress: removed " + n + " fake players"), false);
        return n;
    }

    /**
     * Builds {@code count} test pads in force-loaded chunks at Y 200 next to the world spawn. {@code kinds} picks what
     * is on a pad: {@code r} an observer clock driving 15 redstone dust on lamps, {@code h} a chest emptying through
     * 8 hoppers into a chest plus 16 idle hoppers, {@code m} 8 zombies and a villager in a closed stone room.
     */
    private static int machines(CommandSourceStack source, int count, String kinds) {
        MinecraftServer s = source.getServer();
        CommandSourceStack quiet = s.createCommandSourceStack().withSuppressedOutput();
        BlockPos spawn = s.overworld().getRespawnData().pos();
        int cols = (int) Math.ceil(Math.sqrt(count));
        int bx = (spawn.getX() >> 4 << 4) + 64, bz = (spawn.getZ() >> 4 << 4) + 64, y = 200;
        int size = cols * 20;
        // forceload takes at most 256 chunks per command
        for (int cx = bx; cx < bx + size; cx += 128) {
            for (int cz = bz; cz < bz + size; cz += 128) {
                run(s, quiet, "forceload add " + cx + " " + cz + " " + Math.min(cx + 127, bx + size) + " " + Math.min(cz + 127, bz + size));
            }
        }
        for (int i = 0; i < count; i++) {
            int ox = bx + (i % cols) * 20, oz = bz + (i / cols) * 20;
            if (kinds.contains("r")) {
                run(s, quiet, "fill " + ox + " " + y + " " + (oz + 1) + " " + (ox + 17) + " " + y + " " + (oz + 1) + " minecraft:redstone_lamp");
                run(s, quiet, "fill " + (ox + 3) + " " + (y + 1) + " " + (oz + 1) + " " + (ox + 17) + " " + (y + 1) + " " + (oz + 1) + " minecraft:redstone_wire");
                run(s, quiet, "setblock " + (ox + 1) + " " + (y + 1) + " " + (oz + 1) + " minecraft:observer[facing=east]");
                run(s, quiet, "setblock " + (ox + 2) + " " + (y + 1) + " " + (oz + 1) + " minecraft:observer[facing=west]");
            }
            if (kinds.contains("h")) {
                run(s, quiet, "fill " + ox + " " + (y + 2) + " " + (oz + 4) + " " + (ox + 7) + " " + (y + 2) + " " + (oz + 4) + " minecraft:hopper[facing=east]");
                run(s, quiet, "setblock " + (ox + 8) + " " + (y + 2) + " " + (oz + 4) + " minecraft:chest");
                run(s, quiet, "setblock " + ox + " " + (y + 3) + " " + (oz + 4) + " minecraft:chest");
                for (int slot = 0; slot < 27; slot++) {
                    run(s, quiet, "item replace block " + ox + " " + (y + 3) + " " + (oz + 4) + " container." + slot + " with minecraft:cobblestone 64");
                }
                run(s, quiet, "fill " + ox + " " + (y + 2) + " " + (oz + 6) + " " + (ox + 15) + " " + (y + 2) + " " + (oz + 6) + " minecraft:hopper[facing=down]");
            }
            if (kinds.contains("m")) {
                run(s, quiet, "fill " + ox + " " + y + " " + (oz + 9) + " " + (ox + 15) + " " + (y + 3) + " " + (oz + 18) + " minecraft:stone hollow");
                for (int z = 0; z < 8; z++) {
                    run(s, quiet, "summon minecraft:zombie " + (ox + 2 + z) + " " + (y + 1) + " " + (oz + 11) + " {PersistenceRequired:1b}");
                }
                run(s, quiet, "summon minecraft:villager " + (ox + 12) + " " + (y + 1) + " " + (oz + 16) + " {Invulnerable:1b,PersistenceRequired:1b}");
            }
        }
        String msg = "Stress: built " + count + " machine pads (" + kinds + ") from " + bx + " " + y + " " + bz;
        source.sendSuccess(() -> Component.literal(msg), false);
        return count;
    }

    private static void run(MinecraftServer server, CommandSourceStack source, String command) {
        server.getCommands().performPrefixedCommand(source, command);
    }

    /** Debug: the chunk map's internal queues (by reflection; names as in 26.3). */
    private static int chunks(CommandSourceStack source) {
        ServerLevel level = source.getServer().overworld();
        StringBuilder sb = new StringBuilder("Stress chunks:");
        try {
            Object map = level.getChunkSource().chunkMap;
            for (String name : new String[] {"updatingChunkMap", "visibleChunkMap", "pendingUnloads", "toDrop", "unloadQueue", "chunksToEagerlySave"}) {
                Field f = map.getClass().getDeclaredField(name);
                f.setAccessible(true);
                Object v = f.get(map);
                int size = v instanceof java.util.Map<?, ?> m ? m.size() : ((java.util.Collection<?>) v).size();
                sb.append(' ').append(name).append('=').append(size);
            }
            Field p = level.getChunkSource().getClass().getDeclaredField("mainThreadProcessor");
            p.setAccessible(true);
            sb.append(" mainThreadTasks=").append(((net.minecraft.util.thread.BlockableEventLoop<?>) p.get(level.getChunkSource())).getPendingTasksCount());
            sb.append(" spawnChunks=").append(level.getChunkSource().chunkMap.getDistanceManager().getNaturalSpawnChunkCount());
        } catch (ReflectiveOperationException | RuntimeException e) {
            sb.append(" failed: ").append(e);
        }
        String msg = sb.toString();
        source.sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int mark(CommandSourceStack source) {
        sampleCount = 0;
        markTime = System.nanoTime();
        markGcMillis = gcMillis();
        source.sendSuccess(() -> Component.literal("Stress: measurement started"), false);
        return 1;
    }

    private static int report(CommandSourceStack source) {
        MinecraftServer s = source.getServer();
        int n = sampleCount;
        if (n == 0) {
            source.sendSuccess(() -> Component.literal("Stress: no ticks measured yet"), false);
            return 0;
        }
        long[] sorted = Arrays.copyOf(samples, n);
        Arrays.sort(sorted);
        double sum = 0;
        int over50 = 0;
        for (long v : sorted) {
            sum += v;
            if (v > 50_000_000L) over50++;
        }
        double seconds = (System.nanoTime() - markTime) / 1e9;
        int entities = 0, chunks = 0;
        for (ServerLevel level : s.getAllLevels()) {
            for (Entity ignored : level.getAllEntities()) entities++;
            chunks += level.getChunkSource().getLoadedChunksCount();
        }
        String msg = String.format(java.util.Locale.ROOT,
            "Stress report: players=%d ticks=%d in %.1fs tps=%.2f | mspt mean=%.2f p50=%.2f p95=%.2f p99=%.2f max=%.2f | over50ms=%.1f%% | entities=%d chunks=%d view=%d sim=%d | gc=%.1f%% heap=%d/%dMB",
            s.getPlayerList().getPlayers().size(), n, seconds, Math.min(20.0, n / seconds),
            sum / n / 1e6, sorted[n / 2] / 1e6, sorted[(int) (n * 0.95)] / 1e6, sorted[(int) (n * 0.99)] / 1e6, sorted[n - 1] / 1e6,
            100.0 * over50 / n, entities, chunks, s.getPlayerList().getViewDistance(), s.getPlayerList().getSimulationDistance(),
            (gcMillis() - markGcMillis) / (seconds * 10.0),
            (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) >> 20, Runtime.getRuntime().maxMemory() >> 20);
        source.sendSuccess(() -> Component.literal(msg), false);
        return n;
    }

    /** Called at the start of {@code MinecraftServer.tickServer}. */
    public static void tickStart(MinecraftServer s) {
        server = s;
        tickStart = System.nanoTime();
        for (Fake f : FAKES) {
            try {
                step(f);
            } catch (RuntimeException e) {
                // a fake player must never stop the test
            }
        }
    }

    /** Called at the end of {@code MinecraftServer.tickServer}. */
    public static void tickEnd() {
        if (sampleCount == samples.length) {
            samples = Arrays.copyOf(samples, samples.length * 2);
        }
        samples[sampleCount++] = System.nanoTime() - tickStart;
    }

    private static void step(Fake f) {
        ServerPlayer p = f.player;
        if (f.cheat != null) {
            // no doTick here: it lets gravity act on the bot on the server side, which a real player's own client
            // does instead, and the server-side move would look like a teleport between two packets
            cheatStep(f);
            return;
        }
        p.doTick(); // what the packet listener does for a real player every tick
        if (p.getHealth() < p.getMaxHealth()) p.setHealth(p.getMaxHealth());
        if (f.rest > 0) {
            f.rest--;
            return;
        }
        if (RANDOM.nextInt(400) == 0) {
            f.rest = 100 + RANDOM.nextInt(600); // stand still for 5 to 35 seconds
            return;
        }
        if (RANDOM.nextInt(200) == 0) f.turn();
        double x = p.getX() + f.dirX * 0.2159; // walking speed, 4.317 blocks per second
        double z = p.getZ() + f.dirZ * 0.2159;
        if (!f.explorer) {
            double dx = x - f.homeX, dz = z - f.homeZ;
            if (dx * dx + dz * dz > 48 * 48) { // wanderers stay near home
                double len = Math.sqrt(dx * dx + dz * dz);
                f.dirX = -dx / len;
                f.dirZ = -dz / len;
                return;
            }
        }
        place(f, x, z);
    }

    /**
     * Test driver for anti-cheat plugins: bot {@code index} (1 = Bot1) sends real movement packets through its packet
     * listener for {@code ticks} ticks. Modes: {@code walk}, {@code sprint}, {@code jump} (sprint-jumping with the
     * game's gravity) are legitimate; {@code speed} (0.9 blocks per tick), {@code fly} (up, then hovering),
     * {@code glide} (up, then sinking 0.05 per tick) and {@code timer} (three packets per tick) are not.
     * {@code reach} and {@code clicks} hit the next bot once: from 6 and from 2 blocks, and 30 times in one tick.
     */
    private static int cheat(CommandSourceStack source, int index, String mode, int ticks) {
        if (index > FAKES.size()) {
            source.sendFailure(Component.literal("Stress: there is no Bot" + index));
            return 0;
        }
        Fake f = FAKES.get(index - 1);
        ServerPlayer p = f.player;
        if (mode.equals("reach") || mode.equals("clicks")) {
            if (FAKES.size() < 2) {
                source.sendFailure(Component.literal("Stress: needs two bots"));
                return 0;
            }
            ServerPlayer target = FAKES.get(index % FAKES.size()).player;
            if (mode.equals("reach")) {
                for (double away : new double[] {6, 6, 2}) {
                    target.setPos(p.getX() + away, p.getY(), p.getZ());
                    p.attack(target);
                }
            } else {
                target.setPos(p.getX() + 2, p.getY(), p.getZ());
                for (int i = 0; i < 30; i++) {
                    p.attack(target);
                }
            }
            source.sendSuccess(() -> Component.literal("Stress: " + p.getName().getString() + " hit "
                + target.getName().getString() + " (" + mode + ")"), false);
            return 1;
        }
        f.cheat = mode;
        f.cheatTicks = ticks;
        f.cheatAge = 0;
        f.cx = p.getX();
        f.cy = p.getY();
        f.cz = p.getZ();
        f.groundY = p.getY();
        f.vy = 0;
        if (!p.connection.hasClientLoaded()) {
            p.connection.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
        }
        source.sendSuccess(() -> Component.literal("Stress: " + p.getName().getString() + " sends '" + mode
            + "' packets for " + ticks + " ticks from " + String.format("%.1f, %.1f, %.1f", f.cx, f.cy, f.cz)), false);
        return 1;
    }

    /**
     * {@code slot < 0}: lists the icons of the window bot {@code index} has open. Otherwise clicks that slot
     * ({@code how}: left, right or shift) the way a click packet would, then names the slot again.
     */
    private static int menu(CommandSourceStack source, int index, int slot, String how) {
        if (index > FAKES.size()) {
            source.sendFailure(Component.literal("Stress: there is no Bot" + index));
            return 0;
        }
        ServerPlayer p = FAKES.get(index - 1).player;
        net.minecraft.world.inventory.AbstractContainerMenu open = p.containerMenu;
        if (open == p.inventoryMenu) {
            source.sendSuccess(() -> Component.literal("Stress menu: " + p.getName().getString() + " has no window open"), false);
            return 0;
        }
        int top = open.slots.size() - 36; // the rest is the player's own inventory
        if (slot >= 0) {
            open.clicked(slot, how.equals("right") ? 1 : 0, how.equals("shift")
                ? net.minecraft.world.inventory.ContainerInput.QUICK_MOVE : net.minecraft.world.inventory.ContainerInput.PICKUP, p);
            if (p.containerMenu != open) {
                source.sendSuccess(() -> Component.literal("Stress menu: clicked " + slot + ", the window closed"), false);
                return 1;
            }
            String after = slot < top ? describe(open.getSlot(slot).getItem()) : "?";
            source.sendSuccess(() -> Component.literal("Stress menu: clicked " + slot + " (" + how + ") -> " + after), false);
            return 1;
        }
        StringBuilder sb = new StringBuilder("Stress menu: " + top + " slots, carried " + (open.getCarried().isEmpty() ? "nothing" : "AN ITEM"));
        for (int i = 0; i < top; i++) {
            net.minecraft.world.item.ItemStack stack = open.getSlot(i).getItem();
            if (!stack.isEmpty() && !stack.getHoverName().getString().isBlank()) {
                sb.append("\n  ").append(i).append(": ").append(describe(stack));
            }
        }
        String msg = sb.toString();
        source.sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    /** Hands a packet to the bot's listener the way the network does: through {@code Connection.genericsFtw}. */
    private static void receive(ServerPlayer p, net.minecraft.network.protocol.Packet<?> packet) {
        try {
            java.lang.reflect.Method m = Connection.class.getDeclaredMethod("genericsFtw",
                net.minecraft.network.protocol.Packet.class, PacketListener.class);
            m.setAccessible(true);
            m.invoke(null, packet, p.connection);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The bot types a command (without the slash) into its chat: an unsigned command packet. */
    private static int type(CommandSourceStack source, int index, String command) {
        if (index > FAKES.size()) {
            source.sendFailure(Component.literal("Stress: there is no Bot" + index));
            return 0;
        }
        ServerPlayer p = FAKES.get(index - 1).player;
        receive(p, new net.minecraft.network.protocol.game.ServerboundChatCommandPacket(command));
        String shown = command.contains(" ") ? command.substring(0, command.indexOf(' ')) + " ..." : command;
        source.sendSuccess(() -> Component.literal("Stress: " + p.getName().getString() + " typed /" + shown), false);
        return 1;
    }

    /** The bot selects a hotbar slot: an ordinary game packet, to see whether the server takes it. */
    private static int slot(CommandSourceStack source, int index, int slot) {
        if (index > FAKES.size()) {
            source.sendFailure(Component.literal("Stress: there is no Bot" + index));
            return 0;
        }
        ServerPlayer p = FAKES.get(index - 1).player;
        int before = p.getInventory().getSelectedSlot();
        receive(p, new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(slot));
        int after = p.getInventory().getSelectedSlot();
        source.sendSuccess(() -> Component.literal("Stress: " + p.getName().getString() + " asked for hotbar slot " + slot
            + ": was " + before + ", is " + after + (after == slot ? " (accepted)" : " (ignored)")), false);
        return 1;
    }

    /**
     * The bot answers the Singularity API form it has open: {@code answer} is the button's id followed by
     * {@code key=value} pairs, or {@code forged} to answer with a made-up form id.
     */
    private static int form(CommandSourceStack source, int index, String answer) {
        if (index > FAKES.size()) {
            source.sendFailure(Component.literal("Stress: there is no Bot" + index));
            return 0;
        }
        ServerPlayer p = FAKES.get(index - 1).player;
        String[] parts = answer.split(" ");
        String id;
        try {
            Class<?> forms = Class.forName("net.singularitymc.sapi.internal.mc.FormImpl", true, StressMod.class.getClassLoader());
            Field open = forms.getDeclaredField("OPEN");
            open.setAccessible(true);
            Object entry = ((java.util.Map<?, ?>) open.get(null)).get(p.getUUID());
            if (entry == null) {
                source.sendSuccess(() -> Component.literal("Stress form: " + p.getName().getString() + " has no form open"), false);
                return 0;
            }
            java.lang.reflect.Method idOf = entry.getClass().getDeclaredMethod("id");
            idOf.setAccessible(true);
            id = (String) idOf.invoke(entry);
        } catch (ReflectiveOperationException e) {
            source.sendFailure(Component.literal("Stress form: the Singularity API mod is not there (" + e + ")"));
            return 0;
        }
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.putString("sapi_form", parts[0].equals("forged") ? "00".repeat(12) : id);
        tag.putString("sapi_button", parts[0]);
        for (int i = 1; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq > 0) {
                tag.putString(parts[i].substring(0, eq), parts[i].substring(eq + 1));
            }
        }
        receive(p, new net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket(
            net.minecraft.resources.Identifier.fromNamespaceAndPath("singularity", "form"), java.util.Optional.of(tag)));
        source.sendSuccess(() -> Component.literal("Stress form: " + p.getName().getString() + " pressed '" + parts[0] + "'"), false);
        return 1;
    }

    private static String describe(net.minecraft.world.item.ItemStack stack) {
        if (stack.isEmpty()) {
            return "(empty)";
        }
        StringBuilder sb = new StringBuilder(stack.getHoverName().getString().replaceAll("§.", ""));
        net.minecraft.world.item.component.ItemLore lore = stack.get(net.minecraft.core.component.DataComponents.LORE);
        if (lore != null && !lore.lines().isEmpty()) {
            sb.append(" | ").append(lore.lines().get(0).getString().replaceAll("§.", ""));
        }
        return sb.toString();
    }

    private static void cheatStep(Fake f) {
        ServerPlayer p = f.player;
        acceptTeleport(p);
        // what the packet listener's own tick does first: this tick's movement is measured from here
        p.connection.resetPosition();
        if (p.getX() != f.cx || p.getY() != f.cy || p.getZ() != f.cz) {
            // the server put the bot somewhere else (a setback): go on from there, like a client
            f.cx = p.getX();
            f.cy = p.getY();
            f.cz = p.getZ();
            f.vy = 0;
        }
        int age = f.cheatAge++;
        switch (f.cheat) {
            case "walk" -> send(f, 0.2159, 0, true);
            case "sprint" -> send(f, 0.2806, 0, true);
            case "speed" -> send(f, 0.9, 0, true);
            case "timer" -> {
                for (int i = 0; i < 3; i++) {
                    send(f, 0.2159, 0, true);
                }
            }
            case "jump" -> {
                if (f.cy <= f.groundY) {
                    f.vy = 0.42;
                }
                double y = Math.max(f.groundY, f.cy + f.vy);
                f.vy = (f.vy - 0.08) * 0.98;
                send(f, 0.36, y - f.cy, y <= f.groundY);
            }
            case "mace" -> {
                // legitimate: a mace with Wind Burst on mobs in mid-air. Thrown up, falls by the game's gravity,
                // and is thrown up again on the next hit, without touching the ground in between.
                if (age == 0 || f.vy < -0.6) {
                    f.vy = 1.2;
                }
                send(f, 0.1, f.vy, false);
                f.vy = (f.vy - 0.08) * 0.98;
            }
            case "fly" -> send(f, 0.2, f.cy < f.groundY + 4 ? 0.3 : 0, false);
            case "glide" -> send(f, 0.2, age < 20 ? 0.3 : -0.05, false);
            default -> f.cheatAge = f.cheatTicks;
        }
        if (f.cheatAge >= f.cheatTicks) {
            String msg = "Stress: " + p.getName().getString() + " finished '" + f.cheat + "' at "
                + String.format("%.1f, %.1f, %.1f", p.getX(), p.getY(), p.getZ());
            f.cheat = null;
            server.getPlayerList().broadcastSystemMessage(Component.literal(msg), false);
        }
    }

    /** One position packet, {@code horizontal} blocks along +X and {@code dy} up, handled like one from the network. */
    private static void send(Fake f, double horizontal, double dy, boolean onGround) {
        ServerPlayer p = f.player;
        f.cx += horizontal;
        f.cy += dy;
        p.connection.handleMovePlayer(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Pos(
            f.cx, f.cy, f.cz, onGround, false));
        // a client ends each of its ticks with this; 26.3 drops a client that sends two positions without it
        p.connection.handleClientTickEnd(net.minecraft.network.protocol.game.ServerboundClientTickEndPacket.INSTANCE);
        acceptTeleport(p);
    }

    /** A client confirms every teleport; until then the server ignores its movement. */
    private static void acceptTeleport(ServerPlayer p) {
        try {
            Class<?> c = net.minecraft.server.network.ServerGamePacketListenerImpl.class;
            Field awaiting = c.getDeclaredField("awaitingPositionFromClient");
            awaiting.setAccessible(true);
            Object pos = awaiting.get(p.connection);
            if (pos == null) {
                return;
            }
            Field id = c.getDeclaredField("awaitingTeleport");
            id.setAccessible(true);
            net.minecraft.world.phys.Vec3 at = (net.minecraft.world.phys.Vec3) pos;
            p.connection.handleAcceptTeleportPacket(new net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket(
                id.getInt(p.connection), at.x, at.y, at.z, p.getYRot(), p.getXRot()));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void place(Fake f, double x, double z) {
        ServerPlayer p = f.player;
        ServerLevel level = (ServerLevel) p.level();
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        double y = p.getY();
        if (level.hasChunk(bx >> 4, bz >> 4)) {
            y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
        }
        p.setPos(x, y, z);
        p.setYRot((float) Math.toDegrees(Math.atan2(-f.dirX, f.dirZ)));
        level.getChunkSource().move(p);
    }

    /** A connection whose channel swallows every packet. */
    private static final class FakeConnection extends Connection {
        FakeConnection() {
            super(PacketFlow.SERVERBOUND);
            EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
                @Override
                public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                    ReferenceCountUtil.release(msg);
                    promise.setSuccess();
                }
            });
            try {
                Field f = Connection.class.getDeclaredField("channel");
                f.setAccessible(true);
                f.set(this, channel);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void setReadOnly() {
        }

        @Override
        public void handleDisconnection() {
        }

        @Override
        public void setListenerForServerboundHandshake(PacketListener listener) {
        }

        @Override
        public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocol, T listener) {
        }

        @Override
        public void setupOutboundProtocol(ProtocolInfo<?> protocol) {
        }
    }
}
