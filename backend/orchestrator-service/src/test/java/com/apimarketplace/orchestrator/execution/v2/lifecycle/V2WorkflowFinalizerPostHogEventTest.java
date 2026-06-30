package com.apimarketplace.orchestrator.execution.v2.lifecycle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Unit-tests the PII-free {@code workflow_run_completed} property bag. The PostHog
 * send path (gating / fire-and-forget) is covered by {@code PostHogAnalyticsClientTest}.
 */
class V2WorkflowFinalizerPostHogEventTest {

    @Test
    @DisplayName("workflow_run_completed props carry status/steps/items/ids")
    void buildsProps() {
        Map<String, Object> p = V2WorkflowFinalizer.buildWorkflowRunCompletedProps(
                "COMPLETED", 5000L, 7, 1, 2, 0.875, 3, 3, 0, "run-abc", "wfr-1", "org-1");

        assertEquals("COMPLETED", p.get("run_status"));
        assertEquals(5000L, p.get("duration_ms"));
        assertEquals(7, p.get("completed_steps"));
        assertEquals(1, p.get("failed_steps"));
        assertEquals(2, p.get("skipped_steps"));
        assertEquals(0.875, (double) p.get("success_rate"), 1e-9);
        assertEquals(3, p.get("total_items"));
        assertEquals(3, p.get("success_items"));
        assertEquals(0, p.get("failed_items"));
        assertEquals("run-abc", p.get("run_id"));
        assertEquals("wfr-1", p.get("workflow_run_id"));
        assertEquals("org-1", p.get("organization_id"));
    }

    @Test
    @DisplayName("null workflow_run_id and org are omitted")
    void omitsNulls() {
        Map<String, Object> p = V2WorkflowFinalizer.buildWorkflowRunCompletedProps(
                "FAILED", 100L, 0, 1, 0, 0.0, 1, 0, 1, "run-x", null, null);

        assertFalse(p.containsKey("workflow_run_id"));
        assertFalse(p.containsKey("organization_id"));
        assertEquals("FAILED", p.get("run_status"));
    }
}
