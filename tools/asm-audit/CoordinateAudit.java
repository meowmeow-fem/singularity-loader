import java.util.*;
import java.util.zip.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/**
 * Singularity 3.5.1 experiment (docs/ASM_64BIT.md): how far do X/Z block coordinates spread through Minecraft's
 * bytecode? An interprocedural ASM dataflow ("taint") analysis: X/Z start in Vec3i.x/z, ChunkPos.x/z and
 * Entity.getX/getZ, and flow through locals, arithmetic, fields, parameters, return values, overrides and lambda
 * captures until nothing changes. The result is what an ASM rewrite to 64-bit X/Z would have to change, and where it
 * would hit code it cannot change (the JDK and libraries, arrays, int-only formats).
 *
 * <p>It over-approximates: a value counts as a coordinate if any input was one.
 *
 * <p>Usage: java -cp asm.jar;asm-tree.jar;asm-analysis.jar CoordinateAudit.java server.jar
 */
public class CoordinateAudit {
    // ---- program model ----
    static final Map<String, ClassNode> classes = new HashMap<>();
    static final Map<String, List<String>> supers = new HashMap<>();
    static final Map<String, List<String>> subs = new HashMap<>();
    /** method "owner.name desc" -> family id (overrides share a family). */
    static final Map<String, Integer> family = new HashMap<>();
    static final List<List<String>> familyMembers = new ArrayList<>();

    // ---- taint state ----
    static final Map<Integer, BitSet> taintedParams = new HashMap<>();
    static final Set<Integer> taintedReturns = new HashSet<>();
    static final Set<String> taintedFields = new HashSet<>();

    // ---- indices for the worklist ----
    static final Map<Integer, Set<String>> callersOfFamily = new HashMap<>();
    static final Map<String, Set<String>> readersOfField = new HashMap<>();
    static final Map<String, MethodNode> methods = new HashMap<>();

    public static void main(String[] args) throws Exception {
        long t0 = System.currentTimeMillis();
        try (ZipFile z = new ZipFile(args[0])) {
            for (ZipEntry e : Collections.list(z.entries())) {
                if (!e.getName().startsWith("net/minecraft/") || !e.getName().endsWith(".class")) continue;
                ClassNode cn = new ClassNode();
                new ClassReader(z.getInputStream(e)).accept(cn, ClassReader.SKIP_FRAMES);
                classes.put(cn.name, cn);
            }
        }
        for (ClassNode cn : classes.values()) {
            List<String> s = new ArrayList<>();
            if (cn.superName != null) s.add(cn.superName);
            s.addAll(cn.interfaces);
            supers.put(cn.name, s);
            for (String p : s) subs.computeIfAbsent(p, k -> new ArrayList<>()).add(cn.name);
            for (MethodNode m : cn.methods) methods.put(key(cn.name, m.name, m.desc), m);
        }
        buildFamilies();
        buildIndices();

        // Seeds
        taintedFields.add("net/minecraft/core/Vec3i.x");
        taintedFields.add("net/minecraft/core/Vec3i.z");
        taintedFields.add("net/minecraft/world/level/ChunkPos.x");
        taintedFields.add("net/minecraft/world/level/ChunkPos.z");
        for (String m : List.of("getX ()D", "getZ ()D", "getBlockX ()I", "getBlockZ ()I")) {
            Integer f = family.get("net/minecraft/world/entity/Entity." + m);
            if (f != null) taintedReturns.add(f);
        }

        // Fixpoint
        Deque<String> work = new ArrayDeque<>(methods.keySet());
        Set<String> queued = new HashSet<>(methods.keySet());
        int analyses = 0, failures = 0;
        while (!work.isEmpty()) {
            String mk = work.poll();
            queued.remove(mk);
            Changes ch = new Changes();
            try {
                analyze(mk, ch, null);
            } catch (AnalyzerException ex) {
                failures++;
            }
            analyses++;
            for (String m : ch.affected) if (methods.containsKey(m) && queued.add(m)) work.add(m);
        }
        // Final pass with statistics
        Stats st = new Stats();
        for (String mk : methods.keySet()) {
            try {
                analyze(mk, new Changes(), st);
            } catch (AnalyzerException ex) {
                // counted above
            }
        }
        report(st, analyses, failures, System.currentTimeMillis() - t0);
    }

    static String key(String owner, String name, String desc) {
        return owner + "." + name + " " + desc;
    }

