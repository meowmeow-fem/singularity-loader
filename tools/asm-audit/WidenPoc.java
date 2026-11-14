import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/**
 * Singularity 3.5.1 experiment (docs/ASM_64BIT.md): an ASM pass that turns X/Z coordinates from int into long in a
 * closed set of classes, with no Mixin. Starting from seed fields it finds, to a fixpoint, every local, field,
 * parameter and return value that has to become long, then rewrites the bytecode: int opcodes to long ones,
 * descriptors, local slots (a long takes two), DUP/POP/SWAP shapes, and I2L / L2I where an int meets a long or a long
 * meets an int-only place (array index, shift amount, library call). java.lang.Math calls switch to their long overloads.
 *
 * <p>Usage: java -cp asm.jar;asm-tree.jar;asm-analysis.jar WidenPoc.java inDir outDir owner.field...
 */
public class WidenPoc {
    static final Map<String, ClassNode> classes = new LinkedHashMap<>();
    static final Map<String, MethodNode> methods = new HashMap<>();
    static final Map<String, String> methodOwner = new HashMap<>();
    static final Set<String> longFields = new HashSet<>();
    static final Map<String, BitSet> longParams = new HashMap<>();
    static final Set<String> longReturns = new HashSet<>();
    static final Map<String, Set<Integer>> longSlots = new HashMap<>();
    static boolean changed;
    static final Set<String> MATH_LONG = Set.of("abs", "max", "min", "floorDiv", "floorMod");
    static final int I = 1, L = 2;

    public static void main(String[] a) throws Exception {
        Path in = Path.of(a[0]), out = Path.of(a[1]);
        try (Stream<Path> s = Files.walk(in)) {
            for (Path p : s.filter(p -> p.toString().endsWith(".class")).toList()) {
                ClassNode cn = new ClassNode();
                new ClassReader(Files.readAllBytes(p)).accept(cn, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
                classes.put(cn.name, cn);
                for (MethodNode m : cn.methods) {
                    methods.put(key(cn.name, m.name, m.desc), m);
                    methodOwner.put(key(cn.name, m.name, m.desc), cn.name);
                }
            }
        }
        for (int i = 2; i < a.length; i++) longFields.add(a[i]);
        int rounds = 0;
        do {
            changed = false;
            for (String mk : methods.keySet()) analyze(mk);
            rounds++;
        } while (changed);
        System.out.println("fixpoint after " + rounds + " rounds: long fields " + longFields + ", long returns " + longReturns.size()
            + ", methods with long params " + longParams.size());
        int rewritten = 0;
        for (Map.Entry<String, MethodNode> e : methods.entrySet()) rewritten += rewrite(e.getKey(), e.getValue());
        for (ClassNode cn : classes.values()) {
            for (FieldNode f : cn.fields) if (longFields.contains(cn.name + "." + f.name) && f.desc.equals("I")) f.desc = "J";
            for (MethodNode m : cn.methods) m.desc = newDesc(key(cn.name, m.name, m.desc), m.desc);
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
                @Override
                protected String getCommonSuperClass(String t1, String t2) {
                    return "java/lang/Object";
                }
            };
            cn.accept(cw);
            Path target = out.resolve(cn.name + ".class");
            Files.createDirectories(target.getParent());
            Files.write(target, cw.toByteArray());
        }
        System.out.println("rewrote " + rewritten + " instructions in " + classes.size() + " classes");
    }

    static String key(String owner, String name, String desc) {
        return owner + "." + name + desc;
    }

    // ---- values: size in the ORIGINAL code, int-ness, long-ness, and the instructions that produced them ----
    static final class V implements Value {
        final int size;
        final boolean isInt;
        final boolean t;
        final Set<AbstractInsnNode> src;

        V(int size, boolean isInt, boolean t, Set<AbstractInsnNode> src) {
            this.size = size;
            this.isInt = isInt;
            this.t = t;
            this.src = src;
        }

        public int getSize() {
            return size;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof V v && v.size == size && v.isInt == isInt && v.t == t && v.src.equals(src);
        }

