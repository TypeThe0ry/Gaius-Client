import dev.gaius.tools.VersionProfile;
import java.util.zip.ZipFile;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

public final class GaiusChunkLayerBytecodeVerifier {
    private static final class HelperLoader extends ClassLoader {
        Class<?> define(byte[] bytes) {
            return defineClass(null, bytes, 0, bytes.length);
        }
    }

    /** Execute the emitted helper; this does not stand in for a live server cancellation test. */
    private static void executePendingHelper(ZipFile jar) throws Exception {
        HelperLoader loader = new HelperLoader();
        ClassWriter stub = new ClassWriter(0);
        stub.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                "org/teavm/platform/PlatformRunnable", null, "java/lang/Object", null);
        stub.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "run", "()V", null, null).visitEnd();
        stub.visitEnd();
        loader.define(stub.toByteArray());
        Class<?> helper;
        try (var input = jar.getInputStream(jar.getEntry(
                "dev/gaius/browser/BrowserChunkGenerationYield.class"))) {
            helper = loader.define(input.readAllBytes());
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        AtomicInteger continuations = new AtomicInteger();
        future.thenRun(continuations::incrementAndGet);
        Object callback = helper.getConstructor(CompletableFuture.class).newInstance(future);
        require(!future.isDone() && continuations.get() == 0,
                "helper construction must not complete a batch inline");
        helper.getMethod("run").invoke(callback);
        require(future.isDone() && !future.isCompletedExceptionally() && continuations.get() == 1,
                "batch helper must normally complete its future and invoke the continuation once");
        helper.getMethod("run").invoke(callback);
        require(continuations.get() == 1, "duplicate helper dispatch repeated the continuation");
        System.out.println("PENDING_HELPER_JVM_OK normalCompletion=1 duplicateContinuation=0");
    }

    private static final String BROWSER_WORLDGEN_SCHEDULER =
            "dev/gaius/browser/BrowserWorldgenScheduler";
    private static final String SCHEDULE_CHUNK_IN_LAYER_DESCRIPTOR =
            "(Lnet/minecraft/world/level/chunk/status/ChunkStatus;Z"
                    + "Lnet/minecraft/server/level/GenerationChunkHolder;)Z";

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static AbstractInsnNode firstExecutable(AbstractInsnNode instruction) {
        while (instruction != null && instruction.getOpcode() < 0) {
            instruction = instruction.getNext();
        }
        return instruction;
    }

    private static AbstractInsnNode nextExecutable(AbstractInsnNode instruction) {
        return instruction == null ? null : firstExecutable(instruction.getNext());
    }

    private static AbstractInsnNode previousExecutable(AbstractInsnNode instruction) {
        if (instruction == null) return null;
        instruction = instruction.getPrevious();
        while (instruction != null && instruction.getOpcode() < 0) {
            instruction = instruction.getPrevious();
        }
        return instruction;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(candidate -> candidate.name.equals(name))
                .findFirst().orElseThrow(() -> new IllegalStateException("missing " + name));
    }

    private static MethodInsnNode firstCall(
            AbstractInsnNode startExclusive, AbstractInsnNode stopExclusive) {
        for (AbstractInsnNode instruction = nextExecutable(startExclusive);
                instruction != null && instruction != stopExclusive;
                instruction = nextExecutable(instruction)) {
            if (instruction instanceof MethodInsnNode call) return call;
        }
        return null;
    }

    private static boolean isTerminal(AbstractInsnNode instruction) {
        if (instruction == null) return true;
        return switch (instruction.getOpcode()) {
            case Opcodes.ATHROW,
                    Opcodes.IRETURN,
                    Opcodes.LRETURN,
                    Opcodes.FRETURN,
                    Opcodes.DRETURN,
                    Opcodes.ARETURN,
                    Opcodes.RETURN,
                    Opcodes.RET -> true;
            default -> false;
        };
    }

    private static void addSuccessor(List<AbstractInsnNode> successors,
            AbstractInsnNode instruction) {
        if (instruction != null) successors.add(instruction);
    }

    private static List<AbstractInsnNode> successors(AbstractInsnNode instruction) {
        List<AbstractInsnNode> successors = new ArrayList<>();
        if (instruction instanceof JumpInsnNode jump) {
            addSuccessor(successors, firstExecutable(jump.label));
            if (jump.getOpcode() != Opcodes.GOTO && jump.getOpcode() != Opcodes.JSR) {
                addSuccessor(successors, nextExecutable(instruction));
            }
        } else if (instruction instanceof TableSwitchInsnNode tableSwitch) {
            addSuccessor(successors, firstExecutable(tableSwitch.dflt));
            for (LabelNode label : tableSwitch.labels) {
                addSuccessor(successors, firstExecutable(label));
            }
        } else if (instruction instanceof LookupSwitchInsnNode lookupSwitch) {
            addSuccessor(successors, firstExecutable(lookupSwitch.dflt));
            for (LabelNode label : lookupSwitch.labels) {
                addSuccessor(successors, firstExecutable(label));
            }
        } else if (!isTerminal(instruction)) {
            addSuccessor(successors, nextExecutable(instruction));
        }
        return successors;
    }

    private static boolean isBrowserWorldgenPulse(AbstractInsnNode instruction) {
        return instruction instanceof MethodInsnNode call
                && call.getOpcode() == Opcodes.INVOKESTATIC
                && BROWSER_WORLDGEN_SCHEDULER.equals(call.owner)
                && call.name.equals("pulse")
                && call.desc.equals("()V");
    }

    private static boolean hasFreshReturnBeforePulse(MethodNode method) {
        for (AbstractInsnNode pulse : method.instructions) {
            if (!isBrowserWorldgenPulse(pulse)) continue;
            int pulseIndex = method.instructions.indexOf(pulse);
            for (AbstractInsnNode first = method.instructions.getFirst(); first != null;
                    first = first.getNext()) {
                if (!(first instanceof FieldInsnNode field)
                        || field.getOpcode() != Opcodes.GETFIELD
                        || !field.name.equals("browserLayerYield")) continue;
                if (method.instructions.indexOf(first) >= pulseIndex) continue;
                boolean sawNullBranch = false;
                for (AbstractInsnNode cursor = nextExecutable(field);
                        cursor != null && method.instructions.indexOf(cursor) < pulseIndex;
                        cursor = nextExecutable(cursor)) {
                    if (cursor instanceof JumpInsnNode jump
                            && jump.getOpcode() == Opcodes.IFNULL) {
                        sawNullBranch = true;
                    }
                    if (sawNullBranch && cursor.getOpcode() == Opcodes.ARETURN) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean everyPathHitsPulse(AbstractInsnNode instruction,
            java.util.IdentityHashMap<AbstractInsnNode, Boolean> memo,
            java.util.Set<AbstractInsnNode> active) {
        if (isBrowserWorldgenPulse(instruction)) return true;
        if (isTerminal(instruction)) return false;
        Boolean cached = memo.get(instruction);
        if (cached != null) return cached;
        if (!active.add(instruction)) return false;
        List<AbstractInsnNode> next = successors(instruction);
        boolean allPathsHit = !next.isEmpty();
        for (AbstractInsnNode successor : next) {
            if (!everyPathHitsPulse(successor, memo, active)) {
                allPathsHit = false;
                break;
            }
        }
        active.remove(instruction);
        memo.put(instruction, allPathsHit);
        return allPathsHit;
    }

    private static void verifySuccessfulHolderPulsePaths(MethodNode layer, String owner) {
        int successfulHolderBranches = 0;
        for (AbstractInsnNode instruction : layer.instructions) {
            if (!(instruction instanceof MethodInsnNode call)
                    || !call.name.equals("scheduleChunkInLayer")) continue;
            require(call.getOpcode() == Opcodes.INVOKEVIRTUAL
                            && owner.equals(call.owner)
                            && SCHEDULE_CHUNK_IN_LAYER_DESCRIPTOR.equals(call.desc),
                    "scheduleLayer holder submission call shape changed");
            AbstractInsnNode branch = nextExecutable(call);
            require(branch instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFEQ,
                    "scheduleLayer holder result must branch on success");
            AbstractInsnNode successPath = nextExecutable(branch);
            require(successPath != null,
                    "scheduleLayer successful holder path is empty");
            successfulHolderBranches++;
            require(everyPathHitsPulse(successPath,
                            new java.util.IdentityHashMap<>(),
                            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>())),
                    "scheduleLayer successful holder path lost BrowserWorldgenScheduler.pulse");
        }
        require(successfulHolderBranches > 0,
                "scheduleLayer holder submission call is missing");
    }

    private static void verifyNoBrowserWorldgenSchedulerCalls(ClassNode node,
            String profile) {
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && BROWSER_WORLDGEN_SCHEDULER.equals(call.owner)) {
                    throw new IllegalStateException(profile + " " + node.name
                            + " must not call BrowserWorldgenScheduler ("
                            + call.name + call.desc + ")");
                }
            }
        }
        System.out.println("PROFILE_CALL_SURFACE_OK " + profile + " " + node.name);
    }

    private static void verifyCleanupBlock(LabelNode label, String target) {
        boolean activeCleared = false;
        boolean yieldCleared = false;
        boolean exits = false;
        for (AbstractInsnNode instruction = firstExecutable(label); instruction != null;
                instruction = nextExecutable(instruction)) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && field.name.equals("browserLayerActive")) {
                activeCleared = previousExecutable(instruction).getOpcode() == Opcodes.ICONST_0;
            } else if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && field.name.equals("browserLayerYield")) {
                yieldCleared = previousExecutable(instruction).getOpcode() == Opcodes.ACONST_NULL;
            } else if (instruction instanceof JumpInsnNode jump
                    && jump.getOpcode() == Opcodes.GOTO) {
                exits = true;
                break;
            }
        }
        require(activeCleared && yieldCleared && exits, target + " cleanup CFG changed");
    }

    private static void verifyLayerBarrierCfg(ClassNode node, String profile) {
        MethodNode run = method(node, "runUntilWait");
        List<FieldInsnNode> activeGets = new ArrayList<>();
        for (AbstractInsnNode instruction : run.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && field.name.equals("browserLayerActive")) activeGets.add(field);
        }
        require(activeGets.size() == 1, "runUntilWait active gate count changed");
        FieldInsnNode activeGet = activeGets.get(0);
        AbstractInsnNode activeNext = nextExecutable(activeGet);
        require(activeNext instanceof JumpInsnNode, "runUntilWait active gate lost branch");
        JumpInsnNode activeBranch = (JumpInsnNode) activeNext;
        require(activeBranch.getOpcode() == Opcodes.IFEQ,
                "runUntilWait active branch must use IFEQ to vanilla wait");
        MethodInsnNode activeCall = firstCall(activeBranch, activeBranch.label);
        require(activeCall != null && activeCall.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && activeCall.name.equals("scheduleNextLayer"),
                "runUntilWait active fallthrough must schedule the next holder batch");
        MethodInsnNode waitCall = firstCall(activeBranch.label, null);
        require(waitCall != null && waitCall.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && waitCall.name.equals("waitForScheduledLayer"),
                "runUntilWait inactive target must enter vanilla layer wait");

        FieldInsnNode yieldGet = null;
        for (AbstractInsnNode instruction = nextExecutable(activeCall);
                instruction != null && instruction != activeBranch.label;
                instruction = nextExecutable(instruction)) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && field.name.equals("browserLayerYield")) {
                yieldGet = field;
                break;
            }
        }
        require(yieldGet != null, "runUntilWait active branch lost batch future");
        AbstractInsnNode yieldNext = nextExecutable(yieldGet);
        require(yieldNext instanceof JumpInsnNode, "runUntilWait batch future lost null branch");
        JumpInsnNode yieldNull = (JumpInsnNode) yieldNext;
        require(yieldNull.getOpcode() == Opcodes.IFNULL && yieldNull.label == activeBranch.label,
                "runUntilWait null batch future must drain through vanilla wait");
        boolean returned = false;
        for (AbstractInsnNode instruction = nextExecutable(yieldNull);
                instruction != null && instruction != activeBranch.label;
                instruction = nextExecutable(instruction)) {
            if (instruction.getOpcode() == Opcodes.ARETURN) {
                returned = true;
                break;
            }
        }
        require(returned, "runUntilWait non-null batch future must return");

        MethodNode layer = method(node, "scheduleLayer");
        AbstractInsnNode first = firstExecutable(layer.instructions.getFirst());
        AbstractInsnNode second = nextExecutable(first);
        require(first != null && first.getOpcode() == Opcodes.ICONST_0,
                "scheduleLayer batch counter must initialize at entry");
        require(second instanceof VarInsnNode && second.getOpcode() == Opcodes.ISTORE
                        && ((VarInsnNode) second).var == 7,
                "scheduleLayer batch counter must reset local 7 at entry");

        JumpInsnNode resumeBranch = null;
        JumpInsnNode batchBackedge = null;
        int batchBackedges = 0;
        for (AbstractInsnNode instruction : layer.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && field.name.equals("browserLayerActive")) {
                AbstractInsnNode branch = nextExecutable(field);
                if (branch instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFNE) {
                    require(resumeBranch == null, "scheduleLayer active resume branch changed");
                    resumeBranch = jump;
                }
            }
            if (instruction instanceof JumpInsnNode jump
                    && jump.getOpcode() == Opcodes.IF_ICMPLT) {
                batchBackedge = jump;
                batchBackedges++;
            }
        }
        require(resumeBranch != null, "scheduleLayer active resume branch missing");
        require(batchBackedges == 1 && batchBackedge != null,
                "scheduleLayer must have one bounded batch backedge");
        AbstractInsnNode limit = previousExecutable(batchBackedge);
        AbstractInsnNode count = previousExecutable(limit);
        require(limit instanceof LdcInsnNode && Integer.valueOf(16).equals(((LdcInsnNode) limit).cst),
                "scheduleLayer holder batch limit changed");
        require(count instanceof VarInsnNode && count.getOpcode() == Opcodes.ILOAD
                        && ((VarInsnNode) count).var == 7,
                "scheduleLayer batch guard must read local 7");
        require(batchBackedge.label == resumeBranch.label,
                "scheduleLayer batch guard must resume at the holder body");

        FieldInsnNode finalActiveClear = null;
        JumpInsnNode finalYieldJump = null;
        int finalCandidates = 0;
        for (AbstractInsnNode instruction : layer.instructions) {
            if (!(instruction instanceof FieldInsnNode field)
                    || field.getOpcode() != Opcodes.PUTFIELD
                    || !field.name.equals("browserLayerActive")
                    || previousExecutable(field).getOpcode() != Opcodes.ICONST_0) continue;
            AbstractInsnNode next = nextExecutable(field);
            if (!(next instanceof JumpInsnNode jump) || jump.getOpcode() != Opcodes.GOTO) continue;
            AbstractInsnNode target = firstExecutable(jump.label);
            if (target instanceof TypeInsnNode type
                    && type.getOpcode() == Opcodes.NEW
                    && type.desc.equals("java/util/concurrent/CompletableFuture")) {
                finalActiveClear = field;
                finalYieldJump = jump;
                finalCandidates++;
            }
        }
        require(finalCandidates == 1 && finalActiveClear != null && finalYieldJump != null,
                "scheduleLayer final holder continuation path changed");
        JumpInsnNode finalCoordinateBranch = null;
        for (AbstractInsnNode instruction = previousExecutable(finalActiveClear);
                instruction != null; instruction = previousExecutable(instruction)) {
            if (instruction instanceof JumpInsnNode jump
                    && jump.getOpcode() == Opcodes.IF_ICMPLE) {
                finalCoordinateBranch = jump;
                break;
            }
        }
        require(finalCoordinateBranch != null,
                "scheduleLayer final holder coordinate branch missing");
        AbstractInsnNode continueBatch = firstExecutable(finalCoordinateBranch.label);
        require(continueBatch instanceof VarInsnNode
                        && continueBatch.getOpcode() == Opcodes.ILOAD
                        && ((VarInsnNode) continueBatch).var == 7,
                "scheduleLayer non-final coordinate path must reach the batch guard");
        AbstractInsnNode futureStart = firstExecutable(finalYieldJump.label);
        FieldInsnNode yieldPut = null;
        MethodInsnNode platformContinuation = null;
        for (AbstractInsnNode instruction = futureStart; instruction != null;
                instruction = nextExecutable(instruction)) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && field.name.equals("browserLayerYield")) yieldPut = field;
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("org/teavm/platform/Platform")
                    && call.name.equals("startThread")
                    && call.desc.equals("(Lorg/teavm/platform/PlatformRunnable;)V")) {
                platformContinuation = call;
                break;
            }
        }
        require(yieldPut != null && platformContinuation != null,
                "scheduleLayer final continuation must publish and start its TeaVM thread");

        JumpInsnNode cancellation = null;
        JumpInsnNode holderRejected = null;
        for (AbstractInsnNode instruction : layer.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && field.name.equals("markedForCancellation")) {
                AbstractInsnNode branch = nextExecutable(field);
                if (branch instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFNE) {
                    cancellation = jump;
                }
            }
            if (instruction instanceof MethodInsnNode call
                    && call.name.equals("scheduleChunkInLayer")) {
                AbstractInsnNode branch = nextExecutable(call);
                if (branch instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFEQ) {
                    holderRejected = jump;
                }
            }
        }
        require(cancellation != null && holderRejected != null
                        && cancellation.label == holderRejected.label,
                "scheduleLayer cancellation and rejected holder must share cleanup");
        verifyCleanupBlock(cancellation.label, "scheduleLayer cancel");

        List<TryCatchBlockNode> throwableHandlers = layer.tryCatchBlocks.stream()
                .filter(block -> "java/lang/Throwable".equals(block.type)).toList();
        require(throwableHandlers.size() == 1, "scheduleLayer Throwable handler changed");
        AbstractInsnNode handlerStart = firstExecutable(throwableHandlers.get(0).handler);
        require(handlerStart instanceof VarInsnNode && handlerStart.getOpcode() == Opcodes.ASTORE,
                "scheduleLayer Throwable handler must retain the thrown value");
        int throwableLocal = ((VarInsnNode) handlerStart).var;
        boolean handlerActiveCleared = false;
        boolean handlerYieldCleared = false;
        boolean rethrowsSame = false;
        for (AbstractInsnNode instruction = nextExecutable(handlerStart); instruction != null;
                instruction = nextExecutable(instruction)) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && field.name.equals("browserLayerActive")) {
                handlerActiveCleared = previousExecutable(field).getOpcode() == Opcodes.ICONST_0;
            } else if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && field.name.equals("browserLayerYield")) {
                handlerYieldCleared = previousExecutable(field).getOpcode() == Opcodes.ACONST_NULL;
            } else if (instruction.getOpcode() == Opcodes.ATHROW) {
                AbstractInsnNode loaded = previousExecutable(instruction);
                rethrowsSame = loaded instanceof VarInsnNode && loaded.getOpcode() == Opcodes.ALOAD
                        && ((VarInsnNode) loaded).var == throwableLocal;
                break;
            }
        }
        require(handlerActiveCleared && handlerYieldCleared && rethrowsSame,
                "scheduleLayer Throwable cleanup/rethrow changed");
        if (VersionProfile.forId(profile).modernPatchSet()
                && !VersionProfile.forId(profile).renderpearl()) {
            verifySuccessfulHolderPulsePaths(layer, node.name);
            System.out.println("PROFILE_CFG_OK " + profile + " " + node.name);
        }
        System.out.println("CFG_VERIFIER_OK " + node.name);
    }

    private static void verifyPendingLayerYield(ClassNode node) {
        boolean yieldField = false;
        for (FieldNode field : node.fields) {
            if (field.name.equals("browserLayerYield")) {
                yieldField = true;
                require(field.desc.equals("Ljava/util/concurrent/CompletableFuture;"),
                        "26.2 browserLayerYield descriptor changed");
            }
        }
        require(yieldField, "26.2 ChunkGenerationTask lost browserLayerYield");
        boolean helperCall = false;
        boolean platformStartThread = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && call.owner.equals("dev/gaius/browser/BrowserChunkGenerationYield")
                        && call.name.equals("<init>")) {
                    helperCall = true;
                }
                if (instruction instanceof MethodInsnNode call
                        && call.owner.equals("org/teavm/platform/Platform")
                        && call.name.equals("startThread")
                        && call.desc.equals("(Lorg/teavm/platform/PlatformRunnable;)V")) {
                    platformStartThread = true;
                }
            }
        }
        require(helperCall, "26.2 bytecode lost BrowserChunkGenerationYield helper");
        require(platformStartThread,
                "26.2 pending continuation must use Platform.startThread(PlatformRunnable)");
        System.out.println("PENDING_LAYER_YIELD_OK " + node.name);
    }

    private static void verifyActiveCleanupBlock(LabelNode label, String target) {
        boolean activeCleared = false;
        boolean exits = false;
        for (AbstractInsnNode instruction = firstExecutable(label); instruction != null;
                instruction = nextExecutable(instruction)) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && field.name.equals("browserLayerActive")) {
                activeCleared = previousExecutable(instruction).getOpcode() == Opcodes.ICONST_0;
            } else if (instruction instanceof JumpInsnNode jump
                    && jump.getOpcode() == Opcodes.GOTO) {
                exits = true;
                break;
            }
        }
        require(activeCleared && exits, target + " active cleanup CFG changed");
    }

    private static void verifyLayerBarrierCfg262(ClassNode node, String profile) {
        verifyPendingLayerYield(node);
        MethodNode run = method(node, "runUntilWait");

        List<FieldInsnNode> activeGets = new ArrayList<>();
        for (AbstractInsnNode instruction : run.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && field.name.equals("browserLayerActive")) {
                activeGets.add(field);
            }
        }
        require(activeGets.size() == 1,
                "26.2 runUntilWait active gate count changed");
        FieldInsnNode activeGet = activeGets.get(0);
        AbstractInsnNode activeNext = nextExecutable(activeGet);
        require(activeNext instanceof JumpInsnNode jump
                        && jump.getOpcode() == Opcodes.IFEQ,
                "26.2 runUntilWait active gate must use IFEQ to vanilla wait");
        JumpInsnNode activeBranch = (JumpInsnNode) activeNext;
        MethodInsnNode activeSchedule = firstCall(activeBranch, activeBranch.label);
        require(activeSchedule != null
                        && activeSchedule.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && activeSchedule.name.equals("scheduleNextLayer"),
                "26.2 active gate must schedule the current layer before re-entry");

        FieldInsnNode activeYieldGet = null;
        for (AbstractInsnNode instruction = nextExecutable(activeSchedule);
                instruction != null && instruction != activeBranch.label;
                instruction = nextExecutable(instruction)) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && field.name.equals("browserLayerYield")) {
                activeYieldGet = field;
                break;
            }
            require(!(instruction instanceof MethodInsnNode call
                            && call.name.equals("waitForScheduledLayer")),
                    "26.2 active branch reaches vanilla wait before pending return");
        }
        require(activeYieldGet != null,
                "26.2 active branch lost pending future read");
        AbstractInsnNode activeYieldBranch = nextExecutable(activeYieldGet);
        require(activeYieldBranch instanceof JumpInsnNode jump
                        && jump.getOpcode() == Opcodes.IFNULL,
                "26.2 active branch lost null-future test");
        boolean activeReturned = false;
        for (AbstractInsnNode instruction = nextExecutable(activeYieldBranch);
                instruction != activeBranch.label && instruction != null;
                instruction = nextExecutable(instruction)) {
            require(!isBrowserWorldgenPulse(instruction),
                    "26.2 active branch pulsed before returning pending future");
            if (instruction.getOpcode() == Opcodes.ARETURN) {
                activeReturned = true;
                break;
            }
        }
        require(activeReturned,
                "26.2 active branch must return pending future before re-entry");

        List<JumpInsnNode> runBackedges = new ArrayList<>();
        for (AbstractInsnNode instruction : run.instructions) {
            if (instruction instanceof JumpInsnNode jump
                    && jump.getOpcode() == Opcodes.GOTO
                    && run.instructions.indexOf(jump.label)
                            < run.instructions.indexOf(jump)) {
                runBackedges.add(jump);
            }
        }
        require(runBackedges.size() == 1,
                "26.2 runUntilWait must retain exactly one vanilla backward edge");
        JumpInsnNode vanillaBackedge = runBackedges.get(0);
        AbstractInsnNode pulseBeforeBackedge = previousExecutable(vanillaBackedge);
        require(isBrowserWorldgenPulse(pulseBeforeBackedge),
                "26.2 original runUntilWait edge lost its scheduler pulse");
        require(hasFreshReturnBeforePulse(run),
                "26.2 fresh scheduleNextLayer path must return pending future before pulse");

        AbstractInsnNode vanillaWait = firstCall(activeBranch.label, null);
        require(vanillaWait instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && call.name.equals("waitForScheduledLayer"),
                "26.2 inactive gate target must enter vanilla layer wait");

        MethodNode layer = method(node, "scheduleLayer");
        AbstractInsnNode first = firstExecutable(layer.instructions.getFirst());
        AbstractInsnNode second = nextExecutable(first);
        require(first != null && first.getOpcode() == Opcodes.ICONST_0,
                "26.2 scheduleLayer batch counter must initialize at entry");
        require(second instanceof VarInsnNode && second.getOpcode() == Opcodes.ISTORE
                        && ((VarInsnNode) second).var == 7,
                "26.2 scheduleLayer batch counter must reset local 7 at entry");

        JumpInsnNode resumeBranch = null;
        for (AbstractInsnNode instruction : layer.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && field.name.equals("browserLayerActive")) {
                AbstractInsnNode branch = nextExecutable(field);
                if (branch instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFNE) {
                    require(resumeBranch == null,
                            "26.2 scheduleLayer active resume branch changed");
                    resumeBranch = jump;
                }
            }
        }
        require(resumeBranch != null,
                "26.2 scheduleLayer active resume branch missing");

        List<JumpInsnNode> batchGuards = new ArrayList<>();
        for (AbstractInsnNode instruction : layer.instructions) {
            if (instruction instanceof JumpInsnNode jump
                    && jump.getOpcode() == Opcodes.IF_ICMPLT) {
                batchGuards.add(jump);
            }
        }
        require(batchGuards.size() == 1,
                "26.2 scheduleLayer must retain one holder batch guard");
        JumpInsnNode batchGuard = batchGuards.get(0);
        AbstractInsnNode limit = previousExecutable(batchGuard);
        AbstractInsnNode count = previousExecutable(limit);
        require(limit instanceof LdcInsnNode && Integer.valueOf(16).equals(((LdcInsnNode) limit).cst),
                "26.2 scheduleLayer holder batch limit changed");
        require(count instanceof VarInsnNode && count.getOpcode() == Opcodes.ILOAD
                        && ((VarInsnNode) count).var == 7,
                "26.2 scheduleLayer batch guard must read local 7");
        require(batchGuard.label == resumeBranch.label
                        && layer.instructions.indexOf(batchGuard.label)
                                < layer.instructions.indexOf(batchGuard),
                "26.2 scheduleLayer batch guard must re-enter the holder body");

        List<JumpInsnNode> layerBackedges = new ArrayList<>();
        for (AbstractInsnNode instruction : layer.instructions) {
            if (instruction instanceof JumpInsnNode jump
                    && layer.instructions.indexOf(jump.label)
                            < layer.instructions.indexOf(jump)) {
                layerBackedges.add(jump);
            }
        }
        require(layerBackedges.size() == 1,
                "26.2 scheduleLayer must retain one method-local re-entry edge");
        JumpInsnNode layerBackedge = layerBackedges.get(0);
        require(layerBackedge.getOpcode() == Opcodes.IF_ICMPLT
                        && layerBackedge.label == resumeBranch.label,
                "26.2 scheduleLayer re-entry edge must resume the holder body");
        AbstractInsnNode fullBatchReturn = nextExecutable(batchGuard);
        require((fullBatchReturn instanceof JumpInsnNode jump
                        && jump.getOpcode() == Opcodes.GOTO
                        && layer.instructions.indexOf(jump.label)
                                > layer.instructions.indexOf(jump))
                || (fullBatchReturn instanceof TypeInsnNode type
                        && type.getOpcode() == Opcodes.NEW
                        && type.desc.equals("java/util/concurrent/CompletableFuture")),
                "26.2 full holder batch must return with its active cursor");

        JumpInsnNode cancellation = null;
        JumpInsnNode holderRejected = null;
        for (AbstractInsnNode instruction : layer.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && field.name.equals("markedForCancellation")) {
                AbstractInsnNode branch = nextExecutable(field);
                if (branch instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFNE) {
                    cancellation = jump;
                }
            }
            if (instruction instanceof MethodInsnNode call
                    && call.name.equals("scheduleChunkInLayer")) {
                AbstractInsnNode branch = nextExecutable(call);
                if (branch instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFEQ) {
                    holderRejected = jump;
                }
            }
        }
        require(cancellation != null && holderRejected != null
                        && cancellation.label == holderRejected.label,
                "26.2 cancellation and rejected holder must share cleanup");
        verifyActiveCleanupBlock(cancellation.label, "26.2 scheduleLayer cancel");

        JumpInsnNode finalCoordinateBranch = null;
        for (AbstractInsnNode instruction : layer.instructions) {
            if (instruction instanceof JumpInsnNode jump
                    && jump.getOpcode() == Opcodes.IF_ICMPLE
                    && jump.label != cancellation.label) {
                finalCoordinateBranch = jump;
            }
        }
        require(finalCoordinateBranch != null,
                "26.2 final holder coordinate branch missing");
        boolean finalActiveCleared = false;
        for (AbstractInsnNode instruction = nextExecutable(finalCoordinateBranch);
                instruction != null && instruction != firstExecutable(finalCoordinateBranch.label);
                instruction = nextExecutable(instruction)) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && field.name.equals("browserLayerActive")
                    && previousExecutable(field).getOpcode() == Opcodes.ICONST_0) {
                finalActiveCleared = true;
                break;
            }
        }
        require(finalActiveCleared,
                "26.2 final holder path must clear active before vanilla wait");

        List<TryCatchBlockNode> throwableHandlers = layer.tryCatchBlocks.stream()
                .filter(block -> "java/lang/Throwable".equals(block.type)).toList();
        require(throwableHandlers.size() == 1,
                "26.2 scheduleLayer Throwable handler changed");
        AbstractInsnNode handlerStart = firstExecutable(throwableHandlers.get(0).handler);
        require(handlerStart instanceof VarInsnNode && handlerStart.getOpcode() == Opcodes.ASTORE,
                "26.2 scheduleLayer Throwable handler must retain the thrown value");
        int throwableLocal = ((VarInsnNode) handlerStart).var;
        boolean handlerActiveCleared = false;
        boolean rethrowsSame = false;
        for (AbstractInsnNode instruction = nextExecutable(handlerStart); instruction != null;
                instruction = nextExecutable(instruction)) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && field.name.equals("browserLayerActive")) {
                handlerActiveCleared = previousExecutable(field).getOpcode() == Opcodes.ICONST_0;
            } else if (instruction.getOpcode() == Opcodes.ATHROW) {
                AbstractInsnNode loaded = previousExecutable(instruction);
                rethrowsSame = loaded instanceof VarInsnNode
                        && loaded.getOpcode() == Opcodes.ALOAD
                        && ((VarInsnNode) loaded).var == throwableLocal;
                break;
            }
        }
        require(handlerActiveCleared && rethrowsSame,
                "26.2 scheduleLayer Throwable cleanup/rethrow changed");
        verifySuccessfulHolderPulsePaths(layer, node.name);
        System.out.println("PROFILE_CFG_OK " + profile + " " + node.name);
        System.out.println("CFG_VERIFIER_OK " + node.name);
    }

    private static void verify(ZipFile jar, String name, String profile) throws Exception {
        var entry = jar.getEntry(name);
        if (entry == null) {
            throw new IllegalStateException("missing verifier entry: " + name);
        }
        ClassNode node = new ClassNode();
        try (var input = jar.getInputStream(entry)) {
            new ClassReader(input.readAllBytes()).accept(node, 0);
        }
        for (MethodNode method : node.methods) {
            new Analyzer<BasicValue>(new BasicVerifier()).analyze(node.name, method);
        }
        if (name.equals("net/minecraft/server/level/ChunkGenerationTask.class")) {
            if (isModernProfile(profile)) {
                verifyLayerBarrierCfg262(node, profile);
            } else {
                verifyLayerBarrierCfg(node, profile);
            }
        }
        if (VersionProfile.forId(profile).legacyPatchSet()) {
            verifyNoBrowserWorldgenSchedulerCalls(node, profile);
        }
        System.out.println("BASIC_VERIFIER_OK " + name);
    }

    /**
     * 26.2 and 26.3 share Minecraft262BrowserPatcher's ChunkGenerationTask cooperation patch
     * (identical target bytecode), so both are checked against the modern layer-barrier CFG.
     */
    private static boolean isModernProfile(String profile) {
        return VersionProfile.forId(profile).modernPatchSet();
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 2,
                "usage: GaiusChunkLayerBytecodeVerifier <client.jar> <profile-id>");
        String profile = args[1];
        require(VersionProfile.forId(profile).legacyPatchSet() || isModernProfile(profile),
                "unsupported Minecraft profile: " + profile);
        try (ZipFile jar = new ZipFile(args[0])) {
            verify(jar, "net/minecraft/server/level/ChunkGenerationTask.class", profile);
            if (VersionProfile.forId(profile).legacyPatchSet()) {
                verify(jar, "dev/gaius/browser/BrowserChunkGenerationYield.class", profile);
            } else {
                require(jar.getEntry("dev/gaius/browser/BrowserChunkGenerationYield.class") != null,
                        "26.2 overlay must emit BrowserChunkGenerationYield.class");
                verify(jar, "dev/gaius/browser/BrowserChunkGenerationYield.class", profile);
                executePendingHelper(jar);
            }
        }
    }
}
