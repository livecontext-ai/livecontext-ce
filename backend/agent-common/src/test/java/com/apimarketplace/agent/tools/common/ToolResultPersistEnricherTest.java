package com.apimarketplace.agent.tools.common;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ToolResultPersistEnricher")
class ToolResultPersistEnricherTest {

    private static final String VIZ_TYPE = "image_generation";

    private static ToolExecutionContext ctx(Map<String, Object> credentials) {
        return new ToolExecutionContext("tenant-42", credentials, Map.of(), Set.of(), null, null, null, null);
    }

    private static Map<String, Object> chatCreds() {
        Map<String, Object> m = new HashMap<>();
        m.put("conversationId", "conv-1");
        m.put("__messageId__", "msg-1");
        m.put("__agentId__", "agent-1");
        m.put("__toolCallId__", "tc-1");
        return m;
    }

    private static ToolResultPersistEnricher.PersistedInterface fakeInterface() {
        return new ToolResultPersistEnricher.PersistedInterface("iface-99", "Generated image");
    }

    @Nested
    @DisplayName("Credential extraction")
    class Credentials {

        @Test
        @DisplayName("missing conversationId → returns originalResult unchanged, persistFn never called")
        void missingConversationIdSkips() {
            ToolExecutionResult original = ToolExecutionResult.success(Map.of("foo", "bar"));
            AtomicBoolean called = new AtomicBoolean(false);
            ToolResultPersistEnricher.PersistFn persist = (c, p, d) -> { called.set(true); return fakeInterface(); };

            Map<String, Object> creds = new HashMap<>();
            creds.put("__messageId__", "msg-1"); // conversationId missing
            ToolExecutionResult out = ToolResultPersistEnricher.enrichAndPersist(
                    original, Map.of(), ctx(creds), VIZ_TYPE, persist, null);

            assertThat(out).isSameAs(original);
            assertThat(called).isFalse();
        }

        @Test
        @DisplayName("missing __messageId__ → returns originalResult unchanged")
        void missingMessageIdSkips() {
            ToolExecutionResult original = ToolExecutionResult.success(Map.of("foo", "bar"));
            ToolResultPersistEnricher.PersistFn persist = (c, p, d) -> fakeInterface();

            Map<String, Object> creds = new HashMap<>();
            creds.put("conversationId", "conv-1");
            ToolExecutionResult out = ToolResultPersistEnricher.enrichAndPersist(
                    original, Map.of(), ctx(creds), VIZ_TYPE, persist, null);

            assertThat(out).isSameAs(original);
        }

        @Test
        @DisplayName("null credentials → returns originalResult unchanged")
        void nullCredentialsSkips() {
            ToolExecutionResult original = ToolExecutionResult.success(Map.of());
            ToolResultPersistEnricher.PersistFn persist = (c, p, d) -> fakeInterface();

            ToolExecutionResult out = ToolResultPersistEnricher.enrichAndPersist(
                    original, Map.of(), ctx(null), VIZ_TYPE, persist, null);

            assertThat(out).isSameAs(original);
        }
    }

    @Nested
    @DisplayName("Persist function delegation")
    class PersistDelegation {

        @Test
        @DisplayName("persistFn returns null → originalResult passed through, no marker")
        void nullPersistResultPassesThrough() {
            ToolExecutionResult original = ToolExecutionResult.success(Map.of("k", "v"));
            ToolResultPersistEnricher.PersistFn persist = (c, p, d) -> null;

            ToolExecutionResult out = ToolResultPersistEnricher.enrichAndPersist(
                    original, Map.of(), ctx(chatCreds()), VIZ_TYPE, persist, null);

            assertThat(out).isSameAs(original);
        }

        @Test
        @DisplayName("persistFn throws → caught, originalResult returned, no propagation")
        void persistFnThrowsCaught() {
            ToolExecutionResult original = ToolExecutionResult.success(Map.of("k", "v"));
            ToolResultPersistEnricher.PersistFn persist = (c, p, d) -> {
                throw new RuntimeException("interface-service down");
            };

            ToolExecutionResult out = ToolResultPersistEnricher.enrichAndPersist(
                    original, Map.of(), ctx(chatCreds()), VIZ_TYPE, persist, null);

            assertThat(out).isSameAs(original);
        }

        @Test
        @DisplayName("persistFn receives the original parameters and data")
        void persistFnReceivesArgs() {
            ToolExecutionResult original = ToolExecutionResult.success(Map.of("res", 42));
            Map<String, Object> params = Map.of("query", "hello");
            AtomicReference<Map<String, Object>> seenParams = new AtomicReference<>();
            AtomicReference<Object> seenData = new AtomicReference<>();
            ToolResultPersistEnricher.PersistFn persist = (c, p, d) -> {
                seenParams.set(p);
                seenData.set(d);
                return fakeInterface();
            };

            ToolResultPersistEnricher.enrichAndPersist(
                    original, params, ctx(chatCreds()), VIZ_TYPE, persist, null);

            assertThat(seenParams.get()).containsEntry("query", "hello");
            assertThat(seenData.get()).isEqualTo(Map.of("res", 42));
        }
    }

    @Nested
    @DisplayName("Result enrichment")
    class Enrichment {

