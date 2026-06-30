package com.apimarketplace.orchestrator.webhook;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.trigger.client.TriggerClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for WebhookIndexService.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WebhookIndexService")
class WebhookIndexServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private WorkflowRepository workflowRepository;

    @Mock
    private TriggerClient triggerClient;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    private WebhookIndexService service;

    @BeforeEach
    void setUp() {
        service = new WebhookIndexService(redisTemplate, workflowRepository, triggerClient);
    }

    @Nested
    @DisplayName("index")
    class IndexTests {

        @Test
        @DisplayName("Should skip indexing when token is null")
        void shouldSkipWhenTokenNull() {
            service.index(null, new WebhookTarget("wf-1", "trigger:wh", "t1"));

            verifyNoInteractions(redisTemplate);
        }

        @Test
        @DisplayName("Should skip indexing when token is blank")
        void shouldSkipWhenTokenBlank() {
            service.index("  ", new WebhookTarget("wf-1", "trigger:wh", "t1"));

            verifyNoInteractions(redisTemplate);
        }

        @Test
        @DisplayName("Should index webhook in Redis")
        void shouldIndexInRedis() {
            when(redisTemplate.opsForHash()).thenReturn(hashOperations);

            service.index("wh_token123", new WebhookTarget("wf-1", "trigger:wh", "t1"));

            verify(hashOperations).putAll(anyString(), anyMap());
        }
    }

    @Nested
    @DisplayName("lookup")
    class LookupTests {

        @Test
        @DisplayName("Should return empty for null token")
        void shouldReturnEmptyForNull() {
            assertThat(service.lookup(null)).isEmpty();
        }

        @Test
        @DisplayName("Should return empty for blank token")
        void shouldReturnEmptyForBlank() {
            assertThat(service.lookup("  ")).isEmpty();
        }

        @Test
        @DisplayName("Should return empty when not found in Redis")
        void shouldReturnEmptyWhenNotFound() {
            when(redisTemplate.opsForHash()).thenReturn(hashOperations);
            when(hashOperations.entries(anyString())).thenReturn(Map.of());

            assertThat(service.lookup("wh_missing")).isEmpty();
        }

        @Test
        @DisplayName("Should return target when found in Redis")
        void shouldReturnTargetWhenFound() {
            Map<Object, Object> data = new HashMap<>();
            data.put("workflowId", "wf-123");
            data.put("triggerId", "trigger:my_webhook");
            data.put("tenantId", "tenant-1");

            when(redisTemplate.opsForHash()).thenReturn(hashOperations);
            when(hashOperations.entries(anyString())).thenReturn(data);

            Optional<WebhookTarget> result = service.lookup("wh_token123");

            assertThat(result).isPresent();
            assertThat(result.get().workflowId()).isEqualTo("wf-123");
            assertThat(result.get().triggerId()).isEqualTo("trigger:my_webhook");
            assertThat(result.get().tenantId()).isEqualTo("tenant-1");
        }
    }

    @Nested
    @DisplayName("remove")
    class RemoveTests {

        @Test
        @DisplayName("Should skip when token is null")
        void shouldSkipWhenTokenNull() {
            service.remove(null);
            verifyNoInteractions(redisTemplate);
        }

        @Test
        @DisplayName("Should skip when token is blank")
        void shouldSkipWhenTokenBlank() {
            service.remove("  ");
            verifyNoInteractions(redisTemplate);
        }

        @Test
        @DisplayName("Should delete key from Redis")
        void shouldDeleteFromRedis() {
            when(redisTemplate.delete(anyString())).thenReturn(true);

            service.remove("wh_token123");

            verify(redisTemplate).delete(anyString());
        }
    }

    @Nested
    @DisplayName("syncForWorkflow")
    class SyncForWorkflowTests {

        @Test
        @DisplayName("Should index all tokens for workflow")
        void shouldIndexAllTokens() {
            UUID workflowId = UUID.randomUUID();
            WorkflowEntity workflow = mock(WorkflowEntity.class);
            when(workflow.getId()).thenReturn(workflowId);
            when(workflow.getTenantId()).thenReturn("tenant-1");

            when(triggerClient.getTokensForWorkflow(workflowId))
                    .thenReturn(Map.of("trigger:wh1", "wh_token1"));
            when(redisTemplate.opsForHash()).thenReturn(hashOperations);

            service.syncForWorkflow(workflow);

            verify(hashOperations).putAll(anyString(), anyMap());
        }
    }

    @Nested
    @DisplayName("rebuildIndex")
    class RebuildIndexTests {

        @Test
        @DisplayName("Should rebuild from all tokens in database")
        void shouldRebuildFromDatabase() {
            UUID workflowId = UUID.randomUUID();

            WorkflowEntity workflow = mock(WorkflowEntity.class);
            when(workflow.getId()).thenReturn(workflowId);
            when(workflow.getTenantId()).thenReturn("tenant-1");

            when(workflowRepository.findAll()).thenReturn(List.of(workflow));
            when(triggerClient.getTokensForWorkflow(workflowId))
                    .thenReturn(Map.of("trigger:wh1", "wh_token1"));
            when(redisTemplate.opsForHash()).thenReturn(hashOperations);

            service.rebuildIndex();

            verify(hashOperations).putAll(anyString(), anyMap());
        }

        @Test
        @DisplayName("Should skip workflows with no tokens")
        void shouldSkipWorkflowsWithNoTokens() {
            UUID workflowId = UUID.randomUUID();

            WorkflowEntity workflow = mock(WorkflowEntity.class);
            when(workflow.getId()).thenReturn(workflowId);

            when(workflowRepository.findAll()).thenReturn(List.of(workflow));
            when(triggerClient.getTokensForWorkflow(workflowId))
                    .thenReturn(Map.of());

            service.rebuildIndex();

            verifyNoInteractions(hashOperations);
        }
    }
}
