package net.singularitymc.loader.util;

import java.util.Random;

/**
 * The Far Lands as they were: Minecraft Beta 1.7.3's terrain generator ({@code ChunkProviderGenerate}), used for every
 * chunk from 12,550,816 blocks out (see docs/FARLANDS.md). No Minecraft types, so it can be tested and rendered alone.
 *
 * <p>Why the Far Lands exist: the 16-octave "limit" noises sample at {@code cell * 684.412}. Past cell 3,137,706 that is
 * beyond 2^31, the {@code (int)} floor in the Perlin noise saturates at {@link Integer#MAX_VALUE}, and the "fraction"
 * {@code x - floor} becomes a huge number. The smoothstep of it explodes, so the density alternates between enormous
 * positive and negative values: the walls with their long gaps. Modern Minecraft wraps noise coordinates, and Mojang's
 * debug switch turns that wrap off for every noise at once (biomes, caves, aquifers), which only gives water and lava.
 * Here only this terrain noise overflows; biomes, surface, caves, ores and trees stay modern.
 */
public final class ClassicFarLands {
    /** First chunk whose terrain noise overflows: block 12,550,816 (overflow starts at 12,550,821). */
    public static final int ONSET_CHUNK = 784_426;
    /** Beta worlds were 128 blocks high; the Far Lands reach up to here. */
    public static final int TOP = 128;

    public static final int AIR = 0;
    public static final int STONE = 1;
    public static final int WATER = 2;

    // Beta mixed in temperature and humidity; a plains-like climate (0.8 / 0.4) stands in for them.
    private static final double CLIMATE = 1.0 - Math.pow(1.0 - 0.8 * 0.4, 4);

    private static volatile ClassicFarLands cached;

    private final long seed;
    private final Octaves minLimit;
    private final Octaves maxLimit;
    private final Octaves main;
    private final Octaves scale;
    private final Octaves depth;

    /** Receives one block of a generated chunk; x and z are 0..15, y is the world Y (0..127). */
    @FunctionalInterface
    public interface BlockSink {
        void accept(int x, int y, int z, int kind);
    }

    public ClassicFarLands(long seed) {
        this.seed = seed;
        // Same construction order as Beta, so a seed gives Beta's noise.
        Random random = new Random(seed);
        minLimit = new Octaves(random, 16);
        maxLimit = new Octaves(random, 16);
        main = new Octaves(random, 8);
        new Octaves(random, 4); // sand / gravel noise, unused here
        new Octaves(random, 4); // stone-height noise, unused here
        scale = new Octaves(random, 10);
        depth = new Octaves(random, 16);
    }

    /** The generator for this world seed (worlds rarely change seed, so one is kept). */
    public static ClassicFarLands forSeed(long seed) {
        ClassicFarLands c = cached;
        if (c == null || c.seed != seed) {
            c = new ClassicFarLands(seed);
            cached = c;
        }
        return c;
    }

    public static boolean isFarLandsChunk(int chunkX, int chunkZ) {
        return chunkX >= ONSET_CHUNK || chunkX < -ONSET_CHUNK || chunkZ >= ONSET_CHUNK || chunkZ < -ONSET_CHUNK;
    }

    /** Generates one chunk the way Beta did: a 5 x 17 x 5 density field, interpolated over 4 x 8 x 4 blocks. */
    public void fill(long chunkX, long chunkZ, int seaLevel, BlockSink sink) {
        double[] f = densityField(chunkX, chunkZ);
        for (int cx = 0; cx < 4; cx++) {
            for (int cz = 0; cz < 4; cz++) {
                for (int cy = 0; cy < 16; cy++) {
                    double x0z0 = f[index(cx, cz, cy)];
                    double x0z1 = f[index(cx, cz + 1, cy)];
                    double x1z0 = f[index(cx + 1, cz, cy)];
                    double x1z1 = f[index(cx + 1, cz + 1, cy)];
                    double dx0z0 = (f[index(cx, cz, cy + 1)] - x0z0) * 0.125;
                    double dx0z1 = (f[index(cx, cz + 1, cy + 1)] - x0z1) * 0.125;
                    double dx1z0 = (f[index(cx + 1, cz, cy + 1)] - x1z0) * 0.125;
                    double dx1z1 = (f[index(cx + 1, cz + 1, cy + 1)] - x1z1) * 0.125;
                    for (int ly = 0; ly < 8; ly++) {
                        double z0 = x0z0;
                        double z1 = x0z1;
                        double stepZ0 = (x1z0 - x0z0) * 0.25;
                        double stepZ1 = (x1z1 - x0z1) * 0.25;
                        int y = cy * 8 + ly;
                        for (int lx = 0; lx < 4; lx++) {
                            double d = z0;
                            double stepZ = (z1 - z0) * 0.25;
                            for (int lz = 0; lz < 4; lz++) {
                                int kind = d > 0.0 ? STONE : y < seaLevel ? WATER : AIR;
                                sink.accept(cx * 4 + lx, y, cz * 4 + lz, kind);
                                d += stepZ;
                            }
                            z0 += stepZ0;
                            z1 += stepZ1;
                        }
                        x0z0 += dx0z0;
                        x0z1 += dx0z1;
                        x1z0 += dx1z0;
                        x1z1 += dx1z1;
                    }
                }
            }
        }
    }

