package net.singularitymc.loader.asm;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.singularitymc.loader.classloading.ClassTransformer;
import net.singularitymc.loader.util.Log;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

/**
 * {@code experimental_long_positions} (docs/ROADMAP_64BIT.md): Minecraft's coordinate classes get long X/Z at load
 * time, one roadmap step after another (3.5.2 positions, 3.5.3 math and geometry, 3.5.4 world access). The first time one of them is
 * loaded, all of them are taken (after Mixin, so the wide packing patches are in), widened together by
 * {@link CoordinateWidener} in scoped mode, and served from a cache. Every method whose signature changed keeps its
 * int signature as well, so the rest of the game runs unchanged; widened code (later updates) uses the long ones.
 * Where a coordinate crosses into code that is not widened yet it is carried ({@code WidePacking.carryOut} /
 * {@code carryIn}): int code sees its low 32 bits, and what it hands back is moved next to the true position. If
 * the widener reports any problem, the classes are served unwidened and the problem is logged.
 */
public final class PositionWidening implements ClassTransformer {
    private static final String CORE = "net/minecraft/core/";

    /** 3.5.2: the position types. */
    public static final List<String> POSITIONS = List.of(
        CORE + "Vec3i",
        CORE + "BlockPos",
        CORE + "BlockPos$MutableBlockPos",
        CORE + "SectionPos",
        "net/minecraft/world/level/ChunkPos");
    /** 3.5.3: math and geometry (block <-> double conversions, boxes, iteration, quarter positions). */
    public static final List<String> GEOMETRY = List.of(
        "net/minecraft/util/Mth",
        CORE + "QuartPos",
        CORE + "Cursor3D",
        CORE + "BlockBox",
        CORE + "Direction",
        "net/minecraft/world/level/levelgen/structure/BoundingBox",
        "net/minecraft/world/phys/AABB",
        "net/minecraft/world/phys/Vec3");
    private static final String LEVEL = "net/minecraft/world/level/";
    /**
     * 3.5.4: world access. The level interfaces with every implementor in 26.3 (block and chunk lookups by
     * coordinate), the chunk source, entity sections, and the chunk IO (which skips chunks past the int range).
     */
    public static final List<String> WORLD = List.of(
        LEVEL + "LevelReader",
        LEVEL + "LevelAccessor",
        LEVEL + "CommonLevelAccessor",
        LEVEL + "ServerLevelAccessor",
        LEVEL + "WorldGenLevel",
        LEVEL + "Level",
        "net/minecraft/server/level/ServerLevel",
        "net/minecraft/server/level/WorldGenRegion",
        LEVEL + "chunk/ChunkSource",
        "net/minecraft/server/level/ServerChunkCache",
        LEVEL + "entity/EntitySectionStorage",
        LEVEL + "entity/PersistentEntitySectionManager",
        LEVEL + "chunk/storage/IOWorker",
        // 3.5.6: the hit location of a block a client uses is rebuilt from its block position
        "net/minecraft/world/phys/BlockHitResult",
        // 3.5.7: /forceload compares the corners of its area, which int code gets wrong across 2^31
        "net/minecraft/server/level/ColumnPos",
        "net/minecraft/commands/arguments/coordinates/ColumnPosArgument",
        "net/minecraft/server/commands/ForceLoadCommand");
    /** Everything widened by class name; closed under subclassing (nothing else in 26.3 extends these). */
    public static final List<String> CLASSES = java.util.stream.Stream.of(POSITIONS, GEOMETRY, WORLD).flatMap(List::stream).toList();
    /**
     * 3.5.8: terrain generation at true coordinates. Whole packages (noise, aquifers, density functions, surface
     * rules, biomes, structures, features, carvers, chunks and their generation steps), so no int code is left between
     * a chunk and the coordinates it is generated from. The save format (chunk storage) stays out.
     */
    public static final List<String> GENERATION_PACKAGES = List.of(LEVEL + "levelgen/", LEVEL + "biome/", LEVEL + "chunk/", LEVEL + "lighting/");
    public static final List<String> GENERATION_EXCLUDED = List.of(LEVEL + "chunk/storage/");
    /** Classes outside those packages that generation hands coordinates to, or that implement its interfaces. */
    public static final List<String> GENERATION = List.of("net/minecraft/util/StaticCache2D", LEVEL + "NoiseColumn", LEVEL + "StructureManager",
        LEVEL + "EmptyStructureManager",
        "net/minecraft/server/level/ChunkGenerationTask", "net/minecraft/server/level/GenerationChunkHolder",
        "net/minecraft/server/level/ChunkHolder", "net/minecraft/server/level/ChunkTaskDispatcher",
        "net/minecraft/server/level/ThrottlingChunkTaskDispatcher",
        // lighting's level graphs and their subclasses (chunk tickets, POI distances), the threaded light engine
        "net/minecraft/server/level/ChunkTracker", "net/minecraft/server/level/SectionTracker",
        "net/minecraft/server/level/ThreadedLevelLightEngine", "net/minecraft/world/entity/ai/village/poi/PoiManager$DistanceTracker",
        "net/minecraft/server/level/DistanceManager", "net/minecraft/server/level/ChunkMap", "net/minecraft/server/level/LoadingChunkTracker",
        "net/minecraft/server/level/SimulationChunkTracker");