        @Override
        public int hashCode() {
            return Objects.hash(size, isInt, t, src);
        }
    }

    static V val(Type type, boolean t, AbstractInsnNode insn) {
        return new V(type.getSize(), isInt(type), t && isInt(type), insn == null ? Set.of() : Set.of(insn));
    }

    static boolean isInt(Type t) {
        int s = t.getSort();
        return s == Type.INT || s == Type.SHORT || s == Type.BYTE || s == Type.CHAR || s == Type.BOOLEAN;
    }

    static boolean isMathLong(MethodInsnNode mi) {
        return mi.owner.equals("java/lang/Math") && MATH_LONG.contains(mi.name) && mi.desc.contains("I");
    }

    static Frame<V>[] analyze(String mk) throws AnalyzerException {
        MethodNode m = methods.get(mk);
        String owner = methodOwner.get(mk);
        if (m.instructions.size() == 0) return null;
        Set<Integer> slots = longSlots.computeIfAbsent(mk, k -> new HashSet<>());
        BitSet params = longParams.getOrDefault(mk, new BitSet());
        boolean isStatic = (m.access & Opcodes.ACC_STATIC) != 0;
        Type[] args = Type.getArgumentTypes(m.desc);
        int slot = isStatic ? 0 : 1;
        for (int i = 0; i < args.length; i++) {
            if (params.get(i) && slots.add(slot)) changed = true;
            slot += args[i].getSize();
        }
        Interpreter<V> interp = new Interpreter<>(Opcodes.ASM9) {
            @Override
            public V newValue(Type type) {
                if (type == null) return new V(1, false, false, Set.of());
                if (type == Type.VOID_TYPE) return null;
                return val(type, false, null);
            }

            @Override
            public V newParameterValue(boolean isInstanceMethod, int local, Type type) {
                return new V(type.getSize(), isInt(type), isInt(type) && slots.contains(local), Set.of());
            }

            @Override
            public V newOperation(AbstractInsnNode in) {
                return switch (in.getOpcode()) {
                    case Opcodes.LCONST_0, Opcodes.LCONST_1, Opcodes.DCONST_0, Opcodes.DCONST_1 -> new V(2, false, false, Set.of(in));
                    case Opcodes.LDC -> {
                        Object c = ((LdcInsnNode) in).cst;
                        yield new V(c instanceof Long || c instanceof Double ? 2 : 1, c instanceof Integer, false, Set.of(in));
                    }
                    case Opcodes.GETSTATIC -> {
                        FieldInsnNode fi = (FieldInsnNode) in;
                        yield val(Type.getType(fi.desc), longFields.contains(fi.owner + "." + fi.name), in);
                    }
                    case Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2, Opcodes.ICONST_3, Opcodes.ICONST_4,
                         Opcodes.ICONST_5, Opcodes.BIPUSH, Opcodes.SIPUSH -> new V(1, true, false, Set.of(in));
                    default -> new V(1, false, false, Set.of(in));
                };
            }

            @Override
            public V copyOperation(AbstractInsnNode in, V v) {
                int op = in.getOpcode();
                if (op == Opcodes.ILOAD) return new V(1, true, slots.contains(((VarInsnNode) in).var), Set.of(in));
                if (op >= Opcodes.LLOAD && op <= Opcodes.ALOAD) return new V(v.size, false, false, Set.of(in));
                if (op == Opcodes.ISTORE && v.t && slots.add(((VarInsnNode) in).var)) changed = true;
                return v; // stores and DUP/SWAP keep the value (and its producers)
            }

            @Override
            public V unaryOperation(AbstractInsnNode in, V v) {
                int op = in.getOpcode();
                switch (op) {
                    case Opcodes.INEG, Opcodes.L2I:
                        return new V(1, true, v.t, Set.of(in));
                    case Opcodes.IINC:
                        return new V(1, true, slots.contains(((IincInsnNode) in).var), Set.of(in));
                    case Opcodes.I2L:
                        return new V(2, false, false, Set.of(in));
                    case Opcodes.I2B, Opcodes.I2C, Opcodes.I2S, Opcodes.F2I, Opcodes.D2I:
                        return new V(1, true, false, Set.of(in));
                    case Opcodes.I2F, Opcodes.L2F, Opcodes.D2F, Opcodes.FNEG, Opcodes.ARRAYLENGTH, Opcodes.INSTANCEOF:
                        return new V(1, op == Opcodes.ARRAYLENGTH || op == Opcodes.INSTANCEOF, false, Set.of(in));
                    case Opcodes.I2D, Opcodes.L2D, Opcodes.F2L, Opcodes.F2D, Opcodes.D2L, Opcodes.LNEG, Opcodes.DNEG:
                        return new V(2, false, false, Set.of(in));
                    case Opcodes.GETFIELD: {
                        FieldInsnNode fi = (FieldInsnNode) in;
                        return val(Type.getType(fi.desc), longFields.contains(fi.owner + "." + fi.name), in);
                    }
                    case Opcodes.PUTSTATIC: {
                        FieldInsnNode fi = (FieldInsnNode) in;
                        if (v.t && longFields.add(fi.owner + "." + fi.name)) changed = true;
                        return null;
                    }
                    case Opcodes.NEWARRAY, Opcodes.ANEWARRAY, Opcodes.CHECKCAST:
                        return new V(1, false, false, Set.of(in));
                    default:
                        return null; // IFxx, switches, returns, ATHROW, monitors
                }
            }

            @Override
            public V binaryOperation(AbstractInsnNode in, V a, V b) {
                int op = in.getOpcode();
                switch (op) {
                    case Opcodes.IADD, Opcodes.ISUB, Opcodes.IMUL, Opcodes.IDIV, Opcodes.IREM, Opcodes.IAND, Opcodes.IOR, Opcodes.IXOR:
                        return new V(1, true, a.t || b.t, Set.of(in));
                    case Opcodes.ISHL, Opcodes.ISHR, Opcodes.IUSHR:
                        return new V(1, true, a.t, Set.of(in));
                    case Opcodes.IALOAD, Opcodes.BALOAD, Opcodes.CALOAD, Opcodes.SALOAD:
                        return new V(1, true, false, Set.of(in));
                    case Opcodes.LALOAD, Opcodes.DALOAD, Opcodes.LADD, Opcodes.LSUB, Opcodes.LMUL, Opcodes.LDIV, Opcodes.LREM,
                         Opcodes.LSHL, Opcodes.LSHR, Opcodes.LUSHR, Opcodes.LAND, Opcodes.LOR, Opcodes.LXOR,
                         Opcodes.DADD, Opcodes.DSUB, Opcodes.DMUL, Opcodes.DDIV, Opcodes.DREM:
                        return new V(2, false, false, Set.of(in));
                    case Opcodes.LCMP, Opcodes.FCMPL, Opcodes.FCMPG, Opcodes.DCMPL, Opcodes.DCMPG:
                        return new V(1, true, false, Set.of(in));
                    case Opcodes.FALOAD, Opcodes.AALOAD, Opcodes.FADD, Opcodes.FSUB, Opcodes.FMUL, Opcodes.FDIV, Opcodes.FREM:
                        return new V(1, false, false, Set.of(in));
                    case Opcodes.PUTFIELD: {
                        FieldInsnNode fi = (FieldInsnNode) in;
                        if (b.t && longFields.add(fi.owner + "." + fi.name)) changed = true;
                        return null;
                    }
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
                    String callee = key(mi.owner, mi.name, mi.desc);
                    int off = mi.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1;
                    if (methods.containsKey(callee)) {
                        Type[] at = Type.getArgumentTypes(mi.desc);
                        for (int i = 0; i < at.length; i++) {
                            if (vals.get(i + off).t) {
                                BitSet b = longParams.computeIfAbsent(callee, k -> new BitSet());
                                if (!b.get(i)) {
                                    b.set(i);
                                    changed = true;
                                }
                            }
                        }
                        return rt == Type.VOID_TYPE ? null : val(rt, longReturns.contains(callee), in);
                    }
                    if (isMathLong(mi)) {
                        boolean any = vals.stream().anyMatch(v -> v.t);
                        return val(rt, any, in);
                    }
                    return rt == Type.VOID_TYPE ? null : val(rt, false, in);
                }
                if (in instanceof InvokeDynamicInsnNode indy) {
                    Type rt = Type.getReturnType(indy.desc);
                    return rt == Type.VOID_TYPE ? null : val(rt, false, in);
                }
                return new V(1, false, false, Set.of(in));
            }

            @Override
            public void returnOperation(AbstractInsnNode in, V v, V expected) {
                if (v.t && longReturns.add(mk)) changed = true;
            }

            @Override
            public V merge(V a, V b) {
                if (a.equals(b)) return a;
                Set<AbstractInsnNode> s = new HashSet<>(a.src);
                s.addAll(b.src);
                return new V(a.size, a.isInt || b.isInt, a.t || b.t, s);
            }
        };
        Analyzer<V> an = new Analyzer<>(interp);
        return an.analyze(owner, m);
    }

