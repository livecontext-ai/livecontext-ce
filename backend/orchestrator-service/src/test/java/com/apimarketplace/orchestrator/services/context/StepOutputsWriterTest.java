package com.apimarketplace.orchestrator.services.context;

import com.apimarketplace.orchestrator.domain.workflow.Agent;
import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.InterfaceDef;
import com.apimarketplace.orchestrator.domain.workflow.Step;
import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for StepOutputsWriter - the centralized writer that prevents the alias-drift
 * bug class (3 production incidents in 7 days before this refactor).
 */
@DisplayName("StepOutputsWriter")
class StepOutputsWriterTest {

    @BeforeEach
    void clear() {
        StepOutputsWriter.clearCacheForTesting();
    }

    @AfterEach
    void clearAfter() {
        StepOutputsWriter.clearCacheForTesting();
    }

    @Nested
    @DisplayName("bareAlias()")
    class BareAlias {

        @Test
        @DisplayName("Returns substring after colon for prefixed keys")
        void extractsAfterColon() {
            assertThat(StepOutputsWriter.bareAlias("mcp:read_email")).isEqualTo("read_email");
            assertThat(StepOutputsWriter.bareAlias("trigger:start")).isEqualTo("start");
            assertThat(StepOutputsWriter.bareAlias("core:my_loop")).isEqualTo("my_loop");
            assertThat(StepOutputsWriter.bareAlias("agent:classify")).isEqualTo("classify");
            assertThat(StepOutputsWriter.bareAlias("table:users")).isEqualTo("users");
            assertThat(StepOutputsWriter.bareAlias("interface:form")).isEqualTo("form");
        }

        @Test
        @DisplayName("Returns null when no colon (no alias to mirror)")
        void returnsNullWithoutColon() {
            assertThat(StepOutputsWriter.bareAlias("read_email")).isNull();
            assertThat(StepOutputsWriter.bareAlias("anything")).isNull();
        }

        @Test
        @DisplayName("Returns null when colon is the last character (no alias content)")
        void returnsNullForTrailingColon() {
            assertThat(StepOutputsWriter.bareAlias("mcp:")).isNull();
            assertThat(StepOutputsWriter.bareAlias("trigger:")).isNull();
        }

        @Test
        @DisplayName("Tolerates null / empty input without NPE")
        void tolerantOfNullEmpty() {
            assertThat(StepOutputsWriter.bareAlias(null)).isNull();
            assertThat(StepOutputsWriter.bareAlias("")).isNull();
        }

        @Test
        @DisplayName("First colon is the separator (multi-colon keys keep tail intact)")
        void firstColonIsSeparator() {
            // The contract: only the FIRST colon separates prefix from alias.
            // Multi-segment IDs like "core:loop:iterate" become alias "loop:iterate"
            // - matching the legacy extractor semantics.
            assertThat(StepOutputsWriter.bareAlias("core:loop:iterate")).isEqualTo("loop:iterate");
        }
    }

    @Nested
    @DisplayName("writeWithAlias()")
    class WriteWithAlias {

        @Test
        @DisplayName("Writes BOTH full-key and bare alias from the same value reference")
        void writesBoth() {
            Map<String, Object> outputs = new HashMap<>();
            Map<String, Object> value = Map.of("output", Map.of("body", "hello"));

            StepOutputsWriter.writeWithAlias(outputs, "mcp:read_email", value);

            assertThat(outputs).hasSize(2);
            assertThat(outputs.get("mcp:read_email")).isSameAs(value);
            assertThat(outputs.get("read_email")).isSameAs(value);
        }

        @Test
        @DisplayName("Writes only the key when no prefix (no alias to mirror)")
        void writesOnlyKeyWhenNoPrefix() {
            Map<String, Object> outputs = new HashMap<>();
            Map<String, Object> value = Map.of("foo", "bar");

            StepOutputsWriter.writeWithAlias(outputs, "no_prefix", value);

            assertThat(outputs).hasSize(1);
            assertThat(outputs.get("no_prefix")).isSameAs(value);
        }

