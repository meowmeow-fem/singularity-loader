# Coordinates beyond 30 million (experimental)

Status: stage 1 is in (config only, default = vanilla). Stage 2 (the 33.5-million wall) is in for the server side and
switches itself on when `max_coordinate` needs it. It passed unit tests, a Mixin harness and a live 26.3 server test
(see the end of stage 2), but no player has played out there yet. Vanilla clients still have their own 33.5M limit.

## Setting
`ServerConfig.toml`, section `[singularity]`:

| Key | Default | Meaning |
|---|---|---|
| `enable_farlands` | `true` | Mojang's built-in Far Lands switch (Minecraft 26.3+). Only new chunks. |
| `max_coordinate` | `30000000` | Biggest block coordinate (30000000 .. 1073737728). Also set `max_world_size` and `/worldborder` to match. |

The top is 2^30 minus 256 chunks. Vanilla adds two block coordinates in many places; the live test crashed at
x = 1.9 billion in mineshaft generation (`MineShaftPiece.isInInvalidLocation` takes the midpoint `(minX + maxX) / 2`,
which overflows past 2^30). Such sums cannot all be found and patched, so the limit keeps every two-coordinate sum
inside an int. The 256 chunks are headroom for chunks that load past the edge (view distance, generation margin).

## What `max_coordinate` changes (stage 1)
Replaces the hard-coded limits in: `Level` (bounds check, `getHeight`, `getMaxLocalRawBrightness`), `WorldBorder`
(+ `Settings` codec), `WorldBorderCommand`, `ForceLoadCommand`, `Entity.absSnapTo`, `Entity.load` (saved positions, +-30,000,512),
`ServerGamePacketListenerImpl` (movement clamp), `Player.tick`, `MinecraftServer.getAbsoluteMaxWorldSize`,
`DedicatedServerProperties` (`max_world_size`).

Targets that have overloads name their descriptor (`absSnapTo(DDD)V`, `WorldBorder(Settings)`): a bare method name
selects only one of them, and with `require = 0` a miss is silent. Until 3.5.0 `absSnapTo` and the `WorldBorder`
constructor were missed this way, which snapped players back to exactly 30,000,000. To check the hooks, look for
calls to the handlers in the transformed classes (`--singularity-dump`), not just for the merged handler methods.

Checked against the 26.3 bytecode (constant-pool scan of every `net/minecraft` class). Not changed on purpose:
`BlockPos.<clinit>` (derives the 26-bit packing width from 30,000,000; changing it breaks Y and the network format),
`DimensionType` (`coordinate_scale` codec range), `Attributes` / `Player` 6.0E7 (attribute ranges),
`GradientNoise` (Far Lands), `LegacyWorldBorderFix` (old-save upgrade), `SpatialLongSet` (hash-table sizing).

`getMaxLocalRawBrightness` is a `LevelReader` default method; `Level` overrides it instead of using an injector in an
interface mixin, so `WorldGenRegion` and other non-`Level` readers keep the 30M check.

## Up to 2.1 billion: `full_int_range` (experimental, opt-in)
`full_int_range = true` in `[singularity]` lets `max_coordinate` go up to **2,147,479,552** (2^31 minus 256 chunks).
Past 2^30 the int sum of two coordinates overflows. `classloading/MidpointRewriter` rewrites every int
`(a + b) / 2` and `(a + b) >> 1` in Minecraft's classes to `SafeMath.half` / `halfFloor`, which add in 64 bits (the
result is identical wherever nothing overflows). In 26.3 that is 18 sites in 10 classes; the coordinate ones are
`BoundingBox.getCenter`, `JigsawPlacement.addPieces` (villages, bastions, ancient cities, trail ruins), mineshafts,
ruined portals and structure spacing. Clients set the same in `config/singularity-wide-client.properties`
(`max_coordinate`, `full_int_range`); the client mod rewrites those coordinate classes for singleplayer.

Crawl test (26.3 server, seed 1234, `max_coordinate = 2147479552`, six runs, no exceptions):
- ~1,500 chunks generated at x = 2.1 billion (edge and corner) and at the 2,147,479,000 corner.
- `/place structure` for all 52 structure types at x = 2,100,000,256: 49 generated; desert and jungle pyramids also
  fail at spawn (vanilla terrain rules), the monument also fails at 12.6 million (it needs open deep ocean, the Far
  Lands are solid). A village and a mineshaft at the 2,147,479,000 corner generated.
