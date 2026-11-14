package net.singularitymc.loader.classloading;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * {@code full_int_range} (docs/COORDINATES.md): rewrites the int midpoints {@code (a + b) / 2} and {@code (a + b) >> 1}
 * in Minecraft's classes to {@code SafeMath.half} / {@code halfFloor}, which add in 64 bits. Past 2^30 blocks the
 * int sum of two coordinates overflows (structure centres, mineshafts, jigsaw structures such as villages); where
 * nothing overflows the result is identical, so every match is rewritten. Stack use only shrinks and no jump target
 * moves, so the frames stay valid.
 */
public final class MidpointRewriter implements ClassTransformer {
    private static final String SAFE_MATH = "net/singularitymc/loader/util/SafeMath";

    private final ClassTransformer next;

    public MidpointRewriter(ClassTransformer next) {
        this.next = next;
    }

    @Override
    public byte[] transform(String name, byte[] bytes) {
        byte[] in = next.transform(name, bytes);
        if (!name.startsWith("net.minecraft.") || !mayContainMidpoint(in)) {
            return in;
        }
        return rewrite(in);
    }

    /** Cheap pre-check: the opcode bytes {@code iadd iconst_2 idiv} or {@code iadd iconst_1 ishr} appear somewhere. */
    static boolean mayContainMidpoint(byte[] b) {
        for (int i = 0; i + 2 < b.length; i++) {
            if (b[i] == (byte) Opcodes.IADD
                && (b[i + 1] == (byte) Opcodes.ICONST_2 && b[i + 2] == (byte) Opcodes.IDIV
                    || b[i + 1] == (byte) Opcodes.ICONST_1 && b[i + 2] == (byte) Opcodes.ISHR)) {
                return true;
            }
        }
        return false;
    }

    /** Returns the class with every midpoint rewritten, or the same array if there was none. */
    static byte[] rewrite(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        if (rewrite(node) == 0) {
            return bytes;
        }
        ClassWriter writer = new ClassWriter(reader, 0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** Rewrites the midpoints of a class in place (also used by the Fabric client mod); returns how many. */
    public static int rewrite(ClassNode node) {
        int rewritten = 0;
        for (MethodNode m : node.methods) {
            for (AbstractInsnNode add = m.instructions.getFirst(); add != null; add = add.getNext()) {
                if (add.getOpcode() != Opcodes.IADD) {
                    continue;
                }
                AbstractInsnNode constant = add.getNext();
                AbstractInsnNode op = constant == null ? null : constant.getNext();
                if (op == null) {
                    continue;
                }
                String method;
                if (constant.getOpcode() == Opcodes.ICONST_2 && op.getOpcode() == Opcodes.IDIV) {
                    method = "half";
                } else if (constant.getOpcode() == Opcodes.ICONST_1 && op.getOpcode() == Opcodes.ISHR) {
                    method = "halfFloor";
                } else {
                    continue;
                }
                MethodInsnNode call = new MethodInsnNode(Opcodes.INVOKESTATIC, SAFE_MATH, method, "(II)I", false);
                m.instructions.insertBefore(add, call);
                m.instructions.remove(add);
                m.instructions.remove(constant);
                m.instructions.remove(op);
                add = call;
                rewritten++;
            }
        }
        return rewritten;
    }
}
