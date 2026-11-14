import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Checks that the Mixins of a compiled mod still find their targets in a given Minecraft jar: every {@code @Mixin}
 * class, every {@code method = "..."} of an injector, every {@code @Shadow} / {@code @Accessor} / {@code @Overwrite}
 * member. Optional hooks ({@code require = 0}) are reported as "optional". Nothing is run.
 * Usage: java -cp asm*.jar MixinTargetCheck.java mixin-classes-dir minecraft.jar
 */
public class MixinTargetCheck {
    public static void main(String[] a) throws Exception {
        Map<String, ClassNode> game = new HashMap<>();
        try (ZipFile z = new ZipFile(a[1])) {
            for (ZipEntry e : java.util.Collections.list(z.entries())) {
                if (e.getName().endsWith(".class") && e.getName().startsWith("net/minecraft/")) {
                    ClassNode cn = new ClassNode();
                    new ClassReader(z.getInputStream(e).readAllBytes()).accept(cn, ClassReader.SKIP_CODE);
                    game.put(cn.name, cn);
                }
            }
        }
        int missing = 0, optional = 0, ok = 0;
        List<Path> files;
        try (Stream<Path> s = Files.walk(Path.of(a[0]))) {
            files = s.filter(p -> p.toString().endsWith(".class")).toList();
        }
        for (Path p : files) {
            ClassNode mixin = new ClassNode();
            new ClassReader(Files.readAllBytes(p)).accept(mixin, ClassReader.SKIP_CODE);
            List<String> targets = mixinTargets(mixin);
            if (targets.isEmpty()) continue;
            for (String t : targets) {
                ClassNode target = game.get(t);
                if (target == null) {
                    System.out.println("MISSING class " + t + "  (" + mixin.name + ")");
                    missing++;
                    continue;
                }
                for (MethodNode m : mixin.methods) {
                    for (AnnotationNode an : annotations(m.visibleAnnotations, m.invisibleAnnotations)) {
                        String kind = an.desc.substring(an.desc.lastIndexOf('/') + 1, an.desc.length() - 1);
                        boolean opt = Integer.valueOf(0).equals(value(an, "require"));
                        List<String> wanted = new ArrayList<>();
                        Object method = value(an, "method");
                        if (method instanceof List<?> l) l.forEach(x -> wanted.add((String) x));
                        if (kind.equals("Shadow") || kind.equals("Overwrite")) wanted.add(m.name + m.desc);
                        if (kind.equals("Accessor") || kind.equals("Invoker")) {
                            Object v = value(an, "value");
                            String name = v instanceof String s && !s.isEmpty() ? s : accessorName(m.name);
                            if (kind.equals("Accessor")) {
                                if (field(game, target, name) == null) {
                                    System.out.println((opt ? "optional " : "MISSING ") + "field " + t + "." + name + "  (" + mixin.name + ")");
                                    if (opt) optional++; else missing++;
                                } else ok++;
                                continue;
                            }
                            wanted.add(name);
                        }
                        for (String w : wanted) {
                            if (method(game, target, w)) {
                                if (kind.equals("Inject")) {
                                    String bad = injectHandlerMismatch(game, target, w, m);
                                    if (bad != null) {
                                        System.out.println((opt ? "optional " : "MISSING ") + "Inject handler " + t + "." + w + ": " + bad + "  (" + mixin.name + "." + m.name + ")");
                                        if (opt) optional++; else missing++;
                                        continue;
                                    }
                                }
                                ok++;
                            } else {
                                System.out.println((opt ? "optional " : "MISSING ") + kind + " " + t + "." + w + "  (" + mixin.name + ")");
                                if (opt) optional++; else missing++;
                            }
                        }
                        // @At(target = "Lowner;name(desc)") of Minecraft members
                        for (String at : atTargets(an)) {
                            int semi = at.indexOf(';');
                            String owner = at.substring(1, semi);
                            if (!owner.startsWith("net/minecraft/")) continue;
                            ClassNode oc = game.get(owner);
                            String member = at.substring(semi + 1);
                            boolean found = oc != null && (member.contains("(") ? method(game, oc, member)
                                : field(game, oc, member.contains(":") ? member.substring(0, member.indexOf(':')) : member) != null);
                            if (found) {
                                ok++;
                            } else {
                                System.out.println((opt ? "optional " : "MISSING ") + "@At " + at + "  (" + mixin.name + "." + m.name + ")");
                                if (opt) optional++; else missing++;
                            }
                        }
                    }
                }
                for (FieldNode f : mixin.fields) {
                    for (AnnotationNode an : annotations(f.visibleAnnotations, f.invisibleAnnotations)) {
                        if (!an.desc.endsWith("/Shadow;")) continue;
                        if (field(game, target, f.name) == null) {
                            System.out.println("MISSING field " + t + "." + f.name + "  (" + mixin.name + ")");
                            missing++;
                        } else ok++;
                    }
                }
            }
        }
        System.out.println("found: " + ok + ", missing: " + missing + ", optional missing: " + optional);
        if (missing > 0) System.exit(1);
    }

