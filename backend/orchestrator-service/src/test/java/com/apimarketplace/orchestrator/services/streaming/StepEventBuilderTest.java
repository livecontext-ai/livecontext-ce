package com.apimarketplace.orchestrator.services.streaming;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.domain.workflow.StepExecutionResult;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StepEventBuilder")
class StepEventBuilderTest {

    @Mock
    private WorkflowExecution mockExecution;

    private StepEventBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new StepEventBuilder();
    }

    @Nested
    @DisplayName("build() with DB status counts")
    class BuildWithDbStatusCountsTests {

        @Test
        @DisplayName("Should include all required event fields")
        void shouldIncludeAllRequiredFields() {
            when(mockExecution.getRunId()).thenReturn("run-1");
            when(mockExecution.getCurrentLevel()).thenReturn(0);

            StepExecutionResult result = new StepExecutionResult(
                "step1", NodeStatus.COMPLETED, "Done",
                Map.of("key", "val"), 150L, null
            );

            Map<String, Object> eventData = builder.build(
                mockExecution, "step1", "mcp:step1", "mcp:step1", result
            );

            assertEquals("step_executed", eventData.get("type"));
            assertEquals("run-1", eventData.get("runId"));
            assertEquals("step1", eventData.get("stepId"));
            assertEquals("mcp:step1", eventData.get("stepAlias"));
            assertEquals("mcp:step1", eventData.get("normalizedStepId"));
            assertEquals("mcp:step1", eventData.get("originalStepId"));
            assertEquals("Done", eventData.get("message"));
            assertEquals(150L, eventData.get("executionTime"));
            assertEquals("DAG", eventData.get("executionType"));
        }

        @Test
        @DisplayName("Should use DB status counts when provided")
        void shouldUseDbStatusCounts() {
            when(mockExecution.getRunId()).thenReturn("run-1");
            when(mockExecution.getCurrentLevel()).thenReturn(0);

            StepExecutionResult result = new StepExecutionResult(
                "step1", NodeStatus.COMPLETED, "Done",
                null, 100L, null
            );

            Map<String, Object> dbCounts = new LinkedHashMap<>();
            dbCounts.put("success", 10);
            dbCounts.put("failure", 2);
            dbCounts.put("skipped", 1);
            dbCounts.put("running", 0);

            Map<String, Object> eventData = builder.build(
                mockExecution, "step1", "mcp:step1", "mcp:step1", result, dbCounts
            );

            @SuppressWarnings("unchecked")
            Map<String, Object> statusCounts = (Map<String, Object>) eventData.get("statusCounts");
            assertNotNull(statusCounts);
            assertEquals(10, statusCounts.get("completed"));
            assertEquals(2, statusCounts.get("failed"));
            assertEquals(1, statusCounts.get("skipped"));
            assertEquals(0, statusCounts.get("running"));
            assertEquals(13, statusCounts.get("processed"));
            assertEquals(13, statusCounts.get("total"));
        }

        @Test
        @DisplayName("Should map COMPLETED status to UI 'completed'")
        void shouldMapCompletedStatus() {
            when(mockExecution.getRunId()).thenReturn("run-1");
            when(mockExecution.getCurrentLevel()).thenReturn(0);

            StepExecutionResult result = new StepExecutionResult(
                "step1", NodeStatus.COMPLETED, "OK",
                null, 0L, null
            );

            Map<String, Object> eventData = builder.build(
                mockExecution, "step1", "mcp:step1", "mcp:step1", result
            );

            assertEquals("completed", eventData.get("uiStatus"));
        }

        @Test
        @DisplayName("Should map FAILED status to UI 'error'")
        void shouldMapFailedStatus() {
            when(mockExecution.getRunId()).thenReturn("run-1");
            when(mockExecution.getCurrentLevel()).thenReturn(0);

            StepExecutionResult result = new StepExecutionResult(
                "step1", NodeStatus.FAILED, "Error occurred",
                null, 0L, null
            );

            Map<String, Object> eventData = builder.build(
                mockExecution, "step1", "mcp:step1", "mcp:step1", result
            );

            assertEquals("failed", eventData.get("uiStatus"));
        }

        @Test
        @DisplayName("Should include item metadata from output")
        void shouldIncludeItemMetadata() {
            when(mockExecution.getRunId()).thenReturn("run-1");
            when(mockExecution.getCurrentLevel()).thenReturn(0);

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("itemId", "item-42");
            output.put("triggerId", "trigger:webhook");
            output.put("absoluteIndex", 5);
            output.put("itemIndex", 3);
            output.put("tenantId", "tenant-1");

            StepExecutionResult result = new StepExecutionResult(
                "step1", NodeStatus.COMPLETED, "OK",
                output, 0L, null
            );

            Map<String, Object> eventData = builder.build(
                mockExecution, "step1", "mcp:step1", "mcp:step1", result
            );

            assertEquals("item-42", eventData.get("itemId"));
            assertEquals("trigger:webhook", eventData.get("triggerId"));
            assertEquals(5, eventData.get("absoluteIndex"));
            assertEquals(3, eventData.get("itemIndex"));
            assertEquals("tenant-1", eventData.get("tenantId"));
        }
    }

    @Nested
    @DisplayName("build() without DB status counts (default)")
    class BuildWithoutDbStatusCountsTests {

        @Test
        @DisplayName("Should create default counts from COMPLETED result")
        void shouldCreateDefaultCountsForCompleted() {
            when(mockExecution.getRunId()).thenReturn("run-1");
            when(mockExecution.getCurrentLevel()).thenReturn(0);
            when(mockExecution.getStepItemMetrics(any())).thenReturn(null);

            StepExecutionResult result = new StepExecutionResult(
                "step1", NodeStatus.COMPLETED, "OK",
                null, 0L, null
            );

            Map<String, Object> eventData = builder.build(
                mockExecution, "step1", "mcp:step1", "mcp:step1", result
            );

            @SuppressWarnings("unchecked")
            Map<String, Object> statusCounts = (Map<String, Object>) eventData.get("statusCounts");
            assertNotNull(statusCounts);
            assertEquals(1L, statusCounts.get("completed"));
            assertEquals(0L, statusCounts.get("failed"));
            assertEquals(0L, statusCounts.get("skipped"));
        }

        @Test
        @DisplayName("Should create default counts from FAILED result")
        void shouldCreateDefaultCountsForFailed() {
            when(mockExecution.getRunId()).thenReturn("run-1");
            when(mockExecution.getCurrentLevel()).thenReturn(0);
            when(mockExecution.getStepItemMetrics(any())).thenReturn(null);

            StepExecutionResult result = new StepExecutionResult(
                "step1", NodeStatus.FAILED, "Error",
                null, 0L, null
            );

            Map<String, Object> eventData = builder.build(
                mockExecution, "step1", "mcp:step1", "mcp:step1", result
            );

            @SuppressWarnings("unchecked")
            Map<String, Object> statusCounts = (Map<String, Object>) eventData.get("statusCounts");
            assertNotNull(statusCounts);
            assertEquals(0L, statusCounts.get("completed"));
            assertEquals(1L, statusCounts.get("failed"));
        }

        @Test
        @DisplayName("Should create default counts from SKIPPED result")
        void shouldCreateDefaultCountsForSkipped() {
            when(mockExecution.getRunId()).thenReturn("run-1");
            when(mockExecution.getCurrentLevel()).thenReturn(0);
            when(mockExecution.getStepItemMetrics(any())).thenReturn(null);

            StepExecutionResult result = new StepExecutionResult(
                "step1", NodeStatus.COMPLETED, "Skipped",
                null, 0L, null
            );

            Map<String, Object> eventData = builder.build(
                mockExecution, "step1", "mcp:step1", "mcp:step1", result
            );

            @SuppressWarnings("unchecked")
            Map<String, Object> statusCounts = (Map<String, Object>) eventData.get("statusCounts");
            assertNotNull(statusCounts);
            assertEquals(1L, statusCounts.get("completed"));
            assertEquals(0L, statusCounts.get("failed"));
            assertEquals(0L, statusCounts.get("skipped"));
        }
    }

    @Nested
    @DisplayName("build() forwards browser-agent live-view fields")
    class BrowserAgentFieldForwardingTests {

        @Test
        @DisplayName("Forwards session_id, cdp_token, cdp_ws_url, run_id, node_id from raw output")
        void forwardsBrowserAgentFields() {
            when(mockExecution.getRunId()).thenReturn("run-42");
            when(mockExecution.getCurrentLevel()).thenReturn(0);
            when(mockExecution.getStepItemMetrics(any())).thenReturn(null);

            Map<String, Object> rawOutput = new LinkedHashMap<>();
            rawOutput.put("session_id", "ses_abc");
            rawOutput.put("cdp_token", "eyJtoken.fake.signature");
            rawOutput.put("cdp_ws_url", "wss://websearch-host.example/cdp/ses_abc");
            rawOutput.put("step_index", 7);
            rawOutput.put("last_action", "click #login");
            rawOutput.put("cost_usd", 0.043);
            rawOutput.put("run_id", "run-42");
            rawOutput.put("node_id", "agent:browser_agent");
            // Non-browser fields should still flow through (regression check).
            rawOutput.put("itemId", "item-1");

            StepExecutionResult result = new StepExecutionResult(
                "browser-step", NodeStatus.COMPLETED, "Done",
                rawOutput, 1500L, null
            );

            Map<String, Object> eventData = builder.build(
                mockExecution, "browser-step", "agent:browser_agent",
                "agent:browser_agent", result
            );

            assertEquals("ses_abc", eventData.get("session_id"));
            assertEquals("eyJtoken.fake.signature", eventData.get("cdp_token"));
            assertEquals("wss://websearch-host.example/cdp/ses_abc", eventData.get("cdp_ws_url"));
            assertEquals(7, eventData.get("step_index"));
            assertEquals("click #login", eventData.get("last_action"));
            assertEquals(0.043, eventData.get("cost_usd"));
            // run_id + node_id required by the chat-side AgentBrowsePanelContent
            // for token refresh + takeover-resume (BrowserLiveCdpPanel gates
            // scheduleReconnect on both runId AND sessionId being present).
            assertEquals("run-42", eventData.get("run_id"));
            assertEquals("agent:browser_agent", eventData.get("node_id"));
            // Pre-existing field forwarding still works.
            assertEquals("item-1", eventData.get("itemId"));
        }

        @Test
        @DisplayName("Omits browser fields when raw output has none - no-op for non-browser nodes")
        void omitsBrowserFieldsForOtherNodeTypes() {
            when(mockExecution.getRunId()).thenReturn("run-1");
            when(mockExecution.getCurrentLevel()).thenReturn(0);
            when(mockExecution.getStepItemMetrics(any())).thenReturn(null);

            // A regular non-browser step output.
            Map<String, Object> rawOutput = Map.of("itemId", "item-1", "result", "ok");
            StepExecutionResult result = new StepExecutionResult(
                "mcp-step", NodeStatus.COMPLETED, "OK",
                rawOutput, 100L, null
            );

            Map<String, Object> eventData = builder.build(
                mockExecution, "mcp-step", "mcp:step1", "mcp:step1", result
            );

            assertFalse(eventData.containsKey("session_id"));
            assertFalse(eventData.containsKey("cdp_token"));
            assertFalse(eventData.containsKey("cdp_ws_url"));
            assertFalse(eventData.containsKey("step_index"));
        }
    }
}