    static String newDesc(String mk, String desc) {
        if (!methods.containsKey(mk)) return desc;
        Type[] at = Type.getArgumentTypes(desc);
        BitSet b = longParams.getOrDefault(mk, new BitSet());
        for (int i = 0; i < at.length; i++) if (b.get(i) && at[i] == Type.INT_TYPE) at[i] = Type.LONG_TYPE;
        Type rt = Type.getReturnType(desc);
        if (longReturns.contains(mk) && rt == Type.INT_TYPE) rt = Type.LONG_TYPE;
        return Type.getMethodDescriptor(rt, at);
    }

    // ---- rewrite ----
    static int rewrite(String mk, MethodNode m) throws AnalyzerException {
        Frame<V>[] frames = analyze(mk);
        if (frames == null) return 0;
        Set<Integer> slots = longSlots.get(mk);
        Map<AbstractInsnNode, Integer> conv = new HashMap<>();
        AbstractInsnNode[] insns = m.instructions.toArray();
        // 1. what representation every int operand needs, and conversions after the producers that disagree
        for (int i = 0; i < insns.length; i++) {
            Frame<V> f = frames[i];
            if (f == null) continue;
            AbstractInsnNode in = insns[i];
            int op = in.getOpcode();
            int n = f.getStackSize();
            switch (op) {
                case Opcodes.IADD, Opcodes.ISUB, Opcodes.IMUL, Opcodes.IDIV, Opcodes.IREM, Opcodes.IAND, Opcodes.IOR, Opcodes.IXOR,
                     Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE, Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE -> {
                    V a = f.getStack(n - 2), b = f.getStack(n - 1);
                    int need = a.t || b.t ? L : I;
                    need(conv, a, need);
                    need(conv, b, need);
                }
                case Opcodes.ISHL, Opcodes.ISHR, Opcodes.IUSHR -> need(conv, f.getStack(n - 1), I);
                case Opcodes.ISTORE -> need(conv, f.getStack(n - 1), slots.contains(((VarInsnNode) in).var) ? L : I);
                case Opcodes.IRETURN -> need(conv, f.getStack(n - 1), longReturns.contains(mk) ? L : I);
                case Opcodes.PUTFIELD, Opcodes.PUTSTATIC -> {
                    FieldInsnNode fi = (FieldInsnNode) in;
                    if (fi.desc.equals("I")) need(conv, f.getStack(n - 1), longFields.contains(fi.owner + "." + fi.name) ? L : I);
                }
                case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESTATIC, Opcodes.INVOKESPECIAL, Opcodes.INVOKEINTERFACE -> {
                    MethodInsnNode mi = (MethodInsnNode) in;
                    Type[] at = Type.getArgumentTypes(mi.desc);
                    int base = n - Arrays.stream(at).mapToInt(t -> 1).sum();
                    String callee = key(mi.owner, mi.name, mi.desc);
                    boolean mathLong = isMathLong(mi) && Arrays.stream(at).anyMatch(t -> true)
                        && java.util.stream.IntStream.range(0, at.length).anyMatch(k -> f.getStack(base + k).t);
                    for (int k = 0; k < at.length; k++) {
                        if (at[k] != Type.INT_TYPE) continue;
                        int want = methods.containsKey(callee) ? (longParams.getOrDefault(callee, new BitSet()).get(k) ? L : I) : (mathLong ? L : I);
                        need(conv, f.getStack(base + k), want);
                    }
                }
                case Opcodes.INVOKEDYNAMIC -> {
                    InvokeDynamicInsnNode indy = (InvokeDynamicInsnNode) in;
                    if (!indy.bsm.getOwner().equals("java/lang/invoke/StringConcatFactory")) {
                        Type[] at = Type.getArgumentTypes(indy.desc);
                        for (int k = 0; k < at.length; k++) if (isInt(at[k])) need(conv, f.getStack(n - at.length + k), I);
                    }
                }
                case Opcodes.IALOAD, Opcodes.LALOAD, Opcodes.FALOAD, Opcodes.DALOAD, Opcodes.AALOAD, Opcodes.BALOAD, Opcodes.CALOAD,
                     Opcodes.SALOAD -> need(conv, f.getStack(n - 1), I);
                case Opcodes.IASTORE, Opcodes.BASTORE, Opcodes.CASTORE, Opcodes.SASTORE -> {
                    need(conv, f.getStack(n - 2), I);
                    need(conv, f.getStack(n - 1), I);
                }
                case Opcodes.LASTORE, Opcodes.FASTORE, Opcodes.DASTORE, Opcodes.AASTORE -> need(conv, f.getStack(n - 2), I);
                case Opcodes.NEWARRAY, Opcodes.ANEWARRAY, Opcodes.TABLESWITCH, Opcodes.LOOKUPSWITCH -> need(conv, f.getStack(n - 1), I);
                default -> { }
            }
        }
        // 2. local slots: params first (as in the new descriptor), then every other slot; a long takes two
        boolean isStatic = (m.access & Opcodes.ACC_STATIC) != 0;
        int[] newIndex = new int[Math.max(m.maxLocals, 1) + 2];
        Arrays.fill(newIndex, -1);
        Set<Integer> wide = new HashSet<>();
        for (AbstractInsnNode in : insns) {
            if (in instanceof VarInsnNode v && (v.getOpcode() == Opcodes.LLOAD || v.getOpcode() == Opcodes.DLOAD
                || v.getOpcode() == Opcodes.LSTORE || v.getOpcode() == Opcodes.DSTORE)) wide.add(v.var);
        }
        int cur = 0, old = 0;
        if (!isStatic) newIndex[old++] = cur++;
        for (Type t : Type.getArgumentTypes(m.desc)) {
            newIndex[old] = cur;
            boolean becomesLong = t == Type.INT_TYPE && slots.contains(old);
            cur += becomesLong ? 2 : t.getSize();
            old += t.getSize();
        }
        for (int s = old; s < m.maxLocals; s++) {
            newIndex[s] = cur;
            cur += wide.contains(s) || slots.contains(s) ? 2 : 1;
        }
        // 3. emit
        InsnList out = new InsnList();
        int count = 0;
        for (int i = 0; i < insns.length; i++) {
            AbstractInsnNode in = insns[i];
            Frame<V> f = frames[i];
            int op = in.getOpcode();
            if (in instanceof FrameNode) continue;
            if (f == null) {
                out.add(in);
                continue;
            }
            int n = f.getStackSize();
            boolean before = out.size() > 0;
            int sizeBefore = out.size();
            if (in instanceof VarInsnNode v) {
                int nv = newIndex[v.var];
                int nop = op;
                if (slots.contains(v.var)) {
                    if (op == Opcodes.ILOAD) nop = Opcodes.LLOAD;
                    if (op == Opcodes.ISTORE) nop = Opcodes.LSTORE;
                }
                out.add(new VarInsnNode(nop, nv));
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
            } else if (op >= Opcodes.IADD && op <= Opcodes.IXOR && isIntArith(op)) {
                boolean longOp = op == Opcodes.ISHL || op == Opcodes.ISHR || op == Opcodes.IUSHR
                    ? rep(conv, f.getStack(n - 2)) == L
                    : rep(conv, f.getStack(n - 1)) == L;
                out.add(new InsnNode(longOp ? op + 1 : op)); // every L-opcode is the I-opcode + 1
            } else if (op == Opcodes.INEG) {
                out.add(new InsnNode(rep(conv, f.getStack(n - 1)) == L ? Opcodes.LNEG : op));
            } else if (op >= Opcodes.IF_ICMPEQ && op <= Opcodes.IF_ICMPLE && rep(conv, f.getStack(n - 1)) == L) {
                out.add(new InsnNode(Opcodes.LCMP));
                out.add(new JumpInsnNode(op - Opcodes.IF_ICMPEQ + Opcodes.IFEQ, ((JumpInsnNode) in).label));
            } else if (op >= Opcodes.IFEQ && op <= Opcodes.IFLE && f.getStack(n - 1).isInt && rep(conv, f.getStack(n - 1)) == L) {
                out.add(new InsnNode(Opcodes.LCONST_0));
                out.add(new InsnNode(Opcodes.LCMP));
                out.add(new JumpInsnNode(op, ((JumpInsnNode) in).label));
            } else if ((op == Opcodes.I2L || op == Opcodes.L2I) && f.getStack(n - 1).isInt && rep(conv, f.getStack(n - 1)) == L) {
                // already a long: nothing to do
            } else if (op == Opcodes.L2I && f.getStack(n - 1).size == 2 && rep(conv, f.getStack(n - 1)) == L) {
                out.add(in);
            } else if ((op == Opcodes.I2D || op == Opcodes.I2F) && rep(conv, f.getStack(n - 1)) == L) {
                out.add(new InsnNode(op == Opcodes.I2D ? Opcodes.L2D : Opcodes.L2F));
            } else if ((op == Opcodes.I2B || op == Opcodes.I2C || op == Opcodes.I2S) && rep(conv, f.getStack(n - 1)) == L) {
                out.add(new InsnNode(Opcodes.L2I));
                out.add(new InsnNode(op));
            } else if (op == Opcodes.IRETURN && longReturns.contains(mk)) {
                out.add(new InsnNode(Opcodes.LRETURN));
            } else if (in instanceof FieldInsnNode fi && fi.desc.equals("I") && longFields.contains(fi.owner + "." + fi.name)) {
                out.add(new FieldInsnNode(op, fi.owner, fi.name, "J"));
            } else if (in instanceof MethodInsnNode mi) {
                String callee = key(mi.owner, mi.name, mi.desc);
                String desc = mi.desc;
                if (methods.containsKey(callee)) {
                    desc = newDesc(callee, mi.desc);
                } else if (isMathLong(mi)) {
                    Type[] at = Type.getArgumentTypes(mi.desc);
                    int base = n - at.length;
                    boolean any = false;
                    for (int k = 0; k < at.length; k++) any |= rep(conv, f.getStack(base + k)) == L;
                    if (any) desc = mi.desc.replace('I', 'J');
                }
                out.add(new MethodInsnNode(op, mi.owner, mi.name, desc, mi.itf));
            } else if (in instanceof InvokeDynamicInsnNode indy && indy.bsm.getOwner().equals("java/lang/invoke/StringConcatFactory")) {
                Type[] at = Type.getArgumentTypes(indy.desc);
                for (int k = 0; k < at.length; k++) {
                    if (at[k] == Type.INT_TYPE && rep(conv, f.getStack(n - at.length + k)) == L) at[k] = Type.LONG_TYPE;
                }
                out.add(new InvokeDynamicInsnNode(indy.name, Type.getMethodDescriptor(Type.getReturnType(indy.desc), at), indy.bsm, indy.bsmArgs));
            } else if (op == Opcodes.POP || op == Opcodes.DUP || op == Opcodes.DUP_X1 || op == Opcodes.SWAP) {
                stackOp(out, op, f, n, conv);
            } else if ((op == Opcodes.POP2 || op == Opcodes.DUP2 || op == Opcodes.DUP2_X1 || op == Opcodes.DUP_X2 || op == Opcodes.DUP2_X2)
                && changesSize(f, n, conv, op == Opcodes.DUP_X2 || op == Opcodes.DUP2_X1 ? 3 : op == Opcodes.DUP2_X2 ? 4 : 2)) {
                throw new IllegalStateException("not handled: " + Printer(op) + " over a widened value in " + mk);
            } else {
                out.add(in);
            }
            if (out.size() != sizeBefore && !(in instanceof LabelNode) && !(in instanceof LineNumberNode)) count++;
            Integer c = conv.get(in);
            if (c != null) out.add(new InsnNode(c == L ? Opcodes.I2L : Opcodes.L2I));
        }
        m.instructions = out;
        m.localVariables = null;
        m.maxLocals = cur + 4;
        return count;
    }

