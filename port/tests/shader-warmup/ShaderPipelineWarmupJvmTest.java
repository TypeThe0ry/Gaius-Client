package dev.gaius.browser;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.renderer.RenderPipelines;

/**
 * JVM test of BrowserPipelineWarmup against the vanilla 26.3 pipeline registry and the patched
 * PipelineCache (port/scripts/shader-pipeline-warmup-smoke.mjs runs it with the patched classes
 * first on the class path). Lives in the package of the class so it can use the test seams.
 */
public final class ShaderPipelineWarmupJvmTest {
    private static final class FakeHost implements BrowserPipelineWarmup.Host {
        final Map<String, String> params = new java.util.HashMap<>();
        boolean menu;
        long clock;
        final List<String> notes = new ArrayList<>();
        int published;
        boolean lastDone;
        boolean lastStarted;
        int lastWarmed;

        public String param(String name) { return params.get(name); }
        public boolean menuReady() { return menu; }
        public long nanos() { return clock; }
        public void publish(boolean active, String reason, int eager, int deferred, int warmed, int failed,
                int misses, boolean started, boolean done, double warmMs, double maxMs, int queued) {
            published++; lastDone = done; lastStarted = started; lastWarmed = warmed;
        }
        public void note(String list, String value) { notes.add(list + ":" + value); }
        public void log(String message) { notes.add("log:" + message); }
    }

    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static List<String> names(List<?> list) {
        List<String> out = new ArrayList<>();
        for (Object o : list) out.add(String.valueOf(o));
        return out;
    }

    private static final class RecordingTarget implements PipelineWarmTarget {
        final List<Object> warmed = new ArrayList<>();
        final FakeHost host;
        long cost;
        RecordingTarget(FakeHost host, long cost) { this.host = host; this.cost = cost; }
        public Object gaiusWarm(Object pipeline) { warmed.add(pipeline); host.clock += cost; return pipeline; }
    }

