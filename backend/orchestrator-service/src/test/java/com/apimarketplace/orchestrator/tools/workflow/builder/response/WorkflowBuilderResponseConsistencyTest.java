package com.apimarketplace.orchestrator.tools.workflow.builder.response;

import com.apimarketplace.orchestrator.tools.workflow.builder.ResponseContextBuilder;
import com.apimarketplace.orchestrator.tools.workflow.builder.ResponseOptimizer;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.creators.ForkMergeNodeCreator;
import com.apimarketplace.orchestrator.tools.workflow.builder.creators.UtilityNodeCreator;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSessionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Tests for workflow builder response consistency.
 * Ensures all node creation responses follow the same patterns:
 * - NEXT pattern with required fields
 * - Consistent variable syntax
 * - Consistent status field
 * - Proper node_id format
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Workflow Builder Response Consistency Tests")
class WorkflowBuilderResponseConsistencyTest {

    @Mock
    private ResponseContextBuilder contextBuilder;

    @Mock
    private WorkflowBuilderSessionStore sessionStore;

    @Mock
    private ResponseOptimizer responseOptimizer;

    @Mock
    private com.apimarketplace.orchestrator.service.NodeLibraryService nodeLibraryService;

    @Mock
    private com.apimarketplace.orchestrator.repository.WorkflowRepository workflowRepository;

    private TriggerStepResponseBuilder triggerStepBuilder;
    private AgentResponseBuilder agentBuilder;
    private ControlNodeResponseBuilder controlNodeBuilder;
    private ForkMergeNodeCreator forkMergeCreator;
    private UtilityNodeCreator utilityCreator;

    private WorkflowBuilderSession session;

    @BeforeEach
    void setUp() {
        triggerStepBuilder = new TriggerStepResponseBuilder(contextBuilder);
        agentBuilder = new AgentResponseBuilder(contextBuilder);
        controlNodeBuilder = new ControlNodeResponseBuilder(contextBuilder);
        forkMergeCreator = new ForkMergeNodeCreator(sessionStore);
        utilityCreator = new UtilityNodeCreator(sessionStore, responseOptimizer, nodeLibraryService, workflowRepository);

        session = WorkflowBuilderSession.builder()
            .sessionId("test-session")
            .tenantId("test-tenant")
            .workflowName("Test Workflow")
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();

        // Setup mock behavior
        lenient().when(contextBuilder.getAccessibleVariables(any(), anyString()))
            .thenReturn(Collections.emptyMap());
        lenient().when(contextBuilder.validateConditionReferences(any(), any(), any()))
            .thenReturn(Collections.emptyList());
    }

    // ==================== NEXT Pattern Tests ====================

    @Nested
    @DisplayName("NEXT Pattern Presence")
    class NextPatternPresence {

        @Test
        @DisplayName("Trigger response should have NEXT pattern")
        void triggerHasNextPattern() {
            Map<String, Object> response = triggerStepBuilder.buildTriggerResponse(
                session, "trigger:my_trigger", "My Trigger", "table", "123",
                Map.of("id", "Long", "name", "String"),
                Map.of("id", "{{trigger:my_trigger.id}}")
            );

            assertHasNextPattern(response, "trigger");
        }

        @Test
        @DisplayName("Step response should have NEXT pattern")
        void stepHasNextPattern() {
            // Add trigger first
            addTriggerToSession("Start");

            Map<String, Object> response = triggerStepBuilder.buildStepResponse(
                session, "mcp:my_step", "My Step", "test-tool-id",
                "trigger:start", null, true,
                Map.of("result", "{{mcp:my_step.output.result}}"),
                Collections.emptyList(), null, Collections.emptyList(), true
            );

            assertHasNextPattern(response, "step");
            assertNextPatternHasMcpOutputSyntax(response);
        }

