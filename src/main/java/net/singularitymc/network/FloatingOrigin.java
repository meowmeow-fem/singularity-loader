package net.singularitymc.network;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.PositionAndRotation;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.PositionStep;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;
import net.singularitymc.compat.GameShims;
import net.singularitymc.loader.util.WidePacking;

/**
 * experimental_long_positions, 3.5.6 (docs/ROADMAP_64BIT.md): every player's client sees the world shifted by an
 * origin near that player, a multiple of 2^20 blocks, so a vanilla client (32-bit coordinates, clamped at 30 million)
 * can play anywhere. Players within 2^23 blocks of 0 keep the origin 0 and see true coordinates. A teleport (or a
 * walk) to more than 2^23 blocks from the current origin moves it: the player's chunks and entities are dropped in
 * the old frame and tracked again in the new one.
 *
 * <p>The shift is applied where packets are encoded and decoded: block, section and chunk positions in their codecs
 * (see {@code FriendlyByteBufWideMixin}), the packets with raw coordinates here and in a few write mixins. The origin
 * is stored on the connection's channel and changed on its event loop, so it stays in order with the packets.
 * Text (chat, F3) keeps the client's view: the F3 coordinates are the true ones minus the origin.
 */
public final class FloatingOrigin {
    /** Origins are multiples of this (chunk, region and section aligned). */
    public static final long STEP = 1L << 20;
    /** Within this distance of 0 the origin is 0; farther than this (plus one step) from the origin, it moves. */
    public static final long KEEP = 1L << 23;
    private static final long[] ZERO = {0, 0};
    private static final AttributeKey<long[]> ORIGIN = AttributeKey.valueOf("singularity_floating_origin");
    private static final Map<ServerPlayer, long[]> ORIGINS = Collections.synchronizedMap(new WeakHashMap<>());

    private FloatingOrigin() {}

    // ---- Main thread ----

    public static long[] of(ServerPlayer player) {
        long[] o = ORIGINS.get(player);
        return o == null ? ZERO : o;
    }

    /** The origin for a position: 0 near the middle of the world, else the nearest multiple of {@link #STEP}. */
    public static long axis(double v) {
        return Math.abs(v) < KEEP ? 0 : Math.round(v / STEP) * STEP;
    }

    /** Whether a position is still in the player's frame. */
    public static boolean fits(ServerPlayer player, double x, double z) {
        long[] o = of(player);
        return Math.abs(x - o[0]) < KEEP + STEP && Math.abs(z - o[1]) < KEEP + STEP;
    }

    /** Sets the origin for the packets sent to this player from now on. */
    public static void set(ServerPlayer player, Connection connection, long ox, long oz) {
        long[] o = {ox, oz};
        ORIGINS.put(player, o);
        Channel channel = ((ChannelHolder) connection).singularity$channel();
        if (channel != null) {
            channel.eventLoop().execute(() -> channel.attr(ORIGIN).set(o));
        }
        // For the Singularity Wide Client mod (true coordinates on F3); the numbers ride in the payload's id, which
        // is all that survives for an unknown payload. Vanilla clients drop it.
        connection.send(new ClientboundCustomPayloadPacket(new DiscardedPayload(
            Identifier.fromNamespaceAndPath("singularity", "origin/" + ox + "/" + oz))));
    }

    /**
     * Before the server tells the player where they are: the first time (joining, respawning: a new player object),
     * the frame around that position; later, if the position is outside the frame, the frame moves there.
     *
     * @param tracked whether the player is in their level already (else the client is about to get everything anew)
     * @param retrack drops and re-tracks the player's chunks and entities around the {@code set} in the middle
     */
    public static void beforePositionSync(ServerPlayer player, Connection connection, boolean tracked, Retracker retrack) {
        long ox = axis(player.getX());
        long oz = axis(player.getZ());
        if (!ORIGINS.containsKey(player) || !tracked) {
            set(player, connection, ox, oz);
            return;
        }
        if (fits(player, player.getX(), player.getZ())) {
            return;
        }
        retrack.retrack(() -> set(player, connection, ox, oz));
        player.sendSystemMessage(Component.literal("[Singularity] Your client's coordinates are now shifted by "
            + ox + ", " + oz + " (true position = F3 position + that)."));
    }

    /** Drops the player's chunks and entities, runs the change, tracks them again. */
    @FunctionalInterface
    public interface Retracker {
        void retrack(Runnable change);
    }

    /** Mixed into {@code Connection}: its channel. */
    public interface ChannelHolder {
        Channel singularity$channel();
    }

    // ---- Event loop: encoding and decoding ----

    public static long[] origin(Channel channel) {
        long[] o = channel.attr(ORIGIN).get();
        return o == null ? ZERO : o;
    }

    /** A clientbound packet in the client's frame (the origin set by {@link WidePacking#setNetworkOrigin}). */
    public static Packet<?> toClient(Packet<?> packet) {
        if (!WidePacking.hasNetworkOrigin()) {
            return packet;
        }
        long ox = WidePacking.networkOriginX();
        long oz = WidePacking.networkOriginZ();
        return shiftPacket(packet, -ox, -oz);
    }

    /** A serverbound packet in the server's frame. */
    public static Object fromClient(Object packet, long[] origin) {
        if (origin[0] == 0 && origin[1] == 0 || !(packet instanceof Packet<?> p)) {
            return packet;
        }
        if (p instanceof ServerboundAcceptTeleportationPacket || p instanceof ServerboundMoveVehiclePacket) {
            return shiftPacket(p, origin[0], origin[1]);
        }
        if (p instanceof MovePlayerShift move) {
            move.singularity$shift(origin[0], origin[1]);
        }
        return p;
    }

