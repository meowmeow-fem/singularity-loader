package net.singularitymc.gui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.LinearGradientPaint;
import java.awt.Point;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

/**
 * The server's window (shown unless the server is started with {@code nogui}): Singularity's replacement for the
 * grey Swing window of the vanilla server. A rounded, borderless window with the tick rate, tick time history,
 * memory, the console with a command line, the players and a row of quick actions.
 *
 * <p>This class knows nothing of Minecraft: it draws what a {@link Model} tells it, so it can be shown with made-up
 * numbers ({@link #main}) and tested without a server. Everything here runs on the Swing thread except
 * {@link #log}, which any thread may call.
 *
 * <p>Cost: a server window must not eat the server's CPU. The moving parts (glow outline, ambient light, gauges)
 * are redrawn 30 times a second while the window has the focus, 5 times while it does not, and not at all while it
 * is minimised; the console itself is only redrawn when text arrives. {@code -Dsingularity.gui.animations=false}
 * stops the movement altogether.
 */
public final class ConsoleWindow {

    /** What the window shows and what it can do; implemented over the real server by {@code ServerGui}. */
    public interface Model {
        /** For the header, e.g. {@code "Loader 4.4.0  ·  Minecraft 26.3"}. */
        String subtitle();

        /** Whether the server has finished starting. */
        boolean ready();

        /** Ticks since the server started: the window works out the tick rate from it. */
        long tickCount();

        /** Average time one tick takes, in milliseconds. */
        double mspt();

        List<String> players();

        int maxPlayers();

        /** The adaptive load level (0 = not under load). */
        int loadLevel();

        /** The server folder (for "open folder"). */
        Path directory();

        /** Runs a console command. */
        void command(String command);

        /** Stops the server. */
        void stop();
    }

    // ---- palette -------------------------------------------------------------------------------------------
    private static final Color BG = new Color(0x130E1D);
    private static final Color BG_CONSOLE = new Color(0x0F0B17);
    private static final Color TEXT = new Color(0xEDE7F6);
    private static final Color DIM = new Color(0x9D92B4);
    private static final Color FAINT = new Color(0x6B6182);
    private static final Color PINK = new Color(0xFF5FA8);
    private static final Color PURPLE = new Color(0xA05CFF);
    private static final Color GOOD = new Color(0x62F0A9);
    private static final Color WARN = new Color(0xFFD166);
    private static final Color BAD = new Color(0xFF5F7A);

    /** Transparent margin around the window body: room for the glow. */
    private static final int PAD = 14;
    private static final int RADIUS = 26;
    private static final int HISTORY = 90;
    private static final int MAX_LINES = 2000;

    private final Model model;
    private final JFrame frame;
    private final Root root;
    private final boolean animate;
    private final boolean translucent;
    private final Font sans;
    private final Font mono;

    private final ConcurrentLinkedQueue<String[]> pending = new ConcurrentLinkedQueue<>();
    private final List<Runnable> perFrame = new ArrayList<>();
    private final List<String> history = new ArrayList<>();
    private int historyAt;

    private JTextPane console;
    private JScrollPane consoleScroll;
    private JTextField input;
    private JPanel playerList;
    private JLabel playersTitle;
    private Gauge gauge;
    private Spark spark;
    private Meter memory;
    private JLabel uptimeValue;
    private JLabel playersValue;
    private JLabel loadValue;
    private JComponent header;
    private JComponent left;
    private JComponent right;
    private Timer timer;

    // numbers, sampled once a second
    private final long startedAt = System.currentTimeMillis();
    private long lastSampleNanos = System.nanoTime();
    private long lastTicks = -1;
    private double tps = 20;
    private double mspt;
    private final float[] msptHistory = new float[HISTORY];
    private int msptCount;
    private List<String> shownPlayers = List.of();
    private String selectedPlayer;
    private float time;
    private long lastFrameNanos = System.nanoTime();
    private int frameNo;
    private boolean closing;
    private long closeArmedUntil;

