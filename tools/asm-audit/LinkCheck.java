import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Checks that compiled code still links against a given Minecraft jar: every class, method and field of
 * {@code net/minecraft/} it references (calls, field accesses, method references, casts, descriptors, supertypes) must
 * exist there, looked up through superclasses and interfaces. Members inherited from outside the game (Object, a JDK
 * interface, a library) are not checked. Nothing is run. Complements {@code MixinTargetCheck}, which checks the Mixin
 * annotations themselves.
 * Usage: java -cp asm.jar;asm-tree.jar LinkCheck.java classes-dir-or-jar minecraft.jar [prefix-to-skip ...]
 */
public class LinkCheck {
    static Map<String, ClassNode> game = new HashMap<>();
    static final java.util.Set<String> OBJECT_METHODS = java.util.Set.of(
        "hashCode", "equals", "toString", "getClass", "clone", "finalize", "notify", "notifyAll", "wait");

    public static void main(String[] a) throws Exception {
        try (ZipFile z = new ZipFile(a[1])) {
            for (ZipEntry e : Collections.list(z.entries())) {
                if (e.getName().endsWith(".class") && e.getName().startsWith("net/minecraft/")) {
                    ClassNode cn = new ClassNode();
                    new ClassReader(z.getInputStream(e).readAllBytes()).accept(cn, ClassReader.SKIP_CODE);
                    game.put(cn.name, cn);
                }
            }
        }
        List<String> skip = new ArrayList<>();
        for (int i = 2; i < a.length; i++) skip.add(a[i].replace('.', '/'));
        Map<String, TreeSet<String>> missing = new TreeMap<>();
        int checked = 0;
        for (byte[] bytes : classes(Path.of(a[0]))) {
            ClassNode cn = new ClassNode();
            new ClassReader(bytes).accept(cn, 0);
            if (skip.stream().anyMatch(cn.name::startsWith)) continue;
            List<String> problems = new ArrayList<>();
            type(cn.superName, problems);
            for (String i : cn.interfaces) type(i, problems);
            for (FieldNode f : cn.fields) desc(f.desc, problems);
            for (MethodNode m : cn.methods) {
                desc(m.desc, problems);
                for (AbstractInsnNode in : m.instructions) {
                    checked++;
                    if (in instanceof MethodInsnNode mi) method(mi.owner, mi.name, mi.desc, problems);
                    else if (in instanceof FieldInsnNode fi) field(fi.owner, fi.name, fi.desc, problems);
                    else if (in instanceof TypeInsnNode ti) type(ti.desc.startsWith("[") ? Type.getType(ti.desc).getElementType().getInternalName() : ti.desc, problems);
                    else if (in instanceof LdcInsnNode li && li.cst instanceof Type t) desc(t.getDescriptor(), problems);
                    else if (in instanceof InvokeDynamicInsnNode id) {
                        desc(id.desc, problems);
                        for (Object arg : id.bsmArgs) {
                            if (arg instanceof Handle h) {
                                if (h.getTag() <= 4) field(h.getOwner(), h.getName(), h.getDesc(), problems);
                                else method(h.getOwner(), h.getName(), h.getDesc(), problems);
                            } else if (arg instanceof Type t) desc(t.getDescriptor(), problems);
                        }
                    }
                }
            }
            if (!problems.isEmpty()) missing.computeIfAbsent(cn.name, k -> new TreeSet<>()).addAll(problems);
        }
        int n = 0;
        for (var e : missing.entrySet()) {
            System.out.println(e.getKey());
            for (String p : e.getValue()) {
                System.out.println("    MISSING " + p);
                n++;
            }
        }
        System.out.println("instructions checked: " + checked + ", classes with problems: " + missing.size() + ", missing references: " + n);
        if (n > 0) System.exit(1);
    }

