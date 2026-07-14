package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@code mock} parameter of {@code workflow(action='modify')}:
 * set / replace-whole / clear semantics, the params-nesting rescue, node-kind
 * refusals, and modify-time validation through the real plan parser.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowBuilderModifier - mock block handling")
class WorkflowBuilderModifierMockTest {

    @Mock
    private WorkflowBuilderSessionStore sessionStore;

    private WorkflowBuilderModifier modifier;

    @BeforeEach
    void setUp() {
        modifier = new WorkflowBuilderModifier(sessionStore);
    }

    private WorkflowBuilderSession createSession() {
        return WorkflowBuilderSession.builder()
                .sessionId("test-session")
                .tenantId("test-tenant")
                .workflowName("Test Workflow")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    private WorkflowBuilderSession sessionWithMcpNode() {
        WorkflowBuilderSession session = createSession();
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "gmail/gmail-list-messages");
        node.put("type", "mcp");
        node.put("label", "Fetch Emails");
        node.put("params", new LinkedHashMap<>(Map.of("userId", "me")));
        session.getMcps().add(node);
        return session;
    }

    private Map<String, Object> mcpNode(WorkflowBuilderSession session) {
        return session.getMcps().get(0);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ToolExecutionResult result) {
        return (Map<String, Object>) result.data();
    }

    @Nested
    @DisplayName("Set / replace / clear")
    class SetReplaceClear {

        @Test
        @DisplayName("mock-only modify (no params) sets the block and reports {configured:true, kind}")
        void mockOnlyModifySets() {
            WorkflowBuilderSession session = sessionWithMcpNode();

            ToolExecutionResult result = modifier.executeModifyNode(session, Map.of(
                    "node", "Fetch Emails",
                    "mock", Map.of("output", Map.of("messages", List.of(), "result_count", 0))));

            assertThat(result.success()).isTrue();
            assertThat(mcpNode(session)).containsKey("mock");
            Map<String, Object> report = data(result);
            assertThat((Map<String, Object>) report.get("mock"))
                    .containsEntry("configured", true)
                    .containsEntry("kind", "output");
            assertThat((java.util.Set<String>) report.get("modified_fields")).contains("mock");
            assertThat((String) report.get("mock_hint")).contains("mock_mode='off'");
        }

        @Test
        @DisplayName("the block is REPLACED whole on re-modify (stale keys never resurrect)")
        void replacedWholeNotMerged() {
            WorkflowBuilderSession session = sessionWithMcpNode();
            modifier.executeModifyNode(session, Map.of(
                    "node", "Fetch Emails",
                    "mock", Map.of("output", Map.of("old_key", 1))));

            modifier.executeModifyNode(session, Map.of(
                    "node", "Fetch Emails",
                    "mock", Map.of("output", Map.of("new_key", 2))));

            @SuppressWarnings("unchecked")
            Map<String, Object> mock = (Map<String, Object>) mcpNode(session).get("mock");
            @SuppressWarnings("unchecked")
            Map<String, Object> output = (Map<String, Object>) mock.get("output");
            assertThat(output).containsOnlyKeys("new_key");
        }

        @Test
        @DisplayName("mock={} removes the block and reports {configured:false}")
        void emptyObjectClears() {
            WorkflowBuilderSession session = sessionWithMcpNode();
            modifier.executeModifyNode(session, Map.of(
                    "node", "Fetch Emails",
                    "mock", Map.of("output", Map.of("x", 1))));

            ToolExecutionResult result = modifier.executeModifyNode(session, Map.of(
                    "node", "Fetch Emails",
                    "mock", Map.of()));

            assertThat(result.success()).isTrue();
            assertThat(mcpNode(session)).doesNotContainKey("mock");
            assertThat((Map<String, Object>) data(result).get("mock"))
                    .containsEntry("configured", false);
        }

        @Test
        @DisplayName("undo restores the previous mock block")
        void undoRestoresPreviousMock() {
            WorkflowBuilderSession session = sessionWithMcpNode();
            modifier.executeModifyNode(session, Map.of(
                    "node", "Fetch Emails",
                    "mock", Map.of("output", Map.of("v", 1))));
            modifier.executeModifyNode(session, Map.of(
                    "node", "Fetch Emails",
                    "mock", Map.of("output", Map.of("v", 2))));

            modifier.executeUndo(session);

            @SuppressWarnings("unchecked")
            Map<String, Object> mock = (Map<String, Object>) mcpNode(session).get("mock");
            @SuppressWarnings("unchecked")
            Map<String, Object> output = (Map<String, Object>) mock.get("output");
            assertThat(output).containsEntry("v", 1);
        }

