package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.AgentObservabilityRequest;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionRequestDto;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionResponseDto;
import com.apimarketplace.agent.client.dto.execution.ClassifyRequestDto;
import com.apimarketplace.agent.client.dto.execution.ClassifyResponseDto;
import com.apimarketplace.agent.client.dto.execution.GuardrailRequestDto;
import com.apimarketplace.agent.client.dto.execution.GuardrailResponseDto;
import com.apimarketplace.orchestrator.domain.workflow.Agent;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AgentNode.
 * AgentNode executes AI/LLM agents via remote agent-service.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentNode")
class AgentNodeTest {

    @Mock
    private WorkflowPlan mockPlan;

    @Mock
    private AgentClient mockAgentClient;

    @Mock
    private V2TemplateAdapter mockTemplateAdapter;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        Map<String, Object> triggerData = new HashMap<>();
        triggerData.put("user_input", "Hello, world!");
        triggerData.put("context", Map.of("topic", "greetings"));

        context = ExecutionContext.create(
            "run-1",
            "workflow-run-1",
            "tenant-1",
            "item-1",
            0,
            triggerData,
            mockPlan
        );
    }

    /**
     * Helper to create a ServiceRegistry with the mocked agent client.
     */
    private ServiceRegistry buildServiceRegistry() {
        return ServiceRegistry.builder()
            .agentClient(mockAgentClient)
            .build();
    }

    /**
     * Helper to inject agent client into a node via ServiceRegistry.
     */
    private void injectAgentClient(AgentNode node) {
        node.acceptServices(buildServiceRegistry());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Constructor tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("Should create AgentNode with agentConfig and dependencies")
        void shouldCreateAgentNodeWithAgentConfigAndDependencies() {
            Agent agent = createBasicAgent();
            AgentNode node = new AgentNode("agent:analyzer", agent, List.of("trigger:webhook"));

            assertEquals("agent:analyzer", node.getNodeId());
            assertEquals(NodeType.AGENT, node.getType());
            assertEquals(agent, node.getAgentConfig());
        }

        @Test
        @DisplayName("Should create AgentNode with agentConfig only")
        void shouldCreateAgentNodeWithAgentConfigOnly() {
            Agent agent = createBasicAgent();
            AgentNode node = new AgentNode("agent:analyzer", agent);

            assertEquals("agent:analyzer", node.getNodeId());
            assertEquals(NodeType.AGENT, node.getType());
        }

        @Test
        @DisplayName("Should handle null dependencies")
        void shouldHandleNullDependencies() {
            Agent agent = createBasicAgent();
            AgentNode node = new AgentNode("agent:analyzer", agent, null);

            assertNotNull(node);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Builder tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("Should build AgentNode using builder pattern")
        void shouldBuildAgentNodeUsingBuilderPattern() {
            Agent agent = createBasicAgent();

            AgentNode node = AgentNode.builder()
                .nodeId("agent:analyzer")
                .agentConfig(agent)
                .dependencies(List.of("trigger:webhook"))
                .build();

            assertEquals("agent:analyzer", node.getNodeId());
            assertEquals(agent, node.getAgentConfig());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Execution tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Execution")
    class ExecutionTests {

        @Test
        @DisplayName("Should return failure when AgentClient is null")
        void shouldReturnFailureWhenClientIsNull() {
            Agent agent = createBasicAgent();
            AgentNode node = new AgentNode("agent:analyzer", agent);
            // No client set

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            assertTrue(result.errorMessage().get().contains("AgentClient not available"));
        }

        @Test
        @DisplayName("Should call AgentClient for remote execution")
        void shouldCallAgentClient() {
            Agent agent = createBasicAgent();
            AgentNode node = new AgentNode("agent:analyzer", agent);
            injectAgentClient(node);

            when(mockAgentClient.executeAgent(any(AgentExecutionRequestDto.class)))
                .thenReturn(createSuccessResponse());

            node.execute(context);

            verify(mockAgentClient).executeAgent(any(AgentExecutionRequestDto.class));
        }

        @Test
        @DisplayName("Should return success when agent execution succeeds")
        void shouldReturnSuccessWhenAgentExecutionSucceeds() {
            Agent agent = createBasicAgent();
            AgentNode node = new AgentNode("agent:analyzer", agent);
            injectAgentClient(node);

            when(mockAgentClient.executeAgent(any(AgentExecutionRequestDto.class)))
                .thenReturn(createSuccessResponse());

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("Agent response content", result.output().get("response"));
            assertEquals("Agent response content", result.output().get("content"));
        }

        @Test
        @DisplayName("Should return failure when agent execution fails")
        void shouldReturnFailureWhenAgentExecutionFails() {
            Agent agent = createBasicAgent();
            AgentNode node = new AgentNode("agent:analyzer", agent);
            injectAgentClient(node);

            when(mockAgentClient.executeAgent(any(AgentExecutionRequestDto.class)))
                .thenReturn(createFailureResponse("Agent error"));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            assertEquals("Agent error", result.errorMessage().get());
        }

        @Test
        @DisplayName("Should handle AgentClient exception")
        void shouldHandleAgentClientException() {
            Agent agent = createBasicAgent();
            AgentNode node = new AgentNode("agent:analyzer", agent);
            injectAgentClient(node);

            when(mockAgentClient.executeAgent(any(AgentExecutionRequestDto.class)))
                .thenThrow(new RuntimeException("Service unavailable"));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            assertTrue(result.errorMessage().get().contains("Service unavailable"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // AgentRequest building tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AgentRequest Building")
    class AgentRequestBuildingTests {

        @Test
        @DisplayName("Should include prompt in request")
        void shouldIncludePromptInRequest() {
            Agent agent = createAgentWithPrompt("Analyze this data");
            AgentNode node = new AgentNode("agent:analyzer", agent);
            injectAgentClient(node);

            when(mockAgentClient.executeAgent(any(AgentExecutionRequestDto.class)))
                .thenReturn(createSuccessResponse());

            node.execute(context);

            ArgumentCaptor<AgentExecutionRequestDto> captor = ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
            verify(mockAgentClient).executeAgent(captor.capture());

            assertEquals("Analyze this data", captor.getValue().prompt());
        }

        @Test
        @DisplayName("Should include systemPrompt in request")
        void shouldIncludeSystemPromptInRequest() {
            Agent agent = createAgentWithSystemPrompt("You are a helpful assistant");
            AgentNode node = new AgentNode("agent:analyzer", agent);
            injectAgentClient(node);

            when(mockAgentClient.executeAgent(any(AgentExecutionRequestDto.class)))
                .thenReturn(createSuccessResponse());

            node.execute(context);

            ArgumentCaptor<AgentExecutionRequestDto> captor = ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
            verify(mockAgentClient).executeAgent(captor.capture());

            assertThat(captor.getValue().systemPrompt())
                .contains("You are a helpful assistant")
                .contains("Current date:");
        }

        @Test
        @DisplayName("Should include tenantId and runId from context")
        void shouldIncludeTenantIdAndRunIdFromContext() {
            Agent agent = createBasicAgent();
            AgentNode node = new AgentNode("agent:analyzer", agent);
            injectAgentClient(node);

            when(mockAgentClient.executeAgent(any(AgentExecutionRequestDto.class)))
                .thenReturn(createSuccessResponse());

            node.execute(context);

            ArgumentCaptor<AgentExecutionRequestDto> captor = ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
            verify(mockAgentClient).executeAgent(captor.capture());

            assertEquals("tenant-1", captor.getValue().tenantId());
            assertEquals("run-1", captor.getValue().runId());
        }

        @Test
        @DisplayName("Should include nodeId in request")
        void shouldIncludeNodeIdInRequest() {
            Agent agent = createBasicAgent();
            AgentNode node = new AgentNode("agent:analyzer", agent);
            injectAgentClient(node);

            when(mockAgentClient.executeAgent(any(AgentExecutionRequestDto.class)))
                .thenReturn(createSuccessResponse());

            node.execute(context);

            ArgumentCaptor<AgentExecutionRequestDto> captor = ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
            verify(mockAgentClient).executeAgent(captor.capture());

            assertEquals("agent:analyzer", captor.getValue().nodeId());
        }

        @Test
        @DisplayName("Should enable autoDiscoverTools when tools list is empty")
        void shouldEnableAutoDiscoverToolsWhenToolsListIsEmpty() {
            Agent agent = createAgentWithNoTools();
            AgentNode node = new AgentNode("agent:analyzer", agent);
            injectAgentClient(node);

            when(mockAgentClient.executeAgent(any(AgentExecutionRequestDto.class)))
                .thenReturn(createSuccessResponse());

            node.execute(context);

            ArgumentCaptor<AgentExecutionRequestDto> captor = ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
            verify(mockAgentClient).executeAgent(captor.capture());

            assertTrue(captor.getValue().autoDiscoverTools());
        }

        @Test
        @DisplayName("Should use default prompt when prompt is null")
        void shouldUseDefaultPromptWhenPromptIsNull() {
            Agent agent = createAgentWithNullPrompt();
            AgentNode node = new AgentNode("agent:analyzer", agent);
            injectAgentClient(node);

            when(mockAgentClient.executeAgent(any(AgentExecutionRequestDto.class)))
                .thenReturn(createSuccessResponse());

            node.execute(context);

            ArgumentCaptor<AgentExecutionRequestDto> captor = ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
            verify(mockAgentClient).executeAgent(captor.capture());

            assertNotNull(captor.getValue().prompt());
            assertFalse(captor.getValue().prompt().isBlank());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Prompt template resolution tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Prompt Template Resolution")
    class PromptTemplateResolutionTests {

        @Test
        @DisplayName("Should resolve prompt template when templateAdapter available")
        void shouldResolvePromptTemplateWhenTemplateAdapterAvailable() {
            Agent agent = createAgentWithPrompt("Hello {{trigger:webhook.user_input}}");
            AgentNode node = new AgentNode("agent:analyzer", agent);
            node.acceptServices(ServiceRegistry.builder()
                .agentClient(mockAgentClient)
                .templateAdapter(mockTemplateAdapter)
                .build());

            when(mockTemplateAdapter.resolveTemplates(any(), any()))
                .thenReturn(Map.of("prompt", "Hello World!"));

            when(mockAgentClient.executeAgent(any(AgentExecutionRequestDto.class)))
                .thenReturn(createSuccessResponse());

            node.execute(context);

            verify(mockTemplateAdapter, atLeastOnce()).resolveTemplates(any(), any());
        }

        @Test
        @DisplayName("Should use original prompt when template resolution fails")
        void shouldUseOriginalPromptWhenTemplateResolutionFails() {
            Agent agent = createAgentWithPrompt("Original prompt");
            AgentNode node = new AgentNode("agent:analyzer", agent);
            node.acceptServices(ServiceRegistry.builder()
                .agentClient(mockAgentClient)
                .templateAdapter(mockTemplateAdapter)
                .build());

            when(mockTemplateAdapter.resolveTemplates(any(), any()))
                .thenThrow(new RuntimeException("Template error"));

            when(mockAgentClient.executeAgent(any(AgentExecutionRequestDto.class)))
                .thenReturn(createSuccessResponse());

            node.execute(context);

            ArgumentCaptor<AgentExecutionRequestDto> captor = ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
            verify(mockAgentClient).executeAgent(captor.capture());

            assertEquals("Original prompt", captor.getValue().prompt());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Success result output tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Success Result Output")
    class SuccessResultOutputTests {

        @Test
        @DisplayName("Should include iterations in output")
        void shouldIncludeIterationsInOutput() {
            Agent agent = createBasicAgent();
            AgentNode node = new AgentNode("agent:analyzer", agent);
            injectAgentClient(node);

            when(mockAgentClient.executeAgent(any(AgentExecutionRequestDto.class)))
                .thenReturn(createSuccessResponse());

            NodeExecutionResult result = node.execute(context);

            assertEquals(3, result.output().get("iterations"));
        }

        @Test
        @DisplayName("Should include durationMs in output")
        void shouldIncludeDurationMsInOutput() {
            Agent agent = createBasicAgent();
            AgentNode node = new AgentNode("agent:analyzer", agent);
            injectAgentClient(node);

            when(mockAgentClient.executeAgent(any(AgentExecutionRequestDto.class)))
                .thenReturn(createSuccessResponse());

            NodeExecutionResult result = node.execute(context);

            // Duration is measured locally, not from the response
            assertNotNull(result.output().get("durationMs"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // canExecute() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("canExecute()")
    class CanExecuteTests {

        @Test
        @DisplayName("Should return true when no dependencies")
        void shouldReturnTrueWhenNoDependencies() {
            Agent agent = createBasicAgent();
            AgentNode node = new AgentNode("agent:analyzer", agent, List.of());

            assertTrue(node.canExecute(context));
        }

        @Test
        @DisplayName("Should return false when dependencies not completed")
        void shouldReturnFalseWhenDependenciesNotCompleted() {
            Agent agent = createBasicAgent();
            AgentNode node = new AgentNode("agent:analyzer", agent, List.of("mcp:fetch_data"));

            assertFalse(node.canExecute(context));
        }

        @Test
        @DisplayName("Should return true when dependencies completed")
        void shouldReturnTrueWhenDependenciesCompleted() {
            Agent agent = createBasicAgent();
            AgentNode node = new AgentNode("agent:analyzer", agent, List.of("mcp:fetch_data"));

            NodeExecutionResult stepResult = NodeExecutionResult.success("mcp:fetch_data", Map.of());
            ExecutionContext updatedContext = context.withResult("mcp:fetch_data", stepResult);

            assertTrue(node.canExecute(updatedContext));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getNextNodes() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getNextNodes()")
    class GetNextNodesTests {

        @Test
        @DisplayName("Should return successors on success")
        void shouldReturnSuccessorsOnSuccess() {
            Agent agent = createBasicAgent();
            AgentNode node = new AgentNode("agent:analyzer", agent);
            node.addSuccessor(createSuccessorNode("mcp:next"));

            NodeExecutionResult result = NodeExecutionResult.success(node.getNodeId(), Map.of());
            List<ExecutionNode> nextNodes = node.getNextNodes(result);

            assertEquals(1, nextNodes.size());
        }

        @Test
        @DisplayName("Should return empty on failure")
        void shouldReturnEmptyOnFailure() {
            Agent agent = createBasicAgent();
            AgentNode node = new AgentNode("agent:analyzer", agent);
            node.addSuccessor(createSuccessorNode("mcp:next"));

            NodeExecutionResult result = NodeExecutionResult.failure(node.getNodeId(), "Error");
            List<ExecutionNode> nextNodes = node.getNextNodes(result);

            assertTrue(nextNodes.isEmpty());
        }

        // ── Classify routing ─────────────────────────────────────────────────────
        // Regression guard: getNextNodes() must route to the correct category branch
        // using agentConfig.type() (in-memory), NOT output.get("node_type") (which is
        // stripped by GenericOutputSchemaMapper when ClassifyNodeSpec doesn't declare it).
        // The prod bug: node_type was absent from stored output → fell through to
        // "no selection info" → all 5 label-nodes executed on every email.

        @Test
        @DisplayName("Classify: routes to correct branch when node_type is in output")
        void classifyRoutesCorrectlyWithNodeType() {
            Agent agent = createClassifyAgent();
            AgentNode node = new AgentNode("agent:classifier", agent);
            BaseNode catA = createSuccessorNode("mcp:label_a");
            BaseNode catB = createSuccessorNode("mcp:label_b");
            node.addCategoryTarget("category_0", catA);
            node.addCategoryTarget("category_1", catB);

            // Output as stored by createClassifySuccessResult (node_type present)
            Map<String, Object> output = new HashMap<>();
            output.put("node_type", "CLASSIFY");
            output.put("selected_category", "category_a");
            output.put("selected_category_index", 0);
            NodeExecutionResult result = NodeExecutionResult.success(node.getNodeId(), output);

            List<ExecutionNode> nextNodes = node.getNextNodes(result);

            assertEquals(1, nextNodes.size());
            assertEquals("mcp:label_a", nextNodes.get(0).getNodeId());
        }

        @Test
        @DisplayName("Classify: routes to correct branch even when node_type is absent (DB read after mapper strips it)")
        void classifyRoutesCorrectlyWithoutNodeType() {
            Agent agent = createClassifyAgent();
            AgentNode node = new AgentNode("agent:classifier", agent);
            BaseNode catA = createSuccessorNode("mcp:label_a");
            BaseNode catB = createSuccessorNode("mcp:label_b");
            node.addCategoryTarget("category_0", catA);
            node.addCategoryTarget("category_1", catB);

            // Simulate DB read: node_type stripped by GenericOutputSchemaMapper
            Map<String, Object> output = new HashMap<>();
            output.put("selected_category", "category_b"); // second category
            output.put("selected_category_index", 1);
            // node_type intentionally absent - this is the bug scenario
            NodeExecutionResult result = NodeExecutionResult.success(node.getNodeId(), output);

            List<ExecutionNode> nextNodes = node.getNextNodes(result);

            assertEquals(1, nextNodes.size());
            assertEquals("mcp:label_b", nextNodes.get(0).getNodeId());
        }

        @Test
        @DisplayName("Classify: returns empty list when no category matches selected_category")
        void classifyReturnsEmptyWhenNoCategoryMatches() {
            Agent agent = createClassifyAgent();
            AgentNode node = new AgentNode("agent:classifier", agent);
            BaseNode catA = createSuccessorNode("mcp:label_a");
            BaseNode catB = createSuccessorNode("mcp:label_b");
            node.addCategoryTarget("category_0", catA);
            node.addCategoryTarget("category_1", catB);

            // selected_category doesn't match any configured category label
            Map<String, Object> output = new HashMap<>();
            output.put("selected_category", "nonexistent_category");
            output.put("selected_category_index", -1);
            NodeExecutionResult result = NodeExecutionResult.success(node.getNodeId(), output);

            List<ExecutionNode> nextNodes = node.getNextNodes(result);

            // Must return empty list - not all successors (which was the old buggy behavior)
            assertTrue(nextNodes.isEmpty());
        }

        @Test
        @DisplayName("Classify: returns empty list when selected_category is null")
        void classifyReturnsEmptyWhenNullCategory() {
            Agent agent = createClassifyAgent();
            AgentNode node = new AgentNode("agent:classifier", agent);
            node.addCategoryTarget("category_0", createSuccessorNode("mcp:label_a"));

            Map<String, Object> output = new HashMap<>();
            // selected_category intentionally null
            NodeExecutionResult result = NodeExecutionResult.success(node.getNodeId(), output);

            List<ExecutionNode> nextNodes = node.getNextNodes(result);

            assertTrue(nextNodes.isEmpty());
        }

        @Test
        @DisplayName("Guardrail: routes to pass branch using agentConfig.type() when node_type absent")
        void guardrailRoutesCorrectlyWithoutNodeType() {
            Agent agent = createGuardrailAgent();
            AgentNode node = new AgentNode("agent:guardrail", agent);
            BaseNode passNode = createSuccessorNode("mcp:pass");
            BaseNode failNode = createSuccessorNode("mcp:fail");
            node.addGuardrailTarget("pass", passNode);
            node.addGuardrailTarget("fail", failNode);

            // Simulate DB read: node_type stripped
            Map<String, Object> output = new HashMap<>();
            output.put("passed", true);
            // node_type intentionally absent
            NodeExecutionResult result = NodeExecutionResult.success(node.getNodeId(), output);

            List<ExecutionNode> nextNodes = node.getNextNodes(result);

            assertEquals(1, nextNodes.size());
            assertEquals("mcp:pass", nextNodes.get(0).getNodeId());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // onComplete() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("onComplete()")
    class OnCompleteTests {

        @Test
        @DisplayName("Should not throw exception")
        void shouldNotThrowException() {
            Agent agent = createBasicAgent();
            AgentNode node = new AgentNode("agent:analyzer", agent);
            NodeExecutionResult result = NodeExecutionResult.success(node.getNodeId(), Map.of());

            assertDoesNotThrow(() -> node.onComplete(context, result));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getAgentConfig() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getAgentConfig()")
    class GetAgentConfigTests {

        @Test
        @DisplayName("Should return the agent configuration")
        void shouldReturnTheAgentConfiguration() {
            Agent agent = createBasicAgent();
            AgentNode node = new AgentNode("agent:analyzer", agent);

            assertEquals(agent, node.getAgentConfig());
            assertEquals("Data Analyzer", node.getAgentConfig().label());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Classify observability tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Classify Observability Recording")
    class ClassifyObservabilityTests {

        @Test
        @DisplayName("Should record durationMs and totalTokens from ClassifyResult")
        void shouldRecordDurationAndTokens() {
            Agent agent = createClassifyAgent();
            AgentNode node = new AgentNode("agent:classifier", agent);
            injectAgentClient(node);

            ClassifyResponseDto response = new ClassifyResponseDto(
                true, "category_a", 0.95, "High confidence match",
                null, 450L, "openai", "gpt-4o", 320, 200, 120, null, null, null
            );
            when(mockAgentClient.executeClassify(any(ClassifyRequestDto.class)))
                .thenReturn(response);

            node.execute(context);

            ArgumentCaptor<AgentObservabilityRequest> captor =
                ArgumentCaptor.forClass(AgentObservabilityRequest.class);
            verify(mockAgentClient).recordObservability(captor.capture());

            AgentObservabilityRequest obs = captor.getValue();
            assertThat(obs.getDurationMs()).isEqualTo(450L);
            assertThat(obs.getTotalTokens()).isEqualTo(320L);
            assertThat(obs.getIterationCount()).isEqualTo(1);
            assertThat(obs.getAgentType()).isEqualTo("classify");
            assertThat(obs.getStatus()).isEqualTo("COMPLETED");
        }

        @Test
        @DisplayName("Should record FAILED status when classify fails")
        void shouldRecordFailedStatus() {
            Agent agent = createClassifyAgent();
            AgentNode node = new AgentNode("agent:classifier", agent);
            injectAgentClient(node);

            ClassifyResponseDto response = new ClassifyResponseDto(
                false, null, 0, null,
                "Classification error", 100L, "openai", "gpt-4o", 50, 0, 0, null, null, null
            );
            when(mockAgentClient.executeClassify(any(ClassifyRequestDto.class)))
                .thenReturn(response);

            node.execute(context);

            ArgumentCaptor<AgentObservabilityRequest> captor =
                ArgumentCaptor.forClass(AgentObservabilityRequest.class);
            verify(mockAgentClient).recordObservability(captor.capture());

            AgentObservabilityRequest obs = captor.getValue();
            assertThat(obs.getStatus()).isEqualTo("FAILED");
            assertThat(obs.getDurationMs()).isEqualTo(100L);
            // ClassifyResult.failure() factory sets tokensUsed=0
            assertThat(obs.getTotalTokens()).isEqualTo(0L);
            assertThat(obs.getIterationCount()).isEqualTo(1);
            assertThat(obs.getErrorMessage()).isEqualTo("Classification error");
        }

        @Test
        @DisplayName("Should set tenantId and nodeId on observability request")
        void shouldSetContextFields() {
            Agent agent = createClassifyAgent();
            AgentNode node = new AgentNode("agent:classifier", agent);
            injectAgentClient(node);

            when(mockAgentClient.executeClassify(any(ClassifyRequestDto.class)))
                .thenReturn(new ClassifyResponseDto(
                    true, "cat1", 0.9, "ok", null, 200L, "openai", "gpt-4o", 100, 70, 30, null, null, null
                ));

            node.execute(context);

            ArgumentCaptor<AgentObservabilityRequest> captor =
                ArgumentCaptor.forClass(AgentObservabilityRequest.class);
            verify(mockAgentClient).recordObservability(captor.capture());

            AgentObservabilityRequest obs = captor.getValue();
            assertThat(obs.getTenantId()).isEqualTo("tenant-1");
            assertThat(obs.getNodeId()).isEqualTo("agent:classifier");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Guardrail observability tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Guardrail Observability Recording")
    class GuardrailObservabilityTests {

        @Test
        @DisplayName("Should record durationMs and totalTokens from GuardrailResult")
        void shouldRecordDurationAndTokens() {
            Agent agent = createGuardrailAgent();
            AgentNode node = new AgentNode("agent:guardrail", agent);
            injectAgentClient(node);

            GuardrailResponseDto response = new GuardrailResponseDto(
                true, true, List.of(), Map.of(), null,
                null, 350L, "openai", "gpt-4o", 275, 175, 100, null, null, null
            );
            when(mockAgentClient.executeGuardrail(any(GuardrailRequestDto.class)))
                .thenReturn(response);

            node.execute(context);

            ArgumentCaptor<AgentObservabilityRequest> captor =
                ArgumentCaptor.forClass(AgentObservabilityRequest.class);
            verify(mockAgentClient).recordObservability(captor.capture());

            AgentObservabilityRequest obs = captor.getValue();
            assertThat(obs.getDurationMs()).isEqualTo(350L);
            assertThat(obs.getTotalTokens()).isEqualTo(275L);
            assertThat(obs.getIterationCount()).isEqualTo(1);
            assertThat(obs.getAgentType()).isEqualTo("guardrail");
            assertThat(obs.getStatus()).isEqualTo("COMPLETED");
        }

        @Test
        @DisplayName("Should record FAILED status when guardrail fails")
        void shouldRecordFailedStatus() {
            Agent agent = createGuardrailAgent();
            AgentNode node = new AgentNode("agent:guardrail", agent);
            injectAgentClient(node);

            GuardrailResponseDto response = new GuardrailResponseDto(
                false, false, List.of(), Map.of(), null,
                "Guardrail error", 80L, "openai", "gpt-4o", 40, 0, 0, null, null, null
            );
            when(mockAgentClient.executeGuardrail(any(GuardrailRequestDto.class)))
                .thenReturn(response);

            node.execute(context);

            ArgumentCaptor<AgentObservabilityRequest> captor =
                ArgumentCaptor.forClass(AgentObservabilityRequest.class);
            verify(mockAgentClient).recordObservability(captor.capture());

            AgentObservabilityRequest obs = captor.getValue();
            assertThat(obs.getStatus()).isEqualTo("FAILED");
            assertThat(obs.getDurationMs()).isEqualTo(80L);
            // GuardrailResult.failure() factory sets tokensUsed=0
            assertThat(obs.getTotalTokens()).isEqualTo(0L);
            assertThat(obs.getIterationCount()).isEqualTo(1);
            assertThat(obs.getErrorMessage()).isEqualTo("Guardrail error");
        }

        @Test
        @DisplayName("Should set tenantId and nodeId on observability request")
        void shouldSetContextFields() {
            Agent agent = createGuardrailAgent();
            AgentNode node = new AgentNode("agent:guardrail", agent);
            injectAgentClient(node);

            when(mockAgentClient.executeGuardrail(any(GuardrailRequestDto.class)))
                .thenReturn(new GuardrailResponseDto(
                    true, true, List.of(), Map.of(), null,
                    null, 200L, "openai", "gpt-4o", 100, 70, 30, null, null, null
                ));

            node.execute(context);

            ArgumentCaptor<AgentObservabilityRequest> captor =
                ArgumentCaptor.forClass(AgentObservabilityRequest.class);
            verify(mockAgentClient).recordObservability(captor.capture());

            AgentObservabilityRequest obs = captor.getValue();
            assertThat(obs.getTenantId()).isEqualTo("tenant-1");
            assertThat(obs.getNodeId()).isEqualTo("agent:guardrail");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // prepareInput content injection tests (guardrailParams / classifyParams)
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Content injection from guardrailParams/classifyParams")
    class ContentInjectionTests {

        @Test
        @DisplayName("Guardrail: guardrailParams injected as content in request when not in params")
        void guardrailParamsInjectedAsContent() {
            // Agent with guardrailParams as separate field, NOT in params map
            Agent agent = new Agent(
                "agent-guardrail-2", "guardrail", "Content Guard",
                null, null, "google", "gemini-3-flash-preview", null,
                "Validate content", 0.7, 4096, 10, 5,
                List.of(), null,
                Map.of("action", "flag"),  // params: NO "content" key
                List.of(),
                null,
                List.of(Map.of("id", "no_pii", "description", "No personal information")),
                "{{trigger:start.output.user_input}}",  // guardrailParams as separate field
                null
            );

            AgentNode node = new AgentNode("agent:guardrail", agent);
            injectAgentClient(node);

            GuardrailResponseDto response = new GuardrailResponseDto(
                true, true, List.of(), Map.of(), null,
                null, 200L, "google", "gemini-3-flash-preview", 100, 70, 30, null, null, null
            );
            when(mockAgentClient.executeGuardrail(any(GuardrailRequestDto.class)))
                .thenReturn(response);

            node.execute(context);

            ArgumentCaptor<GuardrailRequestDto> captor = ArgumentCaptor.forClass(GuardrailRequestDto.class);
            verify(mockAgentClient).executeGuardrail(captor.capture());

            // Content must NOT be empty - guardrailParams was injected
            assertThat(captor.getValue().content()).isNotNull();
            assertThat(captor.getValue().content()).isNotEmpty();
        }

        @Test
        @DisplayName("Guardrail: content falls back to prompt when guardrailParams is null")
        void guardrailContentFallsBackToPromptWithoutGuardrailParams() {
            // Agent with NO guardrailParams and NO content in params, but HAS a prompt
            Agent agent = new Agent(
                "agent-guardrail-3", "guardrail", "Content Guard",
                null, null, "openai", "gpt-4o", null,
                "Validate content", 0.7, 4096, 10, 5,
                List.of(), null,
                Map.of("action", "flag"),  // No "content" key
                List.of(),
                null,
                List.of(Map.of("id", "no_pii", "description", "No PII")),
                null,  // guardrailParams is null
                null
            );

            AgentNode node = new AgentNode("agent:guardrail", agent);
            injectAgentClient(node);

            GuardrailResponseDto response = new GuardrailResponseDto(
                true, true, List.of(), Map.of(), null,
                null, 200L, "openai", "gpt-4o", 100, 70, 30, null, null, null
            );
            when(mockAgentClient.executeGuardrail(any(GuardrailRequestDto.class)))
                .thenReturn(response);

            node.execute(context);

            ArgumentCaptor<GuardrailRequestDto> captor = ArgumentCaptor.forClass(GuardrailRequestDto.class);
            verify(mockAgentClient).executeGuardrail(captor.capture());

            // Without guardrailParams, resolveContent() falls back to prompt
            assertThat(captor.getValue().content()).isEqualTo("Validate content");
        }

        @Test
        @DisplayName("Guardrail: content empty when guardrailParams is null AND prompt is null")
        void guardrailContentEmptyWithoutGuardrailParamsAndPrompt() {
            // Agent with NO guardrailParams, NO content in params, AND no prompt
            Agent agent = new Agent(
                "agent-guardrail-5", "guardrail", "Content Guard",
                null, null, "openai", "gpt-4o", null,
                null, 0.7, 4096, 10, 5,  // prompt is null
                List.of(), null,
                Map.of("action", "flag"),  // No "content" key
                List.of(),
                null,
                List.of(Map.of("id", "no_pii", "description", "No PII")),
                null,  // guardrailParams is null
                null
            );

            AgentNode node = new AgentNode("agent:guardrail", agent);
            injectAgentClient(node);

            GuardrailResponseDto response = new GuardrailResponseDto(
                true, true, List.of(), Map.of(), null,
                null, 200L, "openai", "gpt-4o", 100, 70, 30, null, null, null
            );
            when(mockAgentClient.executeGuardrail(any(GuardrailRequestDto.class)))
                .thenReturn(response);

            node.execute(context);

            ArgumentCaptor<GuardrailRequestDto> captor = ArgumentCaptor.forClass(GuardrailRequestDto.class);
            verify(mockAgentClient).executeGuardrail(captor.capture());

            // Without guardrailParams AND without prompt, content is empty
            assertThat(captor.getValue().content()).isEmpty();
        }

        @Test
        @DisplayName("Guardrail: params content takes precedence over guardrailParams (putIfAbsent)")
        void guardrailParamsContentDoesNotOverrideExistingContent() {
            // Agent with BOTH content in params AND guardrailParams
            Agent agent = new Agent(
                "agent-guardrail-4", "guardrail", "Content Guard",
                null, null, "openai", "gpt-4o", null,
                "Validate content", 0.7, 4096, 10, 5,
                List.of(), null,
                Map.of("content", "From params", "action", "flag"),  // content in params
                List.of(),
                null,
                List.of(Map.of("id", "no_pii", "description", "No PII")),
                "From guardrailParams",  // also set
                null
            );

            AgentNode node = new AgentNode("agent:guardrail", agent);
            injectAgentClient(node);

            GuardrailResponseDto response = new GuardrailResponseDto(
                true, true, List.of(), Map.of(), null,
                null, 200L, "openai", "gpt-4o", 100, 70, 30, null, null, null
            );
            when(mockAgentClient.executeGuardrail(any(GuardrailRequestDto.class)))
                .thenReturn(response);

            node.execute(context);

            ArgumentCaptor<GuardrailRequestDto> captor = ArgumentCaptor.forClass(GuardrailRequestDto.class);
            verify(mockAgentClient).executeGuardrail(captor.capture());

            // putIfAbsent: params "content" takes priority
            assertThat(captor.getValue().content()).isEqualTo("From params");
        }

        @Test
        @DisplayName("Classify: classifyParams injected as content in request when not in params")
        void classifyParamsInjectedAsContent() {
            // Agent with classifyParams as separate field, NOT in params map
            Agent agent = new Agent(
                "agent-classify-2", "classify", "Content Classifier",
                null, null, "openai", "gpt-4o", null,
                "Classify this content", 0.7, 4096, 10, 5,
                List.of(), null,
                Map.of(),  // params: NO "content" key
                List.of(
                    Map.of("label", "category_a", "description", "Category A"),
                    Map.of("label", "category_b", "description", "Category B")
                ),
                "{{trigger:start.output.user_input}}",  // classifyParams as separate field
                List.of(),
                null,
                null
            );

            AgentNode node = new AgentNode("agent:classifier", agent);
            injectAgentClient(node);

            ClassifyResponseDto response = new ClassifyResponseDto(
                true, "category_a", 0.95, "High confidence match",
                null, 450L, "openai", "gpt-4o", 320, 200, 120, null, null, null
            );
            when(mockAgentClient.executeClassify(any(ClassifyRequestDto.class)))
                .thenReturn(response);

            node.execute(context);

            ArgumentCaptor<ClassifyRequestDto> captor = ArgumentCaptor.forClass(ClassifyRequestDto.class);
            verify(mockAgentClient).executeClassify(captor.capture());

            // Content must NOT be empty - classifyParams was injected
            assertThat(captor.getValue().content()).isNotNull();
            assertThat(captor.getValue().content()).isNotEmpty();
        }

        @Test
        @DisplayName("Classify: classifyParams does not override existing content in params (putIfAbsent)")
        void classifyParamsContentDoesNotOverrideExistingContent() {
            Agent agent = new Agent(
                "agent-classify-3", "classify", "Content Classifier",
                null, null, "openai", "gpt-4o", null,
                "Classify this content", 0.7, 4096, 10, 5,
                List.of(), null,
                Map.of("content", "From params"),  // content in params
                List.of(
                    Map.of("label", "category_a", "description", "Category A")
                ),
                "From classifyParams",  // also set
                List.of(),
                null,
                null
            );

            AgentNode node = new AgentNode("agent:classifier", agent);
            injectAgentClient(node);

            ClassifyResponseDto response = new ClassifyResponseDto(
                true, "category_a", 0.95, "ok",
                null, 200L, "openai", "gpt-4o", 100, 70, 30, null, null, null
            );
            when(mockAgentClient.executeClassify(any(ClassifyRequestDto.class)))
                .thenReturn(response);

            node.execute(context);

            ArgumentCaptor<ClassifyRequestDto> captor = ArgumentCaptor.forClass(ClassifyRequestDto.class);
            verify(mockAgentClient).executeClassify(captor.capture());

            // putIfAbsent: params "content" takes priority
            assertThat(captor.getValue().content()).isEqualTo("From params");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helper methods
    // ═══════════════════════════════════════════════════════════════════════════

    private Agent createBasicAgent() {
        return new Agent(
            "agent-1",
            "agent",
            "Data Analyzer",
            null,
            null,
            "openai",
            "gpt-4o",
            "You are a data analyst",
            "Analyze the provided data",
            0.7,
            4096,
            10,
            5,
            List.of("tool1", "tool2"),
            null,
            Map.of(),
            List.of(),
            null,
            List.of(),
            null
        , null);
    }

    private Agent createAgentWithPrompt(String prompt) {
        return new Agent(
            "agent-1",
            "agent",
            "Data Analyzer",
            null,
            null,
            "openai",
            "gpt-4o",
            null,
            prompt,
            0.7,
            4096,
            10,
            5,
            List.of(),
            null,
            Map.of(),
            List.of(),
            null,
            List.of(),
            null
        , null);
    }

    private Agent createAgentWithSystemPrompt(String systemPrompt) {
        return new Agent(
            "agent-1",
            "agent",
            "Data Analyzer",
            null,
            null,
            "openai",
            "gpt-4o",
            systemPrompt,
            "Analyze data",
            0.7,
            4096,
            10,
            5,
            List.of(),
            null,
            Map.of(),
            List.of(),
            null,
            List.of(),
            null
        , null);
    }

    private Agent createAgentWithNoTools() {
        return new Agent(
            "agent-1",
            "agent",
            "Data Analyzer",
            null,
            null,
            "openai",
            "gpt-4o",
            null,
            "Analyze data",
            0.7,
            4096,
            10,
            5,
            List.of(),
            null,
            Map.of(),
            List.of(),
            null,
            List.of(),
            null
        , null);
    }

    private Agent createAgentWithNullPrompt() {
        return new Agent(
            "agent-1",
            "agent",
            "Data Analyzer",
            null,
            null,
            "openai",
            "gpt-4o",
            null,
            null,
            0.7,
            4096,
            10,
            5,
            List.of(),
            null,
            Map.of(),
            List.of(),
            null,
            List.of(),
            null
        , null);
    }

    private Agent createClassifyAgent() {
        return new Agent(
            "agent-classify-1",
            "classify",
            "Content Classifier",
            null,
            null,
            "openai",
            "gpt-4o",
            null,
            "Classify this content",
            0.7,
            4096,
            10,
            5,
            List.of(),
            null,
            Map.of("content", "Test content to classify"),
            List.of(
                Map.of("label", "category_a", "description", "Category A"),
                Map.of("label", "category_b", "description", "Category B")
            ),
            null,
            List.of(),
            null
        , null);
    }

    private Agent createGuardrailAgent() {
        return new Agent(
            "agent-guardrail-1",
            "guardrail",
            "Content Guard",
            null,
            null,
            "openai",
            "gpt-4o",
            null,
            "Validate this content",
            0.7,
            4096,
            10,
            5,
            List.of(),
            null,
            Map.of("content", "Test content to validate", "action", "flag"),
            List.of(),
            null,
            List.of(
                Map.of("id", "no_pii", "description", "No personal information")
            ),
            null
        , null);
    }

    private AgentExecutionResponseDto createSuccessResponse() {
        return new AgentExecutionResponseDto(
            true,                    // success
            "Agent response content", // finalResponse
            "Agent response content", // content
            List.of(),               // toolResults
            3,                       // iterations
            Map.of(),                // totalUsage
            null,                    // error
            1500L,                   // durationMs
            "openai",                // provider
            "gpt-4o",               // model
            List.of(),               // conversationHistory
            null,                    // stopReason
            Map.of(),                // metrics
            List.of(),               // usagePerIteration
            List.of(),               // iterationDurations
            List.of(),               // finishReasonsPerIteration
            null,                    // thinkingSections
            null                     // orderedEntries
        , null);
    }

    private AgentExecutionResponseDto createFailureResponse(String error) {
        return new AgentExecutionResponseDto(
            false,                   // success
            null,                    // finalResponse
            null,                    // content
            List.of(),               // toolResults
            0,                       // iterations
            Map.of(),                // totalUsage
            error,                   // error
            0L,                      // durationMs
            "openai",                // provider
            "gpt-4o",               // model
            List.of(),               // conversationHistory
            null,                    // stopReason
            Map.of(),                // metrics
            List.of(),               // usagePerIteration
            List.of(),               // iterationDurations
            List.of(),               // finishReasonsPerIteration
            null,                    // thinkingSections
            null                     // orderedEntries
        , null);
    }

    private BaseNode createSuccessorNode(String nodeId) {
        return new BaseNode(nodeId, NodeType.MCP) {
            @Override
            public NodeExecutionResult execute(ExecutionContext context) {
                return NodeExecutionResult.success(nodeId, Map.of());
            }
        };
    }
}