        @Test
        @DisplayName("Subsequent write overwrites BOTH entries (no half-update)")
        void overwriteRefreshesBoth() {
            Map<String, Object> outputs = new HashMap<>();
            Map<String, Object> v1 = Map.of("v", 1);
            Map<String, Object> v2 = Map.of("v", 2);

            StepOutputsWriter.writeWithAlias(outputs, "mcp:foo", v1);
            StepOutputsWriter.writeWithAlias(outputs, "mcp:foo", v2);

            // Both keys must point at v2 - otherwise the alias would drift and the bug class
            // returns. This is the contract the inline duplications kept forgetting.
            assertThat(outputs.get("mcp:foo")).isSameAs(v2);
            assertThat(outputs.get("foo")).isSameAs(v2);
        }

        @Test
        @DisplayName("Null map / null key are no-ops (no NPE)")
        void tolerantOfNulls() {
            assertThatCode(() -> StepOutputsWriter.writeWithAlias(null, "mcp:foo", Map.of())).doesNotThrowAnyException();
            assertThatCode(() -> StepOutputsWriter.writeWithAlias(new HashMap<>(), null, Map.of())).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("normalizeWrongPrefixes()")
    class NormalizeWrongPrefixes {

        @Test
        @DisplayName("Adds entry under correct full-key when storage had wrong prefix")
        void addsCorrectPrefix() {
            // Simulate: storage wrote 'mcp:start' but plan declares 'trigger:start'.
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("mcp:start", Map.of("triggered_at", "now"));

            Map<String, String> aliasMap = Map.of("start", "trigger:start");

            StepOutputsWriter.normalizeWrongPrefixes(outputs, aliasMap);

            assertThat(outputs).containsKey("trigger:start");
            // The bare alias of trigger:start (i.e., "start") is also written.
            assertThat(outputs).containsKey("start");
            // The legacy wrong-prefix entry is preserved (we add, never remove).
            assertThat(outputs).containsKey("mcp:start");
        }

        @Test
        @DisplayName("Wrong-prefix normalization adds the correct-prefix entry AND its bare alias "
                + "atomically - full-key and bare alias reference the SAME value object after the call")
        void atomicFullKeyAndAliasAfterNormalize() {
            // The contract that matters for the bug class: after normalizeWrongPrefixes, the
            // bare alias and the full key always reference the SAME value - never drift.
            // We don't pin which value wins when a pre-existing bare alias is present (HashMap
            // iteration order is not part of the spec); we only pin atomicity.
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("mcp:start", Map.of("from", "wrong-prefix"));

            Map<String, String> aliasMap = Map.of("start", "trigger:start");

            StepOutputsWriter.normalizeWrongPrefixes(outputs, aliasMap);

            // Correct-prefix entry was added.
            assertThat(outputs).containsKey("trigger:start");
            // And the bare alias of trigger:start ('start') points to the SAME value object -
            // this is the writeWithAlias atomicity contract.
            assertThat(outputs.get("start")).isSameAs(outputs.get("trigger:start"));
        }

        @Test
        @DisplayName("Records 'normalization' alias-collision via the collisionRecorder")
        void normalizationFiresMetric() {
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("mcp:start", Map.of("k", "v"));
            Map<String, String> aliasMap = Map.of("start", "trigger:start");

            java.util.List<String> recorded = new java.util.ArrayList<>();
            StepOutputsWriter.normalizeWrongPrefixes(outputs, aliasMap, recorded::add);

            assertThat(recorded).containsExactly("normalization");
        }

        @Test
        @DisplayName("No metric recorded when no wrong prefix to correct")
        void noMetricWhenNothingToCorrect() {
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("trigger:start", Map.of("k", "v"));
            Map<String, String> aliasMap = Map.of("start", "trigger:start");

            java.util.List<String> recorded = new java.util.ArrayList<>();
            StepOutputsWriter.normalizeWrongPrefixes(outputs, aliasMap, recorded::add);

            assertThat(recorded).isEmpty();
        }

        @Test
        @DisplayName("Already-correct entry: no write - normalize is for WRONG prefixes only")
        void noOpWhenAlreadyCorrect() {
            // normalizeWrongPrefixes ONLY adds the correct-prefix entry when it's MISSING.
            // It does NOT back-fill bare aliases for already-correct entries - that's the
            // responsibility of the writers at the storage→context boundary (RunContextService
            // Pass 1/2, ExecutionContext.withResult). Test pins the spec.
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("trigger:start", Map.of("k", "v"));

            Map<String, String> aliasMap = Map.of("start", "trigger:start");

            StepOutputsWriter.normalizeWrongPrefixes(outputs, aliasMap);

            assertThat(outputs).hasSize(1);
            assertThat(outputs).containsKey("trigger:start");
        }

        @Test
        @DisplayName("Null map / empty aliasMap are no-ops")
        void tolerantOfNullsAndEmpty() {
            assertThatCode(() -> StepOutputsWriter.normalizeWrongPrefixes(null, Map.of())).doesNotThrowAnyException();
            assertThatCode(() -> StepOutputsWriter.normalizeWrongPrefixes(new HashMap<>(), null)).doesNotThrowAnyException();
            assertThatCode(() -> StepOutputsWriter.normalizeWrongPrefixes(new HashMap<>(), Map.of())).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("aliasMapping() - memoized by planId")
    class AliasMappingCache {

        @Test
        @DisplayName("Caches result by planId, returning same instance on second call")
        void cachesByPlanId() {
            WorkflowPlan plan = makePlan("plan-1", "Read Email", "Send Slack");

            Map<String, String> first = StepOutputsWriter.aliasMapping(plan);
            Map<String, String> second = StepOutputsWriter.aliasMapping(plan);

            assertThat(second).isSameAs(first);
        }

        @Test
        @DisplayName("Distinct planIds produce distinct cache entries")
        void distinctPlanIds() {
            WorkflowPlan p1 = makePlan("plan-A", "Read Email");
            WorkflowPlan p2 = makePlan("plan-B", "Read Email");

            Map<String, String> m1 = StepOutputsWriter.aliasMapping(p1);
            Map<String, String> m2 = StepOutputsWriter.aliasMapping(p2);

            assertThat(m1).isNotSameAs(m2);
            // Both should contain the same alias mapping content
            assertThat(m1).containsEntry("read_email", "mcp:read_email");
            assertThat(m2).containsEntry("read_email", "mcp:read_email");
        }

        @Test
        @DisplayName("Plan without id is NOT cached (transient/anonymous plans)")
        void anonymousPlanNotCached() {
            int sizeBefore = StepOutputsWriter.cacheSizeForTesting();
            WorkflowPlan anonymous = makePlan(null, "Foo");

            StepOutputsWriter.aliasMapping(anonymous);

            assertThat(StepOutputsWriter.cacheSizeForTesting()).isEqualTo(sizeBefore);
        }

        @Test
        @DisplayName("Maps trigger / mcp / agent / core / table / interface labels correctly")
        void mapsAllNodeTypes() {
            // Build the inner mocks FIRST so their lenient().when() calls complete before
            // we enter the outer plan's when().thenReturn() chain. Nesting them inline causes
            // Mockito UnfinishedStubbing because the thread-local stubbing state collides.
            Trigger trigger = makeTrigger("Inbound");
            Step mcp = makeMcp("Send Slack");
            Agent agent = makeAgent("Classify Intent");
            Core core = makeCore("Route");
            Step table = makeMcp("Users Table");
            InterfaceDef iface = makeInterface("Form");

            WorkflowPlan plan = mock(WorkflowPlan.class);
            when(plan.getId()).thenReturn("plan-all");
            when(plan.getTriggers()).thenReturn(List.of(trigger));
            when(plan.getMcps()).thenReturn(List.of(mcp));
            when(plan.getAgents()).thenReturn(List.of(agent));
            when(plan.getCores()).thenReturn(List.of(core));
            when(plan.getTables()).thenReturn(List.of(table));
            when(plan.getInterfaces()).thenReturn(List.of(iface));

            Map<String, String> mapping = StepOutputsWriter.aliasMapping(plan);

            assertThat(mapping).containsEntry("inbound", "trigger:inbound");
            assertThat(mapping).containsEntry("send_slack", "mcp:send_slack");
            assertThat(mapping).containsEntry("classify_intent", "agent:classify_intent");
            assertThat(mapping).containsEntry("route", "core:route");
            assertThat(mapping).containsEntry("users_table", "table:users_table");
            assertThat(mapping).containsEntry("form", "interface:form");
        }
    }

    @Nested
    @DisplayName("Daily Email Digest regression - bug class closure")
    class DailyEmailDigestPerItemAliasStaysFresh {

        @Test
        @DisplayName("Per-item enrichment via writeWithAlias keeps the bare-alias entry fresh "
                + "(reproduces prod run 6c67cb76 epoch 3, fixed by aa9616b7d)")
        void perItemAliasStaysFresh() {
            // Reproduce the prod bug: outer V2StepByStepContextManager pre-loaded
            // stepOutputs with item 0's value under both full-key 'mcp:read_email' AND
            // alias 'read_email'. Then per-item enrichment runs for item 5 and writes
            // only the full key. Result: CodeNode reading $input.read_email still sees
            // item 0's data → wrong mail processed.
            Map<String, Object> outputs = new HashMap<>();
            Map<String, Object> item0 = Map.of("from", "Makrem ZEMZEMI", "subject", "YEEZY");
            // Pre-load from outer DB-load (this is what V2StepByStepContextManager produces).
            outputs.put("mcp:read_email", item0);
            outputs.put("read_email", item0);

            // Pre-fix code would have done: outputs.put("mcp:read_email", item5)
            // - leaving 'read_email' pointing at item0 (the bug).
            // Post-fix code does writeWithAlias, which updates BOTH atomically.
            Map<String, Object> item5 = Map.of("from", "LinkedIn", "subject", "Jérémy FRANCK");
            StepOutputsWriter.writeWithAlias(outputs, "mcp:read_email", item5);

            // Both lookups MUST resolve to item5 - that's the contract.
            assertThat(outputs.get("mcp:read_email")).isSameAs(item5);
            assertThat(outputs.get("read_email"))
                .as("Bare alias must be refreshed atomically with the full key - otherwise the "
                    + "Daily Email Digest bug returns: $input.read_email keeps item 0's data.")
                .isSameAs(item5);
        }
    }

    // ── Test fixtures ───────────────────────────────────────────────────────────

    private static WorkflowPlan makePlan(String planId, String... mcpLabels) {
        // Build child mocks FIRST so nested lenient().when() chains complete before the
        // outer plan stubbing starts. Inlining them inside the .thenReturn() argument
        // would interrupt the outer plan stubbing thread-local state → UnfinishedStubbing.
        List<Step> mcps = java.util.Arrays.stream(mcpLabels)
            .map(StepOutputsWriterTest::makeMcp)
            .toList();

        WorkflowPlan plan = mock(WorkflowPlan.class);
        lenient().when(plan.getId()).thenReturn(planId);
        lenient().when(plan.getTriggers()).thenReturn(List.of());
        lenient().when(plan.getMcps()).thenReturn(mcps);
        lenient().when(plan.getAgents()).thenReturn(List.of());
        lenient().when(plan.getCores()).thenReturn(List.of());
        lenient().when(plan.getTables()).thenReturn(List.of());
        lenient().when(plan.getInterfaces()).thenReturn(List.of());
        return plan;
    }

    private static Trigger makeTrigger(String label) {
        Trigger t = mock(Trigger.class);
        lenient().when(t.label()).thenReturn(label);
        lenient().when(t.id()).thenReturn("t-" + label);
        lenient().when(t.getNormalizedKey()).thenReturn("trigger:" + slug(label));
        return t;
    }

    private static Step makeMcp(String label) {
        Step s = mock(Step.class);
        lenient().when(s.label()).thenReturn(label);
        lenient().when(s.getNormalizedKey()).thenReturn("mcp:" + slug(label));
        return s;
    }

    private static Agent makeAgent(String label) {
        Agent a = mock(Agent.class);
        lenient().when(a.label()).thenReturn(label);
        lenient().when(a.getNormalizedKey()).thenReturn("agent:" + slug(label));
        return a;
    }

    private static Core makeCore(String label) {
        Core c = mock(Core.class);
        lenient().when(c.label()).thenReturn(label);
        lenient().when(c.id()).thenReturn("c-" + label);
        lenient().when(c.getNormalizedKey()).thenReturn("core:" + slug(label));
        return c;
    }

    private static InterfaceDef makeInterface(String label) {
        InterfaceDef i = mock(InterfaceDef.class);
        lenient().when(i.label()).thenReturn(label);
        lenient().when(i.getNormalizedKey()).thenReturn("interface:" + slug(label));
        return i;
    }

    private static String slug(String label) {
        return label.toLowerCase().replace(" ", "_");
    }
}
