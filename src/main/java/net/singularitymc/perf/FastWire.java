package net.singularitymc.perf;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.redstone.NeighborUpdater;
import net.singularitymc.loader.util.Log;

/**
 * The opt-in fast redstone dust ({@code redstone_wire_algorithm = "fast"}, 4.4).
 *
 * <p>Vanilla lets every dust settle by itself: a dust that changes sets its new power and notifies its surroundings,
 * each notified dust recomputes, and so on. Powering a line down takes many rounds that way (15, 13, 11, ...), each
 * with 42 notifications per dust. Here a change is worked out for the whole affected piece of the network at once:
 *
 * <ol>
 *   <li>If the dust that was asked to update already has the power vanilla would give it, nothing happens (as in vanilla).</li>
 *   <li>Otherwise every dust within 15 steps is collected (a change cannot reach farther: power drops by one per
 *       step); dust at step 16 keeps its power and acts as a fixed input.</li>
 *   <li>The final power of each collected dust is computed from the real sources (torches, repeaters, powered
 *       blocks: vanilla's own {@code getBlockSignal}) and the fixed inputs, strongest first. The connection rules
 *       are vanilla's: straight neighbours, up a block if nothing solid is above the dust, down a block that is not
 *       solid, and the one-way step over a non-solid block.</li>
 *   <li>All changed dust is set, then every other block next to a changed dust (two steps around, as in vanilla) is
 *       notified once.</li>
 * </ol>
 *
 * <p>The powers that result are vanilla's. What differs is the order in which other components hear about it, and
 * that they hear once, after all dust has its final power, instead of during the intermediate states. Contraptions
 * built on vanilla's update order can behave differently, which is why this is opt-in.
 */
public final class FastWire {
    private FastWire() {}

    private static final Log LOG = Log.get("Singularity");
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    /** More dust than this in reach: let vanilla do it. */
    private static final int MAX_REGION = 4096;

    private static MethodHandle blockSignal;
    private static boolean unavailable;

    private static final class Node {
        final BlockPos pos;
        final BlockState state;
        final int current;
        final int distance;
        int power;
        final List<Node> receivers = new ArrayList<>(4);

        Node(BlockPos pos, BlockState state, int distance) {
            this.pos = pos;
            this.state = state;
            this.current = state.getValue(BlockStateProperties.POWER);
            this.distance = distance;
        }

        boolean fixed() {
            return distance > 15;
        }
    }

    /**
     * Handles a dust update. Returns false if vanilla has to do it (not available on this version, too large).
     *
     * @param evaluator the game's {@code DefaultRedstoneWireEvaluator}
     */
    public static boolean update(Object evaluator, Level level, BlockPos pos, BlockState state) {
        if (unavailable || !PerfHooks.onServerThread()) {
            return false;
        }
        Block wire = state.getBlock();
        try {
            if (blockSignal == null) {
                blockSignal = findBlockSignal(evaluator.getClass());
            }
            // 1. nothing to do?
            int target = Math.max(source(evaluator, level, pos), incoming(level, pos, wire, null) - 1);
            if (target == state.getValue(BlockStateProperties.POWER)) {
                return true;
            }
            // 2. the dust within reach
            Map<BlockPos, Node> nodes = new HashMap<>();
            ArrayDeque<Node> queue = new ArrayDeque<>();
            Node start = new Node(pos.immutable(), state, 0);
            nodes.put(start.pos, start);
            queue.add(start);
            List<Node> region = new ArrayList<>();
            while (!queue.isEmpty()) {
                Node n = queue.poll();
                if (n.fixed()) {
                    continue;
                }
                region.add(n);
                if (region.size() > MAX_REGION) {
                    return false;
                }
                for (Direction d : HORIZONTAL) {
                    BlockPos side = n.pos.relative(d);
                    for (int dy = -1; dy <= 1; dy++) {
                        BlockPos p = dy == 0 ? side : dy < 0 ? side.below() : side.above();
                        if (!nodes.containsKey(p)) {
                            BlockState s = level.getBlockState(p);
                            if (s.is(wire)) {
                                Node m = new Node(p, s, n.distance + 1);
                                nodes.put(p, m);
                                queue.add(m);
                            }
                        }
                    }
                }
            }
            // 3. final powers: who receives from whom (vanilla's rule, seen from the receiver), then strongest first
            for (Node n : region) {
                final Node receiver = n;
                incoming(level, n.pos, wire, giver -> {
                    Node g = nodes.get(giver);
                    if (g != null) {
                        g.receivers.add(receiver);
                    }
                });
                n.power = source(evaluator, level, n.pos);
            }
            @SuppressWarnings("unchecked")
            List<Node>[] buckets = new List[16];
            for (int i = 0; i < 16; i++) {
                buckets[i] = new ArrayList<>();
            }
            for (Node n : nodes.values()) {
                if (n.fixed()) {
                    n.power = n.current;
                }
                buckets[n.power].add(n);
            }
            for (int p = 15; p >= 2; p--) {
                List<Node> bucket = buckets[p];
                for (int i = 0; i < bucket.size(); i++) {
                    Node n = bucket.get(i);
                    if (n.power != p) {
                        continue; // raised since it was put here
                    }
                    for (Node r : n.receivers) {
                        if (!r.fixed() && r.power < p - 1) {
                            r.power = p - 1;
                            buckets[p - 1].add(r);
                        }
                    }
                }
            }
            // 4. set, then notify
            List<Node> changed = new ArrayList<>();
            for (Node n : region) {
                if (n.power != n.current && level.getBlockState(n.pos) == n.state) {
                    level.setBlock(n.pos, n.state.setValue(BlockStateProperties.POWER, n.power), 2);
                    changed.add(n);
                }
            }
            LongOpenHashSet told = new LongOpenHashSet();
            for (Node n : changed) {
                notifyAround(level, n.pos, wire, told);
                for (Direction d : Direction.values()) {
                    notifyAround(level, n.pos.relative(d), wire, told);
                }
            }
            return true;
        } catch (ReflectiveOperationException t) {
            unavailable = true;
            LOG.error("redstone_wire_algorithm = \"fast\" is not available on this Minecraft version ({}); using vanilla's dust.", t.toString());
            return false;
        }
    }

