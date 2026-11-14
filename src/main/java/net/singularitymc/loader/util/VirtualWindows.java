package net.singularitymc.loader.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The virtual 64-bit world (docs/FARLANDS.md). Real block coordinates are 32-bit, so 64-bit places are visited
 * through windows: a 2^24-block square of 64-bit space, aligned to 2^24, shown in a reserved real square of the same
 * size. Inside a window, virtual = window's virtual corner + (real - window's real corner), so walking is seamless;
 * the terrain there is generated from the virtual position ({@link FarLandsStages}).
 *
 * <p>Real squares come from the far negative X/Z corner of the world (slot -63, -63 first, moving inwards), never
 * within two squares of the axes. The mapping is saved to {@code singularity-virtual-windows.txt} in the world folder,
 * one window per line: {@code virtualX virtualZ slotX slotZ}.
 */
public final class VirtualWindows {
    public static final int SIZE_BITS = 24;
    public static final int SIZE = 1 << SIZE_BITS; // 16,777,216 blocks
    private static final String FILE = "singularity-virtual-windows.txt";

    /** A window: virtual corner (multiple of SIZE) and its real slot (real corner = slot * SIZE). */
    public record Window(long virtualX, long virtualZ, int slotX, int slotZ) {
        public int realX() {
            return slotX * SIZE;
        }

        public int realZ() {
            return slotZ * SIZE;
        }

        public long virtualX(int realX) {
            return virtualX + (realX - (long) realX());
        }

        public long virtualZ(int realZ) {
            return virtualZ + (realZ - (long) realZ());
        }
    }

    private static final Map<Long, Window> BY_SLOT = new ConcurrentHashMap<>();
    private static final Map<String, Window> BY_VIRTUAL = new ConcurrentHashMap<>();
    private static volatile Path file;

    private VirtualWindows() {}

    /** Reads the windows of a world (called when the server creates its levels). */
    public static synchronized void load(Path worldDir) {
        BY_SLOT.clear();
        BY_VIRTUAL.clear();
        file = worldDir.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] p = line.strip().split("\\s+");
                if (p.length == 4) {
                    add(new Window(Long.parseLong(p[0]), Long.parseLong(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])));
                }
            }
        } catch (IOException | NumberFormatException e) {
            System.getLogger("Singularity").log(System.Logger.Level.ERROR, "Could not read " + file + ": " + e);
        }
    }

    private static void add(Window w) {
        BY_SLOT.put(slotKey(w.slotX(), w.slotZ()), w);
        BY_VIRTUAL.put(w.virtualX() + " " + w.virtualZ(), w);
    }

    private static long slotKey(int slotX, int slotZ) {
        return (long) slotX << 32 ^ slotZ & 0xFFFFFFFFL;
    }

    /** The window showing this real block column, or {@code null}. Lock-free; called during chunk generation. */
    public static Window atReal(int realX, int realZ) {
        if (BY_SLOT.isEmpty()) {
            return null;
        }
        return BY_SLOT.get(slotKey(realX >> SIZE_BITS, realZ >> SIZE_BITS));
    }

    /** Corner of the window containing a virtual coordinate. Windows tile 64-bit space exactly (2^63 / 2^24). */
    static long corner(long v) {
        return v >> SIZE_BITS << SIZE_BITS;
    }

    /**
     * The window containing (x, z), created on first use. {@code null} when no free real square is left inside
     * {@code limit} (max_coordinate), or the world is too small for any.
     */
    public static synchronized Window windowFor(long x, long z, int limit) {
        long vx = corner(x);
        long vz = corner(z);
        Window w = BY_VIRTUAL.get(vx + " " + vz);
        if (w != null) {
            return w;
        }
        for (int[] slot : slots(limit)) {
            if (!BY_SLOT.containsKey(slotKey(slot[0], slot[1]))) {
                w = new Window(vx, vz, slot[0], slot[1]);
                add(w);
                save();
                return w;
            }
        }
        return null;
    }

    /** Free real squares, farthest first: entirely inside -limit, at least two squares away from both axes. */
    static List<int[]> slots(int limit) {
        int far = -(limit / SIZE); // the farthest slot whose square still starts inside the world
        List<int[]> out = new ArrayList<>();
        for (int ring = far; ring <= -3; ring++) {
            for (int other = far; other <= ring; other++) {
                out.add(new int[] {ring, other});
                if (other != ring) {
                    out.add(new int[] {other, ring});
                }
            }
        }
        return out;
    }

    private static void save() {
        Path f = file;
        if (f == null) {
            return;
        }
        StringBuilder sb = new StringBuilder("# Singularity virtual 64-bit windows: virtualX virtualZ slotX slotZ (real corner = slot * 16777216)\n");
        for (Window w : BY_VIRTUAL.values()) {
            sb.append(w.virtualX()).append(' ').append(w.virtualZ()).append(' ').append(w.slotX()).append(' ').append(w.slotZ()).append('\n');
        }
        try {
            Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.getLogger("Singularity").log(System.Logger.Level.ERROR, "Could not save " + f + ": " + e);
        }
    }

    /** Tests only. */
    static synchronized void reset() {
        BY_SLOT.clear();
        BY_VIRTUAL.clear();
        file = null;
    }
}