        @Test
        @DisplayName("catalog_example is accepted on a catalog-tool node (slug id)")
        void catalogExampleAccepted() {
            WorkflowBuilderSession session = sessionWithMcpNode();

            ToolExecutionResult result = modifier.executeModifyNode(session, Map.of(
                    "node", "Fetch Emails",
                    "mock", Map.of("source", "catalog_example")));

            assertThat(result.success()).isTrue();
            assertThat((Map<String, Object>) data(result).get("mock"))
                    .containsEntry("kind", "catalog_example");
        }
    }

    @Nested
    @DisplayName("Params-nesting rescue")
    class ParamsNestingRescue {

        @Test
        @DisplayName("mock nested inside params is pulled to the node top level (never sent to the real API)")
        void nestedMockRescued() {
            WorkflowBuilderSession session = sessionWithMcpNode();

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("userId", "me");
            params.put("mock", Map.of("output", Map.of("x", 1)));
            ToolExecutionResult result = modifier.executeModifyNode(session, Map.of(
                    "node", "Fetch Emails",
                    "params", params));

            assertThat(result.success()).isTrue();
            Map<String, Object> node = mcpNode(session);
            assertThat(node).containsKey("mock");
            @SuppressWarnings("unchecked")
            Map<String, Object> nodeParams = (Map<String, Object>) node.get("params");
            assertThat(nodeParams).doesNotContainKey("mock");
        }
    }

    @Nested
    @DisplayName("Refusals and validation")
    class RefusalsAndValidation {

        @Test
        @DisplayName("mock on a TRIGGER node is refused with the data_inputs guidance")
        void triggerRefused() {
            WorkflowBuilderSession session = createSession();
            Map<String, Object> trigger = new LinkedHashMap<>();
            trigger.put("id", "trigger:my_webhook");
            trigger.put("type", "webhook");
            trigger.put("label", "My Webhook");
            session.getTriggers().add(trigger);

            ToolExecutionResult result = modifier.executeModifyNode(session, Map.of(
                    "node", "trigger:my_webhook",
                    "mock", Map.of("output", Map.of("x", 1))));

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("data_inputs");
        }

        @Test
        @DisplayName("port on an mcp node is rejected at modify time by the real parser, naming the node")
        void portOnMcpRejected() {
            WorkflowBuilderSession session = sessionWithMcpNode();

            ToolExecutionResult result = modifier.executeModifyNode(session, Map.of(
                    "node", "Fetch Emails",
                    "mock", Map.of("port", "if")));

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("port").contains("mcp:fetch_emails");
            assertThat(mcpNode(session)).doesNotContainKey("mock");
        }

        @Test
        @DisplayName("decision port is validated against the node's REAL branches (unknown port lists valid ports)")
        void decisionPortValidated() {
            WorkflowBuilderSession session = createSession();
            Map<String, Object> decision = new LinkedHashMap<>();
            decision.put("id", "core:check_status");
            decision.put("type", "decision");
            decision.put("label", "Check Status");
            decision.put("decisionConditions", List.of(
                    new LinkedHashMap<>(Map.of("id", "c1", "type", "if", "expression", "x > 1")),
                    new LinkedHashMap<>(Map.of("id", "c2", "type", "else"))));
            session.getCores().add(decision);

            ToolExecutionResult bad = modifier.executeModifyNode(session, Map.of(
                    "node", "Check Status",
                    "mock", Map.of("port", "elseif_5")));
            assertThat(bad.success()).isFalse();
            assertThat(bad.error()).contains("elseif_5").contains("if");

            ToolExecutionResult good = modifier.executeModifyNode(session, Map.of(
                    "node", "Check Status",
                    "mock", Map.of("port", "if")));
            assertThat(good.success()).isTrue();
            assertThat((Map<String, Object>) data(good).get("mock"))
                    .containsEntry("kind", "port");
        }

        @Test
        @DisplayName("a non-object mock value is rejected with the shape guidance")
        void nonObjectRejected() {
            WorkflowBuilderSession session = sessionWithMcpNode();

            ToolExecutionResult result = modifier.executeModifyNode(session, Map.of(
                    "node", "Fetch Emails",
                    "mock", "just mock it"));

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("mock");
        }
    }
}