    /** Notifies the six blocks around {@code center}, once each per update, leaving out dust (it already has its final power). */
    private static void notifyAround(Level level, BlockPos center, Block wire, LongOpenHashSet told) {
        for (Direction d : NeighborUpdater.UPDATE_ORDER) {
            BlockPos target = center.relative(d);
            if (told.add(target.asLong()) && !level.getBlockState(target).is(wire)) {
                level.neighborChanged(target, wire, null);
            }
        }
    }

    /** Power from everything that is not dust: vanilla's own {@code getBlockSignal}. */
    private static int source(Object evaluator, Level level, BlockPos pos) {
        try {
            return (int) blockSignal.invoke(evaluator, level, pos);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private interface Givers {
        void accept(BlockPos giver);
    }

    /**
     * Vanilla's {@code getIncomingWireSignal}: the strongest dust this dust takes power from. With {@code givers}
     * every such dust is reported too.
     */
    private static int incoming(Level level, BlockPos pos, Block wire, Givers givers) {
        int best = 0;
        boolean solidAbove = level.getBlockState(pos.above()).isRedstoneConductor(level, pos.above());
        for (Direction d : HORIZONTAL) {
            BlockPos side = pos.relative(d);
            BlockState sideState = level.getBlockState(side);
            best = Math.max(best, power(sideState, wire, side, givers));
            if (sideState.isRedstoneConductor(level, side)) {
                if (!solidAbove) {
                    BlockPos up = side.above();
                    best = Math.max(best, power(level.getBlockState(up), wire, up, givers));
                }
            } else {
                BlockPos down = side.below();
                best = Math.max(best, power(level.getBlockState(down), wire, down, givers));
            }
        }
        return best;
    }

    private static int power(BlockState state, Block wire, BlockPos pos, Givers givers) {
        if (!state.is(wire)) {
            return 0;
        }
        if (givers != null) {
            givers.accept(pos.immutable());
        }
        return state.getValue(BlockStateProperties.POWER);
    }

    private static MethodHandle findBlockSignal(Class<?> evaluator) throws ReflectiveOperationException {
        for (Class<?> c = evaluator; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals("getBlockSignal") && p.length == 2 && p[0] == Level.class && p[1] == BlockPos.class
                    && m.getReturnType() == int.class) {
                    m.setAccessible(true);
                    return MethodHandles.lookup().unreflect(m);
                }
            }
        }
        throw new NoSuchMethodException("getBlockSignal(Level, BlockPos)");
    }
}
