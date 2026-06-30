package com.apimarketplace.agent.service;

import com.apimarketplace.agent.client.dto.AgentObservabilityRequest;
import com.apimarketplace.agent.domain.AgentExecutionEntity;
import com.apimarketplace.agent.repository.AgentExecutionIterationRepository;
import com.apimarketplace.agent.repository.AgentExecutionMessageRepository;
import com.apimarketplace.agent.repository.AgentExecutionRepository;
import com.apimarketplace.agent.repository.AgentExecutionToolCallRepository;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.service.dto.ChatAgentObservabilityRequest;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.common.storage.service.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

/**
 * CREDIT CONSUMPTION AUDIT - verifies that every LLM execution path
 * correctly triggers credit consumption and that no path allows free execution.
 *
 * Tests cover:
 * - Workflow agent execution (recordFromRequest)
 * - Chat agent execution (recordFromChat)
 * - Sub-agent execution
 * - Classify / Guardrail (totalTokens fallback)
 * - Zero-token edge case (NO consumption - the bypass risk)
 * - Credit failure swallowed without blocking observability
 * - Token fallback accuracy for classify/guardrail
 * - Execution entity credits tracking
 */
@DisplayName("Credit Consumption Audit - all execution paths")
@ExtendWith(MockitoExtension.class)
class CreditConsumptionAuditTest {

    @Mock private AgentExecutionRepository executionRepository;
    @Mock private AgentExecutionIterationRepository iterationRepository;
    @Mock private AgentExecutionMessageRepository messageRepository;
    @Mock private AgentExecutionToolCallRepository toolCallRepository;
    @Mock private StorageService storageService;
    @Mock private CreditConsumptionClient creditClient;
    @Mock private AgentMetricsAggregationService aggregationService;
    @Mock private StorageBreakdownService breakdownService;
    @Mock private AgentRepository agentRepository;
    @Mock private com.apimarketplace.agent.metrics.AgentPrometheusMetrics prometheusMetrics;
    @Mock private com.apimarketplace.agent.service.budget.BudgetReservationService budgetReservationService;

    @Captor private ArgumentCaptor<AgentExecutionEntity> execCaptor;

    private AgentObservabilityService service;

    @BeforeEach
    void setUp() {
        service = new AgentObservabilityService(
                executionRepository, iterationRepository, messageRepository,
                toolCallRepository, storageService, creditClient,
                aggregationService, breakdownService, agentRepository,
                prometheusMetrics, budgetReservationService
        );
    }

    // =====================================================================
    // Helper - build base request with standard tokens
    // =====================================================================

    private AgentObservabilityRequest buildRequest(String agentType, long prompt, long completion, long total) {
        AgentObservabilityRequest req = new AgentObservabilityRequest();
        req.setTenantId("tenant-42");
        req.setAgentEntityId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        req.setAgentType(agentType);
        req.setNodeId("agent:test_agent");
        req.setRunId("run-audit-1");
        req.setProvider("anthropic");
        req.setModel("claude-3-sonnet");
        req.setStatus("COMPLETED");
        req.setDurationMs(3000L);
        req.setPromptTokens(prompt);
        req.setCompletionTokens(completion);
        req.setTotalTokens(total);
        req.setIterationCount(1);
        req.setTotalToolCalls(0);
        return req;
    }

    private AgentObservabilityRequest buildAgentRequest() {
        return buildRequest("agent", 1000, 500, 1500);
    }

    // =====================================================================
    // 1. ZERO TOKENS - THE BYPASS RISK
    // =====================================================================

    @Nested
    @DisplayName("CRITICAL: Zero-token execution - consumption bypass")
    class ZeroTokenBypass {