    private static final String ENTITY = "net/minecraft/world/entity/";
    /**
     * 3.5.8, part 2: entities. All of {@code world/entity}: the entity base classes ({@code Entity},
     * {@code LivingEntity}, {@code Mob}), the AI every mob uses (goals, navigation, movement and look controls, random
     * positions, sensing, brains), every mob, villager, boss and raid, items, projectiles, vehicles, decorations and
     * players; and the pathfinder, so path nodes and walk targets are true coordinates.
     */
    public static final List<String> ENTITY_PACKAGES = List.of(ENTITY, LEVEL + "pathfinder/");
    /**
     * Classes outside those packages that entities hand coordinates to: block collisions (int code placed each block's
     * shape at its low 32 bits, so past 2^31 minecarts, boats and falling blocks fell through the floor), ray tracing,
     * and the shape of a block moved to its position.
     */
    public static final List<String> ENTITIES = List.of(LEVEL + "PathNavigationRegion",
        LEVEL + "BlockCollisions", LEVEL + "CollisionGetter", LEVEL + "BlockGetter", LEVEL + "EntityGetter", LEVEL + "ClipContext",
        // VoxelShape.move(Vec3i) moved a block's shape by its int getX()
        "net/minecraft/world/phys/shapes/VoxelShape");
    /** Methods that never carry coordinates (see {@code CoordinateWidener.notCoordinates}). */
    public static final Set<String> NOT_COORDINATES = Set.of(
        ENTITY + "ai/goal/Goal.adjustedTickDelay(I)I", ENTITY + "ai/goal/Goal.reducedTickDelay(I)I",
        // counters and levels that int math on a coordinate (Mth.floor of a distance, a tick from a position) reaches
        ENTITY + "Entity.remainingFireTicks", ENTITY + "player/Player.totalExperience", ENTITY + "animal/equine/AbstractHorse.temper",
        ENTITY + "ai/goal/RangedAttackGoal.attackTime", ENTITY + "raid/Raid.raidOmenLevel",
        ENTITY + "monster/warden/WardenSpawnTracker.warningLevel", ENTITY + "ai/behavior/LongJumpToRandomPos$PossibleJump.weight",
        ENTITY + "ai/behavior/warden/SonicBoom.TICKS_BEFORE_PLAYING_SOUND",
        ENTITY + "animal/armadillo/ArmadilloAi$ArmadilloBallUp.nextPeekTimer", ENTITY + "animal/goat/Goat.lowerHeadTick",
        ENTITY + "animal/golem/CopperGolem.idleAnimationStartTick", ENTITY + "monster/breeze/Breeze.soundTick",
        ENTITY + "monster/breeze/Breeze.emitGroundParticles(I)V",
        ENTITY + "monster/creaking/Creaking.nextFlickerTime", LEVEL + "pathfinder/PathFinder.maxVisitedNodes");

