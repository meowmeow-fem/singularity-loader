import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.singularitymc.loader.asm.CoordinateWidener;
import net.singularitymc.loader.asm.PositionWidening;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * 3.5.8 dry run: widens PositionWidening's set (scoped mode, twins, carry) plus extra classes or packages, without
 * Mixin, and checks the result: the widener's problems, ASM's BasicVerifier on every method, and that every call or
 * field access from the whole jar into the set still finds its member. Nothing is written or run.
 * Usage: java -cp loader-classes;asm*.jar ScopedDryRun.java server.jar librariesDir [prefix or class ...]
 * A prefix ending in '/' takes every class under it.
 */
public class ScopedDryRun {
    public static void main(String[] a) throws Exception {
        Map<String, byte[]> jar = new LinkedHashMap<>();
        try (ZipFile z = new ZipFile(a[0])) {
            for (ZipEntry e : Collections.list(z.entries())) {
                if (e.getName().startsWith("net/minecraft/") && e.getName().endsWith(".class")) {
                    jar.put(e.getName().substring(0, e.getName().length() - 6), z.getInputStream(e).readAllBytes());
                }
            }
        }
        List<ZipFile> libs = new ArrayList<>();
        try (Stream<Path> s = Files.walk(Path.of(a[1]))) {
            for (Path p : s.filter(p -> p.toString().endsWith(".jar")).toList()) libs.add(new ZipFile(p.toFile()));
        }
        CoordinateWidener.OutsideClasses outside = name -> {
            byte[] b = jar.get(name);
            if (b != null) return b;
            try (InputStream jdk = ClassLoader.getSystemResourceAsStream(name + ".class")) {
                if (jdk != null) return jdk.readAllBytes();
                for (ZipFile lib : libs) {
                    ZipEntry e = lib.getEntry(name + ".class");
                    if (e != null) return lib.getInputStream(e).readAllBytes();
                }
            } catch (IOException e) {
                return null;
            }
            return null;
        };
        Set<String> base = new LinkedHashSet<>(a.length > 2 && a[2].equals("loader")
            ? PositionWidening.scope(jar::get, p -> jar.keySet().stream().filter(c -> c.startsWith(p)).toList())
            : PositionWidening.CLASSES);
        for (int i = 2; i < a.length; i++) {
            if (a[i].equals("loader")) continue;
            String p = a[i];
            if (p.startsWith("-")) {
                base.removeIf(c -> c.startsWith(p.substring(1)) && !PositionWidening.CLASSES.contains(c));
            } else if (p.endsWith("/")) {
                for (String c : jar.keySet()) if (c.startsWith(p)) base.add(c);
            } else {
                base.add(p);
            }
        }
        Set<String> scope = new LinkedHashSet<>(base);
        for (String c : base) {
            byte[] b = jar.get(c);
            if (b == null) {
                System.out.println("missing: " + c);
                continue;
            }
            ClassNode cn = new ClassNode();
            new ClassReader(b).accept(cn, ClassReader.SKIP_CODE);
            if (cn.nestMembers != null) scope.addAll(cn.nestMembers);
        }
        // closure under subclassing: classes outside that extend or implement one in the set
        List<String> openSubclasses = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : jar.entrySet()) {
            if (scope.contains(e.getKey())) continue;
            ClassNode cn = new ClassNode();
            new ClassReader(e.getValue()).accept(cn, ClassReader.SKIP_CODE);
            if (scope.contains(cn.superName) || cn.interfaces.stream().anyMatch(scope::contains)) {
                openSubclasses.add(e.getKey() + " : " + (scope.contains(cn.superName) ? cn.superName : "")
                    + cn.interfaces.stream().filter(scope::contains).toList());
            }
        }
        Map<String, byte[]> in = new LinkedHashMap<>();
        for (String c : scope) if (jar.containsKey(c)) in.put(c, jar.get(c));
        long t0 = System.currentTimeMillis();
        CoordinateWidener.Result r = new CoordinateWidener(in, outside, PositionWidening.TWINS, true, "net/singularitymc/loader/util/WidePacking",
            PositionWidening.CHUNK_SCALED, PositionWidening.QUART_SCALED).notCoordinates(PositionWidening.NOT_COORDINATES).widen(PositionWidening.SEEDS);
        long t1 = System.currentTimeMillis();
        CoordinateWidener.Report rep = r.report();
        String dump = System.getenv("DUMP_DIR");
        if (dump != null) {
            for (Map.Entry<String, byte[]> e : r.classes().entrySet()) {
                Path p = Path.of(dump, e.getKey() + ".class");
                Files.createDirectories(p.getParent());
                Files.write(p, e.getValue());
            }
        }