        @Test
        @DisplayName("Agent response should have NEXT pattern")
        void agentHasNextPattern() {
            addTriggerToSession("Start");

            Map<String, Object> response = agentBuilder.buildAgentResponse(
                session, "agent:my_agent", "My Agent",
                "trigger:start", null,
                Map.of("label", "My Agent", "prompt", "Test prompt"),
                Map.of("response", "{{agent:my_agent.output.response}}"),
                Collections.emptyList(), null, Collections.emptyList(), true
            );

            assertHasNextPattern(response, "agent");
            assertNextPatternHasAgentOutputSyntax(response);
        }

        @Test
        @DisplayName("Guardrail response should have NEXT pattern")
        void guardrailHasNextPattern() {
            addTriggerToSession("Start");

            Map<String, Object> response = agentBuilder.buildGuardrailResponse(
                session, "agent:my_guard", "My Guard",
                "trigger:start", null,
                List.of("no_profanity", "no_pii"),
                Map.of("passed", "{{agent:my_guard.passed}}"),
                true
            );

            assertHasNextPattern(response, "guardrail");
        }

        @Test
        @DisplayName("Classify response should have NEXT pattern")
        void classifyHasNextPattern() {
            addTriggerToSession("Start");

            List<Map<String, String>> categories = List.of(
                Map.of("label", "urgent", "description", "High priority items"),
                Map.of("label", "normal", "description", "Regular priority items"),
                Map.of("label", "low", "description", "Low priority items")
            );
            Map<String, Object> response = agentBuilder.buildClassifyResponse(
                session, "agent:my_classify", "My Classify",
                "trigger:start", null,
                categories,
                Map.of("category", "{{agent:my_classify.category}}"),
                true
            );

            // Classify NEXT has connect_to_categories, pattern, this_node_output, note (no get_params)
            assertThat(response)
                .as("Response for classify should have NEXT pattern")
                .containsKey("NEXT");
            @SuppressWarnings("unchecked")
            Map<String, Object> next = (Map<String, Object>) response.get("NEXT");
            assertThat(next)
                .as("NEXT pattern for classify should not be empty")
                .isNotEmpty();
            assertThat(next).containsKey("pattern");
            assertThat(next).containsKey("connect_to_categories");
        }

        @Test
        @DisplayName("Decision response should have NEXT pattern")
        void decisionHasNextPattern() {
            addTriggerToSession("Start");

            List<Map<String, Object>> conditions = List.of(
                Map.of("condition", "{{x > 10}}", "label", "High"),
                Map.of("label", "Low")
            );

            Map<String, Object> response = controlNodeBuilder.buildDecisionResponse(
                session, "core:my_decision", "My Decision", conditions
            );

            assertHasNextPattern(response, "decision");
        }

        @Test
        @DisplayName("Loop response should have NEXT pattern")
        void loopHasNextPattern() {
            addTriggerToSession("Start");

            Map<String, Object> response = controlNodeBuilder.buildLoopResponse(
                session, "core:my_loop", "My Loop", "{{count < 10}}"
            );

            assertHasNextPattern(response, "loop");
            assertLoopNextPatternHasInsideAndAfterLoop(response);
        }

        @Test
        @DisplayName("ForEach response should have NEXT pattern")
        void forEachHasNextPattern() {
            addTriggerToSession("Start");

            Map<String, Object> response = controlNodeBuilder.buildSplitResponse(
                session, "core:my_split", "My Split",
                "{{trigger:start.items}}", 100
            );

            assertHasNextPattern(response, "split");
        }
    }

    // ==================== Variable Syntax Consistency ====================

    @Nested
    @DisplayName("Variable Syntax Consistency")
    class VariableSyntaxConsistency {

        @Test
        @DisplayName("MCP step NEXT pattern should use .output. syntax")
        void mcpNextPatternUsesOutputSyntax() {
            addTriggerToSession("Start");

            Map<String, Object> response = triggerStepBuilder.buildStepResponse(
                session, "mcp:fetch_data", "Fetch Data", "test-tool-id",
                "trigger:start", null, true,
                Map.of("data", "{{mcp:fetch_data.output.data}}"),
                Collections.emptyList(), null, Collections.emptyList(), true
            );

            @SuppressWarnings("unchecked")
            Map<String, Object> next = (Map<String, Object>) response.get("NEXT");
            assertThat(next).isNotNull();

            String outputRef = (String) next.get("this_step_output");
            assertThat(outputRef).contains(".output.");
            assertThat(outputRef).matches("\\{\\{mcp:fetch_data\\.output\\.<field>\\}\\}");
        }