        @Test
        @DisplayName("zero promptTokens + zero completionTokens + zero totalTokens = credits STILL consumed (platform cost)")
        void zeroTokensStillConsumes() {
            AgentObservabilityRequest req = buildRequest("agent", 0, 0, 0);

            service.recordFromRequest(req);

            // FIX: Even with 0 tokens, the LLM API call was made → always consume.
            // consumeCredits is called with 0/0 tokens - auth-service charges fixed cost.
            verify(creditClient).consumeCredits(
                    eq("tenant-42"),
                    eq("AGENT_EXECUTION"),
                    any(),
                    eq("anthropic"),
                    eq("claude-3-sonnet"),
                    eq(0),
                    eq(0), any(com.apimarketplace.common.credit.LlmCacheTokens.class)
            );
        }

        @Test
        @DisplayName("observability data is STILL recorded even when tokens are zero")
        void observabilityRecordedEvenWithZeroTokens() {
            AgentObservabilityRequest req = buildRequest("agent", 0, 0, 0);

            UUID result = service.recordFromRequest(req);

            // Execution entity is saved (observability is always recorded)
            verify(executionRepository, atLeastOnce()).save(any(AgentExecutionEntity.class));
        }

        @Test
        @DisplayName("zero prompt + zero completion BUT positive total => 50/50 split")
        void positiveOnlyTotalTokensTriggerConsumption() {
            // This is the classify/guardrail pattern: only totalTokens is set
            AgentObservabilityRequest req = buildRequest("classify", 0, 0, 800);

            service.recordFromRequest(req);

            // FIX: totalTokens split 50/50 instead of all-as-input
            verify(creditClient).consumeCredits(
                    eq("tenant-42"),
                    eq("CLASSIFY_EXECUTION"),
                    any(),
                    eq("anthropic"),
                    eq("claude-3-sonnet"),
                    eq(400),  // 800/2 = 400 prompt
                    eq(400), // 800 - 400 = 400 completion
            any(com.apimarketplace.common.credit.LlmCacheTokens.class)
            );
        }
    }

    // =====================================================================
    // 2. CLASSIFY / GUARDRAIL - TOKEN FALLBACK ACCURACY
    // =====================================================================

    @Nested
    @DisplayName("Classify/Guardrail token fallback - all tokens counted as input (cheaper rate)")
    class ClassifyGuardrailFallback {

        @Test
        @DisplayName("classify: totalTokens=500, prompt=0, completion=0 → consumed as 250 prompt + 250 completion (50/50 split)")
        void classifyFallbackSplitFiftyFifty() {
            AgentObservabilityRequest req = buildRequest("classify", 0, 0, 500);

            service.recordFromRequest(req);

            verify(creditClient).consumeCredits(
                    any(), eq("CLASSIFY_EXECUTION"), any(), any(), any(),
                    eq(250), eq(250), any(com.apimarketplace.common.credit.LlmCacheTokens.class)
            );
        }

        @Test
        @DisplayName("guardrail: totalTokens=300, prompt=0, completion=0 → consumed as 150 prompt + 150 completion (50/50 split)")
        void guardrailFallbackSplitFiftyFifty() {
            AgentObservabilityRequest req = buildRequest("guardrail", 0, 0, 300);

            service.recordFromRequest(req);

            verify(creditClient).consumeCredits(
                    any(), eq("GUARDRAIL_EXECUTION"), any(), any(), any(),
                    eq(150), eq(150), any(com.apimarketplace.common.credit.LlmCacheTokens.class)
            );
        }

        @Test
        @DisplayName("classify with actual prompt/completion breakdown → NO fallback, uses real values")
        void classifyWithRealBreakdownNoFallback() {
            AgentObservabilityRequest req = buildRequest("classify", 200, 100, 300);

            service.recordFromRequest(req);

            // Prompt and completion are non-zero, so no fallback applies
            verify(creditClient).consumeCredits(
                    any(), eq("CLASSIFY_EXECUTION"), any(), any(), any(),
                    eq(200), eq(100), any(com.apimarketplace.common.credit.LlmCacheTokens.class)
            );
        }

