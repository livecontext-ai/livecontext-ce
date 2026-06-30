package com.apimarketplace.orchestrator.webhook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for WebhookTarget record.
 */
@DisplayName("WebhookTarget")
class WebhookTargetTest {

    @Test
    @DisplayName("Should store workflow, trigger, and tenant information")
    void shouldStoreAllFields() {
        WebhookTarget target = new WebhookTarget("wf-123", "trigger:webhook", "tenant-1");

        assertThat(target.workflowId()).isEqualTo("wf-123");
        assertThat(target.triggerId()).isEqualTo("trigger:webhook");
        assertThat(target.tenantId()).isEqualTo("tenant-1");
    }

    @Test
    @DisplayName("Should generate correct Redis key from token")
    void shouldGenerateCorrectRedisKey() {
        String key = WebhookTarget.redisKey("abc-token-123");
        assertThat(key).isEqualTo("webhook:abc-token-123");
    }

    @Test
    @DisplayName("Redis key prefix should be 'webhook:'")
    void shouldHaveCorrectPrefix() {
        assertThat(WebhookTarget.REDIS_KEY_PREFIX).isEqualTo("webhook:");
    }
}
