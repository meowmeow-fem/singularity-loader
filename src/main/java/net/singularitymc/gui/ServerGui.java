package net.singularitymc.gui;

import com.mojang.logging.LogQueues;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.SwingUtilities;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerPlayer;
import net.singularitymc.api.SingularityLoader;
import net.singularitymc.loader.util.Log;
import net.singularitymc.loader.util.Perf;

/**
 * Puts {@link ConsoleWindow} in the place of the vanilla server window ({@code DedicatedServer.showGui}, which the
 * game calls unless it was started with {@code nogui} or has no screen). Feeds it the game's log (the same queue
 * the vanilla window reads) and the loader's own log, and takes it away when the server has stopped.
 */
public final class ServerGui {
    private static final Log LOG = Log.get("Singularity");
    /** The game's lines for its window, {@code [12:00:04 INFO]: text}, or as on the console, {@code [12:00:04] [Server thread/INFO]: text}. */
    private static final Pattern LINE = Pattern.compile(
            "^\\[(\\d\\d:\\d\\d:\\d\\d)(?:] \\[(.+?)/| )(\\w+)]: (.*)$", Pattern.DOTALL);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private ServerGui() {}

    /**
     * Opens the window. Returns {@code false} to let the vanilla window open instead: when it is switched off
     * ({@code singularity_window = false} in ServerConfig.toml) or could not be made.
     */
    public static boolean open(DedicatedServer server) {
        if ("false".equalsIgnoreCase(System.getProperty("singularity.gui.window"))) {
            return false;
        }
        try {
            ConsoleWindow[] made = new ConsoleWindow[1];
            SwingUtilities.invokeAndWait(() -> {
                made[0] = new ConsoleWindow(model(server));
                made[0].show();
            });
            ConsoleWindow window = made[0];
            feed(window, server);
            return true;
        } catch (Exception | LinkageError e) {
            LOG.warn("Could not open the Singularity server window ({}); using Minecraft's own.", e.toString());
            return false;
        }
    }

    private static ConsoleWindow.Model model(DedicatedServer server) {
        String subtitle = "Loader " + SingularityLoader.getInstance().getLoaderVersion() + "  \u00b7  Minecraft "
                + SingularityLoader.getInstance().getGameVersion();
        return new ConsoleWindow.Model() {
            @Override
            public String subtitle() {
                return subtitle;
            }

            @Override
            public boolean ready() {
                return server.isReady();
            }

            @Override
            public long tickCount() {
                return server.getTickCount();
            }

            @Override
            public double mspt() {
                return server.getAverageTickTimeNanos() / 1.0e6;
            }

            @Override
            public List<String> players() {
                List<String> names = new ArrayList<>();
                try {
                    // read from the window's thread: a copy, and a player joining right now is seen a second later
                    for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
                        names.add(player.getName().getString());
                    }
                } catch (RuntimeException e) {
                    // the list changed under us or does not exist yet: show what we have
                }
                return names;
            }

            @Override
            public int maxPlayers() {
                return server.getMaxPlayers();
            }

            @Override
            public int loadLevel() {
                return Perf.level();
            }

            @Override
            public Path directory() {
                return server.getServerDirectory().toAbsolutePath().normalize();
            }

            @Override
            public void command(String command) {
                server.handleConsoleInput(command, server.createCommandSourceStack());
            }

            @Override
            public void stop() {
                server.halt(false);
            }
        };
    }

    private static void feed(ConsoleWindow window, DedicatedServer server) {
        // the loader's own entries (they are not in the game's log queue), earlier ones included
        Log.setSink((channel, level, text) -> window.log(LocalTime.now().format(TIME), channel,
                level.toUpperCase(java.util.Locale.ROOT), text));

        Thread pump = new Thread(() -> {
            String line;
            String lastLevel = "INFO";
            while ((line = LogQueues.getNextLogEvent("ServerGuiConsole")) != null) {
                String text = line.stripTrailing();
                Matcher m = LINE.matcher(text);
                if (m.matches()) {
                    lastLevel = m.group(3);
                    window.log(m.group(1), m.group(2) == null ? "Server" : m.group(2), lastLevel, m.group(4));
                } else if (!text.isBlank()) {
                    window.log(LocalTime.now().format(TIME), "", lastLevel, text);
                }
            }
        }, "Singularity window log");
        pump.setDaemon(true);
        pump.start();

        Thread watch = new Thread(() -> {
            try {
                while (!server.isStopped()) {
                    Thread.sleep(250);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Log.setSink(null);
            window.close();
        }, "Singularity window watch");
        watch.setDaemon(true);
        watch.start();
    }
}