        @Test
        @DisplayName("guardrail with only completion tokens set → NO fallback (completionTokens > 0)")
        void guardrailWithOnlyCompletionNoFallback() {
            AgentObservabilityRequest req = buildRequest("guardrail", 0, 150, 150);

            service.recordFromRequest(req);

            // promptTok=0, completionTok=150 → condition is promptTok==0 && completionTok==0 → false
            // So no fallback: uses real prompt=0, completion=150
            verify(creditClient).consumeCredits(
                    any(), any(), any(), any(), any(),
                    eq(0), eq(150), any(com.apimarketplace.common.credit.LlmCacheTokens.class)
            );
        }
    }

    // =====================================================================
    // 3. CREDIT FAILURE - SWALLOWED, NOT BLOCKING
    // =====================================================================

    @Nested
    @DisplayName("Credit failure isolation - consumption failure must not block observability")
    class CreditFailureIsolation {

        @Test
        @DisplayName("creditClient.consumeCredits throws RuntimeException → recordFromRequest returns normally")
        void consumeCreditsThrowsDoesNotBlockObservability() {
            AgentObservabilityRequest req = buildAgentRequest();
            when(creditClient.consumeCredits(any(), any(), any(), any(), any(), anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class)))
                    .thenThrow(new RuntimeException("auth-service down"));

            // Should NOT throw - credit failure is caught and logged
            UUID result = service.recordFromRequest(req);

            // Observability is still recorded
            verify(executionRepository, atLeastOnce()).save(any(AgentExecutionEntity.class));
        }

        @Test
        @DisplayName("creditClient.consumeCredits returns error map → no exception, no creditsConsumed tracked")
        void consumeCreditsReturnsErrorMap() {
            AgentObservabilityRequest req = buildAgentRequest();
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("success", false);
            errorResult.put("error", "402 Insufficient credits");
            when(creditClient.consumeCredits(any(), any(), any(), any(), any(), anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class)))
                    .thenReturn(errorResult);

            service.recordFromRequest(req);

