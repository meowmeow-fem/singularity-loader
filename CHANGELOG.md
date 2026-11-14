# Changelog

All notable changes to Singularity Loader are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [4.4.0] - Unreleased

### Added
- **Singularity API 4.2.0**: `PlayerMoveEvent` (one per movement packet, after the server applied it; cancelling puts
  the player back) and `PlayerAttackEvent` (before a hit is worked out, with the distance to the target's hit box and
  the player's reach; cancelling drops the hit). `Player` gained the movement state anti-cheat checks need:
  `isFlying`, `canFly`, `isGliding`, `isRiptiding`, `isInLiquid`, `isClimbing`, `isInVehicle`, `isSupported`
  (something solid under the player, worked out by the server), `movementSpeed`, `effectLevel(id)`, `ping`.
- **Singularity API 4.3.0**: `net.singularitymc.sapi.gui.Menu` and `Icon`: a chest-style window of clickable icons,
  drawn by the game's own chest screen (works with every client). Nothing can be taken out; a click runs the icon's
  handler; changing an icon while the window is open shows at once.
- **Server Manager** plugin (`server-manager-plugin`, not in the distribution): `/tps`, `/online`, `/broadcast`,
  `/staffchat`, `/maintenance`, `/mute`, `/unmute`, `/kickall`, `/shutdown`, `/tpto`, `/tphere`, `/gm`, timed
  announcements, lag alert for staff; `/dashboard` (a window with tick rate, memory, players, maintenance switch,
  shutdown, and the players to teleport to or mute); a welcome banner for joining players (`welcome` in its config);
  `/singularity`, which draws a black hole in chat next to the server's versions and numbers.
- **Singularity AntiCheat** plugin (`anticheat-plugin`, not in the distribution, needs API 4.3): speed, fly, timer,
  reach and click-rate checks with violation levels, setbacks, staff alerts and an optional kick; `/anticheat`, and
  `/anticheat gui` (a window to switch checks on and off and to see, visit and clear the players' violation levels).
  Tested with fake players that send real movement packets (`/stress cheat`), not yet with a real client.
- **Singularity Keys** (`keys-client-mod`, a Fabric client mod like the wide client): Right Shift sends `/dashboard`,
  F12 sends `/anticheat gui`. Compiled and checked against the 26.3 client only; not run in a client yet.
- Stress mod: `/stress cheat <bot> <walk|sprint|jump|speed|fly|glide|timer|reach|clicks> [ticks]` and
  `/stress menu <bot> [slot] [left|right|shift]` (what window a bot has open; click into it).
- `redstone_wire_algorithm = "fast"` (opt-in, default `"vanilla"`; `net.singularitymc.perf.FastWire`): when a redstone
  dust has to change, every dust within 15 steps gets its final power in one pass (strongest first, from the real
  sources and from the unchanged dust beyond), then the other blocks around the changed dust are notified once.
  Vanilla's connection rules are used and the resulting powers are vanilla's; what changes is the order in which other
  components are updated, and that they see no intermediate powers. 400 dust lines on a clock (26.3, mean tick):
  74 ms in 4.2, 61 ms in 4.3, **25 ms** with `"fast"`. Falls back to vanilla's dust for regions of more than 4,096
  dust and on a game version without the method it needs.
- `stress-mod`: `/stress dust <x> <y> <z> <count>` prints the powers of a row of dust.

## [4.3.0] - Unreleased

Redstone, hoppers, mobs' searches and pathfinding ([docs/PERFORMANCE.md](docs/PERFORMANCE.md), section 6). Measured
on 400 test rigs each (26.3, mean tick, 4.2 against 4.3): redstone dust lines on a clock 74 ms to 61 ms, 9,600
hoppers 7.1 ms to 6.2 ms, 3,600 mobs (zombies chasing villagers) 80 ms to 73 ms.

### Added
- `redstone_dedup_wire_updates` (default true): a redstone dust that changes power notifies each of the 25 blocks
  around it once instead of sending vanilla's 42 notifications. Vanilla's order of first notifications and its dust
  algorithm are kept.
- "Is this chunk in ticking range" is remembered per chunk for the tick instead of being looked up for every entity
  and every block entity (21 % of the tick in the hopper rig).
- `poi_scan_cache` (default true): searches for points of interest (beds, workstations, hives, ...) skip chunk
  columns found empty in the last 10 seconds; adding any point of interest clears the memory.
- `pathfinding_budget_ms` (default 8, halved from load level 3): path requests after a tick has spent the budget get
  "no path" and are retried by the mob later.
- `stress-mod`: `/stress machines <count> [r|h|m]` builds redstone, hopper and mob test rigs.

### Notes
- Lighting: nothing changed. It runs off the server thread in batches and did not show up in any profile.
- Not changed: the dust algorithm's step-by-step power-down of long lines, which is most of what redstone still
  costs. Replacing it changes update order between components, so it is left for a later, opt-in change.
- Checked: dust line, ring and torch cases give vanilla's power values with the new notifications; all hooks are
  optional and were checked offline against 26.1, 26.2 and 26.3.

## [4.2.0] - Unreleased

Many players on weak hardware ([docs/PERFORMANCE.md](docs/PERFORMANCE.md)). Measured with 50 fake players on 2 CPU
threads and 3 GB of heap (26.3, view and simulation distance 10): 4.0 TPS (mean tick 241 ms) with every optimisation
off, 8.9 TPS (112 ms) with the fixed ones, **19.3 TPS (48 ms)** with the default adaptive mode, which ended at view
distance 4 and simulation distance 3. 20 players hold 20.0 TPS at view 7 / simulation 5.

### Added
- **Adaptive performance** (`[performance]` in ServerConfig.toml, `adaptive_performance = true`): the server watches
  its tick times (`target_tick_ms = 45`) and its heap, and under load lowers step by step the simulation distance,
  then the view distance (never below `min_simulation_distance = 3` / `min_view_distance = 4`), slows mob spawning and
  far-away mobs, and under heavy load thins random ticks. It goes back up slowly when the load is gone. Every change
  is logged as `[Perf] load level ...`. `PerfGovernor` holds the logic (unit-tested).
- **Entity activation range** (`entity_activation_range = 48`): mobs with no player within the range tick 1 tick in 4
  (less under load) while nothing happens to them; burning, hurt, fighting, riding, ridden, falling and (land mobs)
  swimming ones and bosses always tick. Their despawn check runs every 4th tick. 0 = vanilla. Farms whose mobs must
  walk by themselves while the player is farther away than the range get slower.
- **Entity tracking** (`entity_tracking_interval = 8`): vanilla re-checks every entity of the world against a player
  on every move packet, starting each check with a stream over the entity's passengers (a third of the tick with 50
  players). "Out of range" is answered without that, and the pass over all entities runs once every 8 ticks per
  player; chunk-section crossings are still handled in the same tick.
- **Chunk work when overloaded** (`overload_chunk_budget_ms = 3`): vanilla unloads chunks and runs the chunk system's
  queued tasks only in spare time, so a server that is behind never lets go of chunks. Each tick always gets 3 ms for
  each. The server thread runs at a slightly higher priority than the chunk workers.
- `mob_spawn_interval` (default 1); ticks without spawning also skip the entity count and the chunk collection.
- Start scripts: `GC_OPTS` with G1 tuned for Minecraft (checked on JDK 25), separate from `JAVA_OPTS` (memory).
- `stress-mod/` (test only, not in the distribution): `/stress spawn|mark|report|view|chunks|clear`, fake players
  (real `ServerPlayer`s on a connection that drops every packet) and tick-time statistics.

### Notes
- `-Dsingularity.opt.disable=true` turns all of this off, as before.
- All new patches are optional hooks and were checked offline against 26.1, 26.1.2, 26.2 and 26.3
  (`MixinTargetCheck`, `LinkCheck`); the spawning hooks have one variant per signature (26.1, 26.2, 26.3).
- Not measured: packet encoding, compression and the network (fake players have none); not touched: redstone,
  hoppers, lighting, collisions, pathfinding, chunk generation. Nobody has played on a 4.2 server yet.
- `MixinTargetCheck` understands `name*` (every overload).

## [4.1.0] - Unreleased

Every unobfuscated Minecraft release: **26.1, 26.1.1, 26.1.2, 26.2 and 26.3**. One loader jar, one Singularity API mod
jar and the same plugins run on all of them ([docs/VERSIONS.md](docs/VERSIONS.md)).

### Added
- Minecraft 26.1 and 26.1.1 (the oldest supported version was 26.1.2; 26.1 is the first that Mojang ships with its own
  names, nothing older is supported). `GameCompat.OLDEST` is 26.1.
- `net.singularitymc.compat`: `GameShims` (calls whose bytecode differs between the versions, bound once at startup)
  and `WorldSeeds` (the world seed of a `RandomState`: `seed()` from 26.3 on, kept from the constructor before, by
  `RandomStateSeedMixin`).
- `tools/asm-audit/LinkCheck.java`: checks that compiled code still finds every Minecraft class, method and field it
  references in a given game jar. `MixinTargetCheck` now also checks that an `@Inject` handler's parameters match its
  target. With `ScopedDryRun` these check a jar against each version offline (docs/VERSIONS.md).
- The cause of a crash report is logged at once in every mode (it was only in 64-bit mode): `CrashReportLongMixin`
  became `CrashReportCauseMixin` in the main Mixin config, unchanged (12 stack frames).

### Changed
- Singularity API mod 4.1.0: depends on `minecraft >=26.1` (was `>=26.3`). Nothing else changed; plugins written for
  4.0 (`api = ">=4.0.0"`) load unchanged.

### Fixed (found checking 26.1 to 26.2; 26.3 behaves as before)
- Bigger world (`max_coordinate` past 33,554,432): the server did not start on 26.1 to 26.1.2. The chunk limit lives in
  `ChunkPos` there and in `ChunkPyramid` from 26.2 on; `ChunkPosWideMixin` covers the older place, both hooks optional.
- Bigger world on 26.1 to 26.2: looking up entities by area (`kill @e[distance=..3]`, collisions, AI) would have thrown
  `NoSuchMethodError`: `AbortableIterationConsumer.accept` returns another type before 26.3. It goes through
  `GameShims.acceptAborts`. The Singularity Wide Client (one jar for 26.1 to 26.3) had the same fault in singleplayer.
- Classic Far Lands on 26.1 to 26.2: never applied, so far chunks had vanilla terrain (the noise step is
  `fillFromNoise` there, not `buildTerrain`). A second hook covers it; the seed comes from `WorldSeeds`, since
  `RandomState.seed()` does not exist there either.
- 64-bit mode on 26.1 to 26.2: did not start. The widener's scope listed `EmptyStructureManager` (26.3+); classes the
  game does not have are now left out (`PositionWidening.scope`). `NaturalSpawnerLongMixin`'s guard takes a
  `Holder<Biome>` there instead of a `BlockPos`: one handler per signature, grouped (exactly one applies).
- The floating origin (64-bit mode) only shifts `PositionStep` / `PositionAndRotation` where they exist (26.3+).

### Tested
- 26.1, 26.1.1, 26.1.2 and 26.2, each in its own server folder with a fresh world (seed 1234), console commands:
  - normal mode: the API mod and the example plugin load, enable and disable; `/plugins`, `/plugins exampleplugin`,
    `/hello`, `/hello Steve`, `/hi`, `/hello a b` (usage), `/exampleplugin:hello Alex`, `/whereami` (console refused);
  - bigger world (`max_coordinate = 100000000`, classic Far Lands): `/forceload`, `/setblock` and `execute if block`
    at x = 50,000,000, an armor stand summoned there and found by `kill @e[distance=..3]`, the Far Lands' stone;
  - 64-bit mode (`experimental_long_positions`, `max_coordinate = 2^52`): 2,066 classes (26.1 to 26.1.2) and 2,096
    (26.2) widened at startup; `/setblock` and `execute if block` at 10,000,000,000 and 35,000,000,000,000, an armor
    stand found at 10,000,000,003.
- Offline: `LinkCheck` and `MixinTargetCheck` against 26.1, 26.1.2, 26.2 and 26.3 (only the other versions' optional
  hooks missing); `ScopedDryRun`: every widened method passes the verifier on 26.1 and 26.2.
- Not tested: a real client on 26.1 to 26.2 (joining, chat and block events, the floating origin, the Wide Client in
  game); Mojang's Far Lands switch does not exist before 26.3 and is skipped with a warning, as before.

## [4.0.0] - Unreleased

Plugins and the Singularity API.

### Added
- **Singularity API mod** (`singularity-api/`, mod id `singularity-api`, version 4.0.0, package `net.singularitymc.sapi`):
  one API for mods and plugins without Minecraft types. `Singularity` (server, `forMod(id)`, plugins, permissions),
  players and entities, worlds (get and set blocks by id), commands (real Brigadier commands with permissions, aliases,
  `/<owner>:<name>` and tab completion), events, a tick scheduler with async tasks, TOML configs, permission defaults and
  a provider hook. Coordinates are 64-bit (`BlockLocation(long x, int y, long z)`, `ChunkLocation`); the conversion to
  Minecraft goes through the widened packing, so positions past 2^31 are exact (live-tested: a block set at
  X = 35,000,000,000,007 reads back there, the low-32-bit position stays untouched). The mod jar goes into
  `SingularityMods/`; `singularity-api-4.0.0-api.jar` is the public API plugins compile against.
- **Plugins**: jars with a `plugin.toml` in `SingularityPlugins/` (the folder was created since 3.0 but never read),
  loaded by the API mod. Load / enable / disable lifecycle, `depends` / `softdepends` / `loadbefore` with a
  deterministic order, an API version check, commands and permissions declared in the descriptor, one class loader per
  plugin. Everything a plugin registered (listeners, tasks, commands) is removed when it is disabled, and a plugin that
  fails never stops the server; all problems in a descriptor are reported at once. `/plugins` (`/pl`) lists them.
  `-Dsingularity.addPlugins=<jar or folder>` loads extra plugins for development.
- API events, posted after the loader's events (cancelling either cancels the action): `PlayerJoinEvent`,
  `PlayerQuitEvent`, `PlayerChatEvent`, `BlockBreakEvent`, `BlockPlaceEvent`, `EntityDamageEvent`,
  `ServerStartedEvent`, `ServerTickEvent`, `ServerStoppingEvent`.
- `example-plugin/` (a command with an alias, a command registered in code, a join listener, a timer, a config),
  [docs/PLUGIN_DEVELOPMENT.md](docs/PLUGIN_DEVELOPMENT.md) and [docs/SINGULARITY_API.md](docs/SINGULARITY_API.md).

### Fixed
- `api/plugin/package-info.java` began with a stray character, which stopped the loader from compiling.

### Not tested yet
- The player events (chat, join, block break / place, damage) and tab completion with a real client; the console
  commands, the plugin lifecycle and 64-bit block access were tested on a server.

## [3.5.8] - Unreleased (in progress)

Eighth of eight updates towards a true 64-bit X/Z world (docs/ROADMAP_64BIT.md), part 1: terrain generation at true coordinates.

- Terrain generation is widened: the whole of `levelgen` (noise, aquifers, density functions, surface rules, structures, features, carvers), `biome`, `chunk` (without its save format) and `lighting`, plus the chunk loading around them (`ChunkMap`, `DistanceManager` and its trackers, `ChunkHolder`, the generation tasks, `StructureManager`, `StaticCache2D`): 978 classes, widened in about 4 seconds at startup. The 64-bit startup costs about 4 seconds more than in 3.5.7.
- No more void gaps: chunks at the int seams (x or z = +-2,147,483,648 and every 4,294,967,296 from there) and at the carry's scale coincidences (every 4,581,298,449, 5,726,623,061 and 22,906,492,245 blocks) are generated like any other. Live-tested one area at a time at 0, 2,147,483,647, -2,147,483,648, 6,442,450,944, 4,581,298,449, 5,726,623,061, 22,906,492,245, 3,518,437,208,883 and 35,000,000,000,000 (bedrock, then a block placed and read back), and six areas at once around two coincidence points.
- The widener (`CoordinateWidener`): a value merged from branches (`c ? x : 0`) is converted where one branch is an int; an int sentinel (`min = Integer.MAX_VALUE`) that becomes a coordinate becomes the long one; a static method with a long overload of the same shape (`Mth.clamp`, `Mth.square`) uses it; twins can be static helpers taking the receiver (`RandomSource.nextIntBetweenInclusive`, `nextInt(II)`, also through its subtypes such as `WorldgenRandom`); a class that cannot be written names the method in the report. `tools/asm-audit/ScopedDryRun.java` widens the loader's set offline and verifies every method.
- The carry knows scales: int signatures take block coordinates (`carryInBlock`) unless they are listed as chunk (`ChunkPos`, `SectionPos`, chunk lookups, `StaticCache2D`) or quart (biome lookups) ones; `Mth.floor` of a position carries at block scale. Where a block coordinate and its quart or chunk coordinate have the same low 32 bits, trying every scale took an int one block away for a chunk.
- Lambdas of the widened set: an interface hop narrows its arguments in order and the lambda takes them back by position when it starts (`WidePacking` transit), exact at every scale and without touching the carry's remembered coordinates.
- The carry remembers 8 far areas per thread instead of 4.
- Fixed (found in play at 35,000,000,000,000): the server stopped (watchdog) when mobs pathfound far out. Path nodes keep int coordinates and the point a mob walks to was made from them directly, so a zombie checked a straight line from its true position to the node's low 32 bits and the server loaded every chunk on the way. The points are carried next to the mob (`PathNodeLongMixin`), and a straight-line check longer than 1,024 blocks is never clear (`PathClearanceLongMixin`). 42 mobs at 35 trillion and at a coincidence point ran without a stall.
- Fixed (found in play at x = 2,147,483,647): the server stopped (watchdog) as soon as fish or squid swam near the int seam. Vanilla int code loops `for (x = min; x <= max; x++)` over boxes, and with `max == Integer.MAX_VALUE` `x++` wraps and never ends; the carry turned the wrapped x into positions ever further east and the server generated chunk after chunk. The carry never hands int code the int extremes (the one column at exactly +-2^31 reads as its neighbour there). Also: entities made by int code from a narrowed block position (falling sand, drops, TNT, XP) are placed next to where the thread works instead of on the other side of the world (`NewEntityPositionLongMixin`), and an int near +-2^31 that is already remembered from the other side of the seam is not remembered again (it pulled int code there to the wrong side).
- Mobs, part 1 (about a quarter of the hostile and peaceful mobs): the AI every mob uses (`world/entity/ai`: goals, navigation, movement and look controls, random positions, sensing, brains) and the pathfinder are widened, so path nodes and walk targets are true coordinates; so are zombie, husk, drowned, zombie villager, skeleton, stray, creeper, spider, cave spider, enderman, witch, slime, cow, pig, sheep, chicken, rabbit, cod, salmon, squid and glow squid with their base classes, and frog, strider, creaking, warden and the ender dragon (their own pathfinding overrides the widened one). 1,422 classes in all, widened in about 7.5 seconds at startup. `Entity.getBlockX()` / `getBlockZ()` have exact long twins for widened code (`WideEntity`); an enderman picking up a block at 35 trillion traced a line from the low 32 bits of its position. `Goal.adjustedTickDelay` / `reducedTickDelay` are marked as never carrying coordinates (`CoordinateWidener.notCoordinates`), which the analysis over-approximated. `ScopedDryRun` lists the classes outside the set that override a widened method (those break; 0 for this set).
- Mobs, part 2: every mob. The whole `animal`, `monster`, `npc` (villagers, wandering traders), `boss` and `raid` packages and `TamableAnimal` (wolves, cats, parrots): 1,860 classes in all, widened in about 10 seconds at startup. Live-tested with 42 more mob types (traders, golems, bees, foxes, wolves, cats, parrots, horses, llamas, goats, axolotls, turtles, dolphins, pandas, frogs, camels, armadillos, piglins, hoglins, zoglins, raiders, blazes, magma cubes, guardians, silverfish, endermites, phantoms, vexes, breezes, bogged, ghasts, shulkers, wither skeletons, tropical fish) across x = 2,147,483,647 and at 35 trillion: 1,323 position samples, 1,143 of them past 2^31, none out of place, no stall.
- Entities: all of `world/entity` (the base classes `Entity`, `LivingEntity` and `Mob`, items, projectiles, vehicles, decorations, players), with block collisions (`BlockCollisions`, `CollisionGetter`, `BlockGetter`, `EntityGetter`, `ClipContext`) and `VoxelShape`: 2,162 classes, widened in about 13 seconds at startup.
- Fixed (older than 3.5.8): past 2^31, minecarts, boats and falling blocks fell through the floor. `VoxelShape.move(Vec3i)` moved a block's collision shape by the int `getX()`, so every shape sat at the low 32 bits of its position; the sand never landed. Live-tested: armor stands, villagers, dropped items, minecarts, boats, arrows and falling sand at 1,000, at the seam, at 3 and at 15 billion stay on their floor (all 24 present), and the same after a restart.
- Fixed (older than 3.5.8): past 2^31, zombies and skeletons did not burn in daylight (the sky check read the low 32 bits of their position). They burn like near spawn now.
- `CoordinateWidener.notCoordinates` also takes fields: counters and levels that int math on a coordinate reaches (fire ticks, total experience, horse temper, raid omen level, warden warning level, animation and sound ticks, the pathfinder's node limit) stay ints. `Player.totalExperience` had become a long that `ServerPlayer`, outside the set, reads as an int.
- Fixed in the widener: a value merged from a long branch and an int branch (`c ? random(1, 80) : tick - 1`) that is stored as an int got both converted (`Breeze.tick`).
- Fixed: the widener's common superclass of two types (for stack frames) treated interfaces outside the set as classes; a `Player` meeting an `Entity` in `SulfurCube.playerPush` became `Object` and the class failed to verify. `ScopedDryRun` now type-checks with the game's class hierarchy (it only checked value sorts before).
- Safety net: a ray (line of sight, block picking, mob goals) longer than 4,096 blocks is made empty instead of loading every chunk on the line (`ClipContextLongMixin`).
- Live-tested: the 21 mobs with villagers and iron golems on land and in water across x = 2,147,483,647 and at 35,000,000,000,000 for 90 seconds (about 1,000 position samples, none out of place, no stall); without a player near, the hostile ones stand mostly still, as they do near spawn.
- Faster: a lambda hop takes one thread-local lookup on each side instead of about seven. Generation in 64-bit mode was 2.6 times as slow as without it (surface rules call a lambda per rule per block); it is now about 2 times (8.4 s against 4.1 s for the same 36 chunks with their surroundings).
- Removed: the 3.5.7 generation gaps (`ChunkStatusTasksLongMixin`) and the generation hints (`ChunkGenerationLongMixin`, `ChunkMapStepLongMixin`), which generation no longer needs.
- Crash reports logged at once show 12 stack frames instead of 5.
- `max_coordinate` is read leniently: separators and stray symbols around the digits (`140_737_488_355_328`, `30,000,000`, quotes, a pasted bullet) are ignored, and a power of two can be written `"2^52"`. A value that is still not a number is logged as an error (it was a warning, and the world silently stayed at 30,000,000 blocks, so every teleport past that was an "invalid position").
- Fixed: a transit frame (lambda hop) was left behind after its call, and a callback that int code called later took the old arguments whenever their low 32 bits matched. At exact multiples of 4,294,967,296 (every power of two from 2^32 up) a carving lambda read its local x = 0 as the far coordinate and the aquifer indexed 2^32 blocks off (`ArrayIndexOutOfBoundsException`). A frame now ends when its call returns.
- Fixed: the server stopped (watchdog) generating the chunk below an odd multiple of 2^31 (the one whose narrowed minimum is 2,147,483,632) when it placed mobs: vanilla retries a spot until it lies in `[minX, minX + 16)` in int math, and `minX + 16` overflowed. That chunk column gets no mobs at generation (`NaturalSpawnerLongMixin`).
- Chunks, entities and POIs read from disk remember their chunk from its 64-bit key instead of its int coordinates.
- Singularity Wide Client: one jar for Minecraft 26.1, 26.2 and 26.3 (every Mixin target checked against all three client jars with the new `tools/asm-audit/MixinTargetCheck.java`), and a separate build for 1.21.11 (`wide-client-mod-1.21.11`, Fabric Loom with Mojang's names, remapped to intermediary; `./gradlew -p wide-client-mod-1.21.11 build`). Fabric Loader 0.18.4 or newer. Older clients join a 26.3 server through ViaProxy (`run/viaproxy/start-viaproxy.bat`); a status ping as 1.21.11, 26.1 and 26.2 through it reaches the server. Not played in game yet.
- Fixed: everything within 4,096 blocks of an exact multiple of 4,294,967,296 (2^32, 2^47, 2^50, every power of two from 2^32 up) failed to generate. Int code saw the low 32 bits of a coordinate, which there are below 4,096, and the carry takes those as offsets and heights (they cannot be told apart), so int code worked next to 0 instead (environment attributes, mineshaft biome checks: "Requested chunk unavailable", watchdog). Int code now sees far coordinates shifted (`WidePacking.narrow`): the world past the int range is cut into stretches of 2,130,706,432 blocks, each seen as one translation, positive ones as ints in [-2^31, -2^24) and negative ones in [2^24, 2^31). So no far coordinate ever reads as a small int, and a spawn-area coordinate (within +-2^23) can no longer be taken for a far one either. The first stretch on each side is the low 32 bits as before, so nothing changes within +-4,278,190,080 blocks. Where a stretch ends the next starts lower (like the int range's own end at 2^31: int loops across it run empty); the carry looks across it. Quart and chunk coordinates follow the block ones. Live-tested: generation, a block placed and read back and an entity teleported at exactly 2^32, 2^47, 2^50, 2^51 and 2^52 - 2^32, and at both ends of the first stretches (+4,278,190,080 and -4,278,190,081).
- Saves past +-4,278,190,080 blocks (up to 2^34) made before this hold the old view of their ints: such a chunk is read the old way (`WidePacking.beginOldNarrowing`) and its block entities' x / z are rewritten; chunks saved from now on carry `singularity_narrowing` and their true `xPos` / `zPos`. Live-tested: a chest, a furnace with their items and a gold block saved by 3.5.7 at 10 billion load, and again after saving with this build, twice. Entities and POIs saved there before keep any int positions in them in the old view (entity positions themselves are doubles and stay right).
- The network origin (3.5.6) reads the ints int code made of a position next to the player's origin instead of assuming low 32 bits (block, chunk and light packets).
- An implementor outside the widened set of a widened interface gets its int arguments narrowed (`narrow`, at the interface's scale, not remembered) instead of a plain cast. A plain cast gave far coordinates their low 32 bits, which int code elsewhere no longer sees.
- Fixed in the widener: a long that meets an int that is never a coordinate handed to game code (array index or size, shift amount, switch, a JDK or library method, the result of `hashCode` / `compareTo`) is narrowed without the carry remembering it. `ChunkPos.hashCode` remembered its hash (about 10^15) as a coordinate, so ints next to it were carried there: during a test at x = 2^31, chunks 5 * 10^11 blocks away were generated and the server stalled (watchdog, about every second run). It was there before too, but the low 32 bits hid it.
- `getMaxLocalRawBrightness` checks the world limit with the true coordinates in 64-bit mode (past the first stretches the narrowed ones read as beyond it: full brightness, no monsters).
- The int signatures of the conversions (`SectionPos.blockToSectionCoord` / `posToSectionCoord`, `QuartPos.fromBlock` / `fromSection` / `toBlock` / `toSection`) narrow their result at its own scale (`"ret:<scale>:"` entries in `PositionWidening.CHUNK_SCALED`); they took the scale of their argument, which the low 32 bits of before did not notice.
- Known: generation is still about twice as slow as with 64-bit mode off; with view distance 8+ a long teleport takes a while to fill in.
- Known: entities are not widened yet. Int code (block updates, falling blocks, collisions) still builds entity positions from 32-bit ints and relies on the carry, so several far areas loaded at once (five in testing, one on the int seam) can still fail to generate or stall a tick; two areas exactly 4,294,967,296 blocks apart in use at once remain ambiguous for that int code. Next: entities (long block position plus a double offset), the locator bar and structure-block offsets.

## [3.5.7] - Unreleased

Seventh of eight updates towards a true 64-bit X/Z world (docs/ROADMAP_64BIT.md): crash fixes and client polish.

- Fixed: the server hung ("Watching Server" crash report in `BlockCollisions`) when a player went to x = 2,147,483,647. Int code computed `floor(maxX) + 1`, which wraps there; the carry now also remembers coordinates within 16 million blocks of 2^31, and a box whose corner still wrapped is empty instead of a 4-billion-block loop (`Cursor3D`).
- Fixed: chunks around the int seams (x or z = +-2,147,483,648, then every 4,294,967,296) failed to generate (aquifers asked for a negative volume; the server then waited for the chunk forever). Terrain generation is not widened yet, so chunks within 512 blocks of a seam are left empty: a void gap.
- Fixed: the world border stopped at +-2,147,479,536 (`max_world_size` is an int), so past it everyone was outside the border (border damage in survival). In long mode the border reaches `max_coordinate`; `max_world_size` no longer limits it there.
- Fixed: int code's limit checks refused positions near 2^31 ("That position is out of this world!" for `/forceload` at 2,147,483,600). `/forceload` across the seam marks the right chunks (`ForceLoadCommand`, `ColumnPos` widened).
- Fixed: a generation worker that had just worked 2^32 blocks away (the two seams are) mixed the areas up (crash in `ChunkDependencies.get`): every generation task remembers its own chunk first.
- Fixed: mobs placed during generation far out landed 2^33 blocks off (`NaturalSpawner` turns coordinates into doubles).
- Fixed: past 2^35 blocks (34,359,738,368) chunk coordinates no longer fit an int either, and the carry took some of them for block coordinates (crash `ChunkHolder ... is null` at x = 3,518,437,208,883). Int signatures that hand out or take chunk and section coordinates (`ChunkPos`, `SectionPos`, the level's `getChunk` / `hasChunk`, and calls to them inside widened code) carry at chunk scale (`carryInChunk` / `carryOutChunk`); the carry keeps every remembered coordinate at block scale and compares candidates in blocks.
- Fixed: generation crashed ("Requested chunk unavailable during world generation", inverted structure boxes) at the scale coincidences of the carry: X or Z where a block coordinate and its chunk or quart coordinate have nearly the same low 32 bits (block/chunk every 2^36/15 = 4,581,298,449 blocks, block/quart every 2^34/3 = 5,726,623,061, quart/chunk every 2^36/3 = 22,906,492,245; for example 4,581,298,449 and 3,518,437,208,883 = 2^45/10). An int there reads as either; until generation is widened (3.5.8) chunks within 32,768 blocks of one are left empty like the seams (structures: 65,536).
- Known: several far areas loaded at once within about 70,000 blocks of each other next to such a point can still fail to generate (the carry compares with coordinates remembered from the other areas). One player's surroundings work.
- A crash report's cause is logged at once: building the report collects system information, which takes minutes on Windows machines with broken performance counters, while the server waited with nothing in the log.
- Singularity Wide Client: F3 shows the true position (XYZ, Block, Chunk and region) on a server that shifts the client's frame, and coordinates suggested while typing a command are the true ones. The server sends the shift as a custom payload that vanilla clients ignore.
- Lag measured: long mode costs about 10 % at startup and 5 % while generating; the rest is terrain generation (view distance 10 in the Far Lands is heavy).

## [3.5.6] - Unreleased

Sixth of eight updates towards a true 64-bit X/Z world (docs/ROADMAP_64BIT.md): clients past 2^31.

- Floating origin (`net.singularitymc.network.FloatingOrigin`): every player's client sees the world shifted by an origin near them (a multiple of 1,048,576; 0 within 8,388,608 of the centre, so normal worlds are unchanged), so vanilla clients can play anywhere. A teleport (or walking) out of the frame moves it: chunks and entities are dropped and tracked again, border, spawn and time sent again, and the player is told the shift in chat (true position = F3 position + shift).
- Shifted on the wire: block, section and chunk positions (their codecs, so also entity data, items, GlobalPos), entity spawns, teleports and position syncs, player positions (absolute components only), chunks and light, chunk cache centre, particles, explosions, sounds (also fixed: their int eighths stuck past 2^28 blocks), damage sources, look-at, vehicles, the world border centre; from the client: movement, vehicle movement, teleport confirmations, block positions. The hit location of a used block is rebuilt from the 64-bit block position (`BlockHitResult` widened).
- Live-tested with a vanilla 26.3 client: joined, teleported to 3,000,000,000 and 10,000,000,000 and back, terrain renders and the position stays in sync.
- Known (3.5.7): F3 and typed or tab-completed coordinates are in the client's shifted frame; the locator bar and structure-block offsets are not shifted.

## [3.5.5] - Unreleased

Fifth of eight updates towards a true 64-bit X/Z world (docs/ROADMAP_64BIT.md): saves.

- Chunks, entities and POIs past 2^31 are saved and loaded, up to +-17,179,869,184 blocks (chunk coordinates fit the format's ints; structure-reference keys are saved in the vanilla int-pair form). Farther chunks still regenerate after a restart.
- The int block positions in saved data (block entities, scheduled ticks, structure boxes, POIs, entities' own block positions) are carried back next to the chunk or entity being read (`WidePacking.carryChunk` / `carryAt`). Region files get their true names (for example `r.5859375.0.mca` at 3 billion).
- Live-tested: blocks, a chest with items, armor stands and villagers survive a restart at 3,000,000,000 and 15,000,000,000.

## [3.5.4] - Unreleased

Fourth of eight updates towards a true 64-bit X/Z world (docs/ROADMAP_64BIT.md).

- Server-side play past 2^31: with `experimental_long_positions` and `full_int_range`, `max_coordinate` goes up to 4,503,599,627,370,496 (2^52). Live-tested with commands at 3,000,000,000 and 10,000,000,000: `/forceload`, generation (terrain, a village), `/setblock`, `/fill`, falling sand, pistons, mobs with area queries, a restart.
- World access widened (55 classes with nest members): `LevelReader`, `LevelAccessor`, `CommonLevelAccessor`, `ServerLevelAccessor`, `WorldGenLevel`, `Level`, `ServerLevel`, `WorldGenRegion`, `ChunkSource`, `ServerChunkCache`, `EntitySectionStorage`, `PersistentEntitySectionManager`, `IOWorker`.
- 64-bit chunk keys (vanilla within +-2^29 chunks, escape-coded beyond) and section keys (int layout within the int block range, section Y clamped to -127..126, escape-coded beyond), sharing the wide packing's region table. `ChunkPos.pack`/`getX`/`getZ`/`unpack` go through it.
- Carry (`WidePacking.carryOut`/`carryIn`/`floorCarry`): code that is not widened yet sees a far coordinate's low 32 bits; what it hands back, directly, shifted to quart or chunk scale, or as a double, is moved next to the true position. `Mth.floor`/`ceil` wrap (and carry) instead of sticking at 2^31-1.
- Widener: carry at the int/long boundary (int signatures, calls, outside fields, parameters); a widened abstract method keeps its int version abstract and gets a long default that calls it, so implementors outside the set keep working.
- Commands read absolute block coordinates as longs. Chunks, entities and POIs past the int block range are not saved or loaded (they regenerate after a restart; saves are 3.5.5); forced chunks are remembered.
- Known: command feedback and logs print the low 32 bits of far X/Z; see docs/ROADMAP_64BIT.md for the carry's limits. Clients cannot follow past 2^31 yet (3.5.6/3.5.7).
- Fixed a stray character in `SingularityLauncher.fatal` that stopped the build.

## [3.5.3] - Unreleased

Third of eight updates towards a true 64-bit X/Z world (docs/ROADMAP_64BIT.md).

- `experimental_long_positions` now also widens math and geometry: `Mth`, `QuartPos`, `Cursor3D`, `BlockBox`, `Direction`, `BoundingBox`, `AABB`, `Vec3` (33 classes with nest members, 51 long fields, 116 int bridges). `BoundingBox` min/max X/Z are long; `BlockPos.containing`, `Vec3.atCenterOf`, `new AABB(BlockPos)`, `AABB.of(BoundingBox)`, `BoundingBox.fromCorners`/`getCenter` (no midpoint overflow), and `relative(Direction, n)` work past 2^31 (`betweenClosed` / `Cursor3D` are widened but not yet checked there).
- Double -> block conversions use `Mth.lfloor` / `Mth.ceilLong` (always-used twins of `floor` / `ceil`).
- Widener: always-used twins take precedence over methods inside the widened set.
- Live-tested with the flag on: generation, 12 structures at spawn and at 1 billion, mobs, falling blocks, pistons, TNT, water, a restart.

## [3.5.2] - Unreleased

Second of eight updates towards a true 64-bit X/Z world (docs/ROADMAP_64BIT.md).

- `experimental_long_positions` (ServerConfig.toml, default off): `Vec3i`, `BlockPos`, `MutableBlockPos`, `SectionPos`, `ChunkPos` and their nest members (18 classes) get 64-bit X/Z when the server starts (`asm/PositionWidening`). Every method whose signature changed keeps its int signature as a bridge (82), so the rest of the game runs unchanged; `new BlockPos(3_000_000_000L, 64, 0)`, `offset`, `ChunkPos.containing`, `SectionPos.of` and `asLong`/`of` work past 2^31. Turns on the wide packing. Live-tested: generation, 12 structures at spawn and at 1 billion, mobs, a restart.
- 64-bit `BlockPos` packing: the wide packing's region table takes 64-bit region coordinates (`blockAsLong(long, int, long)`, `blockX64`, `blockZ64`); the int API gives the same values as before.
- Widener: scoped mode with int bridges, extra long twins (always used for exact replacements like the packing), twins take their argument types from the twin's descriptor.
- Known: axis-generic code (`Vec3i.cross`, `get(Axis)`) widens Y too; harmless (a long holds every height). Section and chunk keys stay 32-bit until 3.5.4.

## [3.5.1] - Unreleased

First of eight updates towards a true 64-bit X/Z world (docs/ROADMAP_64BIT.md, docs/ASM_64BIT.md).

- `asm/CoordinateWidener`: an ASM pass (no Mixin) that turns int X/Z into long: override families and interfaces, methods overriding JDK/library methods keep their descriptor, lambdas (captured coordinates widen), method references (bridges), records (`ObjectMethods` handles, record components), every DUP/POP/SWAP shape (replayed through temporaries), `java.lang.Math` and other JDK long twins (`Integer.compare` to `Long.compare`, ...), string concatenation; a value wanted both as int and as long is converted where it is used. Unit-tested on a fixture hierarchy.
- `tools/asm-audit`: coordinate audit, the test 1 proof of concept, and a dry run of the widener over the whole 26.3 server jar (68,689 of 68,698 methods pass the verifier, no dangling references; whole-jar widening still over-reaches, which is why 3.5.2 widens a closed set with int bridges).

## [3.5.0] - Unreleased

Backport release: Minecraft **26.1.2 to 26.3** are supported.

- `GameCompat`: warns for versions outside 26.1.2-26.3, and checks that the EULA/settings patches have their targets. If they do not, the loader generates `eula.txt` and `server.properties` from `ServerEula.txt` / `ServerConfig.toml` (compatibility mode) instead of failing.
- `EulaMixin` and `SettingsMixin` no longer stop the server when a target is missing (`require = 0`); all optimisation patches were already optional.
- Experimental events API for mods (`docs/EVENTS.md`): `EventBus` plus `BlockBreakEvent`, `BlockPlaceEvent`, `ItemUseEvent`, `EntityDamageEvent`, `CommandRegistrationEvent`, `PlayerJoinEvent`, `PlayerLeaveEvent`. The loader jar is now also a class source of the game class loader so event classes can use Minecraft types. Plugin side is a placeholder package only.
- `enable_farlands` (ServerConfig.toml, default on) with `farlands_style` (docs/FARLANDS.md): `"classic"` (default) generates the real Far Lands from 12,550,821 blocks out with a port of Beta 1.7.3's terrain generator, overflow included, under modern biomes, caves, ores and trees; `"mojang"` is Mojang's debug switch (`SharedConstants.DEBUG_ENABLE_FARLANDS`, 26.3+), which overflows every noise and mostly gives water and lava.
- `max_coordinate` (ServerConfig.toml, default 30000000 = vanilla): stage 1 of an experimental bigger world (`docs/COORDINATES.md`): lifts the hard-coded 30M limits (10 optional Mixins). Positions beyond 33,554,432 still break; stage 2 pending.
  Fixed: the negative side of the `Player.tick` clamp and of `/worldborder add|set` stayed at vanilla values; `getMaxLocalRawBrightness` (monster spawning light) is now covered; the highest `max_coordinate` is 1073737728 (2^30 minus 256 chunks): above 2^30 vanilla's own coordinate sums such as `(minX + maxX) / 2` overflow (seen crashing mineshaft generation).
- Stage 2 of the bigger world: wide position packing (`WidePacking`, 6 Mixins in `singularity-loader.wide.mixins.json`, including the chunk-validity limit in `ChunkPyramid`), loaded only when `max_coordinate` passes 33,550,336. `BlockPos`/`SectionPos` keys no longer alias past 33,554,432; network formats stay vanilla, and block positions from clients are un-aliased next to the player. Vanilla clients still mis-show block updates and light out there.
- Virtual 64-bit world (docs/FARLANDS.md): `/vtp <x> <z>` with 64-bit coordinates opens a window (a 16,777,216-block square of 64-bit space shown in a reserved real square, saved with the world); `/vpos` and the action bar show the virtual position. Far Lands stages 3 to 6 there: Gridded Lands (8,589,934,592), Fartherer Lands (53,905,378,846,980), Striped Fartherer Lands (2^57), Final Fringelands (9,223,372,036,854,000,000). Checked live in one window per stage.
- `full_int_range` (experimental, default off): `max_coordinate` up to 2,147,479,552. Every int `(a + b) / 2` and `(a + b) >> 1` in Minecraft's classes is rewritten to 64-bit math (`MidpointRewriter`); crawl-tested at 2.1 billion with all 52 structure types, mobs, a restart and block physics. The client mod has the same opt-in in `config/singularity-wide-client.properties`.
- The classic Far Lands include every stage that fits the world: Edge and Corner Far Lands (12,550,821) and Edge and Corner Farther Lands (1,004,065,811), checked live.
- `world_border_warning` (default false): border packets carry a warning distance / time of 0, so no client draws the red border tint (no resource pack needed). A border still at vanilla's saved size grows to the full `max_coordinate` size when loaded, so players past 30M are inside it and can break blocks (`ServerLevel.mayInteract` checks the border).
- Fixed: `EntityLimitMixin` and `WorldBorderLimitMixin` never applied (a bare method name picked an overload without the constants), so players were snapped back to exactly 30,000,000; they now name their descriptors. `Entity.load` (saved positions clamped to +-30,000,512) is patched too.
- New subproject `wide-client-mod`: **Singularity Wide Client**, a Fabric client mod for Minecraft 26.3 (Fabric Loader 0.19.5+, no Fabric API). Vanilla clients clamp themselves to 29,999,999 in `Player.tick`; the mod lifts the client's limits, uses the same wide packing, and un-aliases block/section positions from the server next to the player. Works in singleplayer too.
- Building no longer needs `run/GameLibraries` when `libs/client` is filled (`./gradlew setupClientLibs`); `totem-teleport-mod` finds the same libraries, and a missing-libraries build stops with instructions.
- Select a version with `--singularity-game-version=26.2` (each version gets its own `Vanilla-Server-<version>.jar`, but `GameLibraries`/`GameVersions` are shared, so use one server folder per Minecraft version).

## [0.1.0] - Unreleased

First release: a dedicated-server mod loader for Minecraft 26.3.

### Added
- Server 3.0 layout: `.Singularity/`, `GameLibraries/`, `GameVersions/`, `ServerLogs/`, `SingularityMods/`, `SingularityPlugins/`, `ServerEula.txt`, `ServerConfig.toml`. `ServerConfig.toml` replaces `server.properties` and `ServerEula.txt` replaces `eula.txt` (via `SettingsMixin`/`EulaMixin`); an old `server.properties` is imported once.
- New framed log format, banner and a single-line animated loading bar for downloads and unpacking; EULA question on first start.
- Downloads Mojang's official server jar on first start into `.singularity/server/<version>/` (through Mojang's version manifest, SHA-1 verified, cached, works offline afterwards). `--singularity-game-version`, `--singularity-server-url`, `--singularity-offline`.
- Runs Mojang's "bundler" jar by unpacking it itself (SHA-256 verified), and also runs an already-unpacked `versions/<id>/` + `libraries/` server.
- Clear error messages when the loader's `lib` folder is missing.
- `--singularity-setup`: downloads and unpacks the game and prints the `minecraftDir` for mod projects.
- Child-first game class loader with Mixin applied to every class it defines.
- Mixin (FabricMC fork 0.17.2) and MixinExtras 0.5.5 support, running on Java 25.
- `singularity.mod.json` mod format with entrypoints (`preLaunch`, `main`, `server`), Mixin configs,
  dependency declarations (`depends`, `recommends`, `suggests`, `conflicts`, `breaks`) and semantic version ranges.
- Dependency resolution with a deterministic load order and an error report that lists every problem at once.
- Built-in pseudo mods `singularity-loader`, `minecraft` and `java`.
- Public API: `SingularityLoader`, `ModContainer`, `ModMetadata`, `Version`, `VersionRange`, entrypoint interfaces.
- Server brand changes from `vanilla` to `singularity`.
- Crowd optimisations (see `docs/PERFORMANCE.md`): capped entity-to-entity pushing, throttled goal selection for crowded mobs, throttled villager brains (`-Dsingularity.opt.pushCap`, `crowd`, `crowdAiRate`, `crowdBrainRate`).
- Optimisation patches: resting items and XP orbs search for merge partners less often (`-Dsingularity.opt.itemMergeRest`, `-Dsingularity.opt.xpMergeRest`, `-Dsingularity.opt.disable=true`).
- Debugging aids: `--singularity-debug`, `--singularity-dump`, per-launch log file.
- `example-mod`, start scripts, unit tests and CI workflow.
