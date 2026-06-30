package com.apimarketplace.orchestrator.execution.v2.async;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.AgentObservabilityRequest;
import com.apimarketplace.orchestrator.domain.execution.AgentResultMessage;
import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.domain.workflow.Agent;
import com.apimarketplace.orchestrator.domain.workflow.StepExecutionResult;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.split.SplitContextManager;
import com.apimarketplace.orchestrator.services.completion.StepCompletionOrchestrator;
import com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused unit tests for {@link AgentAsyncCompletionService#injectAgentMetadata}.
 *
 * <h2>Why this test exists</h2>
 * <p>The async worker path delivers raw {@code ClassifyResponseDto} / {@code GuardrailResponseDto}
 * JSON as the result payload. Those DTOs don't carry the fields
 * {@code StepDataPersistenceService.enrichAgentFields} keys off to derive
 * {@code selected_branch} - specifically {@code node_type},
 * {@code selected_category_index}, and the snake_case {@code selected_category} alias.
 * The inline path adds them in {@code AgentNode.createClassifySuccessResult} /
 * {@code createGuardrailSuccessResult}; we mirror that here.</p>
 *
 * <p>Missing this wrapping was the root cause of the "classify runs with 0 routed items"
 * bug observed in run {@code *_512defbb}, where 5 async guardrail deliveries left
 * {@code selected_branch} null on every row, causing
 * {@code SplitAwareNodeExecutor.getRoutedItemIndices} to return an empty set and spin
 * the successor sweep forever.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentAsyncCompletionService - output shape normalization")
class AgentAsyncCompletionServiceOutputShapeTest {

    @Mock private PendingAgentRegistry registry;
    @Mock private StepCompletionOrchestrator stepCompletionOrchestrator;
    @Mock private SplitContextManager splitContextManager;
    @Mock private RunningNodeTracker runningNodeTracker;
    @Mock private SplitCoalesceTracker splitCoalesceTracker;
    @Mock private com.apimarketplace.orchestrator.repository.WorkflowRunRepository runRepository;

    private AgentAsyncCompletionService service;

    @BeforeEach
    void setUp() {
        service = new AgentAsyncCompletionService(
            registry,
            stepCompletionOrchestrator,
            splitContextManager,
            runningNodeTracker,
            splitCoalesceTracker,
            new com.apimarketplace.orchestrator.execution.v2.services.NodeSearchService(),
            runRepository);
    }

    @AfterEach
    void tearDown() throws Exception {
        setAgentClient(null);
    }

    private PendingAgent agent(String agentType, String nodeId, int itemIndex) {
        return new PendingAgent(
            "corr-" + agentType,
            "run-1",
            nodeId,
            nodeId.substring(nodeId.indexOf(':') + 1),
            "trigger:default",
            0,
            itemIndex,
            String.valueOf(itemIndex),
            agentType,
            "tenant-1",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            Instant.now());
    }

    private WorkflowExecution executionWithAgent(Agent planAgent) {
        return executionWithAgent(planAgent, null, null);
    }

    private WorkflowExecution executionWithAgent(Agent planAgent, UUID workflowId, UUID workflowRunId) {
        WorkflowExecution execution = mock(WorkflowExecution.class);
        WorkflowPlan plan = mock(WorkflowPlan.class);
        lenient().when(execution.getPlan()).thenReturn(plan);
        lenient().when(execution.getWorkflowRunId()).thenReturn(workflowRunId);
        if (workflowId != null) {
            lenient().when(plan.getId()).thenReturn(workflowId.toString());
        }
        if (planAgent != null) {
            lenient().when(plan.findAgent(planAgent.getNormalizedKey()))
                .thenReturn(Optional.of(planAgent));
        }
        return execution;
    }

    private void setAgentClient(AgentClient agentClient) throws Exception {
        Field field = AgentAsyncCompletionService.class.getDeclaredField("agentClient");
        field.setAccessible(true);
        field.set(service, agentClient);
    }

    private void invokeRecordAsyncObservability(
            WorkflowExecution execution,
            PendingAgent pending,
            AgentResultMessage result,
            StepExecutionResult stepResult) throws Exception {
        Method method = AgentAsyncCompletionService.class.getDeclaredMethod(
            "recordAsyncObservability",
            WorkflowExecution.class,
            PendingAgent.class,
            AgentResultMessage.class,
            StepExecutionResult.class);
        method.setAccessible(true);
        method.invoke(service, execution, pending, result, stepResult);
    }

    @Nested
    @DisplayName("guardrail")
    class Guardrail {

        @Test
        @DisplayName("stamps node_type=GUARDRAIL so enrichAgentFields can derive selected_branch from passed")
        void stampsNodeTypeAndKeepsPassedField() {
            Map<String, Object> output = new HashMap<>();
            // DTO field is already `passed` (same name in camelCase and snake_case),
            // so the only thing missing is node_type.
            output.put("passed", true);
            output.put("violations", List.of());

            service.injectAgentMetadata(output, executionWithAgent(null), agent("guardrail", "agent:check_safety", 2));

            assertThat(output).containsEntry("node_type", "GUARDRAIL");
            assertThat(output).containsEntry("passed", true);
            assertThat(output).containsEntry("item_index", 2);
            assertThat(output).containsEntry("item_id", "2");
        }

        @Test
        @DisplayName("derives response='Content passed all checks' when passed=true")
        void derivesResponsePass() {
            Map<String, Object> output = new HashMap<>();
            output.put("passed", true);
            output.put("violations", List.of());

            service.injectAgentMetadata(output, executionWithAgent(null), agent("guardrail", "agent:check_safety", 0));

            assertThat(output).containsEntry("response", "Content passed all checks");
        }

        @Test
        @DisplayName("derives response='Content violated N rule(s)' when passed=false")
        void derivesResponseFail() {
            Map<String, Object> output = new HashMap<>();
            output.put("passed", false);
            output.put("violations", List.of("rule1", "rule2"));

            service.injectAgentMetadata(output, executionWithAgent(null), agent("guardrail", "agent:check_safety", 0));

            assertThat(output).containsEntry("response", "Content violated 2 rule(s)");
        }

        @Test
        @DisplayName("aliases tokensUsed → tokens_used so enrichAgentFields reads it")
        void aliasesTokensUsed() {
            Map<String, Object> output = new HashMap<>();
            output.put("passed", true);
            output.put("violations", List.of());
            output.put("tokensUsed", 123);

            service.injectAgentMetadata(output, executionWithAgent(null), agent("guardrail", "agent:check_safety", 0));

            assertThat(output).containsEntry("tokens_used", 123);
            assertThat(output).containsEntry("tokensUsed", 123); // original preserved
        }
    }

    @Nested
    @DisplayName("classify")
    class Classify {

        private Agent classifyAgentWithCategories(String label, List<String> categoryLabels) {
            List<Map<String, Object>> categories = new java.util.ArrayList<>();
            for (String c : categoryLabels) {
                categories.add(Map.of("label", c));
            }
            return new Agent(
                null, "classify", label, null, true,
                "openai", "gpt-4", null, "prompt", 0.7, 4096, 10, 5,
                List.of(), null, Map.of(),
                categories, null,
                List.of(), null, null);
        }

        @Test
        @DisplayName("stamps node_type=CLASSIFY and resolves selected_category_index from plan categories")
        void resolvesIndexFromPlanCategories() {
            Agent planAgent = classifyAgentWithCategories(
                "categorize_message", List.of("promotions", "personal", "work"));

            Map<String, Object> output = new HashMap<>();
            output.put("selectedCategory", "work"); // camelCase as DTO produces

            service.injectAgentMetadata(output, executionWithAgent(planAgent),
                agent("classify", "agent:categorize_message", 1));

            assertThat(output).containsEntry("node_type", "CLASSIFY");
            assertThat(output).containsEntry("selected_category", "work");
            assertThat(output).containsEntry("selected_category_index", 2);
            assertThat(output).containsEntry("item_index", 1);
        }

        @Test
        @DisplayName("case-insensitive category match")
        void caseInsensitiveMatch() {
            Agent planAgent = classifyAgentWithCategories(
                "categorize_message", List.of("Promotions", "Personal", "Work"));

            Map<String, Object> output = new HashMap<>();
            output.put("selectedCategory", "personal");

            service.injectAgentMetadata(output, executionWithAgent(planAgent),
                agent("classify", "agent:categorize_message", 0));

            assertThat(output).containsEntry("selected_category_index", 1);
        }

        @Test
        @DisplayName("accented LLM label matches the normalized configured label (Réseaux → reseaux)")
        void accentInsensitiveMatch() {
            // Regression: prod LLMs (especially weaker models) often echo a category
            // with French accents when the configured label is ASCII-slugified.
            // LabelNormalizer translates both sides to NFD-stripped ASCII so they match.
            Agent planAgent = classifyAgentWithCategories(
                "categorize_message", List.of("finance", "reseaux", "tech"));

            Map<String, Object> output = new HashMap<>();
            output.put("selectedCategory", "Réseaux");

            service.injectAgentMetadata(output, executionWithAgent(planAgent),
                agent("classify", "agent:categorize_message", 0));

            assertThat(output).containsEntry("selected_category_index", 1);
        }

        @Test
        @DisplayName("whitespace and punctuation in LLM label normalize to match (\"  Work Items \" → work_items)")
        void whitespaceAndPunctuationNormalizeToMatch() {
            // Regression: LLM outputs often have surrounding whitespace, mixed case,
            // or use spaces/hyphens where config uses underscores. All collapse via
            // LabelNormalizer to the same slug.
            Agent planAgent = classifyAgentWithCategories(
                "categorize_message", List.of("work_items", "personal"));

            Map<String, Object> output = new HashMap<>();
            output.put("selectedCategory", "  Work Items ");

            service.injectAgentMetadata(output, executionWithAgent(planAgent),
                agent("classify", "agent:categorize_message", 0));

            assertThat(output).containsEntry("selected_category_index", 0);
        }

        @Test
        @DisplayName("unknown category writes selected_category_index=-1 (mirrors AgentNode.createClassifySuccessResult so sync and async paths produce identical step output)")
        void unknownCategoryWritesIndexMinusOneMirroringSyncPath() {
            // Regression for the prod bug observed on run_<id>:
            // when the LLM returned a category label not in the configured list (e.g. "other"),
            // the async path SKIPPED injecting selected_category_index, leaving the field
            // absent. enrichAgentFields then fell back to label-as-selectedBranch ("other"),
            // which the routing query (selected_branch == "category_N") never matched -
            // the item was silently lost. The sync path always writes -1 (AgentNode.java:1336),
            // so async must do the same: index=-1 is the explicit "no match" signal.
            Agent planAgent = classifyAgentWithCategories(
                "categorize_message", List.of("a", "b"));

            Map<String, Object> output = new HashMap<>();
            output.put("selectedCategory", "c");

            service.injectAgentMetadata(output, executionWithAgent(planAgent),
                agent("classify", "agent:categorize_message", 0));

            assertThat(output).containsEntry("node_type", "CLASSIFY");
            assertThat(output).containsEntry("selected_category", "c");
            assertThat(output).containsEntry("selected_category_index", -1);
        }

        @Test
        @DisplayName("when findAgent returns empty (agent missing from plan), selected_category_index=-1 is still written")
        void agentMissingFromPlanWritesIndexMinusOne() {
            // Outer guard (execution + plan both non-null) is still satisfied; the failure
            // mode is internal to resolveCategoryIndex which returns -1 when findAgent
            // yields empty. Post-fix the field is written. Pre-fix it was skipped (idx=-1
            // failed the `idx >= 0` guard) - same observable bug as the unknown-label case.
            Agent unrelatedAgent = classifyAgentWithCategories(
                "other_classifier", List.of("x"));

            Map<String, Object> output = new HashMap<>();
            output.put("selectedCategory", "anything");

            service.injectAgentMetadata(output, executionWithAgent(unrelatedAgent),
                agent("classify", "agent:categorize_message", 0));

            assertThat(output).containsEntry("selected_category_index", -1);
        }

        @Test
        @DisplayName("derives response='Classified as: <label>' from selected_category")
        void derivesResponse() {
            Agent planAgent = classifyAgentWithCategories(
                "categorize_message", List.of("promotions", "personal", "work"));

            Map<String, Object> output = new HashMap<>();
            output.put("selectedCategory", "work");

            service.injectAgentMetadata(output, executionWithAgent(planAgent),
                agent("classify", "agent:categorize_message", 0));

            assertThat(output).containsEntry("response", "Classified as: work");
        }

        @Test
        @DisplayName("aliases tokensUsed → tokens_used so enrichAgentFields reads it")
        void aliasesTokensUsed() {
            Agent planAgent = classifyAgentWithCategories(
                "categorize_message", List.of("a"));

            Map<String, Object> output = new HashMap<>();
            output.put("selectedCategory", "a");
            output.put("tokensUsed", 456);

            service.injectAgentMetadata(output, executionWithAgent(planAgent),
                agent("classify", "agent:categorize_message", 0));

            assertThat(output).containsEntry("tokens_used", 456);
        }

        @Test
        @DisplayName("existing response is preserved (does not overwrite)")
        void preservesExistingResponse() {
            Agent planAgent = classifyAgentWithCategories(
                "categorize_message", List.of("work"));

            Map<String, Object> output = new HashMap<>();
            output.put("selectedCategory", "work");
            output.put("response", "custom response");

            service.injectAgentMetadata(output, executionWithAgent(planAgent),
                agent("classify", "agent:categorize_message", 0));

            assertThat(output).containsEntry("response", "custom response");
        }
    }

    @Nested
    @DisplayName("agent (regular)")
    class RegularAgent {

        private Agent regularAgent(String label, UUID agentEntityId) {
            return regularAgentWithLimits(label, agentEntityId, 96, 1);
        }

        private Agent regularAgentWithLimits(String label, UUID agentEntityId, int maxTokens, int maxIterations) {
            return new Agent(
                null, "agent", label, agentEntityId.toString(), false,
                "deepseek", "deepseek-chat", "system", "prompt", 0.0, maxTokens, maxIterations, 0,
                List.of(), null, Map.of(),
                null, null,
                null, null, null);
        }

        @Test
        @DisplayName("stamps node_type=AGENT and item context")
        void stampsAgentType() {
            Map<String, Object> output = new HashMap<>();
            output.put("response", "hello");

            service.injectAgentMetadata(output, executionWithAgent(null),
                agent("agent", "agent:writer", 3));

            assertThat(output).containsEntry("node_type", "AGENT");
            assertThat(output).containsEntry("item_index", 3);
            assertThat(output).containsEntry("item_id", "3");
        }

        @Test
        @DisplayName("aliases nested totalUsage into tokens_used and prompt/completion fields")
        void aliasesNestedTotalUsage() {
            Map<String, Object> output = new HashMap<>();
            output.put("response", "hello");
            output.put("totalUsage", Map.of(
                "promptTokens", 11,
                "completionTokens", 7,
                "totalTokens", 18));

            service.injectAgentMetadata(output, executionWithAgent(null),
                agent("agent", "agent:writer", 0));

            assertThat(output).containsEntry("tokens_used", 18);
            assertThat(output).containsEntry("promptTokens", 11);
            assertThat(output).containsEntry("completionTokens", 7);
        }

        @Test
        @DisplayName("agent_config_snapshot uses PendingAgent runtime limits when rebuilt plan has defaults")
        @SuppressWarnings("unchecked")
        void agentConfigSnapshotUsesPendingRuntimeLimitsWhenPlanIsStale() {
            UUID agentEntityId = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
            Agent stalePlanAgent = regularAgentWithLimits("Workflow Agent", agentEntityId, 4096, 10);
            Map<String, Object> runtimeConfig = new HashMap<>();
            runtimeConfig.put("provider", "deepseek");
            runtimeConfig.put("model", "deepseek-chat");
            runtimeConfig.put("temperature", 0.0);
            runtimeConfig.put("maxTokens", 96);
            runtimeConfig.put("maxIterations", 1);
            runtimeConfig.put("systemPrompt", "resolved system");
            PendingAgent pending = new PendingAgent(
                "corr-agent",
                "run_<id>",
                "agent:workflow_agent",
                "workflow_agent",
                "trigger:start",
                1,
                0,
                "0",
                "agent",
                "tenant-1",
                null,
                runtimeConfig,
                null,
                null,
                null,
                "deepseek-chat",
                "resolved system",
                "reply with marker",
                Instant.now(),
                "org-1");

            Map<String, Object> output = new HashMap<>();
            output.put("response", "hello");

            service.injectAgentMetadata(output, executionWithAgent(stalePlanAgent), pending);

            Map<String, Object> snapshot = (Map<String, Object>) output.get("agent_config_snapshot");
            assertThat(snapshot).containsEntry("provider", "deepseek");
            assertThat(snapshot).containsEntry("model", "deepseek-chat");
            assertThat(snapshot).containsEntry("temperature", 0.0);
            assertThat(snapshot).containsEntry("maxTokens", 96);
            assertThat(snapshot).containsEntry("maxIterations", 1);
        }

        @Test
        @DisplayName("records workflowRunId from WorkflowExecution when PendingAgent carries the public run id")
        void recordsWorkflowRunIdFromExecutionWhenPendingRunIdIsPublic() throws Exception {
            UUID agentEntityId = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
            UUID workflowId = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
            UUID workflowRunId = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
            String publicRunId = "run_<id>";
            AgentClient agentClient = mock(AgentClient.class);
            setAgentClient(agentClient);

            Agent planAgent = regularAgent("Workflow Agent", agentEntityId);
            WorkflowExecution execution = executionWithAgent(planAgent, workflowId, workflowRunId);
            PendingAgent pending = new PendingAgent(
                "corr-agent",
                publicRunId,
                "agent:workflow_agent",
                "workflow_agent",
                "trigger:start",
                2,
                0,
                "0",
                "agent",
                "tenant-1",
                null,
                null,
                null,
                null,
                null,
                "deepseek-chat",
                "system",
                "reply with marker",
                Instant.now(),
                null);
            Map<String, Object> rawResult = new HashMap<>();
            rawResult.put("provider", "deepseek");
            rawResult.put("model", "deepseek-chat");
            rawResult.put("iterations", 1);
            rawResult.put("totalUsage", Map.of(
                "promptTokens", 11,
                "completionTokens", 7,
                "totalTokens", 18));
            rawResult.put("conversationHistory", List.of(
                Map.of("role", "ASSISTANT", "content", "marker")));

            invokeRecordAsyncObservability(
                execution,
                pending,
                new AgentResultMessage(
                    "corr-agent",
                    publicRunId,
                    "agent:workflow_agent",
                    rawResult,
                    true,
                    null,
                    "agent",
                    Instant.now()),
                new StepExecutionResult("agent:workflow_agent", NodeStatus.COMPLETED, "Success", rawResult, 123, null));

            ArgumentCaptor<AgentObservabilityRequest> captor =
                ArgumentCaptor.forClass(AgentObservabilityRequest.class);
            verify(agentClient).recordObservability(captor.capture());
            AgentObservabilityRequest request = captor.getValue();

            assertThat(request.getWorkflowId()).isEqualTo(workflowId);
            assertThat(request.getWorkflowRunId()).isEqualTo(workflowRunId);
            assertThat(request.getRunId()).isEqualTo(publicRunId);
            assertThat(request.getNodeId()).isEqualTo("agent:workflow_agent");
            assertThat(request.getAgentEntityId()).isEqualTo(agentEntityId);
            assertThat(request.getTotalTokens()).isEqualTo(18);
        }

        @Test
        @DisplayName("records workflowId from the persisted run when async reconstruction generated a synthetic plan id")
        void recordsWorkflowIdFromPersistedRunWhenPlanIdIsSynthetic() throws Exception {
            UUID agentEntityId = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
            UUID persistedWorkflowId = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
            UUID syntheticPlanId = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
            UUID workflowRunId = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
            String publicRunId = "run_<id>";
            AgentClient agentClient = mock(AgentClient.class);
            setAgentClient(agentClient);

            when(runRepository.findWorkflowIdByRunIdPublic(publicRunId))
                .thenReturn(Optional.of(persistedWorkflowId));

            Agent planAgent = regularAgent("Workflow Agent", agentEntityId);
            WorkflowExecution execution = executionWithAgent(planAgent, syntheticPlanId, workflowRunId);
            PendingAgent pending = new PendingAgent(
                "corr-agent",
                publicRunId,
                "agent:workflow_agent",
                "workflow_agent",
                "trigger:start",
                1,
                0,
                "0",
                "agent",
                "tenant-1",
                null,
                null,
                null,
                null,
                null,
                "deepseek-chat",
                "system",
                "reply with marker",
                Instant.now(),
                "org-1");
            Map<String, Object> rawResult = new HashMap<>();
            rawResult.put("provider", "deepseek");
            rawResult.put("model", "deepseek-chat");
            rawResult.put("totalUsage", Map.of("totalTokens", 18));

            invokeRecordAsyncObservability(
                execution,
                pending,
                new AgentResultMessage(
                    "corr-agent",
                    publicRunId,
                    "agent:workflow_agent",
                    rawResult,
                    true,
                    null,
                    "agent",
                    Instant.now()),
                new StepExecutionResult("agent:workflow_agent", NodeStatus.COMPLETED, "Success", rawResult, 123, null));

            ArgumentCaptor<AgentObservabilityRequest> captor =
                ArgumentCaptor.forClass(AgentObservabilityRequest.class);
            verify(agentClient).recordObservability(captor.capture());
            AgentObservabilityRequest request = captor.getValue();

            assertThat(request.getWorkflowId()).isEqualTo(persistedWorkflowId);
            assertThat(request.getWorkflowId()).isNotEqualTo(syntheticPlanId);
            assertThat(request.getWorkflowRunId()).isEqualTo(workflowRunId);
            assertThat(request.getRunId()).isEqualTo(publicRunId);
        }

        @Test
        @DisplayName("records runtime limits from PendingAgent when async rebuilt plan has defaults")
        void recordsRuntimeLimitsFromPendingWhenPlanIsStale() throws Exception {
            UUID agentEntityId = UUID.fromString("00000000-0000-0000-0000-0000000000a4");
            UUID workflowId = UUID.fromString("00000000-0000-0000-0000-0000000000b4");
            UUID workflowRunId = UUID.fromString("00000000-0000-0000-0000-0000000000c4");
            String publicRunId = "run_<id>";
            AgentClient agentClient = mock(AgentClient.class);
            setAgentClient(agentClient);

            Agent stalePlanAgent = regularAgentWithLimits("Workflow Agent", agentEntityId, 4096, 10);
            WorkflowExecution execution = executionWithAgent(stalePlanAgent, workflowId, workflowRunId);
            Map<String, Object> runtimeConfig = new HashMap<>();
            runtimeConfig.put("provider", "deepseek");
            runtimeConfig.put("model", "deepseek-chat");
            runtimeConfig.put("temperature", 0.0);
            runtimeConfig.put("maxTokens", 96);
            runtimeConfig.put("maxIterations", 1);
            PendingAgent pending = new PendingAgent(
                "corr-agent",
                publicRunId,
                "agent:workflow_agent",
                "workflow_agent",
                "trigger:start",
                1,
                0,
                "0",
                "agent",
                "tenant-1",
                null,
                runtimeConfig,
                null,
                null,
                null,
                "deepseek-chat",
                "system",
                "reply with marker",
                Instant.now(),
                "org-1");
            Map<String, Object> rawResult = new HashMap<>();
            rawResult.put("provider", "deepseek");
            rawResult.put("model", "deepseek-chat");
            rawResult.put("totalUsage", Map.of("totalTokens", 18));

            invokeRecordAsyncObservability(
                execution,
                pending,
                new AgentResultMessage(
                    "corr-agent",
                    publicRunId,
                    "agent:workflow_agent",
                    rawResult,
                    true,
                    null,
                    "agent",
                    Instant.now()),
                new StepExecutionResult("agent:workflow_agent", NodeStatus.COMPLETED, "Success", rawResult, 123, null));

            ArgumentCaptor<AgentObservabilityRequest> captor =
                ArgumentCaptor.forClass(AgentObservabilityRequest.class);
            verify(agentClient).recordObservability(captor.capture());
            AgentObservabilityRequest request = captor.getValue();

            assertThat(request.getProvider()).isEqualTo("deepseek");
            assertThat(request.getModel()).isEqualTo("deepseek-chat");
            assertThat(request.getTemperature()).isEqualTo(0.0);
            assertThat(request.getMaxTokensConfig()).isEqualTo(96);
            assertThat(request.getMaxIterationsConfig()).isEqualTo(1);
        }
    }
}