    private static int index(int x, int z, int y) {
        return (x * 5 + z) * 17 + y;
    }

    /** Beta 1.7.3 {@code ChunkProviderGenerate.func_4061_a}: density at the cell corners, in x, z, y order. */
    double[] densityField(long chunkX, long chunkZ) {
        // 64-bit chunks (the virtual 64-bit world, docs/FARLANDS.md); inside the int range this is Beta exactly.
        double cellX = chunkX * 4.0;
        double cellZ = chunkZ * 4.0;
        double xz = 684.412;
        double vertical = 684.412;
        double[] scaleNoise = scale.noise2d(cellX, cellZ, 5, 5, 1.121, 1.121);
        double[] depthNoise = depth.noise2d(cellX, cellZ, 5, 5, 200.0, 200.0);
        double[] mainNoise = main.noise3d(cellX, 0, cellZ, 5, 17, 5, xz / 80.0, vertical / 160.0, xz / 80.0);
        double[] minNoise = minLimit.noise3d(cellX, 0, cellZ, 5, 17, 5, xz, vertical, xz);
        double[] maxNoise = maxLimit.noise3d(cellX, 0, cellZ, 5, 17, 5, xz, vertical, xz);
        double[] out = new double[5 * 17 * 5];
        int i = 0;
        int column = 0;
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                double heightScale = (scaleNoise[column] + 256.0) / 512.0 * CLIMATE;
                if (heightScale > 1.0) {
                    heightScale = 1.0;
                }
                double heightDepth = depthNoise[column] / 8000.0;
                if (heightDepth < 0.0) {
                    heightDepth = -heightDepth * 0.3;
                }
                heightDepth = heightDepth * 3.0 - 2.0;
                if (heightDepth < 0.0) {
                    heightDepth /= 2.0;
                    if (heightDepth < -1.0) {
                        heightDepth = -1.0;
                    }
                    heightDepth /= 1.4;
                    heightDepth /= 2.0;
                    heightScale = 0.0;
                } else {
                    if (heightDepth > 1.0) {
                        heightDepth = 1.0;
                    }
                    heightDepth /= 8.0;
                }
                if (heightScale < 0.0) {
                    heightScale = 0.0;
                }
                heightScale += 0.5;
                heightDepth = heightDepth * 17.0 / 16.0;
                double centre = 17.0 / 2.0 + heightDepth * 4.0;
                column++;
                for (int y = 0; y < 17; y++) {
                    double falloff = (y - centre) * 12.0 / heightScale;
                    if (falloff < 0.0) {
                        falloff *= 4.0;
                    }
                    double low = minNoise[i] / 512.0;
                    double high = maxNoise[i] / 512.0;
                    double blend = (mainNoise[i] / 10.0 + 1.0) / 2.0;
                    double density = blend < 0.0 ? low : blend > 1.0 ? high : low + (high - low) * blend;
                    density -= falloff;
                    if (y > 17 - 4) {
                        double top = (float) (y - (17 - 4)) / 3.0F;
                        density = density * (1.0 - top) + -10.0 * top;
                    }
                    out[i++] = Double.isNaN(density) ? -1.0 : density;
                }
            }
        }
        return out;
    }

    /** Beta's {@code NoiseGeneratorOctaves}: octave 0 has the highest frequency and the smallest amplitude. */
    static final class Octaves {
        private final Perlin[] octaves;

        Octaves(Random random, int count) {
            octaves = new Perlin[count];
            for (int i = 0; i < count; i++) {
                octaves[i] = new Perlin(random);
            }
        }

        double[] noise3d(double x, double y, double z, int sizeX, int sizeY, int sizeZ, double scaleX, double scaleY, double scaleZ) {
            double[] out = new double[sizeX * sizeY * sizeZ];
            double frequency = 1.0;
            for (Perlin p : octaves) {
                p.add3d(out, x, y, z, sizeX, sizeY, sizeZ, scaleX * frequency, scaleY * frequency, scaleZ * frequency, frequency);
                frequency /= 2.0;
            }
            return out;
        }

        double[] noise2d(double x, double z, int sizeX, int sizeZ, double scaleX, double scaleZ) {
            double[] out = new double[sizeX * sizeZ];
            double frequency = 1.0;
            for (Perlin p : octaves) {
                p.add2d(out, x, z, sizeX, sizeZ, scaleX * frequency, scaleZ * frequency, frequency);
                frequency /= 2.0;
            }
            return out;
        }
    }

    /** Beta's {@code NoiseGeneratorPerlin}, overflow included: {@code (int) x} saturates instead of wrapping. */
    static final class Perlin {
        private final int[] permutations = new int[512];
        private final double xOffset;
        private final double yOffset;
        private final double zOffset;

        Perlin(Random random) {
            xOffset = random.nextDouble() * 256.0;
            yOffset = random.nextDouble() * 256.0;
            zOffset = random.nextDouble() * 256.0;
            for (int i = 0; i < 256; i++) {
                permutations[i] = i;
            }
            for (int i = 0; i < 256; i++) {
                int j = random.nextInt(256 - i) + i;
                int t = permutations[i];
                permutations[i] = permutations[j];
                permutations[j] = t;
                permutations[i + 256] = permutations[i];
            }
        }

        private static int floor(double v) {
            int i = (int) v; // saturates at +-2^31 far out: this is the Far Lands
            return v < i ? i - 1 : i;
        }

        private static double fade(double t) {
            return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
        }

        private static double lerp(double t, double a, double b) {
            return a + t * (b - a);
        }

        private static double grad(int hash, double x, double y, double z) {
            int h = hash & 15;
            double u = h >= 8 ? y : x;
            double v = h >= 4 ? (h != 12 && h != 14 ? z : x) : y;
            return ((h & 1) != 0 ? -u : u) + ((h & 2) != 0 ? -v : v);
        }

        private static double grad2(int hash, double x, double z) {
            int h = hash & 15;
            double u = (1 - ((h & 8) >> 3)) * x;
            double v = h >= 4 ? (h != 12 && h != 14 ? z : x) : 0.0;
            return ((h & 1) != 0 ? -u : u) + ((h & 2) != 0 ? -v : v);
        }

        /** Adds one octave over a sizeX x sizeZ x sizeY grid (output order x, z, y), as Beta's {@code func_805_a}. */
        void add3d(double[] out, double x, double y, double z, int sizeX, int sizeY, int sizeZ,
                   double scaleX, double scaleY, double scaleZ, double frequency) {
            int[] p = permutations;
            double amplitude = 1.0 / frequency;
            int i = 0;
            int cachedY = -1;
            double a = 0.0;
            double b = 0.0;
            double c = 0.0;
            double d = 0.0;
            for (int ix = 0; ix < sizeX; ix++) {
                double fx = (x + ix) * scaleX + xOffset;
                int gx = floor(fx);
                int px = gx & 255;
                fx -= gx;
                double ux = fade(fx);
                for (int iz = 0; iz < sizeZ; iz++) {
                    double fz = (z + iz) * scaleZ + zOffset;
                    int gz = floor(fz);
                    int pz = gz & 255;
                    fz -= gz;
                    double uz = fade(fz);
                    for (int iy = 0; iy < sizeY; iy++) {
                        double fy = (y + iy) * scaleY + yOffset;
                        int gy = floor(fy);
                        int py = gy & 255;
                        fy -= gy;
                        double uy = fade(fy);
                        // Beta only recomputes the corner gradients when the y cell changes (with that y's fraction).
                        if (iy == 0 || py != cachedY) {
                            cachedY = py;
                            int ay = p[px] + py;
                            int aay = p[ay] + pz;
                            int aby = p[ay + 1] + pz;
                            int by = p[px + 1] + py;
                            int bay = p[by] + pz;
                            int bby = p[by + 1] + pz;
                            a = lerp(ux, grad(p[aay], fx, fy, fz), grad(p[bay], fx - 1.0, fy, fz));
                            b = lerp(ux, grad(p[aby], fx, fy - 1.0, fz), grad(p[bby], fx - 1.0, fy - 1.0, fz));
                            c = lerp(ux, grad(p[aay + 1], fx, fy, fz - 1.0), grad(p[bay + 1], fx - 1.0, fy, fz - 1.0));
                            d = lerp(ux, grad(p[aby + 1], fx, fy - 1.0, fz - 1.0), grad(p[bby + 1], fx - 1.0, fy - 1.0, fz - 1.0));
                        }
                        double lower = lerp(uy, a, b);
                        double upper = lerp(uy, c, d);
                        out[i++] += lerp(uz, lower, upper) * amplitude;
                    }
                }
            }
        }

        /** The 2D variant (Beta calls it with y = 10, height 1): output order x, z. */
        void add2d(double[] out, double x, double z, int sizeX, int sizeZ, double scaleX, double scaleZ, double frequency) {
            int[] p = permutations;
            double amplitude = 1.0 / frequency;
            int i = 0;
            for (int ix = 0; ix < sizeX; ix++) {
                double fx = (x + ix) * scaleX + xOffset;
                int gx = floor(fx);
                int px = gx & 255;
                fx -= gx;
                double ux = fade(fx);
                for (int iz = 0; iz < sizeZ; iz++) {
                    double fz = (z + iz) * scaleZ + zOffset;
                    int gz = floor(fz);
                    int pz = gz & 255;
                    fz -= gz;
                    double uz = fade(fz);
                    int a = p[px];
                    int aa = p[a] + pz;
                    int b = p[px + 1];
                    int ba = p[b] + pz;
                    double lower = lerp(ux, grad2(p[aa], fx, fz), grad(p[ba], fx - 1.0, 0.0, fz));
                    double upper = lerp(ux, grad(p[aa + 1], fx, 0.0, fz - 1.0), grad(p[ba + 1], fx - 1.0, 0.0, fz - 1.0));
                    out[i++] += lerp(uz, lower, upper) * amplitude;
                }
            }
        }
    }
}