    private static List<String> mixinTargets(ClassNode mixin) {
        List<String> out = new ArrayList<>();
        for (AnnotationNode an : annotations(mixin.visibleAnnotations, mixin.invisibleAnnotations)) {
            if (!an.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) continue;
            if (value(an, "value") instanceof List<?> l) l.forEach(x -> out.add(((Type) x).getInternalName()));
            if (value(an, "targets") instanceof List<?> l) l.forEach(x -> out.add(((String) x).replace('.', '/')));
        }
        return out;
    }

    private static List<String> atTargets(AnnotationNode an) {
        List<String> out = new ArrayList<>();
        collectAt(an, out);
        return out;
    }

    private static void collectAt(Object v, List<String> out) {
        if (v instanceof AnnotationNode n) {
            if (n.desc.equals("Lorg/spongepowered/asm/mixin/injection/At;") && value(n, "target") instanceof String s && s.startsWith("L") && s.contains(";")) {
                out.add(s);
            }
            if (n.values != null) for (int i = 1; i < n.values.size(); i += 2) collectAt(n.values.get(i), out);
        } else if (v instanceof List<?> l) {
            for (Object o : l) collectAt(o, out);
        }
    }

    private static List<AnnotationNode> annotations(List<AnnotationNode> a, List<AnnotationNode> b) {
        List<AnnotationNode> out = new ArrayList<>();
        if (a != null) out.addAll(a);
        if (b != null) out.addAll(b);
        return out;
    }

    private static Object value(AnnotationNode an, String key) {
        if (an.values == null) return null;
        for (int i = 0; i < an.values.size(); i += 2) {
            if (an.values.get(i).equals(key)) return an.values.get(i + 1);
        }
        return null;
    }

    private static String accessorName(String m) {
        String s = m.replaceFirst("^(get|set|is|call|invoke)", "");
        if (s.startsWith("singularity$")) s = s.substring("singularity$".length());
        return s.isEmpty() ? m : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    /** A method reference as Mixin takes it: "name", "name(desc)", "nameDesc", or "Lowner;name(desc)". */
    private static boolean method(Map<String, ClassNode> game, ClassNode target, String ref) {
        String r = ref.startsWith("L") && ref.contains(";") ? ref.substring(ref.indexOf(';') + 1) : ref;
        int paren = r.indexOf('(');
        String name = paren < 0 ? r : r.substring(0, paren);
        String desc = paren < 0 ? null : r.substring(paren);
        if (name.endsWith("*")) name = name.substring(0, name.length() - 1); // "name*": every overload
        for (ClassNode c = target; c != null; c = game.get(c.superName)) {
            for (MethodNode m : c.methods) {
                if (m.name.equals(name) && (desc == null || m.desc.equals(desc))) return true;
            }
            for (String i : c.interfaces) {
                ClassNode ic = game.get(i);
                if (ic != null) for (MethodNode m : ic.methods) {
                    if (m.name.equals(name) && (desc == null || m.desc.equals(desc))) return true;
                }
            }
        }
        return false;
    }

    /**
     * An {@code @Inject} handler takes either no target arguments or all of them, in order, before its CallbackInfo
     * (captured locals may follow). Returns null if at least one target method of that name fits, else a description.
     */
    private static String injectHandlerMismatch(Map<String, ClassNode> game, ClassNode target, String ref, MethodNode handler) {
        String r = ref.startsWith("L") && ref.contains(";") ? ref.substring(ref.indexOf(';') + 1) : ref;
        int paren = r.indexOf('(');
        String name = paren < 0 ? r : r.substring(0, paren);
        String desc = paren < 0 ? null : r.substring(paren);
        if (name.equals("<init>*")) name = "<init>";
        Type[] h = Type.getArgumentTypes(handler.desc);
        int ci = -1;
        for (int i = 0; i < h.length; i++) {
            if (h[i].getInternalName().startsWith("org/spongepowered/asm/mixin/injection/callback/CallbackInfo")) {
                ci = i;
                break;
            }
        }
        if (ci < 0) return "handler has no CallbackInfo";
        String seen = null;
        for (MethodNode tm : target.methods) {
            if (!tm.name.equals(name) || (desc != null && !tm.desc.equals(desc))) continue;
            Type[] a = Type.getArgumentTypes(tm.desc);
            if (ci == 0) return null;
            if (ci == a.length) {
                boolean same = true;
                for (int i = 0; i < a.length; i++) same &= a[i].equals(h[i]);
                if (same) return null;
            }
            seen = tm.desc;
        }
        return seen == null ? null : "target is " + name + seen + ", handler takes " + handler.desc;
    }

    private static FieldNode field(Map<String, ClassNode> game, ClassNode target, String name) {
        for (ClassNode c = target; c != null; c = game.get(c.superName)) {
            for (FieldNode f : c.fields) if (f.name.equals(name)) return f;
        }
        return null;
    }
}
