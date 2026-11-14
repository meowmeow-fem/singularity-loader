package net.singularitymc.loader.asm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.RecordComponentNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.Interpreter;
import org.objectweb.asm.tree.analysis.Value;

/**
 * Turns int coordinates into long ones in a set of classes, with plain ASM (3.5.1 of docs/ROADMAP_64BIT.md).
 *
 * <p>Given seed fields (for example {@code Vec3i.x}), it finds to a fixpoint every field, local slot, parameter and
 * return value an X/Z value reaches, then rewrites the bytecode: int opcodes to long ones, descriptors, local slots
 * (a long takes two), stack shuffles, {@code java.lang.Math} int calls to their long overloads, string concatenation,
 * lambdas and method handles. Wherever an int meets a long it inserts {@code I2L} right after the instruction that
 * produced the int; wherever a long meets a place that must stay int (array index or size, shift amount, switch, a
 * JDK/library method, a method that overrides one), it inserts {@code L2I}.
 *
 * <ul>
 *   <li>Overrides share a family; a family whose method overrides a JDK/library method (hashCode, compareTo,
 *       IntSupplier.getAsInt...) keeps its descriptor.</li>
 *   <li>Lambda bodies ({@code lambda$...}): captured coordinates widen; the functional interface's own parameters and
 *       int return stay int.</li>
 *   <li>Method references to a widened method go through a generated bridge with the original descriptor.</li>
 *   <li>Records: record components and {@code ObjectMethods} field handles follow their fields.</li>
 * </ul>
 * It over-approximates on purpose (anything that ever holds a coordinate becomes long); a long holds every int value.
 */
public final class CoordinateWidener {
    /** Bytes of a class outside the widened set (JDK, libraries), or {@code null} if unknown. */
    @FunctionalInterface
    public interface OutsideClasses {
        byte[] bytes(String internalName);
    }

    /** What happened, for logs and tests. */
    public record Report(int rounds, Set<String> longFields, int longParameters, int longReturns, int rewrittenInstructions,
                         int bridges, List<String> problems) {}

    public record Result(Map<String, byte[]> classes, Report report) {}

    private static final int I = 1;
    private static final int L = 2;
    /** A pre-conversion to long of an int that comes from outside the set (see {@code carry}). */
    private static final int CARRY_L = 3;
    /** The same for a chunk coordinate (the int goes to a chunk-scaled method, see {@code chunkScaled}). */
    private static final int CHUNK_L = 4;
    /**
     * A conversion to int for consumers that never take a coordinate (3.5.8): array indices and sizes, shift amounts,
     * switches, JDK and library methods, the int result of {@code hashCode} / {@code compareTo}. Narrowed there without
     * the carry remembering it: {@code ChunkPos.hashCode} narrowed its long hash through the carry, which remembered the hash
     * (about 10^15) as a coordinate, and ints next to it were then carried there (chunks generated 5 * 10^11 blocks
     * away during a test at x = 2^31, the server stalled).
     */
    private static final int I_PLAIN = 5;
    private static final Set<String> MATH_LONG = Set.of("abs", "max", "min", "floorDiv", "floorMod", "addExact",
        "subtractExact", "multiplyExact", "negateExact", "absExact", "clamp");
    private static final String LAMBDA_METAFACTORY = "java/lang/invoke/LambdaMetafactory";
    private static final String CONCAT_FACTORY = "java/lang/invoke/StringConcatFactory";

    private final Map<String, ClassNode> classes = new LinkedHashMap<>();
    private final Map<String, MethodNode> methods = new HashMap<>();
    private final Map<String, String> methodOwner = new HashMap<>();
    private final OutsideClasses outside;
    private final Map<String, OutsideInfo> outsideInfo = new HashMap<>();

    private final Map<String, Integer> family = new HashMap<>();
    private final List<List<String>> familyMembers = new ArrayList<>();
    private final Set<Integer> pinnedFamilies = new HashSet<>();
    private final Map<String, BitSet> pinnedParams = new HashMap<>();
    private final Set<String> pinnedReturns = new HashSet<>();
    private final Set<String> lambdaBodies = new HashSet<>();
    /** The index of a lambda body's first functional-interface parameter (the ones before are captured). */
    private final Map<String, Integer> lambdaSamStart = new HashMap<>();

    private final Set<String> longFields = new HashSet<>();
    private final Map<Integer, BitSet> longParams = new HashMap<>();
    private final Set<Integer> longReturns = new HashSet<>();
    private final Map<String, Set<Integer>> longSlots = new HashMap<>();
    private final Map<String, String> bridges = new LinkedHashMap<>();
    private final List<String> problems = new ArrayList<>();
    private final Map<String, String[]> extraTwins;
    private final boolean keepIntSignatures;
    /** Class with static {@code carryIn(I)J} / {@code carryOut(J)I} used at the int/long boundary, or null for I2L / L2I. */
    private final String carry;
    private final Set<String> chunkScaled;
    private final Set<String> quartScaled;
    private int intBridges;
    private boolean changed;

    public CoordinateWidener(Map<String, byte[]> input, OutsideClasses outside) {
        this(input, outside, Map.of(), false);
    }

    /**
     * @param extraTwins        more methods outside the set with a long twin, as {@code "owner.name(desc)"} to
     *                          {owner, name, desc} (like the java.lang.Math overloads)
     * @param keepIntSignatures scoped mode (3.5.2): every non-private method whose descriptor changes keeps a copy
     *                          with its original descriptor that converts and delegates, so code outside the set keeps
     *                          working unchanged
     */
    public CoordinateWidener(Map<String, byte[]> input, OutsideClasses outside, Map<String, String[]> extraTwins, boolean keepIntSignatures) {
        this(input, outside, extraTwins, keepIntSignatures, null);
    }

    /**
     * @param carry internal name of a class with {@code static long carryIn(int)} and {@code static int carryOut(long)}
     *              (3.5.4): used instead of I2L / L2I where a value crosses between the set and the code outside it
     *              (int signatures, calls and fields outside the set), so a 64-bit coordinate that int code only saw as
     *              its low 32 bits can be restored when it comes back
     */
    public CoordinateWidener(Map<String, byte[]> input, OutsideClasses outside, Map<String, String[]> extraTwins, boolean keepIntSignatures, String carry) {
        this(input, outside, extraTwins, keepIntSignatures, carry, Set.of());
    }

    /**
     * @param chunkScaled int signatures whose coordinates are chunk (or section) coordinates, as
     *                    {@code "owner.name(int desc)"}, {@code "*.name"} for every method of that name, or
     *                    {@code "owner.*"} for every method of a class ({@code "args:owner.name(int desc)"}: only the
     *                    arguments, the result is a block coordinate): their
     *                    bridges use {@code carryInChunk(I)J} / {@code carryOutChunk(J)I} of the carry class (3.5.7)
     */
    public CoordinateWidener(Map<String, byte[]> input, OutsideClasses outside, Map<String, String[]> extraTwins, boolean keepIntSignatures,
                             String carry, Set<String> chunkScaled) {
        this(input, outside, extraTwins, keepIntSignatures, carry, chunkScaled, null);
    }

    /**
     * @param quartScaled the same as {@code chunkScaled} for quart (biome) coordinates ({@code carryInQuart(I)J} /
     *                    {@code carryOutQuart(J)I}). Given (3.5.8), every other int signature takes block coordinates
     *                    ({@code carryInBlock(I)J}); null keeps {@code carryIn(I)J}, which tries every scale.
     */
    public CoordinateWidener(Map<String, byte[]> input, OutsideClasses outside, Map<String, String[]> extraTwins, boolean keepIntSignatures,
                             String carry, Set<String> chunkScaled, Set<String> quartScaled) {
        this.outside = outside;
        this.extraTwins = new HashMap<>(extraTwins);
        this.keepIntSignatures = keepIntSignatures;
        this.carry = carry;
        this.chunkScaled = chunkScaled;
        this.quartScaled = quartScaled;
        for (byte[] b : input.values()) {
            ClassNode cn = new ClassNode();
            new ClassReader(b).accept(cn, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
            classes.put(cn.name, cn);
            for (MethodNode m : cn.methods) {
                methods.put(key(cn.name, m.name, m.desc), m);
                methodOwner.put(key(cn.name, m.name, m.desc), cn.name);
            }
        }
        // 3.5.8: a static method with a long overload of the same shape (Mth.clamp(III)I and clamp(JJJ)J) is its own
        // twin: calls that need longs go to the overload instead of widening the int one into a clash
        for (ClassNode cn : classes.values()) {
            for (MethodNode m : cn.methods) {
                if ((m.access & Opcodes.ACC_STATIC) == 0 || m.desc.indexOf('I') < 0) continue;
                String longDesc = allLong(m.desc);
                String k = cn.name + "." + m.name + m.desc;
                if (methods.containsKey(key(cn.name, m.name, longDesc)) && !this.extraTwins.containsKey(k)) {
                    this.extraTwins.put(k, new String[] {cn.name, m.name, longDesc});
                }
            }
        }
        buildFamilies();
        findLambdaBodies();
    }

    /**
     * Methods ({@code "owner.name(desc)"}, with their override families) and fields ({@code "owner.name"}) that never
     * take, return or hold coordinates, so
     * they keep their int signatures whatever flows into them (3.5.8: the analysis over-approximates, and a tick delay
     * that some goal computes from a distance would otherwise become a coordinate). Call before {@link #widen}.
     */
    public CoordinateWidener notCoordinates(Collection<String> methodKeys) {
        for (String k : methodKeys) {
            if (methods.containsKey(k)) {
                pinnedFamilies.add(fam(k));
            } else if (k.indexOf('(') < 0) {
                pinnedFields.add(resolveField(k.substring(0, k.lastIndexOf('.')), k.substring(k.lastIndexOf('.') + 1))); // a field
            }
        }
        return this;
    }

    /** Widens everything the seed fields ({@code owner/Name.field}) reach and returns all classes, changed or not. */
    public Result widen(Collection<String> seedFields) {
        for (String f : seedFields) {
            longFields.add(resolveField(f.substring(0, f.lastIndexOf('.')), f.substring(f.lastIndexOf('.') + 1)));
        }
        int rounds = 0;
        do {
            changed = false;
            for (String mk : methods.keySet()) {
                try {
                    analyze(mk);
                } catch (AnalyzerException | RuntimeException e) {
                    problems.add("analysis of " + mk + ": " + e);
                }
            }
            rounds++;
        } while (changed && rounds < 100);
        int rewritten = 0;
        for (Map.Entry<String, MethodNode> e : new ArrayList<>(methods.entrySet())) {
            try {
                rewritten += rewrite(e.getKey(), e.getValue());
            } catch (AnalyzerException | RuntimeException ex) {
                problems.add("rewrite of " + e.getKey() + ": " + ex.getMessage());
            }
        }
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (ClassNode cn : classes.values()) {
            for (FieldNode f : cn.fields) {
                if (f.desc.equals("I") && longFields.contains(cn.name + "." + f.name)) f.desc = "J";
            }
            if (cn.recordComponents != null) {
                for (RecordComponentNode rc : cn.recordComponents) {
                    if (rc.descriptor.equals("I") && longFields.contains(cn.name + "." + rc.name)) rc.descriptor = "J";
                }
            }
            List<MethodNode> intSignatures = new ArrayList<>();
            Set<String> existing = new HashSet<>();
            for (MethodNode m : cn.methods) existing.add(m.name + m.desc);
            for (MethodNode m : cn.methods) {
                if (m.name.startsWith("singularity$ref$")) continue;
                String old = m.desc;
                String nd = newDesc(key(cn.name, m.name, old), old);
                if (nd.equals(old)) continue;
                if (existing.contains(m.name + nd)) {
                    problems.add("widening " + cn.name + "." + m.name + old + " clashes with an existing " + m.name + nd);
                    continue;
                }
                m.desc = nd;
                if (keepIntSignatures && (m.access & Opcodes.ACC_PRIVATE) == 0) {
                    if ((m.access & Opcodes.ACC_ABSTRACT) != 0) {
                        intSignatures.add(abstractFallback(cn, m, old));
                    } else {
                        intSignatures.add(intSignature(cn, m, old));
                    }
                }
            }
            cn.methods.addAll(intSignatures);
            intBridges += intSignatures.size();
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
                @Override
                protected String getCommonSuperClass(String a, String b) {
                    return commonSuper(a, b);
                }
            };
            try {
                cn.accept(cw);
                out.put(cn.name, cw.toByteArray());
            } catch (RuntimeException ex) {
                StackTraceElement[] st = ex.getStackTrace();
                problems.add("writing " + cn.name + (failingMethod(cn).map(m -> "." + m).orElse("")) + ": " + ex
                    + (st.length > 0 ? " at " + st[0] : "") + (st.length > 1 ? " < " + st[1] : ""));
            }
        }
        int params = longParams.values().stream().mapToInt(BitSet::cardinality).sum();
        return new Result(out, new Report(rounds, Set.copyOf(longFields), params, longReturns.size(), rewritten,
            bridges.size() + intBridges, List.copyOf(problems)));
    }

