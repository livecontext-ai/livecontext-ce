package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.client.dto.execution.AgentExecutionRequestDto;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionResponseDto;
import com.apimarketplace.agent.config.AgentDefaultsConfig;
import com.apimarketplace.agent.config.ToolAccessControl;
import com.apimarketplace.agent.domain.*;
import com.apimarketplace.agent.loop.AgentLoopContext;
import com.apimarketplace.agent.loop.AgentLoopResult;
import com.apimarketplace.agent.loop.AgentLoopService;
import com.apimarketplace.agent.service.AgentObservabilityService;
import com.apimarketplace.agent.service.AgentService;
import com.apimarketplace.agent.service.AgentTaskService;
import com.apimarketplace.agent.service.budget.BudgetReservationService;
import com.apimarketplace.agent.service.budget.BudgetResolver;
import com.apimarketplace.agent.service.budget.BudgetState;
import com.apimarketplace.agent.service.budget.GuardChainFactory;
import com.apimarketplace.agent.service.budget.InsufficientBudgetException;
import com.apimarketplace.agent.service.budget.ModelCostCalculator;
import com.apimarketplace.conversation.client.ConversationClient;
import com.apimarketplace.agent.streaming.StreamingCallback;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.event.EventBus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("SubAgentExecutionHandler")
@ExtendWith(MockitoExtension.class)
class SubAgentExecutionHandlerTest {

    @Mock private AgentService agentService;
    @Mock private AgentLoopService agentLoopService;
    @Mock private CoreToolsCache coreToolsCache;
    @Mock private ConversationClient conversationServiceClient;
    @Mock private AgentObservabilityService observabilityService;
    @Mock private ConversationRedisStreamingCallback conversationRedisStreamingCallback;
    @Mock private StringRedisTemplate redisTemplate;
    @SuppressWarnings("unchecked")
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private EventBus eventBus;
    @Mock private BudgetResolver budgetResolver;
    @Mock private BudgetReservationService budgetReservationService;
    @Mock private CreditConsumptionClient creditConsumptionClient;
    @Mock private AgentTaskService agentTaskService;
    @Mock private GuardChainFactory guardChainFactory;
    @Mock private AgentActivityPublisher agentActivityPublisher;

    private ObjectMapper objectMapper;
    private AgentDefaultsConfig agentDefaults;
    private SubAgentExecutionHandler handler;

    private static final String TENANT_ID = "tenant-1";
    private static final String ORG_ID = "org-1";
    private static final String ORG_ROLE = "MEMBER";
    private static final UUID AGENT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        agentDefaults = new AgentDefaultsConfig();
        handler = new SubAgentExecutionHandler(
            agentService, agentLoopService, coreToolsCache,
            conversationServiceClient, observabilityService,
            conversationRedisStreamingCallback, redisTemplate, eventBus, objectMapper,
            budgetResolver, budgetReservationService, creditConsumptionClient, agentTaskService,
            guardChainFactory, agentActivityPublisher, agentDefaults);

        // GuardChainFactory.resolveCalculator returns a zero-cost calculator by default in tests
        lenient().when(guardChainFactory.resolveCalculator(any(), any()))
            .thenReturn(new ModelCostCalculator(new BigDecimal("0.001"), new BigDecimal("0.003"), BigDecimal.ZERO));

        // Tool resolution defaults (both overloads → empty). A null toolsConfig uses the no-arg
        // overload; a non-null toolsConfig now routes through the canonical FILTERED
        // getCoreTools(Set) overload (AgentModuleResolver → coreToolNames). Tests that only
        // exercise credential forwarding don't care about the returned tools - lenient defaults
        // keep them from NPE'ing and avoid UnnecessaryStubbing churn. Per-test stubs override.
        lenient().when(coreToolsCache.getCoreTools()).thenReturn(List.of());
        lenient().when(coreToolsCache.getCoreTools(anySet())).thenReturn(List.of());

