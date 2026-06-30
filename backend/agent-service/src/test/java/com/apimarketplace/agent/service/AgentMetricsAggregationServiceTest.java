package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentExecutionToolCallEntity;
import com.apimarketplace.agent.repository.AgentMetricsAggregationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit coverage for the option-D org-aware rollup WRITE path in
 * {@link AgentMetricsAggregationService}: the per-agent tool/resource/model rollup feeds
 * and the callee-side sub-agent feed, plus the resource-id extraction whose precedence
 * MUST stay byte-identical to the V345 backfill SQL CASE and the read query CASE.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentMetricsAggregationService - option D org rollups")
class AgentMetricsAggregationServiceTest {

    private static final String ORG = "org-acme";
    private static final String TENANT = "tenant-1";
    private static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String MODEL = "claude-3-sonnet";

    @Mock private AgentMetricsAggregationRepository repository;
    private AgentMetricsAggregationService service;

    @BeforeEach
    void setUp() {
        service = new AgentMetricsAggregationService(repository);
    }

    private static AgentExecutionToolCallEntity toolCall(String name, boolean success, Long durationMs,
                                                         boolean repeat, Map<String, Object> args) {
        AgentExecutionToolCallEntity tc = new AgentExecutionToolCallEntity();
        tc.setToolName(name);
        tc.setSuccess(success);
        tc.setDurationMs(durationMs);
        tc.setRepeat(repeat);
        tc.setArguments(args);
        return tc;
    }

    // ── resource-id extraction (the 3-place precedence contract) ──────────────
    @Nested
    @DisplayName("extractResourceId - family COALESCE precedence (kept in sync with backfill + read SQL)")
    class ExtractResourceId {
        @Test
        @DisplayName("table prefers table_id, then datasource_id, then id")
        void tablePrecedence() {
            assertThat(AgentMetricsAggregationService.extractResourceId("table",
                Map.of("table_id", "t1", "datasource_id", "d1", "id", "i1"))).isEqualTo("t1");
            assertThat(AgentMetricsAggregationService.extractResourceId("table",
                Map.of("datasource_id", "d1", "id", "i1"))).isEqualTo("d1");
            assertThat(AgentMetricsAggregationService.extractResourceId("table",
                Map.of("id", "i1"))).isEqualTo("i1");
            assertThat(AgentMetricsAggregationService.extractResourceId("table", Map.of("other", "x"))).isNull();
        }

        @Test
        @DisplayName("interface/workflow fall back to id; application/skill do NOT")
        void otherFamilies() {
            assertThat(AgentMetricsAggregationService.extractResourceId("interface", Map.of("id", "i"))).isEqualTo("i");
            assertThat(AgentMetricsAggregationService.extractResourceId("workflow", Map.of("id", "w"))).isEqualTo("w");
            assertThat(AgentMetricsAggregationService.extractResourceId("application", Map.of("id", "a"))).isNull();
            assertThat(AgentMetricsAggregationService.extractResourceId("application", Map.of("application_id", "a"))).isEqualTo("a");
            assertThat(AgentMetricsAggregationService.extractResourceId("skill", Map.of("skill_id", "s"))).isEqualTo("s");
        }

        @Test
        @DisplayName("non-family tool and null args yield null; numeric ids stringify like SQL ->>")
        void nonFamilyAndNumeric() {
            assertThat(AgentMetricsAggregationService.extractResourceId("web_search", Map.of("id", "x"))).isNull();
            assertThat(AgentMetricsAggregationService.extractResourceId("table", null)).isNull();
            assertThat(AgentMetricsAggregationService.extractResourceId("table", Map.of("table_id", 42L))).isEqualTo("42");
        }
    }