        Map<String, ClassNode> out = new HashMap<>();
        for (Map.Entry<String, byte[]> e : jar.entrySet()) {
            byte[] b = r.classes().getOrDefault(e.getKey(), e.getValue());
            ClassNode cn = new ClassNode();
            new ClassReader(b).accept(cn, 0);
            out.put(cn.name, cn);
        }
        // overrides that break: a class outside the set overriding a method of the set whose signature was widened
        Set<String> widenedSigs = new HashSet<>();
        for (Map.Entry<String, byte[]> e : r.classes().entrySet()) {
            ClassNode before = new ClassNode();
            new ClassReader(jar.get(e.getKey())).accept(before, ClassReader.SKIP_CODE);
            ClassNode after = new ClassNode();
            new ClassReader(e.getValue()).accept(after, ClassReader.SKIP_CODE);
            Set<String> afterSigs = new HashSet<>();
            for (MethodNode m : after.methods) afterSigs.add(m.name + m.desc);
            for (MethodNode m : before.methods) {
                if ((m.access & (org.objectweb.asm.Opcodes.ACC_PRIVATE | org.objectweb.asm.Opcodes.ACC_STATIC)) != 0) continue;
                for (MethodNode w : after.methods) {
                    if (w.name.equals(m.name) && !w.desc.equals(m.desc) && w.desc.replace('J', 'I').equals(m.desc.replace('J', 'I'))) {
                        widenedSigs.add(e.getKey() + "." + m.name + m.desc);
                    }
                }
            }
        }
        List<String> brokenOverrides = new ArrayList<>();
        for (String sub : openSubclasses) {
            String name = sub.substring(0, sub.indexOf(' '));
            ClassNode cn = new ClassNode();
            new ClassReader(jar.get(name)).accept(cn, ClassReader.SKIP_CODE);
            Deque<String> supers = new ArrayDeque<>();
            if (cn.superName != null) supers.add(cn.superName);
            supers.addAll(cn.interfaces);
            Set<String> seen = new HashSet<>();
            while (!supers.isEmpty()) {
                String s = supers.poll();
                if (!seen.add(s) || !jar.containsKey(s)) continue;
                for (MethodNode m : cn.methods) {
                    if (widenedSigs.contains(s + "." + m.name + m.desc)) brokenOverrides.add(name + "." + m.name + m.desc + " overrides " + s);
                }
                ClassNode sc = new ClassNode();
                new ClassReader(jar.get(s)).accept(sc, ClassReader.SKIP_CODE);
                if (sc.superName != null) supers.add(sc.superName);
                supers.addAll(sc.interfaces);
            }
        }

        int methods = 0, verified = 0;
        Map<String, Integer> verifyErrors = new TreeMap<>();
        List<String> verifySamples = new ArrayList<>();
        Map<String, ClassNode> hierarchy = new HashMap<>(out);
        for (String c : r.classes().keySet()) {
            ClassNode cn = out.get(c);
            for (MethodNode m : cn.methods) {
                if (m.instructions.size() == 0) continue;
                methods++;
                try {
                    // types too (a Player meeting an Entity must stay an Entity), with the game's class hierarchy
                    new Analyzer<>(new JarVerifier(cn, hierarchy, outside)).analyze(c, m);
                    verified++;
                } catch (AnalyzerException ex) {
                    String msg = String.valueOf(ex.getMessage()).replaceAll("\\d+", "#");
                    verifyErrors.merge(msg.length() > 90 ? msg.substring(0, 90) : msg, 1, Integer::sum);
                    if (verifySamples.size() < 10) verifySamples.add(c + "." + m.name + m.desc + ": " + ex.getMessage());
                }
            }
        }
        int refs = 0, dangling = 0;
        List<String> danglingSamples = new ArrayList<>();
        for (ClassNode cn : out.values()) {
            for (MethodNode m : cn.methods) {
                for (AbstractInsnNode insn : m.instructions) {
                    if (insn instanceof MethodInsnNode mi && scope.contains(mi.owner)) {
                        refs++;
                        if (!WidenDryRunSupport.hasMethod(out, mi.owner, mi.name, mi.desc)) {
                            dangling++;
                            if (danglingSamples.size() < 10) danglingSamples.add(cn.name + " -> " + mi.owner + "." + mi.name + mi.desc);
                        }
                    } else if (insn instanceof FieldInsnNode fi && scope.contains(fi.owner)) {
                        refs++;
                        if (!WidenDryRunSupport.hasField(out, fi.owner, fi.name, fi.desc)) {
                            dangling++;
                            if (danglingSamples.size() < 10) danglingSamples.add(cn.name + " -> field " + fi.owner + "." + fi.name + " " + fi.desc);
                        }
                    }
                }
            }
        }
        System.out.printf("scope: %d classes (%d given), widened in %.1fs, %d rounds%n", scope.size(), base.size(), (t1 - t0) / 1000.0, rep.rounds());
        System.out.printf("long fields: %d, rewritten instructions: %d, bridges: %d%n", rep.longFields().size(), rep.rewrittenInstructions(), rep.bridges());
        if (System.getenv("LIST_FIELDS") != null) rep.longFields().stream().sorted().forEach(f -> System.out.println("  field " + f));
        System.out.printf("classes outside extending the set: %d%n", openSubclasses.size());
        openSubclasses.stream().limit(5).forEach(s -> System.out.println("    " + s));
        System.out.printf("of them overriding a widened method (these break): %d%n", brokenOverrides.size());
        brokenOverrides.stream().limit(40).forEach(s -> System.out.println("    " + s));
        System.out.printf("verifier: %d of %d methods pass%n", verified, methods);
        verifyErrors.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(10).forEach(e -> System.out.printf("  %6d  %s%n", e.getValue(), e.getKey()));
        verifySamples.forEach(s -> System.out.println("    e.g. " + s));
        System.out.printf("references into the set: %d, dangling: %d%n", refs, dangling);
        danglingSamples.forEach(s -> System.out.println("    e.g. " + s));
        Map<String, Integer> problemKinds = new TreeMap<>();
        for (String p : rep.problems()) problemKinds.merge(p.replaceAll(" (in|of) \\S+.*$", "").replaceAll("\\d+", "#"), 1, Integer::sum);
        System.out.printf("engine problems: %d%n", rep.problems().size());
        problemKinds.forEach((k, v) -> System.out.printf("  %6d  %s%n", v, k));
        rep.problems().stream().limit(12).forEach(s -> System.out.println("    e.g. " + s));
    }
}

