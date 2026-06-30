package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.service.NodeLibraryService;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.orchestrator.services.WorkflowManagementService;
import com.apimarketplace.orchestrator.services.WorkflowPlanVersionService;
import com.apimarketplace.trigger.client.TriggerClient;
import com.apimarketplace.trigger.client.dto.StandaloneWebhookDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for WorkflowBuilderLoader standalone webhook URL resolution.
 *
 * Verifies that:
 * - Standalone webhook URLs are resolved from DB on workflow load
 * - Triggers without webhookId are handled gracefully
 * - TriggerClient failures don't break workflow load
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowBuilderLoader - Standalone Webhook Resolution")
class WorkflowBuilderLoaderWebhookTest {

    @Mock
    private WorkflowBuilderSessionStore sessionStore;

    @Mock
    private WorkflowManagementService workflowService;

    @Mock
    private WorkflowRepository workflowRepository;

    @Mock
    private WorkflowBuilderLogger buildLogger;

    @Mock
    private WorkflowBuilderValidator validator;

    @Mock
    private DataSourceClient dataSourceService;

    @Mock
    private ToolSchemaFetcher toolSchemaFetcher;

    @Mock
    private NodeLibraryService nodeLibraryService;

    @Mock
    private TriggerClient triggerClient;

    @Mock
    private WorkflowPlanVersionService versionService;

    @Mock
    private AgentWorkflowFireService agentFireService;

    private WorkflowBuilderLoader loader;

    @BeforeEach
    void setUp() {
        loader = new WorkflowBuilderLoader(
                sessionStore, workflowService, workflowRepository,
                buildLogger, validator, dataSourceService,
                toolSchemaFetcher, nodeLibraryService,
                new ObjectMapper(), triggerClient, versionService,
                agentFireService
        );
    }

    private WorkflowEntity createWorkflowEntity(String name, Map<String, Object> plan) {
        WorkflowEntity entity = new WorkflowEntity();
        entity.setId(UUID.randomUUID());
        entity.setTenantId("tenant-1");
        entity.setName(name);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        entity.setPlan(plan);
        return entity;
    }

    private StandaloneWebhookDto createWebhookDto(String webhookId, String name) {
        StandaloneWebhookDto dto = new StandaloneWebhookDto();
        dto.setId(UUID.fromString(webhookId));
        dto.setName(name);
        dto.setToken("wh_token123");
        dto.setHttpMethod("POST");
        dto.setAuthType("none");
        dto.setIsActive(true);
        dto.setCreatedAt(Instant.now());
        dto.setUpdatedAt(Instant.now());
        return dto;
    }

    @Nested
    @DisplayName("Standalone webhook URL resolution on load")
    class StandaloneWebhookResolution {

        @Test
        @DisplayName("Should resolve standalone webhook URL on load")
        void shouldResolveStandaloneWebhookUrl() {
            String webhookId = UUID.randomUUID().toString();
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("webhookId", webhookId);
            params.put("httpMethod", "POST");

            Map<String, Object> trigger = new LinkedHashMap<>();
            trigger.put("label", "My Hook");
            trigger.put("type", "webhook");
            trigger.put("params", params);

            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("triggers", List.of(trigger));
            plan.put("mcps", List.of());
            plan.put("cores", List.of());
            plan.put("edges", List.of());

            WorkflowEntity entity = createWorkflowEntity("Test", plan);
            when(workflowRepository.findById(entity.getId())).thenReturn(Optional.of(entity));
            when(triggerClient.findStandaloneByWorkflowId(entity.getId()))
                    .thenReturn(List.of(createWebhookDto(webhookId, "My Hook")));

            Map<String, Object> loadParams = Map.of("id", entity.getId().toString());
            ToolExecutionResult result = loader.executeLoad("tenant-1", null, "conv-1", loadParams);

            assertThat(result.success()).isTrue();
            verify(triggerClient).findStandaloneByWorkflowId(entity.getId());
        }

        @Test
        @DisplayName("Should skip resolution for triggers without webhookId")
        void shouldSkipTriggersWithoutWebhookId() {
            Map<String, Object> trigger = new LinkedHashMap<>();
            trigger.put("label", "Manual");
            trigger.put("type", "manual");

            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("triggers", List.of(trigger));
            plan.put("mcps", List.of());
            plan.put("cores", List.of());
            plan.put("edges", List.of());

            WorkflowEntity entity = createWorkflowEntity("Test", plan);
            when(workflowRepository.findById(entity.getId())).thenReturn(Optional.of(entity));

            Map<String, Object> loadParams = Map.of("id", entity.getId().toString());
            loader.executeLoad("tenant-1", null, "conv-1", loadParams);

            verify(triggerClient, never()).findStandaloneByWorkflowId(any());
        }

        @Test
        @DisplayName("Should survive standalone webhook service failure")
        void shouldSurviveWebhookServiceFailure() {
            String webhookId = UUID.randomUUID().toString();
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("webhookId", webhookId);

            Map<String, Object> trigger = new LinkedHashMap<>();
            trigger.put("label", "My Hook");
            trigger.put("type", "webhook");
            trigger.put("params", params);

            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("triggers", List.of(trigger));
            plan.put("mcps", List.of());
            plan.put("cores", List.of());
            plan.put("edges", List.of());

            WorkflowEntity entity = createWorkflowEntity("Test", plan);
            when(workflowRepository.findById(entity.getId())).thenReturn(Optional.of(entity));
            when(triggerClient.findStandaloneByWorkflowId(entity.getId()))
                    .thenThrow(new RuntimeException("DB error"));

            Map<String, Object> loadParams = Map.of("id", entity.getId().toString());
            ToolExecutionResult result = loader.executeLoad("tenant-1", null, "conv-1", loadParams);

            // Should still succeed - webhook resolution failure is non-fatal
            assertThat(result.success()).isTrue();
        }

        @Test
        @DisplayName("Should not call service for workflow with no plan")
        void shouldNotCallServiceForNoPlan() {
            WorkflowEntity entity = createWorkflowEntity("Empty", null);
            when(workflowRepository.findById(entity.getId())).thenReturn(Optional.of(entity));

            Map<String, Object> loadParams = Map.of("id", entity.getId().toString());
            loader.executeLoad("tenant-1", null, "conv-1", loadParams);

            verify(triggerClient, never()).findStandaloneByWorkflowId(any());
        }
    }
}
