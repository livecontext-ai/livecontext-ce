package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentExecutionEntity;
import com.apimarketplace.agent.domain.AgentExecutionIterationEntity;
import com.apimarketplace.agent.domain.AgentExecutionMessageEntity;
import com.apimarketplace.agent.domain.AgentExecutionToolCallEntity;
import com.apimarketplace.agent.service.dto.ChatAgentObservabilityRequest;
import com.apimarketplace.agent.service.dto.ChatAgentObservabilityRequest.*;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.repository.AgentExecutionIterationRepository;
import com.apimarketplace.agent.repository.AgentExecutionMessageRepository;
import com.apimarketplace.agent.repository.AgentExecutionRepository;
import com.apimarketplace.agent.repository.AgentExecutionToolCallRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("AgentObservabilityService - recordFromChat (conversation-service path)")
@ExtendWith(MockitoExtension.class)
class AgentObservabilityServiceRecordFromChatTest {

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
    @Mock private com.apimarketplace.agent.repository.AgentTaskClaimRepository claimRepository;

    @Captor private ArgumentCaptor<AgentExecutionEntity> execCaptor;
    @Captor private ArgumentCaptor<List<AgentExecutionIterationEntity>> iterationsCaptor;
    @Captor private ArgumentCaptor<List<AgentExecutionMessageEntity>> messagesCaptor;
    @Captor private ArgumentCaptor<List<AgentExecutionToolCallEntity>> toolCallsCaptor;

    private AgentObservabilityService service;

    private static final String TENANT_ID = "tenant-1";
    private static final String AGENT_ID = "00000000-0000-0000-0000-000000000001";
    private static final String CONV_ID = "conv-abc";