    static String Printer(int op) {
        return org.objectweb.asm.util.Printer.OPCODES[op];
    }

    static boolean isIntArith(int op) {
        return switch (op) {
            case Opcodes.IADD, Opcodes.ISUB, Opcodes.IMUL, Opcodes.IDIV, Opcodes.IREM, Opcodes.ISHL, Opcodes.ISHR, Opcodes.IUSHR,
                 Opcodes.IAND, Opcodes.IOR, Opcodes.IXOR -> true;
            default -> false;
        };
    }

    /** The representation a value ends up in: long if it is one, or if a conversion to long follows its producers. */
    static int rep(Map<AbstractInsnNode, Integer> conv, V v) {
        if (!v.isInt) return I;
        for (AbstractInsnNode p : v.src) {
            Integer c = conv.get(p);
            if (c != null) return c;
        }
        return v.t ? L : I;
    }

    static int size(Map<AbstractInsnNode, Integer> conv, V v) {
        return v.isInt && rep(conv, v) == L ? 2 : v.size;
    }

    static void need(Map<AbstractInsnNode, Integer> conv, V v, int want) {
        if (!v.isInt) return;
        int have = v.t ? L : I;
        if (have == want) return;
        if (v.src.isEmpty()) throw new IllegalStateException("cannot convert a parameter in place");
        for (AbstractInsnNode p : v.src) {
            Integer prev = conv.put(p, want);
            if (prev != null && prev != want) throw new IllegalStateException("a value is needed both as int and as long");
        }
    }

