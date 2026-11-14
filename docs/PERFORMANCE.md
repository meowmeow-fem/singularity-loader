# Performance guide

Singularity 4.2 is tuned for **many players on weak hardware**. Everything below is on by default, is set in the
`[performance]` section of `ServerConfig.toml`, and is switched off as a whole with `-Dsingularity.opt.disable=true`.

## Measured

50 fake players (see "Measuring" below) spread over 3,000 x 3,000 blocks, 10 % of them exploring new terrain, on
**2 CPU threads and 3 GB of heap**, Minecraft 26.3, `view_distance = 10`, `simulation_distance = 10`, 60 seconds
measured after 4 minutes of warm-up:

| | TPS | mean tick | median tick |
|---|---|---|---|
| all optimisations off (`-Dsingularity.opt.disable=true`) | 4.0 | 241 ms | 223 ms |
| fixed optimisations only (`adaptive_performance = false`, distances stay 10 / 10) | 8.9 | 112 ms | 100 ms |
| default (adaptive: ended at view 4, simulation 3) | **19.3** | **48 ms** | 38 ms |

With 20 players on the same 2 threads the default holds 20.0 TPS (38 ms) at view 7 / simulation 5.

What this does not measure: fake players send and receive no packets, so packet encoding, compression and the
network are not in these numbers, and they walk rather than build, fight or run farms. Treat the numbers as "how much
world simulation fits", not as a promise for your server.

## What the loader does

### 1. Adaptive performance (`adaptive_performance`, default on)

The server watches its tick times in 5-second windows. Over `target_tick_ms` (45) it raises a load level; well under
it for 30 seconds it lowers the level again (and waits longer each time a step down did not hold). Each level:

| Level | Simulation distance | View distance | Mob spawning | Far mobs (see 2) | Random ticks |
|---|---|---|---|---|---|
| 0 | as configured | as configured | every tick | beyond 48 blocks: 1 tick in 4 | all |
| 1 | -1 | | every 2nd tick | | |
| 2 | -2 | | | beyond 36 blocks: 1 in 10 | |
| 3, 4 | -3, -4 | -1, -2 | every 4th tick from 4 | | |
| 5, 6 | -5, -6 | -3, -4 | | beyond 24 blocks: 1 in 20 | every 2nd chunk tick |
| 7 + | ... | ... | | | every 3rd chunk tick |

The distances stop at `min_simulation_distance` (3) and `min_view_distance` (4) and never go above what `[world]`
says. Two levels at once if ticks take more than twice the target. The heap counts too: if it is still 85 % full
right after a garbage collection, the server holds more world than fits and the level rises whatever the tick times
are. Every change is logged:

```
[Perf] load level 4 of 10 (ticks took 104.2 ms, target 45; heap 16% full after collection): simulation distance 6,
view distance 8, mob spawning every 4 tick(s), far mobs beyond 36 blocks tick 1 in 10.
```

What players notice at high levels: a shorter view, mobs and farms outside the simulation distance stand still,
fewer new mobs, and from level 5 crops grow at half speed. A server that keeps its tick rate never leaves level 0.

### 2. Entity activation range (`entity_activation_range`, default 48 blocks)

In the baseline profile 40 % of the tick was mobs that no player was near. A mob with no player within the range
ticks only once in a while (1 tick in 4, less under load) as long as nothing happens to it. It keeps every tick while
it burns, is hurt, has a target, rides or is ridden, and (land mobs) while it falls or is in water; bosses always
tick. Fish, squid and bats can idle although they swim and fly. Growing up and breeding cooldowns keep running.
Far mobs are also checked for despawning every 4th tick instead of every tick.

Side effect: a mob farm whose mobs must walk somewhere by themselves while you stand farther away than the range gets
slower. Stand closer, raise the value, or set it to 0 (vanilla).

### 3. Entity tracking (`entity_tracking_interval`, default 8 ticks)

