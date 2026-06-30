package com.apimarketplace.common.analytics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostHogAnalyticsClientTest {

    @Test
    @DisplayName("Inactive (no-op) when disabled, even with a key")
    void inactiveWhenDisabled() {
        PostHogAnalyticsClient client = new PostHogAnalyticsClient(false, "phc_test", "https://eu.i.posthog.com");
        assertFalse(client.isActive());
    }

    @Test
    @DisplayName("Inactive (no-op) when enabled but the api-key is blank")
    void inactiveWhenKeyBlank() {
        assertFalse(new PostHogAnalyticsClient(true, "", "https://eu.i.posthog.com").isActive());
        assertFalse(new PostHogAnalyticsClient(true, "   ", "https://eu.i.posthog.com").isActive());
        assertFalse(new PostHogAnalyticsClient(true, null, "https://eu.i.posthog.com").isActive());
    }

    @Test
    @DisplayName("Active only when enabled AND a key is present")
    void activeWhenEnabledAndKeyed() {
        assertTrue(new PostHogAnalyticsClient(true, "phc_test", "https://eu.i.posthog.com").isActive());
    }

    @Test
    @DisplayName("capture never throws when inactive (safe to ship without config)")
    void captureNoThrowWhenInactive() {
        PostHogAnalyticsClient client = new PostHogAnalyticsClient(false, "", null);
        assertDoesNotThrow(() -> client.capture("tenant-1", "agent_run_stopped", Map.of("status", "COMPLETED")));
    }

    @Test
    @DisplayName("capture never throws on null/blank distinct id or event")
    void captureNoThrowOnBadInput() {
        PostHogAnalyticsClient client = new PostHogAnalyticsClient(true, "phc_test", "https://eu.i.posthog.com");
        assertDoesNotThrow(() -> {
            client.capture(null, "evt", Map.of());
            client.capture(" ", "evt", Map.of());
            client.capture("tenant-1", null, Map.of());
            client.capture("tenant-1", "evt", null);
        });
    }

    @Test
    @DisplayName("buildPayload carries api_key, event, distinct_id, merged props and $lib")
    void buildPayloadShape() {
        PostHogAnalyticsClient client = new PostHogAnalyticsClient(true, "phc_test", "https://eu.i.posthog.com");
        Map<String, Object> props = new HashMap<>();
        props.put("status", "COMPLETED");
        props.put("organization_id", "org-1");

        Map<String, Object> body = client.buildPayload("tenant-1", "agent_run_stopped", props);

        assertEquals("phc_test", body.get("api_key"));
        assertEquals("agent_run_stopped", body.get("event"));
        assertEquals("tenant-1", body.get("distinct_id"));
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) body.get("properties");
        assertEquals("COMPLETED", out.get("status"));
        assertEquals("org-1", out.get("organization_id"));
        assertEquals("livecontext-backend", out.get("$lib"));
    }

    @Test
    @DisplayName("buildPayload drops null property values and tolerates null map")
    void buildPayloadNullSafe() {
        PostHogAnalyticsClient client = new PostHogAnalyticsClient(true, "phc_test", "https://eu.i.posthog.com");
        Map<String, Object> props = new HashMap<>();
        props.put("organization_id", null);
        props.put("status", "FAILED");

        Map<String, Object> body = client.buildPayload("tenant-1", "evt", props);
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) body.get("properties");
        assertFalse(out.containsKey("organization_id"));
        assertEquals("FAILED", out.get("status"));

        assertDoesNotThrow(() -> client.buildPayload("tenant-1", "evt", null));
    }
}
