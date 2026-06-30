package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.orchestrator.config.AgentDefaultsConfig;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.context.RunContextService;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.trigger.client.TriggerClient;
import com.apimarketplace.trigger.client.dto.StandaloneChatEndpointDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatDispatchService")
class ChatDispatchServiceTest {

    @Mock private TriggerClient triggerClient;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private RestTemplate restTemplate;
    @Mock private ObjectMapper objectMapper;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowRunRepository runRepository;
    @Mock private ReusableTriggerService triggerService;
    @Mock private ProductionRunResolver productionRunResolver;
    @Mock private CreditConsumptionClient creditClient;
    @Mock private AgentDefaultsConfig agentDefaults;
    @Mock private AgentClient agentClient;
    @Mock private RunContextService runContextService;
    @Mock private HashOperations<String, Object, Object> hashOperations;

    private ChatDispatchService service;

    private static final UUID WORKFLOW_ID = UUID.randomUUID();
    private static final String TENANT_ID = "tenant-1";
    private static final String TOKEN = "ch_test123";
    private static final String TRIGGER_ID = "trigger:chat";
    private static final String RUN_ID = "run_123";

    @BeforeEach
    void setUp() {
        service = new ChatDispatchService(
                triggerClient, redisTemplate, restTemplate, objectMapper,
                workflowRepository, runRepository, triggerService, productionRunResolver,
                creditClient, agentDefaults, agentClient, runContextService, "http://localhost:8087");
        lenient().when(creditClient.checkCredits(any())).thenReturn(true);

        // ProductionRunResolver delegates to existing repo stubs (refactor compat).
        lenient().when(productionRunResolver.resolve(any(), any())).thenAnswer(inv -> {
            java.util.UUID wfId = inv.getArgument(0);
            var wf = workflowRepository.findById(wfId).orElse(null);
            if (wf == null) {
                return new ProductionRunResolver.Resolution(
                    java.util.Optional.empty(), ProductionRunResolver.Outcome.WORKFLOW_MISSING, null);
            }
            Integer pinned = wf.getPinnedVersion();
            var r = pinned != null
                ? runRepository.findFirstByWorkflowIdAndPlanVersionOrderByStartedAtDesc(wfId, pinned)
                : runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(wfId);
            var outcome = pinned == null
                ? (r.isPresent() ? ProductionRunResolver.Outcome.FOUND : ProductionRunResolver.Outcome.NOT_PINNED)
                : (r.isPresent() ? ProductionRunResolver.Outcome.FOUND : ProductionRunResolver.Outcome.NO_PRODUCTION_RUN);
            return new ProductionRunResolver.Resolution(r, outcome, wf.getName());
        });
    }

    private StandaloneChatEndpointDto createEndpoint(String triggerId) {
        StandaloneChatEndpointDto dto = new StandaloneChatEndpointDto();
        dto.setId(UUID.randomUUID());
        dto.setTenantId(TENANT_ID);
        dto.setName("Test Chat");
        dto.setToken(TOKEN);
        dto.setWorkflowId(WORKFLOW_ID);
        dto.setIsActive(true);
        dto.setMemoryEnabled(true);
        dto.setTriggerId(triggerId);
        return dto;
    }

    private WorkflowEntity createWorkflow(Integer pinnedVersion) {
        WorkflowEntity wf = new WorkflowEntity();
        wf.setId(WORKFLOW_ID);
        wf.setTenantId(TENANT_ID);
        wf.setPlan(Map.of("triggers", java.util.List.of()));
        wf.setPinnedVersion(pinnedVersion);
        return wf;
    }

