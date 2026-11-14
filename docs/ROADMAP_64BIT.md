# Road to a true 64-bit X/Z world (3.5.1 - 3.5.8)

docs/ASM_64BIT.md showed that ASM can turn int X/Z into long, and how much of the game that touches (1,846 classes,
603 fields, ~4,300 signatures, plus the save format, the protocol and the client). The work is split over eight
updates, easiest first; each builds on the one before. Until the last ones land, the 64-bit mode stays off and the
game behaves exactly as before.

How each step lands without breaking the rest: the classes of that step are widened as a closed set (with their
nest members), and every method whose signature changes keeps its int signature as a bridge. Code not yet widened
keeps calling the int versions; code widened in a later step calls the long ones.

Since 3.5.4 the bridges *carry* coordinates (`WidePacking.carryOut` / `carryIn`): narrowing a coordinate past the
int range remembers it on the thread, and an int coming back (directly, shifted right by 2 or 4, or turned into a
double) is moved to the value int code would have made of the remembered one next to it. So most of the game works
out there before it is widened. Since 3.5.8 int code sees far coordinates shifted, not as their low 32 bits
(`WidePacking.narrow`: stretches of 2,130,706,432 blocks, positive ones as ints below -2^24, negative ones above
2^24; unchanged within +-4,278,190,080), so no far coordinate reads as a small int (offsets, heights) and there is
no seam at multiples of 2^32. Limits: a coordinate is only restored within 2^24 blocks of where int code got it; at
the end of a stretch int loops across it run empty (as at 2^31); and an int position above 2^23 blocks can be
mistaken for a far one int code sees the same way, on a thread that just worked there. Text that int code prints
(command feedback, logs) shows the narrowed value.

| Version | Content | Status |
|---|---|---|
| 3.5.1 | The widener engine (`loader/asm/CoordinateWidener`): override families and interfaces, methods that override JDK/library methods keep their descriptor, lambdas (captured coordinates widen, SAM signatures stay), method references (bridges), records (`ObjectMethods` handles, record components), every DUP/POP/SWAP shape, `java.lang.Math` long overloads, string concatenation. Unit tests on a fixture hierarchy; a dry run over the whole 26.3 server jar with the bytecode verifier. | done |
| 3.5.2 | Position types: `Vec3i`, `BlockPos`, `MutableBlockPos`, `SectionPos`, `ChunkPos` (+ nest members, 18 classes) with long X/Z at load (`experimental_long_positions`), int bridges for every changed signature, 64-bit `BlockPos` packing. Live-tested with the flag on. Section and chunk keys move to 3.5.4. | done |
| 3.5.3 | Math and geometry: `Mth` (`floor`/`ceil` -> `lfloor`/`ceilLong`), `QuartPos`, `Cursor3D`, `BlockBox`, `Direction`, `BoundingBox` (long min/max X/Z), `AABB` and `Vec3` <-> block conversions, iteration helpers (`betweenClosed`); 33 classes with nest members. Live-tested with the flag on. | done |
| 3.5.4 | World access: the level interfaces with all implementors (`Level`, `ServerLevel`, `WorldGenRegion`, ...), `ChunkSource`/`ServerChunkCache`, entity sections, chunk IO; 64-bit section and chunk keys; a 64-bit `max_coordinate` (with `full_int_range`); commands read long coordinates. Code not widened yet is *carried*: it sees the low 32 bits, and what it hands back (also shifted to quart/chunk scale, or as a double) is moved next to the true position. Chunks past 2^31 are not saved (regenerate after a restart). Live-tested with commands at 3 and 10 billion: generation, a village, blocks, falling sand, pistons, mobs, a restart. | done |
| 3.5.5 | Saves: chunks, entities and POIs past 2^31 are saved and loaded, up to +-2^34 blocks (17,179,869,184; chunk coordinates stay ints, structure-reference keys are saved in the vanilla int-pair form). Int block positions in the data (block entities, ticks, structure boxes, POIs, entity fields) are carried back next to the chunk or entity being read. No new format needed. Live-tested: blocks, a chest's items, armor stands and villagers survive restarts at 3e9 and 1.5e10; 3e10 regenerates. | done |
| 3.5.6 | Network, as a per-player *floating origin* instead of a new protocol: each client sees the world shifted by an origin near it (a multiple of 2^20; 0 within 2^23 of the centre), so vanilla clients play anywhere. Block/section/chunk positions are shifted in their codecs, raw coordinates in the packets that have them; a teleport out of the frame drops and re-tracks the player's chunks and entities. Live-tested with a vanilla 26.3 client teleported to 3e9 and 1e10 and back: terrain renders, position stays in sync. | done |
| 3.5.7 | Crash fixes from play-testing: the 2^31 seam (collisions, `/forceload`, int limit checks, generation: chunks within 512 blocks of a seam stay empty), the world border reaching `max_coordinate`, generation workers mixing areas 2^32 apart, mobs placed by generation; chunk coordinates past 2^35 blocks carried at chunk scale; void gaps at the carry's scale coincidences (every 2^36/15, 2^34/3 and 2^36/3 blocks). Live-tested up to 3.5e13. Singularity Wide Client: true coordinates on F3 and in command suggestions. | done |
| 3.5.8 | Terrain generation at true coordinates: `levelgen`, `biome`, `chunk`, `lighting` and chunk loading widened (978 classes), no seam or coincidence gaps, scale-aware carries, exact hand-over to lambdas. Live-tested at the seams, the coincidence points, 3.5e12 and 3.5e13. Entities: all of `world/entity` (every mob, item, projectile, vehicle and player), the pathfinder, block collisions and ray tracing (2,162 classes in all). Wide Client for 26.1-26.3 (one jar) and 1.21.11 (Loom build), older clients through ViaProxy. The seam at exact multiples of 2^32 is gone (int code sees far coordinates shifted); live-tested at 2^32, 2^47, 2^50, 2^51 and 2^52 - 2^32. Still to do: block and block-entity code (`world/level/block`), explosions, commands, and entities past 2^53 (doubles lose whole blocks: long block position + double offset); the locator bar and structure-block offsets in the shifted frame. | part 1 done |