- 36 mobs (12 kinds) ticked at 2.1 billion and at the corner and were still there after a restart.
- At the corner: water flows, sand falls, a piston fires, TNT explodes, lightning, `/clone`.

Not covered: sums that are not midpoints (for example `x + size` in rare code paths), and anything the crawl does
not reach (some redstone, rare mob AI). Back up worlds that use it.

## Hard ceilings
- **64-bit coordinates are not possible.** `BlockPos`, `ChunkPos`, `SectionPos`, region files and the network protocol
  all use 32-bit ints, and entity positions are doubles (no whole-block precision past 2^53). It would be a rewrite of
  the game, not a patch.
- **2^31** is the ceiling of the int type. **2^30** is the safe default: vanilla code like `(minX + maxX) / 2`
  overflows above it; `full_int_range` (above) rewrites those midpoints to reach 2,147,479,552. The packing in
  stage 2 itself handles the full int range.

## Why 33,554,432 (2^25) is a wall
`BlockPos.asLong()` packs X and Z into 26 bits each and Y into 12. `SectionPos` uses 22/20/22 bits (also 2^25 blocks).
A block position needs 76 bits at full int range, so no single `long` can hold it: positions past +-33,554,432 alias,
and decoding (`BlockPos.of(long)`, `getX(long)`) returns the wrong place 67,108,864 blocks away.

### Why SplitMix64 / MurmurHash3 cannot fix it
They are good 64-bit mixers, but the problem is not hash quality:
- 76 bits of input into 64 bits of output must collide (pigeonhole), whatever the function.
- The light engine, pathfinding, aquifers and `SectionPos` *decode* the packed value back to x/y/z. A hash is one-way
  (SplitMix64's finalizer is a bijection on 64 bits, but that does not help: the input is still 76 bits).
- fastutil's `Long2*` maps already mix keys (`HashCommon.mix`), so even as a hashCode improvement there is little gain.

SplitMix64 is used in stage 2, but only to spread the keys of the far-region table.

## Stage 2: wide packing (implemented)
`util/WidePacking` plus `patches/wide/*` in a separate Mixin config, `singularity-loader.wide.mixins.json`. It is only
registered when `max_coordinate` > 33,550,336 (2^25 minus 256 chunks); otherwise nothing changes. Inside +-2^25 every
packed value is bit-identical to vanilla.

| Patch | What it does |
|---|---|
| `BlockPosWideMixin` | Overwrites `asLong(III)`, `getX/Y/Z(J)`, `getFlatIndex(J)`. Raw Y 2032..2063 are escape codes; far positions use a region table (8192 squares of 65,536 blocks, SplitMix64-hashed, never emptied while running). |
| `SectionPosWideMixin` | Overwrites `asLong(III)`, `x/y/z(J)`, `getZeroNode(J)` with the lossless bijection (no table). `STREAM_CODEC` stays vanilla on the wire. |
| `EntitySectionStorageWideMixin` | Rewrites the two key-range lookups so columns that share key fields are told apart. |
| `ChunkPyramidWideMixin` | `ChunkPos.isValid` (chunk creation, `Level` bounds) uses a limit derived from `BlockPos.MAX_HORIZONTAL_COORDINATE` (2^25 - 1, from the packing); it is computed from the full int range instead. Found by the live test, not the constant scan, because the value is computed at run time. |
| `ChunkPosWideMixin` | The same limit on 26.1 to 26.1.2, where it is a field of `ChunkPos` itself (it moved to `ChunkPyramid` in 26.2). Each game has one of the two hooks (4.1, docs/VERSIONS.md). |
| `FriendlyByteBufWideMixin` | Block positions on the wire stay vanilla; incoming ones are un-aliased next to the sending player. |
| `PacketDecoderWideMixin` | Tells the above which player's packet is being decoded. |

Limits:
- Section Y is clamped to -128..127 (block Y -2048..2047) in the entity, light and POI keys. Entities flung above that
  share the top section; queries are clamped the same way, so they are still found.
- If more than 8192 far regions are touched in one run, new ones alias like vanilla until a restart (logged). Random
  seeds taken from positions (end gateway exit search, trial spawners, desert pyramid loot) differ between runs out there.
- Vanilla clients cannot go past 29,999,999 at all (their own `Player.tick` clamp). Players need the client mod below.

How it was checked: `WidePackingTest` (round trips over the full int range, injectivity, vanilla equality, region
table overflow), plus a harness that boots the loader's Mixin setup on the 26.3 server classes with
`max_coordinate = 2000000000` and checks `BlockPos`/`SectionPos` round trips, wire formats, and that two entities
2^26 blocks apart stay in separate entity sections (with the default limit the same checks fail, as vanilla aliases).

Live test (26.3 server, `max_coordinate = 1073737728`, console commands): A = (1,000,000,000, z 1,000,000,000) and
B = A - 2^26 in X, which share packed keys in vanilla.
- Both far chunks load and generate (Far Lands on); no exceptions.
- Glowstone in a sealed stone box at A: light next to it >= 14. The same sealed box at B, without glowstone: light 0.
  A control box at x = 100: light >= 14.
- Armor stands at A and B: a query near A finds exactly 1; killing it leaves the one at B alive.

## Client mod (`wide-client-mod`)
A vanilla client cannot go past 29,999,999: `Player.tick` clamps the local player there (the client runs the same
code as the server), and the server then logs "moved too quickly". The client also decodes block updates and computes
lighting with the 2^25 packing. So players who want to go past 30M need **Singularity Wide Client**, a Fabric mod.

It is the loader's own patch set compiled for the client (the source files are shared, see
`wide-client-mod/build.gradle.kts`): the stage 1 limits, the stage 2 packing, and a client `PacketDecoder` patch that
un-aliases incoming block and section positions next to the local player (or next to a teleport target the main
thread has not applied yet). Its limit is the highest `max_coordinate` (1,073,737,728); the server enforces its own.
Wire formats are vanilla, so the mod is harmless on vanilla servers, and singleplayer/LAN worlds get the patches too.