    /**
     * Every class widened: {@link #CLASSES}, {@link #GENERATION}, {@link #ENTITIES} and the generation and entity
     * packages, with their nest members (inner classes may read the private X/Z fields directly). Only classes the
     * running game has: the lists are written against 26.3 and also serve 26.1 and 26.2.
     *
     * @param original     class bytes by internal name
     * @param classesUnder the game's classes under a package prefix
     */
    public static Set<String> scope(Function<String, byte[]> original, Function<String, java.util.Collection<String>> classesUnder) {
        Set<String> base = new LinkedHashSet<>(CLASSES);
        base.addAll(GENERATION);
        base.addAll(ENTITIES);
        for (String p : java.util.stream.Stream.concat(GENERATION_PACKAGES.stream(), ENTITY_PACKAGES.stream()).toList()) {
            for (String c : classesUnder.apply(p)) {
                if (GENERATION_EXCLUDED.stream().noneMatch(c::startsWith)) base.add(c);
            }
        }
        Set<String> s = new LinkedHashSet<>(base);
        for (String c : base) {
            byte[] b = original.apply(c);
            if (b == null) continue;
            ClassNode cn = new ClassNode();
            new ClassReader(b).accept(cn, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            if (cn.nestMembers != null) s.addAll(cn.nestMembers);
        }
        // A listed class this Minecraft version does not have is left out (EmptyStructureManager exists from 26.3 on;
        // 26.1 and 26.2 lack it). Widening it would hand the widener no bytes and stop the server.
        s.removeIf(c -> original.apply(c) == null);
        return s;
    }

    private static final String BOX = "net/minecraft/world/level/levelgen/structure/BoundingBox";
    public static final List<String> SEEDS = List.of(
        CORE + "Vec3i.x", CORE + "Vec3i.z",
        "net/minecraft/world/level/ChunkPos.x", "net/minecraft/world/level/ChunkPos.z",
        BOX + ".minX", BOX + ".maxX", BOX + ".minZ", BOX + ".maxZ");
    private static final String PACKING = "net/singularitymc/loader/util/WidePacking";
    private static final String MTH = "net/minecraft/util/Mth";
    private static final String SETTINGS = "net/singularitymc/loader/util/PatchSettings";
    private static final String RANDOM = "net/minecraft/util/RandomSource";
    private static final String WIDE_RANDOM = "net/singularitymc/world/WideRandom";
    private static final String ENTITY_CLASS = "net/minecraft/world/entity/Entity";
    private static final String WIDE_ENTITY = "net/singularitymc/world/WideEntity";
    /**
     * Exact long replacements, always used: the wide packing's 64-bit API (what the overwritten BlockPos packing
     * switches to), and the double -> block conversions, so {@code BlockPos.containing}, AABB and Vec3 give 64-bit
     * blocks.
     */
    public static final Map<String, String[]> TWINS = Map.ofEntries(
        twin(PACKING, "blockAsLong(III)J", "blockAsLong(JIJ)J"),
        twin(PACKING, "blockX(J)I", "blockX64(J)J"),
        twin(PACKING, "blockZ(J)I", "blockZ64(J)J"),
        twin(MTH, "floor(D)I", PACKING, "floorCarry(D)J"),
        twin(MTH, "ceil(D)I", PACKING, "ceilCarry(D)J"),
        // 3.5.4: 64-bit section and chunk keys, the 64-bit world limit
        twin(PACKING, "sectionAsLong(III)J", "sectionAsLong(JIJ)J"),
        twin(PACKING, "sectionX(J)I", "sectionX64(J)J"),
        twin(PACKING, "sectionY(J)I", "sectionY64(J)I"),
        twin(PACKING, "sectionZ(J)I", "sectionZ64(J)J"),
        twin(PACKING, "sectionZeroNode(J)J", "sectionZeroNode64(J)J"),
        twin(PACKING, "clampSectionY(I)I", "clampSectionY64(I)I"),
        twin(PACKING, "sectionRanges(I)Z", "sectionRanges64(J)Z"),
        twin(PACKING, "chunkPack(II)J", "chunkPack(JJ)J"),
        twin(PACKING, "chunkX(J)I", "chunkX64(J)J"),
        twin(PACKING, "chunkZ(J)I", "chunkZ64(J)J"),
        twin(PACKING, "chunkSavable(II)Z", "chunkSavable64(JJ)Z"),
        twin(SETTINGS, "limit()I", "limit64()J"),
        twin(SETTINGS, "border()I", "border64()J"),
        // 3.5.8: random positions between two coordinates (static helpers taking the RandomSource first)
        twin(RANDOM, "nextIntBetweenInclusive(II)I", WIDE_RANDOM, "nextBetweenInclusive(L" + RANDOM + ";JJ)J"),
        twin(RANDOM, "nextInt(II)I", WIDE_RANDOM, "nextBetween(L" + RANDOM + ";JJ)J"),
        // 3.5.8: an entity's block coordinates (entities are not widened; widened mob code uses getBlockX() + 0.5)
        twin(ENTITY_CLASS, "getBlockX()I", WIDE_ENTITY, "blockX(L" + ENTITY_CLASS + ";)J"),
        twin(ENTITY_CLASS, "getBlockZ()I", WIDE_ENTITY, "blockZ(L" + ENTITY_CLASS + ";)J"));
    private static final String CARRY = PACKING;
    private static final String CHUNK_POS = "net/minecraft/world/level/ChunkPos";
    private static final String SECTION_POS = CORE + "SectionPos";
    /**
     * 3.5.7: int signatures whose X/Z are chunk or section coordinates. Past 2^35 blocks those no longer fit an int
     * either, and they are carried at chunk scale (a chunk coordinate whose low bits are close to a block
     * coordinate's would be taken for it).
     */
    public static final Set<String> CHUNK_SCALED = Set.of(
        CHUNK_POS + ".<init>(II)V", CHUNK_POS + ".pack(II)J", CHUNK_POS + ".getX(J)I", CHUNK_POS + ".getZ(J)I",
        CHUNK_POS + ".x()I", CHUNK_POS + ".z()I", CHUNK_POS + ".hash(II)I", CHUNK_POS + ".isValid(II)Z",
        CHUNK_POS + ".getChessboardDistance(II)I",
        SECTION_POS + ".of(III)L" + SECTION_POS + ";", SECTION_POS + ".asLong(III)J", SECTION_POS + ".getZeroNode(II)J",
        SECTION_POS + ".x(J)I", SECTION_POS + ".z(J)I", SECTION_POS + ".x()I", SECTION_POS + ".z()I",
        SECTION_POS + ".betweenClosedStream(IIIIII)Ljava/util/stream/Stream;",
        // the level and chunk source: chunks by chunk coordinates
        "*.getChunk", "*.getChunkNow", "*.hasChunk", "*.getChunkForLighting", "*.getChunkFuture", "*.getChunkFutureMainThread",
        // 3.5.8: the square of chunks around one being generated
        "net/minecraft/util/StaticCache2D.*", "net/minecraft/util/StaticCache2D$Initializer.*", "net/minecraft/util/StaticCache2D$MappingFunction.*",
        "*.getChunkForCollisions", "*.isChunkTracked",
        // section in, block (or quart) out
        "args:" + SECTION_POS + ".sectionToBlockCoord(I)I", "args:" + SECTION_POS + ".sectionToBlockCoord(II)I",
        "args:" + CORE + "QuartPos.fromSection(I)I",
        // 3.5.8: conversions return another scale than they take, and int code sees each scale narrowed its own way
        // (WidePacking.narrowScaled); with the low 32 bits of before that did not matter
        "ret:Chunk:" + SECTION_POS + ".blockToSectionCoord(I)I", "ret:Chunk:" + SECTION_POS + ".blockToSectionCoord(D)I",
        "ret:Chunk:" + SECTION_POS + ".posToSectionCoord(D)I",
        "ret:Quart:" + CORE + "QuartPos.fromBlock(I)I", "ret:Quart:" + CORE + "QuartPos.fromSection(I)I",
        "ret:Block:" + CORE + "QuartPos.toBlock(I)I", "ret:Chunk:" + CORE + "QuartPos.toSection(I)I");
    /**
     * 3.5.8: int signatures whose X/Z are quart (biome, block >> 2) coordinates. Every other int signature takes block
     * coordinates and carries only at block scale (see {@code WidePacking.carryInBlock}).
     */
    public static final Set<String> QUART_SCALED = Set.of(
        "*.getNoiseBiome", "*.getNoiseBiomeAtQuart", "*.getUncachedNoiseBiome",
        LEVEL + "biome/Climate$Sampler.sample(III)L" + LEVEL + "biome/Climate$TargetPoint;",
        CORE + "QuartPos.toBlock(I)I", CORE + "QuartPos.toSection(I)I");

    private static Map.Entry<String, String[]> twin(String owner, String from, String to) {
        return twin(owner, from, owner, to);
    }

    private static Map.Entry<String, String[]> twin(String owner, String from, String toOwner, String to) {
        int p = to.indexOf('(');
        return Map.entry(owner + "." + from, new String[] {toOwner, to.substring(0, p), to.substring(p)});
    }

    private static final Log LOG = Log.get("Singularity");

    private final ClassTransformer next;
    private final Function<String, byte[]> original;
    private final CoordinateWidener.OutsideClasses outside;
    /** See {@link #scope}. */
    private final Set<String> scope;
    private volatile Map<String, byte[]> widened;

    /**
     * @param next     the transformer before this one (Mixin)
     * @param original untransformed class bytes by internal name, from the game's class sources
     * @param outside  any class's bytes (game, libraries, JDK) for the class hierarchy
     * @param classesUnder the game's classes under a package prefix
     */
    public PositionWidening(ClassTransformer next, Function<String, byte[]> original, CoordinateWidener.OutsideClasses outside,
                            Function<String, java.util.Collection<String>> classesUnder) {
        this.next = next;
        this.original = original;
        this.outside = outside;
        this.scope = Set.copyOf(scope(original, classesUnder));
    }

    @Override
    public byte[] transform(String name, byte[] bytes) {
        String internal = name.replace('.', '/');
        if (!scope.contains(internal)) {
            return next.transform(name, bytes);
        }
        return widened().get(internal);
    }

    private synchronized Map<String, byte[]> widened() {
        if (widened != null) {
            return widened;
        }
        Map<String, byte[]> in = new LinkedHashMap<>();
        for (String c : scope) {
            in.put(c, next.transform(c.replace('/', '.'), original.apply(c)));
        }
        long t0 = System.nanoTime();
        CoordinateWidener.Result r = new CoordinateWidener(in, outside, TWINS, true, CARRY, CHUNK_SCALED, QUART_SCALED)
            .notCoordinates(NOT_COORDINATES).widen(SEEDS);
        CoordinateWidener.Report rep = r.report();
        if (rep.problems().isEmpty() && r.classes().keySet().containsAll(scope)) {
            LOG.info("Long positions (experimental): {} classes widened in {} ms ({} long fields, {} instructions rewritten, {} int bridges).",
                scope.size(), (System.nanoTime() - t0) / 1_000_000, rep.longFields().size(), rep.rewrittenInstructions(), rep.bridges());
            widened = r.classes();
        } else {
            LOG.error("Long positions (experimental) are off: the widener reported {} problem(s); the position classes stay as they were.",
                rep.problems().size());
            rep.problems().stream().limit(10).forEach(p -> LOG.error("  {}", p));
            widened = in;
        }
        return widened;
    }
}