    /** A descriptor with every int (parameter and return) made long. */
    private static String allLong(String desc) {
        Type[] args = Type.getArgumentTypes(desc);
        for (int i = 0; i < args.length; i++) if (args[i] == Type.INT_TYPE) args[i] = Type.LONG_TYPE;
        Type ret = Type.getReturnType(desc);
        return Type.getMethodDescriptor(ret == Type.INT_TYPE ? Type.LONG_TYPE : ret, args);
    }

    /** For a problem report: the first method of a class that cannot be written on its own. */
    private Optional<String> failingMethod(ClassNode cn) {
        for (MethodNode m : cn.methods) {
            ClassNode one = new ClassNode();
            one.version = cn.version;
            one.access = cn.access;
            one.name = cn.name;
            one.superName = cn.superName;
            one.interfaces = cn.interfaces;
            one.fields = cn.fields;
            one.methods = List.of(m);
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
                @Override
                protected String getCommonSuperClass(String a, String b) {
                    return commonSuper(a, b);
                }
            };
            try {
                one.accept(cw);
            } catch (RuntimeException ex) {
                return Optional.of(m.name + m.desc);
            }
        }
        return Optional.empty();
    }

    /**
     * The original int signature of a widened method: converts int arguments to long where the widened method takes
     * a long, calls it (virtually, so overrides of the long version are honoured), and narrows a widened return.
     */
    private MethodNode intSignature(ClassNode cn, MethodNode widened, String oldDesc) {
        int access = widened.access & ~(Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_SYNCHRONIZED);
        String[] exceptions = widened.exceptions == null ? null : widened.exceptions.toArray(new String[0]);
        MethodNode b = new MethodNode(access, widened.name, oldDesc, null, exceptions);
        boolean isStatic = (widened.access & Opcodes.ACC_STATIC) != 0;
        boolean ctor = widened.name.equals("<init>");
        boolean itf = (cn.access & Opcodes.ACC_INTERFACE) != 0;
        InsnList c = b.instructions;
        String scale = bridgeScale(cn.name, widened.name, oldDesc);
        int slot = 0;
        if (!isStatic) c.add(new VarInsnNode(Opcodes.ALOAD, slot++));
        Type[] orig = Type.getArgumentTypes(oldDesc);
        Type[] wide = Type.getArgumentTypes(widened.desc);
        for (int k = 0; k < orig.length; k++) {
            c.add(new VarInsnNode(orig[k].getOpcode(Opcodes.ILOAD), slot));
            if (orig[k] == Type.INT_TYPE && wide[k] == Type.LONG_TYPE) {
                c.add(carry != null ? new MethodInsnNode(Opcodes.INVOKESTATIC, carry, "carryIn" + scale, "(I)J", false) : new InsnNode(Opcodes.I2L));
            }
            slot += orig[k].getSize();
        }
        int invoke = isStatic ? Opcodes.INVOKESTATIC : ctor || (widened.access & Opcodes.ACC_PRIVATE) != 0 ? Opcodes.INVOKESPECIAL
            : itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
        c.add(new MethodInsnNode(invoke, cn.name, widened.name, widened.desc, itf));
        Type ret = Type.getReturnType(oldDesc);
        if (ret == Type.INT_TYPE && Type.getReturnType(widened.desc) == Type.LONG_TYPE) {
            // a block coordinate going out is carried as before; "args:" entries return block coordinates
            String out = scale.equals("Block") || scale.equals("Chunk") && !chunkScaled(cn.name, widened.name, oldDesc) ? "" : scale;
            // a conversion that returns another scale ("ret:<scale>:" entries, 3.5.8)
            for (String s : new String[] {"Block", "Chunk", "Quart"}) {
                if (chunkScaled.contains("ret:" + s + ":" + cn.name + "." + widened.name + oldDesc)) out = s.equals("Block") ? "" : s;
            }
            c.add(carry != null ? new MethodInsnNode(Opcodes.INVOKESTATIC, carry, "carryOut" + out, "(J)I", false) : new InsnNode(Opcodes.L2I));
        }
        c.add(new InsnNode(ret.getOpcode(Opcodes.IRETURN)));
        b.maxLocals = slot;
        b.maxStack = slot + 2;
        return b;
    }

    /**
     * A widened abstract method (scoped mode): the int signature stays abstract, and the long one gets a body that
     * calls it, so an implementor outside the set (which only has the int method) still works when widened code calls
     * the long one. Implementors inside the set override the long method and get an int signature of their own.
     */
    private MethodNode abstractFallback(ClassNode cn, MethodNode widened, String oldDesc) {
        String[] exceptions = widened.exceptions == null ? null : widened.exceptions.toArray(new String[0]);
        MethodNode intAbstract = new MethodNode(widened.access, widened.name, oldDesc, null, exceptions);
        boolean itf = (cn.access & Opcodes.ACC_INTERFACE) != 0;
        widened.access &= ~Opcodes.ACC_ABSTRACT;
        InsnList c = new InsnList();
        boolean chunk = chunkScaled(cn.name, widened.name, oldDesc);
        Type[] orig = Type.getArgumentTypes(oldDesc);
        Type[] wide = Type.getArgumentTypes(widened.desc);
        int locals = 1;
        for (Type t : wide) locals += t.getSize();
        // the receiver is usually a lambda of the set: the int arguments go over in a transit frame (WidePacking),
        // filled here in order: frame[position] = argument, then frame[TRANSIT_SLOTS] = count
        int frame = locals;
        if (carry != null) {
            c.add(new MethodInsnNode(Opcodes.INVOKESTATIC, carry, "transitFrameForCall", "()[J", false));
            c.add(new VarInsnNode(Opcodes.ASTORE, frame));
            int position = 0;
            int s = 1;
            for (int k = 0; k < wide.length; k++) {
                if (orig[k] == Type.INT_TYPE && position < TRANSIT_SLOTS) {
                    c.add(new VarInsnNode(Opcodes.ALOAD, frame));
                    c.add(new LdcInsnNode(position++));
                    c.add(new VarInsnNode(wide[k].getOpcode(Opcodes.ILOAD), s));
                    if (wide[k] == Type.INT_TYPE) c.add(new InsnNode(Opcodes.I2L));
                    c.add(new InsnNode(Opcodes.LASTORE));
                }
                s += wide[k].getSize();
            }
            c.add(new VarInsnNode(Opcodes.ALOAD, frame));
            c.add(new LdcInsnNode(TRANSIT_SLOTS));
            c.add(new LdcInsnNode((long) position));
            c.add(new InsnNode(Opcodes.LASTORE));
            locals++;
        }
        c.add(new VarInsnNode(Opcodes.ALOAD, 0));
        int slot = 1;
        // an implementor outside the set is int code: it gets the coordinates narrowed the way int code sees them
        // (3.5.8; not remembered: the frame carries them back). A plain cast gave far coordinates the low 32 bits,
        // which int code elsewhere no longer sees (carving at 2^47 indexed an aquifer 2^32 blocks off).
        String out = carry == null ? null : switch (bridgeScale(cn.name, widened.name, oldDesc)) {
            case "Chunk" -> "narrowChunk";
            case "Quart" -> "narrowQuart";
            default -> "narrow";
        };
        for (int k = 0; k < wide.length; k++) {
            c.add(new VarInsnNode(wide[k].getOpcode(Opcodes.ILOAD), slot));
            if (orig[k] == Type.INT_TYPE && wide[k] == Type.LONG_TYPE) {
                c.add(out != null ? new MethodInsnNode(Opcodes.INVOKESTATIC, carry, out, "(J)I", false) : new InsnNode(Opcodes.L2I));
            }
            slot += wide[k].getSize();
        }
        c.add(new MethodInsnNode(itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL, cn.name, widened.name, oldDesc, itf));
        Type ret = Type.getReturnType(widened.desc);
        if (ret == Type.LONG_TYPE && Type.getReturnType(oldDesc) == Type.INT_TYPE) {
            c.add(carry != null && !chunk ? new MethodInsnNode(Opcodes.INVOKESTATIC, carry, "carryInReturn", "(I)J", false) : toLong(true, chunk));
        }
        if (carry != null) {
            // the frame was for this call only (an implementor outside the set never takes it)
            c.add(new VarInsnNode(Opcodes.ALOAD, frame));
            c.add(new MethodInsnNode(Opcodes.INVOKESTATIC, carry, "transitEnd", "([J)V", false));
        }
        c.add(new InsnNode(ret.getOpcode(Opcodes.IRETURN)));
        widened.instructions = c;
        widened.maxLocals = locals;
        widened.maxStack = slot + 4;
        return intAbstract;
    }

    /** int to long; {@code fromOutside}: the int comes from code outside the set and may be a narrowed coordinate. */
    private AbstractInsnNode toLong(boolean fromOutside) {
        return toLong(fromOutside, false);
    }

    /** {@code chunk}: the int is a chunk or section coordinate (see {@code chunkScaled}). */
    private AbstractInsnNode toLong(boolean fromOutside, boolean chunk) {
        return fromOutside && carry != null ? new MethodInsnNode(Opcodes.INVOKESTATIC, carry, chunk ? "carryInChunk" : "carryIn", "(I)J", false)
            : new InsnNode(Opcodes.I2L);
    }

    /** For {@link #I_PLAIN}: the int int code sees, without remembering it (it may be a hash, an index...). */
    private AbstractInsnNode toPlainInt() {
        return carry != null ? new MethodInsnNode(Opcodes.INVOKESTATIC, carry, "narrow", "(J)I", false) : new InsnNode(Opcodes.L2I);
    }

    private AbstractInsnNode toInt(boolean chunk) {
        return carry != null ? new MethodInsnNode(Opcodes.INVOKESTATIC, carry, chunk ? "carryOutChunk" : "carryOut", "(J)I", false)
            : new InsnNode(Opcodes.L2I);
    }

    /** Argument slots of a transit frame (WidePacking.TRANSIT_SLOTS); the count follows them. */
    private static final int TRANSIT_SLOTS = 8;

    /** Loads the transit frame into local {@code frame} (the receiving side of a hop, see {@link #abstractFallback}). */
    private void transitFrame(InsnList c, int frame) {
        c.add(new MethodInsnNode(Opcodes.INVOKESTATIC, carry, "transitFrame", "()[J", false));
        c.add(new VarInsnNode(Opcodes.ASTORE, frame));
    }

    /**
     * Pushes the int in local {@code intSlot} widened: the argument at {@code position} (among a call's int arguments)
     * of a lambda or method reference of the set, called through an interface hop (3.5.8).
     */
    private void transitTake(InsnList c, int frame, int intSlot, int position, boolean chunk) {
        if (carry == null) {
            c.add(new VarInsnNode(Opcodes.ILOAD, intSlot));
            c.add(new InsnNode(Opcodes.I2L));
            return;
        }
        c.add(new VarInsnNode(Opcodes.ALOAD, frame));
        c.add(new VarInsnNode(Opcodes.ILOAD, intSlot));
        c.add(new LdcInsnNode(position));
        c.add(new MethodInsnNode(Opcodes.INVOKESTATIC, carry, chunk ? "transitTakeChunk" : "transitTake", "([JII)J", false));
    }

    /**
     * The scale of the coordinates an int signature takes, as the suffix of the carry methods: "Chunk", "Quart",
     * "Block", or "" (any scale) without a quart list.
     */
    private String bridgeScale(String owner, String name, String intDesc) {
        if (chunkScaled(owner, name, intDesc) || chunkScaled.contains("args:" + owner + "." + name + intDesc)) return "Chunk";
        if (quartScaled == null) return "";
        return listed(quartScaled, owner, name, intDesc) ? "Quart" : "Block";
    }

    private static boolean listed(Set<String> list, String owner, String name, String intDesc) {
        return list.contains(owner + "." + name + intDesc) || list.contains("*." + name) || list.contains(owner + ".*");
    }

    private boolean chunkScaled(String owner, String name, String intDesc) {
        return listed(chunkScaled, owner, name, intDesc);
    }

    /** Whether an instruction calls a chunk-scaled method (its int operands are chunk coordinates). */
    private boolean chunkConsumer(AbstractInsnNode in) {
        return in instanceof MethodInsnNode mi && chunkScaled(mi.owner, mi.name, mi.desc);
    }

    /** Producers (in the method being rewritten) of ints that a chunk-scaled method takes as long. */
    private final Set<AbstractInsnNode> chunkSources = new HashSet<>();

    /** Local slots below this hold the parameters of the method being rewritten. */
    private int paramSlots;

    /**
     * Whether an int this instruction produces may be a narrowed coordinate: the result of a call (outside the set, or
     * inside it but computed from ints, like {@code QuartPos.toSection} of an int parameter), a field outside the set,
     * or a parameter (of a method that keeps its int signature because it overrides one outside the set). Not locals,
     * fields of the set or arithmetic: loop counters and indices must stay exact.
     */
    private boolean fromOutside(AbstractInsnNode p) {
        if (p instanceof MethodInsnNode) return true;
        if (p instanceof FieldInsnNode fi) return (fi.getOpcode() == Opcodes.GETFIELD || fi.getOpcode() == Opcodes.GETSTATIC) && !classes.containsKey(fi.owner);
        return p instanceof VarInsnNode vi && vi.getOpcode() == Opcodes.ILOAD && vi.var < paramSlots;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Program model

    private static String key(String owner, String name, String desc) {
        return owner + "." + name + desc;
    }

    private record OutsideInfo(String superName, List<String> interfaces, Set<String> methods, boolean isInterface) {}

    /** Whether a class (in the set or outside) is an interface; unknown classes count as classes. */
    private boolean isInterface(String name) {
        ClassNode cn = classes.get(name);
        if (cn != null) return (cn.access & Opcodes.ACC_INTERFACE) != 0;
        OutsideInfo o = outside(name);
        return o != null && o.isInterface();
    }

    private OutsideInfo outside(String name) {
        return outsideInfo.computeIfAbsent(name, n -> {
            byte[] b = outside.bytes(n);
            if (b == null) return null;
            ClassNode cn = new ClassNode();
            new ClassReader(b).accept(cn, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            Set<String> ms = new HashSet<>();
            for (MethodNode m : cn.methods) {
                if ((m.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) == 0) ms.add(m.name + m.desc);
            }
            return new OutsideInfo(cn.superName, cn.interfaces, ms, (cn.access & Opcodes.ACC_INTERFACE) != 0);
        });
    }

    private List<String> directSupers(String c) {
        ClassNode cn = classes.get(c);
        if (cn != null) {
            List<String> s = new ArrayList<>();
            if (cn.superName != null) s.add(cn.superName);
            s.addAll(cn.interfaces);
            return s;
        }
        OutsideInfo o = outside(c);
        if (o == null) return List.of();
        List<String> s = new ArrayList<>();
        if (o.superName() != null) s.add(o.superName());
        s.addAll(o.interfaces());
        return s;
    }

    private Set<String> allSupers(String c) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> d = new ArrayDeque<>(directSupers(c));
        while (!d.isEmpty()) {
            String s = d.poll();
            if (seen.add(s)) d.addAll(directSupers(s));
        }
        return seen;
    }

    private void buildFamilies() {
        Map<String, String> parent = new HashMap<>();
        for (String m : methods.keySet()) parent.put(m, m);
        Set<String> pinnedMembers = new HashSet<>();
        for (ClassNode cn : classes.values()) {
            for (MethodNode m : cn.methods) {
                if ((m.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) != 0 || m.name.startsWith("<")) continue;
                String mk = key(cn.name, m.name, m.desc);
                for (String sup : allSupers(cn.name)) {
                    String k = key(sup, m.name, m.desc);
                    if (methods.containsKey(k)) {
                        union(parent, mk, k);
                    } else if (!classes.containsKey(sup)) {
                        OutsideInfo o = outside(sup);
                        if (o != null && o.methods().contains(m.name + m.desc)) pinnedMembers.add(mk);
                    }
                }
                // Unknown outside supertypes (not on the class path): stay safe, keep the descriptor.
                for (String sup : allSupers(cn.name)) {
                    if (!classes.containsKey(sup) && outside(sup) == null && !sup.equals("java/lang/Object")) pinnedMembers.add(mk);
                }
            }
        }
        Map<String, Integer> ids = new HashMap<>();
        for (String m : methods.keySet()) {
            String root = find(parent, m);
            Integer id = ids.get(root);
            if (id == null) {
                id = familyMembers.size();
                ids.put(root, id);
                familyMembers.add(new ArrayList<>());
            }
            family.put(m, id);
            familyMembers.get(id).add(m);
        }
        for (String m : pinnedMembers) pinnedFamilies.add(family.get(m));
    }

    private static String find(Map<String, String> p, String x) {
        while (!p.get(x).equals(x)) {
            p.put(x, p.get(p.get(x)));
            x = p.get(x);
        }
        return x;
    }

    private static void union(Map<String, String> p, String a, String b) {
        String ra = find(p, a);
        String rb = find(p, b);
        if (!ra.equals(rb)) p.put(ra, rb);
    }

    /** The method a call ends up in (walking up from the static owner), or null if it is outside the set. */
    private String resolveMethod(String owner, String name, String desc) {
        if (!classes.containsKey(owner)) return null;
        String direct = key(owner, name, desc);
        if (methods.containsKey(direct)) return direct;
        for (String s : allSupers(owner)) {
            String k = key(s, name, desc);
            if (methods.containsKey(k)) return k;
        }
        return null;
    }

    private String resolveField(String owner, String name) {
        String c = owner;
        while (c != null && classes.containsKey(c)) {
            ClassNode cn = classes.get(c);
            for (FieldNode f : cn.fields) if (f.name.equals(name)) return c + "." + name;
            c = cn.superName;
        }
        return owner + "." + name;
    }

    private boolean fieldIsLong(String owner, String name, String desc) {
        return desc.equals("I") && longFields.contains(resolveField(owner, name));
    }

    /** Lambda bodies: their functional-interface parameters and int return keep their types. */
    private void findLambdaBodies() {
        for (MethodNode m : methods.values()) {
            for (AbstractInsnNode in : m.instructions) {
                if (!(in instanceof InvokeDynamicInsnNode indy) || !indy.bsm.getOwner().equals(LAMBDA_METAFACTORY)) continue;
                if (indy.bsmArgs.length < 3 || !(indy.bsmArgs[1] instanceof Handle h)) continue;
                String impl = key(h.getOwner(), h.getName(), h.getDesc());
                if (!methods.containsKey(impl) || !h.getName().startsWith("lambda$")) continue;
                lambdaBodies.add(impl);
                int captured = Type.getArgumentTypes(indy.desc).length;
                boolean instance = h.getTag() != Opcodes.H_INVOKESTATIC;
                int firstSamParam = captured - (instance ? 1 : 0);
                lambdaSamStart.put(impl, Math.max(firstSamParam, 0));
                BitSet pinned = pinnedParams.computeIfAbsent(impl, k -> new BitSet());
                int params = Type.getArgumentTypes(h.getDesc()).length;
                for (int i = Math.max(firstSamParam, 0); i < params; i++) pinned.set(i);
                Type samReturn = ((Type) indy.bsmArgs[2]).getReturnType();
                if (isInt(samReturn) || samReturn.getSort() == Type.OBJECT || samReturn.getSort() == Type.ARRAY) pinnedReturns.add(impl);
            }
        }
    }

    private String commonSuper(String a, String b) {
        if (a.equals(b)) return a;
        Set<String> sa = new LinkedHashSet<>();
        sa.add(a);
        sa.addAll(allSupers(a));
        if (sa.contains(b)) return b;
        Deque<String> d = new ArrayDeque<>();
        d.add(b);
        // walk b's superclasses (not interfaces, in the set or outside it: 3.5.8, a Player meeting an Entity got Object)
        while (!d.isEmpty()) {
            String s = d.poll();
            if (sa.contains(s) && !isInterface(s)) return s;
            directSupers(s).stream().filter(x -> !isInterface(x)).forEach(d::add);
        }
        return "java/lang/Object";
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Analysis

    /** A value: size in the original code, its sort (I, J, F, D, A), whether it is (to be) a long coordinate, producers. */
    private record V(int size, char kind, boolean t, Set<AbstractInsnNode> src) implements Value {
        @Override
        public int getSize() {
            return size;
        }
    }

    private static char kind(Type t) {
        return switch (t.getSort()) {
            case Type.INT, Type.SHORT, Type.BYTE, Type.CHAR, Type.BOOLEAN -> 'I';
            case Type.LONG -> 'J';
            case Type.FLOAT -> 'F';
            case Type.DOUBLE -> 'D';
            default -> 'A';
        };
    }

    private static boolean isInt(Type t) {
        return kind(t) == 'I';
    }

    private V val(Type type, boolean t, AbstractInsnNode in) {
        char k = kind(type);
        if (in != null) producedLong.put(in, t && k == 'I');
        return new V(type.getSize(), k, t && k == 'I', in == null ? Set.of() : Set.of(in));
    }

    private V v(int size, char kind, boolean t, AbstractInsnNode in) {
        producedLong.put(in, t && kind == 'I');
        return new V(size, kind, t && kind == 'I', Set.of(in));
    }

    /**
     * Whether each producing instruction yields a coordinate (long after the rewrite) by itself. A value merged from
     * branches ({@code c ? x : 0}) is a coordinate if any branch is; the others are converted (3.5.8).
     */
    private final Map<AbstractInsnNode, Boolean> producedLong = new HashMap<>();

    private int fam(String mk) {
        return family.get(mk);
    }

    private boolean paramLong(String mk, int i) {
        return !pinnedFamilies.contains(fam(mk)) && !pinnedParams.getOrDefault(mk, new BitSet()).get(i)
            && longParams.getOrDefault(fam(mk), new BitSet()).get(i);
    }

    private boolean returnLong(String mk) {
        return !pinnedFamilies.contains(fam(mk)) && !pinnedReturns.contains(mk) && longReturns.contains(fam(mk));
    }

    private void markParam(String mk, int i) {
        if (pinnedFamilies.contains(fam(mk)) || pinnedParams.getOrDefault(mk, new BitSet()).get(i)) return;
        BitSet b = longParams.computeIfAbsent(fam(mk), k -> new BitSet());
        if (!b.get(i)) {
            b.set(i);
            changed = true;
        }
    }

    /** JDK methods with a long twin that gives the right answer for long coordinates (owner, name, descriptor). */
    private static final Map<String, String[]> LONG_TWINS = Map.of(
        "java/lang/Integer.compare(II)I", new String[] {"java/lang/Long", "compare", "(JJ)I"},
        "java/lang/Integer.signum(I)I", new String[] {"java/lang/Long", "signum", "(J)I"},
        "java/lang/Integer.hashCode(I)I", new String[] {"java/lang/Long", "hashCode", "(J)I"},
        "java/lang/Integer.toString(I)Ljava/lang/String;", new String[] {"java/lang/Long", "toString", "(J)Ljava/lang/String;"},
        "java/lang/String.valueOf(I)Ljava/lang/String;", new String[] {"java/lang/String", "valueOf", "(J)Ljava/lang/String;"},
        "java/lang/StringBuilder.append(I)Ljava/lang/StringBuilder;",
        new String[] {"java/lang/StringBuilder", "append", "(J)Ljava/lang/StringBuilder;"});

    /** The long twin of a call outside the set ({@code java.lang.Math}, {@link #LONG_TWINS}, the extra twins), or null. */
    private String[] longTwin(MethodInsnNode mi) {
        if (mi.owner.equals("java/lang/Math") && MATH_LONG.contains(mi.name) && mi.desc.indexOf('I') >= 0
            && mi.desc.indexOf('J') < 0 && mi.desc.indexOf('D') < 0 && mi.desc.indexOf('F') < 0) {
            return new String[] {mi.owner, mi.name, mi.desc.replace('I', 'J')};
        }
        String k = mi.owner + "." + mi.name + mi.desc;
        String[] t = LONG_TWINS.get(k);
        return t != null ? t : extraTwin(mi);
    }

    /**
     * The extra twin of a call, also when it names a subtype of the twin's owner (3.5.8: {@code WorldgenRandom} for a
     * twin of {@code RandomSource}), or null.
     */
    private String[] extraTwin(MethodInsnNode mi) {
        String[] t = extraTwins.get(mi.owner + "." + mi.name + mi.desc);
        if (t != null || mi.getOpcode() == Opcodes.INVOKESTATIC) return t;
        return inheritedTwins.computeIfAbsent(mi.owner + "." + mi.name + mi.desc, k -> {
            for (String s : allSupers(mi.owner)) {
                String[] st = extraTwins.get(s + "." + mi.name + mi.desc);
                if (st != null) return Optional.of(st);
            }
            return Optional.empty();
        }).orElse(null);
    }

    private final Map<String, Optional<String[]>> inheritedTwins = new HashMap<>();

    /**
     * A twin of an instance method that is a static method taking the receiver first (3.5.8:
     * {@code RandomSource.nextIntBetweenInclusive(II)I} to a static helper with long bounds).
     */
    private static boolean staticReceiverTwin(MethodInsnNode mi, String[] twin) {
        return mi.getOpcode() != Opcodes.INVOKESTATIC
            && Type.getArgumentTypes(twin[2]).length == Type.getArgumentTypes(mi.desc).length + 1;
    }

    /**
     * The extra twins are exact replacements (for example the 64-bit packing), so they are always used, also when
     * the coordinate comes out rather than in ({@code blockX(J)I} to {@code blockX64(J)J}), and also when the original
     * method is in the widened set itself ({@code Mth.floor(D)I} to {@code Mth.lfloor(D)J}).
     */
    private boolean alwaysTwin(MethodInsnNode mi) {
        return extraTwin(mi) != null;
    }

    /** The parameter slots of a method that hold widened parameters (receiver excluded). */
    private Set<Integer> paramSlots(String mk, MethodNode m, Set<Integer> pinnedOut) {
        Set<Integer> s = new HashSet<>();
        int slot = (m.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        Type[] args = Type.getArgumentTypes(m.desc);
        for (int i = 0; i < args.length; i++) {
            if (isInt(args[i])) {
                if (paramLong(mk, i)) s.add(slot);
                else pinnedOut.add(slot);
            }
            slot += args[i].getSize();
        }
        return s;
    }

    private Frame<V>[] analyze(String mk) throws AnalyzerException {
        MethodNode m = methods.get(mk);
        if (m.instructions.size() == 0) return null;
        Set<Integer> slots = longSlots.computeIfAbsent(mk, k -> new HashSet<>());
        Set<Integer> intParamSlots = new HashSet<>();
        for (int s : paramSlots(mk, m, intParamSlots)) {
            if (slots.add(s)) changed = true;
        }
        Interpreter<V> interp = new Interpreter<>(Opcodes.ASM9) {
            @Override
            public V newValue(Type type) {
                if (type == null) return new V(1, 'A', false, Set.of());
                if (type == Type.VOID_TYPE) return null;
                return val(type, false, null);
            }

            @Override
            public V newParameterValue(boolean isInstanceMethod, int local, Type type) {
                return new V(type.getSize(), kind(type), isInt(type) && slots.contains(local), Set.of());
            }

            @Override
            public V newOperation(AbstractInsnNode in) {
                int op = in.getOpcode();
                return switch (op) {
                    case Opcodes.LCONST_0, Opcodes.LCONST_1 -> v(2, 'J', false, in);
                    case Opcodes.DCONST_0, Opcodes.DCONST_1 -> v(2, 'D', false, in);
                    case Opcodes.FCONST_0, Opcodes.FCONST_1, Opcodes.FCONST_2 -> v(1, 'F', false, in);
                    case Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2, Opcodes.ICONST_3,
                         Opcodes.ICONST_4, Opcodes.ICONST_5, Opcodes.BIPUSH, Opcodes.SIPUSH -> v(1, 'I', false, in);
                    case Opcodes.LDC -> {
                        Object c = ((LdcInsnNode) in).cst;
                        yield c instanceof Integer ? v(1, 'I', false, in) : c instanceof Long ? v(2, 'J', false, in)
                            : c instanceof Double ? v(2, 'D', false, in) : c instanceof Float ? v(1, 'F', false, in) : v(1, 'A', false, in);
                    }
                    case Opcodes.GETSTATIC -> {
                        FieldInsnNode fi = (FieldInsnNode) in;
                        yield val(Type.getType(fi.desc), fieldIsLong(fi.owner, fi.name, fi.desc), in);
                    }
                    default -> v(1, 'A', false, in); // ACONST_NULL, NEW, JSR
                };
            }

            @Override
            public V copyOperation(AbstractInsnNode in, V value) {
                int op = in.getOpcode();
                switch (op) {
                    case Opcodes.ILOAD:
                        return v(1, 'I', slots.contains(((VarInsnNode) in).var), in);
                    case Opcodes.LLOAD:
                        return v(2, 'J', false, in);
                    case Opcodes.FLOAD:
                        return v(1, 'F', false, in);
                    case Opcodes.DLOAD:
                        return v(2, 'D', false, in);
                    case Opcodes.ALOAD:
                        return v(1, 'A', false, in);
                    case Opcodes.ISTORE: {
                        int var = ((VarInsnNode) in).var;
                        if (value.t && !intParamSlots.contains(var) && slots.add(var)) changed = true;
                        return value;
                    }
                    default:
                        return value; // other stores, DUP*, SWAP: the same value, the same producers
                }
            }

            @Override
            public V unaryOperation(AbstractInsnNode in, V value) {
                int op = in.getOpcode();
                switch (op) {
                    case Opcodes.INEG, Opcodes.L2I:
                        return v(1, 'I', value.t, in);
                    case Opcodes.IINC:
                        return v(1, 'I', slots.contains(((IincInsnNode) in).var), in);
                    case Opcodes.I2B, Opcodes.I2C, Opcodes.I2S, Opcodes.F2I, Opcodes.D2I, Opcodes.ARRAYLENGTH, Opcodes.INSTANCEOF:
                        return v(1, 'I', false, in);
                    case Opcodes.I2L, Opcodes.F2L, Opcodes.D2L, Opcodes.LNEG:
                        return v(2, 'J', false, in);
                    case Opcodes.I2F, Opcodes.L2F, Opcodes.D2F, Opcodes.FNEG:
                        return v(1, 'F', false, in);
                    case Opcodes.I2D, Opcodes.L2D, Opcodes.F2D, Opcodes.DNEG:
                        return v(2, 'D', false, in);
                    case Opcodes.GETFIELD: {
                        FieldInsnNode fi = (FieldInsnNode) in;
                        return val(Type.getType(fi.desc), fieldIsLong(fi.owner, fi.name, fi.desc), in);
                    }
                    case Opcodes.PUTSTATIC: {
                        FieldInsnNode fi = (FieldInsnNode) in;
                        markField(fi, value);
                        return null;
                    }
                    case Opcodes.NEWARRAY, Opcodes.ANEWARRAY, Opcodes.CHECKCAST:
                        return v(1, 'A', false, in);
                    default:
                        return null;
                }
            }

            @Override
            public V binaryOperation(AbstractInsnNode in, V a, V b) {
                int op = in.getOpcode();
                switch (op) {
                    case Opcodes.IADD, Opcodes.ISUB, Opcodes.IMUL, Opcodes.IDIV, Opcodes.IREM, Opcodes.IAND, Opcodes.IOR, Opcodes.IXOR:
                        return v(1, 'I', a.t() || b.t(), in);
                    case Opcodes.ISHL, Opcodes.ISHR, Opcodes.IUSHR:
                        return v(1, 'I', a.t(), in);
                    case Opcodes.IALOAD, Opcodes.BALOAD, Opcodes.CALOAD, Opcodes.SALOAD, Opcodes.LCMP, Opcodes.FCMPL, Opcodes.FCMPG,
                         Opcodes.DCMPL, Opcodes.DCMPG:
                        return v(1, 'I', false, in);
                    case Opcodes.LALOAD, Opcodes.LADD, Opcodes.LSUB, Opcodes.LMUL, Opcodes.LDIV, Opcodes.LREM, Opcodes.LSHL,
                         Opcodes.LSHR, Opcodes.LUSHR, Opcodes.LAND, Opcodes.LOR, Opcodes.LXOR:
                        return v(2, 'J', false, in);
                    case Opcodes.DALOAD, Opcodes.DADD, Opcodes.DSUB, Opcodes.DMUL, Opcodes.DDIV, Opcodes.DREM:
                        return v(2, 'D', false, in);
                    case Opcodes.FALOAD, Opcodes.FADD, Opcodes.FSUB, Opcodes.FMUL, Opcodes.FDIV, Opcodes.FREM:
                        return v(1, 'F', false, in);
                    case Opcodes.AALOAD:
                        return v(1, 'A', false, in);
                    case Opcodes.PUTFIELD:
                        markField((FieldInsnNode) in, b);
                        return null;
                    default:
                        return null;
                }
            }

            @Override
            public V ternaryOperation(AbstractInsnNode in, V a, V b, V c) {
                return null;
            }

            @Override
            public V naryOperation(AbstractInsnNode in, List<? extends V> vals) {
                if (in instanceof MethodInsnNode mi) {
                    Type rt = Type.getReturnType(mi.desc);
                    String callee = alwaysTwin(mi) ? null : resolveMethod(mi.owner, mi.name, mi.desc); // exact twins win
                    int off = mi.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1;
                    if (callee != null) {
                        Type[] at = Type.getArgumentTypes(mi.desc);
                        for (int i = 0; i < at.length; i++) if (vals.get(i + off).t()) markParam(callee, i);
                        return rt == Type.VOID_TYPE ? null : val(rt, returnLong(callee), in);
                    }
                    String[] twin = longTwin(mi);
                    if (twin != null && (alwaysTwin(mi) || vals.stream().anyMatch(V::t))) {
                        return val(rt, Type.getReturnType(twin[2]) == Type.LONG_TYPE, in);
                    }
                    return rt == Type.VOID_TYPE ? null : val(rt, false, in);
                }
                if (in instanceof InvokeDynamicInsnNode indy) {
                    lambdaCaptures(indy, vals);
                    Type rt = Type.getReturnType(indy.desc);
                    return rt == Type.VOID_TYPE ? null : val(rt, false, in);
                }
                return v(1, 'A', false, in); // MULTIANEWARRAY
            }

            @Override
            public void returnOperation(AbstractInsnNode in, V value, V expected) {
                if (value.t() && !pinnedFamilies.contains(fam(mk)) && !pinnedReturns.contains(mk) && longReturns.add(fam(mk))) changed = true;
            }

            @Override
            public V merge(V a, V b) {
                if (a.equals(b)) return a;
                Set<AbstractInsnNode> s = new HashSet<>(a.src());
                s.addAll(b.src());
                char k = a.kind() == b.kind() ? a.kind() : a.kind() == 'A' ? b.kind() : a.kind();
                return new V(Math.max(a.size(), b.size()), k, a.t() || b.t(), s);
            }
        };
        return new Analyzer<>(interp).analyze(methodOwner.get(mk), m);
    }

    private void markField(FieldInsnNode fi, V value) {
        if (value.t() && fi.desc.equals("I") && classes.containsKey(fi.owner) && !pinnedFields.contains(resolveField(fi.owner, fi.name))
            && longFields.add(resolveField(fi.owner, fi.name))) changed = true;
    }

    /** Fields ({@code "owner.name"}) that never hold coordinates (see {@link #notCoordinates}). */
    private final Set<String> pinnedFields = new HashSet<>();

    /** Captured coordinates widen the lambda body's matching parameters. */
    private void lambdaCaptures(InvokeDynamicInsnNode indy, List<? extends V> vals) {
        if (!indy.bsm.getOwner().equals(LAMBDA_METAFACTORY) || indy.bsmArgs.length < 2 || !(indy.bsmArgs[1] instanceof Handle h)) return;
        String impl = key(h.getOwner(), h.getName(), h.getDesc());
        if (!lambdaBodies.contains(impl)) return;
        boolean instance = h.getTag() != Opcodes.H_INVOKESTATIC;
        for (int i = 0; i < vals.size(); i++) {
            int param = i - (instance ? 1 : 0);
            if (param >= 0 && vals.get(i).t()) markParam(impl, param);
        }
    }

    private String newDesc(String mk, String desc) {
        if (!methods.containsKey(mk)) return desc;
        Type[] at = Type.getArgumentTypes(desc);
        boolean any = false;
        for (int i = 0; i < at.length; i++) {
            if (at[i] == Type.INT_TYPE && paramLong(mk, i)) {
                at[i] = Type.LONG_TYPE;
                any = true;
            }
        }
        Type rt = Type.getReturnType(desc);
        if (rt == Type.INT_TYPE && returnLong(mk)) {
            rt = Type.LONG_TYPE;
            any = true;
        }
        return any ? Type.getMethodDescriptor(rt, at) : desc;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Rewrite

    private int rewrite(String mk, MethodNode m) throws AnalyzerException {
        Frame<V>[] frames = analyze(mk);
        if (frames == null) return 0;
        Set<Integer> slots = longSlots.get(mk);
        Map<AbstractInsnNode, Integer> conv = new HashMap<>();
        Map<AbstractInsnNode, List<Integer>> preConv = new HashMap<>();
        AbstractInsnNode[] insns = m.instructions.toArray();
        boolean longReturn = returnLong(mk);
        paramSlots = (m.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        for (Type t : Type.getArgumentTypes(m.desc)) paramSlots += t.getSize();
        // a chunk-scaled method converts its chunk coordinates at chunk scale in its body too: int parameters that
        // stay ints (ChunkPos.pack(II)), and narrowings (all of StaticCache2D works on chunk coordinates)
        boolean chunkBody = chunkScaled(methodOwner.get(mk), m.name, m.desc);
        // a lambda body of the set takes its int parameters from an interface hop (see transitOut)
        boolean lambdaBody = lambdaBodies.contains(mk);
        List<Need> needs = new ArrayList<>();
        for (int i = 0; i < insns.length; i++) {
            if (frames[i] != null) requirements(insns[i], frames[i], slots, needs, longReturn);
        }
        resolve(mk, needs, conv, preConv);
        // Local slots: parameters as in the new descriptor, then the rest; a long takes two.
        boolean isStatic = (m.access & Opcodes.ACC_STATIC) != 0;
        int[] newIndex = new int[m.maxLocals + 2];
        Set<Integer> wide = new HashSet<>();
        for (AbstractInsnNode in : insns) {
            if (in instanceof VarInsnNode vi && (vi.getOpcode() == Opcodes.LLOAD || vi.getOpcode() == Opcodes.DLOAD
                || vi.getOpcode() == Opcodes.LSTORE || vi.getOpcode() == Opcodes.DSTORE)) wide.add(vi.var);
        }
        int cur = 0;
        int old = 0;
        if (!isStatic) newIndex[old++] = cur++;
        Type[] args = Type.getArgumentTypes(m.desc);
        for (int i = 0; i < args.length; i++) {
            newIndex[old] = cur;
            cur += args[i] == Type.INT_TYPE && paramLong(mk, i) ? 2 : args[i].getSize();
            old += args[i].getSize();
        }
        for (int s = old; s < m.maxLocals; s++) {
            newIndex[s] = cur;
            cur += wide.contains(s) || slots.contains(s) ? 2 : 1;
        }
        int[] temp = {cur};
        // a lambda body takes its int coordinates back from the transit when it starts, before any call of its own can
        // start another transit; each use then loads that long
        Map<Integer, Integer> snapshot = new HashMap<>();
        InsnList entry = new InsnList();
        if (lambdaBody && carry != null) {
            Set<Integer> converted = new HashSet<>();
            for (Map.Entry<AbstractInsnNode, Integer> e : conv.entrySet()) {
                if (e.getValue() == L && e.getKey() instanceof VarInsnNode vi && vi.getOpcode() == Opcodes.ILOAD && vi.var < paramSlots) converted.add(vi.var);
            }
            int samStart = lambdaSamStart.getOrDefault(mk, 0);
            int var = isStatic ? 0 : 1;
            int position = 0;
            int frame = -1;
            for (int i = 0; i < args.length; i++) {
                if (i >= samStart && args[i] == Type.INT_TYPE) {
                    if (converted.contains(var) && !paramLong(mk, i)) {
                        if (frame < 0) {
                            frame = temp[0]++;
                            transitFrame(entry, frame);
                        }
                        int s = temp[0];
                        temp[0] += 2;
                        transitTake(entry, frame, newIndex[var], position, chunkBody);
                        entry.add(new VarInsnNode(Opcodes.LSTORE, s));
                        snapshot.put(var, s);
                    }
                    position++;
                }
                var += args[i].getSize();
            }
        }
        InsnList out = new InsnList();
        int count = 0;
        for (int i = 0; i < insns.length; i++) {
            AbstractInsnNode in = insns[i];
            if (in instanceof FrameNode) continue;
            Frame<V> f = frames[i];
            int before = out.size();
            List<Integer> pre = preConv.get(in);
            if (pre != null) {
                for (int c : pre) out.add(c == I_PLAIN ? toPlainInt() : c == I ? toInt(chunkBody) : toLong(c == CARRY_L || c == CHUNK_L, c == CHUNK_L || chunkBody && c == CARRY_L));
                before = out.size();
                count += pre.size();
            }
            if (f == null) {
                out.add(in);
            } else {
                emit(mk, in, f, slots, conv, newIndex, temp, out, longReturn);
            }
            if (!sameInstruction(out, before, in)) count++;
            Integer c = conv.get(in);
            if (c != null) {
                Integer snap = c == L && in instanceof VarInsnNode vi && vi.getOpcode() == Opcodes.ILOAD ? snapshot.get(vi.var) : null;
                boolean chunk = chunkBody && in instanceof VarInsnNode || chunkSources.contains(in);
                if (snap != null) {
                    out.add(new InsnNode(Opcodes.POP));
                    out.add(new VarInsnNode(Opcodes.LLOAD, snap));
                } else if (c == L && in instanceof LdcInsnNode ldc && ldc.cst instanceof Integer k && (k == Integer.MAX_VALUE || k == Integer.MIN_VALUE)) {
                    // a sentinel (min = Integer.MAX_VALUE; min = Math.min(min, x)) becomes the long one (3.5.8)
                    out.add(new InsnNode(Opcodes.POP));
                    out.add(new LdcInsnNode(k == Integer.MAX_VALUE ? Long.MAX_VALUE : Long.MIN_VALUE));
                } else {
                    out.add(c == I_PLAIN ? toPlainInt() : c != L ? toInt(chunkBody) : toLong(fromOutside(in), chunk));
                }
                count++;
            }
        }
        out.insert(entry);
        m.instructions = out;
        m.localVariables = null;
        m.maxLocals = temp[0];
        return count;
    }

    private static boolean sameInstruction(InsnList out, int before, AbstractInsnNode in) {
        return out.size() == before + 1 && out.getLast() == in;
    }

    private static int rep(Map<AbstractInsnNode, Integer> conv, V value) {
        if (value.kind() != 'I') return 0;
        for (AbstractInsnNode p : value.src()) {
            Integer c = conv.get(p);
            if (c != null) return c == I_PLAIN ? I : c;
        }
        return value.t() ? L : I;
    }

    /** Size after the rewrite. */
    private static int size(Map<AbstractInsnNode, Integer> conv, V value) {
        return value.kind() == 'I' && rep(conv, value) == L ? 2 : value.size();
    }

    /** A consumer wants an int operand as int or as long; {@code top}: the operand is on top of the stack there. */
    private record Need(AbstractInsnNode consumer, V value, int want, boolean top) {}

    /**
     * Picks one representation per producer (long if any consumer needs a long, int only if every consumer of a
     * long wants an int) and converts right before the consumers that still disagree, which works when their operand
     * is on top of the stack.
     */
    private void resolve(String mk, List<Need> needs, Map<AbstractInsnNode, Integer> conv, Map<AbstractInsnNode, List<Integer>> preConv) {
        Map<AbstractInsnNode, Boolean> anyLong = new HashMap<>();
        Map<AbstractInsnNode, Boolean> allInt = new HashMap<>();
        Map<AbstractInsnNode, Boolean> allPlain = new HashMap<>();
        for (Need nd : needs) {
            for (AbstractInsnNode p : nd.value().src()) {
                anyLong.merge(p, nd.want() == L, Boolean::logicalOr);
                allInt.merge(p, nd.want() == I, Boolean::logicalAnd);
                allPlain.merge(p, nd.want() == I && plainSink(mk, nd.consumer()), Boolean::logicalAnd);
            }
        }
        chunkSources.clear();
        for (Need nd : needs) {
            for (AbstractInsnNode p : nd.value().src()) {
                // an int producer merged into a coordinate is converted like one whose consumer wants a long
                boolean intHere = !nd.value().t() || nd.value().src().size() > 1 && !producedLong.getOrDefault(p, true);
                // (only when the merged value is wanted as a long: c ? random(1, 80) : tick - 1 into an int field stays int)
                if (intHere && (anyLong.get(p) || nd.value().t() && nd.want() == L)) {
                    conv.put(p, L);
                    if (nd.want() == L && chunkConsumer(nd.consumer())) chunkSources.add(p);
                }
                if (nd.value().t() && !intHere && allInt.get(p)) conv.put(p, allPlain.get(p) ? I_PLAIN : I);
            }
        }
        for (Need nd : needs) {
            int have = rep(conv, nd.value());
            if (have == nd.want()) continue;
            if (nd.value().src().isEmpty()) {
                problems.add("cannot convert a value without a producing instruction in " + mk);
            } else if (nd.top()) {
                boolean outsideInt = nd.want() == L && nd.value().src().stream().anyMatch(this::fromOutside);
                int want = nd.want() == I && plainSink(mk, nd.consumer()) ? I_PLAIN : nd.want();
                preConv.computeIfAbsent(nd.consumer(), k -> new ArrayList<>())
                    .add(!outsideInt ? want : chunkConsumer(nd.consumer()) ? CHUNK_L : CARRY_L);
            } else {
                problems.add("a value below the top of the stack is needed both as int and as long in " + mk);
            }
        }
    }

    /**
     * Whether an int this consumer takes is never a coordinate handed to game code (see {@link #I_PLAIN}): array
     * accesses and sizes, shifts, switches, calls into the JDK or a library, and the result of the methods that keep
     * their int descriptor because they implement {@code Object.hashCode} or {@code Comparable.compareTo}.
     */
    private boolean plainSink(String mk, AbstractInsnNode consumer) {
        int op = consumer.getOpcode();
        return switch (op) {
            case Opcodes.IALOAD, Opcodes.LALOAD, Opcodes.FALOAD, Opcodes.DALOAD, Opcodes.AALOAD, Opcodes.BALOAD, Opcodes.CALOAD,
                 Opcodes.SALOAD, Opcodes.NEWARRAY, Opcodes.ANEWARRAY, Opcodes.MULTIANEWARRAY, Opcodes.TABLESWITCH, Opcodes.LOOKUPSWITCH,
                 Opcodes.IASTORE, Opcodes.LASTORE, Opcodes.FASTORE, Opcodes.DASTORE, Opcodes.AASTORE, Opcodes.BASTORE, Opcodes.CASTORE,
                 Opcodes.SASTORE, Opcodes.ISHL, Opcodes.ISHR, Opcodes.IUSHR, Opcodes.LSHL, Opcodes.LSHR, Opcodes.LUSHR -> true;
            case Opcodes.IRETURN -> mk.contains(".hashCode()I") || mk.contains(".compareTo(");
            case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESTATIC, Opcodes.INVOKESPECIAL, Opcodes.INVOKEINTERFACE ->
                !((MethodInsnNode) consumer).owner.startsWith("net/minecraft/") && !((MethodInsnNode) consumer).owner.startsWith("net/singularitymc/")
                    && !((MethodInsnNode) consumer).owner.startsWith("com/mojang/");
            default -> false;
        };
    }

    private void requirements(AbstractInsnNode in, Frame<V> f, Set<Integer> slots, List<Need> needs, boolean longReturn) {
        int op = in.getOpcode();
        int n = f.getStackSize();
        java.util.function.BiConsumer<Integer, Integer> need = (pos, want) -> {
            V value = f.getStack(pos);
            if (value.kind() == 'I') needs.add(new Need(in, value, want, pos == n - 1));
        };
        switch (op) {
            case Opcodes.IADD, Opcodes.ISUB, Opcodes.IMUL, Opcodes.IDIV, Opcodes.IREM, Opcodes.IAND, Opcodes.IOR, Opcodes.IXOR,
                 Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE, Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE -> {
                int want = f.getStack(n - 2).t() || f.getStack(n - 1).t() ? L : I;
                need.accept(n - 2, want);
                need.accept(n - 1, want);
            }
            case Opcodes.ISHL, Opcodes.ISHR, Opcodes.IUSHR, Opcodes.LSHL, Opcodes.LSHR, Opcodes.LUSHR -> need.accept(n - 1, I);
            case Opcodes.ISTORE -> need.accept(n - 1, slots.contains(((VarInsnNode) in).var) ? L : I);
            case Opcodes.IRETURN -> need.accept(n - 1, longReturn ? L : I);
            case Opcodes.PUTFIELD, Opcodes.PUTSTATIC -> {
                FieldInsnNode fi = (FieldInsnNode) in;
                need.accept(n - 1, fieldIsLong(fi.owner, fi.name, fi.desc) ? L : I);
            }
            case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESTATIC, Opcodes.INVOKESPECIAL, Opcodes.INVOKEINTERFACE -> {
                MethodInsnNode mi = (MethodInsnNode) in;
                Type[] at = Type.getArgumentTypes(mi.desc);
                int base = n - at.length;
                String callee = alwaysTwin(mi) ? null : resolveMethod(mi.owner, mi.name, mi.desc); // exact twins win
                String[] twin = callee == null ? longTwin(mi) : null;
                boolean useTwin = twin != null && alwaysTwin(mi);
                if (twin != null) {
                    for (int k = 0; k < at.length; k++) useTwin |= f.getStack(base + k).t();
                }
                Type[] twinArgs = useTwin ? Type.getArgumentTypes(twin[2]) : null;
                int receiver = useTwin && staticReceiverTwin(mi, twin) ? 1 : 0;
                for (int k = 0; k < at.length; k++) {
                    if (!isInt(at[k])) continue;
                    int want = callee != null ? (at[k] == Type.INT_TYPE && paramLong(callee, k) ? L : I)
                        : useTwin && twinArgs[k + receiver] == Type.LONG_TYPE ? L : I; // the twin decides which arguments are long
                    need.accept(base + k, want);
                }
            }
            case Opcodes.INVOKEDYNAMIC -> {
                InvokeDynamicInsnNode indy = (InvokeDynamicInsnNode) in;
                Type[] at = Type.getArgumentTypes(indy.desc);
                int base = n - at.length;
                boolean concat = indy.bsm.getOwner().equals(CONCAT_FACTORY);
                String impl = lambdaImpl(indy);
                boolean instance = impl != null && ((Handle) indy.bsmArgs[1]).getTag() != Opcodes.H_INVOKESTATIC;
                for (int k = 0; k < at.length; k++) {
                    if (!isInt(at[k]) || concat) continue;
                    int param = k - (instance ? 1 : 0);
                    int want = impl != null && param >= 0 && at[k] == Type.INT_TYPE && paramLong(impl, param) ? L : I;
                    need.accept(base + k, want);
                }
            }
            case Opcodes.IALOAD, Opcodes.LALOAD, Opcodes.FALOAD, Opcodes.DALOAD, Opcodes.AALOAD, Opcodes.BALOAD, Opcodes.CALOAD,
                 Opcodes.SALOAD, Opcodes.NEWARRAY, Opcodes.ANEWARRAY, Opcodes.TABLESWITCH, Opcodes.LOOKUPSWITCH -> need.accept(n - 1, I);
            case Opcodes.IASTORE, Opcodes.BASTORE, Opcodes.CASTORE, Opcodes.SASTORE -> {
                need.accept(n - 2, I);
                need.accept(n - 1, I);
            }
            case Opcodes.LASTORE, Opcodes.FASTORE, Opcodes.AASTORE -> need.accept(n - 2, I);
            case Opcodes.DASTORE -> need.accept(n - 2, I);
            case Opcodes.MULTIANEWARRAY -> {
                int dims = ((org.objectweb.asm.tree.MultiANewArrayInsnNode) in).dims;
                for (int k = 1; k <= dims; k++) need.accept(n - k, I);
            }
            default -> { }
        }
    }

    /** The lambda body an invokedynamic targets, if it is one of ours. */
    private String lambdaImpl(InvokeDynamicInsnNode indy) {
        if (!indy.bsm.getOwner().equals(LAMBDA_METAFACTORY) || indy.bsmArgs.length < 2 || !(indy.bsmArgs[1] instanceof Handle h)) return null;
        String impl = key(h.getOwner(), h.getName(), h.getDesc());
        return lambdaBodies.contains(impl) ? impl : null;
    }

    private void emit(String mk, AbstractInsnNode in, Frame<V> f, Set<Integer> slots, Map<AbstractInsnNode, Integer> conv,
                      int[] newIndex, int[] temp, InsnList out, boolean longReturn) {
        int op = in.getOpcode();
        int n = f.getStackSize();
        if (in instanceof VarInsnNode vi) {
            int nop = op;
            if (slots.contains(vi.var)) {
                if (op == Opcodes.ILOAD) nop = Opcodes.LLOAD;
                if (op == Opcodes.ISTORE) nop = Opcodes.LSTORE;
            }
            out.add(new VarInsnNode(nop, newIndex[vi.var]));
        } else if (in instanceof IincInsnNode ii) {
            int nv = newIndex[ii.var];
            if (slots.contains(ii.var)) {
                out.add(new VarInsnNode(Opcodes.LLOAD, nv));
                out.add(new LdcInsnNode((long) ii.incr));
                out.add(new InsnNode(Opcodes.LADD));
                out.add(new VarInsnNode(Opcodes.LSTORE, nv));
            } else {
                out.add(new IincInsnNode(nv, ii.incr));
            }
        } else if (isIntArith(op)) {
            boolean shift = op == Opcodes.ISHL || op == Opcodes.ISHR || op == Opcodes.IUSHR;
            boolean longOp = rep(conv, f.getStack(shift ? n - 2 : n - 1)) == L;
            out.add(longOp ? new InsnNode(op + 1) : in); // each long opcode is the int one + 1
        } else if (op == Opcodes.INEG && rep(conv, f.getStack(n - 1)) == L) {
            out.add(new InsnNode(Opcodes.LNEG));
        } else if (op >= Opcodes.IF_ICMPEQ && op <= Opcodes.IF_ICMPLE && rep(conv, f.getStack(n - 1)) == L) {
            out.add(new InsnNode(Opcodes.LCMP));
            out.add(new JumpInsnNode(op - Opcodes.IF_ICMPEQ + Opcodes.IFEQ, ((JumpInsnNode) in).label));
        } else if (op >= Opcodes.IFEQ && op <= Opcodes.IFLE && rep(conv, f.getStack(n - 1)) == L) {
            out.add(new InsnNode(Opcodes.LCONST_0));
            out.add(new InsnNode(Opcodes.LCMP));
            out.add(new JumpInsnNode(op, ((JumpInsnNode) in).label));
        } else if ((op == Opcodes.I2L || op == Opcodes.L2I && f.getStack(n - 1).kind() == 'I') && rep(conv, f.getStack(n - 1)) == L) {
            // already long
        } else if ((op == Opcodes.I2D || op == Opcodes.I2F) && rep(conv, f.getStack(n - 1)) == L) {
            out.add(new InsnNode(op == Opcodes.I2D ? Opcodes.L2D : Opcodes.L2F));
        } else if ((op == Opcodes.I2B || op == Opcodes.I2C || op == Opcodes.I2S) && rep(conv, f.getStack(n - 1)) == L) {
            out.add(new InsnNode(Opcodes.L2I));
            out.add(in);
        } else if (op == Opcodes.IRETURN && longReturn) {
            out.add(new InsnNode(Opcodes.LRETURN));
        } else if (in instanceof FieldInsnNode fi && fieldIsLong(fi.owner, fi.name, fi.desc)) {
            out.add(new FieldInsnNode(op, fi.owner, fi.name, "J"));
        } else if (in instanceof MethodInsnNode mi) {
            String callee = alwaysTwin(mi) ? null : resolveMethod(mi.owner, mi.name, mi.desc); // exact twins win
            String[] twin = callee == null ? longTwin(mi) : null;
            if (twin != null) {
                // Same decision as in requirements(): the twin is used when a coordinate goes in.
                Type[] at = Type.getArgumentTypes(mi.desc);
                boolean any = alwaysTwin(mi);
                for (int k = 0; k < at.length; k++) any |= f.getStack(n - at.length + k).t();
                if (!any) {
                    out.add(in);
                } else if (staticReceiverTwin(mi, twin)) {
                    out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, twin[0], twin[1], twin[2], false));
                } else {
                    out.add(new MethodInsnNode(op, twin[0], twin[1], twin[2], mi.itf));
                }
            } else {
                String desc = callee != null ? newDesc(callee, mi.desc) : mi.desc;
                out.add(desc.equals(mi.desc) ? in : new MethodInsnNode(op, mi.owner, mi.name, desc, mi.itf));
            }
        } else if (in instanceof InvokeDynamicInsnNode indy) {
            out.add(rewriteIndy(indy, f, n, conv));
        } else if (in instanceof LdcInsnNode ldc && ldc.cst instanceof Handle h) {
            out.add(new LdcInsnNode(retarget(h)));
        } else if (op >= Opcodes.POP && op <= Opcodes.SWAP) {
            stackOp(op, f, n, conv, temp, out, in);
        } else {
            out.add(in);
        }
    }

    private static boolean isIntArith(int op) {
        return switch (op) {
            case Opcodes.IADD, Opcodes.ISUB, Opcodes.IMUL, Opcodes.IDIV, Opcodes.IREM, Opcodes.ISHL, Opcodes.ISHR, Opcodes.IUSHR,
                 Opcodes.IAND, Opcodes.IOR, Opcodes.IXOR -> true;
            default -> false;
        };
    }

    private AbstractInsnNode rewriteIndy(InvokeDynamicInsnNode indy, Frame<V> f, int n, Map<AbstractInsnNode, Integer> conv) {
        Type[] at = Type.getArgumentTypes(indy.desc);
        Object[] bsmArgs = indy.bsmArgs.clone();
        boolean descChanged = false;
        if (indy.bsm.getOwner().equals(CONCAT_FACTORY) || lambdaImpl(indy) != null) {
            for (int k = 0; k < at.length; k++) {
                if (at[k] == Type.INT_TYPE && rep(conv, f.getStack(n - at.length + k)) == L) {
                    at[k] = Type.LONG_TYPE;
                    descChanged = true;
                }
            }
        }
        boolean argsChanged = false;
        for (int k = 0; k < bsmArgs.length; k++) {
            if (bsmArgs[k] instanceof Handle h) {
                Handle r = retarget(h);
                if (r != h) {
                    bsmArgs[k] = r;
                    argsChanged = true;
                }
            }
        }
        if (!descChanged && !argsChanged) return indy;
        String desc = descChanged ? Type.getMethodDescriptor(Type.getReturnType(indy.desc), at) : indy.desc;
        return new InvokeDynamicInsnNode(indy.name, desc, indy.bsm, bsmArgs);
    }

    /** A method or field handle after widening: field handles follow their field; widened methods get a bridge. */
    private Handle retarget(Handle h) {
        int tag = h.getTag();
        if (tag <= Opcodes.H_PUTSTATIC) {
            return h.getDesc().equals("I") && longFields.contains(resolveField(h.getOwner(), h.getName()))
                ? new Handle(tag, h.getOwner(), h.getName(), "J", h.isInterface()) : h;
        }
        String mk = resolveMethod(h.getOwner(), h.getName(), h.getDesc());
        if (mk == null) return h;
        String nd = newDesc(mk, h.getDesc());
        if (nd.equals(h.getDesc())) return h;
        if (lambdaBodies.contains(mk)) return new Handle(tag, h.getOwner(), h.getName(), nd, h.isInterface());
        return bridge(h, nd);
    }

    /** A public static method with the handle's original descriptor that calls the widened method. */
    private Handle bridge(Handle h, String newDesc) {
        String bkey = h.getTag() + " " + key(h.getOwner(), h.getName(), h.getDesc());
        ClassNode owner = classes.get(h.getOwner());
        boolean itf = (owner.access & Opcodes.ACC_INTERFACE) != 0;
        boolean ctor = h.getTag() == Opcodes.H_NEWINVOKESPECIAL;
        boolean receiver = !ctor && h.getTag() != Opcodes.H_INVOKESTATIC;
        Type[] orig = Type.getArgumentTypes(h.getDesc());
        Type[] wide = Type.getArgumentTypes(newDesc);
        List<Type> params = new ArrayList<>();
        if (receiver) params.add(Type.getObjectType(h.getOwner()));
        params.addAll(Arrays.asList(orig));
        Type ret = ctor ? Type.getObjectType(h.getOwner()) : Type.getReturnType(h.getDesc());
        String bdesc = Type.getMethodDescriptor(ret, params.toArray(new Type[0]));
        String name = bridges.get(bkey);
        if (name == null) {
            name = "singularity$ref$" + (ctor ? "new" : h.getName()) + "$" + bridges.size();
            bridges.put(bkey, name);
            MethodNode b = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, name, bdesc, null, null);
            InsnList c = b.instructions;
            int slot = 0;
            if (ctor) {
                c.add(new TypeInsnNode(Opcodes.NEW, h.getOwner()));
                c.add(new InsnNode(Opcodes.DUP));
            }
            int frame = (receiver ? 1 : 0) + Arrays.stream(orig).mapToInt(Type::getSize).sum();
            if (carry != null) transitFrame(c, frame);
            if (receiver) c.add(new VarInsnNode(Opcodes.ALOAD, slot++));
            boolean chunk = chunkScaled(h.getOwner(), h.getName(), h.getDesc());
            int position = 0;
            for (int k = 0; k < orig.length; k++) {
                if (orig[k] == Type.INT_TYPE && wide[k] == Type.LONG_TYPE) {
                    transitTake(c, frame, slot, position, chunk);
                } else {
                    c.add(new VarInsnNode(orig[k].getOpcode(Opcodes.ILOAD), slot));
                }
                if (orig[k] == Type.INT_TYPE) position++;
                slot += orig[k].getSize();
            }
            int invoke = switch (h.getTag()) {
                case Opcodes.H_INVOKESTATIC -> Opcodes.INVOKESTATIC;
                case Opcodes.H_INVOKEINTERFACE -> Opcodes.INVOKEINTERFACE;
                case Opcodes.H_INVOKESPECIAL, Opcodes.H_NEWINVOKESPECIAL -> Opcodes.INVOKESPECIAL;
                default -> Opcodes.INVOKEVIRTUAL;
            };
            c.add(new MethodInsnNode(invoke, h.getOwner(), h.getName(), newDesc, h.isInterface()));
            Type wret = Type.getReturnType(newDesc);
            if (!ctor && Type.getReturnType(h.getDesc()) == Type.INT_TYPE && wret == Type.LONG_TYPE) c.add(toInt(chunk));
            c.add(new InsnNode(ret.getOpcode(Opcodes.IRETURN)));
            b.maxLocals = frame + 1;
            b.maxStack = slot + 6;
            owner.methods.add(b);
        }
        return new Handle(Opcodes.H_INVOKESTATIC, h.getOwner(), name, bdesc, itf);
    }

    /** DUP/POP/SWAP and friends. When a value changes size, the original shuffle is replayed through temporaries. */
    private void stackOp(int op, Frame<V> f, int n, Map<AbstractInsnNode, Integer> conv, int[] temp, InsnList out, AbstractInsnNode in) {
        int c1 = f.getStack(n - 1).size();
        int c2 = n >= 2 ? f.getStack(n - 2).size() : 0;
        int c3 = n >= 3 ? f.getStack(n - 3).size() : 0;
        // inputs: how many stack values (from the top) the original instruction reads; outputs: their order afterwards
        int inputs;
        int[] outputs; // indices into inputs, 0 = deepest
        switch (op) {
            case Opcodes.POP -> { inputs = 1; outputs = new int[] {}; }
            case Opcodes.POP2 -> { if (c1 == 2) { inputs = 1; outputs = new int[] {}; } else { inputs = 2; outputs = new int[] {}; } }
            case Opcodes.DUP -> { inputs = 1; outputs = new int[] {0, 0}; }
            case Opcodes.DUP_X1 -> { inputs = 2; outputs = new int[] {1, 0, 1}; }
            case Opcodes.DUP_X2 -> {
                if (c2 == 2) { inputs = 2; outputs = new int[] {1, 0, 1}; } else { inputs = 3; outputs = new int[] {2, 0, 1, 2}; }
            }
            case Opcodes.DUP2 -> { if (c1 == 2) { inputs = 1; outputs = new int[] {0, 0}; } else { inputs = 2; outputs = new int[] {0, 1, 0, 1}; } }
            case Opcodes.DUP2_X1 -> {
                if (c1 == 2) { inputs = 2; outputs = new int[] {1, 0, 1}; } else { inputs = 3; outputs = new int[] {1, 2, 0, 1, 2}; }
            }
            case Opcodes.DUP2_X2 -> {
                if (c1 == 2 && c2 == 2) { inputs = 2; outputs = new int[] {1, 0, 1}; }
                else if (c1 == 2) { inputs = 3; outputs = new int[] {2, 0, 1, 2}; }
                else if (c3 == 2) { inputs = 3; outputs = new int[] {1, 2, 0, 1, 2}; }
                else { inputs = 4; outputs = new int[] {2, 3, 0, 1, 2, 3}; }
            }
            default -> { inputs = 2; outputs = new int[] {1, 0}; } // SWAP
        }
        V[] vals = new V[inputs];
        boolean resized = false;
        for (int k = 0; k < inputs; k++) {
            vals[k] = f.getStack(n - inputs + k);
            resized |= size(conv, vals[k]) != vals[k].size();
        }
        if (!resized) {
            out.add(in);
            return;
        }
        if (outputs.length == 0) {
            for (int k = inputs - 1; k >= 0; k--) out.add(new InsnNode(size(conv, vals[k]) == 2 ? Opcodes.POP2 : Opcodes.POP));
            return;
        }
        int[] slot = new int[inputs];
        for (int k = inputs - 1; k >= 0; k--) {
            slot[k] = temp[0];
            temp[0] += size(conv, vals[k]);
            out.add(new VarInsnNode(finalType(conv, vals[k]).getOpcode(Opcodes.ISTORE), slot[k]));
        }
        for (int idx : outputs) out.add(new VarInsnNode(finalType(conv, vals[idx]).getOpcode(Opcodes.ILOAD), slot[idx]));
    }

    private static Type finalType(Map<AbstractInsnNode, Integer> conv, V value) {
        return switch (value.kind()) {
            case 'I' -> rep(conv, value) == L ? Type.LONG_TYPE : Type.INT_TYPE;
            case 'J' -> Type.LONG_TYPE;
            case 'F' -> Type.FLOAT_TYPE;
            case 'D' -> Type.DOUBLE_TYPE;
            default -> Type.getObjectType("java/lang/Object");
        };
    }
}