Vanilla re-checks every entity of the world against a player on every move packet of that player (20 a second), and
each check walked the entity's passengers through a stream: a third of the tick with 50 players. The check now
answers "out of range" without that walk, and the pass over all entities runs once every 8 ticks per player. Entities
that cross a chunk-section border are still updated in the same tick; what can be late, by up to 0.4 seconds, is an
entity appearing at the very edge of its tracking range.

### 4. Chunk work on an overloaded server (`overload_chunk_budget_ms`, default 3)

Vanilla unloads chunks and runs the chunk system's queued work only in the time left before the next tick. A server
that is behind has none: it keeps what it has loaded, and lowering the view distance does nothing. Every tick now gets
3 ms for unloading and 3 ms for chunk tasks, whatever the schedule says. The server thread also gets a slightly higher
priority than the chunk worker threads (honoured on Windows and macOS).

### 5. Natural spawning (`mob_spawn_interval`, default 1)

In ticks without spawning (the setting, or the adaptive level) the server also skips counting every entity and
collecting the chunks near players. Mob caps are unchanged; they fill more slowly, and the chunks' inhabited time
(local difficulty) grows that much more slowly.

### 6. Redstone, hoppers, mobs' searches and pathfinding (4.3)

Measured on test rigs in force-loaded chunks without players (`/stress machines 400 <kind>`, 26.3, all cores,
adaptive mode and activation range off), mean tick before and after:

| Rig (400 of each) | 4.2 | 4.3 | What changed |
|---|---|---|---|
| Observer clock driving 15 redstone dust on lamps | 74 ms | 61 ms | dust notifies each neighbour once |
| 8 hoppers moving items between chests + 16 idle hoppers (9,600 hoppers) | 7.1 ms | 6.2 ms | ticking-range lookups cached |
| 8 zombies chasing a villager in a room (3,600 mobs) | 80 ms | 73 ms | POI scans cached, ticking-range cache, pathfinding budget |

* **Redstone dust** (`redstone_dedup_wire_updates`): a dust that changes power makes vanilla notify 42 blocks: 25
  different ones, most several times, itself six times, and every notified dust recomputes its power from about 30
  block lookups. Each block is now notified once, at the place in vanilla's order where it came first. The dust
  algorithm is unchanged, including its slow step-by-step power-down of long lines (15, 13, 11, ...), which is where
  most of the remaining time goes; replacing that algorithm changes update order and is not done here.
* **Fast dust** (4.4, opt-in: `redstone_wire_algorithm = "fast"`): the same rig runs at a mean tick of **25 ms**
  (20 TPS). When a dust has to change, all dust within 15 steps gets its final power in one pass and the blocks
  around are notified once, after all dust is final. The powers are the same as vanilla's (checked on a line, a ring
  and torch-fed lines, switching on and off); the order in which pistons, lamps, repeaters and the like hear about
  the change is not, and they no longer see the intermediate powers, so a contraption built on vanilla's update
  order can behave differently. That is why it is off by default.
* **Hoppers** were already cheap in 26.x (about 0.25 microseconds per hopper and tick). The largest cost around them
  was the game asking "is this chunk ticking" for every block entity (and every entity) in every tick with a hash
  lookup; the answer is now remembered per chunk for the tick. This helps every block entity and entity, not only
  hoppers.
* **Points of interest** (`poi_scan_cache`): villagers without a bed or job, bees, golems and raids scan 7 x 7 chunk
  columns of 24 sections every second or two. A column found empty is skipped for 10 seconds, or until anything is
  added anywhere. A bed placed in such a column is seen at once (adding clears the memory).
* **Pathfinding** (`pathfinding_budget_ms = 8`, half of it from load level 3): once a tick has spent the budget,
  further path requests in that tick get "no path" and the mob retries a little later, as after any failed search.
  The search itself is vanilla's. In the mob rig the budget was reached in every tick, so those mobs do react more
  slowly there; a normal server does not come near it.
