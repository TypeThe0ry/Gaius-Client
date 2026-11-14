package dev.gaius.tools.patches.worldgen;

import static dev.gaius.tools.patches.support.ClientPatchSupport.find;
import static dev.gaius.tools.patches.support.ClientPatchSupport.read;
import static dev.gaius.tools.patches.support.ClientPatchSupport.writeComputeFrames;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * The feature-placement hot paths of the MinecraftClientPatcher step ({@code patches/worldgen}):
 * the per-block lookups that {@code applyBiomeDecoration} repeats thousands of times per chunk
 * (ore blobs ask the section cache and the replacement rules once per block).
 *
 * <p>Both patches use only signatures that are the same in every supported profile
 * ({@code BulkSectionAccess}, {@code OreFeature}, {@code Holder.Reference}), keep every random
 * draw, every visit order and every placed block of vanilla, and only answer a repeated question
 * from the answer of the previous identical question.
 */
public final class FeaturePlacementPatches {
    private FeaturePlacementPatches() {
    }

    private static final String BULK_SECTION_ACCESS = "net/minecraft/world/level/chunk/BulkSectionAccess";
    private static final String ORE_FEATURE = "net/minecraft/world/level/levelgen/feature/OreFeature";
    private static final String SECTION_POS = "net/minecraft/core/SectionPos";
    private static final String BLOCK_POS = "net/minecraft/core/BlockPos";
    private static final String BLOCK_STATE = "net/minecraft/world/level/block/state/BlockState";
    private static final String LEVEL_CHUNK_SECTION = "net/minecraft/world/level/chunk/LevelChunkSection";
    private static final String FUNCTION = "java/util/function/Function";
    private static final String HOLDER_REFERENCE = "net/minecraft/core/Holder$Reference";
    private static final String TAG_KEY = "net/minecraft/tags/TagKey";

    /** The memo fields {@code Holder.Reference} gains: the last tag answered true, and the last answered false. */
    public static final String TAG_HIT_FIELD = "gaius$tagHit";
    public static final String TAG_MISS_FIELD = "gaius$tagMiss";

    /**
     * {@code BulkSectionAccess.getSection} asks the level for the section index and the section
     * count before it looks at the section it returned last. An ore blob visits its blocks in
     * runs inside one section, so almost every call repeats the previous call's section. The
     * patched method answers that case first: the cached section is returned when the position's
     * section key (and its section Y, so a 22/20/22-bit key alias cannot match) equals the key
     * the cache was filled for. The cache is only ever filled after the vanilla range check
     * passed for that key, so the vanilla method would have returned the same section; every
     * other call runs the unchanged vanilla body.
     *
     * <p>{@code OreFeature} also builds {@code bulkSectionAccess::getBlockState} as a new
     * {@code Function} for every block and replacement target it tests. The class implements
     * {@code Function<BlockPos, BlockState>} (its {@code apply} is {@code getBlockState}), and
     * the {@code invokedynamic} that wrapped the receiver is dropped, so the receiver itself is
     * the function: the same answers, no allocation. {@code BulkSectionAccess} is used by
     * {@code OreFeature} only (checked for every supported profile).
     */
    public static void patchBulkSectionAccessLookup(String jar, Path root) throws IOException {
        Path bulkOutput = root.resolve(BULK_SECTION_ACCESS + ".class");
        Path oreOutput = root.resolve(ORE_FEATURE + ".class");
        ClassNode bulk = Files.exists(bulkOutput) ? read(bulkOutput) : read(jar, BULK_SECTION_ACCESS + ".class");
        ClassNode ore = Files.exists(oreOutput) ? read(oreOutput) : read(jar, ORE_FEATURE + ".class");
        patchBulkSection(bulk);
        // The Function view is only worth its method where the ore code still wraps the access in a
        // lambda; a release that stopped doing so keeps the last-section lookup alone.
        int replaced = dropBlockStateGetterLambdas(ore);
        if (replaced > 0) {
            addFunctionView(bulk);
            writeComputeFrames(ore, oreOutput);
        }
        writeComputeFrames(bulk, bulkOutput);
        System.out.println("BulkSectionAccess: last-section lookup before the level queries"
                + (replaced > 0 ? ", Function view for OreFeature (" + replaced + " lambda sites)"
                        : ", no OreFeature lambda to replace"));
    }