    public static void main(String[] args) throws Exception {
        // Link and verify the patched classes (-Xverify:all) before anything else.
        Class.forName("net.minecraft.client.renderer.ShaderManager", true, ShaderPipelineWarmupJvmTest.class.getClassLoader());
        Class.forName("com.mojang.blaze3d.pipeline.PipelineCache", true, ShaderPipelineWarmupJvmTest.class.getClassLoader());
        FakeHost host = new FakeHost();
        BrowserPipelineWarmup.setHostForTest(host);
        BrowserPipelineWarmup.resetForTest();

        List<RenderPipeline> required = RenderPipelines.requiredPipelines();
        List<RenderPipeline> optional = RenderPipelines.optionalPipelines();
        check(required.size() + optional.size() >= 150, "registry size " + required.size() + "+" + optional.size());

        // 1. Title split of the real registry: the title set stays, the rest is returned for later, nothing is lost.
        BrowserPipelineWarmup.beginConfigLoad();
        BrowserPipelineWarmup.noteShaderPack("vanilla");
        List<Object> eagerRequired = BrowserPipelineWarmup.eager(required, true);
        List<Object> eagerOptional = BrowserPipelineWarmup.eager(optional, false);
        Set<String> eagerNames = new LinkedHashSet<>(names(eagerRequired));
        eagerNames.addAll(names(eagerOptional));
        check(eagerNames.equals(new LinkedHashSet<>(List.of(BrowserPipelineWarmup.TITLE))),
                "eager set is the title set: " + eagerNames);
        check(eagerRequired.size() == BrowserPipelineWarmup.TITLE.length, "all title pipelines are required ones");
        for (int i = 1; i < eagerRequired.size(); i++) {
            check(required.indexOf(eagerRequired.get(i - 1)) < required.indexOf(eagerRequired.get(i)),
                    "registry order kept");
        }

        // 2. Attach with a real (patched) PipelineCache over a fake device: bridge, miss hook and warm-up.
        int[] compiles = {0};
        GpuDevice device = (GpuDevice) Proxy.newProxyInstance(GpuDevice.class.getClassLoader(),
                new Class<?>[] {GpuDevice.class}, (proxy, method, a) -> {
                    if (method.getName().equals("compilePipeline")) {
                        compiles[0]++;
                        CompiledRenderPipeline compiled = (CompiledRenderPipeline) Proxy.newProxyInstance(
                                CompiledRenderPipeline.class.getClassLoader(),
                                new Class<?>[] {CompiledRenderPipeline.class}, (p2, m2, a2) -> null);
                        CompiledRenderPipeline.Pending pending = () -> compiled;
                        return CompletableFuture.completedFuture(pending);
                    }
                    return null;
                });
        ShaderSource source = (ShaderSource) Proxy.newProxyInstance(ShaderSource.class.getClassLoader(),
                new Class<?>[] {ShaderSource.class}, (proxy, method, a) -> null);
        PipelineCache cache = new PipelineCache(device, source);
        check(cache instanceof PipelineWarmTarget, "patched PipelineCache implements PipelineWarmTarget");
        RenderPipeline any = required.get(0);
        Object first = ((PipelineWarmTarget) cache).gaiusWarm(any);
        check(first != null && compiles[0] == 1, "bridge compiles through get: " + compiles[0]);
        check(((PipelineWarmTarget) cache).gaiusWarm(any) == first && compiles[0] == 1, "second get is cached");
        check(host.notes.contains("misses:" + any), "miss reported once: " + host.notes);
        check(cache.get(any) == first, "get returns the cached object");
        long missNotes = host.notes.stream().filter(n -> n.startsWith("misses:")).count();
        check(missNotes == 1, "a hit is not a miss");

        // 3. Warm-up drive: waits for the menu, delays, then one budget per frame, priority first.
        BrowserPipelineWarmup.attach(cache);
        int deferredTotal = required.size() + optional.size() - eagerNames.size();
        check(BrowserPipelineWarmup.queuedForTest() == deferredTotal, "queued " + BrowserPipelineWarmup.queuedForTest()
                + " of " + deferredTotal);
        for (int frame = 0; frame < 50; frame++) {
            host.clock += 16_000_000L;
            BrowserPipelineWarmup.onFrame();
        }
        check(compiles[0] == 1 && BrowserPipelineWarmup.warmedForTest() == 0, "nothing compiles before the menu is ready");
        host.menu = true;
        int framesToStart = 0;
        while (BrowserPipelineWarmup.warmedForTest() == 0 && framesToStart < 100) {
            host.clock += 16_000_000L;
            BrowserPipelineWarmup.onFrame();
            framesToStart++;
        }
        check(framesToStart == 1 + BrowserPipelineWarmup.START_DELAY_FRAMES + 1, "start delay frames " + framesToStart);
        int frames = 0;
        while (BrowserPipelineWarmup.queuedForTest() > 0 && frames < 2000) {
            host.clock += 16_000_000L;
            BrowserPipelineWarmup.onFrame();
            frames++;
        }
        check(BrowserPipelineWarmup.queuedForTest() == 0, "queue drained");
        check(host.lastDone && host.lastStarted, "done published");
        check(BrowserPipelineWarmup.warmedForTest() == deferredTotal, "warmed " + BrowserPipelineWarmup.warmedForTest());
        check(host.notes.stream().filter(n -> n.startsWith("misses:")).count() == 1, "the warm-up's own compilations are not misses: " + host.notes.size());
        // every registered pipeline is in the cache now: further gets are hits, nothing compiles again
        int before = compiles[0];
        Set<String> seen = new HashSet<>();
        for (RenderPipeline p : required) {
            check(cache.get(p) != null, "cached " + p);
            seen.add(p.toString());
        }
        for (RenderPipeline p : optional) {
            check(cache.get(p) != null, "cached " + p);
            seen.add(p.toString());
        }
        check(compiles[0] == before + eagerNames.size(), "the title pipelines compile on first get here (the reload does that in the game)");
        check(seen.size() == required.size() + optional.size(), "all distinct");

        // 4. Order: priority list first, transparency variants last.
        List<Object> ordered = BrowserPipelineWarmup.order(
                new ArrayList<>(required), new ArrayList<>(optional));
        List<String> orderedNames = names(ordered);
        check(orderedNames.size() == new HashSet<>(orderedNames).size(), "order has no duplicates");
        int firstPriority = orderedNames.indexOf("minecraft:pipeline/gui_opaque_textured_background");
        check(firstPriority == 0, "priority list leads: " + orderedNames.get(0));
        int lastNonOit = -1, firstOit = Integer.MAX_VALUE;
        for (int i = 0; i < orderedNames.size(); i++) {
            if (orderedNames.get(i).startsWith("minecraft:pipeline/oit_")) firstOit = Math.min(firstOit, i);
            else lastNonOit = i;
        }
        check(firstOit > lastNonOit, "oit variants last");

        // 5. Cancel: a new attach (next reload) drops the unfinished queue of the previous cache.
        BrowserPipelineWarmup.resetForTest();
        host.menu = true;
        BrowserPipelineWarmup.beginConfigLoad();
        BrowserPipelineWarmup.eager(required, true);
        BrowserPipelineWarmup.eager(optional, false);
        RecordingTarget slow = new RecordingTarget(host, 1_000_000L);
        BrowserPipelineWarmup.attach(slow);
        for (int i = 0; i < 20; i++) { host.clock += 16_000_000L; BrowserPipelineWarmup.onFrame(); }
        int warmedBefore = slow.warmed.size();
        check(warmedBefore > 0 && BrowserPipelineWarmup.queuedForTest() > 0, "partial progress " + warmedBefore);
        BrowserPipelineWarmup.beginConfigLoad();
        BrowserPipelineWarmup.eager(required, true);
        BrowserPipelineWarmup.eager(optional, false);
        RecordingTarget next = new RecordingTarget(host, 1_000_000L);
        BrowserPipelineWarmup.attach(next);
        check(BrowserPipelineWarmup.queuedForTest() == deferredTotal, "fresh queue after re-attach");
        for (int i = 0; i < 400; i++) { host.clock += 16_000_000L; BrowserPipelineWarmup.onFrame(); }
        check(slow.warmed.size() == warmedBefore, "the old cache is not touched after a new reload");
        check(next.warmed.size() == deferredTotal, "the new cache gets everything: " + next.warmed.size());

        // 6. Budget: a pipeline that costs more than the budget gets one pipeline per frame.
        BrowserPipelineWarmup.resetForTest();
        host.menu = true;
        BrowserPipelineWarmup.beginConfigLoad();
        BrowserPipelineWarmup.eager(required, true);
        BrowserPipelineWarmup.eager(optional, false);
        RecordingTarget heavy = new RecordingTarget(host, 15_000_000L);
        BrowserPipelineWarmup.attach(heavy);
        for (int i = 0; i < BrowserPipelineWarmup.START_DELAY_FRAMES + 2; i++) { host.clock += 16_000_000L; BrowserPipelineWarmup.onFrame(); }
        int base = heavy.warmed.size();
        host.clock += 16_000_000L; BrowserPipelineWarmup.onFrame();
        check(heavy.warmed.size() - base == 1, "one 15 ms pipeline per frame over a 6 ms budget");
        BrowserPipelineWarmup.resetForTest();
        BrowserPipelineWarmup.beginConfigLoad();
        BrowserPipelineWarmup.eager(required, true);
        BrowserPipelineWarmup.eager(optional, false);
        RecordingTarget light = new RecordingTarget(host, 1_000_000L);
        BrowserPipelineWarmup.attach(light);
        for (int i = 0; i < BrowserPipelineWarmup.START_DELAY_FRAMES + 2; i++) { host.clock += 16_000_000L; BrowserPipelineWarmup.onFrame(); }
        base = light.warmed.size();
        host.clock += 16_000_000L; BrowserPipelineWarmup.onFrame();
        check(light.warmed.size() - base == 6, "six 1 ms pipelines fit the 6 ms budget: " + (light.warmed.size() - base));

        // 7. Slow frames (world join) are skipped up to MAX_SKIPPED_FRAMES, then one batch runs anyway.
        BrowserPipelineWarmup.resetForTest();
        BrowserPipelineWarmup.beginConfigLoad();
        BrowserPipelineWarmup.eager(required, true);
        BrowserPipelineWarmup.eager(optional, false);
        RecordingTarget slowFrames = new RecordingTarget(host, 1_000_000L);
        BrowserPipelineWarmup.attach(slowFrames);
        for (int i = 0; i < BrowserPipelineWarmup.START_DELAY_FRAMES + 2; i++) { host.clock += 16_000_000L; BrowserPipelineWarmup.onFrame(); }
        base = slowFrames.warmed.size();
        for (int i = 0; i < BrowserPipelineWarmup.MAX_SKIPPED_FRAMES; i++) { host.clock += 200_000_000L; BrowserPipelineWarmup.onFrame(); }
        check(slowFrames.warmed.size() == base, "slow frames are left alone");
        host.clock += 200_000_000L; BrowserPipelineWarmup.onFrame();
        check(slowFrames.warmed.size() > base, "progress after the skip limit");

        // 7b. gaiusPipelineHold=1: the warm-up never starts; everything stays for on-demand compilation.
        BrowserPipelineWarmup.resetForTest();
        host.menu = true;
        host.params.put("gaiusPipelineHold", "1");
        BrowserPipelineWarmup.beginConfigLoad();
        BrowserPipelineWarmup.eager(required, true);
        BrowserPipelineWarmup.eager(optional, false);
        RecordingTarget held = new RecordingTarget(host, 1_000_000L);
        BrowserPipelineWarmup.attach(held);
        for (int i = 0; i < 300; i++) { host.clock += 16_000_000L; BrowserPipelineWarmup.onFrame(); }
        check(held.warmed.isEmpty() && BrowserPipelineWarmup.queuedForTest() == deferredTotal, "hold keeps the queue untouched");
        host.params.remove("gaiusPipelineHold");

        // 8. Vanilla order kept: foreign shader pack, parameter, registry mismatch, eager=all.
        BrowserPipelineWarmup.resetForTest();
        BrowserPipelineWarmup.beginConfigLoad();
        BrowserPipelineWarmup.noteShaderPack("vanilla");
        BrowserPipelineWarmup.noteShaderPack("file/MyPack.zip");
        check((Object) BrowserPipelineWarmup.eager(required, true) == (Object) required, "foreign shader pack keeps the full list");
        check((Object) BrowserPipelineWarmup.eager(optional, false) == (Object) optional, "... and the optional list");
        check("foreign-shader-pack".equals(BrowserPipelineWarmup.reasonForTest()), BrowserPipelineWarmup.reasonForTest());
        BrowserPipelineWarmup.attach(new RecordingTarget(host, 1));
        check(BrowserPipelineWarmup.queuedForTest() == 0, "nothing deferred for a foreign pack");
        BrowserPipelineWarmup.beginConfigLoad();
        check((Object) BrowserPipelineWarmup.eager(required, true) != (Object) required, "the next reload starts clean");
        BrowserPipelineWarmup.eager(optional, false);
        BrowserPipelineWarmup.attach(new RecordingTarget(host, 1));
        host.params.put("gaiusPipelineWarmup", "0");
        BrowserPipelineWarmup.beginConfigLoad();
        check((Object) BrowserPipelineWarmup.eager(required, true) == (Object) required, "gaiusPipelineWarmup=0");
        BrowserPipelineWarmup.eager(optional, false);
        BrowserPipelineWarmup.attach(new RecordingTarget(host, 1));
        host.params.remove("gaiusPipelineWarmup");
        host.params.put("gaiusPipelineEager", "all");
        BrowserPipelineWarmup.beginConfigLoad();
        check((Object) BrowserPipelineWarmup.eager(required, true) == (Object) required, "gaiusPipelineEager=all");
        BrowserPipelineWarmup.eager(optional, false);
        BrowserPipelineWarmup.attach(new RecordingTarget(host, 1));
        host.params.put("gaiusPipelineEager", "none");
        BrowserPipelineWarmup.beginConfigLoad();
        check(BrowserPipelineWarmup.eager(required, true).isEmpty(), "gaiusPipelineEager=none");
        BrowserPipelineWarmup.eager(optional, false);
        BrowserPipelineWarmup.attach(new RecordingTarget(host, 1));
        check(BrowserPipelineWarmup.queuedForTest() == required.size() + optional.size(), "everything deferred");
        host.params.put("gaiusPipelineEager", "gui,minecraft:pipeline/panorama");
        BrowserPipelineWarmup.beginConfigLoad();
        List<Object> two = BrowserPipelineWarmup.eager(required, true);
        check(names(two).containsAll(List.of("minecraft:pipeline/gui", "minecraft:pipeline/panorama")) && two.size() == 2, "named eager set " + names(two));
        host.params.remove("gaiusPipelineEager");
        BrowserPipelineWarmup.resetForTest();
        List<Object> fake = new ArrayList<>(List.of("a", "b", "c", "d"));
        BrowserPipelineWarmup.beginConfigLoad();
        check(BrowserPipelineWarmup.eager(fake, true) == fake, "registry mismatch keeps the list");
        check("registry-mismatch".equals(BrowserPipelineWarmup.reasonForTest()), BrowserPipelineWarmup.reasonForTest());

        // 9. A target that is not a PipelineWarmTarget never arms the warm-up.
        BrowserPipelineWarmup.resetForTest();
        BrowserPipelineWarmup.beginConfigLoad();
        BrowserPipelineWarmup.eager(required, true);
        BrowserPipelineWarmup.eager(optional, false);
        BrowserPipelineWarmup.attach(new Object());
        check(BrowserPipelineWarmup.queuedForTest() == 0, "no target, no warm-up");

        System.out.println("ShaderPipelineWarmupJvmTest OK checks=" + checks + " registry=" + required.size() + "+" + optional.size()
                + " deferred=" + deferredTotal);
    }
}
