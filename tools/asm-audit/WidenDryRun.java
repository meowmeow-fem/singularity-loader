import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.singularitymc.loader.asm.CoordinateWidener;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * 3.5.1 dry run (docs/ROADMAP_64BIT.md): widens X/Z in the whole server jar with CoordinateWidener, then checks the
 * result: every method through ASM's BasicVerifier, and every call / field access against what exists afterwards.
 * Nothing is written or run. Usage: java -cp loader-classes;asm*.jar WidenDryRun.java server.jar librariesDir
 */
public class WidenDryRun {
    public static void main(String[] a) throws Exception {
        long t0 = System.currentTimeMillis();
        Map<String, byte[]> in = new LinkedHashMap<>();
        try (ZipFile z = new ZipFile(a[0])) {
            for (ZipEntry e : Collections.list(z.entries())) {
                if (e.getName().startsWith("net/minecraft/") && e.getName().endsWith(".class")) {
                    in.put(e.getName().substring(0, e.getName().length() - 6), z.getInputStream(e).readAllBytes());
                }
            }
        }
        List<ZipFile> libs = new ArrayList<>();
        try (Stream<Path> s = Files.list(Path.of(a[1]))) {
            for (Path p : s.filter(p -> p.toString().endsWith(".jar")).toList()) libs.add(new ZipFile(p.toFile()));
        }
        CoordinateWidener.OutsideClasses outside = name -> {
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
        CoordinateWidener w = new CoordinateWidener(in, outside);
        long t1 = System.currentTimeMillis();
        CoordinateWidener.Result r = w.widen(List.of("net/minecraft/core/Vec3i.x", "net/minecraft/core/Vec3i.z",
            "net/minecraft/world/level/ChunkPos.x", "net/minecraft/world/level/ChunkPos.z"));
        long t2 = System.currentTimeMillis();
        CoordinateWidener.Report rep = r.report();

        // Verify
        Map<String, ClassNode> out = new HashMap<>();
        for (Map.Entry<String, byte[]> e : r.classes().entrySet()) {
            ClassNode cn = new ClassNode();
            new ClassReader(e.getValue()).accept(cn, 0);
            out.put(cn.name, cn);
        }
        int methods = 0, verified = 0, changedClasses = 0;
        Map<String, Integer> verifyErrors = new TreeMap<>();
        List<String> verifySamples = new ArrayList<>();
        for (Map.Entry<String, ClassNode> e : out.entrySet()) {
            if (!Arrays.equals(in.get(e.getKey()), r.classes().get(e.getKey()))) changedClasses++;
            for (MethodNode m : e.getValue().methods) {
                if (m.instructions.size() == 0) continue;
                methods++;
                try {
                    new Analyzer<>(new BasicVerifier()).analyze(e.getKey(), m);
                    verified++;
                } catch (AnalyzerException ex) {
                    String msg = String.valueOf(ex.getMessage()).replaceAll("\\d+", "#");
                    verifyErrors.merge(msg.length() > 90 ? msg.substring(0, 90) : msg, 1, Integer::sum);
                    if (verifySamples.size() < 8) verifySamples.add(e.getKey() + "." + m.name + m.desc + ": " + ex.getMessage());
                }
            }
        }
        // Dangling references: calls and field accesses into the jar that no longer match a member
        int refs = 0, dangling = 0;
        List<String> danglingSamples = new ArrayList<>();
        for (ClassNode cn : out.values()) {
            for (MethodNode m : cn.methods) {
                for (AbstractInsnNode insn : m.instructions) {
                    if (insn instanceof MethodInsnNode mi && out.containsKey(mi.owner)) {
                        refs++;
                        if (!hasMethod(out, mi.owner, mi.name, mi.desc)) {
                            dangling++;
                            if (danglingSamples.size() < 8) danglingSamples.add(cn.name + " -> " + mi.owner + "." + mi.name + mi.desc);
                        }
                    } else if (insn instanceof FieldInsnNode fi && out.containsKey(fi.owner)) {
                        refs++;
                        if (!hasField(out, fi.owner, fi.name, fi.desc)) {
                            dangling++;
                            if (danglingSamples.size() < 8) danglingSamples.add(cn.name + " -> field " + fi.owner + "." + fi.name + " " + fi.desc);
                        }
                    }
                }
            }
        }
        System.out.println("=== 3.5.1 dry run: X/Z to long over the 26.3 server jar ===");
        System.out.printf("classes: %d (changed: %d), load %.1fs, widen %.1fs (%d rounds)%n", in.size(), changedClasses,
            (t1 - t0) / 1000.0, (t2 - t1) / 1000.0, rep.rounds());
        System.out.printf("long fields: %d, long parameters: %d, long return families: %d, rewritten instructions: %d, bridges: %d%n",
            rep.longFields().size(), rep.longParameters(), rep.longReturns(), rep.rewrittenInstructions(), rep.bridges());
        System.out.printf("verifier: %d of %d methods pass (%.2f%%)%n", verified, methods, 100.0 * verified / methods);
        verifyErrors.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(10)
            .forEach(e -> System.out.printf("  %6d  %s%n", e.getValue(), e.getKey()));
        verifySamples.forEach(s -> System.out.println("    e.g. " + s));
        System.out.printf("references into the jar: %d, dangling after the rewrite: %d%n", refs, dangling);
        danglingSamples.forEach(s -> System.out.println("    e.g. " + s));
        Map<String, Integer> problemKinds = new TreeMap<>();
        for (String p : rep.problems()) problemKinds.merge(p.replaceAll(" (in|of) \\S+.*$", "").replaceAll("\\d+", "#"), 1, Integer::sum);
        System.out.printf("engine problems: %d%n", rep.problems().size());
        problemKinds.forEach((k, v) -> System.out.printf("  %6d  %s%n", v, k));
        rep.problems().stream().limit(5).forEach(s -> System.out.println("    e.g. " + s));
        System.out.printf("total %.1fs%n", (System.currentTimeMillis() - t0) / 1000.0);
    }

    static boolean hasMethod(Map<String, ClassNode> out, String owner, String name, String desc) {
        Deque<String> d = new ArrayDeque<>(List.of(owner));
        Set<String> seen = new HashSet<>();
        while (!d.isEmpty()) {
            String c = d.poll();
            if (!seen.add(c)) continue;
            ClassNode cn = out.get(c);
            if (cn == null) return true; // reaches outside the jar: inherited from the JDK/a library
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
