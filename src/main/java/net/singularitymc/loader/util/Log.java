package net.singularitymc.loader.util;

import java.io.Console;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Minimal logger used by the loader before (and alongside) the game's own logging.
 *
 * <p>Writes to the console stream that was current when the loader started and, once
 * {@link #configure} has been called, to a per-launch log file. Messages use {@code {}} placeholders.
 */
public final class Log {
    public enum Level {
        DEBUG("Debug"), INFO("Info"), WARN("Warn"), ERROR("Error");

        final String label;

        Level(String label) {
            this.label = label;
        }
    }

    private static final PrintStream CONSOLE = System.out;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int KEEP_OLD_LOGS = 20;
    private static volatile boolean debugEnabled = Boolean.getBoolean("singularity.debug");
    private static PrintWriter file;
    /** The loading bar that is currently being drawn (guarded by {@code Log.class}). */
    private static Progress active;

    private final String channel;

    private Log(String channel) {
        this.channel = channel;
    }

    public static Log get(String channel) {
        return new Log(channel);
    }

    /**
     * Enables debug output and mirrors all output to {@code logFile}. The log of the previous start is kept
     * next to it under its own date (the newest {@value #KEEP_OLD_LOGS} are kept).
     */
    public static synchronized void configure(boolean debug, Path logFile) {
        debugEnabled = debug;
        if (logFile == null) {
            return;
        }
        try {
            Path dir = logFile.toAbsolutePath().getParent();
            Files.createDirectories(dir);
            archivePrevious(logFile, dir);
            file = new PrintWriter(Files.newBufferedWriter(logFile, StandardCharsets.UTF_8), true);
        } catch (IOException e) {
            CONSOLE.println("[Singularity] [Warn]: Could not open log file " + logFile + ": " + e);
        }
    }

    private static void archivePrevious(Path logFile, Path dir) {
        try {
            if (Files.isRegularFile(logFile) && Files.size(logFile) > 0) {
                String stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
                        .format(Files.getLastModifiedTime(logFile).toInstant().atZone(java.time.ZoneId.systemDefault()));
                Path target = dir.resolve(stamp + ".log");
                for (int i = 1; Files.exists(target); i++) {
                    target = dir.resolve(stamp + "-" + i + ".log");
                }
                Files.move(logFile, target);
            }
            try (var stream = Files.list(dir)) {
                var old = stream.filter(p -> p.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}(-\\d+)?\\.log"))
                        .sorted(java.util.Comparator.comparing((Path p) -> p.getFileName().toString()).reversed()).toList();
                for (int i = KEEP_OLD_LOGS; i < old.size(); i++) {
                    Files.deleteIfExists(old.get(i));
                }
            }
        } catch (IOException ignored) {
            // keeping old logs is a courtesy; never stop the server for it
        }
    }

    public static boolean isDebugEnabled() {
        return debugEnabled;
    }

    /** Prints text exactly as it is (used for the banner) to the console and the log file. */
    public static synchronized void banner(String text) {
        for (String line : text.stripTrailing().split("\\R", -1)) {
            println(line);
        }
        println("");
    }

    public void debug(String message, Object... args) {
        log(Level.DEBUG, message, args);
    }

    public void info(String message, Object... args) {
        log(Level.INFO, message, args);
    }

    public void warn(String message, Object... args) {
        log(Level.WARN, message, args);
    }

    public void error(String message, Object... args) {
        log(Level.ERROR, message, args);
    }

    public void log(Level level, String message, Object... args) {
        if (level == Level.DEBUG && !debugEnabled) {
            return;
        }
        String text = format(message, args);
        emit(rows(channel, level, text));
        tell(channel, level.label, text);
    }

    /** Receives every log entry as its parts (the server window shows them without the console's box drawing). */
    public interface Sink {
        void entry(String channel, String level, String text);
    }

    private static final java.util.ArrayDeque<String[]> RECENT = new java.util.ArrayDeque<>();
    private static Sink sink;

    private static synchronized void tell(String channel, String level, String text) {
        if (sink != null) {
            sink.entry(channel, level, text);
            return;
        }
        if (RECENT.size() == 200) {
            RECENT.removeFirst();
        }
        RECENT.addLast(new String[] {channel, level, text});
    }

    /** Sets (or with {@code null} removes) the sink; a new sink first gets the entries logged before it came. */
    public static synchronized void setSink(Sink newSink) {
        sink = newSink;
        if (newSink != null) {
            for (String[] entry : RECENT) {
                newSink.entry(entry[0], entry[1], entry[2]);
            }
            RECENT.clear();
        }
    }

    /** One entry of the log: its text rows, then an empty row and a divider, all with the right edge on the same column. */
    static List<String> rows(String channel, Level level, String text) {
        String time = LocalTime.now().format(TIME);
        String pre = "[" + time + "] || [" + channel + "] [" + level.label + "] | ";
        String post = " | [" + level.label + "] [" + channel + "] || [" + time + "]";
        List<String> rows = new ArrayList<>();
        int width = 0;
        for (String line : text.split("\\R", -1)) {
            String row = pre + line.stripTrailing() + post;
            rows.add(row);
            width = Math.max(width, row.length());
        }
        List<String> out = new ArrayList<>();
        for (String row : rows) {
            out.add(row + " ".repeat(width - row.length()) + " |");
        }
        out.add(" ".repeat(width + 1) + "|");
        out.add("-".repeat(width + 1) + "|");
        return out;
    }

    private static synchronized void emit(List<String> rows) {
        for (String row : rows) {
            println(row);
        }
    }

    /** Prints one line, keeping a loading bar that is being drawn intact underneath it. */
    private static void println(String line) {
        Progress bar = active;
        if (bar != null && bar.interactive) {
            bar.clearLine();
        }
        CONSOLE.println(line);
        if (file != null) {
            file.println(line);
        }
        if (bar != null && bar.interactive) {
            bar.draw();
        }
    }

    /** A loading bar for work with {@code total} steps. Close it when the work is done. */
    public Progress progress(String label, long total) {
        return new Progress(channel, label, total, false);
    }

    /** A loading bar for a download of {@code totalBytes} bytes; the counter is shown in MB. */
    public Progress progressBytes(String label, long totalBytes) {
        return new Progress(channel, label, totalBytes, true);
    }

    /** True if stdout is an interactive terminal, so that a line can be redrawn in place. */
    private static boolean interactiveConsole() {
        Console console = System.console();
        if (console == null) {
            return false;
        }
        try {
            return (boolean) Console.class.getMethod("isTerminal").invoke(console); // Java 22+: false when output is redirected
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true; // before Java 22 System.console() is only non-null for a real terminal
        }
    }

    /**
     * A single-line loading bar with a spinner:
     *
     * <pre>
     * [12:00:01] || [Singularity] [Info] | Unpacking And Patching All Required Java Libraries [ 7 | 40]... / [&#9608;&#9608;&#9608;&#9617;&#9617;&#9617;] 17% | [Info] [Singularity] || [12:00:01] |
     * </pre>
     *
     * On a terminal it is redrawn in place (the spinner turns through {@code | / - \}); everywhere else (log
     * files, redirected output) only the finished line is written, so logs stay readable.
     */
    public static final class Progress implements AutoCloseable {
        private static final int BAR_WIDTH = 20;
        private static final char[] SPIN = {'|', '/', '\u2014', '\\'};
        private final String channel;
        private final String label;
        private final long total;
        private final boolean bytes;
        private final boolean interactive = interactiveConsole();
        private final boolean unicode = CONSOLE.charset().newEncoder().canEncode('\u2588') && CONSOLE.charset().newEncoder().canEncode('\u2014');
        private volatile long done;
        private volatile boolean closed;
        private int frame;
        private Thread spinner;

        private Progress(String channel, String label, long total, boolean bytes) {
            this.channel = channel;
            this.label = label;
            this.total = Math.max(1, total);
            this.bytes = bytes;
            synchronized (Log.class) {
                active = this;
                if (interactive) {
                    draw();
                }
            }
            if (interactive) {
                spinner = new Thread(() -> {
                    while (!closed) {
                        try {
                            Thread.sleep(90);
                        } catch (InterruptedException e) {
                            return;
                        }
                        synchronized (Log.class) {
                            if (!closed) {
                                frame++;
                                draw();
                            }
                        }
                    }
                }, "Singularity-Progress");
                spinner.setDaemon(true);
                spinner.start();
            }
        }

        public void step() {
            set(done + 1);
        }

        public void set(long value) {
            done = Math.min(total, Math.max(0, value));
        }

        /** Draws the finished bar on its own line, followed by the usual empty row and divider. */
        @Override
        public void close() {
            synchronized (Log.class) {
                if (closed) {
                    return;
                }
                closed = true;
                if (active == this) {
                    active = null;
                }
                List<String> out = new ArrayList<>(rows(channel, Level.INFO, body(done >= total ? "OK" : "..")));
                String finalRow = out.remove(0);
                if (interactive) {
                    CONSOLE.print("\r" + finalRow + "\n");
                } else {
                    CONSOLE.println(finalRow);
                }
                if (file != null) {
                    file.println(finalRow);
                }
                for (String row : out) {
                    println(row);
                }
            }
            if (spinner != null) {
                spinner.interrupt();
            }
        }

        private String body(String spin) {
            String counter;
            if (bytes) {
                String t = String.format(Locale.ROOT, "%.1f", total / 1_048_576.0);
                counter = String.format(Locale.ROOT, "%" + t.length() + ".1f | %s MB", done / 1_048_576.0, t);
            } else {
                int w = Long.toString(total).length();
                counter = String.format(Locale.ROOT, "%" + w + "d | %d", done, total);
            }
            int filled = (int) (done * BAR_WIDTH / total);
            String bar = (unicode ? "\u2588" : "#").repeat(filled) + (unicode ? "\u2591" : "-").repeat(BAR_WIDTH - filled);
            String pct = String.format(Locale.ROOT, "%3d%%", done * 100 / total);
            return label + " [" + counter + "]... " + String.format("%-2s", spin) + " [" + bar + "] " + pct;
        }

        private void draw() {
            char c = SPIN[frame % SPIN.length];
            if (c == '\u2014' && !unicode) {
                c = '-';
            }
            String row = rows(channel, Level.INFO, body(String.valueOf(c))).get(0);
            CONSOLE.print("\r" + row);
            CONSOLE.flush();
        }

        private void clearLine() {
            CONSOLE.print("\r" + " ".repeat(220) + "\r");
        }
    }

    /**
     * Replaces {@code {}} placeholders with {@code args}. A trailing {@link Throwable} that has no
     * placeholder of its own is appended as a stack trace.
     */
    public static String format(String pattern, Object... args) {
        if (pattern == null) {
            pattern = "null";
        }
        if (args == null || args.length == 0) {
            return pattern;
        }
        int placeholders = 0;
        for (int i = pattern.indexOf("{}"); i >= 0; i = pattern.indexOf("{}", i + 2)) {
            placeholders++;
        }
        Throwable thrown = null;
        Object last = args[args.length - 1];
        if (args.length > placeholders && last instanceof Throwable t) {
            thrown = t;
            args = Arrays.copyOf(args, args.length - 1);
        }
        StringBuilder sb = new StringBuilder(pattern.length() + 16);
        int pos = 0;
        int arg = 0;
        for (;;) {
            int j = arg < args.length ? pattern.indexOf("{}", pos) : -1;
            if (j < 0) {
                sb.append(pattern, pos, pattern.length());
                break;
            }
            sb.append(pattern, pos, j).append(args[arg++]);
            pos = j + 2;
        }
        if (thrown != null) {
            StringWriter sw = new StringWriter();
            thrown.printStackTrace(new PrintWriter(sw));
            sb.append(System.lineSeparator()).append(sw.toString().stripTrailing());
        }
        return sb.toString();
    }
}
