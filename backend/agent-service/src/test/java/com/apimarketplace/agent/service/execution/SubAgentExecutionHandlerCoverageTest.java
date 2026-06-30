package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.config.AgentDefaultsConfig;
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
import com.apimarketplace.agent.service.budget.ModelCostCalculator;
import com.apimarketplace.agent.streaming.StreamingCallback;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.event.EventBus;
import com.apimarketplace.conversation.client.ConversationClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coverage for the result-shaping edges of {@link SubAgentExecutionHandler} that the main suite
 * leaves open: the 50 000-char truncation boundary, the silent full-content loss when the caller
 * has no parent conversation, and the FAILED / stop-reason shape surfaced to the parent agent when
 * the sub-agent's local loop fails.
 */
@DisplayName("SubAgentExecutionHandler - result shaping coverage")
@ExtendWith(MockitoExtension.class)
class SubAgentExecutionHandlerCoverageTest {

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
    private SubAgentExecutionHandler handler;

    private static final String TENANT_ID = "tenant-1";
    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final int MAX_RESPONSE_LENGTH = 50000;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        handler = new SubAgentExecutionHandler(
            agentService, agentLoopService, coreToolsCache,
            conversationServiceClient, observabilityService,
            conversationRedisStreamingCallback, redisTemplate, eventBus, objectMapper,
            budgetResolver, budgetReservationService, creditConsumptionClient, agentTaskService,
            guardChainFactory, agentActivityPublisher, new AgentDefaultsConfig());

