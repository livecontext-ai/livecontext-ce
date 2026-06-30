package com.apimarketplace.agent.service;

import com.apimarketplace.agent.client.dto.AgentObservabilityRequest;
import com.apimarketplace.agent.domain.AgentExecutionEntity;
import com.apimarketplace.agent.domain.AgentExecutionIterationEntity;
import com.apimarketplace.agent.domain.AgentExecutionMessageEntity;
import com.apimarketplace.agent.domain.AgentExecutionToolCallEntity;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.repository.AgentExecutionIterationRepository;
import com.apimarketplace.agent.repository.AgentExecutionMessageRepository;
import com.apimarketplace.agent.repository.AgentExecutionRepository;
import com.apimarketplace.agent.repository.AgentExecutionToolCallRepository;
import com.apimarketplace.agent.repository.AgentTaskRepository;
import java.math.BigDecimal;
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

@DisplayName("AgentObservabilityService - recordFromRequest (workflow/sub-agent path)")
@ExtendWith(MockitoExtension.class)
class AgentObservabilityServiceRecordFromRequestTest {

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
    // Field-injected on the service (@Autowired required=false), not a constructor arg, so it is
    // wired via ReflectionTestUtils inside the TaskLinkResilience nested class only.
    @Mock private AgentTaskRepository taskRepository;

    @Captor private ArgumentCaptor<AgentExecutionEntity> execCaptor;
    @Captor private ArgumentCaptor<List<AgentExecutionIterationEntity>> iterationsCaptor;
    @Captor private ArgumentCaptor<List<AgentExecutionMessageEntity>> messagesCaptor;
    @Captor private ArgumentCaptor<List<AgentExecutionToolCallEntity>> toolCallsCaptor;

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

    private AgentObservabilityRequest buildBaseRequest() {
        AgentObservabilityRequest req = new AgentObservabilityRequest();
        req.setTenantId("tenant-1");
        req.setAgentEntityId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        req.setAgentType("agent");
        req.setNodeId("agent:my_agent");
        req.setRunId("run-123");
        req.setProvider("anthropic");
        req.setModel("claude-3-sonnet");
        req.setTemperature(0.7);
        req.setMaxTokensConfig(4096);
        req.setMaxIterationsConfig(10);
        req.setStatus("COMPLETED");
        req.setStopReason("end_turn");
        req.setDurationMs(5000L);
        req.setPromptTokens(1000);
        req.setCompletionTokens(500);
        req.setTotalTokens(1500);
        req.setCacheCreationTokens(100);
        req.setCacheReadTokens(50);
        req.setCachedTokens(80);
        req.setReasoningTokens(30);
        req.setIterationCount(2);
        req.setTotalToolCalls(3);
        req.setLoopDetected(false);
        req.setSystemPrompt("You are a helpful assistant");
        return req;
    }

    // ==========================================================================
    // Header fields
    // ==========================================================================

    @Nested
    @DisplayName("execution header")
    class ExecutionHeader {

        @Test
        @DisplayName("should persist all header fields correctly")
        void allHeaderFields() {
            AgentObservabilityRequest req = buildBaseRequest();
            req.setWorkflowRunId(UUID.fromString("00000000-0000-0000-0000-000000000099"));

            service.recordFromRequest(req);

            verify(executionRepository, atLeastOnce()).save(execCaptor.capture());
            AgentExecutionEntity exec = execCaptor.getAllValues().get(0);

            assertThat(exec.getTenantId()).isEqualTo("tenant-1");
            assertThat(exec.getAgentType()).isEqualTo("agent");
            assertThat(exec.getNodeId()).isEqualTo("agent:my_agent");
            assertThat(exec.getRunId()).isEqualTo("run-123");
            assertThat(exec.getProvider()).isEqualTo("anthropic");
            assertThat(exec.getModel()).isEqualTo("claude-3-sonnet");
            assertThat(exec.getTemperature().doubleValue()).isEqualTo(0.7);
            assertThat(exec.getMaxTokensConfig()).isEqualTo(4096);
            assertThat(exec.getMaxIterationsConfig()).isEqualTo(10);
            assertThat(exec.getStatus()).isEqualTo("COMPLETED");
            assertThat(exec.getStopReason()).isEqualTo("end_turn");
            assertThat(exec.getDurationMs()).isEqualTo(5000L);
            assertThat(exec.getTotalPromptTokens()).isEqualTo(1000);
            assertThat(exec.getTotalCompletionTokens()).isEqualTo(500);
            assertThat(exec.getTotalTokens()).isEqualTo(1500);
            assertThat(exec.getTotalCacheCreationTokens()).isEqualTo(100);
            assertThat(exec.getTotalCacheReadTokens()).isEqualTo(50);
            assertThat(exec.getTotalCachedTokens()).isEqualTo(80);
            assertThat(exec.getTotalReasoningTokens()).isEqualTo(30);
            assertThat(exec.getIterationCount()).isEqualTo(2);
            assertThat(exec.getTotalToolCalls()).isEqualTo(3);
            assertThat(exec.isLoopDetected()).isFalse();
            assertThat(exec.getSystemPrompt()).isEqualTo("You are a helpful assistant");
            assertThat(exec.getWorkflowRunId()).isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000099"));
        }

        @Test
        @DisplayName("should set workflowRunId directly when provided")
        void setWorkflowRunIdDirectly() {
            AgentObservabilityRequest req = buildBaseRequest();
            req.setWorkflowRunId(UUID.fromString("00000000-0000-0000-0000-000000000099"));

            service.recordFromRequest(req);

            verify(executionRepository, atLeastOnce()).save(execCaptor.capture());
            assertThat(execCaptor.getAllValues().get(0).getWorkflowRunId())
                .isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000099"));
        }