    private static void patchBulkSection(ClassNode bulk) {
        if ((bulk.access & Opcodes.ACC_INTERFACE) != 0 || bulk.interfaces.contains(FUNCTION)) {
            throw new IllegalStateException(BULK_SECTION_ACCESS + " is not the expected plain class");
        }
        String sectionDescriptor = "L" + LEVEL_CHUNK_SECTION + ";";
        String blockStateDescriptor = "(L" + BLOCK_POS + ";)L" + BLOCK_STATE + ";";
        requireField(bulk, "lastSection", sectionDescriptor);
        requireField(bulk, "lastSectionKey", "J");
        find(bulk, "getBlockState", blockStateDescriptor);
        if (bulk.methods.stream().anyMatch(method -> method.name.equals("apply"))) {
            throw new IllegalStateException(BULK_SECTION_ACCESS + ".apply already exists");
        }
        MethodNode getSection = find(bulk, "getSection", "(L" + BLOCK_POS + ";)" + sectionDescriptor);
        verifyGetSectionShape(getSection);

        LabelNode slow = new LabelNode();
        InsnList fast = new InsnList();
        // if (lastSection != null && lastSectionKey == SectionPos.asLong(pos)
        //         && blockToSectionCoord(pos.getY()) == SectionPos.y(lastSectionKey)) return lastSection;
        fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fast.add(new FieldInsnNode(Opcodes.GETFIELD, BULK_SECTION_ACCESS, "lastSection", sectionDescriptor));
        fast.add(new JumpInsnNode(Opcodes.IFNULL, slow));
        fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fast.add(new FieldInsnNode(Opcodes.GETFIELD, BULK_SECTION_ACCESS, "lastSectionKey", "J"));
        fast.add(new VarInsnNode(Opcodes.ALOAD, 1));
        fast.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SECTION_POS, "asLong", "(L" + BLOCK_POS + ";)J", false));
        fast.add(new InsnNode(Opcodes.LCMP));
        fast.add(new JumpInsnNode(Opcodes.IFNE, slow));
        fast.add(new VarInsnNode(Opcodes.ALOAD, 1));
        fast.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, BLOCK_POS, "getY", "()I", false));
        fast.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SECTION_POS, "blockToSectionCoord", "(I)I", false));
        fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fast.add(new FieldInsnNode(Opcodes.GETFIELD, BULK_SECTION_ACCESS, "lastSectionKey", "J"));
        fast.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SECTION_POS, "y", "(J)I", false));
        fast.add(new JumpInsnNode(Opcodes.IF_ICMPNE, slow));
        fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fast.add(new FieldInsnNode(Opcodes.GETFIELD, BULK_SECTION_ACCESS, "lastSection", sectionDescriptor));
        fast.add(new InsnNode(Opcodes.ARETURN));
        fast.add(slow);
        getSection.instructions.insert(fast);
        getSection.maxStack = Math.max(getSection.maxStack, 4);

    }

    private static void addFunctionView(ClassNode bulk) {
        String blockStateDescriptor = "(L" + BLOCK_POS + ";)L" + BLOCK_STATE + ";";
        bulk.interfaces.add(FUNCTION);
        MethodNode apply = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, "apply",
                "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
        apply.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        apply.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        apply.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, BLOCK_POS));
        apply.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, BULK_SECTION_ACCESS, "getBlockState",
                blockStateDescriptor, false));
        apply.instructions.add(new InsnNode(Opcodes.ARETURN));
        apply.maxStack = 2;
        apply.maxLocals = 2;
        bulk.methods.add(apply);
    }

    /**
     * Only the vanilla shape is patched: a range check against the level, then the key compare
     * against the cache, then computeIfAbsent into the acquired-section map that assigns the cache.
     */
    private static void verifyGetSectionShape(MethodNode getSection) {
        int asLong = 0;
        int cacheWrites = 0;
        int keyWrites = 0;
        int sectionIndexQueries = 0;
        for (AbstractInsnNode instruction : getSection.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call) {
                if (call.owner.equals(SECTION_POS) && call.name.equals("asLong")
                        && call.desc.equals("(L" + BLOCK_POS + ";)J")) {
                    asLong++;
                } else if (call.name.equals("getSectionIndex") && call.desc.equals("(I)I")) {
                    sectionIndexQueries++;
                }
            } else if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
                    && field.owner.equals(BULK_SECTION_ACCESS)) {
                if (field.name.equals("lastSection")) {
                    cacheWrites++;
                } else if (field.name.equals("lastSectionKey")) {
                    keyWrites++;
                }
            }
        }
        if (asLong != 1 || cacheWrites != 1 || keyWrites != 1 || sectionIndexQueries != 1) {
            throw new IllegalStateException("BulkSectionAccess.getSection changed: asLong=" + asLong
                    + " lastSection=" + cacheWrites + " lastSectionKey=" + keyWrites
                    + " getSectionIndex=" + sectionIndexQueries);
        }
    }

    private static int dropBlockStateGetterLambdas(ClassNode ore) {
        String descriptor = "(L" + BULK_SECTION_ACCESS + ";)L" + FUNCTION + ";";
        int replaced = 0;
        for (MethodNode method : ore.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof InvokeDynamicInsnNode dynamic) || !dynamic.desc.equals(descriptor)) {
                    continue;
                }
                if (dynamic.bsmArgs.length < 3 || !(dynamic.bsmArgs[1] instanceof Handle target)
                        || !target.getOwner().equals(BULK_SECTION_ACCESS)
                        || !target.getName().equals("getBlockState")
                        || !target.getDesc().equals("(L" + BLOCK_POS + ";)L" + BLOCK_STATE + ";")
                        || target.getTag() != Opcodes.H_INVOKEVIRTUAL) {
                    throw new IllegalStateException("OreFeature." + method.name
                            + " builds another Function over BulkSectionAccess");
                }
                // The receiver stays on the stack and is the Function.
                method.instructions.remove(dynamic);
                replaced++;
            }
        }
        return replaced;
    }

    /**
     * {@code Holder.Reference.is(TagKey)} is {@code boundTags().contains(tag)}: a hash probe whose
     * hash walks the tag's registry key and identifier. Block states answer it for every ore
     * replacement test, heightmap update and leaf-distance check, nearly always with the tag of
     * the previous question on the same holder (a run of stone asks stone for the same
     * replaceable tag again). The holder now remembers the last tag it answered true and the
     * last it answered false, compared by identity. A hit returns the stored answer; a miss runs
     * the vanilla expression (including its "Tags not bound" failure) and records its result.
     * {@code bindTags} clears both, so a rebound tag set is never answered from the old one, and a
     * null tag always takes the vanilla path.
     */
    public static void patchHolderReferenceTagMemo(String jar, Path root) throws IOException {
        Path output = root.resolve(HOLDER_REFERENCE + ".class");
        ClassNode holder = Files.exists(output) ? read(output) : read(jar, HOLDER_REFERENCE + ".class");
        if (holder.fields.stream().anyMatch(field -> field.name.equals(TAG_HIT_FIELD)
                || field.name.equals(TAG_MISS_FIELD))) {
            throw new IllegalStateException(HOLDER_REFERENCE + " already has the tag memo fields");
        }
        String tagDescriptor = "L" + TAG_KEY + ";";
        requireField(holder, "tags", "Ljava/util/Set;");
        int access = Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_TRANSIENT;
        holder.fields.add(new FieldNode(access, TAG_HIT_FIELD, tagDescriptor, null, null));
        holder.fields.add(new FieldNode(access, TAG_MISS_FIELD, tagDescriptor, null, null));

        MethodNode is = find(holder, "is", "(" + tagDescriptor + ")Z");
        verifyTagIsShape(is);
        int returns = 0;
        for (AbstractInsnNode instruction : is.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.IRETURN) {
                continue;
            }
            // boolean found = <vanilla>; if (found) tagHit = tag; else tagMiss = tag; return found;
            LabelNode miss = new LabelNode();
            LabelNode done = new LabelNode();
            InsnList record = new InsnList();
            record.add(new VarInsnNode(Opcodes.ISTORE, 2));
            record.add(new VarInsnNode(Opcodes.ILOAD, 2));
            record.add(new JumpInsnNode(Opcodes.IFEQ, miss));
            record.add(new VarInsnNode(Opcodes.ALOAD, 0));
            record.add(new VarInsnNode(Opcodes.ALOAD, 1));
            record.add(new FieldInsnNode(Opcodes.PUTFIELD, HOLDER_REFERENCE, TAG_HIT_FIELD, tagDescriptor));
            record.add(new JumpInsnNode(Opcodes.GOTO, done));
            record.add(miss);
            record.add(new VarInsnNode(Opcodes.ALOAD, 0));
            record.add(new VarInsnNode(Opcodes.ALOAD, 1));
            record.add(new FieldInsnNode(Opcodes.PUTFIELD, HOLDER_REFERENCE, TAG_MISS_FIELD, tagDescriptor));
            record.add(done);
            record.add(new VarInsnNode(Opcodes.ILOAD, 2));
            is.instructions.insertBefore(instruction, record);
            returns++;
        }
        if (returns != 1) {
            throw new IllegalStateException("Holder.Reference.is(TagKey) return count changed: " + returns);
        }
        LabelNode slow = new LabelNode();
        LabelNode notHit = new LabelNode();
        InsnList fast = new InsnList();
        fast.add(new VarInsnNode(Opcodes.ALOAD, 1));
        fast.add(new JumpInsnNode(Opcodes.IFNULL, slow));
        fast.add(new VarInsnNode(Opcodes.ALOAD, 1));
        fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fast.add(new FieldInsnNode(Opcodes.GETFIELD, HOLDER_REFERENCE, TAG_HIT_FIELD, tagDescriptor));
        fast.add(new JumpInsnNode(Opcodes.IF_ACMPNE, notHit));
        fast.add(new InsnNode(Opcodes.ICONST_1));
        fast.add(new InsnNode(Opcodes.IRETURN));
        fast.add(notHit);
        fast.add(new VarInsnNode(Opcodes.ALOAD, 1));
        fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fast.add(new FieldInsnNode(Opcodes.GETFIELD, HOLDER_REFERENCE, TAG_MISS_FIELD, tagDescriptor));
        fast.add(new JumpInsnNode(Opcodes.IF_ACMPNE, slow));
        fast.add(new InsnNode(Opcodes.ICONST_0));
        fast.add(new InsnNode(Opcodes.IRETURN));
        fast.add(slow);
        is.instructions.insert(fast);
        is.maxStack = Math.max(is.maxStack, 2);
        is.maxLocals = Math.max(is.maxLocals, 3);

        MethodNode bind = find(holder, "bindTags", "(Ljava/util/Collection;)V");
        int bindReturns = 0;
        int tagWrites = 0;
        for (AbstractInsnNode instruction : bind.instructions.toArray()) {
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
                    && field.owner.equals(HOLDER_REFERENCE) && field.name.equals("tags")) {
                tagWrites++;
            }
        }
        for (AbstractInsnNode instruction : bind.instructions.toArray()) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                InsnList clear = new InsnList();
                for (String field : new String[] {TAG_HIT_FIELD, TAG_MISS_FIELD}) {
                    clear.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    clear.add(new InsnNode(Opcodes.ACONST_NULL));
                    clear.add(new FieldInsnNode(Opcodes.PUTFIELD, HOLDER_REFERENCE, field, tagDescriptor));
                }
                bind.instructions.insertBefore(instruction, clear);
                bindReturns++;
            }
        }
        if (tagWrites != 1 || bindReturns != 1) {
            throw new IllegalStateException("Holder.Reference.bindTags changed: tags writes=" + tagWrites
                    + " returns=" + bindReturns);
        }
        bind.maxStack = Math.max(bind.maxStack, 2);
        writeComputeFrames(holder, output);
        System.out.println("Holder.Reference.is(TagKey): last true/false tag memo, cleared by bindTags");
    }

    /** The vanilla body is {@code boundTags().contains(tag)}; any other shape is not memoized. */
    private static void verifyTagIsShape(MethodNode is) {
        int contains = 0;
        int bound = 0;
        int stores = 0;
        for (AbstractInsnNode instruction : is.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call) {
                if (call.name.equals("contains") && call.owner.equals("java/util/Set")
                        && call.desc.equals("(Ljava/lang/Object;)Z")) {
                    contains++;
                } else if (call.name.equals("boundTags") && call.desc.equals("()Ljava/util/Set;")) {
                    bound++;
                }
            } else if (instruction.getOpcode() == Opcodes.ISTORE || instruction.getOpcode() == Opcodes.PUTFIELD) {
                stores++;
            }
        }
        if (contains != 1 || bound != 1 || stores != 0) {
            throw new IllegalStateException("Holder.Reference.is(TagKey) changed: contains=" + contains
                    + " boundTags=" + bound + " stores=" + stores);
        }
    }

    private static void requireField(ClassNode node, String name, String descriptor) {
        if (node.fields.stream().noneMatch(field -> field.name.equals(name) && field.desc.equals(descriptor))) {
            throw new IllegalStateException(node.name + "." + name + " " + descriptor + " was not found");
        }
    }
}