        @Test
        @DisplayName("Agent NEXT pattern should use UNIFIED .output. syntax")
        void agentNextPatternUsesOutputSyntax() {
            addTriggerToSession("Start");

            Map<String, Object> response = agentBuilder.buildAgentResponse(
                session, "agent:analyzer", "Analyzer",
                "trigger:start", null,
                Map.of("label", "Analyzer", "prompt", "Analyze"),
                Map.of("response", "{{agent:analyzer.output.response}}"),
                Collections.emptyList(), null, Collections.emptyList(), true
            );

            @SuppressWarnings("unchecked")
            Map<String, Object> next = (Map<String, Object>) response.get("NEXT");
            assertThat(next).isNotNull();

            String outputRef = (String) next.get("this_agent_output");
            assertThat(outputRef).contains(".output.");
            assertThat(outputRef).isEqualTo("{{agent:analyzer.output.response}}");
        }

        @Test
        @DisplayName("Trigger NEXT pattern should use UNIFIED .output. syntax")
        void triggerNextPatternUsesOutputSyntax() {
            Map<String, Object> response = triggerStepBuilder.buildTriggerResponse(
                session, "trigger:webhook", "Webhook", "webhook", null,
                Map.of("payload", "Object"),
                Map.of("payload", "{{trigger:webhook.output.payload}}")
            );

            @SuppressWarnings("unchecked")
            Map<String, Object> next = (Map<String, Object>) response.get("NEXT");
            assertThat(next).isNotNull();

            String dataRef = (String) next.get("your_data");
            assertThat(dataRef).contains(".output.");
            assertThat(dataRef).isEqualTo("{{trigger:webhook.output.<column>}}");
        }

        @Test
        @DisplayName("Core node NEXT pattern should use UNIFIED .output. syntax")
        void coreNextPatternUsesOutputSyntax() {
            addTriggerToSession("Start");

            Map<String, Object> response = controlNodeBuilder.buildSplitResponse(
                session, "core:process_items", "Process Items",
                "{{trigger:start.output.items}}", 50
            );

            @SuppressWarnings("unchecked")
            Map<String, Object> next = (Map<String, Object>) response.get("NEXT");
            assertThat(next).isNotNull();

            String itemSyntax = (String) next.get("current_item_syntax");
            assertThat(itemSyntax).contains(".output.");
            assertThat(itemSyntax).contains("{{core:process_items.output.current_item}}");
        }
    }

    // ==================== Status Field Consistency ====================

    @Nested
    @DisplayName("Status Field Consistency")
    class StatusFieldConsistency {

        @Test
        @DisplayName("All responses should have status=OK on success")
        void allResponsesHaveStatusOk() {
            addTriggerToSession("Start");

            // Trigger
            Map<String, Object> triggerResp = triggerStepBuilder.buildTriggerResponse(
                session, "trigger:test", "Test", "table", "1",
                Collections.emptyMap(), Collections.emptyMap()
            );
            assertThat(triggerResp.get("status")).isEqualTo("OK");

            // Step
            Map<String, Object> stepResp = triggerStepBuilder.buildStepResponse(
                session, "mcp:test", "Test", "tool-id",
                "trigger:start", null, true,
                Collections.emptyMap(), Collections.emptyList(), null, Collections.emptyList(), true
            );
            assertThat(stepResp.get("status")).isEqualTo("OK");

            // Agent
            Map<String, Object> agentResp = agentBuilder.buildAgentResponse(
                session, "agent:test", "Test",
                "trigger:start", null,
                Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyList(), null, Collections.emptyList(), true
            );
            assertThat(agentResp.get("status")).isEqualTo("OK");

            // Decision
            Map<String, Object> decisionResp = controlNodeBuilder.buildDecisionResponse(
                session, "core:test", "Test",
                List.of(Map.of("label", "A"), Map.of("label", "B"))
            );
            assertThat(decisionResp.get("status")).isEqualTo("OK");

            // Loop
            Map<String, Object> loopResp = controlNodeBuilder.buildLoopResponse(
                session, "core:test", "Test", "true"
            );
            assertThat(loopResp.get("status")).isEqualTo("OK");

            // ForEach
            Map<String, Object> forEachResp = controlNodeBuilder.buildSplitResponse(
                session, "core:test", "Test", "{{items}}", 100
            );
            assertThat(forEachResp.get("status")).isEqualTo("OK");
        }
    }