    // ── per-call rollup feeds ─────────────────────────────────────────────────
    @Test
    @DisplayName("a tool call feeds the org tool rollup with the RAW nullable duration (so the sample count is honest)")
    void feedsToolOrgRollupWithRawDuration() {
        AgentExecutionToolCallEntity withDur = toolCall("web_search", true, 120L, false, Map.of());
        AgentExecutionToolCallEntity nullDur = toolCall("web_search", true, null, false, Map.of());

        service.updateAggregations(TENANT, ORG, AGENT, "anthropic", MODEL, true, "end_turn", false,
            5000L, 1500L, 2, 2, List.of(withDur, nullDur));

        verify(repository).upsertToolCallStatsByAgentOrg(
            eq(ORG), eq(TENANT), eq(AGENT), eq("web_search"), eq(true), eq(120L), eq(false), any());
        // The null-duration call passes null through (NOT coerced to 0) so duration_sample_count
        // stays honest and the read's AVG-ignoring-NULL is reproduced.
        verify(repository).upsertToolCallStatsByAgentOrg(
            eq(ORG), eq(TENANT), eq(AGENT), eq("web_search"), eq(true), isNull(), eq(false), any());
    }

    @Test
    @DisplayName("a resource-family call feeds the resource rollup with the extracted id; a non-family call does not")
    void feedsResourceRollupOnlyForFamilyTools() {
        AgentExecutionToolCallEntity table = toolCall("table", true, 10L, false, Map.of("table_id", "42"));
        AgentExecutionToolCallEntity search = toolCall("web_search", true, 10L, false, Map.of("id", "nope"));

        service.updateAggregations(TENANT, ORG, AGENT, "anthropic", MODEL, true, "end_turn", false,
            10L, 5L, 1, 2, List.of(table, search));

        verify(repository).upsertResourceCallStatsByAgentOrg(ORG, TENANT, AGENT, "table", "42", true);
        verify(repository, never()).upsertResourceCallStatsByAgentOrg(
            anyString(), anyString(), any(), eq("web_search"), anyString(), anyBoolean());
    }

    @Test
    @DisplayName("a run with no agent entity feeds NO org rollups but still feeds the tenant-wide tool stats")
    void orgRollupsSkippedWhenNoAgentEntity() {
        AgentExecutionToolCallEntity table = toolCall("table", true, 10L, false, Map.of("table_id", "42"));

        service.updateAggregations(TENANT, ORG, null, "anthropic", MODEL, true, "end_turn", false,
            10L, 5L, 1, 1, List.of(table));

        // Per-agent rollups all require agent_entity_id (general-chat rows have none and the
        // reads filter agent_entity_id IS NOT NULL) - none should fire.
        verify(repository, never()).upsertToolCallStatsByAgentOrg(
            anyString(), anyString(), any(), anyString(), anyBoolean(), any(), anyBoolean(), any());
        verify(repository, never()).upsertResourceCallStatsByAgentOrg(
            anyString(), anyString(), any(), anyString(), anyString(), anyBoolean());
        verify(repository, never()).upsertModelExecStatsByAgentOrg(
            anyString(), anyString(), any(), any(), anyBoolean(), anyBoolean(), anyBoolean());
        // The tenant-wide tool rollup (no agent dimension) is unaffected.
        verify(repository).upsertToolCallStats(eq(TENANT), eq("table"), eq(true), eq(10L), eq(false), any());
    }

