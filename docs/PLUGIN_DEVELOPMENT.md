# Writing a plugin

A **plugin** is a jar with a `plugin.toml` that goes into the server's `SingularityPlugins/` folder. It uses the
[Singularity API](SINGULARITY_API.md) (commands, events, scheduler, config, players, worlds) and needs neither Minecraft
nor Mixin to compile. If you need to change how Minecraft itself behaves, write a [mod](MOD_DEVELOPMENT.md) instead.

| | Plugin | Mod |
|---|---|---|
| Folder | `SingularityPlugins/` | `SingularityMods/` |
| Descriptor | `plugin.toml` | `singularity.mod.json` |
| Compiles against | `singularity-api-<v>-api.jar` + `singularity-loader.jar` | the loader, Minecraft, Mixin |
| Can patch Minecraft (Mixin) | no | yes |
| Uses the Singularity API | yes (`JavaPlugin`) | yes (`Singularity.forMod(id)`) |
| Lifecycle | load / enable / disable | entrypoints (`preLaunch`, `main`, `server`) |

[`example-plugin/`](../example-plugin) in this repository is a complete plugin. Copy it to start.

## 1. The server side

Plugins are loaded by the **Singularity API mod**. Put `singularity-api-4.1.0.jar` into `SingularityMods/` and the
plugin jars into `SingularityPlugins/`, then start the server. The same jars work on Minecraft 26.1 to 26.3
([VERSIONS.md](VERSIONS.md)). The log shows

```
[Singularity API] Loading 1 plugin:
[Singularity API]   - Example Plugin 1.0.0 (exampleplugin)
...
[Singularity API] Enabled Example Plugin 1.0.0 (exampleplugin)
```

`/plugins` (alias `/pl`) lists them: green = enabled, red = failed; `/plugins <id>` shows details and the error.

## 2. The project

```
my-plugin/
├── build.gradle.kts
└── src/main/
    ├── java/com/example/MyPlugin.java
    └── resources/
        ├── plugin.toml
        └── config.toml        (optional: default config)
```

`build.gradle.kts` outside this repository (the two jars come from a Singularity build or release: `singularity-api/build/libs/`
and `build/libs/`):

```kotlin
plugins { `java-library` }

dependencies {
    compileOnly(files("libs/singularity-api-4.1.0-api.jar", "libs/singularity-loader.jar"))
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
```

Inside this repository (like `example-plugin`):

```kotlin
dependencies {
    compileOnly(project(path = ":singularity-api", configuration = "pluginApi"))
    compileOnly(project(":")) { isTransitive = false }
}
```

`gradlew :example-plugin:jar` gives `example-plugin/build/libs/example-plugin-1.0.0.jar`.

## 3. `plugin.toml`

```toml
id = "exampleplugin"
name = "Example Plugin"
version = "1.0.0"
main = "net.singularitymc.exampleplugin.ExamplePlugin"
api = ">=4.0.0"
description = "Shows the plugin API."
authors = ["You"]
depends = []          # plugins that must be there; they are enabled first
softdepends = []      # enabled first if they are there
loadbefore = []       # these are enabled after this one

[commands.hello]
description = "Says hello"
usage = "/hello [name]"
aliases = ["hi"]
permission = "exampleplugin.hello"

[permissions."exampleplugin.hello"]
default = "true"      # true | false | op | not_op
description = "Use /hello"
```

