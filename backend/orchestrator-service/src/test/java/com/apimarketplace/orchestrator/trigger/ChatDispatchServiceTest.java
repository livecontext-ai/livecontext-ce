package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.orchestrator.config.AgentDefaultsConfig;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.context.RunContextService;
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
    @Mock private AgentDefaultsConfig agentDefaults;
    @Mock private AgentClient agentClient;
    @Mock private RunContextService runContextService;
    @Mock private ShareInvocationLimiter shareInvocationLimiter;
    @Mock private HashOperations<String, Object, Object> hashOperations;

    private ChatDispatchService service;

    private static final UUID WORKFLOW_ID = UUID.randomUUID();
    private static final String TENANT_ID = "tenant-1";
    private static final String TOKEN = "ch_test123";
    private static final String TRIGGER_ID = "trigger:chat";
    private static final String RUN_ID = "run_123";
    /** Every chat endpoint has one since V263 made the column NOT NULL. */
    private static final String FIXTURE_ORG_ID = "org-fixture-endpoint";

    @BeforeEach
    void setUp() {
        service = new ChatDispatchService(
                triggerClient, redisTemplate, restTemplate, objectMapper,
                workflowRepository, runRepository, triggerService, productionRunResolver,
                shareInvocationLimiter, agentDefaults, agentClient, runContextService, "http://localhost:8087");
        lenient().when(shareInvocationLimiter.tryAcquire(any(), any())).thenReturn(true);

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

    /**
     * Shared fixture. The org is populated because that is the only shape that exists:
     * V263 (phase 6) set {@code trigger.standalone_chat_endpoints.organization_id} NOT NULL,
     * so a null-org endpoint cannot be loaded from the database. Leaving the fixture null put
     * every test in this class on an unreachable branch.
     */
    private StandaloneChatEndpointDto createEndpoint(String triggerId) {
        StandaloneChatEndpointDto dto = new StandaloneChatEndpointDto();
        dto.setId(UUID.randomUUID());
        dto.setTenantId(TENANT_ID);
        dto.setOrganizationId(FIXTURE_ORG_ID);
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

    /**
     * Matches {@link #createEndpoint}'s workspace on purpose. The dispatch guard refuses to fire
     * when the endpoint's org and the run's org disagree; it treats two nulls as a match, which
     * is why an all-null fixture used to work. Now that the endpoint fixture carries a real org -
     * the only shape the database allows - a run left org-less would send every test in this
     * class down the workspace_mismatch branch instead of the one it means to cover.
     * {@code WorkspaceScopeGuardTests} sets both explicitly to exercise the disagreement.
     */
    private WorkflowRunEntity createRun(RunStatus status) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic(RUN_ID);
        run.setTenantId(TENANT_ID);
        run.setOrganizationId(FIXTURE_ORG_ID);
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

        /**
         * Scope contract on the message hop, restated after the endpoint-org fix: the
         * conversation belongs to the PUBLISHED ENDPOINT's workspace, so the endpoint's org is
         * what reaches conversation-service even when an authenticated caller is looking at the
         * page from a different active workspace. Before the fix this hop simply inherited the
         * caller's org, which is why the assertion below changed.
         */
        @Test
        @DisplayName("persistMessage sends the endpoint's org, overriding the caller's active one")
        void persistMessageForwardsOrgHeader() {
            // Arrange - a caller active in a DIFFERENT workspace, holding a role there. The role
            // header is load-bearing in this fixture: the gateway only ever emits org and role
            // together, and without it the "role is null" assertion below would hold whether or
            // not the drop exists, i.e. it would be satisfied by the very bug it names.
            org.springframework.mock.web.MockHttpServletRequest mockReq =
                    new org.springframework.mock.web.MockHttpServletRequest();
            mockReq.addHeader("X-Organization-ID", "org-test-123");
            mockReq.addHeader("X-Organization-Role", "ADMIN");
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
                        .as("the conversation belongs to the endpoint's workspace, not to whoever "
                                + "happens to have another workspace active")
                        .isEqualTo(FIXTURE_ORG_ID);
                assertThat(outbound.getHeaders().getFirst("X-Organization-Role"))
                        .as("a role earned in org-test-123 says nothing about the endpoint's org, "
                                + "so it must not travel alongside the overridden org")
                        .isNull();
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

    @Nested
    @DisplayName("Anonymous session creation - regression for the public-chat 500")
    class AnonymousSessionOrgScopeTests {

        private static final String ENDPOINT_ORG_ID = "org-endpoint-owner";

        /**
         * Reproduces the production failure of 2026-09-18: a visitor on a published chat
         * endpoint got 500 "Failed to create session" on every attempt. The visitor is
         * unauthenticated, so no request context and no inbound X-Organization-ID exist;
         * the outbound create therefore carried no org and conversation-service rejected it
         * with 400 "organizationId required after V261", which surfaced as a 500.
         *
         * <p>No RequestContextHolder is bound here ON PURPOSE - that absence IS the bug's
         * precondition. Pre-fix this assertion fails with a null header.
         */
        @Test
        @DisplayName("Sets X-Organization-ID from the endpoint when no request context exists")
        void anonymousSessionSendsEndpointOrganizationId() {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();

            StandaloneChatEndpointDto endpoint = createEndpoint(TRIGGER_ID);
            endpoint.setOrganizationId(ENDPOINT_ORG_ID);

            when(triggerClient.findChatEndpointByToken(TOKEN)).thenReturn(endpoint);
            when(redisTemplate.opsForHash()).thenReturn(hashOperations);
            when(restTemplate.postForEntity(contains("/api/conversations"),
                    any(org.springframework.http.HttpEntity.class), eq(Map.class)))
                    .thenReturn(org.springframework.http.ResponseEntity
                            .ok(Map.of("id", "conv-anon-1")));

            ChatDispatchService.SessionResponse response =
                    service.createOrResumeSession(TOKEN, null, "203.0.113.9");

            assertThat(response.conversationId()).isEqualTo("conv-anon-1");

            org.mockito.ArgumentCaptor<org.springframework.http.HttpEntity> captor =
                    org.mockito.ArgumentCaptor.forClass(org.springframework.http.HttpEntity.class);
            verify(restTemplate).postForEntity(contains("/api/conversations"), captor.capture(), eq(Map.class));

            assertThat(captor.getValue().getHeaders().getFirst("X-Organization-ID"))
                    .as("an anonymous visitor has no org header to forward, so the endpoint's "
                            + "own workspace must be sent or conversation-service rejects the create")
                    .isEqualTo(ENDPOINT_ORG_ID);
            assertThat(captor.getValue().getHeaders().getFirst("X-User-ID"))
                    .as("the owning tenant still identifies the caller")
                    .isEqualTo(TENANT_ID);
        }

        /**
         * A null endpoint org is NOT reachable from the database: V263 set
         * {@code standalone_chat_endpoints.organization_id} NOT NULL. This pins the DTO-level
         * contract instead - the helper must invent nothing when handed a blank org, so the
         * change stays a no-op on any path that supplies one (a hand-built DTO, a future caller,
         * a CE install restored from a pre-V263 dump).
         */
        @Test
        @DisplayName("Invents no X-Organization-ID when handed an endpoint without one")
        void endpointWithoutOrganizationSendsNoOrgHeader() {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();

            StandaloneChatEndpointDto endpoint = createEndpoint(TRIGGER_ID);
            endpoint.setOrganizationId(null);

            when(triggerClient.findChatEndpointByToken(TOKEN)).thenReturn(endpoint);
            when(redisTemplate.opsForHash()).thenReturn(hashOperations);
            when(restTemplate.postForEntity(contains("/api/conversations"),
                    any(org.springframework.http.HttpEntity.class), eq(Map.class)))
                    .thenReturn(org.springframework.http.ResponseEntity
                            .ok(Map.of("id", "conv-anon-2")));

            service.createOrResumeSession(TOKEN, null, "203.0.113.9");

            org.mockito.ArgumentCaptor<org.springframework.http.HttpEntity> captor =
                    org.mockito.ArgumentCaptor.forClass(org.springframework.http.HttpEntity.class);
            verify(restTemplate).postForEntity(contains("/api/conversations"), captor.capture(), eq(Map.class));

            assertThat(captor.getValue().getHeaders().getFirst("X-Organization-ID")).isNull();
        }

        /**
         * An authenticated caller (builder preview) has a bound request context. The
         * forwarder documents "explicit beats inherited", so the endpoint's own org must
         * win over whatever the caller happens to have active - the conversation belongs to
         * the endpoint's workspace, not to whoever opened the page.
         */
        @Test
        @DisplayName("Endpoint org wins over the caller's active org header")
        void endpointOrgBeatsInboundRequestOrg() {
            org.springframework.mock.web.MockHttpServletRequest mockReq =
                    new org.springframework.mock.web.MockHttpServletRequest();
            mockReq.addHeader("X-Organization-ID", "org-of-the-visitor");
            org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                    new org.springframework.web.context.request.ServletRequestAttributes(mockReq));

            try {
                StandaloneChatEndpointDto endpoint = createEndpoint(TRIGGER_ID);
                endpoint.setOrganizationId(ENDPOINT_ORG_ID);

                when(triggerClient.findChatEndpointByToken(TOKEN)).thenReturn(endpoint);
                when(redisTemplate.opsForHash()).thenReturn(hashOperations);
                when(restTemplate.postForEntity(contains("/api/conversations"),
                        any(org.springframework.http.HttpEntity.class), eq(Map.class)))
                        .thenReturn(org.springframework.http.ResponseEntity
                                .ok(Map.of("id", "conv-anon-3")));

                service.createOrResumeSession(TOKEN, null, "203.0.113.9");

                org.mockito.ArgumentCaptor<org.springframework.http.HttpEntity> captor =
                        org.mockito.ArgumentCaptor.forClass(org.springframework.http.HttpEntity.class);
                verify(restTemplate).postForEntity(contains("/api/conversations"), captor.capture(), eq(Map.class));

                assertThat(captor.getValue().getHeaders().getFirst("X-Organization-ID"))
                        .isEqualTo(ENDPOINT_ORG_ID);
            } finally {
                org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
            }
        }

        /**
         * The session hop is not the only anonymous hop. persistMessage swallows its failure
         * by design (a history write must never break the visitor's reply), so an org-less
         * call here fails INVISIBLY: the session works, the reply is returned, and every
         * message disappears from history behind a single WARN. Fixing only the session
         * creation would have turned a loud 500 into silent data loss.
         */
        @Test
        @DisplayName("Message persistence carries the endpoint org for an anonymous visitor")
        void anonymousPersistMessageSendsEndpointOrganizationId() {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();

            StandaloneChatEndpointDto endpoint = createEndpoint(TRIGGER_ID);
            endpoint.setOrganizationId(ENDPOINT_ORG_ID);
            WorkflowEntity workflow = createWorkflow(null);
            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER);
            // Keep the run in the endpoint's workspace: overriding only the endpoint's org would
            // send this down the workspace_mismatch branch, which skips the dispatch. The
            // assertion below would still hold (the user message is persisted first), but the
            // test would silently stop covering the path it names.
            run.setOrganizationId(ENDPOINT_ORG_ID);

            when(triggerClient.findChatEndpointByToken(TOKEN)).thenReturn(endpoint);
            when(redisTemplate.opsForHash()).thenReturn(hashOperations);
            when(hashOperations.entries(any())).thenReturn(Map.of(
                    "sessionId", "sess-1", "conversationId", "conv-1",
                    "chatEndpointId", endpoint.getId().toString(),
                    "tenantId", TENANT_ID, "ipAddress", "", "createdAt", "2026-01-01T00:00:00Z"));
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID))
                    .thenReturn(Optional.of(run));
            // This stub is required precisely BECAUSE the run now shares the endpoint's
            // workspace: the dispatch actually runs instead of short-circuiting on a mismatch.
            // Mockito flagging it as unnecessary is the signal that this test stopped covering
            // the path it names.
            when(triggerService.executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.CHAT), any()))
                    .thenReturn(TriggerExecutionResult.success(RUN_ID, TRIGGER_ID, TriggerType.CHAT, Set.of(), 1));

            service.sendMessage(TOKEN, "sess-1", "hello");

            org.mockito.ArgumentCaptor<org.springframework.http.HttpEntity> captor =
                    org.mockito.ArgumentCaptor.forClass(org.springframework.http.HttpEntity.class);
            verify(restTemplate, atLeastOnce()).postForEntity(contains("/messages"), captor.capture(), eq(Map.class));

            assertThat(captor.getValue().getHeaders().getFirst("X-Organization-ID"))
                    .as("without this the write is refused and the message vanishes behind a WARN")
                    .isEqualTo(ENDPOINT_ORG_ID);
        }

        /**
         * The other half of the role rule. Dropping the inherited role is only correct when the
         * org was actually overridden; when the caller is already IN the endpoint's workspace the
         * pair is consistent and the role must survive, or an org-RBAC check downstream loses the
         * privilege the caller genuinely holds.
         */
        @Test
        @DisplayName("Keeps the caller's role when their active org already is the endpoint's")
        void roleSurvivesWhenCallerOrgMatchesEndpointOrg() {
            org.springframework.mock.web.MockHttpServletRequest mockReq =
                    new org.springframework.mock.web.MockHttpServletRequest();
            mockReq.addHeader("X-Organization-ID", ENDPOINT_ORG_ID);
            mockReq.addHeader("X-Organization-Role", "ADMIN");
            org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                    new org.springframework.web.context.request.ServletRequestAttributes(mockReq));

            try {
                StandaloneChatEndpointDto endpoint = createEndpoint(TRIGGER_ID);
                endpoint.setOrganizationId(ENDPOINT_ORG_ID);

                when(triggerClient.findChatEndpointByToken(TOKEN)).thenReturn(endpoint);
                when(redisTemplate.opsForHash()).thenReturn(hashOperations);
                when(restTemplate.postForEntity(contains("/api/conversations"),
                        any(org.springframework.http.HttpEntity.class), eq(Map.class)))
                        .thenReturn(org.springframework.http.ResponseEntity
                                .ok(Map.of("id", "conv-same-org")));

                service.createOrResumeSession(TOKEN, null, "203.0.113.9");

                org.mockito.ArgumentCaptor<org.springframework.http.HttpEntity> captor =
                        org.mockito.ArgumentCaptor.forClass(org.springframework.http.HttpEntity.class);
                verify(restTemplate).postForEntity(contains("/api/conversations"), captor.capture(), eq(Map.class));

                assertThat(captor.getValue().getHeaders().getFirst("X-Organization-ID"))
                        .isEqualTo(ENDPOINT_ORG_ID);
                assertThat(captor.getValue().getHeaders().getFirst("X-Organization-Role"))
                        .as("same workspace, so the role is consistent and must not be stripped")
                        .isEqualTo("ADMIN");
            } finally {
                org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
            }
        }

        /**
         * The inheritance fallback the helper's javadoc promises, and the last untested cell of
         * its behaviour table: endpoint org absent, caller org present. Nothing is overridden, so
         * org AND role must both pass through untouched - the drop must not fire here, or a
         * caller acting in their own workspace silently loses the privilege they hold in it.
         */
        @Test
        @DisplayName("Inherits the caller's org and role when the endpoint supplies no org")
        void inboundOrgAndRoleSurviveWhenEndpointHasNoOrg() {
            org.springframework.mock.web.MockHttpServletRequest mockReq =
                    new org.springframework.mock.web.MockHttpServletRequest();
            mockReq.addHeader("X-Organization-ID", "org-of-the-caller");
            mockReq.addHeader("X-Organization-Role", "MEMBER");
            org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                    new org.springframework.web.context.request.ServletRequestAttributes(mockReq));

            try {
                StandaloneChatEndpointDto endpoint = createEndpoint(TRIGGER_ID);
                endpoint.setOrganizationId(null);

                when(triggerClient.findChatEndpointByToken(TOKEN)).thenReturn(endpoint);
                when(redisTemplate.opsForHash()).thenReturn(hashOperations);
                when(restTemplate.postForEntity(contains("/api/conversations"),
                        any(org.springframework.http.HttpEntity.class), eq(Map.class)))
                        .thenReturn(org.springframework.http.ResponseEntity
                                .ok(Map.of("id", "conv-inherited")));

                service.createOrResumeSession(TOKEN, null, "203.0.113.9");

                org.mockito.ArgumentCaptor<org.springframework.http.HttpEntity> captor =
                        org.mockito.ArgumentCaptor.forClass(org.springframework.http.HttpEntity.class);
                verify(restTemplate).postForEntity(contains("/api/conversations"), captor.capture(), eq(Map.class));

                assertThat(captor.getValue().getHeaders().getFirst("X-Organization-ID"))
                        .isEqualTo("org-of-the-caller");
                assertThat(captor.getValue().getHeaders().getFirst("X-Organization-Role"))
                        .as("nothing was overridden, so the inherited pair is still consistent")
                        .isEqualTo("MEMBER");
            } finally {
                org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
            }
        }

        /**
         * The cell the guard was deliberately widened to cover: a role arrives with NO inbound
         * org. The gateway never emits that pair, so this is unreachable today - but the guard
         * drops the role here on purpose rather than depending on an upstream invariant it
         * cannot see, and a deliberate widening that no test defends is one refactor away from
         * being narrowed back as dead weight. Narrowing it must turn this red.
         */
        @Test
        @DisplayName("Drops an inherited role that arrives with no inbound org at all")
        void roleWithoutInboundOrgIsDropped() {
            org.springframework.mock.web.MockHttpServletRequest mockReq =
                    new org.springframework.mock.web.MockHttpServletRequest();
            mockReq.addHeader("X-Organization-Role", "ADMIN");
            org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                    new org.springframework.web.context.request.ServletRequestAttributes(mockReq));

            try {
                StandaloneChatEndpointDto endpoint = createEndpoint(TRIGGER_ID);
                endpoint.setOrganizationId(ENDPOINT_ORG_ID);

                when(triggerClient.findChatEndpointByToken(TOKEN)).thenReturn(endpoint);
                when(redisTemplate.opsForHash()).thenReturn(hashOperations);
                when(restTemplate.postForEntity(contains("/api/conversations"),
                        any(org.springframework.http.HttpEntity.class), eq(Map.class)))
                        .thenReturn(org.springframework.http.ResponseEntity
                                .ok(Map.of("id", "conv-orphan-role")));

                service.createOrResumeSession(TOKEN, null, "203.0.113.9");

                org.mockito.ArgumentCaptor<org.springframework.http.HttpEntity> captor =
                        org.mockito.ArgumentCaptor.forClass(org.springframework.http.HttpEntity.class);
                verify(restTemplate).postForEntity(contains("/api/conversations"), captor.capture(), eq(Map.class));

                assertThat(captor.getValue().getHeaders().getFirst("X-Organization-ID"))
                        .isEqualTo(ENDPOINT_ORG_ID);
                assertThat(captor.getValue().getHeaders().getFirst("X-Organization-Role"))
                        .as("a role with no org of its own belongs to no workspace we are sending to")
                        .isNull();
            } finally {
                org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
            }
        }

        @Test
        @DisplayName("History fetch carries the endpoint org for an anonymous visitor")
        void anonymousGetHistorySendsEndpointOrganizationId() {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();

            StandaloneChatEndpointDto endpoint = createEndpoint(TRIGGER_ID);
            endpoint.setOrganizationId(ENDPOINT_ORG_ID);

            when(triggerClient.findChatEndpointByToken(TOKEN)).thenReturn(endpoint);
            when(redisTemplate.opsForHash()).thenReturn(hashOperations);
            when(hashOperations.entries(any())).thenReturn(Map.of(
                    "sessionId", "sess-1", "conversationId", "conv-1",
                    "chatEndpointId", endpoint.getId().toString(),
                    "tenantId", TENANT_ID, "ipAddress", "", "createdAt", "2026-01-01T00:00:00Z"));
            when(restTemplate.exchange(contains("/messages"),
                    eq(org.springframework.http.HttpMethod.GET),
                    any(org.springframework.http.HttpEntity.class), eq(String.class)))
                    .thenReturn(org.springframework.http.ResponseEntity.ok("[]"));

            service.getHistory(TOKEN, "sess-1");

            org.mockito.ArgumentCaptor<org.springframework.http.HttpEntity> captor =
                    org.mockito.ArgumentCaptor.forClass(org.springframework.http.HttpEntity.class);
            verify(restTemplate).exchange(contains("/messages"),
                    eq(org.springframework.http.HttpMethod.GET), captor.capture(), eq(String.class));

            assertThat(captor.getValue().getHeaders().getFirst("X-Organization-ID"))
                    .isEqualTo(ENDPOINT_ORG_ID);
        }
    }
}