            // No creditsUsed key → no increment
            verify(agentRepository, never()).incrementCreditsConsumed(any(), any());
        }

        @Test
        @DisplayName("creditClient returns null → no crash, no creditsConsumed tracked")
        void consumeCreditsReturnsNull() {
            AgentObservabilityRequest req = buildAgentRequest();
            when(creditClient.consumeCredits(any(), any(), any(), any(), any(), anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class)))
                    .thenReturn(null);

            service.recordFromRequest(req);

            verify(agentRepository, never()).incrementCreditsConsumed(any(), any());
        }

        @Test
        @DisplayName("agentRepository.incrementCreditsConsumed throws → swallowed, no crash")
        void incrementThrowsIsSwallowed() {
            AgentObservabilityRequest req = buildAgentRequest();
            Map<String, Object> creditResult = new HashMap<>();
            creditResult.put("creditsUsed", 2.5);
            when(creditClient.consumeCredits(any(), any(), any(), any(), any(), anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class)))
                    .thenReturn(creditResult);
            doThrow(new RuntimeException("DB constraint violation"))
                    .when(agentRepository).incrementCreditsConsumed(any(), any());

            assertThatCode(() -> service.recordFromRequest(req))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("executionRepository.updateCreditsConsumed throws → swallowed, no crash")
        void updateCreditsOnExecutionThrowsIsSwallowed() {
            AgentObservabilityRequest req = buildAgentRequest();
            Map<String, Object> creditResult = new HashMap<>();
            creditResult.put("creditsUsed", 1.0);
            lenient().when(creditClient.consumeCredits(any(), any(), any(), any(), any(), anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class)))
                    .thenReturn(creditResult);
            lenient().doThrow(new RuntimeException("stale entity"))
                    .when(executionRepository).updateCreditsConsumed(any(), any());

            assertThatCode(() -> service.recordFromRequest(req))
                    .doesNotThrowAnyException();
        }
    }

    // =====================================================================
    // 4. SUB-AGENT CONSUMPTION
    // =====================================================================

    @Nested
    @DisplayName("Sub-agent consumption - must be tracked identically to parent agent")
    class SubAgentConsumption {

        @Test
        @DisplayName("sub-agent execution should consume credits with AGENT_EXECUTION source type")
        void subAgentConsumesCredits() {
            AgentObservabilityRequest req = buildAgentRequest();
            req.setCallerAgentId(UUID.randomUUID());
            req.setNestingDepth(2);
            req.setSource("SUB_AGENT");

            service.recordFromRequest(req);

            verify(creditClient).consumeCredits(
                    eq("tenant-42"),
                    eq("AGENT_EXECUTION"),
                    any(),
                    eq("anthropic"),
                    eq("claude-3-sonnet"),
                    eq(1000),
                    eq(500), any(com.apimarketplace.common.credit.LlmCacheTokens.class)
            );
        }

        @Test
        @DisplayName("sub-agent credits should be tracked on the agent entity (not the caller)")
        void subAgentCreditsTrackedOnAgent() {
            AgentObservabilityRequest req = buildAgentRequest();
            req.setCallerAgentId(UUID.randomUUID());
            req.setNestingDepth(1);

            Map<String, Object> creditResult = new HashMap<>();
            creditResult.put("creditsUsed", 3.0);
            when(creditClient.consumeCredits(any(), any(), any(), any(), any(), anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class)))
                    .thenReturn(creditResult);

            service.recordFromRequest(req);

            // Credits tracked on agentEntityId, not callerAgentId
            verify(agentRepository).incrementCreditsConsumed(
                    eq(UUID.fromString("00000000-0000-0000-0000-000000000001")),
                    eq(BigDecimal.valueOf(3.0))
            );
        }

        @Test
        @DisplayName("deeply nested sub-agent (depth=5) should still consume credits normally")
        void deeplyNestedSubAgentConsumesCredits() {
            AgentObservabilityRequest req = buildAgentRequest();
            req.setCallerAgentId(UUID.randomUUID());
            req.setNestingDepth(5);

            service.recordFromRequest(req);

            verify(creditClient).consumeCredits(any(), any(), any(), any(), any(), anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class));
        }
    }

    // =====================================================================
    // 5. CHAT CONVERSATION CONSUMPTION
    // =====================================================================

    @Nested
    @DisplayName("Chat conversation consumption - recordFromChat path")
    class ChatConsumption {

        private ChatAgentObservabilityRequest buildChatRequest(int promptTokens, int completionTokens, int totalTokens) {
            return new ChatAgentObservabilityRequest(
                    "agent-id-1",            // agentEntityId
                    "anthropic",             // provider
                    "claude-3-sonnet",       // model
                    0.7,                     // temperature
                    4096,                    // maxTokens
                    10,                      // maxIterations
                    true,                    // success
                    "end_turn",              // stopReason
                    null,                    // budgetScope
                    null,                    // errorMessage
                    2000L,                   // durationMs
                    2,                       // iterationCount
                    1,                       // totalToolCalls
                    1,                       // successfulToolCalls
                    0,                       // failedToolCalls
                    5,                       // messageCount
                    promptTokens,            // totalPromptTokens
                    completionTokens,        // totalCompletionTokens
                    totalTokens,             // totalTokens
                    null,                    // totalCacheCreationTokens
                    null,                    // totalCacheReadTokens
                    null,                    // totalCachedTokens
                    null,                    // totalReasoningTokens
                    null,                    // toolSequence
                    null,                    // distinctTools
                    false,                   // loopDetected
                    null,                    // loopType
                    null,                    // loopToolName
                    null,                    // systemPrompt
                    null,                    // userPrompt
                    "conv-123",              // conversationId
                    null,                    // source
                    null,                    // taskId
                    null,                    // executionId
                    null,                    // toolResults
                    null,                    // conversationHistory
                    null,                    // usagePerIteration
                    null,                    // iterationDurations
                    null,                    // finishReasonsPerIteration
                    null                     // toolCallsPerIteration
            );
        }

        @Test
        @DisplayName("chat agent should consume credits with CHAT_CONVERSATION source type")
        void chatConsumesWithCorrectSourceType() {
            ChatAgentObservabilityRequest chatReq = buildChatRequest(800, 400, 1200);

            service.recordFromChat("tenant-42", "org-test", chatReq);

            verify(creditClient).consumeCredits(
                    eq("tenant-42"),
                    eq("CHAT_CONVERSATION"),
                    any(),
                    eq("anthropic"),
                    eq("claude-3-sonnet"),
                    eq(800),
                    eq(400), any(com.apimarketplace.common.credit.LlmCacheTokens.class)
            );
        }

        @Test
        @DisplayName("chat with zero tokens STILL consumes credits (platform cost)")
        void chatZeroTokensStillConsumes() {
            ChatAgentObservabilityRequest chatReq = buildChatRequest(0, 0, 0);

            service.recordFromChat("tenant-42", "org-test", chatReq);

            // FIX: Always consume - even 0-token chats incur platform cost
            verify(creditClient).consumeCredits(
                    eq("tenant-42"),
                    eq("CHAT_CONVERSATION"),
                    any(),
                    eq("anthropic"),
                    eq("claude-3-sonnet"),
                    eq(0),
                    eq(0), any(com.apimarketplace.common.credit.LlmCacheTokens.class)
            );
        }

        @Test
        @DisplayName("chat credit failure should NOT block observability recording")
        void chatCreditFailureDoesNotBlockObservability() {
            ChatAgentObservabilityRequest chatReq = buildChatRequest(500, 200, 700);

            when(creditClient.consumeCredits(any(), any(), any(), any(), any(), anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class)))
                    .thenThrow(new RuntimeException("auth-service unreachable"));

            assertThatCode(() -> service.recordFromChat("tenant-42", "org-test", chatReq))
                    .doesNotThrowAnyException();

            // Observability is still recorded
            verify(executionRepository, atLeastOnce()).save(any(AgentExecutionEntity.class));
        }
    }

    // =====================================================================
    // 6. SOURCE TYPE RESOLUTION - exhaustive check
    // =====================================================================

    @Nested
    @DisplayName("Source type resolution - every agent type maps correctly")
    class SourceTypeResolution {

        @Test
        @DisplayName("agentType=null → AGENT_EXECUTION")
        void nullTypeIsAgent() {
            AgentObservabilityRequest req = buildAgentRequest();
            req.setAgentType(null);

            service.recordFromRequest(req);

            verify(creditClient).consumeCredits(any(), eq("AGENT_EXECUTION"), any(), any(), any(), anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class));
        }

        @Test
        @DisplayName("agentType='agent' → AGENT_EXECUTION")
        void agentTypeIsAgent() {
            service.recordFromRequest(buildAgentRequest());
            verify(creditClient).consumeCredits(any(), eq("AGENT_EXECUTION"), any(), any(), any(), anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class));
        }

        @Test
        @DisplayName("agentType='classify' → CLASSIFY_EXECUTION")
        void classifyTypeIsClassify() {
            AgentObservabilityRequest req = buildRequest("classify", 200, 100, 300);
            service.recordFromRequest(req);
            verify(creditClient).consumeCredits(any(), eq("CLASSIFY_EXECUTION"), any(), any(), any(), anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class));
        }

        @Test
        @DisplayName("agentType='guardrail' → GUARDRAIL_EXECUTION")
        void guardrailTypeIsGuardrail() {
            AgentObservabilityRequest req = buildRequest("guardrail", 200, 100, 300);
            service.recordFromRequest(req);
            verify(creditClient).consumeCredits(any(), eq("GUARDRAIL_EXECUTION"), any(), any(), any(), anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class));
        }

        @Test
        @DisplayName("agentType='CLASSIFY' (uppercase) → CLASSIFY_EXECUTION (case-insensitive)")
        void uppercaseClassifyIsClassify() {
            AgentObservabilityRequest req = buildRequest("CLASSIFY", 200, 100, 300);
            service.recordFromRequest(req);
            verify(creditClient).consumeCredits(any(), eq("CLASSIFY_EXECUTION"), any(), any(), any(), anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class));
        }

        @Test
        @DisplayName("agentType='unknown_type' → AGENT_EXECUTION (default)")
        void unknownTypeDefaultsToAgent() {
            AgentObservabilityRequest req = buildRequest("unknown_type", 200, 100, 300);
            service.recordFromRequest(req);
            verify(creditClient).consumeCredits(any(), eq("AGENT_EXECUTION"), any(), any(), any(), anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class));
        }
    }

    // =====================================================================
    // 7. CREDITS TRACKING ON EXECUTION AND AGENT ENTITY
    // =====================================================================

    @Nested
    @DisplayName("Credits tracking on execution record and agent entity")
    class CreditsTracking {

        @Test
        @DisplayName("should update creditsConsumed on execution record when creditsUsed > 0")
        void shouldUpdateExecutionCredits() {
            AgentObservabilityRequest req = buildAgentRequest();
            Map<String, Object> creditResult = new HashMap<>();
            creditResult.put("creditsUsed", 4.25);
            when(creditClient.consumeCredits(any(), any(), any(), any(), any(), anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class)))
                    .thenReturn(creditResult);

            // Make save set an ID on the entity so executionId is non-null
            UUID fakeExecId = UUID.randomUUID();
            when(executionRepository.save(any(AgentExecutionEntity.class))).thenAnswer(invocation -> {
                AgentExecutionEntity entity = invocation.getArgument(0);
                if (entity.getId() == null) {
                    entity.setId(fakeExecId);
                }
                return entity;
            });

            service.recordFromRequest(req);

            verify(executionRepository).updateCreditsConsumed(eq(fakeExecId), eq(BigDecimal.valueOf(4.25)));
        }

        @Test
        @DisplayName("should increment creditsConsumed on agent entity when creditsUsed > 0")
        void shouldIncrementAgentEntityCredits() {
            AgentObservabilityRequest req = buildAgentRequest();
            Map<String, Object> creditResult = new HashMap<>();
            creditResult.put("creditsUsed", 7.5);
            when(creditClient.consumeCredits(any(), any(), any(), any(), any(), anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class)))
                    .thenReturn(creditResult);

            service.recordFromRequest(req);

            verify(agentRepository).incrementCreditsConsumed(
                    eq(UUID.fromString("00000000-0000-0000-0000-000000000001")),
                    eq(BigDecimal.valueOf(7.5))
            );
        }

        @Test
        @DisplayName("creditsUsed=0 → no execution update, no agent increment")
        void zeroCreditsNoTracking() {
            AgentObservabilityRequest req = buildAgentRequest();
            Map<String, Object> creditResult = new HashMap<>();
            creditResult.put("creditsUsed", 0);
            when(creditClient.consumeCredits(any(), any(), any(), any(), any(), anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class)))
                    .thenReturn(creditResult);

            service.recordFromRequest(req);

            verify(executionRepository, never()).updateCreditsConsumed(any(), any());
            verify(agentRepository, never()).incrementCreditsConsumed(any(), any());
        }

        @Test
        @DisplayName("creditsUsed missing from result map → no tracking")
        void missingCreditsUsedKeyNoTracking() {
            AgentObservabilityRequest req = buildAgentRequest();
            Map<String, Object> creditResult = new HashMap<>();
            creditResult.put("success", true);
            // No "creditsUsed" key
            when(creditClient.consumeCredits(any(), any(), any(), any(), any(), anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class)))
                    .thenReturn(creditResult);

            service.recordFromRequest(req);

            verify(executionRepository, never()).updateCreditsConsumed(any(), any());
            verify(agentRepository, never()).incrementCreditsConsumed(any(), any());
        }

        @Test
        @DisplayName("agentEntityId=null → credits consumed but NOT tracked on agent entity")
        void noAgentEntityNoIncrement() {
            AgentObservabilityRequest req = buildAgentRequest();
            req.setAgentEntityId(null);
            Map<String, Object> creditResult = new HashMap<>();
            creditResult.put("creditsUsed", 2.0);
            when(creditClient.consumeCredits(any(), any(), any(), any(), any(), anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class)))
                    .thenReturn(creditResult);

            service.recordFromRequest(req);

            // Credits consumed
            verify(creditClient).consumeCredits(any(), any(), any(), any(), any(), anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class));
            // But not tracked on agent entity
            verify(agentRepository, never()).incrementCreditsConsumed(any(), any());
        }
    }

    // =====================================================================
    // 8. OBSERVABILITY FAILURE DOES NOT PREVENT CREDIT CONSUMPTION
    // =====================================================================

    @Nested
    @DisplayName("Observability recording failure → credits still consumed")
    class ObservabilityFailureStillConsumes {

        @Test
        @DisplayName("when doRecordFromRequest throws, credit consumption still attempted")
        void creditConsumedEvenWhenRecordingFails() {
            AgentObservabilityRequest req = buildAgentRequest();

            // Make the execution save throw to simulate observability failure
            when(executionRepository.save(any())).thenThrow(new RuntimeException("DB deadlock"));

            service.recordFromRequest(req);

            // Credit consumption should still be attempted because:
            // 1. doRecordFromRequest is wrapped in try/catch (line 98-101)
            // 2. Credit consumption happens AFTER the try/catch (line 104+)
            verify(creditClient).consumeCredits(
                    eq("tenant-42"),
                    eq("AGENT_EXECUTION"),
                    any(),
                    eq("anthropic"),
                    eq("claude-3-sonnet"),
                    eq(1000),
                    eq(500), any(com.apimarketplace.common.credit.LlmCacheTokens.class)
            );
        }
    }

    // =====================================================================
    // 9. TENANT ID PROPAGATION
    // =====================================================================

    @Nested
    @DisplayName("Tenant ID propagation in credit consumption")
    class TenantIdPropagation {

        @Test
        @DisplayName("tenantId from request is forwarded to creditClient")
        void tenantIdForwarded() {
            AgentObservabilityRequest req = buildAgentRequest();
            req.setTenantId("tenant-999");

            service.recordFromRequest(req);

            verify(creditClient).consumeCredits(
                    eq("tenant-999"), any(), any(), any(), any(), anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class)
            );
        }

        @Test
        @DisplayName("null tenantId → creditClient.consumeCredits called with null userId (will return error)")
        void nullTenantIdPassedThrough() {
            AgentObservabilityRequest req = buildAgentRequest();
            req.setTenantId(null);

            service.recordFromRequest(req);

            // consumeCredits is still called - it handles null userId internally
            verify(creditClient).consumeCredits(
                    isNull(), any(), any(), any(), any(), anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class)
            );
        }
    }

    // =====================================================================
    // 10. EXECUTION SOURCE ID USED FOR CREDIT TRACKING
    // =====================================================================

    @Nested
    @DisplayName("Source ID for credit tracking - uses executionId or fallback to nodeId")
    class SourceIdTracking {

        @Test
        @DisplayName("when execution save does not set ID, sourceId falls back to nodeId")
        void sourceIdFallsBackToNodeIdWhenNoId() {
            AgentObservabilityRequest req = buildAgentRequest();
            // Default mock: save returns null entity ID → executionId stays null at credit call

            service.recordFromRequest(req);

            // With default mock (no ID set), executionId is null → falls back to nodeId
            verify(creditClient).consumeCredits(
                    any(), any(),
                    eq("agent:test_agent"),
                    any(), any(), anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class)
            );
        }

        @Test
        @DisplayName("when observability recording fails, sourceId falls back to nodeId")
        void sourceIdFallsBackToNodeIdOnRecordingFailure() {
            AgentObservabilityRequest req = buildAgentRequest();
            when(executionRepository.save(any())).thenThrow(new RuntimeException("DB error"));

            service.recordFromRequest(req);

            // executionId is null (recording failed), so falls back to nodeId
            verify(creditClient).consumeCredits(
                    any(), any(),
                    eq("agent:test_agent"),
                    any(), any(), anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class)
            );
        }
    }
}