    // ---- families: a method and every override of it in Minecraft's classes ----
    static void buildFamilies() {
        Map<String, String> parent = new HashMap<>();
        for (String m : methods.keySet()) parent.put(m, m);
        for (ClassNode cn : classes.values()) {
            for (MethodNode m : cn.methods) {
                if ((m.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) != 0 || m.name.startsWith("<")) continue;
                for (String sup : allSupers(cn.name)) {
                    String k = key(sup, m.name, m.desc);
                    if (methods.containsKey(k)) union(parent, key(cn.name, m.name, m.desc), k);
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
    }

    static Set<String> allSupers(String c) {
        Set<String> out = new LinkedHashSet<>();
        Deque<String> d = new ArrayDeque<>(supers.getOrDefault(c, List.of()));
        while (!d.isEmpty()) {
            String s = d.poll();
            if (out.add(s)) d.addAll(supers.getOrDefault(s, List.of()));
        }
        return out;
    }

    static String find(Map<String, String> p, String x) {
        while (!p.get(x).equals(x)) {
            p.put(x, p.get(p.get(x)));
            x = p.get(x);
        }
        return x;
    }

    static void union(Map<String, String> p, String a, String b) {
        String ra = find(p, a), rb = find(p, b);
        if (!ra.equals(rb)) p.put(ra, rb);
    }

    /** The family a call resolves to, or null if it ends outside Minecraft (JDK, libraries). */
    static Integer resolve(String owner, String name, String desc) {
        Deque<String> d = new ArrayDeque<>();
        d.add(owner);
        Set<String> seen = new HashSet<>();
        while (!d.isEmpty()) {
            String c = d.poll();
            if (!seen.add(c)) continue;
            Integer f = family.get(key(c, name, desc));
            if (f != null) return f;
            d.addAll(supers.getOrDefault(c, List.of()));
        }
        return null;
    }

    static String resolveField(String owner, String name) {
        String c = owner;
        while (c != null && classes.containsKey(c)) {
            ClassNode cn = classes.get(c);
            for (FieldNode f : cn.fields) if (f.name.equals(name)) return c + "." + name;
            c = cn.superName;
        }
        return owner + "." + name;
    }

    static void buildIndices() {
        for (Map.Entry<String, MethodNode> e : methods.entrySet()) {
            for (AbstractInsnNode in : e.getValue().instructions) {
                if (in instanceof MethodInsnNode mi) {
                    Integer f = resolve(mi.owner, mi.name, mi.desc);
                    if (f != null) callersOfFamily.computeIfAbsent(f, k -> new HashSet<>()).add(e.getKey());
                } else if (in instanceof FieldInsnNode fi && (fi.getOpcode() == Opcodes.GETFIELD || fi.getOpcode() == Opcodes.GETSTATIC)) {
                    readersOfField.computeIfAbsent(resolveField(fi.owner, fi.name), k -> new HashSet<>()).add(e.getKey());
                }
            }
        }
    }

    // ---- per-method analysis ----
    static final class Changes {
        final Set<String> affected = new HashSet<>();

        void param(int fam, int index) {
            BitSet b = taintedParams.computeIfAbsent(fam, k -> new BitSet());
            if (!b.get(index)) {
                b.set(index);
                affected.addAll(familyMembers.get(fam));
            }
        }

        void ret(int fam) {
            if (taintedReturns.add(fam)) affected.addAll(callersOfFamily.getOrDefault(fam, Set.of()));
        }

        void field(String f) {
            if (taintedFields.add(f)) affected.addAll(readersOfField.getOrDefault(f, Set.of()));
        }
    }

    static final class Stats {
        final Set<String> methodsTouched = new HashSet<>();
        final Set<String> classesTouched = new HashSet<>();
        long intOps, conversions, compares;
        long arrayIndex, intArrayStores;
        final Map<String, Integer> librarySinks = new HashMap<>();
        final Map<String, Integer> sinkKinds = new TreeMap<>();
        long lambdaCaptures;

        void touch(String mk) {
            methodsTouched.add(mk);
            classesTouched.add(mk.substring(0, mk.indexOf('.')));
        }

        void sink(String kind) {
            sinkKinds.merge(kind, 1, Integer::sum);
        }
    }

    static final class TV implements Value {
        final int size;
        final boolean t;

        TV(int size, boolean t) {
            this.size = size;
            this.t = t;
        }

        public int getSize() {
            return size;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof TV v && v.size == size && v.t == t;
        }

        @Override
        public int hashCode() {
            return size * 2 + (t ? 1 : 0);
        }
    }

    static final TV U1 = new TV(1, false), U2 = new TV(2, false), T1 = new TV(1, true), T2 = new TV(2, true);

    static TV of(Type type, boolean t) {
        return type.getSize() == 2 ? (t ? T2 : U2) : (t ? T1 : U1);
    }

    static boolean isInt(Type t) {
        int s = t.getSort();
        return s == Type.INT || s == Type.SHORT || s == Type.BYTE || s == Type.CHAR;
    }

    static void analyze(String mk, Changes ch, Stats st) throws AnalyzerException {
        MethodNode m = methods.get(mk);
        if (m.instructions.size() == 0) return;
        String owner = mk.substring(0, mk.indexOf('.'));
        int fam = family.get(mk);
        BitSet params = taintedParams.getOrDefault(fam, new BitSet());
        boolean isStatic = (m.access & Opcodes.ACC_STATIC) != 0;
        Interpreter<TV> interp = new Interpreter<>(Opcodes.ASM9) {
            int paramIndex = isStatic ? 0 : -1;

            @Override
            public TV newValue(Type type) {
                if (type == null) return U1;
                if (type == Type.VOID_TYPE) return null;
                return of(type, false);
            }

            @Override
            public TV newParameterValue(boolean isInstanceMethod, int local, Type type) {
                // called in parameter order, receiver first
                int idx = paramIndex++;
                return of(type, idx >= 0 && params.get(idx));
            }

            @Override
            public TV newReturnTypeValue(Type type) {
                return type == Type.VOID_TYPE ? null : of(type, false);
            }

            @Override
            public TV newOperation(AbstractInsnNode in) {
                switch (in.getOpcode()) {
                    case Opcodes.LCONST_0, Opcodes.LCONST_1, Opcodes.DCONST_0, Opcodes.DCONST_1:
                        return U2;
                    case Opcodes.LDC: {
                        Object c = ((LdcInsnNode) in).cst;
                        return c instanceof Long || c instanceof Double ? U2 : U1;
                    }
                    case Opcodes.GETSTATIC: {
                        FieldInsnNode fi = (FieldInsnNode) in;
                        return of(Type.getType(fi.desc), taintedFields.contains(resolveField(fi.owner, fi.name)));
                    }
                    default:
                        return U1;
                }
            }

            @Override
            public TV copyOperation(AbstractInsnNode in, TV v) {
                return v;
            }

            @Override
            public TV unaryOperation(AbstractInsnNode in, TV v) {
                int op = in.getOpcode();
                switch (op) {
                    case Opcodes.INEG, Opcodes.IINC, Opcodes.I2B, Opcodes.I2C, Opcodes.I2S, Opcodes.L2I, Opcodes.F2I, Opcodes.D2I, Opcodes.I2F:
                        if (v.t && st != null) {
                            st.conversions++;
                        }
                        return v.t ? T1 : U1;
                    case Opcodes.FNEG, Opcodes.L2F, Opcodes.D2F:
                        return v.t ? T1 : U1;
                    case Opcodes.LNEG, Opcodes.DNEG, Opcodes.I2L, Opcodes.I2D, Opcodes.L2D, Opcodes.F2L, Opcodes.F2D, Opcodes.D2L:
                        return v.t ? T2 : U2;
                    case Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE, Opcodes.IFGT, Opcodes.IFLE:
                        if (v.t && st != null) st.compares++;
                        return null;
                    case Opcodes.TABLESWITCH, Opcodes.LOOKUPSWITCH:
                        if (v.t && st != null) st.sink("switch on a coordinate");
                        return null;
                    case Opcodes.PUTSTATIC: {
                        FieldInsnNode fi = (FieldInsnNode) in;
                        if (v.t) ch.field(resolveField(fi.owner, fi.name));
                        return null;
                    }
                    case Opcodes.GETFIELD: {
                        FieldInsnNode fi = (FieldInsnNode) in;
                        return of(Type.getType(fi.desc), taintedFields.contains(resolveField(fi.owner, fi.name)));
                    }
                    case Opcodes.NEWARRAY, Opcodes.ANEWARRAY:
                        if (v.t && st != null) st.sink("array size from a coordinate");
                        return U1;
                    case Opcodes.IRETURN, Opcodes.LRETURN, Opcodes.FRETURN, Opcodes.DRETURN, Opcodes.ARETURN, Opcodes.ATHROW,
                         Opcodes.IFNULL, Opcodes.IFNONNULL, Opcodes.MONITORENTER, Opcodes.MONITOREXIT:
                        return null;
                    default:
                        return U1; // CHECKCAST, INSTANCEOF, ARRAYLENGTH
                }
            }

            @Override
            public TV binaryOperation(AbstractInsnNode in, TV a, TV b) {
                int op = in.getOpcode();
                switch (op) {
                    case Opcodes.IALOAD, Opcodes.BALOAD, Opcodes.CALOAD, Opcodes.SALOAD, Opcodes.FALOAD, Opcodes.AALOAD:
                        if (b.t && st != null) st.arrayIndex++;
                        return U1;
                    case Opcodes.LALOAD, Opcodes.DALOAD:
                        if (b.t && st != null) st.arrayIndex++;
                        return U2;
                    case Opcodes.IADD, Opcodes.ISUB, Opcodes.IMUL, Opcodes.IDIV, Opcodes.IREM, Opcodes.ISHL, Opcodes.ISHR, Opcodes.IUSHR,
                         Opcodes.IAND, Opcodes.IOR, Opcodes.IXOR:
                        if ((a.t || b.t) && st != null) st.intOps++;
                        return a.t || b.t ? T1 : U1;
                    case Opcodes.FADD, Opcodes.FSUB, Opcodes.FMUL, Opcodes.FDIV, Opcodes.FREM:
                        return a.t || b.t ? T1 : U1;
                    case Opcodes.LADD, Opcodes.LSUB, Opcodes.LMUL, Opcodes.LDIV, Opcodes.LREM, Opcodes.LSHL, Opcodes.LSHR, Opcodes.LUSHR,
                         Opcodes.LAND, Opcodes.LOR, Opcodes.LXOR, Opcodes.DADD, Opcodes.DSUB, Opcodes.DMUL, Opcodes.DDIV, Opcodes.DREM:
                        return a.t || b.t ? T2 : U2;
                    case Opcodes.LCMP, Opcodes.FCMPL, Opcodes.FCMPG, Opcodes.DCMPL, Opcodes.DCMPG:
                        return U1;
                    case Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE, Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE:
                        if ((a.t || b.t) && st != null) st.compares++;
                        return null;
                    case Opcodes.PUTFIELD: {
                        FieldInsnNode fi = (FieldInsnNode) in;
                        if (b.t) ch.field(resolveField(fi.owner, fi.name));
                        return null;
                    }
                    default:
                        return null; // IF_ACMP*
                }
            }

            @Override
            public TV ternaryOperation(AbstractInsnNode in, TV a, TV b, TV c) {
                if (b.t && st != null) st.arrayIndex++;
                if (c.t && st != null && in.getOpcode() == Opcodes.IASTORE) st.intArrayStores++;
                return null;
            }

            @Override
            public TV naryOperation(AbstractInsnNode in, List<? extends TV> vals) {
                if (in instanceof MethodInsnNode mi) {
                    Type[] at = Type.getArgumentTypes(mi.desc);
                    int off = mi.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1;
                    Integer f = resolve(mi.owner, mi.name, mi.desc);
                    boolean anyArg = false;
                    for (int i = 0; i < at.length; i++) {
                        if (vals.get(i + off).t) {
                            anyArg = true;
                            if (f != null) ch.param(f, i);
                        }
                    }
                    Type rt = Type.getReturnType(mi.desc);
                    if (f == null && anyArg && st != null) {
                        String lib = mi.owner + "." + mi.name;
                        st.librarySinks.merge(lib, 1, Integer::sum);
                        st.sink("call into the JDK or a library (" + (mi.owner.startsWith("java/") ? "JDK" : "library") + ")");
                    }
                    if (rt == Type.VOID_TYPE) return null;
                    // A library call's result counts as a coordinate if a coordinate went in (Math.floorDiv, Math.abs...).
                    boolean t = f != null ? taintedReturns.contains(f) : anyArg && (isInt(rt) || rt.getSort() == Type.LONG || rt.getSort() == Type.DOUBLE);
                    return of(rt, t);
                }
                if (in instanceof InvokeDynamicInsnNode indy) {
                    Type rt = Type.getReturnType(indy.desc);
                    Type[] captured = Type.getArgumentTypes(indy.desc);
                    if (indy.bsmArgs.length >= 2 && indy.bsmArgs[1] instanceof Handle h) {
                        Integer f = family.get(key(h.getOwner(), h.getName(), h.getDesc()));
                        int shift = h.getTag() == Opcodes.H_INVOKESTATIC ? 0 : -1; // receiver is captured first
                        for (int i = 0; i < captured.length; i++) {
                            if (vals.get(i).t) {
                                if (st != null) st.lambdaCaptures++;
                                if (f != null && i + shift >= 0) ch.param(f, i + shift);
                            }
                        }
                    }
                    return rt == Type.VOID_TYPE ? null : of(rt, false);
                }
                if (in.getOpcode() == Opcodes.MULTIANEWARRAY) return U1;
                return U1;
            }

            @Override
            public void returnOperation(AbstractInsnNode in, TV v, TV expected) {
                if (v.t) ch.ret(fam);
            }

            @Override
            public TV merge(TV a, TV b) {
                if (a == null || b == null) return a == null ? b : a;
                return a.t || b.t ? (a.size == 2 ? T2 : T1) : a;
            }
        };
        long before = st == null ? 0 : st.intOps + st.conversions + st.compares + st.arrayIndex + st.librarySinks.values().stream().mapToLong(Integer::longValue).sum();
        Analyzer<TV> an = new Analyzer<>(interp);
        an.analyze(owner, m);
        if (st != null) {
            long after = st.intOps + st.conversions + st.compares + st.arrayIndex + st.librarySinks.values().stream().mapToLong(Integer::longValue).sum();
            if (after > before || params.cardinality() > 0 || taintedReturns.contains(fam)) st.touch(mk);
        }
    }

    static void report(Stats st, int analyses, int failures, long ms) {
        long intFields = taintedFields.stream().filter(f -> {
            int dot = f.lastIndexOf('.');
            ClassNode cn = classes.get(f.substring(0, dot));
            if (cn == null) return false;
            for (FieldNode fn : cn.fields) if (fn.name.equals(f.substring(dot + 1))) return isInt(Type.getType(fn.desc));
            return false;
        }).count();
        long intParams = 0, intReturns = 0;
        for (Map.Entry<Integer, BitSet> e : taintedParams.entrySet()) {
            String any = familyMembers.get(e.getKey()).get(0);
            Type[] at = Type.getArgumentTypes(any.substring(any.indexOf(' ') + 1));
            for (int i = e.getValue().nextSetBit(0); i >= 0; i = e.getValue().nextSetBit(i + 1)) {
                if (i < at.length && isInt(at[i])) intParams += familyMembers.get(e.getKey()).size();
            }
        }
        for (int f : taintedReturns) {
            String any = familyMembers.get(f).get(0);
            if (isInt(Type.getReturnType(any.substring(any.indexOf(' ') + 1)))) intReturns += familyMembers.get(f).size();
        }
        System.out.println("=== ASM coordinate audit (X/Z) ===");
        System.out.printf("classes scanned: %d, methods: %d, analyses: %d (%d failed), %.1f s%n",
            classes.size(), methods.size(), analyses, failures, ms / 1000.0);
        System.out.printf("methods that handle an X/Z value: %d in %d classes%n", st.methodsTouched.size(), st.classesTouched.size());
        System.out.printf("int fields holding X/Z: %d (of %d tainted fields)%n", intFields, taintedFields.size());
        System.out.printf("int parameters to widen: %d, int return types to widen: %d%n", intParams, intReturns);
        System.out.printf("int instructions on X/Z: arithmetic %d, conversions %d, comparisons %d%n", st.intOps, st.conversions, st.compares);
        System.out.printf("X/Z used as an array index: %d, stored into int[]: %d, captured by lambdas: %d%n", st.arrayIndex, st.intArrayStores, st.lambdaCaptures);
        System.out.println("places a rewrite cannot simply widen:");
        st.sinkKinds.forEach((k, v) -> System.out.printf("  %7d  %s%n", v, k));
        System.out.println("top JDK/library methods receiving X/Z:");
        st.librarySinks.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(25)
            .forEach(e -> System.out.printf("  %7d  %s%n", e.getValue(), e.getKey()));
    }
}
