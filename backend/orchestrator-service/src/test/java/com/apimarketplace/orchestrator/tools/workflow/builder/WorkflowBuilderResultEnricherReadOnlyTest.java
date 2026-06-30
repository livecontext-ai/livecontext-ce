package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the read-only "no side-panel focus" contract.
 *
 * <p>Audit a4eee2de3c05abc6c (2026-05-14) flagged that the original 14f9d6032 fix
 * only skipped the enricher's own {@code putIfAbsent}, leaving upstream
 * {@code visualization} (e.g. {@code WorkflowCrudModule.executeGet:325} pre-fix)
 * intact. These tests pin the post-audit contract: a read-only action MUST result
 * in {@code metadata.visualization} being absent, regardless of whether the
 * downstream CRUD handler injected it, regardless of whether a builder session
 * is loaded.
 */
@DisplayName("WorkflowBuilderResultEnricher - read-only viz stripping")
class WorkflowBuilderResultEnricherReadOnlyTest {

    private WorkflowBuilderSessionManager sessionManager;
    private WorkflowBuilderResultEnricher enricher;

    @BeforeEach
    void setUp() {
        sessionManager = mock(WorkflowBuilderSessionManager.class);
        var sessionStore = mock(WorkflowBuilderSessionStore.class);
        when(sessionStore.get(any())).thenReturn(java.util.Optional.empty());
        when(sessionManager.getSessionStore()).thenReturn(sessionStore);
        when(sessionManager.getSessionsForTenant(any())).thenReturn(List.of());

        WorkflowBuilderLogger buildLogger = Mockito.mock(WorkflowBuilderLogger.class);
        enricher = new WorkflowBuilderResultEnricher(sessionManager, buildLogger);
    }

