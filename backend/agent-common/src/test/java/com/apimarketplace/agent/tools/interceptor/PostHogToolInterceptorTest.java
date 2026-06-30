package com.apimarketplace.agent.tools.interceptor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Unit-tests the PII-free {@code tool_call_completed} property bag. The PostHog
 * send path (gating / fire-and-forget) is covered by {@code PostHogAnalyticsClientTest}.
 */
class PostHogToolInterceptorTest {

    @Test
    @DisplayName("success: tool_name/success/duration/org/workflow_id present, no error fields")
    void successProps() {
        Map<String, Object> p = PostHogToolInterceptor.buildToolCallProps("gmail.send", true, 120L, "org-1", "wf-1", null, null);
        assertEquals("gmail.send", p.get("tool_name"));
        assertEquals(true, p.get("success"));
        assertEquals(120L, p.get("duration_ms"));
        assertEquals("org-1", p.get("organization_id"));
        assertEquals("wf-1", p.get("workflow_id"));
        assertFalse(p.containsKey("error_code"));
        assertFalse(p.containsKey("error_type"));
    }

    @Test
    @DisplayName("failure carries error_code; null org and null workflow_id are omitted")
    void failureProps() {
        Map<String, Object> p = PostHogToolInterceptor.buildToolCallProps("slack.post", false, 50L, null, null, "CREDENTIALS_REQUIRED", null);
        assertEquals(false, p.get("success"));
        assertEquals("CREDENTIALS_REQUIRED", p.get("error_code"));
        assertFalse(p.containsKey("organization_id"));
        assertFalse(p.containsKey("workflow_id"));
    }

    @Test
    @DisplayName("exception path carries error_code=EXCEPTION + error_type")
    void exceptionProps() {
        Map<String, Object> p = PostHogToolInterceptor.buildToolCallProps("api.call", false, 30L, "org-2", null, "EXCEPTION", "TimeoutException");
        assertEquals("EXCEPTION", p.get("error_code"));
        assertEquals("TimeoutException", p.get("error_type"));
    }
}