/** ASM's type-checking verifier, with class hierarchy questions answered from the jar, libraries and JDK. */
class JarVerifier extends org.objectweb.asm.tree.analysis.SimpleVerifier {
    private final Map<String, ClassNode> classes;
    private final CoordinateWidener.OutsideClasses outside;

    JarVerifier(ClassNode current, Map<String, ClassNode> classes, CoordinateWidener.OutsideClasses outside) {
        super(org.objectweb.asm.Opcodes.ASM9, org.objectweb.asm.Type.getObjectType(current.name),
            current.superName == null ? null : org.objectweb.asm.Type.getObjectType(current.superName),
            current.interfaces.stream().map(org.objectweb.asm.Type::getObjectType).toList(),
            (current.access & org.objectweb.asm.Opcodes.ACC_INTERFACE) != 0);
        this.classes = classes;
        this.outside = outside;
    }

    private ClassNode node(String name) {
        ClassNode cn = classes.get(name);
        if (cn != null) return cn;
        byte[] b = outside.bytes(name);
        if (b == null) return null;
        cn = new ClassNode();
        new ClassReader(b).accept(cn, ClassReader.SKIP_CODE);
        classes.put(name, cn);
        return cn;
    }

    @Override
    protected boolean isInterface(org.objectweb.asm.Type type) {
        ClassNode cn = node(type.getInternalName());
        return cn != null && (cn.access & org.objectweb.asm.Opcodes.ACC_INTERFACE) != 0;
    }

    @Override
    protected org.objectweb.asm.Type getSuperClass(org.objectweb.asm.Type type) {
        ClassNode cn = node(type.getInternalName());
        return cn == null || cn.superName == null ? null : org.objectweb.asm.Type.getObjectType(cn.superName);
    }

    @Override
    protected boolean isAssignableFrom(org.objectweb.asm.Type type, org.objectweb.asm.Type other) {
        if (type.equals(other)) return true;
        if (type.getSort() != org.objectweb.asm.Type.OBJECT || other.getSort() != org.objectweb.asm.Type.OBJECT) {
            return super.isAssignableFrom(type, other);
        }
        if (type.getInternalName().equals("java/lang/Object") || isInterface(type)) return true; // interfaces: as the JVM verifier
        for (org.objectweb.asm.Type t = other; t != null; t = getSuperClass(t)) {
            if (t.equals(type)) return true;
        }
        return false;
    }

    @Override
    protected Class<?> getClass(org.objectweb.asm.Type type) {
        throw new UnsupportedOperationException("no class loading in the dry run: " + type);
    }
}

class WidenDryRunSupport {
    static boolean hasMethod(Map<String, ClassNode> out, String owner, String name, String desc) {
        Deque<String> d = new ArrayDeque<>(List.of(owner));
        Set<String> seen = new HashSet<>();
        while (!d.isEmpty()) {
            String c = d.poll();
            if (!seen.add(c)) continue;
            ClassNode cn = out.get(c);
            if (cn == null) return true;
            for (MethodNode m : cn.methods) if (m.name.equals(name) && m.desc.equals(desc)) return true;
            if (cn.superName != null) d.add(cn.superName);
            d.addAll(cn.interfaces);
        }
        return false;
    }

    static boolean hasField(Map<String, ClassNode> out, String owner, String name, String desc) {
        String c = owner;
        while (c != null) {
            ClassNode cn = out.get(c);
            if (cn == null) return true;
            for (FieldNode f : cn.fields) if (f.name.equals(name)) return f.desc.equals(desc);
            for (String i : cn.interfaces) if (hasField(out, i, name, desc)) return true;
            c = cn.superName;
        }
        return false;
    }
}