    static boolean changesSize(Frame<V> f, int n, Map<AbstractInsnNode, Integer> conv, int depth) {
        for (int k = 1; k <= Math.min(depth, n); k++) if (f.getStack(n - k).isInt && size(conv, f.getStack(n - k)) == 2) return true;
        return false;
    }

    static void stackOp(InsnList out, int op, Frame<V> f, int n, Map<AbstractInsnNode, Integer> conv) {
        int s1 = size(conv, f.getStack(n - 1));
        int s2 = n >= 2 ? size(conv, f.getStack(n - 2)) : 1;
        switch (op) {
            case Opcodes.POP -> out.add(new InsnNode(s1 == 2 ? Opcodes.POP2 : Opcodes.POP));
            case Opcodes.DUP -> out.add(new InsnNode(s1 == 2 ? Opcodes.DUP2 : Opcodes.DUP));
            case Opcodes.DUP_X1 -> out.add(new InsnNode(s1 == 1 ? (s2 == 1 ? Opcodes.DUP_X1 : Opcodes.DUP_X2) : (s2 == 1 ? Opcodes.DUP2_X1 : Opcodes.DUP2_X2)));
            case Opcodes.SWAP -> {
                if (s1 == 1 && s2 == 1) {
                    out.add(new InsnNode(Opcodes.SWAP));
                } else if (s1 == 2 && s2 == 1) {
                    out.add(new InsnNode(Opcodes.DUP2_X1));
                    out.add(new InsnNode(Opcodes.POP2));
                } else if (s1 == 1) {
                    out.add(new InsnNode(Opcodes.DUP_X2));
                    out.add(new InsnNode(Opcodes.POP));
                } else {
                    out.add(new InsnNode(Opcodes.DUP2_X2));
                    out.add(new InsnNode(Opcodes.POP2));
                }
            }
            default -> throw new IllegalStateException();
        }
    }
}