* **Lighting**: no change. The light engine already runs its work in batches off the server thread, and it did not
  show up in any of the profiles taken; nothing was found there that is both safe and worth it.

### 7. Crowds (since 0.1)

| Problem | Patch | Default |
|---|---|---|
| Every entity in a crowd pushes every neighbour each tick (cost ~ n²) | pushes per entity per tick are capped; cramming still sees the full crowd | 8 pushes |
| Goal / target selection of crowded mobs without a target | runs less often; running goals still tick every tick | 4th tick instead of 2nd |
| Villager brains in trading halls | crowded villagers think every 2nd tick; hurt ones always | every 2nd tick |
| Piles of items / XP orbs | resting ones look for merge partners less often | 2x slower |

"Crowded" = at least 10 pushable neighbours in the last collision step. JVM flags: `-Dsingularity.opt.pushCap=8`,
`crowd=10`, `crowdAiRate=2`, `crowdBrainRate=2`, `itemMergeRest=2`, `xpMergeRest=2` (each with the
`singularity.opt.` prefix).

## Settings for a small machine

* **Memory**: give the JVM about half of the RAM, with both values equal (`JAVA_OPTS="-Xms2G -Xmx2G"`), and keep the
  start scripts' garbage collector options (`GC_OPTS`: G1 with a larger young generation, tuned for Minecraft's
  allocation pattern). A loaded chunk costs roughly 70 KB of heap; 50 players at view distance 10 hold about 30,000.
* `view_distance` / `simulation_distance` in `[world]` are the upper limits the adaptive mode starts from. On 2 cores
  set them to what you want on a good day (8 / 6 is plenty); the server lowers them when it has to.
* `entity_broadcast_range_percentage = 60` sends entities to players from a shorter distance.
* `network_compression_threshold = 256` (default) is fine; `-1` only on a LAN.
* Cap mob farms and animal pens; the game rule for entity cramming (default 24) keeps packed mobs bounded.

## Measuring

`stress-mod/` is a test-only mod (not in the distribution): build it (`gradlew :stress-mod:jar`) and put
`singularity-stress-<version>.jar` into `SingularityMods` of a **test** server (Minecraft 26.3).

```
/stress spawn <count> [spread] [explorers %] [client view distance]    fake players (real ServerPlayers, no network)
/stress mark                                                           start a measurement
/stress report                                                         tick times, entities, chunks, GC share since the mark
/stress machines <count> [kinds]                                       test rigs at Y 200: r redstone, h hoppers, m mobs
/stress view <view> <simulation>                                       set the distances now
/stress chunks                                                         the chunk map's queues
/stress clear                                                          remove the fake players
```

Most fake players wander within 48 blocks of a home, the "explorers" walk straight on and keep generating terrain.
To imitate a small machine start the JVM with `-XX:ActiveProcessorCount=2 -Xmx3G` and pin it to two CPU threads.

On a real server use a profiler such as **spark** (`/spark profiler start`, let it run while the server lags, then
`/spark profiler stop`) and look at which tick phase dominates.

## Honest limits

* The numbers above come from fake players on one machine. Real players also cost packet encoding and compression,
  which is not measured, and nobody has played on a 4.2 server yet.
* Redstone, hoppers and pathfinding got the targeted changes of section 6, with gains of 9 to 18 % on the test
  rigs: useful, not a transformation. Not touched: the redstone dust algorithm itself, lighting, collision math, the
  path search itself, chunk generation speed. A server whose lag comes from one giant redstone machine is helped only
  a little (the adaptive mode will shrink distances, which helps if the machine is outside most players' simulation
  distance).
* Per player there is a floor: at the minimum distances a player still costs about 1 ms of tick on the test machine.
  Past that, only fewer players per server or faster cores help.
* The activation range and the spawn interval change gameplay slightly (see above). `-Dsingularity.opt.disable=true`
  gives vanilla behaviour.