        @Test
        @DisplayName("success result gets [visualize:type:id] marker + display + metadata.visualization")
        void successAddsMarkerAndMetadata() {
            ToolExecutionResult original = ToolExecutionResult.success(Map.of("results", List.of(1, 2, 3)));
            ToolResultPersistEnricher.PersistFn persist = (c, p, d) -> fakeInterface();

            ToolExecutionResult out = ToolResultPersistEnricher.enrichAndPersist(
                    original, Map.of(), ctx(chatCreds()), VIZ_TYPE, persist, null);

            assertThat(out.success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) out.data();
            assertThat(data).containsEntry("results", List.of(1, 2, 3));
            assertThat(data).containsEntry("marker", "[visualize:image_generation:iface-99]");
            assertThat(data).containsKey("display");
            @SuppressWarnings("unchecked")
            Map<String, Object> display = (Map<String, Object>) data.get("display");
            assertThat(display).containsEntry("type", "image_generation");
            assertThat(display).containsEntry("id", "iface-99");
            assertThat(display).containsEntry("title", "Generated image");

            @SuppressWarnings("unchecked")
            Map<String, Object> viz = (Map<String, Object>) out.metadata().get("visualization");
            assertThat(viz).containsEntry("type", "image_generation");
            assertThat(viz).containsEntry("id", "iface-99");
        }

        @Test
        @DisplayName("non-Map originalData wrapped in {result: ...}")
        void nonMapDataWrapped() {
            ToolExecutionResult original = ToolExecutionResult.success("just a string");
            ToolResultPersistEnricher.PersistFn persist = (c, p, d) -> fakeInterface();

            ToolExecutionResult out = ToolResultPersistEnricher.enrichAndPersist(
                    original, Map.of(), ctx(chatCreds()), VIZ_TYPE, persist, null);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) out.data();
            assertThat(data).containsEntry("result", "just a string");
        }

        @Test
        @DisplayName("preserves pre-existing metadata entries")
        void preservesPreExistingMetadata() {
            ToolExecutionResult original = ToolExecutionResult.success(
                    Map.of("k", "v"),
                    Map.of("custom", "metadata-val"));
            ToolResultPersistEnricher.PersistFn persist = (c, p, d) -> fakeInterface();

            ToolExecutionResult out = ToolResultPersistEnricher.enrichAndPersist(
                    original, Map.of(), ctx(chatCreds()), VIZ_TYPE, persist, null);

            assertThat(out.metadata()).containsEntry("custom", "metadata-val");
            assertThat(out.metadata()).containsKey("visualization");
        }
    }

    @Nested
    @DisplayName("PostPersistHook")
    class PostHook {

        @Test
        @DisplayName("hook fires after successful persist")
        void hookFiresAfterPersist() {
            ToolExecutionResult original = ToolExecutionResult.success(Map.of());
            AtomicInteger calls = new AtomicInteger(0);
            ToolResultPersistEnricher.PersistFn persist = (c, p, d) -> fakeInterface();
            ToolResultPersistEnricher.PostPersistHook hook = (c, p, persisted) -> calls.incrementAndGet();

            ToolResultPersistEnricher.enrichAndPersist(
                    original, Map.of(), ctx(chatCreds()), VIZ_TYPE, persist, hook);

            assertThat(calls).hasValue(1);
        }

        @Test
        @DisplayName("null hook → enrichment proceeds")
        void nullHookOk() {
            ToolExecutionResult original = ToolExecutionResult.success(Map.of());
            ToolResultPersistEnricher.PersistFn persist = (c, p, d) -> fakeInterface();

            ToolExecutionResult out = ToolResultPersistEnricher.enrichAndPersist(
                    original, Map.of(), ctx(chatCreds()), VIZ_TYPE, persist, null);

            assertThat(out.success()).isTrue();
        }

        @Test
        @DisplayName("hook throws → swallowed, enrichment still returns marker (non-fatal)")
        void hookThrowsSwallowed() {
            ToolExecutionResult original = ToolExecutionResult.success(Map.of());
            ToolResultPersistEnricher.PersistFn persist = (c, p, d) -> fakeInterface();
            ToolResultPersistEnricher.PostPersistHook hook = (c, p, persisted) -> {
                throw new RuntimeException("redis down");
            };

            ToolExecutionResult out = ToolResultPersistEnricher.enrichAndPersist(
                    original, Map.of(), ctx(chatCreds()), VIZ_TYPE, persist, hook);

            assertThat(out.success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) out.data();
            assertThat(data).containsEntry("marker", "[visualize:image_generation:iface-99]");
        }

        @Test
        @DisplayName("hook does NOT fire when persist returns null")
        void hookNotFiredOnNullPersist() {
            ToolExecutionResult original = ToolExecutionResult.success(Map.of());
            AtomicInteger calls = new AtomicInteger(0);
            ToolResultPersistEnricher.PersistFn persist = (c, p, d) -> null;
            ToolResultPersistEnricher.PostPersistHook hook = (c, p, persisted) -> calls.incrementAndGet();

            ToolResultPersistEnricher.enrichAndPersist(
                    original, Map.of(), ctx(chatCreds()), VIZ_TYPE, persist, hook);

            assertThat(calls).hasValue(0);
        }
    }

    @Nested
    @DisplayName("deepCopyResultData")
    class DeepCopy {

        @Test
        @DisplayName("nested maps and lists are independent copies")
        void nestedCopyIndependent() {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("a", new LinkedHashMap<>(Map.of("nested", "v")));
            source.put("list", List.of(Map.of("k", 1)));

            Map<String, Object> copy = ToolResultPersistEnricher.deepCopyResultData(source);

            assertThat(copy).isNotSameAs(source);
            assertThat(copy.get("a")).isNotSameAs(source.get("a"));
            // mutating the copy's nested map should not affect source
            @SuppressWarnings("unchecked")
            Map<String, Object> copiedNested = (Map<String, Object>) copy.get("a");
            copiedNested.put("nested", "mutated");
            @SuppressWarnings("unchecked")
            Map<String, Object> srcNested = (Map<String, Object>) source.get("a");
            assertThat(srcNested.get("nested")).isEqualTo("v");
        }
    }
}
