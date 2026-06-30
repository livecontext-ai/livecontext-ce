package com.apimarketplace.trigger.integration.repository;

import com.apimarketplace.trigger.domain.WebhookTokenEntity;
import com.apimarketplace.trigger.repository.WebhookTokenRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link WebhookTokenRepository}.
 * Tests webhook token CRUD, lookup by token, and workflow/trigger-based queries.
 */
@DataJpaIntegrationTest
class WebhookTokenRepositoryIntegrationTest {

    @Autowired
    private WebhookTokenRepository webhookTokenRepository;

    @Autowired
    private TestEntityManager entityManager;

    private WebhookTokenEntity persistToken(UUID workflowId, String triggerId, String token) {
        WebhookTokenEntity entity = new WebhookTokenEntity(workflowId, triggerId, token);
        // V263 OrgScopedEntity: stamp org-id before persist (NOT NULL after V261)
        entity.setOrganizationId("tenant-1");
        entityManager.persist(entity);
        entityManager.flush();
        return entity;
    }

    @Nested
    @DisplayName("Basic CRUD")
    class CrudOperations {

        @Test
        @DisplayName("should save and retrieve webhook token")
        void shouldSaveAndRetrieve() {
            UUID workflowId = UUID.randomUUID();
            WebhookTokenEntity entity = new WebhookTokenEntity(workflowId, "trigger:webhook", "test-token-123");
            // V263 OrgScopedEntity: stamp org-id before persist (NOT NULL after V261)
            entity.setOrganizationId("tenant-1");
            WebhookTokenEntity saved = webhookTokenRepository.save(entity);

            assertThat(saved.getId()).isNotNull();
            assertThat(saved.getWorkflowId()).isEqualTo(workflowId);
            assertThat(saved.getTriggerId()).isEqualTo("trigger:webhook");
            assertThat(saved.getToken()).isEqualTo("test-token-123");
        }
    }

    @Nested
    @DisplayName("Token Lookup Queries")
    class TokenLookup {

        @Test
        @DisplayName("should find by token")
        void shouldFindByToken() {
            UUID workflowId = UUID.randomUUID();
            persistToken(workflowId, "trigger:webhook", "unique-token-abc");

            Optional<WebhookTokenEntity> found = webhookTokenRepository.findByToken("unique-token-abc");

            assertThat(found).isPresent();
            assertThat(found.get().getWorkflowId()).isEqualTo(workflowId);
        }

        @Test
        @DisplayName("should return empty for non-existent token")
        void shouldReturnEmptyForNonExistentToken() {
            Optional<WebhookTokenEntity> found = webhookTokenRepository.findByToken("does-not-exist");
            assertThat(found).isEmpty();
        }
    }

    @Nested
    @DisplayName("Workflow/Trigger Queries")
    class WorkflowTriggerQueries {

        @Test
        @DisplayName("should find by workflow ID and trigger ID")
        void shouldFindByWorkflowIdAndTriggerId() {
            UUID workflowId = UUID.randomUUID();
            persistToken(workflowId, "trigger:webhook_1", "token-1");
            persistToken(workflowId, "trigger:webhook_2", "token-2");

            Optional<WebhookTokenEntity> found = webhookTokenRepository
                    .findByWorkflowIdAndTriggerId(workflowId, "trigger:webhook_1");

            assertThat(found).isPresent();
            assertThat(found.get().getToken()).isEqualTo("token-1");
        }

        @Test
        @DisplayName("should find all tokens by workflow ID")
        void shouldFindAllByWorkflowId() {
            UUID workflowId = UUID.randomUUID();
            persistToken(workflowId, "trigger:webhook_1", "token-a");
            persistToken(workflowId, "trigger:webhook_2", "token-b");
            persistToken(UUID.randomUUID(), "trigger:webhook_other", "token-c");

            List<WebhookTokenEntity> found = webhookTokenRepository.findByWorkflowId(workflowId);

            assertThat(found).hasSize(2);
            assertThat(found).extracting(WebhookTokenEntity::getToken)
                    .containsExactlyInAnyOrder("token-a", "token-b");
        }

        @Test
        @DisplayName("should check existence by workflow ID and trigger ID")
        void shouldCheckExistence() {
            UUID workflowId = UUID.randomUUID();
            persistToken(workflowId, "trigger:webhook_1", "token-exists");

            assertThat(webhookTokenRepository.existsByWorkflowIdAndTriggerId(workflowId, "trigger:webhook_1")).isTrue();
            assertThat(webhookTokenRepository.existsByWorkflowIdAndTriggerId(workflowId, "trigger:webhook_2")).isFalse();
        }
    }

    @Nested
    @DisplayName("Delete Operations")
    class DeleteOperations {

        @Test
        @DisplayName("should delete all tokens by workflow ID")
        void shouldDeleteByWorkflowId() {
            UUID workflowId = UUID.randomUUID();
            persistToken(workflowId, "trigger:webhook_1", "del-token-1");
            persistToken(workflowId, "trigger:webhook_2", "del-token-2");

            webhookTokenRepository.deleteByWorkflowId(workflowId);
            entityManager.flush();

            List<WebhookTokenEntity> remaining = webhookTokenRepository.findByWorkflowId(workflowId);
            assertThat(remaining).isEmpty();
        }

        @Test
        @DisplayName("should delete tokens not in trigger ID list")
        void shouldDeleteByWorkflowIdAndTriggerIdNotIn() {
            UUID workflowId = UUID.randomUUID();
            persistToken(workflowId, "trigger:keep", "keep-token");
            persistToken(workflowId, "trigger:remove", "remove-token");

            webhookTokenRepository.deleteByWorkflowIdAndTriggerIdNotIn(workflowId, List.of("trigger:keep"));
            entityManager.flush();

            List<WebhookTokenEntity> remaining = webhookTokenRepository.findByWorkflowId(workflowId);
            assertThat(remaining).hasSize(1);
            assertThat(remaining.get(0).getTriggerId()).isEqualTo("trigger:keep");
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("should return empty list for non-existent workflow ID")
        void shouldReturnEmptyForNonExistentWorkflow() {
            List<WebhookTokenEntity> found = webhookTokenRepository.findByWorkflowId(UUID.randomUUID());
            assertThat(found).isEmpty();
        }

        @Test
        @DisplayName("should handle multiple workflows independently")
        void shouldHandleMultipleWorkflows() {
            UUID workflow1 = UUID.randomUUID();
            UUID workflow2 = UUID.randomUUID();
            persistToken(workflow1, "trigger:webhook", "w1-token");
            persistToken(workflow2, "trigger:webhook", "w2-token");

            List<WebhookTokenEntity> w1Tokens = webhookTokenRepository.findByWorkflowId(workflow1);
            List<WebhookTokenEntity> w2Tokens = webhookTokenRepository.findByWorkflowId(workflow2);

            assertThat(w1Tokens).hasSize(1);
            assertThat(w2Tokens).hasSize(1);
            assertThat(w1Tokens.get(0).getToken()).isEqualTo("w1-token");
            assertThat(w2Tokens.get(0).getToken()).isEqualTo("w2-token");
        }
    }
}