    public ConsoleWindow(Model model) {
        this.model = model;
        this.animate = !"false".equalsIgnoreCase(System.getProperty("singularity.gui.animations"));
        this.sans = pickFont(Font.PLAIN, 13, "Segoe UI", "Inter", "SF Pro Text", "Noto Sans", "Dialog");
        this.mono = pickFont(Font.PLAIN, 13, "Cascadia Mono", "Cascadia Code", "JetBrains Mono", "Consolas", "Menlo",
                "DejaVu Sans Mono", "Monospaced");
        this.frame = new JFrame("Singularity Server");
        frame.setUndecorated(true);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        boolean perPixel = false;
        try {
            GraphicsDevice device = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            perPixel = device.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT);
            if (perPixel) {
                frame.setBackground(new Color(0, 0, 0, 0));
            }
        } catch (RuntimeException e) {
            perPixel = false; // headless (tests, previews) or an odd window system: a plain rectangle then
        }
        this.translucent = perPixel;
        this.root = new Root();
        frame.setContentPane(root);
        build();
        frame.setMinimumSize(new Dimension(900, 560));
        frame.setSize(1120, 700);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                requestStop();
            }
        });
    }

    // ---------------------------------------------------------------------------------------------- public

    /** Shows the window and starts its clock. */
    public void show() {
        frame.setLocationRelativeTo(null);
        try {
            frame.setMaximizedBounds(GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds());
        } catch (RuntimeException ignored) {
            // no screen information: maximising covers the whole screen
        }
        frame.setVisible(true);
        input.requestFocusInWindow();
        timer = new Timer(33, e -> step());
        timer.start();
        // for checking the design on a running server: -Dsingularity.gui.snapshot=<file.png> saves a picture of
        // the window (its own drawing, not the screen) 12 seconds after it opened
        String snapshot = System.getProperty("singularity.gui.snapshot");
        if (snapshot != null && !snapshot.isBlank()) {
            Timer once = new Timer(12_000, e -> {
                BufferedImage image = new BufferedImage(root.getWidth(), root.getHeight(), BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = image.createGraphics();
                g.setColor(new Color(0x2B2B33));
                g.fillRect(0, 0, image.getWidth(), image.getHeight());
                root.paint(g);
                g.dispose();
                try {
                    javax.imageio.ImageIO.write(image, "png", new java.io.File(snapshot));
                } catch (java.io.IOException | RuntimeException failed) {
                    append(clock(), "Window", "WARN", "Could not save the snapshot: " + failed);
                }
            });
            once.setRepeats(false);
            once.start();
        }
    }

    /** Takes the window away (the server is stopping). */
    public void close() {
        SwingUtilities.invokeLater(() -> {
            if (timer != null) {
                timer.stop();
            }
            frame.dispose();
        });
    }

    /**
     * Adds a line to the console. {@code level} is INFO, WARN, ERROR or DEBUG (anything else counts as INFO);
     * {@code source} is what stands in the brackets before the text. Any thread.
     */
    public void log(String time, String source, String level, String text) {
        pending.add(new String[] {time, source, level, text});
    }

    public JFrame frame() {
        return frame;
    }

    // --------------------------------------------------------------------------------------------- building

    private void build() {
        root.setLayout(new BorderLayout(12, 12));
        root.setBorder(BorderFactory.createEmptyBorder(PAD + 14, PAD + 16, PAD + 16, PAD + 16));

        header = buildHeader();
        root.add(header, BorderLayout.NORTH);
        left = buildLeft();
        root.add(left, BorderLayout.WEST);
        root.add(buildConsole(), BorderLayout.CENTER);
        right = buildRight();
        root.add(right, BorderLayout.EAST);

        Mover mover = new Mover();
        root.addMouseListener(mover);
        root.addMouseMotionListener(mover);
        header.addMouseListener(mover);
        header.addMouseMotionListener(mover);
    }

    private JComponent buildHeader() {
        JPanel bar = clear(new BorderLayout(12, 0));
        bar.setPreferredSize(new Dimension(10, 46));

        JPanel brand = clear(new BorderLayout(12, 0));
        brand.add(new Logo(), BorderLayout.WEST);
        JPanel names = clear(null);
        names.setLayout(new BoxLayout(names, BoxLayout.Y_AXIS));
        JLabel title = label("Singularity", sans.deriveFont(Font.BOLD, 19f), TEXT);
        JLabel sub = label(model.subtitle(), sans.deriveFont(12f), DIM);
        names.add(Box.createVerticalGlue());
        names.add(title);
        names.add(sub);
        names.add(Box.createVerticalGlue());
        brand.add(names, BorderLayout.CENTER);
        bar.add(brand, BorderLayout.WEST);

        JPanel centre = clear(new java.awt.GridBagLayout());
        centre.add(new Status());
        bar.add(centre, BorderLayout.CENTER);

        JPanel buttons = clear(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 12));
        buttons.add(new WindowButton(WARN, '-', "Minimise", () -> frame.setExtendedState(frame.getExtendedState() | JFrame.ICONIFIED)));
        buttons.add(new WindowButton(GOOD, '+', "Maximise", this::toggleMaximised));
        buttons.add(new WindowButton(PINK, 'x', "Stop the server and close", this::requestStop));
        bar.add(buttons, BorderLayout.EAST);
        return bar;
    }

    private JComponent buildLeft() {
        JPanel column = clear(null);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setPreferredSize(new Dimension(236, 10));

        Card tick = new Card("TICK RATE");
        gauge = new Gauge();
        tick.body.add(gauge, BorderLayout.CENTER);
        tick.setMaximumSize(new Dimension(Integer.MAX_VALUE, 210));
        tick.setPreferredSize(new Dimension(236, 210));
        column.add(tick);
        column.add(Box.createVerticalStrut(12));

        Card times = new Card("TICK TIME, LAST " + HISTORY + " S");
        spark = new Spark();
        times.body.add(spark, BorderLayout.CENTER);
        times.setMaximumSize(new Dimension(Integer.MAX_VALUE, 130));
        times.setPreferredSize(new Dimension(236, 130));
        column.add(times);
        column.add(Box.createVerticalStrut(12));

        Card mem = new Card("MEMORY");
        memory = new Meter();
        mem.body.add(memory, BorderLayout.CENTER);
        mem.setMaximumSize(new Dimension(Integer.MAX_VALUE, 86));
        mem.setPreferredSize(new Dimension(236, 86));
        column.add(mem);
        column.add(Box.createVerticalStrut(12));

        Card facts = new Card("SERVER");
        JPanel rows = clear(new GridLayout(3, 2, 0, 6));
        uptimeValue = fact(rows, "Uptime");
        playersValue = fact(rows, "Players");
        loadValue = fact(rows, "Load level");
        facts.body.add(rows, BorderLayout.NORTH);
        column.add(facts);
        return column;
    }

    private JLabel fact(JPanel rows, String name) {
        rows.add(label(name, sans.deriveFont(12.5f), DIM));
        JLabel value = label("-", sans.deriveFont(Font.BOLD, 12.5f), TEXT);
        value.setHorizontalAlignment(JLabel.RIGHT);
        value.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 3));
        rows.add(value);
        return value;
    }

    private JComponent buildConsole() {
        Card card = new Card("CONSOLE");
        card.solid = true;
        console = new JTextPane() {
            @Override
            protected void paintComponent(Graphics g) {
                smooth((Graphics2D) g);
                super.paintComponent(g);
            }

            @Override
            public boolean getScrollableTracksViewportWidth() {
                return true; // wrap long lines
            }
        };
        console.setEditable(false);
        console.setOpaque(false);
        console.setFont(mono);
        console.setForeground(TEXT);
        console.setCaretColor(PINK);
        console.setSelectionColor(new Color(160, 92, 255, 110));
        console.setSelectedTextColor(Color.WHITE);
        console.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 8));

        consoleScroll = new JScrollPane(console, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        consoleScroll.setOpaque(false);
        consoleScroll.getViewport().setOpaque(false);
        consoleScroll.setBorder(null);
        consoleScroll.getVerticalScrollBar().setUI(new SlimScrollBar());
        consoleScroll.getVerticalScrollBar().setOpaque(false);
        consoleScroll.getVerticalScrollBar().setPreferredSize(new Dimension(10, 10));
        consoleScroll.getVerticalScrollBar().setUnitIncrement(18);
        card.body.add(consoleScroll, BorderLayout.CENTER);

        input = new JTextField() {
            @Override
            protected void paintComponent(Graphics g) {
                smooth((Graphics2D) g);
                super.paintComponent(g);
                if (getText().isEmpty()) {
                    g.setColor(FAINT);
                    g.setFont(getFont());
                    FontMetrics fm = g.getFontMetrics();
                    g.drawString("Type a command and press Enter  (Up / Down: earlier commands)", getInsets().left,
                            (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
                }
            }
        };
        input.setOpaque(false);
        input.setFont(mono);
        input.setForeground(TEXT);
        input.setCaretColor(PINK);
        input.setSelectionColor(new Color(160, 92, 255, 110));
        input.setSelectedTextColor(Color.WHITE);
        input.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        input.addActionListener(e -> submit());
        input.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_UP && historyAt > 0) {
                    input.setText(history.get(--historyAt));
                } else if (e.getKeyCode() == KeyEvent.VK_DOWN && historyAt < history.size()) {
                    historyAt++;
                    input.setText(historyAt < history.size() ? history.get(historyAt) : "");
                }
            }
        });
        InputBox box = new InputBox(input);
        input.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                box.repaint();
            }

            @Override
            public void focusLost(FocusEvent e) {
                box.repaint();
            }
        });
        JPanel south = clear(new BorderLayout());
        south.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        south.add(box, BorderLayout.CENTER);
        card.body.add(south, BorderLayout.SOUTH);
        return card;
    }

    private JComponent buildRight() {
        JPanel column = clear(new BorderLayout(0, 12));
        column.setPreferredSize(new Dimension(222, 10));

        Card players = new Card("PLAYERS");
        playersTitle = players.title;
        playerList = new FitWidthPanel();
        playerList.setLayout(new BoxLayout(playerList, BoxLayout.Y_AXIS));
        JScrollPane scroll = new JScrollPane(playerList, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUI(new SlimScrollBar());
        scroll.getVerticalScrollBar().setOpaque(false);
        scroll.getVerticalScrollBar().setPreferredSize(new Dimension(8, 8));
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        players.body.add(scroll, BorderLayout.CENTER);
        column.add(players, BorderLayout.CENTER);

        Card quick = new Card("QUICK ACCESS");
        JPanel grid = clear(new GridLayout(0, 2, 8, 8));
        grid.add(new Pill("Save world", false, () -> run("save-all")));
        grid.add(new Pill("List players", false, () -> run("list")));
        grid.add(new Pill("Set day", false, () -> run("time set day")));
        grid.add(new Pill("Clear weather", false, () -> run("weather clear")));
        grid.add(new Pill("Tick report", false, () -> run("tick query")));
        grid.add(new Pill("Clear console", false, () -> console.setText("")));
        grid.add(new Pill("Open folder", false, () -> open(model.directory())));
        grid.add(new Pill("Open config", false, () -> open(model.directory().resolve("ServerConfig.toml"))));
        JPanel stack = clear(new BorderLayout(0, 8));
        stack.add(grid, BorderLayout.CENTER);
        Pill stop = new Pill("Stop server", true, this::requestStop);
        stop.setPreferredSize(new Dimension(10, 34));
        stack.add(stop, BorderLayout.SOUTH);
        quick.body.add(stack, BorderLayout.CENTER);
        column.add(quick, BorderLayout.SOUTH);
        return column;
    }

    // ---------------------------------------------------------------------------------------------- actions

    private void submit() {
        String command = input.getText().trim();
        input.setText("");
        if (command.isEmpty()) {
            return;
        }
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        if (history.isEmpty() || !history.get(history.size() - 1).equals(command)) {
            history.add(command);
        }
        historyAt = history.size();
        run(command);
    }

    private void run(String command) {
        append(clock(), "you", "CMD", "/" + command);
        try {
            model.command(command);
        } catch (RuntimeException e) {
            append(clock(), "Window", "ERROR", "Could not run the command: " + e);
        }
    }

    /** Closing the window stops the server (as the vanilla window does), but only on the second click. */
    private void requestStop() {
        if (closing) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now > closeArmedUntil) {
            closeArmedUntil = now + 4000;
            append(clock(), "Window", "WARN", "Click again within 4 seconds to stop the server.");
            header.repaint();
            return;
        }
        closing = true;
        append(clock(), "Window", "WARN", "Stopping the server...");
        header.repaint();
        Thread stopper = new Thread(model::stop, "Singularity window stop");
        stopper.setDaemon(true);
        stopper.start();
    }

    private void toggleMaximised() {
        int state = frame.getExtendedState();
        frame.setExtendedState((state & JFrame.MAXIMIZED_BOTH) != 0 ? state & ~JFrame.MAXIMIZED_BOTH : state | JFrame.MAXIMIZED_BOTH);
    }

    private void open(Path path) {
        try {
            if (!Files.exists(path)) {
                append(clock(), "Window", "WARN", path + " does not exist yet.");
                return;
            }
            Desktop.getDesktop().open(path.toFile());
        } catch (Exception e) {
            append(clock(), "Window", "WARN", "Could not open " + path + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------------------ clock

    /** One step of the window's clock: log lines, the once-a-second numbers, and the moving parts. */
    private void step() {
        long now = System.nanoTime();
        float dt = Math.min(0.25f, (now - lastFrameNanos) / 1e9f);
        lastFrameNanos = now;
        frameNo++;
        flushLog();
        if (now - lastSampleNanos >= 1_000_000_000L) {
            sample(now);
        }
        boolean minimised = (frame.getExtendedState() & JFrame.ICONIFIED) != 0;
        if (minimised || !animate) {
            return;
        }
        if (!frame.isActive() && frameNo % 6 != 0) {
            return; // in the background: 5 frames a second are plenty
        }
        time += frame.isActive() ? dt : dt * 6;
        for (Runnable r : perFrame) {
            r.run();
        }
        // the console keeps still: only the parts with ambient light and the outline are redrawn
        header.repaint();
        left.repaint();
        right.repaint();
        int w = root.getWidth();
        int h = root.getHeight();
        int edge = PAD + 6;
        root.repaint(0, 0, w, edge);
        root.repaint(0, h - edge, w, edge);
        root.repaint(0, 0, edge, h);
        root.repaint(w - edge, 0, edge, h);
    }

    private void sample(long now) {
        double seconds = (now - lastSampleNanos) / 1e9;
        lastSampleNanos = now;
        long ticks = model.tickCount();
        if (lastTicks >= 0 && seconds > 0) {
            tps = Math.min(20.0, (ticks - lastTicks) / seconds);
        }
        lastTicks = ticks;
        mspt = model.mspt();
        if (msptCount < HISTORY) {
            msptHistory[msptCount++] = (float) mspt;
        } else {
            System.arraycopy(msptHistory, 1, msptHistory, 0, HISTORY - 1);
            msptHistory[HISTORY - 1] = (float) mspt;
        }
        List<String> players = model.players();
        uptimeValue.setText(duration((System.currentTimeMillis() - startedAt) / 1000));
        playersValue.setText(players.size() + " / " + model.maxPlayers());
        int level = model.loadLevel();
        loadValue.setText(level == 0 ? "0, calm" : level + " of 10");
        loadValue.setForeground(level == 0 ? TEXT : level < 4 ? WARN : BAD);
        if (!players.equals(shownPlayers)) {
            shownPlayers = List.copyOf(players);
            rebuildPlayers();
        }
        if (!animate) {
            gauge.shown = tps;
            memory.shown = memory.target();
            header.repaint();
            left.repaint();
        }
    }

    private void rebuildPlayers() {
        playersTitle.setText("PLAYERS  " + shownPlayers.size());
        playerList.removeAll();
        if (shownPlayers.isEmpty()) {
            JLabel none = label("Nobody is online.", sans.deriveFont(12.5f), FAINT);
            none.setBorder(BorderFactory.createEmptyBorder(6, 4, 0, 0));
            playerList.add(none);
        }
        for (String name : shownPlayers) {
            playerList.add(new PlayerRow(name));
        }
        playerList.add(Box.createVerticalGlue());
        playerList.revalidate();
        playerList.repaint();
    }

    // ---------------------------------------------------------------------------------------------- console

    private void flushLog() {
        String[] line;
        int n = 0;
        while (n++ < 400 && (line = pending.poll()) != null) {
            append(line[0], line[1], line[2], line[3]);
        }
    }

    private void append(String time, String source, String level, String text) {
        StyledDocument doc = console.getStyledDocument();
        JScrollBar bar = consoleScroll.getVerticalScrollBar();
        boolean atBottom = bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 24;
        Color levelColour = switch (level) {
            case "WARN" -> WARN;
            case "ERROR", "FATAL" -> BAD;
            case "CMD" -> PINK;
            case "DEBUG" -> FAINT;
            default -> PURPLE;
        };
        Color textColour = switch (level) {
            case "WARN" -> new Color(0xFFE6A8);
            case "ERROR", "FATAL" -> new Color(0xFFB3C0);
            case "CMD" -> Color.WHITE;
            case "DEBUG" -> DIM;
            default -> TEXT;
        };
        try {
            if (doc.getLength() > 0) {
                doc.insertString(doc.getLength(), "\n", null);
            }
            doc.insertString(doc.getLength(), time + " ", style(FAINT, false));
            doc.insertString(doc.getLength(), source + " ", style(levelColour, true));
            doc.insertString(doc.getLength(), text.replaceAll("§.", "").stripTrailing(), style(textColour, false));
            // keep the document bounded: drop whole lines from the top
            var rootElement = doc.getDefaultRootElement();
            int extra = rootElement.getElementCount() - MAX_LINES;
            if (extra > 0) {
                doc.remove(0, rootElement.getElement(extra - 1).getEndOffset());
            }
        } catch (BadLocationException ignored) {
            // cannot happen: offsets come from the document itself
        }
        if (atBottom) {
            console.setCaretPosition(doc.getLength());
        }
    }

    private static SimpleAttributeSet style(Color colour, boolean bold) {
        SimpleAttributeSet set = new SimpleAttributeSet();
        StyleConstants.setForeground(set, colour);
        StyleConstants.setBold(set, bold);
        return set;
    }

    // ----------------------------------------------------------------------------------------------- pieces

    /** The window's body: rounded, dark, with slowly drifting light and a glowing pink-to-purple outline. */
    private final class Root extends JPanel {
        private final BufferedImage pinkLight = light(PINK);
        private final BufferedImage purpleLight = light(PURPLE);

        Root() {
            setOpaque(!translucent);
            setBackground(BG);
        }

        private Shape body() {
            int pad = translucent ? PAD : 0;
            int r = translucent ? RADIUS : 0;
            return new RoundRectangle2D.Float(pad, pad, getWidth() - 2 * pad - 1, getHeight() - 2 * pad - 1, r, r);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            smooth(g);
            int w = getWidth();
            int h = getHeight();
            Shape body = body();
            if (!translucent) {
                g.setColor(BG);
                g.fillRect(0, 0, w, h);
            }

            // glow: the outline again, wider and fainter, underneath
            LinearGradientPaint flow = flow(w, h);
            if (translucent) {
                g.setPaint(flow);
                for (int i = 5; i >= 1; i--) {
                    g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.035f + 0.012f * (5 - i)));
                    g.setStroke(new BasicStroke(i * 4.5f));
                    g.draw(body);
                }
                g.setComposite(AlphaComposite.SrcOver);
            }

            g.setColor(BG);
            g.fill(body);

            // ambient light: three soft lights on slow, different orbits, inside the body only
            Shape clip = g.getClip();
            g.clip(body);
            float t = time;
            drawLight(g, pinkLight, w * (0.16f + 0.10f * (float) Math.sin(t * 0.21)), h * (0.20f + 0.12f * (float) Math.cos(t * 0.17)), w * 0.62f, 0.34f);
            drawLight(g, purpleLight, w * (0.86f + 0.09f * (float) Math.cos(t * 0.15)), h * (0.74f + 0.14f * (float) Math.sin(t * 0.19)), w * 0.70f, 0.36f);
            drawLight(g, purpleLight, w * (0.50f + 0.30f * (float) Math.sin(t * 0.09 + 2)), h * (0.02f + 0.06f * (float) Math.cos(t * 0.13)), w * 0.50f, 0.22f);
            g.setClip(clip);

            // the outline itself
            g.setPaint(flow);
            g.setStroke(new BasicStroke(1.6f));
            g.draw(body);
            g.dispose();
        }

        /** Pink to purple and back, turning slowly around the window. */
        private LinearGradientPaint flow(int w, int h) {
            double angle = time * 0.35;
            float cx = w / 2f;
            float cy = h / 2f;
            float rx = (float) (Math.cos(angle) * w / 2);
            float ry = (float) (Math.sin(angle) * h / 2);
            if (Math.abs(rx) + Math.abs(ry) < 1) {
                rx = 1;
            }
            return new LinearGradientPaint(cx - rx, cy - ry, cx + rx, cy + ry, new float[] {0f, 0.5f, 1f},
                    new Color[] {PINK, PURPLE, new Color(0x6E7BFF)});
        }

        private void drawLight(Graphics2D g, BufferedImage light, float cx, float cy, float size, float alpha) {
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
            g.drawImage(light, Math.round(cx - size / 2), Math.round(cy - size / 2), Math.round(size), Math.round(size), null);
            g.setComposite(AlphaComposite.SrcOver);
        }

        /** A soft round light, drawn once; scaling a picture is far cheaper than a gradient fill per frame. */
        private static BufferedImage light(Color colour) {
            int size = 256;
            BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            g.setPaint(new RadialGradientPaint(size / 2f, size / 2f, size / 2f, new float[] {0f, 0.45f, 1f},
                    new Color[] {new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), 150),
                        new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), 55),
                        new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), 0)}));
            g.fillRect(0, 0, size, size);
            g.dispose();
            return image;
        }
    }

    /** A rounded panel with a small caption. Translucent (the ambient light shows through) unless {@code solid}. */
    private final class Card extends JPanel {
        final JLabel title;
        final JPanel body;
        boolean solid;

        Card(String caption) {
            super(new BorderLayout(0, 8));
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(12, 14, 14, 14));
            title = label(caption, sans.deriveFont(Font.BOLD, 10.5f), DIM);
            add(title, BorderLayout.NORTH);
            body = clear(new BorderLayout());
            add(body, BorderLayout.CENTER);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            smooth(g);
            Shape shape = new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1, getHeight() - 1, 18, 18);
            g.setColor(solid ? new Color(BG_CONSOLE.getRed(), BG_CONSOLE.getGreen(), BG_CONSOLE.getBlue(), 244) : new Color(255, 255, 255, 13));
            g.fill(shape);
            g.setPaint(new LinearGradientPaint(0, 0, 0, getHeight(), new float[] {0f, 1f},
                    new Color[] {new Color(255, 255, 255, 34), new Color(255, 255, 255, 10)}));
            g.setStroke(new BasicStroke(1f));
            g.draw(shape);
            g.dispose();
        }
    }

    /** The tick rate as a three-quarter ring; the ring and the number glide to a new value. */
    private final class Gauge extends JComponent {
        double shown = 20;

        Gauge() {
            perFrame.add(() -> shown += (tps - shown) * 0.12);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            smooth(g);
            int size = Math.min(getWidth(), getHeight() - 22);
            float x = (getWidth() - size) / 2f + 9;
            float y = 9;
            float d = size - 18;
            g.setStroke(new BasicStroke(9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(255, 255, 255, 22));
            g.draw(new Arc2D.Float(x, y, d, d, 225, -270, Arc2D.OPEN));
            double part = Math.max(0, Math.min(1, shown / 20.0));
            Color tone = shown >= 19 ? null : shown >= 15 ? WARN : BAD;
            if (tone == null) {
                g.setPaint(new LinearGradientPaint(x, y + d, x + d, y, new float[] {0f, 1f}, new Color[] {PINK, PURPLE}));
            } else {
                g.setColor(tone);
            }
            if (part > 0.004) {
                g.draw(new Arc2D.Double(x, y, d, d, 225, -270 * part, Arc2D.OPEN));
            }
            String number = String.format(Locale.ROOT, "%.1f", shown);
            g.setFont(sans.deriveFont(Font.BOLD, d * 0.30f));
            g.setColor(TEXT);
            FontMetrics fm = g.getFontMetrics();
            g.drawString(number, x + (d - fm.stringWidth(number)) / 2f, y + d / 2f + fm.getAscent() * 0.32f);
            g.setFont(sans.deriveFont(11f));
            g.setColor(DIM);
            fm = g.getFontMetrics();
            g.drawString("TPS", x + (d - fm.stringWidth("TPS")) / 2f, y + d / 2f + d * 0.26f);
            String below = String.format(Locale.ROOT, "%.1f ms per tick", mspt);
            g.setFont(sans.deriveFont(12f));
            g.setColor(mspt > 50 ? BAD : mspt > 40 ? WARN : DIM);
            fm = g.getFontMetrics();
            g.drawString(below, (getWidth() - fm.stringWidth(below)) / 2f, getHeight() - 5);
            g.dispose();
        }
    }

    /** Tick times of the last minutes as a filled curve; the dashed line is 50 ms, the longest a tick may take. */
    private final class Spark extends JComponent {
        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            smooth(g);
            int w = getWidth();
            int h = getHeight();
            float top = 50;
            for (int i = 0; i < msptCount; i++) {
                top = Math.max(top, msptHistory[i]);
            }
            top *= 1.15f;
            float limitY = h - 50 / top * h;
            g.setColor(new Color(255, 255, 255, 36));
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {4f, 4f}, 0f));
            g.drawLine(0, Math.round(limitY), w, Math.round(limitY));
            g.setFont(sans.deriveFont(10f));
            g.setColor(FAINT);
            g.drawString("50 ms", 2, Math.round(limitY) - 3);
            if (msptCount >= 2) {
                Path2D.Float line = new Path2D.Float();
                float step = (float) w / (HISTORY - 1);
                float x0 = w - (msptCount - 1) * step;
                float prevX = x0;
                float prevY = h - msptHistory[0] / top * h;
                line.moveTo(prevX, prevY);
                for (int i = 1; i < msptCount; i++) {
                    float x = x0 + i * step;
                    float y = Math.max(2, h - msptHistory[i] / top * h);
                    line.curveTo((prevX + x) / 2, prevY, (prevX + x) / 2, y, x, y); // smooth, not jagged
                    prevX = x;
                    prevY = y;
                }
                Path2D.Float area = new Path2D.Float(line);
                area.lineTo(prevX, h);
                area.lineTo(x0, h);
                area.closePath();
                g.setPaint(new LinearGradientPaint(0, 0, 0, h, new float[] {0f, 1f},
                        new Color[] {new Color(255, 95, 168, 90), new Color(160, 92, 255, 8)}));
                g.fill(area);
                g.setPaint(new LinearGradientPaint(0, 0, w, 0, new float[] {0f, 1f}, new Color[] {PURPLE, PINK}));
                g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(line);
                g.setColor(Color.WHITE);
                g.fill(new Ellipse2D.Float(prevX - 3, prevY - 3, 6, 6));
            } else {
                g.setFont(sans.deriveFont(12f));
                g.setColor(FAINT);
                g.drawString("collecting...", 2, h - 6);
            }
            g.dispose();
        }
    }

    /** Memory in use as a rounded bar that glides. */
    private final class Meter extends JComponent {
        double shown;

        Meter() {
            perFrame.add(() -> shown += (target() - shown) * 0.10);
        }

        double target() {
            Runtime rt = Runtime.getRuntime();
            return (double) (rt.totalMemory() - rt.freeMemory()) / rt.maxMemory();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            smooth(g);
            Runtime rt = Runtime.getRuntime();
            double used = (rt.totalMemory() - rt.freeMemory()) / 1073741824.0;
            double max = rt.maxMemory() / 1073741824.0;
            g.setFont(sans.deriveFont(Font.BOLD, 14f));
            g.setColor(TEXT);
            g.drawString(String.format(Locale.ROOT, "%.2f GB", used), 0, 15);
            String of = String.format(Locale.ROOT, "of %.1f GB", max);
            g.setFont(sans.deriveFont(12f));
            g.setColor(DIM);
            g.drawString(of, getWidth() - g.getFontMetrics().stringWidth(of), 15);
            int y = 26;
            int barHeight = 10;
            g.setColor(new Color(255, 255, 255, 22));
            g.fill(new RoundRectangle2D.Float(0, y, getWidth(), barHeight, barHeight, barHeight));
            float width = (float) Math.max(barHeight, getWidth() * Math.min(1, shown));
            if (shown > 0.9) {
                g.setColor(BAD);
            } else {
                g.setPaint(new LinearGradientPaint(0, 0, Math.max(1, getWidth()), 0, new float[] {0f, 1f}, new Color[] {PURPLE, PINK}));
            }
            g.fill(new RoundRectangle2D.Float(0, y, width, barHeight, barHeight, barHeight));
            g.dispose();
        }
    }

    /** The mark in the header: a black hole whose ring turns. */
    private final class Logo extends JComponent {
        Logo() {
            setPreferredSize(new Dimension(42, 42));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            smooth(g);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            g.rotate(time * 0.6, cx, cy);
            g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setPaint(new LinearGradientPaint(cx - 16, cy, cx + 16, cy, new float[] {0f, 1f}, new Color[] {PINK, PURPLE}));
            g.draw(new Arc2D.Float(cx - 15, cy - 15, 30, 30, 20, 250, Arc2D.OPEN));
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(255, 255, 255, 120));
            g.draw(new Arc2D.Float(cx - 9.5f, cy - 9.5f, 19, 19, 200, 150, Arc2D.OPEN));
            g.setColor(new Color(0x07050C));
            g.fill(new Ellipse2D.Float(cx - 6, cy - 6, 12, 12));
            g.dispose();
        }
    }

    /** "Starting", "Running" or "Stopping", with a dot that breathes. */
    private final class Status extends JComponent {
        Status() {
            setPreferredSize(new Dimension(250, 28));
            setFont(sans.deriveFont(Font.BOLD, 12f));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            smooth(g);
            boolean armed = System.currentTimeMillis() <= closeArmedUntil;
            String text = closing ? "Stopping" : armed ? "Click the pink button again to stop" : model.ready() ? "Running" : "Starting";
            Color tone = closing || armed ? PINK : model.ready() ? GOOD : WARN;
            g.setFont(getFont());
            FontMetrics fm = g.getFontMetrics();
            int width = fm.stringWidth(text) + 40;
            float x = (getWidth() - width) / 2f;
            Shape pill = new RoundRectangle2D.Float(x, 1, width, getHeight() - 2, getHeight() - 2, getHeight() - 2);
            g.setColor(new Color(tone.getRed(), tone.getGreen(), tone.getBlue(), 28));
            g.fill(pill);
            g.setColor(new Color(tone.getRed(), tone.getGreen(), tone.getBlue(), 90));
            g.draw(pill);
            float pulse = 0.5f + 0.5f * (float) Math.sin(time * 3.0);
            float r = 4f + 2.5f * pulse;
            g.setColor(new Color(tone.getRed(), tone.getGreen(), tone.getBlue(), (int) (70 * (1 - pulse))));
            g.fill(new Ellipse2D.Float(x + 16 - r - 2, getHeight() / 2f - r - 2, 2 * r + 4, 2 * r + 4));
            g.setColor(tone);
            g.fill(new Ellipse2D.Float(x + 12, getHeight() / 2f - 4, 8, 8));
            g.setColor(TEXT);
            g.drawString(text, x + 28, (getHeight() + fm.getAscent() - fm.getDescent()) / 2f);
            g.dispose();
        }
    }

    /** Anything that reacts to the mouse: its highlight fades in and out instead of switching. */
    private abstract class Hover extends JComponent {
        float glow;
        boolean inside;
        boolean pressed;

        Hover(Runnable action) {
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    inside = true;
                    settle();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    inside = false;
                    pressed = false;
                    settle();
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    pressed = true;
                    repaint();
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    boolean fire = pressed && inside;
                    pressed = false;
                    repaint();
                    if (fire && SwingUtilities.isLeftMouseButton(e)) {
                        action.run();
                    }
                }
            });
            perFrame.add(() -> {
                float target = inside ? 1f : 0f;
                if (Math.abs(target - glow) > 0.01f) {
                    glow += (target - glow) * 0.25f;
                    repaint();
                }
            });
        }

        private void settle() {
            if (!animate) {
                glow = inside ? 1f : 0f;
            }
            repaint();
        }
    }

    /** A rounded button of the quick-access grid. */
    private final class Pill extends Hover {
        private final String text;
        private final boolean danger;

        Pill(String text, boolean danger, Runnable action) {
            super(action);
            this.text = text;
            this.danger = danger;
            setPreferredSize(new Dimension(90, 32));
            setFont(sans.deriveFont(danger ? Font.BOLD : Font.PLAIN, 12f));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            smooth(g);
            float inset = pressed ? 1.5f : 0.5f;
            Shape shape = new RoundRectangle2D.Float(inset, inset, getWidth() - 2 * inset, getHeight() - 2 * inset, 16, 16);
            if (danger) {
                g.setPaint(new LinearGradientPaint(0, 0, getWidth(), 0, new float[] {0f, 1f},
                        new Color[] {new Color(255, 95, 168, 70 + (int) (110 * glow)), new Color(160, 92, 255, 60 + (int) (110 * glow))}));
            } else {
                g.setColor(new Color(255, 255, 255, 16 + (int) (22 * glow)));
            }
            g.fill(shape);
            g.setPaint(new LinearGradientPaint(0, 0, getWidth(), 0, new float[] {0f, 1f},
                    new Color[] {new Color(255, 95, 168, 40 + (int) (190 * glow)), new Color(160, 92, 255, 40 + (int) (190 * glow))}));
            g.setStroke(new BasicStroke(1.2f));
            g.draw(shape);
            g.setFont(getFont());
            g.setColor(glow > 0.5f || danger ? Color.WHITE : TEXT);
            FontMetrics fm = g.getFontMetrics();
            g.drawString(text, (getWidth() - fm.stringWidth(text)) / 2f, (getHeight() + fm.getAscent() - fm.getDescent()) / 2f);
            g.dispose();
        }
    }

    /** Minimise, maximise, stop: three coloured dots; the sign appears when the mouse is over one. */
    private final class WindowButton extends Hover {
        private final Color colour;
        private final char sign;

        WindowButton(Color colour, char sign, String tip, Runnable action) {
            super(action);
            this.colour = colour;
            this.sign = sign;
            setToolTipText(tip);
            setPreferredSize(new Dimension(18, 18));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            smooth(g);
            float grow = 1.5f * glow;
            g.setColor(new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), 150 + (int) (105 * glow)));
            g.fill(new Ellipse2D.Float(2 - grow, 2 - grow, 14 + 2 * grow, 14 + 2 * grow));
            if (glow > 0.15f) {
                g.setColor(new Color(20, 12, 30, (int) (220 * glow)));
                g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                switch (sign) {
                    case '-' -> g.drawLine(6, 9, 12, 9);
                    case '+' -> {
                        g.drawLine(6, 9, 12, 9);
                        g.drawLine(9, 6, 9, 12);
                    }
                    default -> {
                        g.drawLine(6, 6, 12, 12);
                        g.drawLine(12, 6, 6, 12);
                    }
                }
            }
            g.dispose();
        }
    }

    /** One player; a click opens the row to show what can be done with them. */
    private final class PlayerRow extends JPanel {
        PlayerRow(String name) {
            super(new BorderLayout(0, 6));
            setOpaque(false);
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
            boolean open = name.equals(selectedPlayer);
            JPanel top = clear(new BorderLayout(8, 0));
            top.add(new Avatar(name), BorderLayout.WEST);
            top.add(label(name, sans.deriveFont(Font.BOLD, 13f), TEXT), BorderLayout.CENTER);
            add(top, BorderLayout.NORTH);
            if (open) {
                JPanel actions = clear(new GridLayout(2, 2, 6, 6));
                actions.add(new Pill("Kick", false, () -> run("kick " + name)));
                actions.add(new Pill("Ban", false, () -> run("ban " + name)));
                actions.add(new Pill("Make op", false, () -> run("op " + name)));
                actions.add(new Pill("Remove op", false, () -> run("deop " + name)));
                add(actions, BorderLayout.CENTER);
            }
            setMaximumSize(new Dimension(Integer.MAX_VALUE, open ? 122 : 44));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            MouseAdapter toggle = new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    selectedPlayer = name.equals(selectedPlayer) ? null : name;
                    rebuildPlayers();
                }
            };
            addMouseListener(toggle);
            top.addMouseListener(toggle);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            if (getComponentCount() > 1) { // open
                Graphics2D g = (Graphics2D) g0.create();
                smooth(g);
                g.setColor(new Color(255, 255, 255, 14));
                g.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 14, 14));
                g.dispose();
            }
        }
    }

    /** A round badge with the player's first letter, coloured by the name. */
    private final class Avatar extends JComponent {
        private final String letter;
        private final Color colour;

        Avatar(String name) {
            this.letter = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT);
            float mix = (name.hashCode() & 0xFF) / 255f;
            this.colour = new Color((int) (PINK.getRed() * mix + PURPLE.getRed() * (1 - mix)),
                    (int) (PINK.getGreen() * mix + PURPLE.getGreen() * (1 - mix)),
                    (int) (PINK.getBlue() * mix + PURPLE.getBlue() * (1 - mix)));
            setPreferredSize(new Dimension(28, 28));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            smooth(g);
            g.setColor(colour);
            g.fill(new Ellipse2D.Float(1, 1, 26, 26));
            g.setFont(sans.deriveFont(Font.BOLD, 13f));
            g.setColor(Color.WHITE);
            FontMetrics fm = g.getFontMetrics();
            g.drawString(letter, 14 - fm.stringWidth(letter) / 2f, 14 + (fm.getAscent() - fm.getDescent()) / 2f);
            g.dispose();
        }
    }

    /** The command line's frame: rounded, with a prompt sign, lit while the field has the focus. */
    private final class InputBox extends JPanel {
        private final JTextField field;

        InputBox(JTextField field) {
            super(new BorderLayout());
            this.field = field;
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(9, 34, 9, 14));
            add(field, BorderLayout.CENTER);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            smooth(g);
            Shape shape = new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1, getHeight() - 1, 18, 18);
            g.setColor(new Color(255, 255, 255, 14));
            g.fill(shape);
            boolean focus = field.isFocusOwner();
            g.setPaint(new LinearGradientPaint(0, 0, getWidth(), 0, new float[] {0f, 1f},
                    new Color[] {new Color(255, 95, 168, focus ? 230 : 60), new Color(160, 92, 255, focus ? 230 : 60)}));
            g.setStroke(new BasicStroke(focus ? 1.5f : 1f));
            g.draw(shape);
            g.setColor(PINK);
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int cy = getHeight() / 2;
            g.drawLine(15, cy - 5, 20, cy);
            g.drawLine(20, cy, 15, cy + 5);
            g.dispose();
        }
    }

    /** A panel for a scroll pane that is always as wide as the pane (it only scrolls up and down). */
    private static final class FitWidthPanel extends JPanel implements javax.swing.Scrollable {
        FitWidthPanel() {
            setOpaque(false);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return 18;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(18, visible.height - 18);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    /** A thin rounded scroll bar without arrow buttons. */
    private static final class SlimScrollBar extends BasicScrollBarUI {
        @Override
        protected JButton createDecreaseButton(int orientation) {
            return none();
        }

        @Override
        protected JButton createIncreaseButton(int orientation) {
            return none();
        }

        private static JButton none() {
            JButton b = new JButton();
            b.setPreferredSize(new Dimension(0, 0));
            b.setMinimumSize(new Dimension(0, 0));
            b.setMaximumSize(new Dimension(0, 0));
            return b;
        }

        @Override
        protected void paintTrack(Graphics g, JComponent c, Rectangle bounds) {
            // no track
        }

        @Override
        protected void paintThumb(Graphics g0, JComponent c, Rectangle r) {
            Graphics2D g = (Graphics2D) g0.create();
            smooth(g);
            g.setColor(new Color(200, 170, 255, isDragging ? 150 : isThumbRollover() ? 110 : 60));
            g.fill(new RoundRectangle2D.Float(r.x + 2, r.y + 2, r.width - 4, r.height - 4, r.width - 4, r.width - 4));
            g.dispose();
        }
    }

    /** Dragging the header moves the window; dragging an edge or corner resizes it; a double click maximises. */
    private final class Mover extends MouseAdapter {
        private Point pressAt;
        private Rectangle startBounds;
        private int edges;

        private int edgesAt(MouseEvent e) {
            if (e.getComponent() != root || (frame.getExtendedState() & JFrame.MAXIMIZED_BOTH) != 0) {
                return 0;
            }
            int grip = PAD + 8;
            int found = 0;
            if (e.getX() < grip) {
                found |= 1;
            }
            if (e.getX() > root.getWidth() - grip) {
                found |= 2;
            }
            if (e.getY() < grip) {
                found |= 4;
            }
            if (e.getY() > root.getHeight() - grip) {
                found |= 8;
            }
            return found;
        }

        @Override
        public void mouseMoved(MouseEvent e) {
            int at = edgesAt(e);
            int cursor = switch (at) {
                case 1 -> Cursor.W_RESIZE_CURSOR;
                case 2 -> Cursor.E_RESIZE_CURSOR;
                case 4 -> Cursor.N_RESIZE_CURSOR;
                case 8 -> Cursor.S_RESIZE_CURSOR;
                case 5 -> Cursor.NW_RESIZE_CURSOR;
                case 6 -> Cursor.NE_RESIZE_CURSOR;
                case 9 -> Cursor.SW_RESIZE_CURSOR;
                case 10 -> Cursor.SE_RESIZE_CURSOR;
                default -> Cursor.DEFAULT_CURSOR;
            };
            e.getComponent().setCursor(Cursor.getPredefinedCursor(cursor));
        }

        @Override
        public void mousePressed(MouseEvent e) {
            pressAt = e.getLocationOnScreen();
            startBounds = frame.getBounds();
            edges = edgesAt(e);
        }

        @Override
        public void mouseClicked(MouseEvent e) {
            if (e.getClickCount() == 2 && e.getComponent() == header) {
                toggleMaximised();
            }
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            if (pressAt == null || (frame.getExtendedState() & JFrame.MAXIMIZED_BOTH) != 0) {
                return;
            }
            Point now = e.getLocationOnScreen();
            int dx = now.x - pressAt.x;
            int dy = now.y - pressAt.y;
            Rectangle b = new Rectangle(startBounds);
            if (edges == 0) {
                b.translate(dx, dy);
            } else {
                Dimension min = frame.getMinimumSize();
                if ((edges & 1) != 0) {
                    int width = Math.max(min.width, b.width - dx);
                    b.x += b.width - width;
                    b.width = width;
                }
                if ((edges & 2) != 0) {
                    b.width = Math.max(min.width, b.width + dx);
                }
                if ((edges & 4) != 0) {
                    int height = Math.max(min.height, b.height - dy);
                    b.y += b.height - height;
                    b.height = height;
                }
                if ((edges & 8) != 0) {
                    b.height = Math.max(min.height, b.height + dy);
                }
            }
            frame.setBounds(b);
        }
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private static void smooth(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    }

    private static JPanel clear(java.awt.LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        panel.setOpaque(false);
        return panel;
    }

    private static JLabel label(String text, Font font, Color colour) {
        JLabel label = new JLabel(text) {
            @Override
            protected void paintComponent(Graphics g) {
                smooth((Graphics2D) g);
                super.paintComponent(g);
            }
        };
        label.setFont(font);
        label.setForeground(colour);
        return label;
    }

    private static Font pickFont(int style, int size, String... families) {
        try {
            List<String> have = List.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
            for (String family : families) {
                if (have.contains(family)) {
                    return new Font(family, style, size);
                }
            }
        } catch (RuntimeException ignored) {
            // fall through to the last, logical family
        }
        return new Font(families[families.length - 1], style, size);
    }

    private static String clock() {
        return java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    static String duration(long seconds) {
        if (seconds < 60) {
            return seconds + " s";
        }
        if (seconds < 3600) {
            return seconds / 60 + " min " + seconds % 60 + " s";
        }
        if (seconds < 86_400) {
            return seconds / 3600 + " h " + seconds % 3600 / 60 + " min";
        }
        return seconds / 86_400 + " d " + seconds % 86_400 / 3600 + " h";
    }

    // ------------------------------------------------------------------------------------------------- demo

    /**
     * Shows the window with made-up numbers, without a server: {@code java -cp singularity-loader.jar
     * net.singularitymc.gui.ConsoleWindow}. With {@code --png <file>} it draws one picture of the window into the
     * file instead and exits (used to look at the design without a screen).
     */
    public static void main(String[] args) throws Exception {
        String png = args.length == 2 && args[0].equals("--png") ? args[1] : null;
        List<String> names = List.of("Astra", "Nebula_", "quasar99", "Pulsar", "EventHorizon");
        long start = System.nanoTime();
        Model demo = new Model() {
            @Override
            public String subtitle() {
                return "Loader 4.4.0  ·  Minecraft 26.3  ·  demo";
            }

            @Override
            public boolean ready() {
                return true;
            }

            @Override
            public long tickCount() {
                return (System.nanoTime() - start) / 50_000_000L;
            }

            @Override
            public double mspt() {
                double t = (System.nanoTime() - start) / 1e9;
                return 18 + 9 * Math.sin(t / 3) + 5 * Math.sin(t * 1.7);
            }

            @Override
            public List<String> players() {
                return names;
            }

            @Override
            public int maxPlayers() {
                return 20;
            }

            @Override
            public int loadLevel() {
                return 0;
            }

            @Override
            public Path directory() {
                return Path.of(".").toAbsolutePath().normalize();
            }

            @Override
            public void command(String command) {
                // nothing to run in the demo
            }

            @Override
            public void stop() {
                System.exit(0);
            }
        };
        SwingUtilities.invokeAndWait(() -> {
            ConsoleWindow window = new ConsoleWindow(demo);
            window.log("12:00:01", "Singularity", "INFO", "Loading 4 mods: Singularity API, Example Mod, Totem Teleport, Stress Test");
            window.log("12:00:04", "Server thread", "INFO", "Starting minecraft server version 26.3");
            window.log("12:00:04", "Server thread", "INFO", "Preparing level \"world\"");
            window.log("12:00:09", "Server thread", "INFO", "Done (4.137s)! For help, type \"help\"");
            window.log("12:00:09", "Server Manager", "INFO", "Enabled");
            window.log("12:00:31", "Server thread", "INFO", "Astra joined the game");
            window.log("12:01:12", "Server thread", "WARN", "Can't keep up! Is the server overloaded? Running 2094ms or 41 ticks behind");
            window.log("12:01:40", "AntiCheat", "WARN", "[AC] quasar99 failed speed (0.900 blocks/tick, limit 0.370), VL 2");
            window.log("12:02:02", "Server thread", "ERROR", "Could not save the scoreboard: disk full");
            if (png == null) {
                window.show();
                return;
            }
            // a picture: lay the window out without showing it, fill in a minute of numbers, paint it
            window.frame.addNotify();
            window.frame.validate();
            for (int i = 0; i < 70; i++) {
                window.msptHistory[window.msptCount++] = (float) (18 + 9 * Math.sin(i / 3.0) + 5 * Math.sin(i * 1.7) + (i == 52 ? 34 : 0));
            }
            window.tps = 19.6;
            window.mspt = 21.4;
            window.time = 2.4f;
            window.sample(System.nanoTime());
            window.selectedPlayer = "Nebula_";
            window.rebuildPlayers();
            window.flushLog();
            window.gauge.shown = 19.6;
            window.memory.shown = window.memory.target();
            window.frame.validate();
            BufferedImage image = new BufferedImage(window.frame.getWidth(), window.frame.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            g.setColor(new Color(0x2B2B33)); // stands in for the desktop behind the window
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            window.root.paint(g);
            g.dispose();
            try {
                javax.imageio.ImageIO.write(image, "png", new java.io.File(png));
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            window.frame.dispose();
        });
        if (png != null) {
            System.exit(0);
        }
    }
}