| Key | Required | Notes |
|---|---|---|
| `id` | yes | 2-64 characters `a-z 0-9 - _`, starts with a letter. `minecraft`, `java`, `singularity`, `singularity-loader`, `singularity-api` are reserved. Also the name of the data folder and the command prefix (`/exampleplugin:hello`). |
| `version` | yes | SemVer-like (`1.0.0`, `2.1.0-beta.1`). |
| `main` | yes | The class that extends `JavaPlugin`. |
| `name` | no | Shown in logs and `/plugins`; default: the id. |
| `api` | no | Singularity API versions it works with ([version range syntax](MOD_DEVELOPMENT.md#version-ranges)); default any. |
| `description`, `authors` (or `author`), `website` | no | Informational. |
| `depends` / `softdepends` / `loadbefore` | no | Lists of plugin ids. A missing or failed `depends` stops this plugin from loading. Cycles are reported. |
| `[commands.<name>]` | no | `description`, `usage`, `aliases`, `permission`. The executor is set in code. |
| `[permissions."<node>"]` | no | `default` and `description`. **Quote nodes that contain dots**, or TOML reads them as nested tables (the loader tells you). |

Every problem in the file is reported at once in the log, and the server still starts without that plugin.

## 4. The main class and its lifecycle

```java
public final class ExamplePlugin extends JavaPlugin {
    @Override public void onLoad()    { /* server starting: config, listeners, commands. No worlds or players yet. */ }
    @Override public void onEnable()  { /* worlds loaded; runs on the server thread */ }
    @Override public void onDisable() { /* server stopping, in reverse load order */ }
}
```

1. **Load**: right after the mods' entrypoints, before Minecraft loads its worlds, in dependency order. `server()` is not
   available yet.
2. **Enable**: once the server has started (`Done (...)!`), in dependency order, on the server thread.
3. **Disable**: when the server stops, in reverse order. Afterwards the loader removes everything the plugin registered:
   listeners, tasks, commands (they answer that the plugin is disabled).

If the constructor, `onLoad` or `onEnable` throws, the plugin is marked **failed**, its registrations are undone,
plugins that `depend` on it are not enabled, and the server keeps running. There is no hot reload; restart the server
to update a plugin.

`JavaPlugin` gives you `logger()`, `events()`, `commands()`, `command(name)`, `scheduler()`, `config()`, `dataFolder()`
(`SingularityPlugins/<id>/`) and `server()`. Do not create plugin objects yourself: the loader does.

## 5. Commands

```java
// declared in plugin.toml: set the executor
command("hello").setExecutor((sender, label, args) -> {
    if (args.length > 1) return false;              // false: the usage from plugin.toml is shown
    sender.sendMessage("Hello, " + (args.length == 1 ? args[0] : sender.name()) + "!");
    return true;
});
command("hello").setCompleter((sender, label, args) ->
        args.length == 1 ? server().players().stream().map(Player::name).toList() : List.of());

// or entirely in code
commands().register(CommandSpec.of("whereami").description("Shows your position").permission("exampleplugin.whereami"),
        (sender, label, args) -> {
            Player p = sender.asPlayer().orElseThrow(() -> new CommandException("Only players have a position."));
            sender.sendMessage("You are at " + p.blockLocation());
            return true;
        });
```

- Commands are real Minecraft commands: they work in chat, the console, command blocks and `/execute ... run`, and
  clients get tab completion. `args` are the words after the name, split at spaces.
- Every command also exists as `/<plugin id>:<name>`. If the plain name is already a Minecraft or another plugin's
  command, only the prefixed form is registered and the log says so.
- Players who lack the command's permission do not see it. A `CommandException` shows its message in red; any other
  exception is logged and the sender gets "An internal error occurred".
- Commands registered while the server runs are sent to online players straight away.

## 6. Permissions

`sender.hasPermission("node")`: the console has every node. For players a permission provider decides first (a
permissions plugin calls `Singularity.permissions().setProvider(...)`), otherwise the node's default from
`plugin.toml` (`true`, `false`, `op`, `not_op`). Nodes that are declared nowhere are for operators only.
"Operator" means permission level 2 (game master) or higher.

## 7. Events

```java
events().listen(PlayerJoinEvent.class, e -> e.player().sendMessage("Welcome!"));
events().listen(BlockBreakEvent.class, EventPriority.HIGH, e -> {
    if (e.block().equals("minecraft:bedrock")) e.setCancelled(true);
});
```

| Event (`net.singularitymc.sapi.event...`) | When | Cancellable |
|---|---|---|
| `server.ServerStartedEvent` | the server has started (after plugins are enabled) | no |
| `server.ServerStoppingEvent` | the server is stopping (before plugins are disabled) | no |
| `server.ServerTickEvent` | start of every tick | no |
| `player.PlayerJoinEvent` / `player.PlayerQuitEvent` | a player joined / is leaving | no |
| `player.PlayerChatEvent` | a chat message is about to be sent to everyone | yes |
| `block.BlockBreakEvent` | a player is about to break a block | yes |
| `block.BlockPlaceEvent` | a block item is about to be placed | yes |
| `entity.EntityDamageEvent` | a living entity (players too) is about to be hurt | yes |

Priorities run `LOWEST` to `HIGHEST`, then `MONITOR` (observe only). Cancelled events skip later listeners unless they
were registered with `receiveCancelled = true`. You can post your own event classes with `events().post(...)`, which
is how plugins talk to each other.

## 8. Scheduler

```java
scheduler().runTask(() -> ...);                  // next tick, server thread
scheduler().runLater(20 * 5, () -> ...);         // in 5 seconds
Task t = scheduler().runTimer(0, 20, () -> ...); // every second; t.cancel() to stop
scheduler().runAsync(() -> {                     // background thread: no world or player access here
    String data = download();
    scheduler().callSync(() -> { server().broadcast(data); return null; });
});
```

All of a plugin's tasks are cancelled when it is disabled.

## 9. Config

If the jar has a `config.toml`, it is copied to `SingularityPlugins/<id>/config.toml` the first time `config()` is used
(comments included), and from then on the server owner edits that copy.

```java
String greeting = config().getString("greeting", "Hello");
long every = config().getLong("announce-every-ticks", 6000);
List<String> worlds = config().getStringList("worlds.enabled");
config().set("stats.starts", config().getInt("stats.starts", 0) + 1);
config().save();   // writes TOML; comments of the file are not kept
```

A file that cannot be read is reported in the log and never overwritten.

## 10. Players, worlds and 64-bit coordinates

```java
Player p = server().player("Steve").orElseThrow();
BlockLocation feet = p.blockLocation();               // long x, int y, long z: exact
World w = p.world();
if (w.getBlock(feet.below()).equals("minecraft:grass_block")) w.setBlock(feet.below(), "minecraft:gold_block");
p.teleport(new BlockLocation(35_000_000_000_000L, 120, 0).toLocation(w));
```

Singularity worlds can reach far past 2,147,483,647 (`experimental_long_positions`). Keep X and Z in `long`
(`BlockLocation`, `ChunkLocation`) and never cast them to `int`. `Location` uses doubles like Minecraft's entities;
far out a double has less precision after the point (1/128 block at 2^45), but the block it is in is exact.
See [SINGULARITY_API.md](SINGULARITY_API.md#64-bit-coordinates).

Touch worlds and players on the server thread only (commands, events and `runTask`/`runTimer` already run there).

## 11. Testing

- Start the server with an extra plugin jar or a folder that has a `plugin.toml`, without copying:
  `java -Dsingularity.addPlugins=path/to/build/libs/my-plugin.jar -jar singularity-loader.jar nogui`
  (several paths separated by `;` on Windows, `:` elsewhere).
- Type the command in the server console (`hello`, `exampleplugin:hello Alex`) or in game.

## 12. Troubleshooting

| Log message | What to do |
|---|---|
| `Plugin not loaded: x.jar: no plugin.toml (it is a mod: put it into SingularityMods)` | It is a mod, not a plugin. |
| `Plugin not loaded: x.jar: ... unknown key / 'version' is missing / ...` | Fix `plugin.toml`; every problem is listed. |
| `Plugin x not loaded: needs Singularity API >=5.0.0, this is 4.0.0` | Update the Singularity API mod, or use an older plugin version. |
| `needs plugin 'y', which is not installed` | Install `y` or remove it from `depends`. |
| `x.Main does not extend net.singularitymc.sapi.plugin.JavaPlugin` | The `main` class must extend `JavaPlugin`. |
| `/hello of x is already a Minecraft or mod command; use /x:hello` | Pick another name, or use the prefixed form. |
| `The Singularity API is not running` | `singularity-api-<version>.jar` is missing from `SingularityMods/`. |