    /** Mixed into {@code ServerboundMovePlayerPacket}: moves its position (a freshly decoded packet, not shared). */
    public interface MovePlayerShift {
        void singularity$shift(double dx, double dz);
    }

    private static Packet<?> shiftPacket(Packet<?> p, long dx, long dz) {
        Shift s = new Shift(dx, dz);
        if (p instanceof ClientboundLevelChunkWithLightPacket || p instanceof ClientboundLightUpdatePacket) {
            return (Packet<?>) copy((Record) p, s, "chunk");
        }
        if (p instanceof ClientboundPlayerPositionPacket pp) {
            return new ClientboundPlayerPositionPacket(pp.id(), s.change(pp.change(), pp.relatives()), pp.relatives());
        }
        if (p instanceof ClientboundTeleportEntityPacket tp) {
            return new ClientboundTeleportEntityPacket(tp.id(), s.change(tp.change(), tp.relatives()), tp.relatives(), tp.onGround());
        }
        if (p instanceof ClientboundExplodePacket || p instanceof ClientboundLevelParticlesPacket || p instanceof ClientboundEntityPositionSyncPacket
            || p instanceof ClientboundMoveVehiclePacket || p instanceof ClientboundDamageEventPacket
            || p instanceof ServerboundAcceptTeleportationPacket || p instanceof ServerboundMoveVehiclePacket) {
            return (Packet<?>) copy((Record) p, s, "");
        }
        return p;
    }

    /** Absolute positions in packet records, by component name (velocities and offsets have other names). */
    private static final Set<String> POSITIONS = Set.of("center", "position", "endPosition", "sourcePosition", "movingTo", "steps");

    private record Shift(long dx, long dz) {
        Vec3 vec(Vec3 v) {
            return new Vec3(v.x + dx, v.y, v.z + dz);
        }

        PositionMoveRotation change(PositionMoveRotation c, Set<Relative> relatives) {
            Vec3 p = c.position();
            Vec3 moved = new Vec3(relatives.contains(Relative.X) ? p.x : p.x + dx, p.y, relatives.contains(Relative.Z) ? p.z : p.z + dz);
            return new PositionMoveRotation(moved, c.deltaMovement(), c.yRot(), c.xRot());
        }

        /** {@code mode}: "chunk" for int chunk coordinates x / z, else doubles x / z and position-named values. */
        Object value(String name, Object v, String mode) {
            if (mode.equals("chunk")) {
                // int code made these of the narrowed chunk coordinates (WidePacking.narrow), near the origin (-dx)
                if (v instanceof Integer i && name.equals("x")) return (int) (WidePacking.unnarrowNear(i, -dx, 4) + (dx >> 4));
                if (v instanceof Integer i && name.equals("z")) return (int) (WidePacking.unnarrowNear(i, -dz, 4) + (dz >> 4));
                return v;
            }
            if (v instanceof Double d && name.equals("x")) return d + dx;
            if (v instanceof Double d && name.equals("z")) return d + dz;
            if (!POSITIONS.contains(name)) return v;
            return position(v);
        }

        Object position(Object v) {
            if (v instanceof Vec3 vec) return vec(vec);
            if (v instanceof Optional<?> o) return o.map(this::position);
            if (v instanceof List<?> list) {
                List<Object> out = new ArrayList<>(list.size());
                for (Object e : list) out.add(position(e));
                return out;
            }
            if (MODERN_POSITIONS) {
                Object shifted = ModernPositions.shift(this, v);
                if (shifted != null) return shifted;
            }
            if (v instanceof Record r) return copy(r, this, ""); // PositionPath records (endPosition, steps)
            return v;
        }
    }

    /** {@code PositionStep} and {@code PositionAndRotation} exist from Minecraft 26.3 on; 26.1 and 26.2 have neither. */
    private static final boolean MODERN_POSITIONS = GameShims.hasClass("net.minecraft.world.entity.PositionStep")
        && GameShims.hasClass("net.minecraft.core.PositionAndRotation");

    /** The 26.3 position types, kept apart so that older games never resolve them. */
    private static final class ModernPositions {
        static Object shift(Shift s, Object v) {
            if (v instanceof PositionStep step) return new PositionStep(s.vec(step.position()), step.tickOffset());
            if (v instanceof PositionAndRotation par) return new Moved(s.vec(par.position()), par.yRot(), par.xRot());
            return null;
        }
    }

    /** A shifted {@link PositionAndRotation} (the original may be the live entity). */
    private record Moved(Vec3 position, float yRot, float xRot) implements PositionAndRotation {
        @Override
        public PositionAndRotation immutable() {
            return this;
        }
    }

    private static final Map<Class<?>, Constructor<?>> CONSTRUCTORS = new ConcurrentHashMap<>();

    private static Record copy(Record r, Shift s, String mode) {
        RecordComponent[] cs = r.getClass().getRecordComponents();
        Object[] values = new Object[cs.length];
        try {
            for (int i = 0; i < cs.length; i++) {
                cs[i].getAccessor().setAccessible(true);
                values[i] = s.value(cs[i].getName(), cs[i].getAccessor().invoke(r), mode);
            }
            Constructor<?> ctor = CONSTRUCTORS.computeIfAbsent(r.getClass(), c -> {
                Class<?>[] types = new Class<?>[cs.length];
                for (int i = 0; i < cs.length; i++) types[i] = cs[i].getType();
                try {
                    Constructor<?> k = c.getDeclaredConstructor(types);
                    k.setAccessible(true);
                    return k;
                } catch (NoSuchMethodException e) {
                    throw new IllegalStateException(e);
                }
            });
            return (Record) ctor.newInstance(values);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot shift " + r.getClass().getName(), e);
        }
    }
}