        // Stub Redis for rate limiting (returns 1L → first call, under limit)
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.increment(anyString())).thenReturn(1L);

        // Default: memory is enabled so getConversationMessages is always called
        lenient().when(conversationServiceClient.getConversationMessages(any(), anyInt(), any()))
            .thenReturn(List.of());
        // Default: budget guards never deny - disabled state and large tenant balance
        lenient().when(budgetResolver.resolveAndPersist(any(AgentEntity.class), any(Instant.class)))
            .thenReturn(BudgetState.disabled());
        lenient().when(creditConsumptionClient.fetchBalance(anyString()))
            .thenReturn(new BigDecimal("999999"));
    }

    private ToolCall createToolCall(Map<String, Object> args) {
        return new ToolCall("tc-1", "agent", args, null);
    }

    private AgentEntity createAgent() {
        AgentEntity entity = new AgentEntity();
        entity.setId(AGENT_ID);
        entity.setName("Test Agent");
        entity.setModelProvider("openai");
        entity.setModelName("gpt-4");
        entity.setTemperature(new BigDecimal("0.7"));
        entity.setMaxTokens(4096);
        entity.setIsActive(true);
        entity.setSystemPrompt("You are a helpful assistant.");
        return entity;
    }

    private Map<String, Object> defaultCredentials() {
        Map<String, Object> creds = new HashMap<>();
        creds.put("__agent_depth__", 0);
        creds.put("turnId", "turn-1");
        creds.put("conversationId", "parent-conv-123");
        return creds;
    }

    @Nested
    @DisplayName("Parameter Validation")
    class ParameterValidationTests {

        @Test
        @DisplayName("should fail when agent_id is missing")
        void shouldFailMissingAgentId() {
            ToolCall toolCall = createToolCall(Map.of("action", "execute", "prompt", "hello"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, defaultCredentials());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("agent_id is required");
        }

        @Test
        @DisplayName("should fail when agent_id is not a valid UUID")
        void shouldFailInvalidUuid() {
            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", "not-a-uuid", "prompt", "hello"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, defaultCredentials());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("agent_id is required");
        }

        @Test
        @DisplayName("should fail when prompt is missing")
        void shouldFailMissingPrompt() {
            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString()));
            ToolResult result = handler.execute(toolCall, TENANT_ID, defaultCredentials());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("'prompt' is required");
        }

        @Test
        @DisplayName("should fail when prompt is blank")
        void shouldFailBlankPrompt() {
            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "  "));
            ToolResult result = handler.execute(toolCall, TENANT_ID, defaultCredentials());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("'prompt' is required");
        }
    }

    @Nested
    @DisplayName("Access Control")
    class AccessControlTests {

        @Test
        @DisplayName("should allow when no allowedAgentIds restriction")
        void shouldAllowNoRestriction() {
            AgentEntity entity = createAgent();
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("Done"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "Do something"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, defaultCredentials());

            assertThat(result.success()).isTrue();
        }

        @Test
        @DisplayName("should reject when agent not in allowedAgentIds list")
        void shouldRejectNotInAllowedList() {
            Map<String, Object> creds = defaultCredentials();
            creds.put("allowedAgentIds", List.of("other-agent-id"));

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, creds);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("not in your approved agent list");
        }

        @Test
        @DisplayName("rejects sub-agent execute when namespaced allowedAgentIds excludes the child")
        void rejectsSubAgentExecuteWhenNamespacedAllowedListExcludesChild() {
            Map<String, Object> creds = defaultCredentials();
            creds.put("__allowedAgentIds__", List.of("other-agent-id"));
            creds.put("agentAccessMode", "write");

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, creds);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("not in your approved agent list");
            verify(agentService, never()).getAgent(any(UUID.class), anyString());
        }

        @Test
        @DisplayName("rejects sub-agent execute when namespaced agent access mode is read-only")
        void rejectsSubAgentExecuteWhenAgentAccessModeIsReadOnly() {
            Map<String, Object> creds = defaultCredentials();
            creds.put("allowedAgentIds", List.of(AGENT_ID.toString()));
            creds.put("__agentAccessMode__", "read");

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, creds);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("read-only").contains("execute");
            verify(agentService, never()).getAgent(any(UUID.class), anyString());
        }

        @Test
        @DisplayName("should allow when agent in allowedAgentIds list")
        void shouldAllowWhenInList() {
            Map<String, Object> creds = defaultCredentials();
            creds.put("allowedAgentIds", List.of(AGENT_ID.toString()));

            AgentEntity entity = createAgent();
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("OK"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, creds);

            assertThat(result.success()).isTrue();
        }

        @Test
        @DisplayName("org-scoped sub-agent execute loads the child from the active organization workspace")
        void orgScopedSubAgentExecuteLoadsChildFromActiveOrganizationWorkspace() {
            Map<String, Object> creds = defaultCredentials();
            creds.put("__orgId__", "org-123");
            creds.put("__orgRole__", "MEMBER");
            creds.put("allowedAgentIds", List.of(AGENT_ID.toString()));

            AgentEntity entity = createAgent();
            entity.setOrganizationId("org-123");
            lenient().when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.empty());
            when(agentService.getAgent(AGENT_ID, TENANT_ID, "org-123", "MEMBER")).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), eq("org-123")))
                .thenReturn("conv-org-sub");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("OK"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, creds);

            assertThat(result.success()).isTrue();
            verify(agentService).getAgent(AGENT_ID, TENANT_ID, "org-123", "MEMBER");
            verify(agentService, never()).getAgent(AGENT_ID, TENANT_ID);
        }
    }

    @Nested
    @DisplayName("Agent Loading")
    class AgentLoadingTests {

        @Test
        @DisplayName("should fail when agent not found")
        void shouldFailAgentNotFound() {
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.empty());

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, defaultCredentials());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Agent not found");
        }

        @Test
        @DisplayName("should fail when agent is inactive")
        void shouldFailInactiveAgent() {
            AgentEntity entity = createAgent();
            entity.setIsActive(false);
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, defaultCredentials());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("inactive");
        }
    }

    @Nested
    @DisplayName("Execution")
    class ExecutionTests {

        @Test
        @DisplayName("should execute sub-agent successfully and return result")
        void shouldExecuteSuccessfully() {
            AgentEntity entity = createAgent();
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any())).thenReturn("conv-sub");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("Sub-agent completed the task"),
                List.of(), 2, null, 500, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "Analyse data"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, defaultCredentials());

            assertThat(result.success()).isTrue();
            assertThat(result.content()).contains("COMPLETED");
            assertThat(result.content()).contains("Sub-agent completed the task");
            assertThat(result.content()).contains("Test Agent");
        }

        @Test
        @DisplayName("should pass context + prompt as full prompt")
        void shouldCombineContextAndPrompt() {
            AgentEntity entity = createAgent();
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("Done"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(),
                "prompt", "Summarize this", "context", "Important data here"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            // Verify the user prompt passed to saveMessage contains context + prompt
            ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
            verify(conversationServiceClient).saveMessage(
                eq("conv-1"), eq("user"), contentCaptor.capture(), isNull(), eq(TENANT_ID), any());

            String savedPrompt = contentCaptor.getValue();
            assertThat(savedPrompt).contains("Context:");
            assertThat(savedPrompt).contains("Important data here");
            assertThat(savedPrompt).contains("Task:");
            assertThat(savedPrompt).contains("Summarize this");
        }

        @Test
        @DisplayName("should increment depth in sub-agent credentials")
        void shouldIncrementDepth() {
            AgentEntity entity = createAgent();
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("OK"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            Map<String, Object> creds = defaultCredentials();
            creds.put("__agent_depth__", 2);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            handler.execute(toolCall, TENANT_ID, creds);

            // Verify depth was incremented in the context passed to agentLoopService
            ArgumentCaptor<AgentLoopContext> contextCaptor = ArgumentCaptor.forClass(AgentLoopContext.class);
            verify(agentLoopService).execute(contextCaptor.capture(), any(StreamingCallback.class));

            Map<String, Object> subCreds = contextCaptor.getValue().credentials();
            assertThat(subCreds.get("__agent_depth__")).isEqualTo(3);
        }

        @Test
        @DisplayName("stamps a non-blank executionId on the sub-agent context so CE relay billing aggregates it")
        void stampsExecutionIdOnSubAgentContext() {
            AgentEntity entity = createAgent();
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(AgentLoopResult.success(
                    CompletionResponse.text("OK"), List.of(), 1, null, 100, "openai", "gpt-4"));

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            // Without the stamp the sub-agent would silently fall back to per-call CE relay billing.
            ArgumentCaptor<AgentLoopContext> contextCaptor = ArgumentCaptor.forClass(AgentLoopContext.class);
            verify(agentLoopService).execute(contextCaptor.capture(), any(StreamingCallback.class));
            assertThat(contextCaptor.getValue().executionId()).isNotBlank();
        }

        @Test
        @DisplayName("should forward parent restrictions to sub-agent")
        void shouldForwardRestrictions() {
            AgentEntity entity = createAgent();
            entity.setOrganizationId("org-123");
            when(agentService.getAgent(AGENT_ID, TENANT_ID, "org-123", null)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), eq("org-123")))
                .thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("OK"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            Map<String, Object> creds = defaultCredentials();
            creds.put("__allowedWorkflowIds__", List.of("wf-1", "wf-2"));
            creds.put("__allowedFileIds__", List.of("file-1"));
            creds.put("__orgId__", "org-123");
            creds.put("__workflowRunId__", "run-456");

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            handler.execute(toolCall, TENANT_ID, creds);

            ArgumentCaptor<AgentLoopContext> contextCaptor = ArgumentCaptor.forClass(AgentLoopContext.class);
            verify(agentLoopService).execute(contextCaptor.capture(), any(StreamingCallback.class));

            Map<String, Object> subCreds = contextCaptor.getValue().credentials();
            assertThat(subCreds.get("__allowedWorkflowIds__")).isEqualTo(List.of("wf-1", "wf-2"));
            assertThat(subCreds.get("__orgId__")).isEqualTo("org-123");
            assertThat(subCreds.get("__workflowRunId__")).isEqualTo("run-456");
            // The parent's file allow-list must BIND the config-less child - assert the
            // RESOLVED scope (the value FilesToolsProvider actually reads), not just the
            // forwarded namespaced key. Pre-fix the child also got a plain allowedFileIds=[]
            // that shadowed the forward (plain wins) and resolved to "unrestricted" → escape.
            assertThat(ToolAccessControl.getAllowedIds(subCreds, "file")).isEqualTo(List.of("file-1"));
        }

        @Test
        @DisplayName("org credential scopes sub-agent conversation and observability")
        void orgCredentialScopesConversationAndObservability() {
            AgentEntity entity = createAgent();
            entity.setOrganizationId("org-123");
            when(agentService.getAgent(AGENT_ID, TENANT_ID, "org-123", null)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), eq("org-123")))
                .thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("OK"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            Map<String, Object> creds = defaultCredentials();
            creds.put("__orgId__", "org-123");

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello", "memory", false));
            handler.execute(toolCall, TENANT_ID, creds);

            verify(conversationServiceClient).findOrCreateAgentConversation(
                AGENT_ID.toString(), TENANT_ID, entity.getName(), "org-123");
            verify(conversationServiceClient).saveMessage(
                eq("conv-1"), eq("user"), eq("hello"), isNull(), eq(TENANT_ID), any(), eq("org-123"));
            verify(conversationServiceClient).saveMessage(
                eq("conv-1"), eq("assistant"), eq("OK"), isNull(), eq(TENANT_ID), any(), eq("org-123"));

            ArgumentCaptor<com.apimarketplace.agent.client.dto.AgentObservabilityRequest> obsCaptor =
                ArgumentCaptor.forClass(com.apimarketplace.agent.client.dto.AgentObservabilityRequest.class);
            verify(observabilityService).recordFromRequest(obsCaptor.capture());
            assertThat(obsCaptor.getValue().getOrganizationId()).isEqualTo("org-123");
            assertThat(obsCaptor.getValue().getSource()).isEqualTo("SUB_AGENT");
        }

        @Test
        @DisplayName("Sub-agent cascade: absent internal-list key in child config writes [] cred (regression)")
        void subAgentCascadeSetsEmptyCredsWhenChildHasAbsentKey() {
            // Pre-fix: passAllowedIds only put a credential when value was a List;
            // absent keys silently fell through, downstream tool modules saw null and
            // treated as "no restriction" → child sub-agent had unrestricted access
            // to every workflow/table/interface/agent/application in the tenant.
            //
            // Post-fix: passAllowedIds writes List.of() for absent keys, and the
            // parent applyToolsConfigCredentials uses an empty map when the whole
            // toolsConfig is null. Ensures legacy agents (pre-V163) cannot escalate
            // through the sub-agent execution path.
            AgentEntity entity = createAgent();
            // Child has only `mode` and `tools` - none of the 5 internal lists
            entity.setToolsConfig(Map.of("mode", "all"));
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("OK"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            ArgumentCaptor<AgentLoopContext> contextCaptor = ArgumentCaptor.forClass(AgentLoopContext.class);
            verify(agentLoopService).execute(contextCaptor.capture(), any(StreamingCallback.class));

            Map<String, Object> subCreds = contextCaptor.getValue().credentials();
            assertThat(subCreds.get("allowedWorkflowIds")).isEqualTo(List.of());
            assertThat(subCreds.get("allowedTableIds")).isEqualTo(List.of());
            assertThat(subCreds.get("allowedInterfaceIds")).isEqualTo(List.of());
            assertThat(subCreds.get("allowedAgentIds")).isEqualTo(List.of());
            assertThat(subCreds.get("allowedApplicationIds")).isEqualTo(List.of());
        }

        @Test
        @DisplayName("Sub-agent cascade forwards child access modes into runtime credentials")
        void subAgentCascadeForwardsAccessModes() {
            AgentEntity entity = createAgent();
            entity.setToolsConfig(Map.of(
                "tables", List.of("table-1"),
                "agents", List.of(AGENT_ID.toString()),
                "tableAccessMode", "read",
                "workflowAccessMode", "read",
                "interfaceAccessMode", "write",
                "agentAccessMode", "read",
                "applicationAccessMode", "write",
                "skillAccessMode", "read",
                "fileAccessMode", "read"
            ));
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("OK"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            ArgumentCaptor<AgentLoopContext> contextCaptor = ArgumentCaptor.forClass(AgentLoopContext.class);
            verify(agentLoopService).execute(contextCaptor.capture(), any(StreamingCallback.class));

            Map<String, Object> subCreds = contextCaptor.getValue().credentials();
            assertThat(subCreds).containsEntry("allowedTableIds", List.of("table-1"));
            assertThat(subCreds).containsEntry("allowedAgentIds", List.of(AGENT_ID.toString()));
            assertThat(subCreds).containsEntry("tableAccessMode", "read");
            assertThat(subCreds).containsEntry("workflowAccessMode", "read");
            assertThat(subCreds).containsEntry("interfaceAccessMode", "write");
            assertThat(subCreds).containsEntry("agentAccessMode", "read");
            assertThat(subCreds).containsEntry("applicationAccessMode", "write");
            assertThat(subCreds).containsEntry("skillAccessMode", "read");
            assertThat(subCreds).containsEntry("fileAccessMode", "read");
        }

        @Test
        @DisplayName("Sub-agent cascade: an UNKNOWN grant ('bogus') → [] deny-by-default, never the stale list (must NOT fail OPEN)")
        void subAgentCascadeUnknownGrantFailsClosed() {
            AgentEntity entity = createAgent();
            entity.setToolsConfig(Map.of(
                "workflows", List.of("wf-stale"), // a stale list behind a junk grant
                "workflowsGrant", "bogus"
            ));
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("OK"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            ArgumentCaptor<AgentLoopContext> contextCaptor = ArgumentCaptor.forClass(AgentLoopContext.class);
            verify(agentLoopService).execute(contextCaptor.capture(), any(StreamingCallback.class));

            Map<String, Object> subCreds = contextCaptor.getValue().credentials();
            // Deny: [] (present, not omitted → not unrestricted), and NOT the stale ['wf-stale'].
            assertThat(subCreds).containsEntry("allowedWorkflowIds", List.of());
        }

        @Test
        @DisplayName("Sub-agent cascade stringifies a NUMERIC table allow-list (MCP stores tables:[209] as Integer)")
        void subAgentCascadeStringifiesNumericTableAllowlist() {
            // Regression: an agent created via MCP stores its table allow-list with the
            // native JSON type, so `tables:[209]` is a List<Integer>. Tool modules compare
            // with `.contains(String.valueOf(id))`, and a List<Integer> never contains a
            // String → silent "This table is not in your approved table list." The cascade
            // must normalize every element to String before forwarding it.
            AgentEntity entity = createAgent();
            entity.setToolsConfig(Map.of(
                "mode", "none",
                "tables", List.of(209, 42)
            ));
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any())).thenReturn("conv-1");

            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("OK"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            ArgumentCaptor<AgentLoopContext> contextCaptor = ArgumentCaptor.forClass(AgentLoopContext.class);
            verify(agentLoopService).execute(contextCaptor.capture(), any(StreamingCallback.class));

            Map<String, Object> subCreds = contextCaptor.getValue().credentials();
            // Stringified, not the raw List<Integer>.
            assertThat(subCreds).containsEntry("allowedTableIds", List.of("209", "42"));
            // And the canonical read path resolves the same strings the tool modules compare against.
            assertThat(ToolAccessControl.getAllowedIds(subCreds, "table")).isEqualTo(List.of("209", "42"));
        }

        @Test
        @DisplayName("Sub-agent cascade forwards child file allow-list (toolsConfig.files) into runtime credentials")
        void subAgentCascadeForwardsChildFileScope() {
            // A scoped sub-agent must carry its own file allow-list like tables/workflows;
            // FilesToolsProvider reads allowedFileIds to scope list/get/view to those ids.
            AgentEntity entity = createAgent();
            entity.setToolsConfig(Map.of(
                "mode", "none",
                "files", List.of("file-1", "file-2")
            ));
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any())).thenReturn("conv-1");

            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("OK"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            ArgumentCaptor<AgentLoopContext> contextCaptor = ArgumentCaptor.forClass(AgentLoopContext.class);
            verify(agentLoopService).execute(contextCaptor.capture(), any(StreamingCallback.class));

            Map<String, Object> subCreds = contextCaptor.getValue().credentials();
            assertThat(subCreds).containsEntry("allowedFileIds", List.of("file-1", "file-2"));
            assertThat(ToolAccessControl.getAllowedIds(subCreds, "file")).isEqualTo(List.of("file-1", "file-2"));
        }

        @Test
        @DisplayName("Sub-agent file scope is opt-in: a config-less child of an UNSCOPED parent stays unrestricted")
        void subAgentFileScopeOptInWhenNeitherParentNorChildScopes() {
            // Files are opt-in everywhere: no parent forward + no child files list = full org
            // access (null resolved scope). The child must NOT get allowedFileIds=[] (which for
            // the 5 internal resources means deny-all) - that asymmetry is the whole point.
            AgentEntity entity = createAgent();
            entity.setToolsConfig(Map.of("mode", "all"));
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("OK"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            // Parent credentials carry NO __allowedFileIds__ (unscoped parent).
            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            ArgumentCaptor<AgentLoopContext> contextCaptor = ArgumentCaptor.forClass(AgentLoopContext.class);
            verify(agentLoopService).execute(contextCaptor.capture(), any(StreamingCallback.class));

            Map<String, Object> subCreds = contextCaptor.getValue().credentials();
            // No empty allowedFileIds written (unlike the 5 internal resources, which ARE []).
            assertThat(subCreds).doesNotContainKey("allowedFileIds");
            assertThat(ToolAccessControl.getAllowedIds(subCreds, "file")).isNull();
            // Contrast: an internal resource with no child config IS locked to [].
            assertThat(subCreds).containsEntry("allowedTableIds", List.of());
        }

        @Test
        @DisplayName("Sub-agent grant='all' + empty list → allowed<Family>Ids OMITTED (unrestricted)")
        void subAgentCascadeGrantAllOmitsCredential() {
            // The bug: a grant:'all' + empty-list child was BLOCKED on every family when run
            // as a sub-agent, because passAllowedIds wrote allowed<Family>Ids=[] (deny-all)
            // ignoring the grant. With the omission fix, grant='all' OMITS the credential
            // → ToolAccessControl.getAllowedIds returns null = unrestricted access.
            AgentEntity entity = createAgent();
            // workflows + tables granted 'all' with EMPTY lists (V163 self-describing shape).
            entity.setToolsConfig(Map.of(
                "mode", "all",
                "workflows", List.of(),
                "workflowsGrant", "all",
                "tables", List.of(),
                "tablesGrant", "all"
            ));
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("OK"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            ArgumentCaptor<AgentLoopContext> contextCaptor = ArgumentCaptor.forClass(AgentLoopContext.class);
            verify(agentLoopService).execute(contextCaptor.capture(), any(StreamingCallback.class));

            Map<String, Object> subCreds = contextCaptor.getValue().credentials();
            // grant='all' families: credential OMITTED → unrestricted.
            assertThat(subCreds).doesNotContainKey("allowedWorkflowIds");
            assertThat(subCreds).doesNotContainKey("allowedTableIds");
            assertThat(ToolAccessControl.getAllowedIds(subCreds, "workflow")).isNull();
            assertThat(ToolAccessControl.getAllowedIds(subCreds, "table")).isNull();
            // Ungranted families with no list still get []  (deny-all default).
            assertThat(subCreds).containsEntry("allowedInterfaceIds", List.of());
        }

        @Test
        @DisplayName("Sub-agent grant='none' → [], grant='custom'+list → the list")
        void subAgentCascadeGrantNoneAndCustom() {
            AgentEntity entity = createAgent();
            entity.setToolsConfig(Map.of(
                "mode", "all",
                // workflows: none → []
                "workflows", List.of(),
                "workflowsGrant", "none",
                // tables: custom with a list → that list
                "tables", List.of("table-1", "table-2"),
                "tablesGrant", "custom"
            ));
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("OK"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            ArgumentCaptor<AgentLoopContext> contextCaptor = ArgumentCaptor.forClass(AgentLoopContext.class);
            verify(agentLoopService).execute(contextCaptor.capture(), any(StreamingCallback.class));

            Map<String, Object> subCreds = contextCaptor.getValue().credentials();
            // grant='none' → deny-all empty list (present, not omitted).
            assertThat(subCreds).containsEntry("allowedWorkflowIds", List.of());
            // grant='custom' → exactly the configured list.
            assertThat(subCreds).containsEntry("allowedTableIds", List.of("table-1", "table-2"));
            assertThat(ToolAccessControl.getAllowedIds(subCreds, "table"))
                .containsExactly("table-1", "table-2");
        }

        @Test
        @DisplayName("should save assistant response after execution")
        void shouldSaveAssistantResponse() {
            AgentEntity entity = createAgent();
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("Final response"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            // Verify assistant message saved
            verify(conversationServiceClient).saveMessage(
                eq("conv-1"), eq("assistant"), eq("Final response"), any(), eq(TENANT_ID), any());
        }

        @Test
        @DisplayName("should record observability after execution")
        void shouldRecordObservability() {
            AgentEntity entity = createAgent();
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("OK"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            verify(observabilityService).recordFromRequest(any());
        }
    }

    @Nested
    @DisplayName("Rate Limiting")
    class RateLimitTests {

        @Test
        @DisplayName("should allow up to 5 executions per turn (unified maxPerResourcePerTurn default)")
        void shouldAllowUpTo5() {
            AgentEntity entity = createAgent();
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("OK"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            Map<String, Object> creds = defaultCredentials();
            creds.put("turnId", "same-turn");

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));

            // Stub Redis increment to return sequential counts (1..6)
            when(valueOperations.increment(anyString()))
                .thenReturn(1L, 2L, 3L, 4L, 5L, 6L);

            // First 5 should succeed
            for (int i = 0; i < 5; i++) {
                ToolResult result = handler.execute(toolCall, TENANT_ID, creds);
                assertThat(result.success()).isTrue();
            }

            // 6th should fail
            ToolResult result = handler.execute(toolCall, TENANT_ID, creds);
            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("LIMIT REACHED");
        }

        @Test
        @DisplayName("should proceed when Redis rate limit throws (graceful degradation)")
        void shouldProceedWhenRedisRateLimitFails() {
            AgentEntity entity = createAgent();
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("OK"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            // Redis throws on increment → graceful degradation, execution should proceed
            when(valueOperations.increment(anyString()))
                .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("Connection refused"));

            Map<String, Object> creds = defaultCredentials();
            creds.put("turnId", "some-turn");

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));

            ToolResult result = handler.execute(toolCall, TENANT_ID, creds);
            assertThat(result.success()).isTrue();
        }
    }

    @Nested
    @DisplayName("Response Truncation")
    class TruncationTests {

        @Test
        @DisplayName("should truncate long responses and save full content")
        void shouldTruncateLongResponse() {
            AgentEntity entity = createAgent();
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            // Create a response longer than 50000 chars
            String longContent = "x".repeat(60000);
            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text(longContent), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            when(conversationServiceClient.saveToolResult(
                any(), any(), any(), any(), anyBoolean(), anyLong(), any(), any()))
                .thenReturn("result-id");

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, defaultCredentials());

            assertThat(result.success()).isTrue();
            assertThat(result.content()).contains("truncated");
            assertThat(result.content()).contains("full_response_tool_call_id");

            // Verify full content was saved
            verify(conversationServiceClient).saveToolResult(
                eq("parent-conv-123"), eq(TENANT_ID),
                startsWith("agent_execute:"), any(),
                eq(true), anyLong(), eq(longContent), isNull());
        }
    }

    @Nested
    @DisplayName("Tool Resolution")
    class ToolResolutionTests {

        @Test
        @DisplayName("should use all core tools when toolsConfig is null")
        void shouldUseAllToolsWhenConfigNull() {
            AgentEntity entity = createAgent();
            entity.setToolsConfig(null);
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any())).thenReturn("conv-1");
            when(coreToolsCache.getCoreTools()).thenReturn(List.of(
                ToolDefinition.builder().name("agent").build(),
                ToolDefinition.builder().name("workflow").build()
            ));

            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("OK"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            ArgumentCaptor<AgentLoopContext> contextCaptor = ArgumentCaptor.forClass(AgentLoopContext.class);
            verify(agentLoopService).execute(contextCaptor.capture(), any(StreamingCallback.class));

            assertThat(contextCaptor.getValue().tools()).hasSize(2);
        }

        @Test
        @DisplayName("mode='none' scopes to internal modules (catalog blocked) - canonical resolution, parity with chat & workflow (NOT zero tools)")
        void modeNoneScopesToInternalModulesBlockingCatalog() {
            AgentEntity entity = createAgent();
            entity.setToolsConfig(Map.of("mode", "none"));
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any())).thenReturn("conv-1");
            when(coreToolsCache.getCoreTools(anySet())).thenReturn(List.of(
                ToolDefinition.builder().name("table").build()));

            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("OK"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            // Canonical AgentModuleResolver semantics: mode=none blocks only MCP/catalog tools;
            // internal tools (table, agent, …) stay enabled. The sub-agent path now resolves via
            // AgentModuleResolver → coreToolNames exactly like chat & workflow - it USED to return
            // List.of() (zero tools), diverging from the other two paths. So we assert the FILTERED
            // overload is used with catalog dropped but internal tools present - never the no-arg one.
            verify(coreToolsCache).getCoreTools(argThat(set ->
                !set.contains("catalog") && set.contains("table")));
            verify(coreToolsCache, never()).getCoreTools();
        }

        @Test
        @DisplayName("mode='custom' scopes by per-family grants (canonical) - granted families enabled, ungranted blocked; raw 'tools' list is no longer the gate")
        void modeCustomScopesByFamilyGrants() {
            AgentEntity entity = createAgent();
            // Canonical custom: the per-family <family>Grant fields decide access (the raw `tools`
            // list is only the "custom" payload, never the gate). tables+agents granted ⇒ enabled;
            // catalog/skill/files are always-on; interfaces/workflows/applications absent ⇒ blocked.
            entity.setToolsConfig(Map.of("mode", "custom", "tablesGrant", "all", "agentsGrant", "all"));
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any())).thenReturn("conv-1");
            when(coreToolsCache.getCoreTools(anySet())).thenReturn(List.of(
                ToolDefinition.builder().name("table").build(),
                ToolDefinition.builder().name("agent").build()
            ));

            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("OK"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "hello"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            verify(coreToolsCache).getCoreTools(argThat(set ->
                set.contains("table") && set.contains("agent")          // granted families
                && !set.contains("workflow") && !set.contains("interface"))); // ungranted ⇒ blocked
            verify(coreToolsCache, never()).getCoreTools();
        }
    }

    @Nested
    @DisplayName("Memory")
    class MemoryTests {

        private void setupAgentExecution() {
            AgentEntity entity = createAgent();
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any())).thenReturn("conv-sub");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("Done"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);
        }

        @Test
        @DisplayName("should load conversation history by default (memory=true)")
        void shouldLoadHistoryByDefault() {
            setupAgentExecution();
            when(conversationServiceClient.getConversationMessages(eq("conv-sub"), eq(20), eq(TENANT_ID)))
                .thenReturn(List.of(
                    Map.of("role", "user", "content", "Previous question"),
                    Map.of("role", "assistant", "content", "Previous answer")
                ));

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "New task"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            // Verify conversation history was loaded
            verify(conversationServiceClient).getConversationMessages("conv-sub", 20, TENANT_ID);

            // Verify history was passed to agent loop
            ArgumentCaptor<AgentLoopContext> contextCaptor = ArgumentCaptor.forClass(AgentLoopContext.class);
            verify(agentLoopService).execute(contextCaptor.capture(), any(StreamingCallback.class));

            List<Message> history = contextCaptor.getValue().conversationHistory();
            assertThat(history).hasSize(2);
            assertThat(history.get(0).role()).isEqualTo(Message.Role.USER);
            assertThat(history.get(0).content()).isEqualTo("Previous question");
            assertThat(history.get(1).role()).isEqualTo(Message.Role.ASSISTANT);
            assertThat(history.get(1).content()).isEqualTo("Previous answer");
        }

        @Test
        @DisplayName("should skip history when memory=false")
        void shouldSkipHistoryWhenMemoryFalse() {
            setupAgentExecution();

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(),
                "prompt", "New task", "memory", false));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            // Verify conversation history was NOT loaded
            verify(conversationServiceClient, never()).getConversationMessages(any(), anyInt(), any());

            // Verify empty history passed to agent loop
            ArgumentCaptor<AgentLoopContext> contextCaptor = ArgumentCaptor.forClass(AgentLoopContext.class);
            verify(agentLoopService).execute(contextCaptor.capture(), any(StreamingCallback.class));

            assertThat(contextCaptor.getValue().conversationHistory()).isEmpty();
        }

        @Test
        @DisplayName("should pass memory=true explicitly")
        void shouldLoadHistoryWhenMemoryTrue() {
            setupAgentExecution();
            when(conversationServiceClient.getConversationMessages(eq("conv-sub"), eq(20), eq(TENANT_ID)))
                .thenReturn(List.of(Map.of("role", "user", "content", "Hello")));

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(),
                "prompt", "New task", "memory", true));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            verify(conversationServiceClient).getConversationMessages("conv-sub", 20, TENANT_ID);
        }

        @Test
        @DisplayName("should handle empty conversation gracefully")
        void shouldHandleEmptyConversation() {
            setupAgentExecution();
            when(conversationServiceClient.getConversationMessages(eq("conv-sub"), eq(20), eq(TENANT_ID)))
                .thenReturn(List.of());

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "First task"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            ArgumentCaptor<AgentLoopContext> contextCaptor = ArgumentCaptor.forClass(AgentLoopContext.class);
            verify(agentLoopService).execute(contextCaptor.capture(), any(StreamingCallback.class));

            assertThat(contextCaptor.getValue().conversationHistory()).isEmpty();
        }

        @Test
        @DisplayName("should skip system and tool messages from history")
        void shouldFilterSystemAndToolMessages() {
            setupAgentExecution();
            when(conversationServiceClient.getConversationMessages(eq("conv-sub"), eq(20), eq(TENANT_ID)))
                .thenReturn(List.of(
                    Map.of("role", "system", "content", "System prompt"),
                    Map.of("role", "user", "content", "Question"),
                    Map.of("role", "assistant", "content", "Answer"),
                    Map.of("role", "tool", "content", "Tool result")
                ));

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "Next"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            ArgumentCaptor<AgentLoopContext> contextCaptor = ArgumentCaptor.forClass(AgentLoopContext.class);
            verify(agentLoopService).execute(contextCaptor.capture(), any(StreamingCallback.class));

            List<Message> history = contextCaptor.getValue().conversationHistory();
            assertThat(history).hasSize(2);
            assertThat(history.get(0).content()).isEqualTo("Question");
            assertThat(history.get(1).content()).isEqualTo("Answer");
        }

        @Test
        @DisplayName("should treat non-boolean memory param as default (true)")
        void shouldTreatStringMemoryAsDefault() {
            setupAgentExecution();
            when(conversationServiceClient.getConversationMessages(eq("conv-sub"), eq(20), eq(TENANT_ID)))
                .thenReturn(List.of(Map.of("role", "user", "content", "Old msg")));

            // Pass memory as string "false" - not a Boolean, so defaults to true
            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(),
                "prompt", "Task", "memory", "false"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            // String "false" is not Boolean → defaults to true → history loaded
            verify(conversationServiceClient).getConversationMessages("conv-sub", 20, TENANT_ID);
        }

        @Test
        @DisplayName("should handle getConversationMessages exception gracefully")
        void shouldHandleMessageLoadException() {
            setupAgentExecution();
            when(conversationServiceClient.getConversationMessages(any(), anyInt(), any()))
                .thenThrow(new RuntimeException("Connection refused"));

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "Task"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, defaultCredentials());

            // Should still succeed with empty history
            assertThat(result.success()).isTrue();

            ArgumentCaptor<AgentLoopContext> contextCaptor = ArgumentCaptor.forClass(AgentLoopContext.class);
            verify(agentLoopService).execute(contextCaptor.capture(), any(StreamingCallback.class));
            assertThat(contextCaptor.getValue().conversationHistory()).isEmpty();
        }

        @Test
        @DisplayName("should record memoryEnabled in observability")
        void shouldRecordMemoryInObservability() {
            setupAgentExecution();

            // Execute with memory=false
            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(),
                "prompt", "Task", "memory", false));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            ArgumentCaptor<com.apimarketplace.agent.client.dto.AgentObservabilityRequest> obsCaptor =
                ArgumentCaptor.forClass(com.apimarketplace.agent.client.dto.AgentObservabilityRequest.class);
            verify(observabilityService).recordFromRequest(obsCaptor.capture());
            assertThat(obsCaptor.getValue().getMemoryEnabled()).isFalse();
        }

        @Test
        @DisplayName("should record memoryEnabled=true in observability by default")
        void shouldRecordMemoryTrueByDefault() {
            setupAgentExecution();

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "Task"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            ArgumentCaptor<com.apimarketplace.agent.client.dto.AgentObservabilityRequest> obsCaptor =
                ArgumentCaptor.forClass(com.apimarketplace.agent.client.dto.AgentObservabilityRequest.class);
            verify(observabilityService).recordFromRequest(obsCaptor.capture());
            assertThat(obsCaptor.getValue().getMemoryEnabled()).isTrue();
        }

        @Test
        @DisplayName("should prepend SYSTEM and USER messages to observability conversation")
        void shouldPrependSystemAndUserMessages() {
            setupAgentExecution();

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(),
                "prompt", "Summarize this dataset"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            ArgumentCaptor<com.apimarketplace.agent.client.dto.AgentObservabilityRequest> obsCaptor =
                ArgumentCaptor.forClass(com.apimarketplace.agent.client.dto.AgentObservabilityRequest.class);
            verify(observabilityService).recordFromRequest(obsCaptor.capture());

            var req = obsCaptor.getValue();
            // System prompt now includes the full modular prompt (tool docs, core rules)
            // with the custom entity prompt appended at the end
            assertThat(req.getSystemPrompt()).contains("You are a helpful assistant.");
            assertThat(req.getSystemPrompt()).contains("# Available Tools");
            // Messages list starts with SYSTEM + USER (normally missing from AgentLoopResult
            // .conversationHistory() which excludes the initial prompts).
            assertThat(req.getMessages()).isNotNull();
            assertThat(req.getMessages()).hasSizeGreaterThanOrEqualTo(2);
            var sys = req.getMessages().get(0);
            assertThat(sys.getRole()).isEqualTo("SYSTEM");
            assertThat(sys.getContent()).contains("You are a helpful assistant.");
            assertThat(sys.getContent()).contains("# Available Tools");
            var usr = req.getMessages().get(1);
            assertThat(usr.getRole()).isEqualTo("USER");
            assertThat(usr.getContent()).isEqualTo("Summarize this dataset");
        }

        @Test
        @DisplayName("should include optional context in USER message when provided")
        void shouldIncludeContextInUserMessage() {
            setupAgentExecution();

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(),
                "prompt", "Write a report",
                "context", "Q1 sales figures"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            ArgumentCaptor<com.apimarketplace.agent.client.dto.AgentObservabilityRequest> obsCaptor =
                ArgumentCaptor.forClass(com.apimarketplace.agent.client.dto.AgentObservabilityRequest.class);
            verify(observabilityService).recordFromRequest(obsCaptor.capture());

            var req = obsCaptor.getValue();
            var usr = req.getMessages().get(1);
            assertThat(usr.getRole()).isEqualTo("USER");
            assertThat(usr.getContent()).contains("Q1 sales figures").contains("Write a report");
        }
    }

    // ==========================================================================
    // Cascade reservation (§4.4 AGENT_BUDGET_HIERARCHY.md)
    // ==========================================================================

    @Nested
    @DisplayName("Cascade Reservation")
    class CascadeReservationTests {

        private final UUID PARENT_AGENT_ID = UUID.randomUUID();

        private Map<String, Object> parentCredentials() {
            // __agentId__ is the reserved key SubAgentExecutionHandler reads to derive
            // callerAgentEntityId - without it, the handler treats the call as a root
            // invocation and the cascade path is skipped entirely.
            Map<String, Object> creds = defaultCredentials();
            creds.put("__agentId__", PARENT_AGENT_ID.toString());
            return creds;
        }

        private void setupAgentForSuccessfulExecution() {
            AgentEntity entity = createAgent();
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any()))
                .thenReturn("conv-sub");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("Done"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);
        }

        @Test
        @DisplayName("should reserve chain with parent id when spawned from another agent")
        void reservesChainOnSuccessfulSpawn() {
            setupAgentForSuccessfulExecution();

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(),
                "prompt", "hello", "budget_reservation", 10));
            ToolResult result = handler.execute(toolCall, TENANT_ID, parentCredentials());

            assertThat(result.success()).isTrue();
            // Chain is nearest-first: [parent] because parent has no upstream __callerChain__.
            ArgumentCaptor<List<UUID>> chainCaptor = ArgumentCaptor.forClass(List.class);
            ArgumentCaptor<BigDecimal> amountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
            verify(budgetReservationService).tryReserveChain(chainCaptor.capture(), amountCaptor.capture());
            assertThat(chainCaptor.getValue()).containsExactly(PARENT_AGENT_ID);
            assertThat(amountCaptor.getValue()).isEqualByComparingTo(new BigDecimal("10"));
        }

        @Test
        @DisplayName("should return BUDGET_EXHAUSTED failure when ancestor lacks free budget")
        void insufficientBudgetExceptionShortCircuitsSpawn() {
            AgentEntity entity = createAgent();
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any()))
                .thenReturn("conv-sub");

            // tryReserveChain rejects spawn: one ancestor ran out of room. The whole
            // cascade transaction is rolled back by BudgetReservationService internally,
            // so the handler is NOT expected to call settle/refund - there was nothing
            // successfully held to release.
            doThrow(new InsufficientBudgetException(PARENT_AGENT_ID, new BigDecimal("50")))
                .when(budgetReservationService).tryReserveChain(anyList(), any(BigDecimal.class));

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(),
                "prompt", "hello", "budget_reservation", 50));
            ToolResult result = handler.execute(toolCall, TENANT_ID, parentCredentials());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("ancestor agent " + PARENT_AGENT_ID);
            assertThat(result.error()).contains("parent_reservation");
            assertThat(result.error()).contains("BUDGET_EXHAUSTED");

            // Spawn must short-circuit BEFORE any LLM work, but still emit the
            // execution failure to the live fleet stream, conversation, and metrics.
            verify(agentLoopService, never()).execute(any(AgentLoopContext.class), any(StreamingCallback.class));
            verify(budgetReservationService, never()).settleReservationChain(anyList(), any(), any());
            verify(agentActivityPublisher).publishExecutionStarted(
                eq(AGENT_ID.toString()), anyString(), eq("gpt-4"), eq("SUB_AGENT"), isNull());
            verify(agentActivityPublisher).publishExecutionCompleted(
                eq(AGENT_ID.toString()), anyString(), eq("FAILED"), eq(0), eq(0), anyLong(), isNull());
            verify(conversationServiceClient).saveMessage(
                eq("conv-sub"), eq("assistant"), contains("BUDGET_EXHAUSTED"),
                isNull(), eq(TENANT_ID), any());

            ArgumentCaptor<com.apimarketplace.agent.client.dto.AgentObservabilityRequest> obsCaptor =
                ArgumentCaptor.forClass(com.apimarketplace.agent.client.dto.AgentObservabilityRequest.class);
            verify(observabilityService).recordFromRequest(obsCaptor.capture());
            var obs = obsCaptor.getValue();
            assertThat(obs.getStatus()).isEqualTo("FAILED");
            assertThat(obs.getStopReason()).isEqualTo("BUDGET_EXHAUSTED");
            assertThat(obs.getBudgetScope()).isEqualTo("parent_reservation");
            assertThat(obs.getCallerChain()).isNull();
            assertThat(obs.getMessages())
                .anySatisfy(message -> assertThat(message.getContent()).contains("BUDGET_EXHAUSTED"));
        }

        @Test
        @DisplayName("should refund reservation with ZERO actual when spawn fails after reserve")
        void refundsReservationOnEarlyFailureAfterReserve() {
            AgentEntity entity = createAgent();
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any()))
                .thenReturn("conv-sub");            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            // Reservation succeeds (default mock behaviour - no throw), so reservationHeld=true.
            // Then agentLoopService blows up BEFORE recordObservability runs → the outer catch
            // must release the chain (full refund: actual=0) before the exception propagates.
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenThrow(new RuntimeException("LLM call exploded"));

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(),
                "prompt", "hello", "budget_reservation", 25));

            try {
                handler.execute(toolCall, TENANT_ID, parentCredentials());
            } catch (Throwable t) {
                // Expected - the handler rethrows after refunding.
                assertThat(t).hasMessageContaining("LLM call exploded");
            }

            // Verify the reservation was actually taken. Use compareTo-based matchers -
            // the handler goes through toBigDecimal(25) which yields scale=1 ("25.0"), so
            // strict .equals() on a scale-0 constant would fail.
            verify(budgetReservationService).tryReserveChain(
                anyList(),
                argThat(v -> v != null && v.compareTo(new BigDecimal("25")) == 0));
            // Verify the refund path ran with actual=ZERO (full refund).
            ArgumentCaptor<List<UUID>> chainCaptor = ArgumentCaptor.forClass(List.class);
            verify(budgetReservationService).settleReservationChain(
                chainCaptor.capture(),
                argThat(v -> v != null && v.compareTo(new BigDecimal("25")) == 0),
                argThat(v -> v != null && v.compareTo(BigDecimal.ZERO) == 0));
            assertThat(chainCaptor.getValue()).containsExactly(PARENT_AGENT_ID);
            // observability was never reached → its settle hook did not fire, so this is the
            // only settle call - no double refund.
            verify(observabilityService, never()).recordFromRequest(any());
        }

        @Test
        @DisplayName("should NOT refund when spawn succeeds (ownership transferred to observability)")
        void doesNotDoubleRefundOnNormalCompletion() {
            setupAgentForSuccessfulExecution();

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(),
                "prompt", "hello", "budget_reservation", 10));
            ToolResult result = handler.execute(toolCall, TENANT_ID, parentCredentials());

            assertThat(result.success()).isTrue();
            // Reservation was taken …
            verify(budgetReservationService).tryReserveChain(anyList(), any(BigDecimal.class));
            // … ownership transferred to observability - settle is NOT called from the handler
            // (the dual-refund guard: reservationHeld=false before recordObservability runs).
            verify(budgetReservationService, never()).settleReservationChain(anyList(), any(), any());
            // …but the chain + amount DID travel in the observability request so the downstream
            // settle hook can refund once.
            ArgumentCaptor<com.apimarketplace.agent.client.dto.AgentObservabilityRequest> obsCaptor =
                ArgumentCaptor.forClass(com.apimarketplace.agent.client.dto.AgentObservabilityRequest.class);
            verify(observabilityService).recordFromRequest(obsCaptor.capture());
            assertThat(obsCaptor.getValue().getCallerChain()).containsExactly(PARENT_AGENT_ID);
            assertThat(obsCaptor.getValue().getReservedAmount()).isEqualByComparingTo(new BigDecimal("10"));
        }

        @Test
        @DisplayName("should skip reservation entirely when invoked from root (no parent id)")
        void skipsReservationOnRootInvocation() {
            setupAgentForSuccessfulExecution();

            // Default credentials have no __agentId__ → callerAgentEntityId is null →
            // the handler does not even compute a chain and tryReserveChain is never called.
            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(),
                "prompt", "hello", "budget_reservation", 10));
            ToolResult result = handler.execute(toolCall, TENANT_ID, defaultCredentials());

            assertThat(result.success()).isTrue();
            verify(budgetReservationService, never()).tryReserveChain(anyList(), any());
            verify(budgetReservationService, never()).settleReservationChain(anyList(), any(), any());
        }
    }

    @Nested
    @DisplayName("Bridge Routing")
    class BridgeRoutingTests {

        @Mock private SubAgentBridgeClient bridgeClientMock;

        @BeforeEach
        void injectBridge() {
            ReflectionTestUtils.setField(handler, "bridgeClient", bridgeClientMock);
        }

        private AgentEntity createBridgeAgent(String provider, String model) {
            AgentEntity entity = createAgent();
            entity.setModelProvider(provider);
            entity.setModelName(model);
            return entity;
        }

        @Test
        @DisplayName("should route to bridge when provider is claude-code")
        void shouldRouteToBridgeForClaudeCode() {
            AgentEntity entity = createBridgeAgent("claude-code", "claude-sonnet-4-6");
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentExecutionResponseDto bridgeResponse = new AgentExecutionResponseDto(
                true, "Bridge response", "Bridge response", List.of(), 2,
                Map.of("promptTokens", 100, "completionTokens", 50), null, 5000L,
                "claude-code", "claude-sonnet-4-6", List.of(), "COMPLETED",
                Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
            when(bridgeClientMock.execute(any(AgentExecutionRequestDto.class))).thenReturn(bridgeResponse);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "Do something"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, defaultCredentials());

            assertThat(result.success()).isTrue();
            assertThat(result.content()).contains("COMPLETED");
            assertThat(result.content()).contains("Bridge response");

            // Bridge client was called, NOT agentLoopService
            verify(bridgeClientMock).execute(any(AgentExecutionRequestDto.class));
            verify(agentLoopService, never()).execute(any(AgentLoopContext.class), any(StreamingCallback.class));
        }

        @Test
        @DisplayName("bridge sub-agent request carries enabledModules scoped by the child's toolsConfig - mode=none drops catalog (guards the bridge over-billing on the sub-agent path)")
        void bridgeRequestScopesEnabledModulesFromChildToolsConfig() {
            AgentEntity entity = createBridgeAgent("claude-code", "claude-sonnet-4-6");
            entity.setToolsConfig(Map.of("mode", "none")); // catalog/MCP blocked, internal kept
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);
            AgentExecutionResponseDto bridgeResponse = new AgentExecutionResponseDto(
                true, "ok", "ok", List.of(), 1, Map.of(), null, 10L,
                "claude-code", "claude-sonnet-4-6", List.of(), "COMPLETED",
                Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
            when(bridgeClientMock.execute(any(AgentExecutionRequestDto.class))).thenReturn(bridgeResponse);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "Do something"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            ArgumentCaptor<AgentExecutionRequestDto> captor = ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
            verify(bridgeClientMock).execute(captor.capture());
            // The bridge ignores the explicit toolMaps and rebuilds its MCP tool set from
            // enabledModules; a null here (the original async-bug shape) would advertise every
            // core schema. mode=none ⇒ internal modules kept, catalog dropped.
            assertThat(captor.getValue().enabledModules())
                .as("bridge sub-agent must forward the mode-scoped module set (catalog dropped) to the CLI")
                .contains("table")
                .doesNotContain("catalog");
        }

        @Test
        @DisplayName("should route to bridge when provider is codex")
        void shouldRouteToBridgeForCodex() {
            AgentEntity entity = createBridgeAgent("codex", "codex-mini-latest");
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentExecutionResponseDto bridgeResponse = new AgentExecutionResponseDto(
                true, "Codex done", "Codex done", List.of(), 1,
                Map.of(), null, 2000L, "codex", "codex-mini-latest",
                List.of(), "COMPLETED", Map.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), null);
            when(bridgeClientMock.execute(any())).thenReturn(bridgeResponse);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "Do something"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, defaultCredentials());

            assertThat(result.success()).isTrue();
            verify(bridgeClientMock).execute(any(AgentExecutionRequestDto.class));
            verify(agentLoopService, never()).execute(any(), any(StreamingCallback.class));
        }

        @Test
        @DisplayName("should route to bridge when provider is gemini-cli")
        void shouldRouteToBridgeForGeminiCli() {
            AgentEntity entity = createBridgeAgent("gemini-cli", "gemini-2.5-pro");
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentExecutionResponseDto bridgeResponse = new AgentExecutionResponseDto(
                true, "Gemini done", "Gemini done", List.of(), 1,
                Map.of(), null, 3000L, "gemini-cli", "gemini-2.5-pro",
                List.of(), "COMPLETED", Map.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), null);
            when(bridgeClientMock.execute(any())).thenReturn(bridgeResponse);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "Do something"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, defaultCredentials());

            assertThat(result.success()).isTrue();
            verify(bridgeClientMock).execute(any());
        }

        @Test
        @DisplayName("should route to bridge when provider is mistral-vibe")
        void shouldRouteToBridgeForMistralVibe() {
            AgentEntity entity = createBridgeAgent("mistral-vibe", "mistral-large");
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentExecutionResponseDto bridgeResponse = new AgentExecutionResponseDto(
                true, "Mistral done", "Mistral done", List.of(), 1,
                Map.of(), null, 1500L, "mistral-vibe", "mistral-large",
                List.of(), "COMPLETED", Map.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), null);
            when(bridgeClientMock.execute(any())).thenReturn(bridgeResponse);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "Do something"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, defaultCredentials());

            assertThat(result.success()).isTrue();
            verify(bridgeClientMock).execute(any());
        }

        @Test
        @DisplayName("should NOT route to bridge for API providers (openai, anthropic, etc.)")
        void shouldNotRouteToBridgeForApiProviders() {
            AgentEntity entity = createAgent(); // openai/gpt-4 by default
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentLoopResult loopResult = AgentLoopResult.success(
                CompletionResponse.text("Done"), List.of(), 1, null, 100, "openai", "gpt-4");
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(loopResult);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "Do something"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, defaultCredentials());

            assertThat(result.success()).isTrue();

            // AgentLoopService was called, NOT bridge
            verify(agentLoopService).execute(any(AgentLoopContext.class), any(StreamingCallback.class));
            verify(bridgeClientMock, never()).execute(any());
        }

        @Test
        @DisplayName("should handle bridge returning null (failure)")
        void shouldHandleBridgeNullResponse() {
            AgentEntity entity = createBridgeAgent("claude-code", "claude-sonnet-4-6");
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            // Bridge returns null (connection failure, timeout, etc.)
            when(bridgeClientMock.execute(any())).thenReturn(null);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "Do something"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, defaultCredentials());

            // The wrapper always succeeds, inner status in content indicates failure
            assertThat(result.content()).contains("FAILED");
            assertThat(result.content()).contains("no response from bridge server");
        }

        @Test
        @DisplayName("should handle bridge returning error response")
        void shouldHandleBridgeErrorResponse() {
            AgentEntity entity = createBridgeAgent("claude-code", "claude-sonnet-4-6");
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentExecutionResponseDto errorResponse = new AgentExecutionResponseDto(
                false, null, null, List.of(), 0, Map.of(),
                "CLI agent crashed", 1000L, "claude-code", "claude-sonnet-4-6",
                List.of(), "ERROR", Map.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), null);
            when(bridgeClientMock.execute(any())).thenReturn(errorResponse);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "Do something"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, defaultCredentials());

            assertThat(result.content()).contains("FAILED");
            verify(bridgeClientMock).execute(any());
        }

        @Test
        @DisplayName("should pass streaming context in bridge request")
        void shouldPassStreamingContextInBridgeRequest() {
            AgentEntity entity = createBridgeAgent("claude-code", "claude-sonnet-4-6");
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentExecutionResponseDto bridgeResponse = new AgentExecutionResponseDto(
                true, "Done", "Done", List.of(), 1, Map.of(), null, 1000L,
                "claude-code", "claude-sonnet-4-6", List.of(), "COMPLETED",
                Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
            ArgumentCaptor<AgentExecutionRequestDto> dtoCaptor = ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
            when(bridgeClientMock.execute(dtoCaptor.capture())).thenReturn(bridgeResponse);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "Do something"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            AgentExecutionRequestDto captured = dtoCaptor.getValue();
            assertThat(captured.provider()).isEqualTo("claude-code");
            assertThat(captured.model()).isEqualTo("claude-sonnet-4-6");
            assertThat(captured.prompt()).isEqualTo("Do something");
            assertThat(captured.conversationId()).isEqualTo("conv-1");
            assertThat(captured.streamingFormat()).isEqualTo("conversation");
            assertThat(captured.parentConversationId()).isEqualTo("parent-conv-123");
            assertThat(captured.subAgentName()).isEqualTo("Test Agent");
            assertThat(captured.tenantId()).isEqualTo(TENANT_ID);
            assertThat(captured.streamChannelId()).isNotNull();
        }

        @Test
        @DisplayName("should still record observability for bridge execution")
        void shouldRecordObservabilityForBridge() {
            AgentEntity entity = createBridgeAgent("claude-code", "claude-sonnet-4-6");
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentExecutionResponseDto bridgeResponse = new AgentExecutionResponseDto(
                true, "Done", "Done", List.of(), 3, Map.of(), null, 5000L,
                "claude-code", "claude-sonnet-4-6", List.of(), "COMPLETED",
                Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
            when(bridgeClientMock.execute(any())).thenReturn(bridgeResponse);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "Do something"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            // Observability should have been recorded
            verify(observabilityService).recordFromRequest(any());
        }

        @Test
        @DisplayName("should save assistant response to conversation for bridge execution")
        void shouldSaveConversationForBridge() {
            AgentEntity entity = createBridgeAgent("claude-code", "claude-sonnet-4-6");
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentExecutionResponseDto bridgeResponse = new AgentExecutionResponseDto(
                true, "Bridge result", "Bridge result", List.of(), 1, Map.of(),
                null, 2000L, "claude-code", "claude-sonnet-4-6",
                List.of(), "COMPLETED", Map.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), null);
            when(bridgeClientMock.execute(any())).thenReturn(bridgeResponse);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "Do something"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            // User prompt saved + assistant response saved
            verify(conversationServiceClient, times(2))
                .saveMessage(eq("conv-1"), anyString(), anyString(), any(), eq(TENANT_ID), any());
        }

        @Test
        @DisplayName("should work with cascade budget reservation via bridge")
        void shouldWorkWithCascadeReservationViaBridge() {
            UUID parentAgentId = UUID.randomUUID();

            AgentEntity entity = createBridgeAgent("claude-code", "claude-sonnet-4-6");
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentExecutionResponseDto bridgeResponse = new AgentExecutionResponseDto(
                true, "Bridge done", "Bridge done", List.of(), 1, Map.of(),
                null, 2000L, "claude-code", "claude-sonnet-4-6",
                List.of(), "COMPLETED", Map.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), null);
            when(bridgeClientMock.execute(any())).thenReturn(bridgeResponse);

            // Parent credentials with __agentId__
            Map<String, Object> creds = defaultCredentials();
            creds.put("__agentId__", parentAgentId.toString());

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(),
                "prompt", "hello", "budget_reservation", 10));
            ToolResult result = handler.execute(toolCall, TENANT_ID, creds);

            assertThat(result.success()).isTrue();
            assertThat(result.content()).contains("COMPLETED");

            // Budget reservation was still taken via bridge path
            verify(budgetReservationService).tryReserveChain(anyList(), any(BigDecimal.class));
            // Bridge was used, not agentLoopService
            verify(bridgeClientMock).execute(any());
            verify(agentLoopService, never()).execute(any(), any(StreamingCallback.class));
            // Observability recorded with chain
            verify(observabilityService).recordFromRequest(any());
        }

        @Test
        @DisplayName("should truncate long bridge responses")
        void shouldTruncateLongBridgeResponse() {
            AgentEntity entity = createBridgeAgent("claude-code", "claude-sonnet-4-6");
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            // Build a response longer than MAX_RESPONSE_LENGTH (50000 chars)
            String longContent = "X".repeat(60000);
            AgentExecutionResponseDto bridgeResponse = new AgentExecutionResponseDto(
                true, longContent, longContent, List.of(), 1, Map.of(),
                null, 2000L, "claude-code", "claude-sonnet-4-6",
                List.of(), "COMPLETED", Map.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), null);
            when(bridgeClientMock.execute(any())).thenReturn(bridgeResponse);

            // Allow saving full content tool result
            when(conversationServiceClient.saveToolResult(any(), any(), any(), any(), anyBoolean(), anyLong(), any(), any()))
                .thenReturn("tr-123");

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "Do something"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, defaultCredentials());

            assertThat(result.success()).isTrue();
            // Content in the result is truncated
            assertThat(result.content()).contains("truncated");
            assertThat(result.content()).contains("full_response_tool_call_id");
        }

        @Test
        @DisplayName("should pass agent budget to bridge request when budget is enabled")
        void shouldPassBudgetToBridgeRequest() {
            AgentEntity entity = createBridgeAgent("claude-code", "claude-sonnet-4-6");
            entity.setCreditBudget(new BigDecimal("50"));
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any())).thenReturn("conv-1");
            // Budget enabled: 50 total, 30 consumed
            BudgetState enabledBudget = new BudgetState(
                new BigDecimal("50"), new BigDecimal("30"), BigDecimal.ZERO, false);
            when(budgetResolver.resolveAndPersist(any(AgentEntity.class), any(Instant.class)))
                .thenReturn(enabledBudget);

            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentExecutionResponseDto bridgeResponse = new AgentExecutionResponseDto(
                true, "Done", "Done", List.of(), 1, Map.of(), null, 1000L,
                "claude-code", "claude-sonnet-4-6", List.of(), "COMPLETED",
                Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
            ArgumentCaptor<AgentExecutionRequestDto> dtoCaptor = ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
            when(bridgeClientMock.execute(dtoCaptor.capture())).thenReturn(bridgeResponse);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "Do something"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            AgentExecutionRequestDto captured = dtoCaptor.getValue();
            assertThat(captured.maxCreditBudget()).isEqualTo(50.0);
            assertThat(captured.creditsConsumedSoFar()).isEqualTo(30.0);
        }

        @Test
        @DisplayName("should NOT pass budget to bridge request when budget is disabled")
        void shouldNotPassBudgetWhenDisabled() {
            AgentEntity entity = createBridgeAgent("claude-code", "claude-sonnet-4-6");
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any())).thenReturn("conv-1");
            // Budget disabled (default in setUp)

            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            AgentExecutionResponseDto bridgeResponse = new AgentExecutionResponseDto(
                true, "Done", "Done", List.of(), 1, Map.of(), null, 1000L,
                "claude-code", "claude-sonnet-4-6", List.of(), "COMPLETED",
                Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
            ArgumentCaptor<AgentExecutionRequestDto> dtoCaptor = ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
            when(bridgeClientMock.execute(dtoCaptor.capture())).thenReturn(bridgeResponse);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "Do something"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            AgentExecutionRequestDto captured = dtoCaptor.getValue();
            assertThat(captured.maxCreditBudget()).isNull();
            assertThat(captured.creditsConsumedSoFar()).isNull();
        }

        @Test
        @DisplayName("should propagate budgetScope from bridge response to observability")
        void shouldPropagateBudgetScopeToObservability() {
            AgentEntity entity = createBridgeAgent("claude-code", "claude-sonnet-4-6");
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            // Bridge response with BUDGET_EXHAUSTED and budgetScope="agent"
            AgentExecutionResponseDto bridgeResponse = new AgentExecutionResponseDto(
                true, "Partial result", "Partial result", List.of(), 1,
                Map.of("promptTokens", 100, "completionTokens", 50),
                null, 3000L, "claude-code", "claude-sonnet-4-6",
                List.of(), "BUDGET_EXHAUSTED", Map.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), "agent");
            when(bridgeClientMock.execute(any())).thenReturn(bridgeResponse);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "Do something"));
            handler.execute(toolCall, TENANT_ID, defaultCredentials());

            ArgumentCaptor<com.apimarketplace.agent.client.dto.AgentObservabilityRequest> obsCaptor =
                ArgumentCaptor.forClass(com.apimarketplace.agent.client.dto.AgentObservabilityRequest.class);
            verify(observabilityService).recordFromRequest(obsCaptor.capture());

            var obs = obsCaptor.getValue();
            assertThat(obs.getBudgetScope()).isEqualTo("agent");
            assertThat(obs.getStopReason()).isEqualTo("BUDGET_EXHAUSTED");
        }

        @Test
        @DisplayName("should map bridge response usage info correctly")
        void shouldMapBridgeResponseUsageInfo() {
            AgentEntity entity = createBridgeAgent("claude-code", "claude-sonnet-4-6");
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any())).thenReturn("conv-1");
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);

            // Bridge response with detailed usage
            Map<String, Object> usage = Map.of(
                "promptTokens", 500, "completionTokens", 200, "totalTokens", 700,
                "cacheCreationInputTokens", 50, "cacheReadInputTokens", 30);
            AgentExecutionResponseDto bridgeResponse = new AgentExecutionResponseDto(
                true, "Done", "Done", List.of(), 2, usage,
                null, 3000L, "claude-code", "claude-sonnet-4-6",
                List.of(), "COMPLETED", Map.of(), List.of(),
                List.of(1500L, 1500L), List.of("tool_use", "end_turn"),
                List.of(), List.of(), null);
            when(bridgeClientMock.execute(any())).thenReturn(bridgeResponse);

            ToolCall toolCall = createToolCall(Map.of(
                "action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "Do something"));
            ToolResult result = handler.execute(toolCall, TENANT_ID, defaultCredentials());

            assertThat(result.success()).isTrue();
            // Verify observability request has the usage data
            ArgumentCaptor<com.apimarketplace.agent.client.dto.AgentObservabilityRequest> obsCaptor =
                ArgumentCaptor.forClass(com.apimarketplace.agent.client.dto.AgentObservabilityRequest.class);
            verify(observabilityService).recordFromRequest(obsCaptor.capture());

            var obs = obsCaptor.getValue();
            assertThat(obs.getPromptTokens()).isEqualTo(500);
            assertThat(obs.getCompletionTokens()).isEqualTo(200);
            assertThat(obs.getTotalTokens()).isEqualTo(700);
            assertThat(obs.getIterationCount()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("SubAgentBridgeClient.isBridgeProvider")
    class BridgeProviderDetectionTests {

        @Test
        @DisplayName("should detect all bridge providers")
        void shouldDetectBridgeProviders() {
            assertThat(SubAgentBridgeClient.isBridgeProvider("claude-code")).isTrue();
            assertThat(SubAgentBridgeClient.isBridgeProvider("codex")).isTrue();
            assertThat(SubAgentBridgeClient.isBridgeProvider("gemini-cli")).isTrue();
            assertThat(SubAgentBridgeClient.isBridgeProvider("mistral-vibe")).isTrue();
        }

        @Test
        @DisplayName("should be case-insensitive")
        void shouldBeCaseInsensitive() {
            assertThat(SubAgentBridgeClient.isBridgeProvider("Claude-Code")).isTrue();
            assertThat(SubAgentBridgeClient.isBridgeProvider("CLAUDE-CODE")).isTrue();
            assertThat(SubAgentBridgeClient.isBridgeProvider("CODEX")).isTrue();
            assertThat(SubAgentBridgeClient.isBridgeProvider("Gemini-CLI")).isTrue();
            assertThat(SubAgentBridgeClient.isBridgeProvider("MISTRAL-VIBE")).isTrue();
        }

        @Test
        @DisplayName("should reject API providers")
        void shouldRejectApiProviders() {
            assertThat(SubAgentBridgeClient.isBridgeProvider("openai")).isFalse();
            assertThat(SubAgentBridgeClient.isBridgeProvider("anthropic")).isFalse();
            assertThat(SubAgentBridgeClient.isBridgeProvider("google")).isFalse();
            assertThat(SubAgentBridgeClient.isBridgeProvider("mistral")).isFalse();
            assertThat(SubAgentBridgeClient.isBridgeProvider("deepseek")).isFalse();
        }

        @Test
        @DisplayName("should reject null and blank")
        void shouldRejectNullAndBlank() {
            assertThat(SubAgentBridgeClient.isBridgeProvider(null)).isFalse();
            assertThat(SubAgentBridgeClient.isBridgeProvider("")).isFalse();
            assertThat(SubAgentBridgeClient.isBridgeProvider("  ")).isFalse();
        }
    }

    // ==========================================================================
    // V100: per-agent unified maxPerResourcePerTurn override resolution
    // ==========================================================================
    @Nested
    @DisplayName("resolveMaxPerResourcePerTurn (V100 unified per-resource cap)")
    class ResolveMaxPerResourcePerTurn {

        private AgentEntity mockCaller(UUID id) {
            AgentEntity e = new AgentEntity();
            e.setId(id);
            e.setName("Caller");
            return e;
        }

        @Test
        @DisplayName("Returns YAML default (5) when credentials is null")
        void fallsBackWhenCredentialsNull() {
            assertThat(handler.resolveMaxPerResourcePerTurn(null)).isEqualTo(5);
        }

        @Test
        @DisplayName("Returns YAML default when no __agentId__ in credentials")
        void fallsBackWhenNoAgentId() {
            assertThat(handler.resolveMaxPerResourcePerTurn(Map.of("turnId", "turn-1")))
                .isEqualTo(5);
        }

        @Test
        @DisplayName("Returns per-agent override when entity has non-null positive value")
        void usesPerAgentOverride() {
            UUID callerId = UUID.randomUUID();
            AgentEntity caller = mockCaller(callerId);
            caller.setMaxPerResourcePerTurn(7);
            when(agentService.findById(callerId)).thenReturn(Optional.of(caller));

            assertThat(handler.resolveMaxPerResourcePerTurn(
                Map.of("__agentId__", callerId.toString())))
                .isEqualTo(7);
        }

        @Test
        @DisplayName("Falls back to default when entity override is null")
        void fallsBackWhenOverrideNull() {
            UUID callerId = UUID.randomUUID();
            AgentEntity caller = mockCaller(callerId);
            caller.setMaxPerResourcePerTurn(null);
            when(agentService.findById(callerId)).thenReturn(Optional.of(caller));

            assertThat(handler.resolveMaxPerResourcePerTurn(
                Map.of("__agentId__", callerId.toString())))
                .isEqualTo(5);
        }

        @Test
        @DisplayName("Falls back to default when agent not found")
        void fallsBackWhenAgentMissing() {
            UUID callerId = UUID.randomUUID();
            when(agentService.findById(callerId)).thenReturn(Optional.empty());

            assertThat(handler.resolveMaxPerResourcePerTurn(
                Map.of("__agentId__", callerId.toString())))
                .isEqualTo(5);
        }

        @Test
        @DisplayName("Falls back to default on lookup exception (soft-fail)")
        void softFailsOnException() {
            UUID callerId = UUID.randomUUID();
            when(agentService.findById(callerId)).thenThrow(new RuntimeException("DB down"));

            assertThat(handler.resolveMaxPerResourcePerTurn(
                Map.of("__agentId__", callerId.toString())))
                .isEqualTo(5);
        }

        @Test
        @DisplayName("Falls back on malformed __agentId__ string")
        void fallsBackOnMalformedUuid() {
            assertThat(handler.resolveMaxPerResourcePerTurn(
                Map.of("__agentId__", "not-a-uuid")))
                .isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("max_tokens / timeout clamping at the sub-agent build site")
    class BuildSiteClampWiring {

        /**
         * Runs a happy-path sub-agent execution and returns the AgentLoopContext handed to the loop.
         * {@code entityMaxTokens} may be null to exercise the platform-default fallback;
         * {@code timeoutParam} (the tool-call "timeout" arg) may be null to use the default.
         */
        private AgentLoopContext runAndCaptureContext(Integer entityMaxTokens, Integer timeoutParam) {
            AgentEntity entity = createAgent();
            entity.setMaxTokens(entityMaxTokens);
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any())).thenReturn("conv-sub");            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);
            when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
                .thenReturn(AgentLoopResult.success(
                    CompletionResponse.text("done"), List.of(), 1, null, 100, "openai", "gpt-4"));

            Map<String, Object> args = new HashMap<>();
            args.put("action", "execute");
            args.put("agent_id", AGENT_ID.toString());
            args.put("prompt", "go");
            if (timeoutParam != null) {
                args.put("timeout", timeoutParam);
            }
            handler.execute(createToolCall(args), TENANT_ID, defaultCredentials());

            ArgumentCaptor<AgentLoopContext> ctx = ArgumentCaptor.forClass(AgentLoopContext.class);
            verify(agentLoopService).execute(ctx.capture(), any(StreamingCallback.class));
            return ctx.getValue();
        }

        @Test
        @DisplayName("falls back to the safe 8192 floor when the catalog has no cap (modelCatalog absent)")
        void clampsToSafeFloorWhenCatalogMissing() {
            // modelCatalog is not injected here → resolveMaxOutputTokens is not consulted,
            // so the unknown-cap floor applies. A high entity budget must NOT reach the LLM.
            AgentLoopContext context = runAndCaptureContext(16000, null);
            assertThat(context.maxTokens()).isEqualTo(8192); // 16000 → floor (would have 400'd a low-cap model)
        }

        @Test
        @DisplayName("clamps the configured budget to the model's real catalog ceiling")
        void clampsToModelCeilingFromCatalog() {
            com.apimarketplace.agent.service.ModelCatalogService modelCatalog =
                mock(com.apimarketplace.agent.service.ModelCatalogService.class);
            when(modelCatalog.resolveMaxOutputTokens(anyString(), anyString())).thenReturn(4096);
            org.springframework.test.util.ReflectionTestUtils.setField(handler, "modelCatalog", modelCatalog);

            AgentLoopContext context = runAndCaptureContext(16000, null);
            assertThat(context.maxTokens()).isEqualTo(4096); // capped to the catalog ceiling, not the 8192 floor
        }

        @Test
        @DisplayName("null entity budget uses the platform default (16000), then clamps to the floor")
        void nullEntityBudgetUsesPlatformDefaultThenFloor() {
            // Pins the null-fallback: pre-fix this used a hardcoded 4096 (→ 4096); now it uses
            // agentDefaults.getMaxTokens()=16000, clamped to the 8192 floor (no catalog).
            AgentLoopContext context = runAndCaptureContext(null, null);
            assertThat(context.maxTokens()).isEqualTo(8192);
        }

        @Test
        @DisplayName("a sub-agent timeout above the old 3600 cap now survives up to 7200s")
        void timeoutSurvivesUpToRaisedCap() {
            // Pre-raise this was clamped to 3600; the cap is now 7200.
            AgentLoopContext context = runAndCaptureContext(4096, 7200);
            assertThat(context.executionTimeout()).isEqualTo(7200);
        }

        @Test
        @DisplayName("a sub-agent timeout above 7200 is still clamped to the 7200 ceiling")
        void timeoutClampedToRaisedCap() {
            AgentLoopContext context = runAndCaptureContext(4096, 9000);
            assertThat(context.executionTimeout()).isEqualTo(7200);
        }
    }
}
