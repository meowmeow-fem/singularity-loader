# Writing a mod

A Singularity mod is a normal jar with a `singularity.mod.json` in its root. You can write
entrypoints (plain Java code the loader calls) and Mixins (patches to Minecraft's own classes).
Minecraft 26.x is **unobfuscated**, so you use the real names from the game: no mappings needed.

The `example-mod/` folder in this repository is a complete working mod. Copy it to start.

> **Commands, events, scheduler, config, players and worlds without Mixin:** depend on the Singularity API mod
> (`"singularity-api": ">=4.0.0"`) and use `Singularity.forMod("yourmod")`, see [SINGULARITY_API.md](SINGULARITY_API.md).
> If you do not need to patch Minecraft at all, a [plugin](PLUGIN_DEVELOPMENT.md) is simpler.

## 1. `singularity.mod.json`

```json
{
  "schemaVersion": 1,
  "id": "examplemod",
  "version": "1.0.0",
  "name": "Example Mod",
  "description": "What it does.",
  "authors": ["You"],
  "license": "Apache-2.0",
  "environment": "server",
  "entrypoints": {
    "main": ["com.example.ExampleMod"],
    "server": ["com.example.ExampleServer"]
  },
  "mixins": ["examplemod.mixins.json"],
  "depends": {
    "singularity-loader": ">=0.1.0",
    "minecraft": ">=26.1"
  }
}
```

`">=26.1"` accepts every Minecraft version Singularity supports. If your Mixins or code use something that only exists in
a newer version, say so (`">=26.3"`), or check your jar against each version with the tools in
[VERSIONS.md](VERSIONS.md).

A JSON schema is in [`singularity.mod.schema.json`](singularity.mod.schema.json) (point your editor at it).

| Field | Required | Notes |
|---|---|---|
| `id` | yes | 2-64 chars: `a-z`, `0-9`, `-`, `_`; must start with a letter. `singularity-loader`, `minecraft` and `java` are reserved. |
| `version` | yes | `1.2.3`, `26.3`, `2.0.0-beta.1`, ... (SemVer precedence). |
| `environment` | no | `"*"` (default), `"server"` or `"client"`. Mods for another environment are skipped. |
| `entrypoints` | no | key to class name(s). See below. |
| `mixins` | no | Mixin config names, or `{"config": "...", "environment": "server"}`. |
| `depends` / `recommends` / `suggests` / `conflicts` / `breaks` | no | mod id to version range. |
| `name`, `description`, `authors`, `contributors`, `license`, `contact`, `icon`, `custom` | no | Informational; `custom` is free-form data other mods can read. |

### Version ranges

| Syntax | Meaning |
|---|---|
| `*` | any version |
| `>=1.2.3` `>1.2.3` `<=1.2.3` `<1.2.3` | comparison |
| `1.2.3` or `=1.2.3` | exactly that version |
| `^1.2.3` | `>=1.2.3 <2.0.0` (`^0.2.3` is `>=0.2.3 <0.3.0`) |
| `~1.2.3` | `>=1.2.3 <1.3.0` |
| `1.x`, `1.2.*` | wildcard |
| `>=1.2 <2.0` | both must hold |
| `^1.0 \|\| ^2.0` | either may hold (or use a JSON array: `["^1.0", "^2.0"]`) |

### Relationships

| Key | If the other mod is missing / does not match | If it is present and matches |
|---|---|---|
| `depends` | **the server does not start** | fine |
| `breaks` | fine | **the server does not start** |
| `recommends` | warning | fine |
| `conflicts` | fine | warning |
| `suggests` | nothing | nothing |

Dependencies load first. All problems are reported together, not one per start.
`singularity-loader`, `minecraft` and `java` can be used as dependency ids.

## 2. Entrypoints

| Key | Interface | When |
|---|---|---|
| `preLaunch` | `PreLaunchEntrypoint` | After Mixin is ready, **before any Minecraft class is loaded**. Do not touch `net.minecraft` here. |
| `main` | `ModInitializer` | Just before Minecraft's `main`, in dependency order. Registries are not bootstrapped yet. |
| `server` | `ServerModInitializer` | After all `main` entrypoints, on a dedicated server only. |

An entrypoint class needs a public constructor without parameters. One class may implement several
interfaces and be listed under several keys; it is created once.

You can define your own keys for mod-to-mod APIs:

```java
// in the API mod
public interface MyPlugin { void register(); }

// in any mod:  "entrypoints": { "myapi": ["com.other.Plugin"] }
for (MyPlugin p : SingularityLoader.getInstance().getEntrypoints("myapi", MyPlugin.class)) p.register();
```

If an entrypoint throws, the server stops with a message naming the mod and the class.

## 3. Mixins

[Mixin](https://github.com/FabricMC/Mixin) rewrites Minecraft's classes as they load. MixinExtras
(`@WrapOperation`, `@ModifyExpressionValue`, `@Local`, ...) is available too.

`examplemod.mixins.json`:

```json
{
  "required": true,
  "minVersion": "0.8",
  "package": "com.example.mixin",
  "compatibilityLevel": "JAVA_21",
  "mixins": ["DedicatedServerMixin"],
  "injectors": { "defaultRequire": 1 }
}
```

```java
@Mixin(targets = "net.minecraft.server.dedicated.DedicatedServer")
abstract class DedicatedServerMixin {
    @Inject(method = "initServer", at = @At("HEAD"))
    private void onInit(CallbackInfoReturnable<Boolean> cir) { ... }
}
```

Tips:

- **No refmap, no annotation processor.** The game is unobfuscated, so Mixin finds methods by their real names. Do not run Mixin's annotation processor.
- You can compile against Minecraft (`compileOnly` the server jar) and write `@Mixin(DedicatedServer.class)`, or address the target by name with `targets = "..."` and avoid the compile dependency.
- Keep `defaultRequire` at `1` so a Minecraft update that breaks your injection fails loudly instead of silently doing nothing.
- Mixin classes can never be referenced from normal code; call helper classes from the injected method instead.
- Use `compatibilityLevel` `JAVA_21` or `JAVA_25` to match the bytecode you compile (`--singularity-debug` prints a note if Mixin reports a lower maximum, which is harmless).
- `--singularity-dump` writes every class Mixin changed to `.singularity/dump/` so you can open it in a decompiler.

## 4. Building a mod with Gradle

> The easiest start is the **Singularity Example Mod** template project: its build script downloads Minecraft on the first
> Gradle sync and puts it on the compile class path automatically, so none of the manual steps below are needed.

Singularity Loader is not on a Maven repository yet, and a mod that imports `net.minecraft.*` needs the
real game on its compile class path. Both are set up once:

1. **Get the game's folder.** In your server folder run
   ```sh
   java -jar singularity-loader.jar --singularity-setup
   ```
   It downloads and unpacks Minecraft and prints `minecraftDir=...`, a folder containing `versions/` and
   `libraries/`. (Any unpacked server folder with those two folders works too.)
2. **Point your project at it** (`gradle.properties`): `minecraftDir=<that folder>`
3. **Use it in `build.gradle.kts`:**

```kotlin
val minecraftDir = providers.gradleProperty("minecraftDir").getOrElse("")

dependencies {
    compileOnly(files("libs/singularity-loader-0.1.0.jar"))               // the loader API
    compileOnly("net.fabricmc:sponge-mixin:0.17.2+mixin.0.8.7")           // repository: https://maven.fabricmc.net/
    compileOnly(fileTree(minecraftDir) { include("versions/**/*.jar", "libraries/**/*.jar") })
}
tasks.withType<JavaCompile> { options.compilerArgs.add("-proc:none") }
```

Now `import net.minecraft.server.dedicated.DedicatedServer;` and `@Mixin(DedicatedServer.class)` work, and your
IDE can resolve Mixin targets. The `hello-server` mod shows a complete project. If you prefer not to depend on
Minecraft at compile time you can name targets as text (`@Mixin(targets = "net.minecraft...")`), as `example-mod` does.

Put `singularity.mod.json` and your `*.mixins.json` into `src/main/resources`.

## 5. Testing during development

Start the loader with your mod without copying jars around:

```sh
java -Dsingularity.addMods=path/to/build/libs/mymod.jar -jar singularity-loader.jar nogui
# same thing as a command line option (repeatable):
java -jar singularity-loader.jar --singularity-add-mod=path/to/build/libs/mymod.jar nogui
```

Several paths can be given to `-Dsingularity.addMods`, separated by `:` (Linux/macOS) or `;` (Windows).
A directory that contains both your compiled classes and your resources (with `singularity.mod.json`
at its root) also works.

From this repository, `gradle runServer -PwithExample` starts the loader from source in `./run` with
the example mod.