        @Test
        @DisplayName("copies agentConfigSnapshot into the execution row")
        void copiesAgentConfigSnapshotIntoExecutionRow() {
            AgentObservabilityRequest req = buildBaseRequest();
            req.setSource("WORKFLOW");
            req.setMemoryEnabled(true);
            req.setAgentConfigSnapshot(Map.of(
                "provider", "snapshot-provider",
                "toolsConfig", Map.of("mode", "none"),
                "creditBudget", 12.5
            ));

            service.recordFromRequest(req);

            verify(executionRepository, atLeastOnce()).save(execCaptor.capture());
            AgentExecutionEntity exec = execCaptor.getAllValues().get(0);

            assertThat(exec.getAgentConfigSnapshot())
                .containsEntry("provider", "snapshot-provider")
                .containsEntry("model", "claude-3-sonnet")
                .containsEntry("maxTokens", 4096)
                .containsEntry("maxIterations", 10)
                .containsEntry("memoryEnabled", true)
                .containsEntry("source", "WORKFLOW")
                .containsEntry("agentEntityId", "00000000-0000-0000-0000-000000000001");
            assertThat(exec.getAgentConfigSnapshot().get("toolsConfig"))
                .isEqualTo(Map.of("mode", "none"));
            assertThat(exec.getAgentConfigSnapshot().get("creditBudget")).isEqualTo(12.5);
        }
    }

    // ==========================================================================
    // Source detection
    // ==========================================================================

    @Nested
    @DisplayName("source detection")
    class SourceDetection {

        @Test
        @DisplayName("should set source=WORKFLOW when no callerAgentId")
        void workflowSource() {
            AgentObservabilityRequest req = buildBaseRequest();

            service.recordFromRequest(req);

            verify(executionRepository, atLeastOnce()).save(execCaptor.capture());
            assertThat(execCaptor.getAllValues().get(0).getSource()).isEqualTo("WORKFLOW");
        }

        @Test
        @DisplayName("should copy inferred workflow source into the config snapshot")
        void inferredWorkflowSourceInConfigSnapshot() {
            AgentObservabilityRequest req = buildBaseRequest();
            req.setSource(null);

            service.recordFromRequest(req);

            verify(executionRepository, atLeastOnce()).save(execCaptor.capture());
            AgentExecutionEntity exec = execCaptor.getAllValues().get(0);

            assertThat(exec.getSource()).isEqualTo("WORKFLOW");
            assertThat(exec.getAgentConfigSnapshot())
                .containsEntry("source", "WORKFLOW")
                .containsEntry("provider", "anthropic")
                .containsEntry("model", "claude-3-sonnet");
        }

        @Test
        @DisplayName("should set source=SUB_AGENT when callerAgentId is present")
        void subAgentSource() {
            AgentObservabilityRequest req = buildBaseRequest();
            UUID callerId = UUID.randomUUID();
            req.setCallerAgentId(callerId);
            req.setNestingDepth(2);

            service.recordFromRequest(req);

            verify(executionRepository, atLeastOnce()).save(execCaptor.capture());
            AgentExecutionEntity exec = execCaptor.getAllValues().get(0);
            assertThat(exec.getSource()).isEqualTo("SUB_AGENT");
            assertThat(exec.getCallerAgentEntityId()).isEqualTo(callerId);
            assertThat(exec.getDepth()).isEqualTo(2);
        }
    }

    // ==========================================================================
    // Parent conversation linkage - surfaces sub-agent executions under the
    // conversation that spawned them (conversation-scoped observability view).
    // ==========================================================================

    @Nested
    @DisplayName("parent conversation linkage")
    class ParentConversationLinkage {

        @Test
        @DisplayName("persists parentConversationId when the spawn supplies it (sub-agent execution)")
        void persistsParentConversationIdWhenSet() {
            // Regression: sub-agent executions live under their OWN conversationId and
            // previously carried no link to the parent's conversation, so a
            // conversation-scoped observability view could never surface them. The
            // spawning conversation must be persisted on the row.
            AgentObservabilityRequest req = buildBaseRequest();
            req.setCallerAgentId(UUID.randomUUID());
            req.setNestingDepth(1);
            req.setConversationId("sub-agent-own-conv");
            req.setParentConversationId("parent-conv-42");

            service.recordFromRequest(req);

            verify(executionRepository, atLeastOnce()).save(execCaptor.capture());
            AgentExecutionEntity exec = execCaptor.getAllValues().get(0);
            // The sub-agent's own conversation is unchanged...
            assertThat(exec.getConversationId()).isEqualTo("sub-agent-own-conv");
            // ...and the parent conversation is now recorded as the back-reference.
            assertThat(exec.getParentConversationId()).isEqualTo("parent-conv-42");
        }

        @Test
        @DisplayName("leaves parentConversationId null for a root execution (no parent conversation)")
        void leavesParentConversationIdNullForRoot() {
            // Root executions (REST/CLI/chat/workflow) have no spawning conversation -
            // the back-reference must stay null so they are not mis-attributed to a parent.
            AgentObservabilityRequest req = buildBaseRequest();
            req.setConversationId("root-conv");
            // parentConversationId intentionally left unset

            service.recordFromRequest(req);

            verify(executionRepository, atLeastOnce()).save(execCaptor.capture());
            AgentExecutionEntity exec = execCaptor.getAllValues().get(0);
            assertThat(exec.getConversationId()).isEqualTo("root-conv");
            assertThat(exec.getParentConversationId()).isNull();
        }
    }

    // ==========================================================================
    // Source type resolution (credit-ledger charge category)
    // ==========================================================================

    @Nested
    @DisplayName("sourceType resolution - credit ledger segregation")
    class SourceTypeResolution {

        @Test
        @DisplayName("agentType=agent charges credits under sourceType=AGENT_EXECUTION")
        void agentDefault() {
            AgentObservabilityRequest req = buildBaseRequest();
            req.setAgentType("agent");
            service.recordFromRequest(req);
            verify(creditClient).consumeCredits(
                    anyString(), eq("AGENT_EXECUTION"),
                    anyString(), anyString(), anyString(),
                    anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class));
        }

        @Test
        @DisplayName("cache/reasoning counters from the request are forwarded verbatim to billing (cache-aware fix)")
        void cacheCountersForwardedToBilling() {
            // Regression for the 2026-06-11 cache-billing fix: the request's cache
            // write/read, cached-subset and reasoning counters must reach the credit
            // client so auth-service can bill them at the provider's true relative
            // price. Pre-fix, consumeCredits only received prompt+completion and
            // claude-code cache reads were billed at full input rate.
            AgentObservabilityRequest req = buildBaseRequest();
            // buildBaseRequest sets cacheCreation=100, cacheRead=50, cached=80, reasoning=30

            service.recordFromRequest(req);

            verify(creditClient).consumeCredits(
                    anyString(), anyString(), anyString(), anyString(), anyString(),
                    anyInt(), anyInt(),
                    eq(new com.apimarketplace.common.credit.LlmCacheTokens(100, 50, 80, 30)));
        }