        lenient().when(guardChainFactory.resolveCalculator(any(), any()))
            .thenReturn(new ModelCostCalculator(new BigDecimal("0.001"), new BigDecimal("0.003"), BigDecimal.ZERO));
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.increment(anyString())).thenReturn(1L);
        lenient().when(conversationServiceClient.getConversationMessages(any(), anyInt(), any()))
            .thenReturn(List.of());
        lenient().when(budgetResolver.resolveAndPersist(any(AgentEntity.class), any(Instant.class)))
            .thenReturn(BudgetState.disabled());
        lenient().when(creditConsumptionClient.fetchBalance(anyString()))
            .thenReturn(new BigDecimal("999999"));
    }

    private AgentEntity createAgent() {
        AgentEntity e = new AgentEntity();
        e.setId(AGENT_ID);
        e.setName("Test Agent");
        e.setModelProvider("openai");
        e.setModelName("gpt-4");
        e.setTemperature(new BigDecimal("0.7"));
        e.setMaxTokens(4096);
        e.setIsActive(true);
        e.setSystemPrompt("You are a helpful assistant.");
        return e;
    }

    /** Wires the happy-path collaborators so execute() reaches buildToolResult. */
    private void stubExecutablePath() {
        when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(createAgent()));
        when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
        when(coreToolsCache.getCoreTools()).thenReturn(List.of());
        var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
        when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(mockCallback);
    }

    private void stubLoopResult(AgentLoopResult result) {
        when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
            .thenReturn(result);
    }

    private Map<String, Object> credsWithConversation() {
        Map<String, Object> creds = new HashMap<>();
        creds.put("__agent_depth__", 0);
        creds.put("turnId", "turn-1");
        creds.put("conversationId", "parent-conv-123");
        return creds;
    }

    private Map<String, Object> credsWithoutConversation() {
        Map<String, Object> creds = new HashMap<>();
        creds.put("__agent_depth__", 0);
        creds.put("turnId", "turn-1");
        return creds;
    }

    private ToolCall executeCall() {
        return new ToolCall("tc-1", "agent",
            Map.of("action", "execute", "agent_id", AGENT_ID.toString(), "prompt", "go"), null);
    }

    private AgentLoopResult successText(String text) {
        return AgentLoopResult.success(CompletionResponse.text(text), List.of(), 1, null, 100, "openai", "gpt-4");
    }

    @Test
    @DisplayName("a response exactly at the cap is NOT truncated and no full copy is saved")
    void exactlyAtCapNotTruncated() {
        stubExecutablePath();
        stubLoopResult(successText("x".repeat(MAX_RESPONSE_LENGTH)));

        ToolResult result = handler.execute(executeCall(), TENANT_ID, credsWithConversation());

        assertThat(result.content()).doesNotContain("truncated");
        assertThat(result.content()).doesNotContain("full_response_tool_call_id");
        verify(conversationServiceClient, never())
            .saveToolResult(any(), any(), any(), any(), anyBoolean(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("one char over the cap is truncated and the full copy is saved with a retrieval hint")
    void oneOverCapTruncatedAndSaved() {
        stubExecutablePath();
        stubLoopResult(successText("x".repeat(MAX_RESPONSE_LENGTH + 1)));
        when(conversationServiceClient.saveToolResult(
                any(), any(), any(), any(), anyBoolean(), anyLong(), any(), any()))
            .thenReturn("result-id");

        ToolResult result = handler.execute(executeCall(), TENANT_ID, credsWithConversation());

        assertThat(result.content()).contains("truncated");
        assertThat(result.content()).contains("full_response_tool_call_id");
        verify(conversationServiceClient)
            .saveToolResult(any(), any(), any(), any(), anyBoolean(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("truncated but no parent conversation → full content is lost and no retrieval hint is offered")
    void truncatedWithoutParentConversationLosesFullContent() {
        stubExecutablePath();
        stubLoopResult(successText("x".repeat(MAX_RESPONSE_LENGTH + 5000)));

        ToolResult result = handler.execute(executeCall(), TENANT_ID, credsWithoutConversation());

        assertThat(result.content()).contains("truncated");
        // Documents the known limitation: with no parent conversation the full copy can't be
        // persisted, so the agent only ever sees the truncated form - and gets no recovery hint.
        assertThat(result.content()).doesNotContain("full_response_tool_call_id");
        verify(conversationServiceClient, never())
            .saveToolResult(any(), any(), any(), any(), anyBoolean(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("a failed local loop surfaces FAILED + the error text (wrapper still succeeds)")
    void failedLoopSurfacesFailedStatus() {
        stubExecutablePath();
        stubLoopResult(AgentLoopResult.failure("disk exploded", 100, "openai"));

        ToolResult result = handler.execute(executeCall(), TENANT_ID, credsWithConversation());

        // The tool-call wrapper always succeeds; the inner status carries the failure.
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("\"status\":\"FAILED\"");
        assertThat(result.content()).contains("disk exploded");
    }

    @Test
    @DisplayName("a timed-out local loop surfaces stop_reason=TIMEOUT to the parent")
    void timedOutLoopSurfacesTimeoutStopReason() {
        stubExecutablePath();
        stubLoopResult(AgentLoopResult.failure("deadline exceeded", 100, "openai", AgentStopReason.TIMEOUT));

        ToolResult result = handler.execute(executeCall(), TENANT_ID, credsWithConversation());

        assertThat(result.content()).contains("\"status\":\"FAILED\"");
        assertThat(result.content()).contains("\"stop_reason\":\"TIMEOUT\"");
    }

    @Nested
    @DisplayName("execution-timeout resolution + clamp")
    class TimeoutResolution {

        /** Drives a happy-path execute and returns the AgentLoopContext handed to the loop. */
        private AgentLoopContext captureContext(Integer entityTimeout, Integer callerTimeout) {
            AgentEntity entity = createAgent();
            entity.setExecutionTimeout(entityTimeout);
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            when(coreToolsCache.getCoreTools()).thenReturn(List.of());
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);
            stubLoopResult(successText("done"));

            Map<String, Object> args = new HashMap<>();
            args.put("action", "execute");
            args.put("agent_id", AGENT_ID.toString());
            args.put("prompt", "go");
            if (callerTimeout != null) args.put("timeout", callerTimeout);
            handler.execute(new ToolCall("tc-1", "agent", args, null), TENANT_ID, credsWithConversation());

            ArgumentCaptor<AgentLoopContext> ctx = ArgumentCaptor.forClass(AgentLoopContext.class);
            verify(agentLoopService).execute(ctx.capture(), any(StreamingCallback.class));
            return ctx.getValue();
        }

        @Test
        @DisplayName("a caller timeout below the floor is clamped up to the 10s minimum")
        void callerTimeoutBelowFloorClampedToMin() {
            assertThat(captureContext(null, 5).executionTimeout()).isEqualTo(10);
        }

        @Test
        @DisplayName("a null caller timeout falls back to the agent's configured timeout")
        void nullCallerUsesEntityTimeout() {
            assertThat(captureContext(1800, null).executionTimeout()).isEqualTo(1800);
        }

        @Test
        @DisplayName("a null caller AND null entity timeout falls back to the 600s default")
        void nullCallerNullEntityUsesDefault() {
            assertThat(captureContext(null, null).executionTimeout()).isEqualTo(600);
        }
    }

    @Nested
    @DisplayName("inactivity watchdog credential injection")
    class InactivityCredentialInjection {

        /** Drives a happy-path execute and returns the credentials map handed to the loop. */
        private Map<String, Object> captureCredentials(Integer entityInactivity) {
            AgentEntity entity = createAgent();
            entity.setInactivityTimeout(entityInactivity);
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(entity));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any())).thenReturn("conv-1");
            when(coreToolsCache.getCoreTools()).thenReturn(List.of());
            var mockCallback = mock(ConversationRedisStreamingCallback.ConversationCallback.class);
            when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockCallback);
            stubLoopResult(successText("done"));

            Map<String, Object> args = new HashMap<>();
            args.put("action", "execute");
            args.put("agent_id", AGENT_ID.toString());
            args.put("prompt", "go");
            handler.execute(new ToolCall("tc-1", "agent", args, null), TENANT_ID, credsWithConversation());

            ArgumentCaptor<AgentLoopContext> ctx = ArgumentCaptor.forClass(AgentLoopContext.class);
            verify(agentLoopService).execute(ctx.capture(), any(StreamingCallback.class));
            return ctx.getValue().credentials();
        }

        @Test
        @DisplayName("an agent with inactivity_timeout set carries it as __inactivityTimeoutSeconds__")
        void entityInactivityIsCarried() {
            assertThat(captureCredentials(300))
                .as("the sub-agent path must carry the entity's per-agent inactivity window on credentials")
                .containsEntry("__inactivityTimeoutSeconds__", 300);
        }

        @Test
        @DisplayName("an agent with no inactivity_timeout OMITS the credential (the 5-min default applies)")
        void nullEntityOmitsCredential() {
            assertThat(captureCredentials(null)).doesNotContainKey("__inactivityTimeoutSeconds__");
        }

        @Test
        @DisplayName("an agent with inactivity_timeout=0 (disabled) carries 0 VERBATIM, not coerced to the default")
        void zeroEntityInactivityCarriedVerbatim() {
            assertThat(captureCredentials(0))
                .as("0 = disabled must reach the sub-agent credentials verbatim (downstream resolveInactivityWindowMs maps 0 -> watchdog disabled); coercing or dropping it would silently re-enable the 5-min watchdog")
                .containsEntry("__inactivityTimeoutSeconds__", 0);
        }
    }
}
