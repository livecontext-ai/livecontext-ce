package com.apimarketplace.agent.service;

import com.apimarketplace.agent.client.dto.AgentObservabilityRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Unit-tests the PII-free {@code agent_run_stopped} analytics property bag.
 * The PostHog send path itself (gating / fire-and-forget) is covered by
 * {@code PostHogAnalyticsClientTest} in common-lib.
 */
class AgentObservabilityPostHogEventTest {

    @Test
    @DisplayName("agent_run_stopped props carry status/stop_reason/terminal_category/counts/ids - and never the tenant (distinct_id)")
    void buildsAgentRunStoppedProps() {
        AgentObservabilityRequest req = new AgentObservabilityRequest();
        req.setTenantId("tenant-1");
        req.setOrganizationId("org-1");
        req.setAgentType("agent");
        req.setProvider("deepseek");
        req.setModel("deepseek-chat");
        req.setStatus("COMPLETED");
        req.setStopReason("COMPLETED");
        req.setIterationCount(3);
        req.setTotalToolCalls(5);
        req.setTotalTokens(1200);
        req.setDurationMs(8400);
        UUID runId = UUID.randomUUID();
        req.setWorkflowRunId(runId);
        UUID execId = UUID.randomUUID();

        Map<String, Object> props = AgentObservabilityService.buildAgentRunStoppedProps(req, execId, new BigDecimal("0.42"));

        assertEquals("COMPLETED", props.get("status"));
        assertEquals("COMPLETED", props.get("stop_reason"));
        assertEquals("SUCCESS", props.get("terminal_category"));
        assertEquals("agent", props.get("agent_type"));
        assertEquals(3, props.get("iteration_count"));
        assertEquals(5, props.get("total_tool_calls"));
        assertEquals(0.42, (double) props.get("credits_consumed"), 1e-9);
        assertEquals("org-1", props.get("organization_id"));
        assertEquals(runId.toString(), props.get("workflow_run_id"));
        assertEquals(execId.toString(), props.get("agent_execution_id"));
        // The tenant is the distinct_id (passed separately) - it must NOT leak into properties.
        assertFalse(props.containsKey("tenant_id"));
        assertFalse(props.containsValue("tenant-1"));
    }

    @Test
    @DisplayName("terminal_category omitted when stop_reason null; null credits → 0.0; absent ids omitted")
    void nullSafe() {
        AgentObservabilityRequest req = new AgentObservabilityRequest();
        req.setTenantId("tenant-1");
        req.setStatus("FAILED");

        Map<String, Object> props = AgentObservabilityService.buildAgentRunStoppedProps(req, null, null);

        assertFalse(props.containsKey("terminal_category"));
        assertFalse(props.containsKey("workflow_run_id"));
        assertFalse(props.containsKey("agent_execution_id"));
        assertEquals(0.0, (double) props.get("credits_consumed"), 1e-9);
    }
}