    private WorkflowRunEntity createRun(RunStatus status) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic(RUN_ID);
        run.setTenantId(TENANT_ID);
        run.setStatus(status);
        run.setPlanVersion(1);
        return run;
    }

    @Nested
    @DisplayName("sendMessage - dispatch to workflow")
    class DispatchTests {

        @BeforeEach
        void setUpSession() {
            lenient().when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        }

        @Test
        @DisplayName("Should dispatch successfully with stored triggerId")
        void shouldDispatchWithTriggerId() {
            StandaloneChatEndpointDto endpoint = createEndpoint(TRIGGER_ID);
            WorkflowEntity workflow = createWorkflow(null);
            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER);

            when(triggerClient.findChatEndpointByToken(TOKEN)).thenReturn(endpoint);
            when(hashOperations.entries(any())).thenReturn(Map.of(
                    "sessionId", "sess-1", "conversationId", "conv-1",
                    "chatEndpointId", endpoint.getId().toString(),
                    "tenantId", TENANT_ID, "ipAddress", "", "createdAt", "2026-01-01T00:00:00Z"));
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID))
                    .thenReturn(Optional.of(run));
            when(triggerService.executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.CHAT), any()))
                    .thenReturn(TriggerExecutionResult.success(RUN_ID, TRIGGER_ID, TriggerType.CHAT, Set.of(), 1));

            Map<String, Object> result = service.sendMessage(TOKEN, "sess-1", "hello");

            assertThat(result.get("status")).isEqualTo("ok");
            verify(triggerService).executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.CHAT), any());
        }

        @Test
        @DisplayName("Should return no_chat_trigger when triggerId is null")
        void shouldReturnErrorWhenNoTriggerId() {
            StandaloneChatEndpointDto endpoint = createEndpoint(null);
            WorkflowEntity workflow = createWorkflow(null);
            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER);

            when(triggerClient.findChatEndpointByToken(TOKEN)).thenReturn(endpoint);
            when(hashOperations.entries(any())).thenReturn(Map.of(
                    "sessionId", "sess-1", "conversationId", "conv-1",
                    "chatEndpointId", endpoint.getId().toString(),
                    "tenantId", TENANT_ID, "ipAddress", "", "createdAt", "2026-01-01T00:00:00Z"));
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID))
                    .thenReturn(Optional.of(run));

            Map<String, Object> result = service.sendMessage(TOKEN, "sess-1", "hello");

            assertThat(result.get("status")).isEqualTo("no_chat_trigger");
            verify(triggerService, never()).executeTrigger(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should return no_workflow when workflow not found")
        void shouldReturnNoWorkflowWhenNotFound() {
            StandaloneChatEndpointDto endpoint = createEndpoint(TRIGGER_ID);

            when(triggerClient.findChatEndpointByToken(TOKEN)).thenReturn(endpoint);
            when(hashOperations.entries(any())).thenReturn(Map.of(
                    "sessionId", "sess-1", "conversationId", "conv-1",
                    "chatEndpointId", endpoint.getId().toString(),
                    "tenantId", TENANT_ID, "ipAddress", "", "createdAt", "2026-01-01T00:00:00Z"));
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.empty());

            Map<String, Object> result = service.sendMessage(TOKEN, "sess-1", "hello");

            assertThat(result.get("status")).isEqualTo("no_workflow");
            verify(triggerService, never()).executeTrigger(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should return not_pinned when workflow has no pinned version")
        void shouldReturnNoActiveRunWhenNoRun() {
            StandaloneChatEndpointDto endpoint = createEndpoint(TRIGGER_ID);
            WorkflowEntity workflow = createWorkflow(null);

            when(triggerClient.findChatEndpointByToken(TOKEN)).thenReturn(endpoint);
            when(hashOperations.entries(any())).thenReturn(Map.of(
                    "sessionId", "sess-1", "conversationId", "conv-1",
                    "chatEndpointId", endpoint.getId().toString(),
                    "tenantId", TENANT_ID, "ipAddress", "", "createdAt", "2026-01-01T00:00:00Z"));
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));

            Map<String, Object> result = service.sendMessage(TOKEN, "sess-1", "hello");

            assertThat(result.get("status")).isEqualTo("not_pinned");
        }

        @Test
        @DisplayName("Should return run_terminated when run is terminal")
        void shouldReturnRunTerminatedWhenTerminal() {
            StandaloneChatEndpointDto endpoint = createEndpoint(TRIGGER_ID);
            WorkflowEntity workflow = createWorkflow(null);
            WorkflowRunEntity run = createRun(RunStatus.CANCELLED);

            when(triggerClient.findChatEndpointByToken(TOKEN)).thenReturn(endpoint);
            when(hashOperations.entries(any())).thenReturn(Map.of(
                    "sessionId", "sess-1", "conversationId", "conv-1",
                    "chatEndpointId", endpoint.getId().toString(),
                    "tenantId", TENANT_ID, "ipAddress", "", "createdAt", "2026-01-01T00:00:00Z"));
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID))
                    .thenReturn(Optional.of(run));

            Map<String, Object> result = service.sendMessage(TOKEN, "sess-1", "hello");

            assertThat(result.get("status")).isEqualTo("run_terminated");
            verify(triggerService, never()).executeTrigger(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should return insufficient_credits when no credits")
        void shouldReturnInsufficientCredits() {
            StandaloneChatEndpointDto endpoint = createEndpoint(TRIGGER_ID);
            WorkflowEntity workflow = createWorkflow(null);
            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER);

            when(triggerClient.findChatEndpointByToken(TOKEN)).thenReturn(endpoint);
            when(hashOperations.entries(any())).thenReturn(Map.of(
                    "sessionId", "sess-1", "conversationId", "conv-1",
                    "chatEndpointId", endpoint.getId().toString(),
                    "tenantId", TENANT_ID, "ipAddress", "", "createdAt", "2026-01-01T00:00:00Z"));
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID))
                    .thenReturn(Optional.of(run));
            when(creditClient.checkCredits(TENANT_ID)).thenReturn(false);

            Map<String, Object> result = service.sendMessage(TOKEN, "sess-1", "hello");

            assertThat(result.get("status")).isEqualTo("insufficient_credits");
            verify(triggerService, never()).executeTrigger(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should use pinned version for run lookup")
        void shouldUsePinnedVersionForRunLookup() {
            StandaloneChatEndpointDto endpoint = createEndpoint(TRIGGER_ID);
            WorkflowEntity workflow = createWorkflow(5);
            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER);

            when(triggerClient.findChatEndpointByToken(TOKEN)).thenReturn(endpoint);
            when(hashOperations.entries(any())).thenReturn(Map.of(
                    "sessionId", "sess-1", "conversationId", "conv-1",
                    "chatEndpointId", endpoint.getId().toString(),
                    "tenantId", TENANT_ID, "ipAddress", "", "createdAt", "2026-01-01T00:00:00Z"));
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(runRepository.findFirstByWorkflowIdAndPlanVersionOrderByStartedAtDesc(WORKFLOW_ID, 5))
                    .thenReturn(Optional.of(run));
            when(triggerService.executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.CHAT), any()))
                    .thenReturn(TriggerExecutionResult.success(RUN_ID, TRIGGER_ID, TriggerType.CHAT, Set.of(), 1));

            Map<String, Object> result = service.sendMessage(TOKEN, "sess-1", "hello");

            assertThat(result.get("status")).isEqualTo("ok");
            verify(runRepository).findFirstByWorkflowIdAndPlanVersionOrderByStartedAtDesc(WORKFLOW_ID, 5);
            verify(runRepository, never()).findFirstByWorkflowIdOrderByStartedAtDesc(any());
        }

        @Test
        @DisplayName("Should return no_active_run when pinned run not found")
        void shouldReturnNoActiveRunWhenPinnedRunNotFound() {
            StandaloneChatEndpointDto endpoint = createEndpoint(TRIGGER_ID);
            WorkflowEntity workflow = createWorkflow(5);

            when(triggerClient.findChatEndpointByToken(TOKEN)).thenReturn(endpoint);
            when(hashOperations.entries(any())).thenReturn(Map.of(
                    "sessionId", "sess-1", "conversationId", "conv-1",
                    "chatEndpointId", endpoint.getId().toString(),
                    "tenantId", TENANT_ID, "ipAddress", "", "createdAt", "2026-01-01T00:00:00Z"));
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(runRepository.findFirstByWorkflowIdAndPlanVersionOrderByStartedAtDesc(WORKFLOW_ID, 5))
                    .thenReturn(Optional.empty());

            Map<String, Object> result = service.sendMessage(TOKEN, "sess-1", "hello");

            assertThat(result.get("status")).isEqualTo("no_active_run");
        }

        @Test
        @DisplayName("Should return no_workflow when workflowId is null on endpoint")
        void shouldReturnNoWorkflowWhenWorkflowIdNull() {
            StandaloneChatEndpointDto endpoint = createEndpoint(TRIGGER_ID);
            endpoint.setWorkflowId(null);

            when(triggerClient.findChatEndpointByToken(TOKEN)).thenReturn(endpoint);
            when(hashOperations.entries(any())).thenReturn(Map.of(
                    "sessionId", "sess-1", "conversationId", "conv-1",
                    "chatEndpointId", endpoint.getId().toString(),
                    "tenantId", TENANT_ID, "ipAddress", "", "createdAt", "2026-01-01T00:00:00Z"));

            Map<String, Object> result = service.sendMessage(TOKEN, "sess-1", "hello");

            assertThat(result.get("status")).isEqualTo("no_workflow");
        }

        @Test
        @DisplayName("Should return execution_failed when trigger execution fails")
        void shouldReturnExecutionFailed() {
            StandaloneChatEndpointDto endpoint = createEndpoint(TRIGGER_ID);
            WorkflowEntity workflow = createWorkflow(null);
            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER);

            when(triggerClient.findChatEndpointByToken(TOKEN)).thenReturn(endpoint);
            when(hashOperations.entries(any())).thenReturn(Map.of(
                    "sessionId", "sess-1", "conversationId", "conv-1",
                    "chatEndpointId", endpoint.getId().toString(),
                    "tenantId", TENANT_ID, "ipAddress", "", "createdAt", "2026-01-01T00:00:00Z"));
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID))
                    .thenReturn(Optional.of(run));
            when(triggerService.executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.CHAT), any()))
                    .thenReturn(TriggerExecutionResult.failure(RUN_ID, TRIGGER_ID, TriggerType.CHAT, "boom"));

            Map<String, Object> result = service.sendMessage(TOKEN, "sess-1", "hello");

            assertThat(result.get("status")).isEqualTo("execution_failed");
            assertThat(result.get("error")).isEqualTo("boom");
        }
    }

    @Nested
    @DisplayName("Org header forwarding - regression for org-scoped conversation calls")
    class OrgHeaderForwardingTests {

        @BeforeEach
        void setUpSession() {
            lenient().when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        }

        @Test
        @DisplayName("persistMessage forwards X-Organization-ID to conversation-service")
        void persistMessageForwardsOrgHeader() {
            // Arrange - bind request context with org header
            org.springframework.mock.web.MockHttpServletRequest mockReq =
                    new org.springframework.mock.web.MockHttpServletRequest();
            mockReq.addHeader("X-Organization-ID", "org-test-123");
            org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                    new org.springframework.web.context.request.ServletRequestAttributes(mockReq));

            try {
                StandaloneChatEndpointDto endpoint = createEndpoint(TRIGGER_ID);
                WorkflowEntity workflow = createWorkflow(null);
                WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER);

                when(triggerClient.findChatEndpointByToken(TOKEN)).thenReturn(endpoint);
                when(hashOperations.entries(any())).thenReturn(Map.of(
                        "sessionId", "sess-1", "conversationId", "conv-1",
                        "chatEndpointId", endpoint.getId().toString(),
                        "tenantId", TENANT_ID, "ipAddress", "", "createdAt", "2026-01-01T00:00:00Z"));
                when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
                when(runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID))
                        .thenReturn(Optional.of(run));
                when(triggerService.executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.CHAT), any()))
                        .thenReturn(TriggerExecutionResult.success(RUN_ID, TRIGGER_ID, TriggerType.CHAT, Set.of(), 1));

                // Act
                service.sendMessage(TOKEN, "sess-1", "hello");

                // Assert - capture the persistMessage POST call
                org.mockito.ArgumentCaptor<org.springframework.http.HttpEntity> captor =
                        org.mockito.ArgumentCaptor.forClass(org.springframework.http.HttpEntity.class);
                verify(restTemplate, atLeastOnce()).postForEntity(
                        contains("/messages"), captor.capture(), eq(Map.class));

                org.springframework.http.HttpEntity<?> outbound = captor.getValue();
                assertThat(outbound.getHeaders().getFirst("X-Organization-ID"))
                        .as("persistMessage must forward X-Organization-ID to conversation-service")
                        .isEqualTo("org-test-123");
            } finally {
                org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
            }
        }
    }

    @Nested
    @DisplayName("PR22c R3 - workspace-scope guard regression (R2 convergent must-fix A+B+C)")
    class WorkspaceScopeGuardTests {

        private static final String ORG_ID = "org-acme";
        private static final String OTHER_ORG_ID = "org-other";

        @BeforeEach
        void setUpSession() {
            lenient().when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        }

        private void stubSessionFor(StandaloneChatEndpointDto endpoint) {
            // ChatDispatchService.sendMessage validates session.chatEndpointId()
            // matches endpoint.getId() - so we MUST set it to the endpoint's id,
            // not a random UUID.
            when(hashOperations.entries(any())).thenReturn(Map.of(
                    "sessionId", "sess-1", "conversationId", "conv-1",
                    "chatEndpointId", endpoint.getId().toString(),
                    "tenantId", TENANT_ID, "ipAddress", "", "createdAt", "2026-01-01T00:00:00Z"));
        }

        @Test
        @DisplayName("Returns status=workspace_mismatch when endpoint org != run org")
        void refusesFireOnWorkspaceMismatch() {
            StandaloneChatEndpointDto endpoint = createEndpoint(TRIGGER_ID);
            endpoint.setOrganizationId(ORG_ID);
            WorkflowEntity workflow = createWorkflow(null);
            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER);
            run.setOrganizationId(OTHER_ORG_ID);

            when(triggerClient.findChatEndpointByToken(TOKEN)).thenReturn(endpoint);
            stubSessionFor(endpoint);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID))
                    .thenReturn(Optional.of(run));

            Map<String, Object> result = service.sendMessage(TOKEN, "sess-1", "hello");

            assertThat(result.get("status")).isEqualTo("workspace_mismatch");
            verify(triggerService, never()).executeTrigger(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Fires normally when endpoint org == run org")
        void firesOnWorkspaceMatch() {
            StandaloneChatEndpointDto endpoint = createEndpoint(TRIGGER_ID);
            endpoint.setOrganizationId(ORG_ID);
            WorkflowEntity workflow = createWorkflow(null);
            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER);
            run.setOrganizationId(ORG_ID);

            when(triggerClient.findChatEndpointByToken(TOKEN)).thenReturn(endpoint);
            stubSessionFor(endpoint);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID))
                    .thenReturn(Optional.of(run));
            when(triggerService.executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.CHAT), any()))
                    .thenReturn(TriggerExecutionResult.success(RUN_ID, TRIGGER_ID, TriggerType.CHAT, Set.of(), 1));

            Map<String, Object> result = service.sendMessage(TOKEN, "sess-1", "hello");

            assertThat(result.get("status")).isEqualTo("ok");
            verify(triggerService).executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.CHAT), any());
        }
    }
}
