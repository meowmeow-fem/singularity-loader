# Singularity API

Since 4.0 the **Singularity API** is a mod of its own (`singularity-api/`, mod id `singularity-api`, version 4.1.0). It
gives mods and plugins the same API, without Minecraft types, and it loads the plugins of `SingularityPlugins/`
([PLUGIN_DEVELOPMENT.md](PLUGIN_DEVELOPMENT.md)). It runs on Minecraft 26.1 to 26.3 ([VERSIONS.md](VERSIONS.md));
`server().minecraftVersion()` tells a plugin which one.

```
SingularityMods/singularity-api-4.1.0.jar     the mod: API + implementation + plugin loader
singularity-api-4.1.0-api.jar                 the public API only: what plugins compile against
```

## Packages (`net.singularitymc.sapi`)

| Package | What |
|---|---|
| `sapi` | `Singularity` (entry point: `server()`, `forMod(id)`, `plugins()`, `permissions()`, `apiVersion()`), `ApiContext`, `Logger` |
| `sapi.server` | `Server`: players, worlds, broadcast, console commands, tick count, `maxCoordinate()`, `longPositions()` |
| `sapi.entity` | `Entity`, `Player` (also a `CommandSender`), `GameMode` |
| `sapi.world` | `World` (block get/set by id), `BlockLocation` (long x, int y, long z), `ChunkLocation`, `Location` |
| `sapi.command` | `CommandSpec`, `CommandExecutor`, `TabCompleter`, `CommandSender`, `CommandRegistry`, `CommandException` |
| `sapi.event` | `Events` and the API events (`server`, `player`, `block`, `entity`) |
| `sapi.scheduler` | `Scheduler`, `Task` (ticks on the server thread, or async) |
| `sapi.config` | `Config` (TOML file per mod / plugin), `Toml` (read / write TOML) |
| `sapi.permission` | `Permissions`, `PermissionDefault`, `PermissionProvider` |
| `sapi.plugin` | `JavaPlugin`, `PluginDescriptor`, `PluginManager`, `PluginInfo`, `PluginState` |
| `sapi.text` | `Text.colorize("&aHi")` |
| `sapi.internal...` | the implementation (not in the API jar; do not use) |

The loader's own API (`net.singularitymc.api`: `SingularityLoader`, `EventBus`, `EventPriority`, `Version`) is
used by it and stays as it was.

## Using it from a mod

Depend on the API mod and get a context for your mod id in the `server` entrypoint (the API mod starts in its own
`server` entrypoint, so declare the dependency to run after it):

```json
"entrypoints": { "server": ["com.example.MyMod"] },
"depends": { "singularity-api": ">=4.0.0" }
```

```java
public final class MyMod implements ServerModInitializer {
    @Override public void onInitializeServer() {
        ApiContext api = Singularity.forMod("mymod");
        api.commands().register(CommandSpec.of("spawnpos"), (sender, label, args) -> {
            sender.sendMessage("Spawn is in " + Singularity.server().overworld().id());
            return true;
        });
        api.events().listen(ServerStartedEvent.class, e -> api.logger().info("{} worlds", e.server().worlds().size()));
        api.scheduler().runTimer(20, 20 * 60, () -> api.logger().info("another minute"));
    }
}
```

A mod's data folder is `config/<id>/`; `api.config()` is `config/<id>/config.toml`, with defaults from
`<id>.config.toml` in the mod jar. Mods can keep using the loader's events with Minecraft types
(`net.singularitymc.event`, see [EVENTS.md](EVENTS.md)) and Mixin; the API is an addition, not a replacement.

## Using it from a plugin

Extend `JavaPlugin`; `context()` is the plugin's `ApiContext` and `JavaPlugin` has shortcuts for all of it. See
[PLUGIN_DEVELOPMENT.md](PLUGIN_DEVELOPMENT.md).

## How mods and plugins share it

```
loader class path (parent first)     net.singularitymc.api.*  (EventBus, Version, ...)    one copy for everyone
game class loader                    Minecraft, mods, the Singularity API mod (sapi.*)    one copy for everyone
  └─ plugin class loader (one each)  the plugin jar, then the plugins it depends on
```

A plugin's class loader asks the game class loader first, so every plugin and every mod see the same `Singularity`,
the same event bus, scheduler and command registry. Plugins are not transformed by Mixin. They *can* reach Minecraft
classes through the game class loader, but then they depend on Minecraft's internals and on the coordinate rules below.

## Events: loader events and API events

The loader's patches post their events (`net.singularitymc.event.BlockBreakEvent`, ...). The API mod listens to them
at `HIGHEST` priority and posts the matching API event (`net.singularitymc.sapi.event.block.BlockBreakEvent`, ...).
So: listeners of the loader event run first; if one of them cancels, the API event is not posted; cancelling the API
event cancels the loader event and the action. Chat, server start / stop and ticks come from the API mod's own Mixins.

| API event | From |
|---|---|
| `PlayerJoinEvent`, `PlayerQuitEvent` | loader `PlayerJoinEvent`, `PlayerLeaveEvent` |
| `BlockBreakEvent`, `BlockPlaceEvent` | loader `BlockBreakEvent`, `BlockPlaceEvent` |
| `EntityDamageEvent` | loader `EntityDamageEvent` |
| `PlayerChatEvent` | `ServerGamePacketListenerImpl.broadcastChatMessage` |
| `ServerStartedEvent`, `ServerTickEvent`, `ServerStoppingEvent` | `DedicatedServer.initServer`, `MinecraftServer.tickServer`, `MinecraftServer.stopServer` |

## 64-bit coordinates

With `experimental_long_positions` Singularity's worlds go far past the int range (the test server runs to 2^52).
Minecraft's position classes are widened at load time to 64-bit X/Z, but **only the game's own classes** are
widened. A mod or plugin is not: if it calls `BlockPos.getX()` it gets the low 32 bits, and 35,000,000,000,007
becomes 311,504,903, a block on the other side of the world.

The API never does that:

- `BlockLocation` / `ChunkLocation` are `long`. Converting to Minecraft goes through the packed long
  (`BlockPos.of(WidePacking.blockAsLong(x, y, z))`, `WidePacking.blockX64(pos.asLong())`): those methods keep their
  signatures, so unwidened code runs the widened bodies and gets the true value. This was checked on a test server:
  a block set at X = 35,000,000,000,007 reads back there, and the low-32-bit position stays untouched.
- `Location` and entity positions are doubles (Minecraft's own `Entity.getX()` is true). `Entity.blockLocation()`
  floors to `long`, like the loader's `WideEntity.blockX`.
- Never cast a coordinate to `int`. `BlockLocation.offset` fails loudly on `long` overflow instead of wrapping.
- `Server.maxCoordinate()` is the world's limit as a `long`; `Server.longPositions()` says whether the 64-bit mode is on.

Without `experimental_long_positions` the same code works unchanged: X/Z just stay inside the int range.

## Threads

Commands, events and `runTask` / `runLater` / `runTimer` run on the server thread; that is where worlds and players may
be used. `runAsync` runs on a background thread; hand results back with `callSync`. `Server.players()`,
`broadcast` and `dispatchCommand` are safe from any thread.

## Versioning

The API version is the API mod's version (4.0.0). Plugins name the versions they work with in `plugin.toml`
(`api = ">=4.0.0"`), mods in `depends`. Within 4.x changes are additions only.
