# Minecraft versions

Singularity supports every Minecraft release that Mojang ships **unobfuscated**, with its own class and method names:

| Version | Released | Status |
|---|---|---|
| 26.1 | 2026-03-24 | supported (4.1) |
| 26.1.1 | 2026-04-01 | supported (4.1) |
| 26.1.2 | 2026-04-09 | supported (3.5, checked again in 4.1) |
| 26.2 | 2026-06-16 | supported (3.5, checked again in 4.1) |
| 26.3 | 2026-09-15 | supported, the default and the version everything is developed on |

Older releases (1.21.11 and before) are obfuscated and need a mapping layer, which Singularity does not have; the
loader warns and starts anyway, but nothing is promised there. Newer versions get a warning ("newer than the newest
version Singularity was tested with") until they are added here.

One loader jar, one Singularity API mod jar and the same plugins run on all of them. Pick the version with
`--singularity-game-version=<id>` and use one server folder per version (`GameLibraries` / `GameVersions` are shared
inside a folder).

## What works where

| Feature | 26.1 - 26.1.2 | 26.2 | 26.3 |
|---|---|---|---|
| Mods, Mixin, `ServerConfig.toml`, `ServerEula.txt` | yes | yes | yes |
| Singularity API mod, plugins, `/plugins` | yes | yes | yes |
| Bigger world (`max_coordinate` up to 2^30, wide packing past 33,554,432) | yes | yes | yes |
| Classic Far Lands (`farlands_style = "classic"`) | yes | yes | yes |
| Mojang's Far Lands switch (`farlands_style = "mojang"`) | no (skipped with a warning) | no | yes |
| 64-bit world (`experimental_long_positions`) | yes | yes | yes |
| Singularity Wide Client (Fabric) | yes | yes | yes |

## What differs between the versions, and how the loader handles it

The loader is compiled against 26.3. Everything that is not the same in the older versions goes through one of three
mechanisms, so the same jar links against all of them:

- **Optional hooks for both shapes.** Where a method moved or changed its signature, the patch has one hook per shape,
  each `require = 0`, and the game uses the one it has:
  - classic Far Lands: 26.3 fills a chunk in `NoiseBasedChunkGenerator.buildTerrain` (`doFill(NoiseChunk, ChunkAccess)`),
    26.1 - 26.2 in `fillFromNoise` (`doFill(Blender, StructureManager, RandomState, ChunkAccess, int, int)`)
    (`ClassicFarLandsMixin`);
  - the chunk limit of the wide packing: `MAX_CHUNK_COORDINATE_VALUE` is in `ChunkPyramid` from 26.2 on and in
    `ChunkPos` in 26.1 - 26.1.2 (`ChunkPyramidWideMixin`, `ChunkPosWideMixin`);
  - the world seed: `RandomState.seed()` exists from 26.3 on; in 26.1 - 26.2 `RandomStateSeedMixin` keeps it from
    the constructor (`net.singularitymc.compat.WorldSeeds`).
- **Calls bound at startup** (`net.singularitymc.compat.GameShims`): `AbortableIterationConsumer.accept` returns
  `net.minecraft.util.Continuation` in 26.3 and `AbortableIterationConsumer.Continuation` before, so the bytecode of
  a direct call cannot link against both; `GameShims.acceptAborts` binds it once with a method handle.
- **Guarded code** for types that only exist in 26.3: the floating origin shifts `PositionStep` and
  `PositionAndRotation` only if the game has them (26.1 - 26.2 have neither, nor the packets that carry them).
- **The 64-bit widener** (`PositionWidening`) is written against 26.3's classes; a listed class the running game does
  not have is left out (`EmptyStructureManager`, 26.3+). Everything else in its scope exists in all versions and
  widens cleanly (offline: 26.1 2,066 classes, 26.2 2,096, 26.3 2,162; every method passes the verifier).

The API mod needs none of this: everything it calls exists unchanged in 26.1 to 26.3.

## Checking a jar against a version

Three offline tools in `tools/asm-audit` (nothing is run; ASM 9.9 on the class path):

```sh
# Mixin targets: every @Mixin class, injector method, @At target, @Shadow / @Accessor member
java -cp asm-9.9.jar;asm-tree-9.9.jar tools/asm-audit/MixinTargetCheck.java <mixin classes dir> <server.jar>
# Linkage: every Minecraft class, method and field the code references (calls, fields, method references, casts)
java -cp asm-9.9.jar;asm-tree-9.9.jar tools/asm-audit/LinkCheck.java <classes dir or jar> <server.jar> [class prefix to skip ...]
# 64-bit widener over the loader's scope, with the verifier
java -cp build/classes/java/main;lib/* tools/asm-audit/ScopedDryRun.java <server.jar> <GameLibraries dir> loader
```

`<server.jar>` is the unpacked game jar, `GameVersions/<id>/server-<id>.jar` of a server folder
(`java -jar singularity-loader.jar --singularity-setup --singularity-game-version=<id>` makes one). For the loader,
`LinkCheck` is run with `net/singularitymc/network/FloatingOrigin$ModernPositions net/singularitymc/network/FloatingOrigin$Moved`
as skips (the guarded 26.3 code). `MixinTargetCheck` reports the hooks of the other versions as "optional"; that is
expected. It also flags four `PathNodeLongMixin` `@ModifyArg`s on every version, 26.3 included: the mixin targets two
classes and the tool checks each injector against both.

A mod built against 26.3 can use the same tools to see whether it also runs on 26.1 and 26.2, and declare
`"minecraft": ">=26.1"` if it does.

## Tested (4.1)

Fresh worlds, seed 1234, one server folder per version (`run/compat-<version>`), console commands:

- Normal mode on 26.1, 26.1.1, 26.1.2 and 26.2: the API mod and the example plugin load, enable and disable; `/plugins`,
  `/plugins exampleplugin`, `/hello`, `/hello Steve`, `/hi`, `/hello a b` (usage), `/exampleplugin:hello Alex` and
  `/whereami` (refused for the console) answer.
- Bigger world and 64-bit mode: see the 4.1.0 entry in the CHANGELOG for the exact checks.
- Not tested: playing with a real client on 26.1 - 26.2 (joining, chat, block events, the floating origin, the Wide
  Client in game).
