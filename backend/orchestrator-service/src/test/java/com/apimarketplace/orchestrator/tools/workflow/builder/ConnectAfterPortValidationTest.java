package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.service.NodeLibraryService;
import com.apimarketplace.orchestrator.tools.workflow.builder.creators.DecisionNodeCreator;
import com.apimarketplace.orchestrator.tools.workflow.builder.creators.ForkMergeNodeCreator;
import com.apimarketplace.orchestrator.tools.workflow.builder.creators.UtilityNodeCreator;
import com.apimarketplace.orchestrator.tools.workflow.builder.response.ControlNodeResponseBuilder;
import com.apimarketplace.orchestrator.tools.workflow.builder.session.SessionNodeFinder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.lenient;

/**
 * Tests for port validation in connect_after references.
 * Verifies that:
 * - Invalid ports produce clear error messages with valid port lists
 * - Valid ports resolve correctly
 * - Decision response includes actual port names per branch
 * - saved_params are sanitized (no raw LLM fields, no dashed condition IDs)
 * - SessionNodeFinder handles approval/choice ports
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConnectAfter Port Validation")
class ConnectAfterPortValidationTest {

    @Mock
    private WorkflowBuilderSessionStore sessionStore;

    @Mock
    private ResponseOptimizer responseOptimizer;

    @Mock
    private NodeLibraryService nodeLibraryService;

    @Mock
    private WorkflowRepository workflowRepository;

    private DecisionNodeCreator decisionNodeCreator;
    private UtilityNodeCreator utilityNodeCreator;
    private ForkMergeNodeCreator forkMergeNodeCreator;
    private WorkflowBuilderSession session;

    @BeforeEach
    void setUp() {
        decisionNodeCreator = new DecisionNodeCreator(sessionStore, responseOptimizer);
        utilityNodeCreator = new UtilityNodeCreator(sessionStore, responseOptimizer, nodeLibraryService, workflowRepository);
        forkMergeNodeCreator = new ForkMergeNodeCreator(sessionStore);

        session = WorkflowBuilderSession.builder()
            .sessionId("test-session")
            .tenantId("test-tenant")
            .workflowName("Test Workflow")
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();

        lenient().when(responseOptimizer.buildDecisionResponse(any(), any(), any(), any()))
            .thenReturn(new LinkedHashMap<>(Map.of("status", "OK")));
    }

    private void addTrigger(String label) {
        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("label", label);
        trigger.put("id", "trigger:" + WorkflowBuilderSession.normalizeLabel(label));
        trigger.put("type", "webhook");
        session.getTriggers().add(trigger);
    }

    private void addDecisionNode(String label, List<Map<String, Object>> conditions) {
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("id", "core:" + normalizedLabel);
        decision.put("label", label);
        decision.put("type", "decision");

        List<Map<String, Object>> conditionsList = new ArrayList<>();
        int elseifIndex = 0;
        for (int i = 0; i < conditions.size(); i++) {
            Map<String, Object> cond = conditions.get(i);
            String expression = (String) cond.get("condition");
            String branchLabel = (String) cond.get("label");
            String condType;
            if (i == 0) {
                condType = "if";
            } else if ("default".equalsIgnoreCase(expression)) {
                condType = "else";
            } else {
                condType = "elseif";
                elseifIndex++;
            }
            String condId = normalizedLabel + "-" + condType + (condType.equals("elseif") ? "-" + (elseifIndex - 1) : "");
            Map<String, Object> condMap = new LinkedHashMap<>();
            condMap.put("id", condId);
            condMap.put("type", condType);
            condMap.put("label", branchLabel != null ? branchLabel : "Branch");
            condMap.put("expression", expression != null ? expression : "default");
            conditionsList.add(condMap);
        }
        decision.put("decisionConditions", conditionsList);
        session.getCores().add(decision);
    }

    private void addApprovalNode(String label) {
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        Map<String, Object> approval = new LinkedHashMap<>();
        approval.put("id", "core:" + normalizedLabel);
        approval.put("label", label);
        approval.put("type", "approval");
        session.getCores().add(approval);
    }

    private void addForkNode(String label, int branches) {
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        Map<String, Object> fork = new LinkedHashMap<>();
        fork.put("id", "core:" + normalizedLabel);
        fork.put("label", label);
        fork.put("type", "fork");
        List<Map<String, Object>> forkOutputs = new ArrayList<>();
        for (int i = 0; i < branches; i++) {
            forkOutputs.add(Map.of("label", "Branch " + i));
        }
        fork.put("forkOutputs", forkOutputs);
        session.getCores().add(fork);
    }

    private void addLoopNode(String label) {
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        Map<String, Object> loop = new LinkedHashMap<>();
        loop.put("id", "core:" + normalizedLabel);
        loop.put("label", label);
        loop.put("type", "loop");
        session.getCores().add(loop);
    }

    private void addSwitchNode(String label, int cases, boolean withDefault) {
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        Map<String, Object> switchNode = new LinkedHashMap<>();
        switchNode.put("id", "core:" + normalizedLabel);
        switchNode.put("label", label);
        switchNode.put("type", "switch");
        List<Map<String, Object>> switchCases = new ArrayList<>();
        for (int i = 0; i < cases; i++) {
            switchCases.add(Map.of("type", "case", "label", "Case " + i));
        }
        if (withDefault) {
            switchCases.add(Map.of("type", "default", "label", "Default"));
        }
        switchNode.put("switchCases", switchCases);
        session.getCores().add(switchNode);
    }

    private void addOptionNode(String label, int choices) {
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("id", "core:" + normalizedLabel);
        option.put("label", label);
        option.put("type", "option");
        List<Map<String, Object>> optionChoices = new ArrayList<>();
        for (int i = 0; i < choices; i++) {
            optionChoices.add(Map.of("label", "Choice " + i));
        }
        option.put("optionChoices", optionChoices);
        session.getCores().add(option);
    }

    /**
     * Helper: attempt to add a decision node with the given connect_after.
     * This triggers validateConnectAfter() internally.
     * A failed result with "Invalid port" means port validation caught the issue.
     */
    private ToolExecutionResult addDecisionWithConnectAfter(String connectAfter) {
        List<Map<String, Object>> conditions = List.of(
            Map.of("condition", "{{x == 1}}", "label", "A"),
            Map.of("condition", "default", "label", "B")
        );
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("label", "Test Decision " + UUID.randomUUID().toString().substring(0, 4));
        parameters.put("conditions", conditions);
        parameters.put("connect_after", connectAfter);
        return decisionNodeCreator.executeAddDecision(session, parameters);
    }

    // ==================== Invalid Port Detection ====================

    @Nested
    @DisplayName("Invalid port detection (error messages)")
    class InvalidPortDetection {

        @BeforeEach
        void addNodes() {
            addTrigger("Start");
            addDecisionNode("Verifier Validation", List.of(
                Map.of("condition", "{{status == 'ok'}}", "label", "OK"),
                Map.of("condition", "{{status == 'warn'}}", "label", "Warning"),
                Map.of("condition", "default", "label", "Error")
            ));
        }

        @Test
        @DisplayName("Label:approve → error with valid ports if/elseif_0/else")
        void shouldRejectApprovePortOnDecision() {
            ToolExecutionResult result = addDecisionWithConnectAfter("Verifier Validation:approve");

            assertThat(result.success()).isFalse();
            String output = result.error();
            assertThat(output).contains("Invalid port 'approve'");
            assertThat(output).contains("Valid ports:");
            assertThat(output).contains("if");
            assertThat(output).contains("elseif_0");
            assertThat(output).contains("else");
        }

        @Test
        @DisplayName("Label:elseif-0 (dash) → error mentioning valid elseif_0 (underscore)")
        void shouldRejectDashedElseifPort() {
            ToolExecutionResult result = addDecisionWithConnectAfter("Verifier Validation:elseif-0");

            assertThat(result.success()).isFalse();
            String output = result.error();
            assertThat(output).contains("Invalid port 'elseif-0'");
            assertThat(output).contains("elseif_0");
        }

        @Test
        @DisplayName("Label:reject → error with valid ports")
        void shouldRejectRejectPort() {
            ToolExecutionResult result = addDecisionWithConnectAfter("Verifier Validation:reject");

            assertThat(result.success()).isFalse();
            String output = result.error();
            assertThat(output).contains("Invalid port 'reject'");
            assertThat(output).contains("Valid ports:");
        }

        @Test
        @DisplayName("Label:yes on decision → error with valid ports")
        void shouldRejectYesPort() {
            ToolExecutionResult result = addDecisionWithConnectAfter("Verifier Validation:yes");

            assertThat(result.success()).isFalse();
            String output = result.error();
            assertThat(output).contains("Invalid port 'yes'");
            assertThat(output).contains("if");
        }

        @Test
        @DisplayName("Label:verifier_validation-if → error (dashed condition ID, not a port)")
        void shouldRejectConditionIdAsPort() {
            ToolExecutionResult result = addDecisionWithConnectAfter("Verifier Validation:verifier_validation-if");

            assertThat(result.success()).isFalse();
            String output = result.error();
            assertThat(output).contains("Invalid port");
        }
    }

    // ==================== Port Range Validation ====================

    @Nested
    @DisplayName("Port range validation")
    class PortRangeValidation {

        @Test
        @DisplayName("elseif_5 on 2-condition decision → out of range")
        void shouldRejectOutOfRangeElseif() {
            addTrigger("Start");
            addDecisionNode("Check", List.of(
                Map.of("condition", "{{x == 1}}", "label", "Yes"),
                Map.of("condition", "default", "label", "No")
            ));

            ToolExecutionResult result = addDecisionWithConnectAfter("Check:elseif_5");

            assertThat(result.success()).isFalse();
            // elseif_5 is a valid port pattern but out of range for this decision
        }

        @Test
        @DisplayName("Decision with no conditions → error")
        void shouldRejectPortOnEmptyDecision() {
            addTrigger("Start");
            // Manually add a decision with empty conditions
            String normalizedLabel = WorkflowBuilderSession.normalizeLabel("Empty Check");
            Map<String, Object> decision = new LinkedHashMap<>();
            decision.put("id", "core:" + normalizedLabel);
            decision.put("label", "Empty Check");
            decision.put("type", "decision");
            decision.put("decisionConditions", List.of());
            session.getCores().add(decision);

            ToolExecutionResult result = addDecisionWithConnectAfter("Empty Check:if");

            assertThat(result.success()).isFalse();
        }
    }

    // ==================== Valid Port Resolution ====================

    @Nested
    @DisplayName("Valid port resolution")
    class ValidPortResolution {

        @Test
        @DisplayName("Label:if resolves correctly for decision")
        void shouldResolveDecisionIfPort() {
            addTrigger("Start");
            addDecisionNode("Check Status", List.of(
                Map.of("condition", "{{x == 1}}", "label", "Yes"),
                Map.of("condition", "default", "label", "No")
            ));

            ToolExecutionResult result = addDecisionWithConnectAfter("Check Status:if");
            assertThat(result.success()).isTrue();
        }

        @Test
        @DisplayName("Label:else resolves correctly")
        void shouldResolveDecisionElsePort() {
            addTrigger("Start");
            addDecisionNode("Check Status", List.of(
                Map.of("condition", "{{x == 1}}", "label", "Yes"),
                Map.of("condition", "default", "label", "No")
            ));

            ToolExecutionResult result = addDecisionWithConnectAfter("Check Status:else");
            assertThat(result.success()).isTrue();
        }

        @Test
        @DisplayName("Label:elseif_0 resolves correctly")
        void shouldResolveDecisionElseifPort() {
            addTrigger("Start");
            addDecisionNode("Check Status", List.of(
                Map.of("condition", "{{x == 1}}", "label", "Yes"),
                Map.of("condition", "{{x == 2}}", "label", "Maybe"),
                Map.of("condition", "default", "label", "No")
            ));

            ToolExecutionResult result = addDecisionWithConnectAfter("Check Status:elseif_0");
            assertThat(result.success()).isTrue();
        }

        @Test
        @DisplayName("Label:branch_0 resolves for fork")
        void shouldResolveForkBranchPort() {
            addTrigger("Start");
            addForkNode("Parallel", 3);

            ToolExecutionResult result = addDecisionWithConnectAfter("Parallel:branch_0");
            assertThat(result.success()).isTrue();
        }

        @Test
        @DisplayName("Label:case_0 resolves for switch")
        void shouldResolveSwitchCasePort() {
            addTrigger("Start");
            addSwitchNode("Route", 2, true);

            ToolExecutionResult result = addDecisionWithConnectAfter("Route:case_0");
            assertThat(result.success()).isTrue();
        }

        @Test
        @DisplayName("Label:default resolves for switch")
        void shouldResolveSwitchDefaultPort() {
            addTrigger("Start");
            addSwitchNode("Route", 2, true);

            ToolExecutionResult result = addDecisionWithConnectAfter("Route:default");
            assertThat(result.success()).isTrue();
        }

        @Test
        @DisplayName("Label:body resolves for loop")
        void shouldResolveLoopBodyPort() {
            addTrigger("Start");
            addLoopNode("Iterate");

            ToolExecutionResult result = addDecisionWithConnectAfter("Iterate:body");
            assertThat(result.success()).isTrue();
        }

        @Test
        @DisplayName("Label:exit resolves for loop")
        void shouldResolveLoopExitPort() {
            addTrigger("Start");
            addLoopNode("Iterate");

            ToolExecutionResult result = addDecisionWithConnectAfter("Iterate:exit");
            assertThat(result.success()).isTrue();
        }

        @Test
        @DisplayName("Label:iterate resolves for loop (regression: CreatorBase.isValidPort had omitted 'iterate')")
        void shouldResolveLoopIteratePort() {
            addTrigger("Start");
            addLoopNode("Repeat");

            // Pre-fix, isValidPort('iterate') returned false → connect_after to a
            // loop's iterate port was rejected as "Invalid port 'iterate'".
            ToolExecutionResult result = addDecisionWithConnectAfter("Repeat:iterate");
            assertThat(result.success()).isTrue();
        }

        @Test
        @DisplayName("Label:approved resolves for approval")
        void shouldResolveApprovalApprovedPort() {
            addTrigger("Start");
            addApprovalNode("Review");

            ToolExecutionResult result = addDecisionWithConnectAfter("Review:approved");
            assertThat(result.success()).isTrue();
        }

        @Test
        @DisplayName("Label:rejected resolves for approval")
        void shouldResolveApprovalRejectedPort() {
            addTrigger("Start");
            addApprovalNode("Review");

            ToolExecutionResult result = addDecisionWithConnectAfter("Review:rejected");
            assertThat(result.success()).isTrue();
        }

        @Test
        @DisplayName("Label:timeout resolves for approval")
        void shouldResolveApprovalTimeoutPort() {
            addTrigger("Start");
            addApprovalNode("Review");

            ToolExecutionResult result = addDecisionWithConnectAfter("Review:timeout");
            assertThat(result.success()).isTrue();
        }
    }

    // ==================== Decision Response Format ====================

    @Nested
    @DisplayName("Decision response format")
    class DecisionResponseFormat {

        @Test
        @DisplayName("buildDecisionResponse includes port per branch")
        void shouldIncludePortPerBranch() {
            addTrigger("Start");

            ResponseContextBuilder contextBuilder = new ResponseContextBuilder();
            ControlNodeResponseBuilder builder = new ControlNodeResponseBuilder(contextBuilder);

            // Add decision to session first (needed for getLogicalId)
            addDecisionNode("Check", List.of(
                Map.of("condition", "{{x == 1}}", "label", "Yes"),
                Map.of("condition", "{{x == 2}}", "label", "Maybe"),
                Map.of("condition", "default", "label", "No")
            ));

            // Build response with typed conditions
            List<Map<String, Object>> conditions = List.of(
                Map.of("condition", "{{x == 1}}", "label", "Yes", "type", "if"),
                Map.of("condition", "{{x == 2}}", "label", "Maybe", "type", "elseif"),
                Map.of("condition", "default", "label", "No", "type", "else")
            );
            Map<String, Object> response = builder.buildDecisionResponse(session, "core:check", "Check", conditions);

            assertThat(response).containsKey("branches");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> branches = (List<Map<String, Object>>) response.get("branches");
            assertThat(branches).hasSize(3);

            // First branch = if
            assertThat(branches.get(0)).containsEntry("port", "if");
            assertThat(branches.get(0)).containsEntry("connect_after", "Check:if");

            // Second branch = elseif_0
            assertThat(branches.get(1)).containsEntry("port", "elseif_0");
            assertThat(branches.get(1)).containsEntry("connect_after", "Check:elseif_0");

            // Third branch = else
            assertThat(branches.get(2)).containsEntry("port", "else");
            assertThat(branches.get(2)).containsEntry("connect_after", "Check:else");
        }

        @Test
        @DisplayName("NEXT.available_ports is a list of actual ports")
        void shouldHaveAvailablePortsList() {
            addTrigger("Start");

            ResponseContextBuilder contextBuilder = new ResponseContextBuilder();
            ControlNodeResponseBuilder builder = new ControlNodeResponseBuilder(contextBuilder);

            addDecisionNode("Route", List.of(
                Map.of("condition", "{{x == 1}}", "label", "A"),
                Map.of("condition", "default", "label", "B")
            ));

            List<Map<String, Object>> conditions = List.of(
                Map.of("condition", "{{x == 1}}", "label", "A", "type", "if"),
                Map.of("condition", "default", "label", "B", "type", "else")
            );
            Map<String, Object> response = builder.buildDecisionResponse(session, "core:route", "Route", conditions);

            @SuppressWarnings("unchecked")
            Map<String, Object> next = (Map<String, Object>) response.get("NEXT");
            assertThat(next).containsKey("available_ports");
            assertThat(next).containsKey("examples");

            @SuppressWarnings("unchecked")
            List<String> availablePorts = (List<String>) next.get("available_ports");
            assertThat(availablePorts).containsExactly("if", "else");

            @SuppressWarnings("unchecked")
            List<String> examples = (List<String>) next.get("examples");
            assertThat(examples).containsExactly("Route:if", "Route:else");
        }
    }

    // ==================== saved_params Sanitization ====================

    @Nested
    @DisplayName("saved_params sanitization")
    class SavedParamsSanitization {

        @Test
        @DisplayName("saved_params should NOT contain raw LLM 'port' field")
        @SuppressWarnings("unchecked")
        void shouldNotContainRawLlmPortField() {
            addTrigger("Start");

            // Simulate LLM sending a "port": "approve" field (incorrect)
            List<Map<String, Object>> conditions = new ArrayList<>();
            Map<String, Object> cond1 = new LinkedHashMap<>();
            cond1.put("condition", "{{status == 'ok'}}");
            cond1.put("label", "Approved");
            cond1.put("port", "approve"); // LLM-provided junk
            conditions.add(cond1);
            Map<String, Object> cond2 = new LinkedHashMap<>();
            cond2.put("condition", "default");
            cond2.put("label", "Rejected");
            conditions.add(cond2);

            lenient().when(responseOptimizer.buildDecisionResponse(any(), any(), any(), any()))
                .thenReturn(new LinkedHashMap<>(Map.of("status", "OK")));

            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("label", "Verify");
            parameters.put("conditions", conditions);
            parameters.put("connect_after", "Start");

            ToolExecutionResult result = decisionNodeCreator.executeAddDecision(session, parameters);
            assertThat(result.success()).isTrue();

            // Extract saved_params from the result
            Map<String, Object> output = (Map<String, Object>) result.data();
            Map<String, Object> savedParams = (Map<String, Object>) output.get("saved_params");
            assertThat(savedParams).isNotNull();

            List<Map<String, Object>> savedConditions = (List<Map<String, Object>>) savedParams.get("conditions");
            assertThat(savedConditions).isNotNull();
            assertThat(savedConditions).hasSize(2);

            // First condition should have computed port "if", not LLM's "approve"
            Map<String, Object> first = savedConditions.get(0);
            assertThat(first.get("port")).isEqualTo("if");
            assertThat(first).doesNotContainKey("id"); // No dashed condition ID

            // Second condition should have computed port "else"
            Map<String, Object> second = savedConditions.get(1);
            assertThat(second.get("port")).isEqualTo("else");
            assertThat(second).doesNotContainKey("id");
        }

        @Test
        @DisplayName("saved_params includes computed port names (if, elseif_0, else)")
        @SuppressWarnings("unchecked")
        void shouldIncludeComputedPortNames() {
            addTrigger("Start");

            lenient().when(responseOptimizer.buildDecisionResponse(any(), any(), any(), any()))
                .thenReturn(new LinkedHashMap<>(Map.of("status", "OK")));

            List<Map<String, Object>> conditions = List.of(
                Map.of("condition", "{{x == 1}}", "label", "A"),
                Map.of("condition", "{{x == 2}}", "label", "B"),
                Map.of("condition", "default", "label", "C")
            );

            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("label", "Route");
            parameters.put("conditions", conditions);
            parameters.put("connect_after", "Start");

            ToolExecutionResult result = decisionNodeCreator.executeAddDecision(session, parameters);
            assertThat(result.success()).isTrue();

            Map<String, Object> output = (Map<String, Object>) result.data();
            Map<String, Object> savedParams = (Map<String, Object>) output.get("saved_params");
            List<Map<String, Object>> savedConditions = (List<Map<String, Object>>) savedParams.get("conditions");

            assertThat(savedConditions.get(0).get("port")).isEqualTo("if");
            assertThat(savedConditions.get(0).get("type")).isEqualTo("if");
            assertThat(savedConditions.get(1).get("port")).isEqualTo("elseif_0");
            assertThat(savedConditions.get(1).get("type")).isEqualTo("elseif");
            assertThat(savedConditions.get(2).get("port")).isEqualTo("else");
            assertThat(savedConditions.get(2).get("type")).isEqualTo("else");
        }
    }

    // ==================== SessionNodeFinder Port Patterns ====================

    @Nested
    @DisplayName("SessionNodeFinder port patterns")
    class SessionNodeFinderPorts {

        @Test
        @DisplayName("resolveNodeReference('Label:approved') resolves correctly")
        void shouldResolveApprovedPort() {
            addTrigger("Start");
            addApprovalNode("Review");

            String resolved = session.resolveNodeReference("Review:approved");
            assertThat(resolved).isEqualTo("core:review:approved");
        }

        @Test
        @DisplayName("resolveNodeReference('Label:rejected') resolves correctly")
        void shouldResolveRejectedPort() {
            addTrigger("Start");
            addApprovalNode("Review");

            String resolved = session.resolveNodeReference("Review:rejected");
            assertThat(resolved).isEqualTo("core:review:rejected");
        }

        @Test
        @DisplayName("resolveNodeReference('Label:timeout') resolves correctly")
        void shouldResolveTimeoutPort() {
            addTrigger("Start");
            addApprovalNode("Review");

            String resolved = session.resolveNodeReference("Review:timeout");
            assertThat(resolved).isEqualTo("core:review:timeout");
        }

        @Test
        @DisplayName("resolveNodeReference('Label:choice_0') resolves correctly")
        void shouldResolveChoicePort() {
            addTrigger("Start");
            addOptionNode("Pick", 3);

            String resolved = session.resolveNodeReference("Pick:choice_0");
            assertThat(resolved).isEqualTo("core:pick:choice_0");
        }

        @Test
        @DisplayName("nodeExists('core:x:approved') returns true for approval node")
        void shouldRecognizeApprovalPortInNodeExists() {
            addApprovalNode("Review");

            boolean exists = session.nodeExists("core:review:approved");
            assertThat(exists).isTrue();
        }

        @Test
        @DisplayName("nodeExists('core:x:choice_0') returns true for option node")
        void shouldRecognizeChoicePortInNodeExists() {
            addOptionNode("Pick", 3);

            boolean exists = session.nodeExists("core:pick:choice_0");
            assertThat(exists).isTrue();
        }

        @Test
        @DisplayName("extractBaseNodeId works for approval ports via nodeExists")
        void shouldExtractBaseForApprovalPort() {
            addApprovalNode("Manager Approval");

            // nodeExists uses extractBaseNodeId internally
            assertThat(session.nodeExists("core:manager_approval:approved")).isTrue();
            assertThat(session.nodeExists("core:manager_approval:rejected")).isTrue();
            assertThat(session.nodeExists("core:manager_approval:timeout")).isTrue();
        }

        @Test
        @DisplayName("extractBaseNodeId works for choice ports via nodeExists")
        void shouldExtractBaseForChoicePort() {
            addOptionNode("Route Request", 3);

            assertThat(session.nodeExists("core:route_request:choice_0")).isTrue();
            assertThat(session.nodeExists("core:route_request:choice_1")).isTrue();
            assertThat(session.nodeExists("core:route_request:choice_2")).isTrue();
        }
    }

    // ==================== Switch saved_params Sanitization ====================

    @Nested
    @DisplayName("Switch saved_params sanitization")
    class SwitchSavedParamsSanitization {

        @Test
        @DisplayName("Switch saved_params should NOT contain dashed IDs")
        @SuppressWarnings("unchecked")
        void shouldNotContainDashedIds() {
            addTrigger("Start");

            List<Map<String, Object>> cases = List.of(
                Map.of("type", "case", "label", "High", "value", "high"),
                Map.of("type", "case", "label", "Low", "value", "low"),
                Map.of("type", "default", "label", "Other")
            );

            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("label", "Priority");
            parameters.put("expression", "{{input.priority}}");
            parameters.put("cases", cases);
            parameters.put("connect_after", "Start");

            ToolExecutionResult result = decisionNodeCreator.executeAddSwitch(session, parameters);
            assertThat(result.success()).isTrue();

            Map<String, Object> output = (Map<String, Object>) result.data();
            Map<String, Object> savedParams = (Map<String, Object>) output.get("saved_params");
            assertThat(savedParams).isNotNull();

            List<Map<String, Object>> savedCases = (List<Map<String, Object>>) savedParams.get("cases");
            assertThat(savedCases).hasSize(3);

            // No dashed IDs
            for (Map<String, Object> c : savedCases) {
                assertThat(c).doesNotContainKey("id");
            }

            // Computed ports
            assertThat(savedCases.get(0).get("port")).isEqualTo("case_0");
            assertThat(savedCases.get(1).get("port")).isEqualTo("case_1");
            assertThat(savedCases.get(2).get("port")).isEqualTo("default");

            // Label and type preserved
            assertThat(savedCases.get(0).get("label")).isEqualTo("High");
            assertThat(savedCases.get(2).get("type")).isEqualTo("default");
        }
    }

    // ==================== Option saved_params Sanitization ====================

    @Nested
    @DisplayName("Option saved_params sanitization")
    class OptionSavedParamsSanitization {

        @Test
        @DisplayName("Option saved_params should NOT contain dashed IDs")
        @SuppressWarnings("unchecked")
        void shouldNotContainDashedIds() {
            addTrigger("Start");

            List<Map<String, Object>> choices = List.of(
                Map.of("label", "Fast", "expression", "{{speed}} > 100"),
                Map.of("label", "Slow", "expression", "true")
            );

            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("label", "Speed Check");
            parameters.put("choices", choices);
            parameters.put("connect_after", "Start");

            ToolExecutionResult result = decisionNodeCreator.executeAddOption(session, parameters);
            assertThat(result.success()).isTrue();

            Map<String, Object> output = (Map<String, Object>) result.data();
            Map<String, Object> savedParams = (Map<String, Object>) output.get("saved_params");
            assertThat(savedParams).isNotNull();

            List<Map<String, Object>> savedChoices = (List<Map<String, Object>>) savedParams.get("choices");
            assertThat(savedChoices).hasSize(2);

            // No dashed IDs
            for (Map<String, Object> c : savedChoices) {
                assertThat(c).doesNotContainKey("id");
            }

            // Computed ports
            assertThat(savedChoices.get(0).get("port")).isEqualTo("choice_0");
            assertThat(savedChoices.get(1).get("port")).isEqualTo("choice_1");

            // Label preserved
            assertThat(savedChoices.get(0).get("label")).isEqualTo("Fast");
        }
    }

    // ==================== Fork saved_params & NEXT Sanitization ====================

    @Nested
    @DisplayName("Fork saved_params and NEXT guidance")
    class ForkSavedParamsAndNext {

        @Test
        @DisplayName("Fork saved_params should have port per branch, no internal IDs")
        @SuppressWarnings("unchecked")
        void shouldHavePortsInSavedParams() {
            addTrigger("Start");

            List<Map<String, Object>> branches = List.of(
                Map.of("label", "Email"),
                Map.of("label", "SMS"),
                Map.of("label", "Push")
            );

            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("label", "Notify All");
            parameters.put("branches", branches);
            parameters.put("connect_after", "Start");

            ToolExecutionResult result = forkMergeNodeCreator.executeAddFork(session, parameters);
            assertThat(result.success()).isTrue();

            Map<String, Object> output = (Map<String, Object>) result.data();
            Map<String, Object> savedParams = (Map<String, Object>) output.get("saved_params");
            assertThat(savedParams).isNotNull();

            List<Map<String, Object>> savedBranches = (List<Map<String, Object>>) savedParams.get("branches");
            assertThat(savedBranches).hasSize(3);

            // No internal IDs
            for (Map<String, Object> b : savedBranches) {
                assertThat(b).doesNotContainKey("id");
            }

            // Computed ports
            assertThat(savedBranches.get(0).get("port")).isEqualTo("branch_0");
            assertThat(savedBranches.get(1).get("port")).isEqualTo("branch_1");
            assertThat(savedBranches.get(2).get("port")).isEqualTo("branch_2");

            // Labels preserved
            assertThat(savedBranches.get(0).get("label")).isEqualTo("Email");
        }

        @Test
        @DisplayName("Fork NEXT should show concrete port examples")
        @SuppressWarnings("unchecked")
        void shouldHaveConcretePortExamples() {
            addTrigger("Start");

            List<Map<String, Object>> branches = List.of(
                Map.of("label", "A"),
                Map.of("label", "B")
            );

            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("label", "Parallel");
            parameters.put("branches", branches);
            parameters.put("connect_after", "Start");

            ToolExecutionResult result = forkMergeNodeCreator.executeAddFork(session, parameters);
            assertThat(result.success()).isTrue();

            Map<String, Object> output = (Map<String, Object>) result.data();
            Map<String, Object> next = (Map<String, Object>) output.get("NEXT");
            assertThat(next).containsKey("available_ports");
            assertThat(next).containsKey("examples");

            List<String> examples = (List<String>) next.get("examples");
            assertThat(examples).containsExactly("Parallel:branch_0", "Parallel:branch_1");
        }
    }
}