    static List<byte[]> classes(Path p) throws IOException {
        List<byte[]> out = new ArrayList<>();
        if (Files.isDirectory(p)) {
            try (Stream<Path> s = Files.walk(p)) {
                for (Path f : s.filter(x -> x.toString().endsWith(".class")).toList()) out.add(Files.readAllBytes(f));
            }
        } else {
            try (ZipFile z = new ZipFile(p.toFile())) {
                for (ZipEntry e : Collections.list(z.entries())) {
                    if (e.getName().endsWith(".class") && !e.getName().startsWith("META-INF/")) out.add(z.getInputStream(e).readAllBytes());
                }
            }
        }
        return out;
    }

    static boolean mc(String owner) {
        return owner != null && owner.startsWith("net/minecraft/");
    }

    static void type(String internal, List<String> problems) {
        if (internal == null) return;
        if (internal.startsWith("[")) internal = Type.getType(internal).getElementType().getInternalName();
        if (mc(internal) && !game.containsKey(internal)) problems.add("class " + internal);
    }

    static void desc(String d, List<String> problems) {
        if (d.startsWith("(")) {
            for (Type t : Type.getArgumentTypes(d)) desc(t.getDescriptor(), problems);
            desc(Type.getReturnType(d).getDescriptor(), problems);
            return;
        }
        Type t = Type.getType(d);
        if (t.getSort() == Type.ARRAY) t = t.getElementType();
        if (t.getSort() == Type.OBJECT) type(t.getInternalName(), problems);
    }

    static void method(String owner, String name, String d, List<String> problems) {
        if (owner.startsWith("[")) return;
        if (!mc(owner)) return;
        ClassNode c = game.get(owner);
        if (c == null) {
            problems.add("class " + owner);
            return;
        }
        desc(d, problems);
        Boolean found = findMethod(c, name, d, new java.util.HashSet<>());
        if (Boolean.FALSE.equals(found)) problems.add("method " + owner + "." + name + d);
    }

    /** TRUE found, FALSE not found anywhere in the game, null: the hierarchy leaves the game (cannot tell). */
    static Boolean findMethod(ClassNode c, String name, String d, java.util.Set<String> seen) {
        if (!seen.add(c.name)) return Boolean.FALSE;
        for (MethodNode m : c.methods) if (m.name.equals(name) && m.desc.equals(d)) return Boolean.TRUE;
        boolean unknown = false;
        List<String> ups = new ArrayList<>(c.interfaces);
        if (c.superName != null) ups.add(0, c.superName);
        for (String up : ups) {
            ClassNode u = game.get(up);
            if (u == null) {
                // Object's own methods are known; any other class outside the game may declare the method
                if (up.equals("java/lang/Object") ? OBJECT_METHODS.contains(name) : true) unknown = true;
                continue;
            }
            Boolean r = findMethod(u, name, d, seen);
            if (Boolean.TRUE.equals(r)) return r;
            if (r == null) unknown = true;
        }
        return unknown ? null : Boolean.FALSE;
    }

    static void field(String owner, String name, String d, List<String> problems) {
        if (!mc(owner)) return;
        ClassNode c = game.get(owner);
        if (c == null) {
            problems.add("class " + owner);
            return;
        }
        desc(d, problems);
        Boolean found = findField(c, name, d, new java.util.HashSet<>());
        if (Boolean.FALSE.equals(found)) problems.add("field " + owner + "." + name + ":" + d);
    }

    static Boolean findField(ClassNode c, String name, String d, java.util.Set<String> seen) {
        if (!seen.add(c.name)) return Boolean.FALSE;
        for (FieldNode f : c.fields) if (f.name.equals(name) && f.desc.equals(d)) return Boolean.TRUE;
        boolean unknown = false;
        List<String> ups = new ArrayList<>(c.interfaces);
        if (c.superName != null) ups.add(0, c.superName);
        for (String up : ups) {
            ClassNode u = game.get(up);
            if (u == null) {
                if (!up.equals("java/lang/Object")) unknown = true; // Object has no fields
                continue;
            }
            Boolean r = findField(u, name, d, seen);
            if (Boolean.TRUE.equals(r)) return r;
            if (r == null) unknown = true;
        }
        return unknown ? null : Boolean.FALSE;
    }
}