    // ==================== Node ID Format Tests ====================

    @Nested
    @DisplayName("Node ID Format")
    class NodeIdFormat {

        @Test
        @DisplayName("Trigger node_id should have trigger: prefix")
        void triggerNodeIdHasCorrectPrefix() {
            Map<String, Object> response = triggerStepBuilder.buildTriggerResponse(
                session, "trigger:my_trigger", "My Trigger", "table", "123",
                Collections.emptyMap(), Collections.emptyMap()
            );

            String nodeId = (String) response.get("node_id");
            assertThat(nodeId).startsWith("trigger:");
        }

        @Test
        @DisplayName("MCP step node_id should have mcp: prefix")
        void stepNodeIdHasCorrectPrefix() {
            addTriggerToSession("Start");

            Map<String, Object> response = triggerStepBuilder.buildStepResponse(
                session, "mcp:my_step", "My Step", "tool-id",
                "trigger:start", null, true,
                Collections.emptyMap(), Collections.emptyList(), null, Collections.emptyList(), true
            );

            String nodeId = (String) response.get("node_id");
            assertThat(nodeId).startsWith("mcp:");
        }

        @Test
        @DisplayName("Agent node_id should have agent: prefix")
        void agentNodeIdHasCorrectPrefix() {
            addTriggerToSession("Start");

            Map<String, Object> response = agentBuilder.buildAgentResponse(
                session, "agent:my_agent", "My Agent",
                "trigger:start", null,
                Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyList(), null, Collections.emptyList(), true
            );

            String nodeId = (String) response.get("node_id");
            assertThat(nodeId).startsWith("agent:");
        }

        @Test
        @DisplayName("Decision node_id should have core: prefix")
        void decisionNodeIdHasCorrectPrefix() {
            addTriggerToSession("Start");

            Map<String, Object> response = controlNodeBuilder.buildDecisionResponse(
                session, "core:my_decision", "My Decision",
                List.of(Map.of("label", "A"), Map.of("label", "B"))
            );

            String nodeId = (String) response.get("node_id");
            assertThat(nodeId).startsWith("core:");
        }
    }

    // ==================== NEXT Pattern Structure Tests ====================

    @Nested
    @DisplayName("NEXT Pattern Structure")
    class NextPatternStructure {

        @Test
        @DisplayName("NEXT pattern should have 'pattern' field with connect_after")
        void nextPatternHasConnectAfter() {
            addTriggerToSession("Start");

            Map<String, Object> response = triggerStepBuilder.buildStepResponse(
                session, "mcp:api_call", "API Call", "tool-id",
                "trigger:start", null, true,
                Collections.emptyMap(), Collections.emptyList(), null, Collections.emptyList(), true
            );

            @SuppressWarnings("unchecked")
            Map<String, Object> next = (Map<String, Object>) response.get("NEXT");
            String pattern = (String) next.get("pattern");

            assertThat(pattern).contains("connect_after='API Call'");
        }

        @Test
        @DisplayName("NEXT pattern should have 'get_params' field pointing to workflow help")
        void nextPatternHasGetParams() {
            addTriggerToSession("Start");

            Map<String, Object> response = agentBuilder.buildAgentResponse(
                session, "agent:test", "Test Agent",
                "trigger:start", null,
                Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyList(), null, Collections.emptyList(), true
            );

            @SuppressWarnings("unchecked")
            Map<String, Object> next = (Map<String, Object>) response.get("NEXT");
            String getParams = (String) next.get("get_params");

            assertThat(getParams).contains("workflow(action='help'");
        }