    @Test
    @DisplayName("the model rollup is skipped when model is null")
    void modelRollupSkippedWhenModelNull() {
        service.updateAggregations(TENANT, ORG, AGENT, "anthropic", null, true, "end_turn", false,
            1L, 1L, 0, 0, List.of());
        verify(repository, never()).upsertModelExecStatsByAgentOrg(
            anyString(), anyString(), any(), any(), anyBoolean(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("blank org skips ALL org rollups but legacy tenant rollups still run")
    void blankOrgSkipsOrgRollups() {
        AgentExecutionToolCallEntity table = toolCall("table", true, 10L, false, Map.of("table_id", "42"));

        service.updateAggregations(TENANT, "  ", AGENT, "anthropic", MODEL, true, "end_turn", false,
            10L, 5L, 1, 1, List.of(table));

        verify(repository, never()).upsertToolCallStatsByAgentOrg(
            anyString(), anyString(), any(), anyString(), anyBoolean(), any(), anyBoolean(), any());
        verify(repository, never()).upsertResourceCallStatsByAgentOrg(
            anyString(), anyString(), any(), anyString(), anyString(), anyBoolean());
        verify(repository, never()).upsertModelExecStatsByAgentOrg(
            anyString(), anyString(), any(), anyString(), anyBoolean(), anyBoolean(), anyBoolean());
        // legacy per-agent rollup still fired
        verify(repository).upsertToolCallStatsByAgent(eq(TENANT), eq(AGENT), eq("table"), eq(true), eq(10L), eq(false), any());
    }

    // ── model rollup status classification ────────────────────────────────────
    @Nested
    @DisplayName("model rollup completed/failed/budget mapping (resolved-status parity)")
    class ModelMapping {
        @Test
        @DisplayName("success → completed only")
        void completed() {
            service.updateAggregations(TENANT, ORG, AGENT, "anthropic", MODEL, true, "end_turn", false,
                1L, 1L, 0, 1, List.of());
            verify(repository).upsertModelExecStatsByAgentOrg(ORG, TENANT, AGENT, MODEL, true, false, false);
        }

        @Test
        @DisplayName("a genuine failure (not success, not cancelled) → failed, not budget")
        void failed() {
            service.updateAggregations(TENANT, ORG, AGENT, "anthropic", MODEL, false, "ERROR", false,
                1L, 1L, 0, 1, List.of());
            verify(repository).upsertModelExecStatsByAgentOrg(ORG, TENANT, AGENT, MODEL, false, true, false);
        }

        @Test
        @DisplayName("CANCELLED/STOPPED_BY_USER/TIMEOUT → neither completed nor failed")
        void cancelledIsNeither() {
            service.updateAggregations(TENANT, ORG, AGENT, "anthropic", MODEL, false, "CANCELLED", false,
                1L, 1L, 0, 1, List.of());
            verify(repository).upsertModelExecStatsByAgentOrg(ORG, TENANT, AGENT, MODEL, false, false, false);
        }

        @Test
        @DisplayName("BUDGET_EXHAUSTED failure → failed AND budget (subset)")
        void budgetExhaustedSubset() {
            service.updateAggregations(TENANT, ORG, AGENT, "anthropic", MODEL, false, "BUDGET_EXHAUSTED", false,
                1L, 0L, 0, 1, List.of());
            verify(repository).upsertModelExecStatsByAgentOrg(ORG, TENANT, AGENT, MODEL, false, true, true);
        }
    }

    // ── callee-side sub-agent feed ─────────────────────────────────────────────
    @Nested
    @DisplayName("recordSubAgentCallFromCallee - status-based, no-ops without caller/org")
    class SubAgentCallee {
        private static final UUID CALLER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        private static final UUID CALLEE = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

        @Test
        @DisplayName("COMPLETED → success; FAILED → failure; CANCELLED → neither")
        void statusMapping() {
            service.recordSubAgentCallFromCallee(ORG, TENANT, CALLER, CALLEE, "COMPLETED");
            verify(repository).upsertSubAgentCallStatsOrg(ORG, TENANT, CALLER, CALLEE, true, false);

            service.recordSubAgentCallFromCallee(ORG, TENANT, CALLER, CALLEE, "FAILED");
            verify(repository).upsertSubAgentCallStatsOrg(ORG, TENANT, CALLER, CALLEE, false, true);

            service.recordSubAgentCallFromCallee(ORG, TENANT, CALLER, CALLEE, "CANCELLED");
            verify(repository).upsertSubAgentCallStatsOrg(ORG, TENANT, CALLER, CALLEE, false, false);
        }

        @Test
        @DisplayName("no caller (top-level execution) or blank org → no-op")
        void noOpGuards() {
            service.recordSubAgentCallFromCallee(ORG, TENANT, null, CALLEE, "COMPLETED");
            service.recordSubAgentCallFromCallee("  ", TENANT, CALLER, CALLEE, "COMPLETED");
            verify(repository, never()).upsertSubAgentCallStatsOrg(
                anyString(), anyString(), any(), any(), anyBoolean(), anyBoolean());
        }
    }
}
