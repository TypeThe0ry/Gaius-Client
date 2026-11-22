import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.*;

import static org.objectweb.asm.Opcodes.*;

/** Builds the state gate's four negative controls from the emitted bridge. */
public final class StateNeighbourTablesMutator {
    public static void main(String[] args) throws Exception {
        ClassNode bridge = new ClassNode();
        new ClassReader(Files.readAllBytes(Path.of(args[0]))).accept(bridge, 0);
        String mutant = args[1];
        String methodName = mutant.equals("no-value-hashes") ? "create" : "finish";
        MethodNode method = bridge.methods.stream().filter(m -> m.name.equals(methodName)).findFirst().orElseThrow();
        var code = Arrays.stream(method.instructions.toArray()).filter(i -> i.getOpcode() >= 0).toList();
        int changed = 0;
        for (int i = 0; i < code.size(); i++) {
            AbstractInsnNode insn = code.get(i);
            switch (mutant) {
                case "no-value-hashes", "no-wildcard-hash" -> {
                    if (!(insn instanceof MethodInsnNode call) || !call.name.equals("hashCode")
                            || !call.desc.equals("()I") || call.getOpcode() != INVOKEVIRTUAL) continue;
                    method.instructions.insertBefore(call, new InsnNode(POP));
                    method.instructions.set(call, new InsnNode(ICONST_0));
                }
                case "unshared-rows" -> {
                    // tables[base + k * stride][p] = row.clone();
                    if (insn.getOpcode() != AASTORE || code.get(i - 1).getOpcode() != ALOAD
                            || code.get(i - 2).getOpcode() != ILOAD || code.get(i - 3).getOpcode() != AALOAD) continue;
                    String row = "[Lnet/minecraft/world/level/block/state/StateHolder;";
                    InsnList clone = new InsnList();
                    clone.add(new MethodInsnNode(INVOKEVIRTUAL, row, "clone", "()Ljava/lang/Object;", false));
                    clone.add(new TypeInsnNode(CHECKCAST, row));
                    method.instructions.insertBefore(insn, clone);
                }
                case "reversed-rows" -> {
                    // Replace the row index k with row.length - 1 - k at row[k] = states[...].
                    if (insn.getOpcode() != AASTORE || code.get(i - 1).getOpcode() != AALOAD) continue;
                    int field = i - 2;
                    while (field >= 2 && code.get(field).getOpcode() != GETFIELD) field--;
                    if (!(code.get(field) instanceof FieldInsnNode states) || !states.name.equals("states")
                            || code.get(field - 2).getOpcode() != ILOAD) continue;
                    AbstractInsnNode index = code.get(field - 2);
                    InsnList last = new InsnList();
                    for (int op : new int[] {DUP, ARRAYLENGTH, ICONST_1, ISUB}) last.add(new InsnNode(op));
                    method.instructions.insertBefore(index, last);
                    method.instructions.insert(index, new InsnNode(ISUB));
                }
                default -> throw new IllegalArgumentException("Unknown mutant: " + mutant);
            }
            changed++;
        }
        if (changed != 1) throw new IllegalStateException(mutant + " matched " + changed + " mutation sites");
        ClassWriter bytes = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        bridge.accept(bytes);
        Path output = Path.of(args[2]);
        Files.createDirectories(output.getParent());
        Files.write(output, bytes.toByteArray());
    }
}
