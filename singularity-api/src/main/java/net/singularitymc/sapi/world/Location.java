package net.singularitymc.sapi.world;

import java.util.Objects;

/**
 * A point in a world with a view direction, as Minecraft stores entity positions (doubles). Far out a double has less
 * precision after the point (1/128 block at 2^45), so use {@link #block()} / {@link BlockLocation} for block
 * positions: they are exact.
 */
public record Location(World world, double x, double y, double z, float yaw, float pitch) {

    public Location {
        Objects.requireNonNull(world, "world");
    }

    public Location(World world, double x, double y, double z) {
        this(world, x, y, z, 0f, 0f);
    }

    /** The block this point is in. */
    public BlockLocation block() {
        return BlockLocation.containing(x, y, z);
    }

    public long blockX() {
        return (long) Math.floor(x);
    }

    public int blockY() {
        return (int) Math.floor(y);
    }

    public long blockZ() {
        return (long) Math.floor(z);
    }

    public Location add(double dx, double dy, double dz) {
        return new Location(world, x + dx, y + dy, z + dz, yaw, pitch);
    }

    public Location withRotation(float yaw, float pitch) {
        return new Location(world, x, y, z, yaw, pitch);
    }

    public Location withWorld(World world) {
        return new Location(world, x, y, z, yaw, pitch);
    }

    public double distanceSquared(Location other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    @Override
    public String toString() {
        return world.id() + " " + x + ", " + y + ", " + z;
    }
}
