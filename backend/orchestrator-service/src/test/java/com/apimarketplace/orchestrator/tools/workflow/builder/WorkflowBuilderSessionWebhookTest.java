package com.apimarketplace.orchestrator.tools.workflow.builder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for WorkflowBuilderSession webhookTokens field.
 *
 * Verifies that:
 * - webhookTokens is initialized as empty map
 * - Tokens can be stored and retrieved
 * - Multiple tokens for different triggers
 * - Builder pattern works with webhookTokens
 */
@DisplayName("WorkflowBuilderSession - Webhook Tokens")
class WorkflowBuilderSessionWebhookTest {

    // ==================== Initialization ====================

    @Nested
    @DisplayName("Initialization")
    class Initialization {

        @Test
        @DisplayName("Should initialize webhookTokens as empty map")
        void shouldInitializeAsEmptyMap() {
            WorkflowBuilderSession session = WorkflowBuilderSession.create(
                    "tenant-1", "conv-1", "Test", null);

            assertThat(session.getWebhookTokens()).isNotNull();
            assertThat(session.getWebhookTokens()).isEmpty();
        }

        @Test
        @DisplayName("Builder should initialize webhookTokens as empty map")
        void builderShouldInitializeAsEmptyMap() {
            WorkflowBuilderSession session = WorkflowBuilderSession.builder()
                    .sessionId("s1")
                    .tenantId("t1")
                    .build();

            assertThat(session.getWebhookTokens()).isNotNull();
            assertThat(session.getWebhookTokens()).isEmpty();
        }

        @Test
        @DisplayName("No-args constructor should have null but setter works")
        void noArgsConstructorShouldWork() {
            WorkflowBuilderSession session = new WorkflowBuilderSession();
            session.setWebhookTokens(new LinkedHashMap<>());

            assertThat(session.getWebhookTokens()).isNotNull();
            assertThat(session.getWebhookTokens()).isEmpty();
        }
    }

    // ==================== Token Operations ====================

    @Nested
    @DisplayName("Token operations")
    class TokenOperations {

        @Test
        @DisplayName("Should store and retrieve a token")
        void shouldStoreAndRetrieveToken() {
            WorkflowBuilderSession session = WorkflowBuilderSession.create(
                    "tenant-1", "conv-1", "Test", null);

            session.getWebhookTokens().put("trigger:my_webhook", "wh_abc123");

            assertThat(session.getWebhookTokens().get("trigger:my_webhook"))
                    .isEqualTo("wh_abc123");
        }

        @Test
        @DisplayName("Should store multiple tokens for different triggers")
        void shouldStoreMultipleTokens() {
            WorkflowBuilderSession session = WorkflowBuilderSession.create(
                    "tenant-1", "conv-1", "Test", null);

            session.getWebhookTokens().put("trigger:hook_a", "wh_aaa");
            session.getWebhookTokens().put("trigger:hook_b", "wh_bbb");
            session.getWebhookTokens().put("trigger:hook_c", "wh_ccc");

            assertThat(session.getWebhookTokens()).hasSize(3);
            assertThat(session.getWebhookTokens().get("trigger:hook_a")).isEqualTo("wh_aaa");
            assertThat(session.getWebhookTokens().get("trigger:hook_b")).isEqualTo("wh_bbb");
            assertThat(session.getWebhookTokens().get("trigger:hook_c")).isEqualTo("wh_ccc");
        }

        @Test
        @DisplayName("Should return null for non-existent trigger")
        void shouldReturnNullForNonExistent() {
            WorkflowBuilderSession session = WorkflowBuilderSession.create(
                    "tenant-1", "conv-1", "Test", null);

            assertThat(session.getWebhookTokens().get("trigger:nonexistent")).isNull();
        }

        @Test
        @DisplayName("Should overwrite existing token")
        void shouldOverwriteExistingToken() {
            WorkflowBuilderSession session = WorkflowBuilderSession.create(
                    "tenant-1", "conv-1", "Test", null);

            session.getWebhookTokens().put("trigger:hook", "wh_old");
            session.getWebhookTokens().put("trigger:hook", "wh_new");

            assertThat(session.getWebhookTokens().get("trigger:hook")).isEqualTo("wh_new");
            assertThat(session.getWebhookTokens()).hasSize(1);
        }

        @Test
        @DisplayName("Should support putAll for bulk loading")
        void shouldSupportPutAll() {
            WorkflowBuilderSession session = WorkflowBuilderSession.create(
                    "tenant-1", "conv-1", "Test", null);

            Map<String, String> tokens = Map.of(
                    "trigger:a", "wh_1",
                    "trigger:b", "wh_2"
            );
            session.getWebhookTokens().putAll(tokens);

            assertThat(session.getWebhookTokens()).hasSize(2);
            assertThat(session.getWebhookTokens()).containsEntry("trigger:a", "wh_1");
            assertThat(session.getWebhookTokens()).containsEntry("trigger:b", "wh_2");
        }
    }
}