        @Test
        @DisplayName("Loop NEXT should have inside_loop and after_loop patterns")
        void loopNextHasBothPatterns() {
            addTriggerToSession("Start");

            Map<String, Object> response = controlNodeBuilder.buildLoopResponse(
                session, "core:retry", "Retry", "{{count < 3}}"
            );

            @SuppressWarnings("unchecked")
            Map<String, Object> next = (Map<String, Object>) response.get("NEXT");

            assertThat(next).containsKey("inside_loop");
            assertThat(next).containsKey("after_loop");

            String insideLoop = (String) next.get("inside_loop");
            String afterLoop = (String) next.get("after_loop");

            assertThat(insideLoop).contains("connect_after='Retry'");
            assertThat(afterLoop).contains("connect_after_loop='Retry'");
        }

        @Test
        @DisplayName("Decision NEXT should have per_branch pattern with branch placeholder")
        void decisionNextHasBranchPattern() {
            addTriggerToSession("Start");

            Map<String, Object> response = controlNodeBuilder.buildDecisionResponse(
                session, "core:check", "Check",
                List.of(Map.of("label", "Yes"), Map.of("label", "No"))
            );

            @SuppressWarnings("unchecked")
            Map<String, Object> next = (Map<String, Object>) response.get("NEXT");

            assertThat(next).containsKey("per_branch");
            assertThat(next).containsKey("available_ports");

            String perBranch = (String) next.get("per_branch");
            assertThat(perBranch).contains("connect_after='Check:<port>'");
        }

        @Test
        @DisplayName("ForEach NEXT should have after_split and current_item_syntax with .output. syntax")
        void forEachNextHasItemAccess() {
            addTriggerToSession("Start");

            Map<String, Object> response = controlNodeBuilder.buildSplitResponse(
                session, "core:process", "Process",
                "{{items}}", 100
            );

            @SuppressWarnings("unchecked")
            Map<String, Object> next = (Map<String, Object>) response.get("NEXT");

            assertThat(next).containsKey("after_split");
            assertThat(next).containsKey("current_item_syntax");

            String itemSyntax = (String) next.get("current_item_syntax");
            assertThat(itemSyntax).contains("{{core:process.output.current_item}}");

            String indexSyntax = (String) next.get("index_syntax");
            assertThat(indexSyntax).contains("{{core:process.output.current_index}}");
        }
    }

    // ==================== Trigger Output Guidance Tests ====================

    @Nested
    @DisplayName("Trigger Output Guidance")
    class TriggerOutputGuidance {

        @Test
        @DisplayName("Table trigger should show available_columns")
        void tableTriggerShowsColumns() {
            Map<String, String> outputs = Map.of(
                "id", "Long",
                "email", "String",
                "name", "String"
            );
            Map<String, String> refs = Map.of(
                "id", "{{trigger:users.id}}",
                "email", "{{trigger:users.email}}",
                "name", "{{trigger:users.name}}"
            );

            Map<String, Object> response = triggerStepBuilder.buildTriggerResponse(
                session, "trigger:users", "Users", "table", "42",
                outputs, refs
            );

            assertThat(response).containsKey("available_columns");
            assertThat(response).containsKey("reference_syntax");
        }

        @Test
        @DisplayName("Table trigger should show table_id for CRUD operations")
        void tableTriggerShowsTableId() {
            Map<String, Object> response = triggerStepBuilder.buildTriggerResponse(
                session, "trigger:orders", "Orders", "table", "99",
                Collections.emptyMap(), Collections.emptyMap()
            );

            assertThat(response).containsKey("table_id");
            assertThat(response.get("table_id")).isEqualTo("99");
            assertThat(response).containsKey("table_id_usage");
        }

