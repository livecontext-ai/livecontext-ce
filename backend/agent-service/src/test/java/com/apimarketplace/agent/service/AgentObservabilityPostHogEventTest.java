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
    @DisplayName("terminal_category omitted when stop_reason is null AND the status cannot be inferred; null credits → 0.0; absent ids omitted")
    void nullSafe() {
        AgentObservabilityRequest req = new AgentObservabilityRequest();
        req.setTenantId("tenant-1");
        // A terminal status (COMPLETED / FAILED) would now be inferred into a
        // category (see the inference tests below); a non-terminal one must not.
        req.setStatus("RUNNING");

        Map<String, Object> props = AgentObservabilityService.buildAgentRunStoppedProps(req, null, null);

        assertFalse(props.containsKey("terminal_category"));
        assertFalse(props.containsKey("workflow_run_id"));
        assertFalse(props.containsKey("agent_execution_id"));
        assertEquals(0.0, (double) props.get("credits_consumed"), 1e-9);
    }

    // ── stop_reason inference (regression: classify / CLI rows had none) ──────
    //
    // 281 of 375 agent_run_stopped events in one prod month carried no stop_reason
    // and therefore no terminal_category: the classify and CLI producers never
    // named one. The producers now do; the sink also infers from the outcome so
    // a producer that forgets (or an older orchestrator mid-rollout) still lands
    // in a category, flagged as inferred.

    @Test
    @DisplayName("a record without stop_reason infers it from a COMPLETED status and says so")
    void infersCompletedFromStatus() {
        AgentObservabilityRequest req = new AgentObservabilityRequest();
        req.setStatus("COMPLETED");
        req.setAgentType("classify");

        Map<String, Object> p = AgentObservabilityService.buildAgentRunStoppedProps(req, UUID.randomUUID(), BigDecimal.ZERO);

        assertEquals("COMPLETED", p.get("stop_reason"));
        assertEquals("SUCCESS", p.get("terminal_category"));
        assertEquals(true, p.get("stop_reason_inferred"));
    }

    @Test
    @DisplayName("a FAILED status without stop_reason infers ERROR / FAILURE")
    void infersErrorFromFailedStatus() {
        AgentObservabilityRequest req = new AgentObservabilityRequest();
        req.setStatus("FAILED");

        Map<String, Object> p = AgentObservabilityService.buildAgentRunStoppedProps(req, UUID.randomUUID(), null);

        assertEquals("ERROR", p.get("stop_reason"));
        assertEquals("FAILURE", p.get("terminal_category"));
        assertEquals(true, p.get("stop_reason_inferred"));
    }

    @Test
    @DisplayName("an explicit stop_reason is never overridden and is not flagged as inferred")
    void explicitStopReasonWins() {
        AgentObservabilityRequest req = new AgentObservabilityRequest();
        req.setStatus("COMPLETED");
        req.setStopReason("STOPPED_BY_USER");

        Map<String, Object> p = AgentObservabilityService.buildAgentRunStoppedProps(req, UUID.randomUUID(), null);

        assertEquals("STOPPED_BY_USER", p.get("stop_reason"));
        assertEquals("PARTIAL", p.get("terminal_category"));
        assertFalse(p.containsKey("stop_reason_inferred"));
    }

    @Test
    @DisplayName("an unknown status yields no stop_reason rather than a guessed category")
    void unknownStatusStaysNull() {
        assertEquals("COMPLETED", AgentObservabilityService.inferStopReasonFromStatus("partial_success"));
        assertEquals("ERROR", AgentObservabilityService.inferStopReasonFromStatus(" failed "));
        assertEquals(null, AgentObservabilityService.inferStopReasonFromStatus("RUNNING"));
        assertEquals(null, AgentObservabilityService.inferStopReasonFromStatus(null));

        AgentObservabilityRequest req = new AgentObservabilityRequest();
        req.setStatus("RUNNING");
        Map<String, Object> p = AgentObservabilityService.buildAgentRunStoppedProps(req, UUID.randomUUID(), null);
        assertEquals(null, p.get("stop_reason"));
        assertFalse(p.containsKey("terminal_category"));
        assertFalse(p.containsKey("stop_reason_inferred"));
    }

    // ── key_route / model_replaced (analytics v2) ─────────────────────────────

    @Test
    @DisplayName("key_route is the run's key route lowercased (own_key / platform), omitted when unpinned")
    void keyRoute() {
        AgentObservabilityRequest req = new AgentObservabilityRequest();
        req.setStatus("COMPLETED");

        req.setKeyRoute("OWN_KEY");
        assertEquals("own_key", AgentObservabilityService.buildAgentRunStoppedProps(req, null, null).get("key_route"));
        req.setKeyRoute("PLATFORM");
        assertEquals("platform", AgentObservabilityService.buildAgentRunStoppedProps(req, null, null).get("key_route"));
        req.setKeyRoute(null);
        assertFalse(AgentObservabilityService.buildAgentRunStoppedProps(req, null, null).containsKey("key_route"));
    }

    @Test
    @DisplayName("model_replaced + replaced_model when swapped; false without replaced_model when resolution ran; absent when unknown")
    void modelReplaced() {
        AgentObservabilityRequest req = new AgentObservabilityRequest();
        req.setStatus("COMPLETED");
        req.setModel("claude-opus-4-9");

        Map<String, Object> unknown = AgentObservabilityService.buildAgentRunStoppedProps(req, null, null);
        assertFalse(unknown.containsKey("model_replaced"), "unknown must never be reported as false");
        assertFalse(unknown.containsKey("replaced_model"));

        AgentObservabilityService.stampModelReplacement(req, java.util.Optional.empty());
        Map<String, Object> notReplaced = AgentObservabilityService.buildAgentRunStoppedProps(req, null, null);
        assertEquals(false, notReplaced.get("model_replaced"));
        assertFalse(notReplaced.containsKey("replaced_model"));

        AgentObservabilityService.stampModelReplacement(req, java.util.Optional.of(
            new ModelReplacementResolver.Substitution("anthropic", "claude-opus-4-9", "anthropic", "claude-opus-4-8", true)));
        Map<String, Object> replaced = AgentObservabilityService.buildAgentRunStoppedProps(req, null, null);
        assertEquals(true, replaced.get("model_replaced"));
        assertEquals("claude-opus-4-8", replaced.get("replaced_model"));
        assertEquals("claude-opus-4-9", replaced.get("model"));
    }

    @Test
    @DisplayName("stampModelReplacement with an unknown outcome (null) leaves the request untouched")
    void stampUnknownIsNoop() {
        AgentObservabilityRequest req = new AgentObservabilityRequest();
        AgentObservabilityService.stampModelReplacement(req, null);
        assertEquals(null, req.getModelReplaced());
        assertEquals(null, req.getReplacedModel());
        AgentObservabilityService.stampModelReplacement(null, java.util.Optional.empty()); // no throw
    }
}