Build: `./gradlew :wide-client-mod:jar` (needs `libs/client`, see `./gradlew setupClientLibs`) gives
`wide-client-mod/build/libs/singularity-wide-client-<version>.jar`. No Fabric Loom or Fabric API: 26.x is not
obfuscated, so the jar is plain Mixins.

Install: Fabric Loader 0.19.5 or newer for Minecraft 26.3 (Fabric installer), then put the jar into `.minecraft/mods`.

Checked: a harness applies the mod's three Mixin configs to `client-26.3.jar` (all hooks land, including the
`Player.tick` clamp) and checks packing round trips, un-aliasing of block and section update positions, and the
teleport reference. Not yet checked in a running game client.

## Original stage 2 notes
Call sites of the packing (26.3): `BlockPos` (`asLong(III)`, `getX/Y/Z(J)`, `of(J)`, `offset(J..)`, and
`getFlatIndex(J)`, which is raw bit math: `pos & -16`), `SectionPos`, `FriendlyByteBuf`, the light engines and
section storages, `Aquifer$NoiseBasedAquifer`, `SamplerContext`, the three node evaluators, `PathTypeCache`, POI/AI
behaviours, `Biome`, `TheEndGatewayBlockEntity`, `TrialSpawnerStateData`, `DesertPyramidStructure`, `DimensionType`
(`PACKED_Y_LENGTH`). Everything except `getFlatIndex` goes through `asLong(III)` / `getX/Y/Z(J)`, so overwriting
those (plus `getFlatIndex`) covers the callers, but not code that relies on the *layout*:
- `EntitySectionStorage` selects a chunk column with `subSet(asLong(x, 0, z), asLong(x, -1, z) + 1)`, which assumes Y
  is the low bits; any new layout must keep "same column = contiguous key range".
- Wire format: `FriendlyByteBuf.writeBlockPos` and section-update packets must keep the vanilla 26/12/26 and 22/20/22
  layouts for vanilla clients.

Lossless layouts that keep vanilla values unchanged inside +-2^25 (no effect on normal worlds):
1. `SectionPos`: section Y only needs -128..127 (8 bits) but has 20. The 2^20 - 256 unused Y values are exactly enough
   to encode every far column (4095 aliased columns x 256 Y per X/Z field pair), so a bijection exists. Caveat: entities
   above Y ~2047 must have their section Y clamped when in this mode.
2. `BlockPos`: Y values -2048..-2033 and 2032..2047 are never valid (dimension limits), 32 escape codes = 57 bits of
   payload. That is not enough for 76 bits, so far positions need a region table (e.g. 8192 slots of 65,536-block
   squares), which is never freed during a run and must not be written to disk.
3. Un-alias block positions arriving from vanilla clients using the player's position (reach is only a few blocks).
4. Accept that vanilla clients mis-place block updates beyond 33.5M (their decoder is 26 bits) and compute their own
   lighting wrongly there. Playing normally out there needs a client-side mod as well.