        @Test
        @DisplayName("Schedule trigger should indicate no input data")
        void scheduleTriggerIndicatesNoInputData() {
            Map<String, Object> response = triggerStepBuilder.buildTriggerResponse(
                session, "trigger:daily_job", "Daily Job", "schedule", null,
                Collections.emptyMap(), Collections.emptyMap()
            );

            @SuppressWarnings("unchecked")
            Map<String, Object> behavior = (Map<String, Object>) response.get("behavior");
            assertThat(behavior).isNotNull();
            assertThat(behavior).containsKey("no_input_data");
        }
    }

    // ==================== #A2: No stale PLAN_ONLY warning ====================

    @Nested
    @DisplayName("#A2 no stale PLAN_ONLY warning")
    class NoStalePlanOnlyWarning {

        @Test
        @DisplayName("Guardrail response must not include ⚠️_PLAN_ONLY block")
        void guardrailResponseHasNoPlanOnlyWarning() {
            addTriggerToSession("Start");

            Map<String, Object> response = agentBuilder.buildGuardrailResponse(
                session, "agent:my_guard", "My Guard",
                "trigger:start", null,
                List.of("no_profanity"),
                Map.of("passed", "{{agent:my_guard.output.passed}}"),
                true
            );

            assertThat(response)
                .as("guardrail runs at runtime - stale PLAN_ONLY warning must be removed (#A2)")
                .doesNotContainKey("⚠️_PLAN_ONLY");
        }

        @Test
        @DisplayName("Classify response must not include ⚠️_PLAN_ONLY block")
        void classifyResponseHasNoPlanOnlyWarning() {
            addTriggerToSession("Start");

            List<Map<String, String>> categories = List.of(
                Map.of("label", "urgent", "description", "High priority"),
                Map.of("label", "normal", "description", "Regular")
            );
            Map<String, Object> response = agentBuilder.buildClassifyResponse(
                session, "agent:my_classify", "My Classify",
                "trigger:start", null,
                categories,
                Map.of("category", "{{agent:my_classify.output.category}}"),
                true
            );

            assertThat(response)
                .as("classify runs at runtime - stale PLAN_ONLY warning must be removed (#A2)")
                .doesNotContainKey("⚠️_PLAN_ONLY");
        }
    }

    // ==================== Helper Methods ====================

    private void addTriggerToSession(String label) {
        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("label", label);
        trigger.put("id", "trigger:" + WorkflowBuilderSession.normalizeLabel(label));
        trigger.put("type", "table");
        session.getTriggers().add(trigger);
    }

    private void assertHasNextPattern(Map<String, Object> response, String nodeType) {
        assertThat(response)
            .as("Response for %s should have NEXT pattern", nodeType)
            .containsKey("NEXT");

        @SuppressWarnings("unchecked")
        Map<String, Object> next = (Map<String, Object>) response.get("NEXT");
        assertThat(next)
            .as("NEXT pattern for %s should not be empty", nodeType)
            .isNotEmpty();

        assertThat(next)
            .as("NEXT pattern for %s should have get_params field", nodeType)
            .containsKey("get_params");
    }

    private void assertNextPatternHasMcpOutputSyntax(Map<String, Object> response) {
        @SuppressWarnings("unchecked")
        Map<String, Object> next = (Map<String, Object>) response.get("NEXT");

        String outputRef = (String) next.get("this_step_output");
        assertThat(outputRef)
            .as("MCP step NEXT should show .output. syntax")
            .contains(".output.");
    }

    private void assertNextPatternHasAgentOutputSyntax(Map<String, Object> response) {
        @SuppressWarnings("unchecked")
        Map<String, Object> next = (Map<String, Object>) response.get("NEXT");

        String outputRef = (String) next.get("this_agent_output");
        assertThat(outputRef)
            .as("Agent NEXT should use unified .output. syntax")
            .contains(".output.");
        assertThat(outputRef)
            .as("Agent NEXT should show .output.response field")
            .contains(".output.response");
    }

    private void assertLoopNextPatternHasInsideAndAfterLoop(Map<String, Object> response) {
        @SuppressWarnings("unchecked")
        Map<String, Object> next = (Map<String, Object>) response.get("NEXT");

        assertThat(next).containsKey("inside_loop");
        assertThat(next).containsKey("after_loop");
    }
}