    private static Map<String, Object> vizMeta(String type, String id) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("visualization", Map.of("type", type, "id", id, "title", "Anything"));
        return meta;
    }

    // ============================================================
    // No-session path - covers WorkflowCrudModule.executeGet shape
    // ============================================================

    @Nested
    @DisplayName("No active session loaded")
    class NoSession {

        @Test
        @DisplayName("Strips upstream-injected visualization for action='get' (regression for WorkflowCrudModule.executeGet pre-fix)")
        void strips_get() {
            ToolExecutionResult upstream = new ToolExecutionResult(
                    true, Map.of("id", "wf-123"), null, null, vizMeta("workflow", "wf-123"));

            ToolExecutionResult enriched = enricher.addSessionSnapshot(upstream, Map.of(), "tenant-1", "get");

            assertThat(enriched.metadata()).doesNotContainKey("visualization");
        }

        @Test
        @DisplayName("Strips upstream-injected visualization for action='get_run'")
        void strips_getRun() {
            ToolExecutionResult upstream = new ToolExecutionResult(
                    true, Map.of("run_id", "r-1"), null, null, vizMeta("workflow_run", "wf-1"));
            ToolExecutionResult enriched = enricher.addSessionSnapshot(upstream, Map.of(), "tenant-1", "get_run");
            assertThat(enriched.metadata()).doesNotContainKey("visualization");
        }

        @Test
        @DisplayName("Strips visualization for action='get_node_output'")
        void strips_getNodeOutput() {
            ToolExecutionResult upstream = new ToolExecutionResult(
                    true, Map.of("node_id", "n-1"), null, null, vizMeta("workflow_run", "wf-1"));
            ToolExecutionResult enriched = enricher.addSessionSnapshot(upstream, Map.of(), "tenant-1", "get_node_output");
            assertThat(enriched.metadata()).doesNotContainKey("visualization");
        }

        @Test
        @DisplayName("Strips visualization for every action in READ_ONLY_ACTIONS")
        void strips_everyReadOnly() {
            for (String action : WorkflowBuilderActionConfig.READ_ONLY_ACTIONS) {
                ToolExecutionResult upstream = new ToolExecutionResult(
                        true, Map.of(), null, null, vizMeta("workflow", "wf-x"));
                ToolExecutionResult enriched = enricher.addSessionSnapshot(upstream, Map.of(), "t", action);
                assertThat(enriched.metadata())
                        .as("action='%s' must NOT carry visualization through the enricher", action)
                        .doesNotContainKey("visualization");
            }
        }

        @Test
        @DisplayName("Keeps upstream visualization for action='load' (write action - focus IS the intent)")
        void keeps_load() {
            ToolExecutionResult upstream = new ToolExecutionResult(
                    true, Map.of(), null, null, vizMeta("workflow", "wf-1"));
            ToolExecutionResult enriched = enricher.addSessionSnapshot(upstream, Map.of(), "tenant-1", "load");
            assertThat(enriched.metadata()).containsKey("visualization");
        }

        @Test
        @DisplayName("Pass-through (no metadata mutation) when there's no upstream visualization to strip")
        void passthrough_noViz() {
            ToolExecutionResult upstream = new ToolExecutionResult(
                    true, Map.of("foo", "bar"), null, null, Map.of("other", "value"));
            ToolExecutionResult enriched = enricher.addSessionSnapshot(upstream, Map.of(), "tenant-1", "get");
            // Original result returned as-is; no allocation, no churn.
            assertThat(enriched).isSameAs(upstream);
        }

        @Test
        @DisplayName("Failed results are passed through untouched (read-only or not)")
        void failedResult_passthrough() {
            ToolExecutionResult failed = ToolExecutionResult.failure("oops");
            ToolExecutionResult enriched = enricher.addSessionSnapshot(failed, Map.of(), "tenant-1", "get_run");
            assertThat(enriched).isSameAs(failed);
        }
    }

    // ============================================================
    // Session-loaded path - re-audit MUST-FIX (8.6/10 → 9.0+)
    // ============================================================

    @Nested
    @DisplayName("Active builder session loaded for tenant")
    class SessionLoaded {

        private void mockSingleSession(String workflowId, String workflowName) {
            WorkflowBuilderSession session = mock(WorkflowBuilderSession.class);
            when(session.getLoadedWorkflowId()).thenReturn(workflowId);
            when(session.getWorkflowName()).thenReturn(workflowName);
            when(sessionManager.getSessionsForTenant("tenant-1")).thenReturn(List.of(session));
        }

        @Test
        @DisplayName("Strips upstream visualization on read-only action even when session is loaded (defense in depth)")
        void readOnly_strips_visualizationDespiteSession() {
            // Reproduces the audit-flagged path: a CRUD handler injects viz upstream,
            // then the enricher runs WITH a session loaded. Pre-eee8a0065, the
            // putIfAbsent skip was a no-op (viz already present). Post-fix, the
            // enricher MUST actively remove the upstream viz.
            mockSingleSession("wf-abc", "My Workflow");
            ToolExecutionResult upstream = new ToolExecutionResult(
                    true, Map.of("id", "wf-abc"), null, null, vizMeta("workflow", "wf-abc"));

            ToolExecutionResult enriched = enricher.addSessionSnapshot(upstream, Map.of(), "tenant-1", "get");

            assertThat(enriched.metadata()).doesNotContainKey("visualization");
            // draft_id MUST still be injected - session context is independent from focus control.
            assertThat(enriched.data()).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                    .containsEntry("draft_id", "wf-abc");
        }

        @Test
        @DisplayName("Injects fresh session-based visualization on WRITE action (load / execute / modify keep their focus)")
        void writeAction_injectsSessionVisualization() {
            mockSingleSession("wf-xyz", "FlyFinder");
            ToolExecutionResult upstream = new ToolExecutionResult(
                    true, Map.of(), null, null, Map.of());

            ToolExecutionResult enriched = enricher.addSessionSnapshot(upstream, Map.of(), "tenant-1", "add_node");

            assertThat(enriched.metadata()).containsKey("visualization");
            @SuppressWarnings("unchecked")
            Map<String, Object> viz = (Map<String, Object>) enriched.metadata().get("visualization");
            assertThat(viz).containsEntry("type", "workflow")
                    .containsEntry("id", "wf-xyz")
                    .containsEntry("title", "FlyFinder");
        }

        @Test
        @DisplayName("Unknown / null action defaults to write path (viz preserved) - null-safety guard")
        void unknownAction_defaultsToWrite() {
            // canonicalAction == null is the "I don't know what this is" case. The
            // helper returns isReadOnlyAction(null) == false, so the enricher falls
            // into the write branch and the focus is preserved. Documents that
            // unknown actions DEFAULT TO FOCUS (denylist semantics) - flagged as a
            // NICE-TO-HAVE in the audit; this test pins the contract.
            mockSingleSession("wf-1", "Some Workflow");
            ToolExecutionResult upstream = new ToolExecutionResult(
                    true, Map.of(), null, null, Map.of());

            ToolExecutionResult enriched = enricher.addSessionSnapshot(upstream, Map.of(), "tenant-1", null);

            assertThat(enriched.metadata()).containsKey("visualization");
        }

        @Test
        @DisplayName("Unknown non-null action defaults to write path (denylist semantics also apply to strings)")
        void unknownStringAction_defaultsToWrite() {
            // Companion to unknownAction_defaultsToWrite: covers the case where
            // canonicalAction is a non-null string but not in READ_ONLY_ACTIONS
            // (e.g. a typo, a future action forgotten in the set, a renamed alias).
            // Same contract: unknown → write-focus path. Closes the audit gap that
            // only `null` was pinned, not arbitrary unknown strings.
            mockSingleSession("wf-1", "Some Workflow");
            ToolExecutionResult upstream = new ToolExecutionResult(
                    true, Map.of(), null, null, Map.of());

            ToolExecutionResult enriched = enricher.addSessionSnapshot(upstream, Map.of(), "tenant-1", "foobar_not_in_any_set");

            assertThat(enriched.metadata()).containsKey("visualization");
        }
    }
}
