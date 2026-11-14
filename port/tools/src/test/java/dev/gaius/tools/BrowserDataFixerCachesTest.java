package dev.gaius.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.datafixers.DSL;
import com.mojang.datafixers.DataFix;
import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.DataFixerBuilder;
import com.mojang.datafixers.RewriteResult;
import com.mojang.datafixers.TypeRewriteRule;
import com.mojang.datafixers.functions.PointFreeRule;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.datafixers.types.Type;
import com.mojang.datafixers.types.templates.TypeTemplate;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;
import dev.gaius.browser.BrowserDataFixerCaches;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Runs against both the original and patched DataFixerUpper classes on the JVM. */
public final class BrowserDataFixerCachesTest {
    private static final DSL.TypeReference RECORD = () -> "record";

    public static void main(String[] args) throws Exception {
        boolean bounded = args.length == 1 && args[0].equals("bounded");
        testMap();
        Field field = Type.class.getDeclaredField("REWRITE_CACHE");
        field.setAccessible(true);
        Map<?, ?> cache = (Map<?, ?>) field.get(null);
        Field pending = Type.class.getDeclaredField("PENDING_REWRITE_CACHE");
        pending.setAccessible(true);
        check(pending.get(null) instanceof ConcurrentMap, "pending rewrites stay concurrent");

        DataFixer fixer = fixer();
        List<String> inputs = List.of(
                "{\"old\":1,\"nested\":{\"keep\":[1,2,3]},\"text\":\"test\"}",
                "{\"old\":-2147483648,\"untouched\":true}",
                "{\"nested\":[],\"new\":\"preserve\"}");
        List<String> before = inputs.stream().map(input -> migrate(fixer, input)).toList();
        check(JsonParser.parseString(before.getFirst()).getAsJsonObject().get("new").getAsInt() == 1,
                "the fixture actually migrates data");
        AtomicInteger rewrites = new AtomicInteger();
        TypeRewriteRule original = nop(rewrites);
        Type<Integer> type = DSL.intType();
        var first = type.rewrite(original, PointFreeRule.nop());
        check(first.isPresent(), "completed positive rewrite");
        type.rewrite(original, PointFreeRule.nop());
        check(rewrites.get() == 1, "recent rewrite hits the cache");
        TypeRewriteRule empty = new TypeRewriteRule() {
            @Override
            public <A> Optional<RewriteResult<A, ?>> rewrite(Type<A> ignored) {
                return Optional.empty();
            }
        };
        check(type.rewrite(empty, PointFreeRule.nop()).isEmpty(), "negative rewrite is preserved");
        for (int i = 0; i < BrowserDataFixerCaches.MAX_COMPLETED_REWRITES + 1024; i++) {
            type.rewrite(nop(new AtomicInteger()), PointFreeRule.nop());
        }
        int filledSize = cache.size();
        check(!bounded || filledSize <= BrowserDataFixerCaches.MAX_COMPLETED_REWRITES,
                "actual Type cache stays bounded after overflow");
        check(bounded || filledSize > BrowserDataFixerCaches.MAX_COMPLETED_REWRITES,
                "original control retains entries beyond the bound");
        check(type.rewrite(original, PointFreeRule.nop()).equals(first), "evicted result recomputes identically");
        check(rewrites.get() == (bounded ? 2 : 1), "the control distinguishes eviction from a hit");
        check(type.rewrite(empty, PointFreeRule.nop()).isEmpty(), "evicted negative result recomputes");
        List<String> after = inputs.stream().map(input -> migrate(fixer, input)).toList();
        check(before.equals(after), "migration bytes match before and after eviction");
        testPending();
        check(((Map<?, ?>) pending.get(null)).isEmpty(), "pending futures are removed after completion");
        System.out.println("MIGRATIONS " + after);
        System.out.println("PASS bounded=" + bounded + " completedEntries=" + filledSize);
    }

    private static void testMap() {
        Map<Integer, String> cache = BrowserDataFixerCaches.completedRewrites();
        for (int i = 0; i < BrowserDataFixerCaches.MAX_COMPLETED_REWRITES; i++) cache.put(i, "value");
        cache.get(0);
        cache.put(-1, "new");
        check(cache.containsKey(0) && !cache.containsKey(1), "access order protects the recently used entry");
        check(cache.size() == BrowserDataFixerCaches.MAX_COMPLETED_REWRITES, "exact entry bound");
        cache.clear();
        check(cache.isEmpty(), "clear releases all entries");
    }

    private static TypeRewriteRule nop(AtomicInteger calls) {
        return new TypeRewriteRule() {
            @Override
            public <A> Optional<RewriteResult<A, ?>> rewrite(Type<A> type) {
                calls.incrementAndGet();
                return Optional.of(RewriteResult.nop(type));
            }
        };
    }

    private static void testPending() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        TypeRewriteRule rule = new TypeRewriteRule() {
            @Override
            public <A> Optional<RewriteResult<A, ?>> rewrite(Type<A> type) {
                calls.incrementAndGet();
                entered.countDown();
                try {
                    check(release.await(30, TimeUnit.SECONDS), "release concurrent rewrite");
                } catch (InterruptedException error) {
                    throw new AssertionError(error);
                }
                return Optional.of(RewriteResult.nop(type));
            }
        };
        try (var executor = Executors.newFixedThreadPool(4)) {
            var first = executor.submit(() -> DSL.intType().rewrite(rule, PointFreeRule.nop()));
            check(entered.await(30, TimeUnit.SECONDS), "owner begins the rewrite");
            var second = executor.submit(() -> DSL.intType().rewrite(rule, PointFreeRule.nop()));
            var third = executor.submit(() -> DSL.intType().rewrite(rule, PointFreeRule.nop()));
            release.countDown();
            var expected = first.get(30, TimeUnit.SECONDS);
            check(expected.equals(second.get(30, TimeUnit.SECONDS))
                    && expected.equals(third.get(30, TimeUnit.SECONDS)), "all waiters get the completed result");
            check(calls.get() == 1, "pending rewrite coordination is unchanged");
        } finally {
            release.countDown();
        }
    }

    private static DataFixer fixer() {
        DataFixerBuilder builder = new DataFixerBuilder(1);
        builder.addSchema(0, RecordSchema::new);
        Schema schema = builder.addSchema(1, RecordSchema::new);
        builder.addFixer(new DataFix(schema, false) {
            @Override
            protected TypeRewriteRule makeRule() {
                return fixTypeEverywhereTyped("rename old field", getInputSchema().getType(RECORD),
                        typed -> typed.update(DSL.remainderFinder(), value -> value.renameField("old", "new")));
            }
        });
        return builder.build().fixer();
    }

    private static String migrate(DataFixer fixer, String input) {
        Dynamic<JsonElement> value = new Dynamic<>(JsonOps.INSTANCE, JsonParser.parseString(input));
        return fixer.update(RECORD, value, 0, 1).getValue().toString();
    }

    private static final class RecordSchema extends Schema {
        RecordSchema(int version, Schema parent) {
            super(version, parent);
        }

        @Override
        public Map<String, Supplier<TypeTemplate>> registerEntities(Schema schema) {
            return Map.of();
        }

        @Override
        public Map<String, Supplier<TypeTemplate>> registerBlockEntities(Schema schema) {
            return Map.of();
        }

        @Override
        public void registerTypes(Schema schema, Map<String, Supplier<TypeTemplate>> entities,
                Map<String, Supplier<TypeTemplate>> blockEntities) {
            schema.registerType(true, RECORD, DSL::remainder);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
