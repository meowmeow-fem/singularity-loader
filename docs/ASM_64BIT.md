# Singularity 3.5.1, test 1: true 64-bit X/Z with ASM

Question: Mixin patches chosen methods. Raw ASM can rewrite any bytecode as it loads. Could an ASM pass make X and Z
real 64-bit values throughout Minecraft (not the virtual windows of docs/FARLANDS.md)?

Two tools in `tools/asm-audit/`, both pure ASM (no Mixin), run against the 26.3 server jar.

## 1. How far X/Z spread: `CoordinateAudit.java`
An interprocedural dataflow analysis (ASM `Analyzer` with a custom `Interpreter`). X/Z start in `Vec3i.x/z`,
`ChunkPos.x/z` and `Entity.getX/getZ/getBlockX/getBlockZ`, and flow through locals, arithmetic, fields, parameters,
return values, overrides and lambda captures until nothing changes. Y is left out (world height stays 32-bit).
It over-approximates (a value counts if any input was a coordinate).

```
java -cp "asm-9.9.jar;asm-tree-9.9.jar;asm-analysis-9.9.jar" tools/asm-audit/CoordinateAudit.java libs/Mojang_Mapping.jar
```

Result (7,744 classes, 72,116 methods, 7 seconds):

| A 64-bit rewrite would have to change | Count |
|---|---|
| Methods / classes that carry X or Z | 6,448 / 1,846 |
| `int` fields holding X/Z | 603 |
| `int` parameters / return types | 2,995 / 1,274 |
| `int` instructions on X/Z: arithmetic / conversions / comparisons | 5,541 / 1,347 / 3,579 |
| X/Z as an array index / array size / switch value | 191 / 19 / 11 |
| X/Z captured by lambdas | 245 |
| Calls into the JDK / libraries with X/Z | 2,214 / 366 |

Top library calls: `Math.max` 456, `Math.min` 345, `Integer.valueOf` 335 (boxing: codecs, NBT, maps), `Math.abs` 230,
`List.get` 47 (indices), fastutil `LongSet.add` 26 / `Long2ObjectMap.get` 18 (packed keys, already long).

## 2. Can ASM do the rewrite: `WidenPoc.java`
A real widener: from seed fields it finds, to a fixpoint, every local, field, parameter and return that must be long,
then rewrites the bytecode: int opcodes to long (`IADD`->`LADD`, `IF_ICMPLT`->`LCMP`+`IFLT`, `ISHR`->`LSHR`), field
and method descriptors, local slots (a long takes two), `DUP`/`POP`/`SWAP` shapes, `java.lang.Math` int calls to
their long overloads, string concatenation, and `I2L` / `L2I` right after the producing instruction wherever an int
meets a long or a long meets an int-only place (array index, shift amount, other library calls).

Test: `tools/asm-audit/poc/Pos.java` (shaped like `Vec3i`) and `Walk.java` (uses it like game code), widened with
seeds `poc/Pos.x poc/Pos.z`, run with the bytecode verifier on:

| Line | Before (int) | After (ASM-widened) |
|---|---|---|
| far | Pos{-1794967296, 64, 1794967296} | Pos{2500000000, 64, -2500000000} |
| chunk | -112185456, 112185456 | 156250000, -156250000 |
| array column (index narrowed back) | 3 2 | 3 2 |
| past 2^31 | false | true |
| sum of X | -2384901888 | 10500000000 |

Rewritten: `long x, int y, long z`, `Pos(long, int, long)`, `long getX()`, `long chunkX()`, `long distManhattan(Pos)`
(`lsub`, `Math.abs(J)J`, `ladd`, `lreturn`); `offset(int, int, int)` keeps int deltas with `I2L` where they meet X.
185 instructions in 2 classes, fixpoint after 2 rounds.

```
javac -d out/orig tools/asm-audit/poc/*.java
java -cp out/orig poc.Walk
java -cp "asm-9.9.jar;asm-tree-9.9.jar;asm-analysis-9.9.jar;asm-util-9.9.jar" tools/asm-audit/WidenPoc.java out/orig out/wide poc/Pos.x poc/Pos.z
java -cp out/wide poc.Walk
```

## Verdict
**The technique works**: ASM can mechanically and verifiably turn int X/Z into long, including slots, stack shapes,
library overloads and narrowing at int-only boundaries. Applying it to Minecraft is a different scale and runs into
walls the bytecode cannot remove:

1. **Size**: 1,846 classes and ~4,300 signatures change together; every override, interface and lambda
   (`invokedynamic` descriptors, 245 captures) must change consistently. The PoC has no inheritance or lambdas yet.
2. **Libraries**: 2,580 calls cross into code that cannot be widened. `Math` has long overloads; `Integer.valueOf`
   (335) feeds `Codec.INT`, NBT `IntTag`, maps and network `VarInt`, which means new codecs and data formats.
3. **Saves and network**: region files (`r.x.z`), chunk NBT (`xPos`/`zPos`) and every packet with a position are
   32-bit formats. A 64-bit world needs a new save format and protocol, so **the client must run the same rewrite**.
   Fabric has no general class transformer; this needs Singularity to launch the client itself.
4. **Doubles**: entity positions are `double`. Past 2^53 (9 quadrillion) they cannot hold whole blocks; at 10^15
   movement already snaps to 1/8 block. Gameplay at the Fringelands' 9.2 quintillion would also need entity math
   rewritten (for example a long block position plus a double offset), which is a design change, not a type change.
5. **Correctness**: the analysis over-approximates, and every place where an index, hash, array size or switch
   meets X/Z needs a decision; 221 of them are listed by the audit.

So: possible in principle up to about 2^53 blocks, as a very large project (an ASM rewriter for the whole game with
inheritance and lambdas, new save format, new protocol, a Singularity-launched client). Beyond 2^53, not without
redesigning entity positions. The virtual 64-bit world (docs/FARLANDS.md) remains the practical way to see those
distances.

Next step if this continues ("test 2"): extend the widener with override families and lambdas, and point it at one
real subsystem (`Vec3i`/`BlockPos` + `ChunkPos` + `Level.getBlockState`) to see what breaks first.