        @Test
        @DisplayName("agentType=classify charges credits under sourceType=CLASSIFY_EXECUTION")
        void classifySource() {
            AgentObservabilityRequest req = buildBaseRequest();
            req.setAgentType("classify");
            service.recordFromRequest(req);
            verify(creditClient).consumeCredits(
                    anyString(), eq("CLASSIFY_EXECUTION"),
                    anyString(), anyString(), anyString(),
                    anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class));
        }

        @Test
        @DisplayName("agentType=guardrail charges credits under sourceType=GUARDRAIL_EXECUTION")
        void guardrailSource() {
            AgentObservabilityRequest req = buildBaseRequest();
            req.setAgentType("guardrail");
            service.recordFromRequest(req);
            verify(creditClient).consumeCredits(
                    anyString(), eq("GUARDRAIL_EXECUTION"),
                    anyString(), anyString(), anyString(),
                    anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class));
        }

        @Test
        @DisplayName("agentType=CLI charges under sourceType=CLI_SESSION (case-insensitive) - segregated from generic AGENT_EXECUTION")
        void cliSource() {
            AgentObservabilityRequest req = buildBaseRequest();
            req.setAgentType("CLI");  // CliAgentService stamps the upper-case 'CLI'; resolver lower-cases
            service.recordFromRequest(req);
            verify(creditClient).consumeCredits(
                    anyString(), eq("CLI_SESSION"),
                    anyString(), anyString(), anyString(),
                    anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class));
        }

        @Test
        @DisplayName("agentType=browser_agent charges credits under sourceType=BROWSER_AGENT_EXECUTION")
        void browserAgentSource() {
            // Browser-agent runs are LLM-driven Chromium sessions: cost
            // profile differs from chat agents (visual context tokens
            // dominate, multi-minute wall clock). Segregating them in the
            // credit ledger lets the dashboard / Usage History show "I burned
            // $X on browser sessions today" separately. Without this routing,
            // the default "AGENT_EXECUTION" would mix them with chat agent
            // spend and erase the distinction the user can see in the UI.
            AgentObservabilityRequest req = buildBaseRequest();
            req.setAgentType("browser_agent");
            service.recordFromRequest(req);
            verify(creditClient).consumeCredits(
                    anyString(), eq("BROWSER_AGENT_EXECUTION"),
                    anyString(), anyString(), anyString(),
                    anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class));
        }

        @Test
        @DisplayName("Stage 5.4: agentType=compaction_summary charges under sourceType=COMPACTION_SUMMARY")
        void compactionSummarySource() {
            // The COLD summariser bills the tenant too; Grafana panel #10 +
            // the credit-ledger UI segregate these from primary agent cost.
            AgentObservabilityRequest req = buildBaseRequest();
            req.setAgentType("compaction_summary");
            service.recordFromRequest(req);
            verify(creditClient).consumeCredits(
                    anyString(), eq("COMPACTION_SUMMARY"),
                    anyString(), anyString(), anyString(),
                    anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class));
        }

        @Test
        @DisplayName("Stage 5.4: agentType=cold_summary (alias) also maps to COMPACTION_SUMMARY")
        void coldSummaryAliasSource() {
            // Defensive alias - the summariser caller may emit either string.
            // If this mapping drifts, costs bleed into AGENT_EXECUTION and
            // the Grafana segregation breaks silently.
            AgentObservabilityRequest req = buildBaseRequest();
            req.setAgentType("cold_summary");
            service.recordFromRequest(req);
            verify(creditClient).consumeCredits(
                    anyString(), eq("COMPACTION_SUMMARY"),
                    anyString(), anyString(), anyString(),
                    anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class));
        }

        @Test
        @DisplayName("agentType=null defaults to AGENT_EXECUTION")
        void nullAgentTypeDefault() {
            AgentObservabilityRequest req = buildBaseRequest();
            req.setAgentType(null);
            service.recordFromRequest(req);
            verify(creditClient).consumeCredits(
                    anyString(), eq("AGENT_EXECUTION"),
                    anyString(), anyString(), anyString(),
                    anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class));
        }

        @Test
        @DisplayName("sourceType matching is case-insensitive - mixed case still routes")
        void caseInsensitiveSourceType() {
            // Pin that the toLowerCase() normaliser survives - otherwise a
            // caller that writes "Classify" instead of "classify" would
            // silently fall through to AGENT_EXECUTION.
            AgentObservabilityRequest req = buildBaseRequest();
            req.setAgentType("CLASSIFY");
            service.recordFromRequest(req);
            verify(creditClient).consumeCredits(
                    anyString(), eq("CLASSIFY_EXECUTION"),
                    anyString(), anyString(), anyString(),
                    anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class));
        }
    }

    // ==========================================================================
    // Iterations
    // ==========================================================================

    @Nested
    @DisplayName("iterations")
    class Iterations {

        @Test
        @DisplayName("should save iteration records with correct fields")
        void saveIterations() {
            AgentObservabilityRequest req = buildBaseRequest();

            AgentObservabilityRequest.IterationData iter1 = new AgentObservabilityRequest.IterationData();
            iter1.setIterationNumber(1);
            iter1.setToolCallCount(2);
            iter1.setPromptTokens(500);
            iter1.setCompletionTokens(200);
            iter1.setCacheCreationTokens(50);
            iter1.setCacheReadTokens(25);
            iter1.setCachedTokens(40);
            iter1.setReasoningTokens(15);
            iter1.setDurationMs(2000);
            iter1.setFinishReason("tool_use");

            AgentObservabilityRequest.IterationData iter2 = new AgentObservabilityRequest.IterationData();
            iter2.setIterationNumber(2);
            iter2.setToolCallCount(1);
            iter2.setPromptTokens(500);
            iter2.setCompletionTokens(300);
            iter2.setDurationMs(3000);
            iter2.setFinishReason("end_turn");

            req.setIterations(List.of(iter1, iter2));

            service.recordFromRequest(req);

            verify(iterationRepository).saveAll(iterationsCaptor.capture());
            List<AgentExecutionIterationEntity> saved = iterationsCaptor.getValue();

            assertThat(saved).hasSize(2);

            // First iteration
            assertThat(saved.get(0).getIterationNumber()).isEqualTo(1);
            assertThat(saved.get(0).getToolCallCount()).isEqualTo(2);
            assertThat(saved.get(0).getPromptTokens()).isEqualTo(500);
            assertThat(saved.get(0).getCompletionTokens()).isEqualTo(200);
            assertThat(saved.get(0).getCacheCreationTokens()).isEqualTo(50);
            assertThat(saved.get(0).getCacheReadTokens()).isEqualTo(25);
            assertThat(saved.get(0).getCachedTokens()).isEqualTo(40);
            assertThat(saved.get(0).getReasoningTokens()).isEqualTo(15);
            assertThat(saved.get(0).getDurationMs()).isEqualTo(2000);
            assertThat(saved.get(0).getFinishReason()).isEqualTo("tool_use");
            assertThat(saved.get(0).isFinal()).isFalse();

            // Second iteration (should be final)
            assertThat(saved.get(1).getIterationNumber()).isEqualTo(2);
            assertThat(saved.get(1).isFinal()).isTrue();
            assertThat(saved.get(1).getFinishReason()).isEqualTo("end_turn");
        }

        @Test
        @DisplayName("should skip iterations when list is null")
        void noIterations() {
            AgentObservabilityRequest req = buildBaseRequest();
            req.setIterations(null);

            service.recordFromRequest(req);

            verify(iterationRepository, never()).saveAll(any());
        }
    }

    // ==========================================================================
    // Messages
    // ==========================================================================

    @Nested
    @DisplayName("messages")
    class Messages {

        @Test
        @DisplayName("should save messages with iteration number tracking")
        void saveMessagesWithIterationTracking() {
            AgentObservabilityRequest req = buildBaseRequest();

            AgentObservabilityRequest.MessageData sysMsg = new AgentObservabilityRequest.MessageData();
            sysMsg.setRole("SYSTEM");
            sysMsg.setContent("You are helpful");

            AgentObservabilityRequest.MessageData userMsg = new AgentObservabilityRequest.MessageData();
            userMsg.setRole("USER");
            userMsg.setContent("Hello");

            AgentObservabilityRequest.MessageData assistMsg1 = new AgentObservabilityRequest.MessageData();
            assistMsg1.setRole("ASSISTANT");
            assistMsg1.setContent("I'll search for that");

            AgentObservabilityRequest.MessageData toolMsg = new AgentObservabilityRequest.MessageData();
            toolMsg.setRole("TOOL");
            toolMsg.setContent("search results");
            toolMsg.setToolCallId("call_123");
            toolMsg.setToolName("web_search");

            AgentObservabilityRequest.MessageData assistMsg2 = new AgentObservabilityRequest.MessageData();
            assistMsg2.setRole("ASSISTANT");
            assistMsg2.setContent("Here are the results");

            req.setMessages(List.of(sysMsg, userMsg, assistMsg1, toolMsg, assistMsg2));

            service.recordFromRequest(req);

            verify(messageRepository).saveAll(messagesCaptor.capture());
            List<AgentExecutionMessageEntity> saved = messagesCaptor.getValue();

            assertThat(saved).hasSize(5);

            // SYSTEM message: iterationNumber = null
            assertThat(saved.get(0).getRole()).isEqualTo("SYSTEM");
            assertThat(saved.get(0).getIterationNumber()).isNull();
            assertThat(saved.get(0).getSequenceNumber()).isEqualTo(0);

            // USER message: iterationNumber = 0 (no ASSISTANT yet)
            assertThat(saved.get(1).getRole()).isEqualTo("USER");
            assertThat(saved.get(1).getIterationNumber()).isEqualTo(0);

            // First ASSISTANT: iterationNumber = 1
            assertThat(saved.get(2).getRole()).isEqualTo("ASSISTANT");
            assertThat(saved.get(2).getIterationNumber()).isEqualTo(1);

            // TOOL: iterationNumber = 1, has toolCallId
            assertThat(saved.get(3).getRole()).isEqualTo("TOOL");
            assertThat(saved.get(3).getIterationNumber()).isEqualTo(1);
            assertThat(saved.get(3).getToolCallId()).isEqualTo("call_123");
            assertThat(saved.get(3).getToolName()).isEqualTo("web_search");

            // Second ASSISTANT: iterationNumber = 2
            assertThat(saved.get(4).getRole()).isEqualTo("ASSISTANT");
            assertThat(saved.get(4).getIterationNumber()).isEqualTo(2);
        }

        @Test
        @DisplayName("should use DTO-provided iterationNumber when available")
        void useDtoIterationNumber() {
            AgentObservabilityRequest req = buildBaseRequest();

            AgentObservabilityRequest.MessageData msg = new AgentObservabilityRequest.MessageData();
            msg.setRole("ASSISTANT");
            msg.setContent("hello");
            msg.setIterationNumber(5);

            req.setMessages(List.of(msg));

            service.recordFromRequest(req);

            verify(messageRepository).saveAll(messagesCaptor.capture());
            assertThat(messagesCaptor.getValue().get(0).getIterationNumber()).isEqualTo(5);
        }
    }

    // ==========================================================================
    // Tool calls
    // ==========================================================================

    @Nested
    @DisplayName("tool calls")
    class ToolCalls {

        @Test
        @DisplayName("should save tool calls and compute stats")
        void saveToolCallsAndComputeStats() {
            AgentObservabilityRequest req = buildBaseRequest();

            AgentObservabilityRequest.ToolCallData tc1 = new AgentObservabilityRequest.ToolCallData();
            tc1.setToolCallId("call_1");
            tc1.setToolName("web_search");
            tc1.setArguments(Map.of("query", "test"));
            tc1.setSuccess(true);
            tc1.setResult("search results");
            tc1.setDurationMs(500);
            tc1.setIterationNumber(1);

            AgentObservabilityRequest.ToolCallData tc2 = new AgentObservabilityRequest.ToolCallData();
            tc2.setToolCallId("call_2");
            tc2.setToolName("web_search");
            tc2.setArguments(Map.of("query", "test"));
            tc2.setSuccess(true);
            tc2.setResult("more results");
            tc2.setDurationMs(600);
            tc2.setIterationNumber(1);

            AgentObservabilityRequest.ToolCallData tc3 = new AgentObservabilityRequest.ToolCallData();
            tc3.setToolCallId("call_3");
            tc3.setToolName("fetch_url");
            tc3.setArguments(Map.of("url", "http://example.com"));
            tc3.setSuccess(false);
            tc3.setResult(null);
            tc3.setDurationMs(3000);
            tc3.setIterationNumber(2);

            req.setToolCalls(List.of(tc1, tc2, tc3));

            service.recordFromRequest(req);

            // Verify tool call entities saved
            verify(toolCallRepository).saveAll(toolCallsCaptor.capture());
            List<AgentExecutionToolCallEntity> saved = toolCallsCaptor.getValue();
            assertThat(saved).hasSize(3);

            assertThat(saved.get(0).getToolCallId()).isEqualTo("call_1");
            assertThat(saved.get(0).getToolName()).isEqualTo("web_search");
            assertThat(saved.get(0).isSuccess()).isTrue();
            assertThat(saved.get(0).getDurationMs()).isEqualTo(500);
            assertThat(saved.get(0).getSequenceNumber()).isEqualTo(0);

            assertThat(saved.get(2).getToolName()).isEqualTo("fetch_url");
            assertThat(saved.get(2).isSuccess()).isFalse();

            // Verify execution stats
            verify(executionRepository, atLeastOnce()).save(execCaptor.capture());
            AgentExecutionEntity exec = execCaptor.getValue();
            assertThat(exec.getSuccessfulToolCalls()).isEqualTo(2);
            assertThat(exec.getFailedToolCalls()).isEqualTo(1);
            assertThat(exec.getToolSequence()).isEqualTo("web_search,web_search,fetch_url");
            assertThat(exec.getDistinctTools()).containsExactly("web_search", "fetch_url");
        }

        @Test
        @DisplayName("should detect repeat tool calls with same name and arguments")
        void detectRepeatToolCalls() {
            AgentObservabilityRequest req = buildBaseRequest();

            AgentObservabilityRequest.ToolCallData tc1 = new AgentObservabilityRequest.ToolCallData();
            tc1.setToolCallId("call_1");
            tc1.setToolName("web_search");
            tc1.setArguments(Map.of("query", "test"));
            tc1.setSuccess(true);
            tc1.setResult("r1");
            tc1.setIterationNumber(1);

            AgentObservabilityRequest.ToolCallData tc2 = new AgentObservabilityRequest.ToolCallData();
            tc2.setToolCallId("call_2");
            tc2.setToolName("web_search");
            tc2.setArguments(Map.of("query", "test"));
            tc2.setSuccess(true);
            tc2.setResult("r2");
            tc2.setIterationNumber(2);

            AgentObservabilityRequest.ToolCallData tc3 = new AgentObservabilityRequest.ToolCallData();
            tc3.setToolCallId("call_3");
            tc3.setToolName("fetch_url");
            tc3.setArguments(Map.of("url", "http://x"));
            tc3.setSuccess(true);
            tc3.setResult("r3");
            tc3.setIterationNumber(3);

            req.setToolCalls(List.of(tc1, tc2, tc3));

            service.recordFromRequest(req);

            verify(toolCallRepository).saveAll(toolCallsCaptor.capture());
            List<AgentExecutionToolCallEntity> saved = toolCallsCaptor.getValue();

            // First call: not a repeat
            assertThat(saved.get(0).isRepeat()).isFalse();
            assertThat(saved.get(0).getConsecutiveCount()).isEqualTo(1);

            // Second call: same tool+args = repeat
            assertThat(saved.get(1).isRepeat()).isTrue();
            assertThat(saved.get(1).getConsecutiveCount()).isEqualTo(2);

            // Third call: different tool = not a repeat
            assertThat(saved.get(2).isRepeat()).isFalse();
            assertThat(saved.get(2).getConsecutiveCount()).isEqualTo(1);
        }
    }

    // ==========================================================================
    // Counter updates
    // ==========================================================================

    @Nested
    @DisplayName("agent entity counter updates")
    class CounterUpdates {

        @Test
        @DisplayName("should increment counters on successful execution")
        void incrementCountersSuccess() {
            AgentObservabilityRequest req = buildBaseRequest();
            req.setStatus("COMPLETED");

            service.recordFromRequest(req);

            verify(executionRepository).incrementCounters(
                eq(UUID.fromString("00000000-0000-0000-0000-000000000001")),
                eq(1500L),       // totalTokens
                eq(3),           // totalToolCalls
                eq(1),           // success=1
                eq(0),           // failure=0
                eq(5000L),       // durationMs
                any(Instant.class)
            );
        }

        @Test
        @DisplayName("should increment failure counter on failed execution")
        void incrementCountersFailure() {
            AgentObservabilityRequest req = buildBaseRequest();
            req.setStatus("FAILED");

            service.recordFromRequest(req);

            verify(executionRepository).incrementCounters(
                any(UUID.class),
                anyLong(),
                anyInt(),
                eq(0),           // success=0
                eq(1),           // failure=1
                anyLong(),
                any(Instant.class)
            );
        }

        @Test
        @DisplayName("should resolve STOPPED_BY_USER as CANCELLED status and not count as failure")
        void stoppedByUserResolvedAsCancelled() {
            AgentObservabilityRequest req = buildBaseRequest();
            req.setStatus("FAILED");
            req.setStopReason("STOPPED_BY_USER");

            service.recordFromRequest(req);

            // Verify status is resolved to CANCELLED
            verify(executionRepository, atLeastOnce()).save(execCaptor.capture());
            assertThat(execCaptor.getAllValues().get(0).getStatus()).isEqualTo("CANCELLED");

            // Verify counters: success=0, failure=0 (CANCELLED is neither success nor failure)
            verify(executionRepository).incrementCounters(
                any(UUID.class),
                anyLong(),
                anyInt(),
                eq(0),           // success=0
                eq(0),           // failure=0
                anyLong(),
                any(Instant.class)
            );
        }

        @Test
        @DisplayName("should resolve TIMEOUT as COMPLETED (partial) status, not CANCELLED")
        void timeoutResolvedAsPartialCompleted() {
            // Per the agent-stop-reason contract, TIMEOUT is `partial`: the agent ran some
            // iterations and may have produced usable output before being cut off, so it is
            // categorised alongside MAX_ITERATIONS / BUDGET_EXHAUSTED / LOOP_DETECTED rather
            // than as a hard cancellation. The raw stop_reason column still records TIMEOUT
            // for forensics; only the high-level status changes from CANCELLED to COMPLETED.
            AgentObservabilityRequest req = buildBaseRequest();
            req.setStatus("FAILED");
            req.setStopReason("TIMEOUT");

            service.recordFromRequest(req);

            verify(executionRepository, atLeastOnce()).save(execCaptor.capture());
            AgentExecutionEntity persisted = execCaptor.getAllValues().get(0);
            assertThat(persisted.getStatus()).isEqualTo("COMPLETED");
            assertThat(persisted.getStopReason()).isEqualTo("TIMEOUT");

            verify(executionRepository).incrementCounters(
                any(UUID.class),
                anyLong(),
                anyInt(),
                eq(1),           // success=1 - partial outcomes count as successful runs (status COMPLETED)
                eq(0),           // failure=0 - they are not failures
                anyLong(),
                any(Instant.class)
            );
        }

        @Test
        @DisplayName("should keep FAILED status for real errors (non-cancellation stop reasons)")
        void realErrorStaysAsFailed() {
            AgentObservabilityRequest req = buildBaseRequest();
            req.setStatus("FAILED");
            req.setStopReason("ERROR");

            service.recordFromRequest(req);

            verify(executionRepository, atLeastOnce()).save(execCaptor.capture());
            assertThat(execCaptor.getAllValues().get(0).getStatus()).isEqualTo("FAILED");

            verify(executionRepository).incrementCounters(
                any(UUID.class),
                anyLong(),
                anyInt(),
                eq(0),           // success=0
                eq(1),           // failure=1
                anyLong(),
                any(Instant.class)
            );
        }

        @Test
        @DisplayName("should skip counter update when agentEntityId is null")
        void skipCounterUpdateWhenNoAgent() {
            AgentObservabilityRequest req = buildBaseRequest();
            req.setAgentEntityId(null);

            service.recordFromRequest(req);

            verify(executionRepository, never()).incrementCounters(any(), anyLong(), anyInt(), anyInt(), anyInt(), anyLong(), any());
        }
    }

    // ==========================================================================
    // Credit consumption
    // ==========================================================================

    @Nested
    @DisplayName("credit consumption")
    class CreditConsumption {

        @Test
        @DisplayName("should consume credits for agent execution")
        void consumeCredits() {
            AgentObservabilityRequest req = buildBaseRequest();

            service.recordFromRequest(req);

            verify(creditClient).consumeCredits(
                eq("tenant-1"),
                eq("AGENT_EXECUTION"),
                any(String.class),
                eq("anthropic"),
                eq("claude-3-sonnet"),
                eq(1000),
                eq(500), any(com.apimarketplace.common.credit.LlmCacheTokens.class)
            );
        }

        @Test
        @DisplayName("should use CLASSIFY_EXECUTION for classify agent type")
        void classifySourceType() {
            AgentObservabilityRequest req = buildBaseRequest();
            req.setAgentType("classify");

            service.recordFromRequest(req);

            verify(creditClient).consumeCredits(
                any(), eq("CLASSIFY_EXECUTION"), any(), any(), any(), anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class)
            );
        }

        @Test
        @DisplayName("should use GUARDRAIL_EXECUTION for guardrail agent type")
        void guardrailSourceType() {
            AgentObservabilityRequest req = buildBaseRequest();
            req.setAgentType("guardrail");

            service.recordFromRequest(req);

            verify(creditClient).consumeCredits(
                any(), eq("GUARDRAIL_EXECUTION"), any(), any(), any(), anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class)
            );
        }

        @Test
        @DisplayName("should increment creditsConsumed on agent entity when creditsUsed > 0")
        void incrementsCreditsConsumedOnAgent() {
            AgentObservabilityRequest req = buildBaseRequest();
            Map<String, Object> creditResult = new java.util.HashMap<>();
            creditResult.put("creditsUsed", 2.5);
            when(creditClient.consumeCredits(any(), any(), any(), any(), any(), anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class)))
                .thenReturn(creditResult);

            service.recordFromRequest(req);

            verify(agentRepository).incrementCreditsConsumed(
                eq(UUID.fromString("00000000-0000-0000-0000-000000000001")),
                eq(java.math.BigDecimal.valueOf(2.5))
            );
        }

        @Test
        @DisplayName("should NOT increment when creditsUsed is 0")
        void doesNotIncrementWhenCreditsZero() {
            AgentObservabilityRequest req = buildBaseRequest();
            Map<String, Object> creditResult = new java.util.HashMap<>();
            creditResult.put("creditsUsed", 0);
            when(creditClient.consumeCredits(any(), any(), any(), any(), any(), anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class)))
                .thenReturn(creditResult);

            service.recordFromRequest(req);

            verify(agentRepository, never()).incrementCreditsConsumed(any(), any());
        }

        @Test
        @DisplayName("should NOT increment when creditResult is null")
        void doesNotIncrementWhenCreditResultNull() {
            AgentObservabilityRequest req = buildBaseRequest();
            when(creditClient.consumeCredits(any(), any(), any(), any(), any(), anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class)))
                .thenReturn(null);

            service.recordFromRequest(req);

            verify(agentRepository, never()).incrementCreditsConsumed(any(), any());
        }

        @Test
        @DisplayName("should NOT increment when agentEntityId is null")
        void doesNotIncrementWhenAgentEntityIdNull() {
            AgentObservabilityRequest req = buildBaseRequest();
            req.setAgentEntityId(null);
            Map<String, Object> creditResult = new java.util.HashMap<>();
            creditResult.put("creditsUsed", 5.0);
            when(creditClient.consumeCredits(any(), any(), any(), any(), any(), anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class)))
                .thenReturn(creditResult);

            service.recordFromRequest(req);

            verify(agentRepository, never()).incrementCreditsConsumed(any(), any());
        }

        @Test
        @DisplayName("should swallow exception from incrementCreditsConsumed without failing")
        void swallowsIncrementException() {
            AgentObservabilityRequest req = buildBaseRequest();
            Map<String, Object> creditResult = new java.util.HashMap<>();
            creditResult.put("creditsUsed", 3.0);
            when(creditClient.consumeCredits(any(), any(), any(), any(), any(), anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class)))
                .thenReturn(creditResult);
            org.mockito.Mockito.doThrow(new RuntimeException("DB down"))
                .when(agentRepository).incrementCreditsConsumed(any(), any());

            // Should not throw - error is logged and swallowed
            service.recordFromRequest(req);

            verify(agentRepository).incrementCreditsConsumed(any(), any());
        }

        @Test
        @DisplayName("Persists dead-letter row on 402 soft rejection so token usage survives the audit trail")
        void persistsDeadLetterOn402Rejection() {
            // Prod incident: bridge chat burned 2M Opus tokens, auth-service returned 402
            // insufficient credits. consumeCredits returns {success=false, error="402 ..."}
            // without throwing → the existing catch(Exception) branch never fired → no ledger
            // row, no dead-letter row. This test pins the new rejection-path persistence.
            AgentObservabilityRequest req = buildBaseRequest();
            Map<String, Object> rejected = new java.util.HashMap<>();
            rejected.put("success", false);
            rejected.put("error", "402 Insufficient credits");
            when(creditClient.consumeCredits(any(), any(), any(), any(), any(), anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class)))
                .thenReturn(rejected);

            service.recordFromRequest(req);

            verify(creditClient).persistRejection(
                eq("tenant-1"),
                eq("AGENT_EXECUTION"),
                any(String.class),
                eq("anthropic"),
                eq("claude-3-sonnet"),
                eq(1000),
                eq(500),
                eq("402 Insufficient credits")
            );
        }

        @Test
        @DisplayName("Does not persist dead-letter row when credit consumption succeeds")
        void doesNotPersistDeadLetterOnSuccess() {
            AgentObservabilityRequest req = buildBaseRequest();
            Map<String, Object> success = new java.util.HashMap<>();
            success.put("success", true);
            success.put("creditsUsed", 1.0);
            when(creditClient.consumeCredits(any(), any(), any(), any(), any(), anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class)))
                .thenReturn(success);

            service.recordFromRequest(req);

            verify(creditClient, never()).persistRejection(
                any(), any(), any(), any(), any(), anyInt(), anyInt(), any());
        }
    }

    // ==========================================================================
    // Cascade reservation settle (§4.5 AGENT_BUDGET_HIERARCHY.md)
    // ==========================================================================

    @Nested
    @DisplayName("cascade reservation settle")
    class CascadeReservationSettle {

        private AgentObservabilityRequest requestWithChain(
                List<UUID> chain, BigDecimal reserved) {
            AgentObservabilityRequest req = buildBaseRequest();
            UUID caller = chain.get(0);
            req.setCallerAgentId(caller);
            req.setCallerChain(chain);
            req.setReservedAmount(reserved);
            req.setNestingDepth(1);
            return req;
        }

        @Test
        @DisplayName("should settle with actual cost when creditsUsed > 0")
        void settlesWithActualCostOnSuccess() {
            List<UUID> chain = List.of(UUID.randomUUID(), UUID.randomUUID());
            BigDecimal reserved = new BigDecimal("10.0");
            AgentObservabilityRequest req = requestWithChain(chain, reserved);

            Map<String, Object> creditResult = new java.util.HashMap<>();
            creditResult.put("creditsUsed", 2.5);
            when(creditClient.consumeCredits(any(), any(), any(), any(), any(), anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class)))
                .thenReturn(creditResult);

            service.recordFromRequest(req);

            // actualForSettle should equal the real creditsUsed (2.5), reserved stays 10
            // → the settle UPDATE refunds (10 - 2.5) = 7.5 back to each ancestor.
            verify(budgetReservationService).settleReservationChain(
                eq(chain), eq(reserved), eq(BigDecimal.valueOf(2.5)));
        }

        @Test
        @DisplayName("should settle with ZERO actual when creditsUsed == 0 (full refund)")
        void settlesWithZeroOnZeroCostSuccess() {
            List<UUID> chain = List.of(UUID.randomUUID());
            BigDecimal reserved = new BigDecimal("5.0");
            AgentObservabilityRequest req = requestWithChain(chain, reserved);

            Map<String, Object> creditResult = new java.util.HashMap<>();
            creditResult.put("creditsUsed", 0);
            when(creditClient.consumeCredits(any(), any(), any(), any(), any(), anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class)))
                .thenReturn(creditResult);

            service.recordFromRequest(req);

            // Zero-cost run: actualForSettle = 0 so the full reserved amount is refunded.
            // Settle is called OUTSIDE the `creditsUsed > 0` guard specifically to cover this.
            verify(budgetReservationService).settleReservationChain(
                eq(chain), eq(reserved), eq(BigDecimal.valueOf(0.0)));
        }

        @Test
        @DisplayName("should still settle with ZERO actual when credit consumption fails (dead-letter)")
        void settlesWithZeroWhenCreditConsumptionThrows() {
            List<UUID> chain = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
            BigDecimal reserved = new BigDecimal("100");
            AgentObservabilityRequest req = requestWithChain(chain, reserved);

            // Credit client blows up - persistToDeadLetter handles the cost side, but the
            // cascade reservation still MUST be settled or the ancestor chain leaks.
            when(creditClient.consumeCredits(any(), any(), any(), any(), any(), anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class)))
                .thenThrow(new RuntimeException("billing pipeline down"));

            service.recordFromRequest(req);

            // actualForSettle was never updated (still BigDecimal.ZERO default) →
            // settle refunds the full reservation across every ancestor.
            verify(budgetReservationService).settleReservationChain(
                eq(chain), eq(reserved), eq(BigDecimal.ZERO));
        }

        @Test
        @DisplayName("should NOT call settle when chain is empty (root invocation)")
        void doesNotSettleOnRootInvocation() {
            AgentObservabilityRequest req = buildBaseRequest();
            req.setCallerChain(List.of());
            req.setReservedAmount(new BigDecimal("10"));

            service.recordFromRequest(req);

            verify(budgetReservationService, never()).settleReservationChain(any(), any(), any());
        }

        @Test
        @DisplayName("should NOT call settle when reservedAmount is null")
        void doesNotSettleWhenReservedAmountNull() {
            AgentObservabilityRequest req = buildBaseRequest();
            req.setCallerChain(List.of(UUID.randomUUID()));
            req.setReservedAmount(null);

            service.recordFromRequest(req);

            verify(budgetReservationService, never()).settleReservationChain(any(), any(), any());
        }

        @Test
        @DisplayName("should NOT call settle when reservedAmount is zero")
        void doesNotSettleWhenReservedAmountZero() {
            AgentObservabilityRequest req = buildBaseRequest();
            req.setCallerChain(List.of(UUID.randomUUID()));
            req.setReservedAmount(BigDecimal.ZERO);

            service.recordFromRequest(req);

            verify(budgetReservationService, never()).settleReservationChain(any(), any(), any());
        }

        @Test
        @DisplayName("should swallow exception from settleReservationChain without failing")
        void swallowsSettleException() {
            List<UUID> chain = List.of(UUID.randomUUID());
            BigDecimal reserved = new BigDecimal("10");
            AgentObservabilityRequest req = requestWithChain(chain, reserved);

            Map<String, Object> creditResult = new java.util.HashMap<>();
            creditResult.put("creditsUsed", 1.0);
            when(creditClient.consumeCredits(any(), any(), any(), any(), any(), anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class)))
                .thenReturn(creditResult);
            org.mockito.Mockito.doThrow(new RuntimeException("settle failed"))
                .when(budgetReservationService).settleReservationChain(any(), any(), any());

            // Must not throw - settle failure is logged and startup cleanup is the safety net.
            service.recordFromRequest(req);

            verify(budgetReservationService).settleReservationChain(any(), any(), any());
        }
    }

    // ==========================================================================
    // Task-link resilience - a task hard-deleted between agent dispatch and this
    // end-of-run record must NOT make the agent_executions INSERT violate
    // fk_agent_executions_task_id (which aborts the recording transaction).
    // ==========================================================================

    @Nested
    @DisplayName("task-link resilience (fk_agent_executions_task_id)")
    class TaskLinkResilience {

        private final UUID taskId = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

        @BeforeEach
        void wireTaskRepository() {
            // taskRepository is field-injected on the service (@Autowired required=false), so it is
            // absent in the constructor-built service from the outer setUp(); wire it here only.
            org.springframework.test.util.ReflectionTestUtils.setField(service, "taskRepository", taskRepository);
        }

        @Test
        @DisplayName("nulls task_id on the execution row when the task no longer exists (avoids the FK-violation INSERT)")
        void nullsTaskIdWhenTaskGone() {
            AgentObservabilityRequest req = buildBaseRequest();
            req.setTaskId(taskId);
            when(taskRepository.lockTaskRowIfExists(taskId)).thenReturn(Optional.empty()); // task gone -> no lock

            service.recordFromRequest(req);

            verify(executionRepository, atLeastOnce()).save(execCaptor.capture());
            // Pre-fix the row carried task_id=taskId and the INSERT violated the FK; post-fix it is
            // recorded with a NULL link (the intended ON DELETE SET NULL post-delete state). Assert
            // EVERY captured save (the header is saved twice on the same instance) so a future edit
            // that re-mutated task_id between the saves could not slip past.
            assertThat(execCaptor.getAllValues())
                .isNotEmpty()
                .allSatisfy(saved -> assertThat(saved.getTaskId()).isNull());
            verify(taskRepository).lockTaskRowIfExists(taskId);
        }

        @Test
        @DisplayName("keeps task_id when the task still exists (no spurious unlink on the happy path)")
        void keepsTaskIdWhenTaskExists() {
            AgentObservabilityRequest req = buildBaseRequest();
            req.setTaskId(taskId);
            when(taskRepository.lockTaskRowIfExists(taskId)).thenReturn(Optional.of(1)); // task exists -> row locked

            service.recordFromRequest(req);

            verify(executionRepository, atLeastOnce()).save(execCaptor.capture());
            assertThat(execCaptor.getAllValues().get(0).getTaskId()).isEqualTo(taskId);
        }

        @Test
        @DisplayName("defense-in-depth: even an unexpected FK violation from the save degrades gracefully (swallowed, credits still consumed)")
        void fkViolationFromSaveStillDegradesGracefully() {
            // The FOR KEY SHARE lock closes the delete-race, so the save should not FK-violate in
            // practice. This pins the safety net for any OTHER source of a DataIntegrityViolation on
            // the save: recordFromRequest must swallow it (not propagate) and the credit-consumption
            // side must still run, so token usage is never lost.
            AgentObservabilityRequest req = buildBaseRequest();
            req.setTaskId(taskId);
            when(taskRepository.lockTaskRowIfExists(taskId)).thenReturn(Optional.of(1));
            when(executionRepository.save(any())).thenThrow(
                new org.springframework.dao.DataIntegrityViolationException(
                    "ERROR: insert or update on table \"agent_executions\" violates foreign key "
                        + "constraint \"fk_agent_executions_task_id\""));

            // Must NOT propagate out of recordFromRequest.
            org.assertj.core.api.Assertions.assertThatCode(() -> service.recordFromRequest(req))
                .doesNotThrowAnyException();

            // Credit consumption still happens - it runs after the swallowed recording, so token
            // usage is never lost to the residual race (the "not worse" guarantee).
            verify(creditClient).consumeCredits(
                anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class));
        }
    }
}