    @BeforeEach
    void setUp() {
        service = new AgentObservabilityService(
            executionRepository, iterationRepository, messageRepository,
            toolCallRepository, storageService, creditClient,
            aggregationService, breakdownService, agentRepository,
            prometheusMetrics, budgetReservationService
        );
        // claimRepository is @Autowired(required=false) - field-injected. Tests that don't
        // exercise the fallback path leave it null (matches prod when the bean isn't ready).
        try {
            java.lang.reflect.Field f = AgentObservabilityService.class.getDeclaredField("claimRepository");
            f.setAccessible(true);
            f.set(service, claimRepository);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Nested
    @DisplayName("Claim log fallback - fills task_id from agent_task_claims when caller omits it")
    class ClaimLogFallback {

        @Test
        @DisplayName("request.taskId NULL + active claim in log → exec.taskId populated from latest claim")
        void readsTaskIdFromClaimLogWhenRequestTaskIdIsNull() {
            String executionId = UUID.randomUUID().toString();
            UUID expectedTaskId = UUID.randomUUID();

            com.apimarketplace.agent.domain.AgentTaskClaimEntity claim =
                    new com.apimarketplace.agent.domain.AgentTaskClaimEntity();
            claim.setExecutionId(UUID.fromString(executionId));
            claim.setTaskId(expectedTaskId);
            claim.setEvent(com.apimarketplace.agent.domain.AgentTaskClaimEntity.EVT_CLAIMED);
            when(claimRepository.findLatestActiveClaim(UUID.fromString(executionId)))
                    .thenReturn(Optional.of(claim));

            ChatAgentObservabilityRequest req = minimalRequest(
                    /*taskId*/ null, /*executionId*/ executionId);

            service.recordFromChat(TENANT_ID, "org-test", req);

            verify(executionRepository, atLeastOnce()).save(execCaptor.capture());
            AgentExecutionEntity saved = execCaptor.getAllValues().get(0);
            assertThat(saved.getTaskId()).isEqualTo(expectedTaskId);
            assertThat(saved.getId()).isEqualTo(UUID.fromString(executionId));
        }

        @Test
        @DisplayName("request.taskId NULL + NO claim in log → exec.taskId stays null (graceful - claim happened but wasn't logged, or no claim happened)")
        void leavesTaskIdNullWhenClaimLogEmpty() {
            String executionId = UUID.randomUUID().toString();
            when(claimRepository.findLatestActiveClaim(UUID.fromString(executionId)))
                    .thenReturn(Optional.empty());

            ChatAgentObservabilityRequest req = minimalRequest(null, executionId);
            service.recordFromChat(TENANT_ID, "org-test", req);

            verify(executionRepository, atLeastOnce()).save(execCaptor.capture());
            assertThat(execCaptor.getAllValues().get(0).getTaskId()).isNull();
        }

        @Test
        @DisplayName("request.taskId PRESENT → claim log is NOT consulted (caller-supplied value wins)")
        void doesNotConsultClaimLogWhenRequestTaskIdProvided() {
            String executionId = UUID.randomUUID().toString();
            UUID callerSuppliedTaskId = UUID.randomUUID();

            ChatAgentObservabilityRequest req = minimalRequest(
                    callerSuppliedTaskId.toString(), executionId);

            service.recordFromChat(TENANT_ID, "org-test", req);

            verify(executionRepository, atLeastOnce()).save(execCaptor.capture());
            assertThat(execCaptor.getAllValues().get(0).getTaskId()).isEqualTo(callerSuppliedTaskId);
            verify(claimRepository, never()).findLatestActiveClaim(any());
        }

        @Test
        @DisplayName("Malformed executionId → swallowed, fallback not invoked, exec.taskId stays null")
        void swallowsMalformedExecutionIdAndSkipsFallback() {
            ChatAgentObservabilityRequest req = minimalRequest(null, "not-a-uuid");
            service.recordFromChat(TENANT_ID, "org-test", req);

            verify(executionRepository, atLeastOnce()).save(execCaptor.capture());
            assertThat(execCaptor.getAllValues().get(0).getTaskId()).isNull();
            verify(claimRepository, never()).findLatestActiveClaim(any());
        }

        private ChatAgentObservabilityRequest minimalRequest(String taskId, String executionId) {
            // 40 positional args - keep tightly aligned with ChatAgentObservabilityRequest record.
            return new ChatAgentObservabilityRequest(
                AGENT_ID,                            // agentEntityId
                "anthropic", "claude-3-sonnet",      // provider, model
                0.7, 4096, 10,                       // temperature, maxTokens, maxIterations
                true, "COMPLETED", null, null,        // success, stopReason, budgetScope, errorMessage
                100L, 1, 0, 0, 0, 1,                 // durationMs, iterationCount, 3xtoolCallsCounts, messageCount
                10, 5, 15,                            // promptTokens, completionTokens, totalTokens
                null, null, null, null,              // cache + reasoning tokens
                null, null, false, null, null,       // toolSequence, distinctTools, loopDetected, loopType, loopToolName
                "system prompt", "user prompt",       // systemPrompt, userPrompt
                CONV_ID, "CHAT",                      // conversationId, source
                taskId, executionId,                  // taskId, executionId - the fields under test
                null, null, null, null, null, null   // toolResults, conversationHistory, 4× per-iteration lists
            );
        }
    }

    /**
     * Build a full-detail request matching EXACTLY what conversation-service sends.
     * conversationHistory = execution-only messages from getCurrentExecutionMessages()
     * (no SYSTEM, no USER, no prior-turn messages).
     * systemPrompt and userPrompt are sent as separate fields.
     */
    private ChatAgentObservabilityRequest buildFullRequest() {
        // Flattened tool results (after the fix in AgentObservabilityClient)
        List<ToolResultDto> toolResults = List.of(
            new ToolResultDto("call_1", "web_search", Map.of("query", "test"), true, "Search results", null, 450L, Map.of("source", "web")),
            new ToolResultDto("call_2", "fetch_url", Map.of("url", "http://x"), false, null, "timeout", 5000L, null)
        );

        // Conversation history = execution-only messages (getCurrentExecutionMessages)
        List<MessageDto> history = List.of(
            new MessageDto("ASSISTANT", "I'll search", null, null,
                List.of(new ToolCallDto("call_1", "web_search", Map.of("query", "test")))),
            new MessageDto("TOOL", "Search results", "call_1", "web_search", null),
            new MessageDto("ASSISTANT", "Now fetching", null, null,
                List.of(new ToolCallDto("call_2", "fetch_url", Map.of("url", "http://x")))),
            new MessageDto("TOOL", "timeout error", "call_2", "fetch_url", null),
            new MessageDto("ASSISTANT", "Here are the results", null, null, null)
        );

        List<UsageInfoDto> usagePerIter = List.of(
            new UsageInfoDto(500, 200, 700, 50, 25, 40, 15),
            new UsageInfoDto(600, 300, 900, 60, 30, 50, 20),
            new UsageInfoDto(400, 100, 500, null, null, null, null)
        );

        return new ChatAgentObservabilityRequest(
            AGENT_ID, "anthropic", "claude-3-sonnet", 0.7, 4096, 10,
            true, "end_turn", null, null,
            5000L, 3, 2, 1, 1, 5,
            1500, 600, 2100,
            110, 55, 90, 35,
            "web_search,fetch_url", List.of("web_search", "fetch_url"),
            false, null, null,
            "You are helpful", "Search for X", CONV_ID, null, null, null,
            toolResults, history, usagePerIter,
            List.of(1500L, 2000L, 1500L), List.of("tool_use", "tool_use", "end_turn"),
            List.of(1, 1, 0)
        );
    }

    // ==========================================================================
    // Execution header
    // ==========================================================================

    @Nested
    @DisplayName("execution header")
    class ExecutionHeader {

        @Test
        @DisplayName("should build chat execution header with all fields")
        void buildChatHeader() {
            ChatAgentObservabilityRequest req = buildFullRequest();

            service.recordFromChat(TENANT_ID, "org-test", req);

            verify(executionRepository, atLeastOnce()).save(execCaptor.capture());
            AgentExecutionEntity exec = execCaptor.getAllValues().get(0);

            assertThat(exec.getTenantId()).isEqualTo(TENANT_ID);
            assertThat(exec.getSource()).isEqualTo("CHAT");
            assertThat(exec.getAgentType()).isEqualTo("agent");
            assertThat(exec.getAgentEntityId()).isEqualTo(UUID.fromString(AGENT_ID));
            // runId stays null on chat - the previous setRunId(conversationId) hack was
            // removed (chat has no workflow run; the conversation linkage lives in
            // conversation_id). See ChatObservabilityAdapter doc.
            assertThat(exec.getRunId()).isNull();
            assertThat(exec.getConversationId()).isEqualTo(CONV_ID);
            assertThat(exec.getNodeId()).isEqualTo("chat");
            assertThat(exec.getProvider()).isEqualTo("anthropic");
            assertThat(exec.getModel()).isEqualTo("claude-3-sonnet");
            assertThat(exec.getTemperature().doubleValue()).isEqualTo(0.7);
            assertThat(exec.getMaxTokensConfig()).isEqualTo(4096);
            assertThat(exec.getMaxIterationsConfig()).isEqualTo(10);
            assertThat(exec.getAgentConfigSnapshot())
                .containsEntry("provider", "anthropic")
                .containsEntry("model", "claude-3-sonnet")
                .containsEntry("temperature", 0.7)
                .containsEntry("maxTokens", 4096)
                .containsEntry("maxIterations", 10)
                .containsEntry("memoryEnabled", true)
                .containsEntry("source", "CHAT")
                .containsEntry("agentEntityId", AGENT_ID);
            assertThat(exec.getStatus()).isEqualTo("COMPLETED");
            assertThat(exec.getStopReason()).isEqualTo("end_turn");
            assertThat(exec.getDurationMs()).isEqualTo(5000);
            assertThat(exec.getTotalPromptTokens()).isEqualTo(1500);
            assertThat(exec.getTotalCompletionTokens()).isEqualTo(600);
            assertThat(exec.getTotalTokens()).isEqualTo(2100);
            assertThat(exec.getTotalCacheCreationTokens()).isEqualTo(110);
            assertThat(exec.getTotalCacheReadTokens()).isEqualTo(55);
            assertThat(exec.getTotalCachedTokens()).isEqualTo(90);
            assertThat(exec.getTotalReasoningTokens()).isEqualTo(35);
            assertThat(exec.getIterationCount()).isEqualTo(3);
            assertThat(exec.getTotalToolCalls()).isEqualTo(2);
            assertThat(exec.getSuccessfulToolCalls()).isEqualTo(1);
            assertThat(exec.getFailedToolCalls()).isEqualTo(1);
            assertThat(exec.getToolSequence()).isEqualTo("web_search,fetch_url");
            assertThat(exec.getDistinctTools()).containsExactly("web_search", "fetch_url");
            assertThat(exec.isLoopDetected()).isFalse();
            assertThat(exec.getSystemPrompt()).isEqualTo("You are helpful");
        }

        @Test
        @DisplayName("should link execution to task when taskId is provided")
        void taskIdLinked() {
            UUID taskId = UUID.randomUUID();
            ChatAgentObservabilityRequest req = new ChatAgentObservabilityRequest(
                AGENT_ID, "anthropic", "claude-3", null, null, null,
                true, "end_turn", null, null,
                100L, 1, 0, 0, 0, 0,
                10, 5, 15, null, null, null, null,
                null, null, false, null, null, null, null, CONV_ID, "TASK", taskId.toString(), null,
                null, null, null, null, null, null
            );

            service.recordFromChat(TENANT_ID, "org-test", req);

            verify(executionRepository, atLeastOnce()).save(execCaptor.capture());
            AgentExecutionEntity exec = execCaptor.getAllValues().get(0);
            assertThat(exec.getTaskId()).isEqualTo(taskId);
            assertThat(exec.getSource()).isEqualTo("TASK");
        }

        @Test
        @DisplayName("should set FAILED status when success=false")
        void failedStatus() {
            ChatAgentObservabilityRequest req = new ChatAgentObservabilityRequest(
                AGENT_ID, "anthropic", "claude-3", null, null, null,
                false, "error", null, "Something broke",
                1000L, 1, 0, 0, 0, 2,
                100, 50, 150, null, null, null, null,
                null, null, false, null, null, null, null, CONV_ID, null, null, null,
                null, null, null, null, null, null
            );

            service.recordFromChat(TENANT_ID, "org-test", req);

            verify(executionRepository, atLeastOnce()).save(execCaptor.capture());
            assertThat(execCaptor.getAllValues().get(0).getStatus()).isEqualTo("FAILED");
            assertThat(execCaptor.getAllValues().get(0).getErrorMessage()).isEqualTo("Something broke");
        }

        @Test
        @DisplayName("BUDGET_EXHAUSTED with totalTokens > 0 stays COMPLETED - agent ran, just hit the limit mid-execution (preserves legacy 'partial' semantics)")
        void budgetExhaustedWithTokensStaysCompleted() {
            ChatAgentObservabilityRequest req = new ChatAgentObservabilityRequest(
                AGENT_ID, "anthropic", "claude-3", null, null, null,
                false, "BUDGET_EXHAUSTED", "tenant", "Hit per-tenant cap",
                500L, 1, 0, 0, 0, 1,
                250, 100, 350, null, null, null, null,
                null, null, false, null, null, null, null, CONV_ID, "CHAT", null, null,
                null, null, null, null, null, null
            );

            service.recordFromChat(TENANT_ID, "org-test", req);

            verify(executionRepository, atLeastOnce()).save(execCaptor.capture());
            assertThat(execCaptor.getAllValues().get(0).getStatus()).isEqualTo("COMPLETED");
            assertThat(execCaptor.getAllValues().get(0).getStopReason()).isEqualTo("BUDGET_EXHAUSTED");
        }

        @Test
        @DisplayName("TIMEOUT with totalTokens = 0 keeps COMPLETED - the most dangerous case: AgentMetricsQueryService explicitly excludes TIMEOUT from failure_count, so a regression that widened the predicate would make 0-token TIMEOUTs vanish from BOTH success and failure tallies")
        void timeoutWithZeroTokensStaysCompleted() {
            ChatAgentObservabilityRequest req = new ChatAgentObservabilityRequest(
                AGENT_ID, "anthropic", "claude-3", null, null, null,
                false, "TIMEOUT", null, "Execution timeout reached",
                30000L, 0, 0, 0, 0, 0,
                0, 0, 0, null, null, null, null,
                null, null, false, null, null, null, null, CONV_ID, "CHAT", null, null,
                null, null, null, null, null, null
            );

            service.recordFromChat(TENANT_ID, "org-test", req);

            verify(executionRepository, atLeastOnce()).save(execCaptor.capture());
            AgentExecutionEntity persisted = execCaptor.getAllValues().get(0);
            assertThat(persisted.getStatus()).isEqualTo("COMPLETED");
            assertThat(persisted.getStopReason()).isEqualTo("TIMEOUT");
            assertThat(persisted.getTotalTokens()).isEqualTo(0);
        }

        @Test
        @DisplayName("LOOP_DETECTED with totalTokens = 0 keeps COMPLETED - third PARTIAL reason the BUDGET_EXHAUSTED-only narrowing protects from being demoted")
        void loopDetectedWithZeroTokensStaysCompleted() {
            ChatAgentObservabilityRequest req = new ChatAgentObservabilityRequest(
                AGENT_ID, "anthropic", "claude-3", null, null, null,
                false, "LOOP_DETECTED", null, "Same tool called 5x in a row",
                200L, 5, 0, 0, 0, 5,
                0, 0, 0, null, null, null, null,
                null, null, true, "IDENTICAL_TOOL", "duplicate_tool",
                null, null, CONV_ID, "CHAT", null, null,
                null, null, null, null, null, null
            );

            service.recordFromChat(TENANT_ID, "org-test", req);

            verify(executionRepository, atLeastOnce()).save(execCaptor.capture());
            AgentExecutionEntity persisted = execCaptor.getAllValues().get(0);
            assertThat(persisted.getStatus()).isEqualTo("COMPLETED");
            assertThat(persisted.getStopReason()).isEqualTo("LOOP_DETECTED");
            assertThat(persisted.getTotalTokens()).isEqualTo(0);
        }

        @Test
        @DisplayName("MAX_ITERATIONS with totalTokens = 0 keeps COMPLETED - the BUDGET_EXHAUSTED-only narrowing protects TIMEOUT/MAX_ITERATIONS/LOOP_DETECTED from being demoted (AgentMetricsQueryService excludes TIMEOUT from failure_count; FAILED would make 0-token TIMEOUTs vanish from both success and failure tallies)")
        void maxIterationsWithZeroTokensStaysCompleted() {
            ChatAgentObservabilityRequest req = new ChatAgentObservabilityRequest(
                AGENT_ID, "anthropic", "claude-3", null, null, null,
                false, "MAX_ITERATIONS", null, "Hit iteration cap",
                100L, 5, 0, 0, 0, 5,
                0, 0, 0, null, null, null, null,
                null, null, false, null, null, null, null, CONV_ID, "CHAT", null, null,
                null, null, null, null, null, null
            );

            service.recordFromChat(TENANT_ID, "org-test", req);

            verify(executionRepository, atLeastOnce()).save(execCaptor.capture());
            AgentExecutionEntity persisted = execCaptor.getAllValues().get(0);
            assertThat(persisted.getStatus()).isEqualTo("COMPLETED");
            assertThat(persisted.getStopReason()).isEqualTo("MAX_ITERATIONS");
        }

        @Test
        @DisplayName("BUDGET_EXHAUSTED with totalTokens = 0 → FAILED (regression: sync chat 402 path records failure-only rows with 0 tokens; pre-fix the dashboard showed green success because PARTIAL terminal collapsed to COMPLETED)")
        void budgetExhaustedWithZeroTokensIsFailed() {
            ChatAgentObservabilityRequest req = new ChatAgentObservabilityRequest(
                AGENT_ID, "anthropic", "claude-3", null, null, null,
                false, "BUDGET_EXHAUSTED", null, "Insufficient credits",
                0L, 0, 0, 0, 0, 2,
                0, 0, 0, null, null, null, null,
                null, null, false, null, null, null, null, CONV_ID, "SCHEDULE", null, null,
                null, null, null, null, null, null
            );

            service.recordFromChat(TENANT_ID, "org-test", req);

            verify(executionRepository, atLeastOnce()).save(execCaptor.capture());
            AgentExecutionEntity persisted = execCaptor.getAllValues().get(0);
            assertThat(persisted.getStatus()).isEqualTo("FAILED");
            assertThat(persisted.getStopReason()).isEqualTo("BUDGET_EXHAUSTED");
            assertThat(persisted.getErrorMessage()).isEqualTo("Insufficient credits");
        }
    }

    // ==========================================================================
    // Iterations
    // ==========================================================================

    @Nested
    @DisplayName("iterations")
    class Iterations {

        @Test
        @DisplayName("should save 1-based iterations with usage and durations")
        void save1BasedIterations() {
            ChatAgentObservabilityRequest req = buildFullRequest();

            service.recordFromChat(TENANT_ID, "org-test", req);

            verify(iterationRepository).saveAll(iterationsCaptor.capture());
            List<AgentExecutionIterationEntity> saved = iterationsCaptor.getValue();

            assertThat(saved).hasSize(3);

            // Iteration 1
            assertThat(saved.get(0).getIterationNumber()).isEqualTo(1);
            assertThat(saved.get(0).getPromptTokens()).isEqualTo(500);
            assertThat(saved.get(0).getCompletionTokens()).isEqualTo(200);
            assertThat(saved.get(0).getCacheCreationTokens()).isEqualTo(50);
            assertThat(saved.get(0).getCacheReadTokens()).isEqualTo(25);
            assertThat(saved.get(0).getCachedTokens()).isEqualTo(40);
            assertThat(saved.get(0).getReasoningTokens()).isEqualTo(15);
            assertThat(saved.get(0).getDurationMs()).isEqualTo(1500L);
            assertThat(saved.get(0).getFinishReason()).isEqualTo("tool_use");
            assertThat(saved.get(0).isFinal()).isFalse();

            // Iteration 2
            assertThat(saved.get(1).getIterationNumber()).isEqualTo(2);
            assertThat(saved.get(1).getPromptTokens()).isEqualTo(600);
            assertThat(saved.get(1).getDurationMs()).isEqualTo(2000L);
            assertThat(saved.get(1).getFinishReason()).isEqualTo("tool_use");
            assertThat(saved.get(1).isFinal()).isFalse();

            // Iteration 3 (final)
            assertThat(saved.get(2).getIterationNumber()).isEqualTo(3);
            assertThat(saved.get(2).isFinal()).isTrue();
            assertThat(saved.get(2).getFinishReason()).isEqualTo("end_turn");
        }

        @Test
        @DisplayName("should skip iterations when iterationCount is 0")
        void skipIterationsWhenZero() {
            ChatAgentObservabilityRequest req = new ChatAgentObservabilityRequest(
                AGENT_ID, "anthropic", "claude-3", null, null, null,
                true, "end_turn", null, null,
                100L, 0, 0, 0, 0, 0,
                10, 5, 15, null, null, null, null,
                null, null, false, null, null, null, null, CONV_ID, null, null, null,
                null, null, null, null, null, null
            );

            service.recordFromChat(TENANT_ID, "org-test", req);

            verify(iterationRepository, never()).saveAll(any());
        }
    }

    // ==========================================================================
    // Messages (extractCurrentExecutionMessages filtering)
    // ==========================================================================

    @Nested
    @DisplayName("messages")
    class Messages {

        @Test
        @DisplayName("should prepend SYSTEM+USER then append execution messages")
        void prependSystemUserThenExecutionMessages() {
            ChatAgentObservabilityRequest req = buildFullRequest();

            service.recordFromChat(TENANT_ID, "org-test", req);

            verify(messageRepository).saveAll(messagesCaptor.capture());
            List<AgentExecutionMessageEntity> saved = messagesCaptor.getValue();

            // SYSTEM (from systemPrompt) + USER (from userPrompt) + 5 execution messages
            assertThat(saved).hasSize(7);
            assertThat(saved.get(0).getRole()).isEqualTo("SYSTEM");
            assertThat(saved.get(0).getIterationNumber()).isNull();
            assertThat(saved.get(1).getRole()).isEqualTo("USER");
            assertThat(saved.get(1).getContent()).isEqualTo("Search for X");
            assertThat(saved.get(1).getIterationNumber()).isEqualTo(0);
            assertThat(saved.get(2).getRole()).isEqualTo("ASSISTANT");

            // TOOL messages should have toolCallId/toolName
            assertThat(saved.get(3).getRole()).isEqualTo("TOOL");
            assertThat(saved.get(3).getToolCallId()).isEqualTo("call_1");
            assertThat(saved.get(3).getToolName()).isEqualTo("web_search");
        }

        @Test
        @DisplayName("should update messageCount to current execution messages only")
        void updateMessageCountForCurrentExecution() {
            ChatAgentObservabilityRequest req = buildFullRequest();

            service.recordFromChat(TENANT_ID, "org-test", req);

            // The second save should have the updated messageCount
            verify(executionRepository, atLeastOnce()).save(execCaptor.capture());
            AgentExecutionEntity lastSaved = execCaptor.getValue();
            assertThat(lastSaved.getMessageCount()).isEqualTo(7);
        }

        @Test
        @DisplayName("should handle conversation with no prior history (single user message)")
        void noOldHistory() {
            // Execution messages only - USER is provided via userPrompt field
            List<MessageDto> history = List.of(
                new MessageDto("ASSISTANT", "Hi there!", null, null, null)
            );

            ChatAgentObservabilityRequest req = new ChatAgentObservabilityRequest(
                AGENT_ID, "anthropic", "claude-3", null, null, null,
                true, "end_turn", null, null,
                100L, 1, 0, 0, 0, 1,
                10, 5, 15, null, null, null, null,
                null, null, false, null, null, null, "Hello", CONV_ID, null, null, null,
                null, history, null, null, null, null
            );

            service.recordFromChat(TENANT_ID, "org-test", req);

            verify(messageRepository).saveAll(messagesCaptor.capture());
            List<AgentExecutionMessageEntity> saved = messagesCaptor.getValue();
            assertThat(saved).hasSize(2);
            assertThat(saved.get(0).getRole()).isEqualTo("USER");
            assertThat(saved.get(1).getRole()).isEqualTo("ASSISTANT");
        }

        @Test
        @DisplayName("should strip USER/SYSTEM from history to prevent duplication")
        void stripUserAndSystemFromHistory() {
            // Simulate history that leaks SYSTEM+USER (e.g. bridge path returning full conversation)
            List<MessageDto> history = List.of(
                new MessageDto("SYSTEM", "You are helpful", null, null, null),
                new MessageDto("USER", "Search for X", null, null, null),
                new MessageDto("ASSISTANT", "I'll search", null, null, null),
                new MessageDto("TOOL", "results", "call_1", "web_search", null),
                new MessageDto("ASSISTANT", "Done", null, null, null)
            );

            ChatAgentObservabilityRequest req = new ChatAgentObservabilityRequest(
                AGENT_ID, "anthropic", "claude-3", null, null, null,
                true, "end_turn", null, null,
                100L, 2, 1, 1, 0, 5,
                10, 5, 15, null, null, null, null,
                null, null, false, null, null,
                "You are helpful", "Search for X", CONV_ID, null, null, null,
                null, history, null, null, null, null
            );

            service.recordFromChat(TENANT_ID, "org-test", req);

            verify(messageRepository).saveAll(messagesCaptor.capture());
            List<AgentExecutionMessageEntity> saved = messagesCaptor.getValue();

            // Should be: SYSTEM(prepended) + USER(prepended) + ASSISTANT + TOOL + ASSISTANT = 5
            // NOT 7 (no leaked SYSTEM/USER from history)
            assertThat(saved).hasSize(5);
            assertThat(saved.get(0).getRole()).isEqualTo("SYSTEM");
            assertThat(saved.get(0).getContent()).isEqualTo("You are helpful");
            assertThat(saved.get(1).getRole()).isEqualTo("USER");
            assertThat(saved.get(1).getContent()).isEqualTo("Search for X");
            assertThat(saved.get(2).getRole()).isEqualTo("ASSISTANT");
            assertThat(saved.get(3).getRole()).isEqualTo("TOOL");
            assertThat(saved.get(4).getRole()).isEqualTo("ASSISTANT");
            // No duplicate USER messages
            long userCount = saved.stream().filter(m -> "USER".equals(m.getRole())).count();
            assertThat(userCount).isEqualTo(1);
        }

        @Test
        @DisplayName("should handle null userPrompt and null systemPrompt with non-empty history")
        void nullPromptsWithHistory() {
            List<MessageDto> history = List.of(
                new MessageDto("ASSISTANT", "response", null, null, null)
            );

            ChatAgentObservabilityRequest req = new ChatAgentObservabilityRequest(
                AGENT_ID, "anthropic", "claude-3", null, null, null,
                true, "end_turn", null, null,
                100L, 1, 0, 0, 0, 1,
                10, 5, 15, null, null, null, null,
                null, null, false, null, null,
                null, null, CONV_ID, null, null, null,
                null, history, null, null, null, null
            );

            service.recordFromChat(TENANT_ID, "org-test", req);

            verify(messageRepository).saveAll(messagesCaptor.capture());
            List<AgentExecutionMessageEntity> saved = messagesCaptor.getValue();

            // Only the ASSISTANT message - no SYSTEM/USER prepended when prompts are null
            assertThat(saved).hasSize(1);
            assertThat(saved.get(0).getRole()).isEqualTo("ASSISTANT");
        }
    }

    // ==========================================================================
    // Tool calls
    // ==========================================================================

    @Nested
    @DisplayName("tool calls")
    class ToolCalls {

        @Test
        @DisplayName("should save flattened tool results as ToolCall entities")
        void saveFlattenedToolResults() {
            ChatAgentObservabilityRequest req = buildFullRequest();

            service.recordFromChat(TENANT_ID, "org-test", req);

            verify(toolCallRepository).saveAll(toolCallsCaptor.capture());
            List<AgentExecutionToolCallEntity> saved = toolCallsCaptor.getValue();

            assertThat(saved).hasSize(2);

            // First tool call
            assertThat(saved.get(0).getToolCallId()).isEqualTo("call_1");
            assertThat(saved.get(0).getToolName()).isEqualTo("web_search");
            assertThat(saved.get(0).getArguments()).isEqualTo(Map.of("query", "test"));
            assertThat(saved.get(0).isSuccess()).isTrue();
            assertThat(saved.get(0).getContent()).isEqualTo("Search results");
            assertThat(saved.get(0).getDurationMs()).isEqualTo(450L);
            assertThat(saved.get(0).getMetadata()).isEqualTo(Map.of("source", "web"));
            assertThat(saved.get(0).getSequenceNumber()).isEqualTo(0);

            // Second tool call (failed)
            assertThat(saved.get(1).getToolCallId()).isEqualTo("call_2");
            assertThat(saved.get(1).getToolName()).isEqualTo("fetch_url");
            assertThat(saved.get(1).isSuccess()).isFalse();
            assertThat(saved.get(1).getErrorMessage()).isEqualTo("timeout");
            assertThat(saved.get(1).getSequenceNumber()).isEqualTo(1);
        }

        @Test
        @DisplayName("should map tool calls to iterations via toolCallsPerIteration")
        void mapToolCallsToIterations() {
            ChatAgentObservabilityRequest req = buildFullRequest();
            // toolCallsPerIteration = [1, 1, 0] → tool 0 = iter 1, tool 1 = iter 2

            service.recordFromChat(TENANT_ID, "org-test", req);

            verify(toolCallRepository).saveAll(toolCallsCaptor.capture());
            List<AgentExecutionToolCallEntity> saved = toolCallsCaptor.getValue();

            assertThat(saved.get(0).getIterationNumber()).isEqualTo(1);
            assertThat(saved.get(1).getIterationNumber()).isEqualTo(2);
        }

        @Test
        @DisplayName("should detect repeat tool calls")
        void detectRepeatToolCalls() {
            List<ToolResultDto> toolResults = List.of(
                new ToolResultDto("c1", "search", Map.of("q", "test"), true, "r1", null, 100L, null),
                new ToolResultDto("c2", "search", Map.of("q", "test"), true, "r2", null, 100L, null),
                new ToolResultDto("c3", "search", Map.of("q", "test"), true, "r3", null, 100L, null),
                new ToolResultDto("c4", "fetch", Map.of("url", "x"), true, "r4", null, 100L, null)
            );

            List<MessageDto> history = List.of(
                new MessageDto("ASSISTANT", "ok", null, null, null)
            );

            ChatAgentObservabilityRequest req = new ChatAgentObservabilityRequest(
                AGENT_ID, "anthropic", "claude-3", null, null, null,
                true, "end_turn", null, null,
                500L, 1, 4, 4, 0, 1,
                100, 50, 150, null, null, null, null,
                "search,search,search,fetch", List.of("search", "fetch"),
                false, null, null, null, "go", CONV_ID, null, null, null,
                toolResults, history, null, null, null, null
            );

            service.recordFromChat(TENANT_ID, "org-test", req);

            verify(toolCallRepository).saveAll(toolCallsCaptor.capture());
            List<AgentExecutionToolCallEntity> saved = toolCallsCaptor.getValue();

            assertThat(saved.get(0).isRepeat()).isFalse();
            assertThat(saved.get(0).getConsecutiveCount()).isEqualTo(1);

            assertThat(saved.get(1).isRepeat()).isTrue();
            assertThat(saved.get(1).getConsecutiveCount()).isEqualTo(2);

            assertThat(saved.get(2).isRepeat()).isTrue();
            assertThat(saved.get(2).getConsecutiveCount()).isEqualTo(3);

            assertThat(saved.get(3).isRepeat()).isFalse();
            assertThat(saved.get(3).getConsecutiveCount()).isEqualTo(1);
        }
    }

    // ==========================================================================
    // Counter updates
    // ==========================================================================

    @Nested
    @DisplayName("counter updates")
    class CounterUpdates {

        @Test
        @DisplayName("should increment agent entity counters")
        void incrementCounters() {
            ChatAgentObservabilityRequest req = buildFullRequest();

            service.recordFromChat(TENANT_ID, "org-test", req);

            verify(executionRepository).incrementCounters(
                eq(UUID.fromString(AGENT_ID)),
                eq(2100L),
                eq(2),
                eq(1),  // success
                eq(0),  // failure
                eq(5000L),
                any(Instant.class)
            );
        }

        @Test
        @DisplayName("should skip counter update when agentEntityId is null")
        void skipWhenNoAgentId() {
            ChatAgentObservabilityRequest req = new ChatAgentObservabilityRequest(
                null, "anthropic", "claude-3", null, null, null,
                true, "end_turn", null, null,
                100L, 1, 0, 0, 0, 0,
                10, 5, 15, null, null, null, null,
                null, null, false, null, null, null, null, CONV_ID, null, null, null,
                null, null, null, null, null, null
            );

            service.recordFromChat(TENANT_ID, "org-test", req);

            verify(executionRepository, never()).incrementCounters(any(), anyLong(), anyInt(), anyInt(), anyInt(), anyLong(), any());
        }
    }

    // ==========================================================================
    // Edge cases
    // ==========================================================================

    @Nested
    @DisplayName("edge cases")
    class EdgeCases {

        @Test
        @DisplayName("should handle empty toolResults")
        void emptyToolResults() {
            List<MessageDto> history = List.of(
                new MessageDto("ASSISTANT", "Hi!", null, null, null)
            );

            ChatAgentObservabilityRequest req = new ChatAgentObservabilityRequest(
                AGENT_ID, "anthropic", "claude-3", null, null, null,
                true, "end_turn", null, null,
                100L, 1, 0, 0, 0, 1,
                10, 5, 15, null, null, null, null,
                null, null, false, null, null, null, "Hello", CONV_ID, null, null, null,
                List.of(), history, null, null, null, null
            );

            service.recordFromChat(TENANT_ID, "org-test", req);

            verify(toolCallRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("should handle null conversationHistory (header-only mode)")
        void nullHistoryHeaderOnly() {
            ChatAgentObservabilityRequest req = new ChatAgentObservabilityRequest(
                AGENT_ID, "anthropic", "claude-3", null, null, null,
                true, "end_turn", null, null,
                100L, 1, 0, 0, 0, 0,
                10, 5, 15, null, null, null, null,
                null, null, false, null, null, null, null, CONV_ID, null, null, null,
                null, null, null, null, null, null
            );

            service.recordFromChat(TENANT_ID, "org-test", req);

            // Header should still be saved
            verify(executionRepository, atLeastOnce()).save(any(AgentExecutionEntity.class));
            // No detail tables should be saved
            verify(iterationRepository, never()).saveAll(any());
            verify(messageRepository, never()).saveAll(any());
            verify(toolCallRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("should handle null usagePerIteration gracefully")
        void nullUsagePerIteration() {
            List<MessageDto> history = List.of(
                new MessageDto("ASSISTANT", "Hi!", null, null, null)
            );

            ChatAgentObservabilityRequest req = new ChatAgentObservabilityRequest(
                AGENT_ID, "anthropic", "claude-3", null, null, null,
                true, "end_turn", null, null,
                100L, 2, 0, 0, 0, 1,
                10, 5, 15, null, null, null, null,
                null, null, false, null, null, null, "Hello", CONV_ID, null, null, null,
                null, history, null, null, null, null
            );

            service.recordFromChat(TENANT_ID, "org-test", req);

            verify(iterationRepository).saveAll(iterationsCaptor.capture());
            List<AgentExecutionIterationEntity> saved = iterationsCaptor.getValue();
            assertThat(saved).hasSize(2);
            // No usage data, so tokens should be null (Integer type defaults)
            assertThat(saved.get(0).getPromptTokens()).isNull();
        }

        @Test
        @DisplayName("Persists dead-letter row on 402 soft rejection for chat consumption")
        void persistsDeadLetterOn402Rejection() {
            // Mirrors the workflow-path test in AgentObservabilityServiceRecordFromRequestTest.
            // Prod incident: bridge chat burned 2M Opus tokens, auth-service returned 402.
            // consumeCredits returns {success=false, error="402 …"} without throwing, so the
            // pre-fix catch(Exception) branch never fired → no ledger, no dead-letter.
            ChatAgentObservabilityRequest req = buildFullRequest();
            Map<String, Object> rejected = new java.util.HashMap<>();
            rejected.put("success", false);
            rejected.put("error", "402 Insufficient credits");
            when(creditClient.consumeCredits(any(), any(), any(), any(), any(), anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class)))
                .thenReturn(rejected);

            service.recordFromChat(TENANT_ID, "org-test", req);

            verify(creditClient).persistRejection(
                eq(TENANT_ID),
                eq("CHAT_CONVERSATION"),
                any(String.class),
                eq("anthropic"),
                eq("claude-3-sonnet"),
                eq(1500),
                eq(600),
                eq("402 Insufficient credits")
            );
        }

        @Test
        @DisplayName("Does not persist dead-letter row when chat credit consumption succeeds")
        void doesNotPersistDeadLetterOnSuccess() {
            ChatAgentObservabilityRequest req = buildFullRequest();
            Map<String, Object> success = new java.util.HashMap<>();
            success.put("success", true);
            success.put("creditsUsed", 2.0);
            when(creditClient.consumeCredits(any(), any(), any(), any(), any(), anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class)))
                .thenReturn(success);

            service.recordFromChat(TENANT_ID, "org-test", req);

            verify(creditClient, never()).persistRejection(
                any(), any(), any(), any(), any(), anyInt(), anyInt(), any());
        }

        @Test
        @DisplayName("chat cache/reasoning totals are forwarded verbatim to billing (cache-aware fix)")
        void chatCacheCountersForwardedVerbatim() {
            // Regression for the 2026-06-11 cache-billing fix on the CHAT path:
            // the DTO totals (cacheCreation=110, cacheRead=55, cached=90, reasoning=35
            // in buildFullRequest) must reach the credit client in the right slots so
            // auth-service can bill cache reads at 0.1x instead of full input rate.
            ChatAgentObservabilityRequest req = buildFullRequest();

            service.recordFromChat(TENANT_ID, "org-test", req);

            verify(creditClient).consumeCredits(
                eq(TENANT_ID),
                eq("CHAT_CONVERSATION"),
                any(String.class),
                eq("anthropic"),
                eq("claude-3-sonnet"),
                eq(1500),
                eq(600),
                eq(new com.apimarketplace.common.credit.LlmCacheTokens(110, 55, 90, 35))
            );
        }

        @Test
        @DisplayName("should handle null cache token fields in extended usage")
        void nullCacheTokens() {
            ChatAgentObservabilityRequest req = new ChatAgentObservabilityRequest(
                AGENT_ID, "anthropic", "claude-3", null, null, null,
                true, "end_turn", null, null,
                100L, 1, 0, 0, 0, 0,
                10, 5, 15, null, null, null, null,
                null, null, false, null, null, null, null, CONV_ID, null, null, null,
                null, null, null, null, null, null
            );

            service.recordFromChat(TENANT_ID, "org-test", req);

            verify(executionRepository, atLeastOnce()).save(execCaptor.capture());
            AgentExecutionEntity exec = execCaptor.getAllValues().get(0);
            assertThat(exec.getTotalCacheCreationTokens()).isEqualTo(0);
            assertThat(exec.getTotalCacheReadTokens()).isEqualTo(0);
            assertThat(exec.getTotalCachedTokens()).isEqualTo(0);
            assertThat(exec.getTotalReasoningTokens()).isEqualTo(0);
        }
    }

    // ==========================================================================
    // PR20 - workspace identity propagation through the chat path
    // ==========================================================================

    /**
     * Regression guards for the chat-driven half of the "agent visible / history empty
     * in team workspace" bug. Pre-PR20 round-2 the workflow producer stamped org_id but
     * the chat producer ({@code conversation-service AgentObservabilityClient.recordAsync})
     * stripped the X-Organization-ID header on its outbound call, so chat-driven agent
     * rows persisted with org_id=NULL. The reviewer-C finding closed that gap by threading
     * orgId explicitly through the @Async boundary AND through
     * {@code AgentObservabilityService.recordFromChat(tenantId, organizationId, request)}.
     *
     * <p>These tests pin the contract on the agent-service receiving end: when the
     * controller resolves a non-null orgId from the inbound header it propagates onto
     * the persisted row; when no orgId is set (personal workspace) the row carries NULL.</p>
     */
    @Nested
    @DisplayName("PR20 - workspace identity stamped on chat observability rows")
    class OrganizationIdStamping {

        @Test
        @DisplayName("orgId non-null on recordFromChat → stamped onto persisted execution header")
        void orgIdStampedOnHeader() {
            ChatAgentObservabilityRequest req = buildFullRequest();

            service.recordFromChat(TENANT_ID, "org-acme", req);

            verify(executionRepository, atLeastOnce()).save(execCaptor.capture());
            AgentExecutionEntity exec = execCaptor.getAllValues().get(0);
            assertThat(exec.getOrganizationId())
                .as("recordFromChat MUST stamp orgId so the strict org finder finds the row.")
                .isEqualTo("org-acme");
            assertThat(exec.getTenantId())
                .as("tenant_id must still be set; org scope augments rather than replaces tenant.")
                .isEqualTo(TENANT_ID);
        }

        // Round-8 (2026-05-20): two tests deleted that asserted the legacy
        // null-org personal-scope persist behavior:
        //   - nullOrgIdPersistsAsNull: documented org_id=NULL persists after V263 NOT NULL → no longer valid.
        //   - legacyOverloadPersonalScope: exercised the deleted 2-arg recordFromChat overload.
        // Post-V263 the only legitimate scope is org-strict (non-null orgId).
    }
}
